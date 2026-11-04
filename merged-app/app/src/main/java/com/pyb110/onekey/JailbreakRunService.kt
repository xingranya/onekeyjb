package com.pyb110.onekey

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 运行期间的前台服务 + 部分唤醒锁。
 * 只负责保活，不执行 shell；执行仍由 Controller/Engine 负责。
 */
class JailbreakRunService : Service() {
    companion object {
        const val CHANNEL = "onekey_run_fluid"
        const val NOTIFICATION_ID = 0x4f4e
        const val ACTION_START = "com.pyb110.onekey.action.START"
        const val ACTION_UPDATE = "com.pyb110.onekey.action.UPDATE"
        const val ACTION_STOP = "com.pyb110.onekey.action.STOP"

        @Volatile private var liveStage = "准备"
        @Volatile private var liveProgress = 0

        fun start(ctx: Context) {
            liveStage = "准备"
            liveProgress = 0
            val i = Intent(ctx, JailbreakRunService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun update(ctx: Context, stage: String, progress: Float) {
            liveStage = stage
            liveProgress = (progress.coerceIn(0f, 1f) * 100f).toInt()
            val i = Intent(ctx, JailbreakRunService::class.java).setAction(ACTION_UPDATE)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, JailbreakRunService::class.java))
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val what = when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> "SCREEN_OFF"
                Intent.ACTION_SCREEN_ON -> "SCREEN_ON"
                else -> return
            }
            HistoryStore.append(what + "  t=" + System.currentTimeMillis())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "越狱运行状态", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "越狱运行阶段与进度"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWake()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWake()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val percent = liveProgress.coerceIn(0, 100)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setContentTitle("OneKey · " + liveStage)
            .setContentText(percent.toString() + "% · 请保持 Stellar 在线")
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setSubText("越狱进度")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .setContentIntent(pi)
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                val points = listOf(0, 25, 50, 75, 100).map { Notification.ProgressStyle.Point(it) }
                val style = Notification.ProgressStyle()
                    .setProgress(percent)
                    .setStyledByProgress(false)
                    .setProgressPoints(points)
                b.setStyle(style)
                b.setRequestPromotedOngoing(true)
                b.setShortCriticalText(liveStage.take(20))
            }
        }
        return b.build()
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "onekey:jailbreak").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
        wake = null
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        releaseWake()
        super.onDestroy()
    }
}
