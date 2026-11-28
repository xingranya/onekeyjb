package com.pyb110.onekey.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.pyb110.onekey.LogEntry
import com.pyb110.onekey.R
import com.pyb110.onekey.RunLog
import com.pyb110.onekey.shell.OneKeyShell
import com.pyb110.onekey.shell.ShellSecrets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无线调试配对的前台服务。
 *
 * 为什么必须是服务 + 通知栏输入，而不是 App 里弹个输入框：
 *
 *   配对端口只在「设置 → 无线调试 → 使用配对码配对设备」这个对话框可见期间存活。
 *   一旦把设置切到后台（哪怕是按 HOME 键），配对端口立刻关闭，
 *   拿到的 6 位码也就没用了 —— 这是 adbd 的设计，不是权限问题。
 *
 *   所以正确的做法是：用户把设置页留在前台，从通知栏拉下来直接回一条配对码。
 *   全程不离开设置页，配对端口就一直活着。
 *
 * 流程：
 *   1. 找配对端口（mDNS），找不到就发一条「请先打开配对对话框」的提示
 *   2. 发一条带 RemoteInput 回复动作的通知
 *   3. 用户回码 → 配对 → 找连接端口 → 跑 start.sh → 等自研通道就绪
 */
class AdbPairingService : Service() {

    private val busy = AtomicBoolean(false)

    /** 正在跑的线程，onDestroy 时打断，避免服务已销毁而线程仍持有 this。 */
    private val workers = java.util.Collections.synchronizedList(mutableListOf<Thread>())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 线程可能正阻塞在 waitEndpoint 的 latch.await 或 shellCapture 的读上。
        // 不打断的话服务对象会被线程引用住，report() 还会继续往 filesDir 写日志。
        synchronized(workers) {
            workers.forEach { runCatching { it.interrupt() } }
            workers.clear()
        }
        super.onDestroy()
    }

    private fun worker(name: String, body: () -> Unit) {
        val t = Thread(body, name)
        workers.add(t)
        t.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        startAsForeground(promptNotification("正在查找配对端口…"))

        if (intent?.action == ACTION_SUBMIT_CODE) {
            val code = readReply(intent)
            if (code.isNullOrBlank()) {
                finish("配对码为空，已取消")
                return START_NOT_STICKY
            }
            runPairing(code)
            return START_NOT_STICKY
        }

        // 普通启动：先找端口，再发带输入框的通知
        worker("ok-adb-pair-find") {
            val port = AdbBootstrap.findPairingPort(this)
            if (port <= 0) {
                finish("没找到配对端口，请先在开发者选项里打开「使用配对码配对设备」")
                return@worker
            }
            // 找到端口后**继续留在前台**。
            // 原来这里 stopForeground(DETACH) 让服务降为 cached，然后干等用户输入配对码
            // （可能几分钟）。Android 13+ 的 cached 进程随时被冻结/杀死，进程一死
            // AdbBootstrap.state 这个静态字段就没了，用户回投配对码时读到 pairPort=0，
            // 只能从头再来。保持 FOREGROUND_SERVICE_TYPE_SPECIAL_USE 到配对结束才安全。
            post(promptNotification("配对端口 " + port + " 已就绪，请回复设备上显示的 6 位配对码"))
        }
        return START_NOT_STICKY
    }

    /** 拿到配对码后的全部动作。 */
    private fun runPairing(code: String) {
        if (!busy.compareAndSet(false, true)) {
            // 用户连点两次「发送」，或系统重复投递 ACTION_SUBMIT_CODE 时会走到这里。
            // 不能静默 return —— 那会让通知停在「正在查找配对端口…」这个错的状态，
            // 而真正的配对还在跑，用户以为没反应。
            post(promptNotification("正在配对中，请稍候…"))
            return
        }
        worker("ok-adb-pair-run") {
            try {
                val port = AdbBootstrap.state.pairPort
                if (port <= 0) {
                    finish("配对端口已失效，请重新打开「使用配对码配对设备」")
                    return@worker
                }
                post(promptNotification("正在配对…"))
                val ok = AdbBootstrap.completePairing(this, port, code) { entry -> report(entry) }
                finish(if (ok) "Shell 通道已就绪" else "配对未完成，请看应用日志")
            } finally {
                busy.set(false)
            }
        }
    }

    // ---- 通知 ----

    /**
     * 带输入框的通知。
     *
     * RemoteInput 是唯一能在「不离开设置页」的前提下拿到用户输入的手段：
     * 回复动作会把输入内容塞进一个新的 Intent 回投给本服务。
     */
    private fun promptNotification(text: String): Notification {
        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("配对码")
            .setAllowFreeFormInput(true)
            .build()

        // FLAG_MUTABLE 是 RemoteInput 的硬要求（系统要往 Intent 里塞回复内容）。
        // 显式 Intent + exported=false 兜住了 fill-in 面，不需要再叠
        // FLAG_ACTIVITY_NEW_TASK / FLAG_GRANT_READ_URI_PERMISSION —— 那两个对
        // getService 目标是空转。
        val reply = PendingIntent.getService(
            this,
            REQ_REPLY,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_SUBMIT_CODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val action = NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher, "输入配对码", reply
        ).addRemoteInput(remoteInput).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("OneKey 无线调试配对")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(action)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun readReply(intent: Intent): String? {
        val results: Bundle = RemoteInput.getResultsFromIntent(intent) ?: return null
        return results.getCharSequence(KEY_CODE)?.toString()?.trim()
    }

    private fun startAsForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun post(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    /** 收尾：换成一条可点的普通通知，然后退出。 */
    private fun finish(text: String) {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent().setClassName(packageName, "com.pyb110.onekey.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val done = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("OneKey 无线调试配对")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, done)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun report(entry: LogEntry) {
        // 服务里拿不到 controller 实例，直接落到文件，
        // 用户打开应用时就能在日志页看到这一步发生了什么。
        runCatching {
            val f = java.io.File(filesDir, "onekey-live.log")
            // 轮转：配对/重连每次开机至少一条，异常栈单条可达数百字节，
            // 长期不轮转会慢慢吃满 /data。
            if (f.length() > 512 * 1024) {
                val old = java.io.File(filesDir, "onekey-live.log.1")
                runCatching { old.delete() }
                runCatching { f.renameTo(old) }
            }
            f.appendText(entry.format() + "\n")
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "无线调试配对", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                setShowBadge(true)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "adb_pairing"
        private const val NOTIF_ID = 98262
        private const val KEY_CODE = "pairing_code"
        private const val REQ_REPLY = 1
        private const val ACTION_SUBMIT_CODE = "com.pyb110.onekey.adb.SUBMIT_CODE"

        /** 启动配对流程。 */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, AdbPairingService::class.java))
            }.onFailure {
                runCatching { context.startService(Intent(context, AdbPairingService::class.java)) }
            }
        }

        /** 自研通道是否已就绪（配对成功后由 UI 轮询）。 */
        fun ready(): Boolean = OneKeyShell.available()

        /** 启动脚本路径，排障用。 */
        fun scriptPath(context: Context): String = ShellSecrets.scriptFile(context).absolutePath
    }
}
