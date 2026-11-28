package com.pyb110.onekey.adb

import android.content.Context
import android.provider.Settings
import com.pyb110.onekey.RunLog
import com.pyb110.onekey.shell.OneKeyShell
import com.pyb110.onekey.shell.ShellSecrets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无线调试引导（L2）。
 *
 * 这是「没有任何 root、也没装 Stellar」时唯一的入口。
 * 完整链路：
 *   1. 打开无线调试开关（Settings.Global.adb_wifi_enabled = 1，需 WRITE_SECURE_SETTINGS）
 *   2. mDNS 找到 _adb-tls-pairing._tcp 的端口
 *   3. 用 SPAKE2 配对（需要用户抄设备上显示的 6 位码）
 *   4. mDNS 找到 _adb-tls-connect._tcp 的端口
 *   5. 用刚配对的那把密钥做 TLS 客户端证书，跑 start.sh 把自研 shell 拉起来
 *
 * 第 3 步是整个流程里唯一必须人工介入的地方 —— 配对码只显示在设备屏幕上，
 * 没有 API 能读到它。所以这里把它做成一个「等用户输入」的挂起状态，
 * UI 那边弹一个带输入框的通知/弹窗，用户抄完码点确定，这里继续。
 *
 * 配对完成后这把密钥就永久被 adbd 记住（落在 /data/misc/adb/adb_keys），
 * 之后每次开机只要无线调试还开着，第 4、5 步可以全自动完成，不用再输码。
 */
object AdbBootstrap {
    /**
     * adbd 的配对端口与连接端口都只监听回环。
     *
     * mDNS 报出来的地址可能是设备自己的局域网 IP，走它要过一遍防火墙；
     * 用 127.0.0.1 直接命中，也避免某些机型在 Wi-Fi 关闭时 mDNS 地址不可达。
     */
    private const val LOOPBACK = "127.0.0.1"

    /** 引导阶段，供 UI 显示。 */
    enum class Phase(val label: String) {
        IDLE("未开始"),
        ENABLING("正在打开无线调试"),
        FINDING_PAIR("正在查找配对端口"),
        WAIT_CODE("等待输入配对码"),
        PAIRING("正在配对"),
        FINDING_CONNECT("正在查找连接端口"),
        CONNECTING("正在连接 adbd"),
        LAUNCHING("正在启动 Shell 通道"),
        DONE("已完成"),
        FAILED("失败"),
    }

    /** 当前状态快照。 */
    data class State(
        val phase: Phase = Phase.IDLE,
        val detail: String = "",
        val pairPort: Int = 0,
        val connectPort: Int = 0,
        val needCode: Boolean = false,
    )

    @Volatile
    var state: State = State()
        private set

    @Volatile
    private var running = AtomicBoolean(false)

    private fun set(phase: Phase, detail: String = "", pairPort: Int = 0, connectPort: Int = 0, needCode: Boolean = false) {
        state = State(phase, detail, pairPort, connectPort, needCode)
    }

    /** 无线调试开关当前是否打开。 */
    fun wirelessEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") == 1
    }.getOrDefault(false)

    /**
     * 尝试打开无线调试。
     *
     * WRITE_SECURE_SETTINGS 是 signature|privileged 权限，普通 App 拿不到，
     * 只能靠 `adb shell pm grant` 或已 root 时补授（见 ShellBootstrap 的 L1 分支）。
     * 拿不到就直接返回 false，让上层提示用户手动去开发者选项里开。
     */
    fun enableWireless(context: Context): Boolean {
        if (wirelessEnabled(context)) return true
        return runCatching {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
            wirelessEnabled(context)
        }.getOrDefault(false)
    }

    /**
     * 只做第一步：打开无线调试并等配对端口出现。
     *
     * 拆成两段是因为中间必须停下来问用户要配对码 —— 阻塞式一条龙
     * 会让 UI 卡在一个没法显示输入框的调用栈里。
     *
     * 返回配对端口，0 表示没找到。阻塞，必须在后台线程调用。
     */
    fun findPairingPort(context: Context): Int {
        if (!enableWireless(context)) {
            // WRITE_SECURE_SETTINGS 是 signature|privileged，普通安装永远拿不到，
            // 只有 root 后 pm grant 或 su 补授才生效。所以这里不能只说「没有权限」——
            // 用户照着去开发者选项手动打开无线调试就能过，必须把这个下一步写出来。
            set(
                Phase.FAILED,
                "无法自动打开无线调试（缺少 WRITE_SECURE_SETTINGS，需 root 补授一次）；" +
                    "请手动到「开发者选项 → 无线调试」打开后重试",
            )
            return 0
        }
        set(Phase.FINDING_PAIR, "正在查找配对端口")
        val pair = waitEndpoint(context, AdbMdns.TLS_PAIRING, 20_000L)
        if (pair == null) {
            set(Phase.FAILED, "没找到配对端口")
            return 0
        }
        set(Phase.WAIT_CODE, "等待输入配对码", pairPort = pair.port, needCode = true)
        return pair.port
    }

    /**
     * 拿到配对码后完成剩余步骤：配对 → 找连接端口 → 连上去跑 start.sh → 等通道就绪。
     *
     * 阻塞，必须在后台线程调用。
     */
    fun completePairing(
        context: Context,
        pairPort: Int,
        code: String,
        log: (com.pyb110.onekey.LogEntry) -> Unit = {},
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            if (pairPort <= 0) {
                set(Phase.FAILED, "配对端口无效")
                return false
            }
            if (code.isBlank()) {
                set(Phase.FAILED, "已取消配对")
                return false
            }

            // 1. 配对
            set(Phase.PAIRING, "正在配对", pairPort = pairPort)
            val key = AdbKey.get(context)
            val paired = AdbPairing.pair(LOOPBACK, pairPort, code.trim(), key)
            if (!paired.ok) {
                set(Phase.FAILED, "配对失败：" + paired.reason)
                log(RunLog.error("配对失败", paired.reason))
                return false
            }
            log(RunLog.success("配对成功", "密钥 " + key.fingerprint))

            // 2. 找连接端口（配对端口此时已消失，连接端口随即出现）
            set(Phase.FINDING_CONNECT, "正在查找连接端口")
            val conn = waitEndpoint(context, AdbMdns.TLS_CONNECT, 20_000L)
            if (conn == null) {
                set(Phase.FAILED, "没找到连接端口，请确认「无线调试」仍开着")
                log(RunLog.warn("没找到连接端口", "请确认「无线调试」仍开着"))
                return false
            }

            // 3. 连上去跑 start.sh
            val script = ShellSecrets.scriptFile(context)
            if (!script.isFile) {
                set(Phase.FAILED, "启动脚本缺失")
                return false
            }
            set(Phase.LAUNCHING, "正在启动 Shell 通道", connectPort = conn.port)
            val client = AdbClient(LOOPBACK, conn.port, key)
            try {
                client.connect()
                val out = client.shellCapture("sh " + shellQuote(script.absolutePath))
                log(RunLog.info("adbd 回执", out.trim().take(200).ifBlank { "（空）" }))
            } finally {
                client.close()
            }

            // 4. 等自研通道起来
            val ok = waitShell(8_000L)
            if (ok) {
                set(Phase.DONE, "Shell 通道已就绪")
                log(RunLog.success("Shell 通道已就绪", "自研通道已通过无线调试启动"))
            } else {
                set(Phase.FAILED, "adbd 已连上，但 Shell 通道没起来")
                log(RunLog.warn("Shell 通道未起来", "adbd 已连上但服务端没有响应，请重试"))
            }
            return ok
        } catch (e: Exception) {
            // 异常类名比 message 更能定位问题：SSLException 与 SocketException
            // 的 message 都可能是 "connection closed"，但含义完全不同。
            // 栈也一并带上：TLS 握手失败的根因经常藏在 cause 里。
            val cause = generateSequence(e.cause) { it.cause }.take(3)
                .joinToString(" <- ") { it.javaClass.simpleName + ":" + (it.message ?: "") }
            val what = e.javaClass.simpleName + ": " + (e.message ?: "（无消息）") +
                (if (cause.isBlank()) "" else " | " + cause)
            set(Phase.FAILED, what)
            log(RunLog.error("无线调试配对异常", what))
            return false
        } finally {
            running.set(false)
        }
    }

    /**
     * 只走第 4、5 步（已配对过的机器）。
     *
     * 开机后如果无线调试还开着、密钥也还在 adb_keys 里，这条路径不需要任何人工介入。
     */
    fun reconnect(context: Context, log: (String) -> Unit = {}): Boolean {
        if (!wirelessEnabled(context)) return false
        // 与 completePairing 互斥。两条路径都会 AdbKey.get() 与 AdbClient 连 adbd，
        // 并发跑除了抢同一把密钥，还会让 AdbPairingService 的 busy 语义错乱。
        if (!running.compareAndSet(false, true)) {
            log("无线调试：已有一次引导在进行，跳过本次重连")
            return false
        }
        try {
            return reconnectLocked(context, log)
        } finally {
            running.set(false)
        }
    }

    private fun reconnectLocked(context: Context, log: (String) -> Unit): Boolean {
        set(Phase.FINDING_CONNECT, "正在查找连接端口")
        val conn = waitEndpoint(context, AdbMdns.TLS_CONNECT, 12_000L)
        if (conn == null) {
            set(Phase.FAILED, "没找到连接端口")
            log("无线调试：没找到 _adb-tls-connect._tcp，可能还没开或刚打开")
            return false
        }
        val script = ShellSecrets.scriptFile(context)
        if (!script.isFile) {
            set(Phase.FAILED, "启动脚本缺失")
            log("无线调试：启动脚本缺失")
            return false
        }
        return runCatching {
            set(Phase.CONNECTING, "正在连接 adbd", connectPort = conn.port)
            log("无线调试：连接 127.0.0.1:" + conn.port)
            val client = AdbClient(LOOPBACK, conn.port, AdbKey.get(context))
            try {
                client.connect()
                val out = client.shellCapture("sh " + shellQuote(script.absolutePath))
                log("adbd 回执：" + out.trim().take(160).ifBlank { "（空）" })
            } finally {
                client.close()
            }
            set(Phase.LAUNCHING, "正在启动 Shell 通道", connectPort = conn.port)
            val ok = waitShell(8_000L)
            set(if (ok) Phase.DONE else Phase.FAILED, if (ok) "Shell 通道已就绪" else "Shell 通道没起来")
            ok
        }.getOrElse { e ->
            val raw = e.message ?: e.javaClass.simpleName
            set(Phase.FAILED, raw)
            // adbd 在 TLS 握手完成后立刻断开，只有一种原因：这把密钥不在 adb_keys 里。
            // 这不是异常，是「还没配对过」，得引导用户走一次配对码流程。
            val hint = when {
                raw.contains("closed", true) || raw.contains("reset", true) ->
                    "adbd 拒绝了这把密钥（还没配对过），需要走一次配对码流程"
                raw.contains("timed out", true) ->
                    "adbd 没有响应，无线调试可能刚打开还没就绪"
                else -> raw
            }
            log("无线调试异常：" + hint)
            false
        }
    }

    /**
     * 等一个**真正可连**的 mDNS 端点。
     *
     * 不能拿到第一个回调就走：adbd 每次重启（开关无线调试、配对成功、进程被杀）
     * 都会换一个新端口，而 NsdManager 的 SRV 记录有缓存，会把上一次的旧端口
     * 再报一遍。拿着旧端口去连，表现就是 TLS 握手刚成功 adbd 就断开
     * （"New TLS connection" 紧跟 "read failed"），或者干脆 EOFException。
     *
     * 所以这里对每个候选端点做一次 TCP 探测，只有连得上的才算数。
     *
     * **配对端口是例外，绝不能探测。** AOSP 的 pairing_server 每次 accept 都算一次
     * 尝试配额（constants.h 里 kMaxPairingAttempts = 20），连上即断也算：探测建连后
     * 立刻关闭，服务端会走 SetupTlsConnection 失败 → NotifyResult(nullptr) → num_tries++。
     * 累计 20 次后配对服务器直接关掉，用户面对的是「配对码正确但永远失败」，
     * 只能关无线调试重开。而用户点「配对」、mDNS 报旧端口、反复重试都会烧配额，
     * 触发频率比想象中高。配对端口存在即有效，真正的连接由 AdbPairing.pair() 自己建立。
     */
    private fun waitEndpoint(context: Context, type: String, timeoutMs: Long): AdbMdns.Endpoint? {
        val holder = java.util.concurrent.atomic.AtomicReference<AdbMdns.Endpoint?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        val probe = type != AdbMdns.TLS_PAIRING
        val handle = AdbMdns.discover(context, type, onFound = { ep ->
            if (holder.get() != null) return@discover
            if ((!probe || portAlive(ep.port)) && holder.compareAndSet(null, ep)) latch.countDown()
        })
        try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } finally {
            handle.stop()
        }
        return holder.get()
    }

    /** POSIX shell 单引号转义。路径里出现单引号时不能直接拼。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 端口是否真的有人在听。 */
    private fun portAlive(port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(LOOPBACK, port), 400)
            true
        }
    }.getOrDefault(false)

    /**
     * 等服务端自证就绪。
     *
     * 不能直接轮询 OneKeyShell.probe()：它内部走文件队列时单次最长阻塞 3 秒，
     * 8 秒窗口理论上 32 次轮询实际只剩 2~3 次，而服务端 JVM 冷启动就要 300~600 ms，
     * 首次探测很可能在 bind 之前就超时，后续两次也未必赶上。
     *
     * 所以先看抽象 socket 节点是否出现（start.sh 自己也是这么判的），
     * 出现了再走一次真正的 probe 确认能执行命令。
     */
    private fun waitShell(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val name = runCatching {
            val ctx = com.pyb110.onekey.shell.ShellRuntime.appContext ?: return@runCatching null
            com.pyb110.onekey.shell.ShellSecrets.socketName(ctx)
        }.getOrNull()
        while (System.currentTimeMillis() < deadline) {
            if (name != null && socketNodeExists(name)) return OneKeyShell.probe()
            if (OneKeyShell.probe()) return true
            Thread.sleep(250L)
        }
        return false
    }

    /** /proc/net/unix 里是否已有这个抽象 socket。读不到就返回 false（交给 probe 兜底）。 */
    private fun socketNodeExists(name: String): Boolean = runCatching {
        java.io.File("/proc/net/unix").readLines().any { it.endsWith("@" + name) }
    }.getOrDefault(false)
}
