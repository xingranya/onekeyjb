package com.pyb110.onekey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机广播入口：只有用户开启「开机自动越狱」时才拉起前台服务。
 * 真正的等待与执行都在 [AutoJailbreakService] 里，广播本身不做耗时操作。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("merged", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_jailbreak", false)) return
        runCatching {
            context.startForegroundService(Intent(context, AutoJailbreakService::class.java))
        }
    }
}
