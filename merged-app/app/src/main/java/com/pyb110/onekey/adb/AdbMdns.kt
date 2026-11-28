package com.pyb110.onekey.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无线调试的 mDNS 发现。
 *
 * 系统 adbd 在开启「无线调试」后会在回环上广播两个服务：
 *   _adb-tls-connect._tcp   已配对的连接端口（配对成功后用它跑命令）
 *   _adb-tls-pairing._tcp   配对端口（只在配对窗口内存在，配完即消失）
 *
 * 两个都是 127.0.0.1 上的随机端口，只能靠 mDNS 找，没有固定端口可猜。
 *
 * 实现要点：
 *   - NsdManager 的 resolveService 是全局串行的，同一时刻只允许一个在飞，
 *     并发调用会拿到 FAILURE_ALREADY_ACTIVE。这里用一条单线程队列串起来。
 *   - Android 14 起 onServiceFound 里的 host 可能为 null，必须 resolve 才有 host/port。
 *   - 发现是持续过程：配对端口会在用户点「使用配对码配对设备」之后才出现，
 *     所以这里用回调而非一次性查询。
 */
object AdbMdns {
    private const val TAG = "OneKeyAdbMdns"

    const val TLS_CONNECT = "_adb-tls-connect._tcp"
    const val TLS_PAIRING = "_adb-tls-pairing._tcp"

    /** 发现到的服务端点。 */
    data class Endpoint(val host: String, val port: Int, val name: String)

    /**
     * 开始发现。返回一个句柄，调用 [Handle.stop] 停止。
     *
     * [onFound] 会在每次解析出可用端点时回调（同一服务可能回调多次，调用方自行去重）。
     * [onError] 只在启动失败时回调，运行中的单次失败会被忽略并继续。
     */
    fun discover(
        context: Context,
        serviceType: String,
        onFound: (Endpoint) -> Unit,
        onError: (String) -> Unit = {},
    ): Handle {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            onError("系统没有 mDNS 服务")
            return Handle(null, null, AtomicBoolean(true))
        }
        val stopped = AtomicBoolean(false)
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "开始发现 " + serviceType)
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (stopped.get()) return
                if (info.serviceType != serviceType && !info.serviceType.startsWith(serviceType)) return
                resolveSerial(nsd, info, stopped, onFound)
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onError("mDNS 启动失败（错误码 " + errorCode + "）")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        return try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery)
            Handle(nsd, discovery, stopped)
        } catch (e: Exception) {
            onError("mDNS 启动异常：" + (e.message ?: e.javaClass.simpleName))
            Handle(null, null, AtomicBoolean(true))
        }
    }

    /**
     * 串行解析。
     *
     * NsdManager.resolveService 是进程级单例，两个同时飞必有一个 FAILURE_ALREADY_ACTIVE。
     * 这里不维护显式队列，而是失败后延迟重试 —— 发现阶段服务会持续广播，
     * 重试一定能拿到，比维护队列简单且不会因为漏一次回调而卡死。
     */
    private fun resolveSerial(
        nsd: NsdManager,
        info: NsdServiceInfo,
        stopped: AtomicBoolean,
        onFound: (Endpoint) -> Unit,
        attempt: Int = 0,
    ) {
        if (stopped.get() || attempt > 6) return
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (stopped.get()) return
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        // 另一条解析在飞，稍后重试同一条。
                        // 用 schedule 而不是 Thread.sleep：多设备同时广播时每个
                        // onServiceFound 都可能触发一轮重试，线程数会堆到 7 条，
                        // 而且 stop() 叫不醒 sleep 中的线程。
                        runCatching {
                            retryExecutor.schedule(
                                { if (!stopped.get()) resolveSerial(nsd, serviceInfo, stopped, onFound, attempt + 1) },
                                180L, java.util.concurrent.TimeUnit.MILLISECONDS,
                            )
                        }
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (stopped.get()) return
                    val host = hostOf(serviceInfo)
                    val port = serviceInfo.port
                    if (host.isNullOrBlank() || port <= 0) return
                    onFound(Endpoint(host, port, serviceInfo.serviceName ?: ""))
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "解析异常：" + (e.message ?: ""))
        }
    }

    private fun hostOf(info: NsdServiceInfo): String? {
        val h = info.host
        if (h != null) {
            val s = h.hostAddress
            if (!s.isNullOrBlank()) return s
        }
        // Android 14 的兜底：host 为 null 时尝试用 hostAddresses
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val all = info.hostAddresses
            for (a in all) {
                val s = a.hostAddress
                if (!s.isNullOrBlank()) return s
            }
        }
        return null
    }

    /** 单线程调度器，专门跑 resolveService 的延迟重试。 */
    private val retryExecutor: java.util.concurrent.ScheduledExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ok-adb-mdns-retry").apply { isDaemon = true }
        }
    }

    /** 发现句柄。 */
    class Handle internal constructor(
        private val nsd: NsdManager?,
        private val listener: NsdManager.DiscoveryListener?,
        private val stopped: AtomicBoolean,
    ) {
        /**
         * 停止发现。
         *
         * 只置 stopped 并 stopServiceDiscovery 就够了：resolveSerial 在每次
         * 进入与每次回调里都查 stopped，正在飞的 resolve 结果会被丢弃，
         * 排队中的重试也会在 schedule 回调里看到 stopped 而放弃。
         * 不去显式注销 ResolveListener —— NsdManager 没有这个 API，
         * 而 listener 是匿名对象，靠 stopped 短路比留引用去清更简单。
         */
        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            val n = nsd ?: return
            val l = listener ?: return
            runCatching { n.stopServiceDiscovery(l) }
        }
    }
}
