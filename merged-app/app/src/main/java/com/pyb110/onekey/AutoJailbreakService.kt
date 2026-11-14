package com.pyb110.onekey

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 开机自动越狱：由 [BootReceiver] 拉起，全程无界面。
 *
 * 流程：崩溃守卫 → 等 Stellar 就绪 → 已有 root 则跳过 → 驱动 [JailbreakController] 跑一次 → 通知结果。
 * 执行期间置 [RunState.headless]，避免与手动路径的保活通知重复。
 */
class AutoJailbreakService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val first = notification("准备自动越狱…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, first)
        }
        val prefs = getSharedPreferences("merged", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_jailbreak", false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val power = getSystemService(PowerManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "onekey:auto-jailbreak")
        wake.acquire(10 * 60 * 1000L)
        scope.launch {
            try {
                runAuto()
            } catch (t: Throwable) {
                finishNotify("自动越狱异常：" + (t.message ?: t.javaClass.simpleName))
            } finally {
                RunState.headless = false
                runCatching { wake.release() }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runAuto() {
        val controller = JailbreakController.get(applicationContext)

        if (!controller.termsAccepted()) {
            finishNotify("尚未同意用户协议，请先打开应用完成首次设置")
            return
        }
        // 崩溃守卫：上次开机写入内核后仍失败，说明这台机器自动跑有风险，直接关开关。
        if (controller.lastBootFailed()) {
            controller.disableAutoJailbreakAfterFailure()
            finishNotify("上次自动越狱未成功，已自动关闭开关；请手动运行并查看日志")
            return
        }
        if (RunState.active) {
            finishNotify("已有越狱正在进行，本次跳过")
            return
        }

        updateNotification("等待 Stellar 服务就绪…")
        var ready = false
        for (i in 0 until 90) {
            if (StellarShell.available() && StellarShell.hasPermission()) { ready = true; break }
            if (i % 10 == 9) updateNotification("等待 Stellar 服务…（请确认已开启 Stellar 开机启动）")
            delay(2000)
        }
        if (!ready) {
            finishNotify("Stellar 未就绪，自动越狱已取消；请在 Stellar 里开启开机启动后重试")
            return
        }

        if (alreadyRooted()) {
            finishNotify("本次开机已越狱，跳过")
            return
        }

        updateNotification("自动越狱进行中…")
        RunState.headless = true
        controller.refreshState()
        controller.runJailbreak()
        // runJailbreak 是同步入口，实际执行在其 IO 协程里；这里观察状态刷通知。
        for (i in 0 until 20) {
            if (controller.running) break
            delay(200)
        }
        var guard = 0
        while (controller.running && guard < 600) {
            val pct = (controller.progress * 100).toInt().coerceIn(0, 100)
            updateNotification(controller.stage + "  " + pct + "%")
            delay(1000)
            guard++
        }
        RunState.headless = false

        if (controller.lastResultOk == true) {
            finishNotify("自动越狱成功，本次开机有效")
        } else {
            controller.disableAutoJailbreakAfterFailure()
            finishNotify("自动越狱未成功，已关闭开关；打开应用查看日志")
        }
    }

    private fun alreadyRooted(): Boolean = runCatching {
        StellarShell.run("su -c id 2>/dev/null; /system/bin/su -c id 2>/dev/null", 12)
            .output.contains("uid=0")
    }.getOrDefault(false)

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "开机自动越狱", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("OneKey 自动越狱")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun updateNotification(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 1500) return
        lastNotified = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun finishNotify(text: String) {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val done = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("OneKey 自动越狱")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, done)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private companion object {
        const val CHANNEL_ID = "auto_jailbreak"
        const val NOTIF_ID = 98261
    }
}
