package com.pyb110.onekey

import android.content.Context
import dev.onekey.jb.ShizukuShell

/** 同一时间只允许一个用户态重启请求，避免软重启循环。 */
object RestartManager {
    private const val PREF = "onuce_restart_boot"
    private const val MARK = "/data/local/tmp/onekey-softreboot.last"
    private const val MIN_INTERVAL = 300L
    private val BOOT_RE = Regex("^[0-9a-fA-F-]{16,64}$")

    fun request(context: Context, log: (String) -> Unit): Boolean {
        val bootRun = ShizukuShell.run("cat /proc/sys/kernel/random/boot_id 2>/dev/null", 10)
        val boot = bootRun.output.trim()
        if (bootRun.code != 0 || !BOOT_RE.matches(boot)) {
            log("读取 boot_id 失败，未请求重启: " + boot.take(80))
            return false
        }
        val gate = ShizukuShell.run(
            "now=" + "$" + "(date +%s); last=" + "$" + "(cat " + MARK + " 2>/dev/null || echo 0); " +
                "if [ " + "$" + "((now-last)) -lt " + MIN_INTERVAL + " ]; then echo BUSY; " +
                "else echo " + "$" + "now > " + MARK + "; echo OK; fi", 15
        ).output.trim()
        if (gate != "OK") {
            log("5 分钟内已请求过用户态重启，本次请求已阻止")
            return false
        }
        val prefs = context.getSharedPreferences("merged", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF, boot).apply()
        log("已请求一次用户态重启（内核模块保持加载）")
        val d = "$"
        for (su in listOf("/debug_ramdisk/su", "/system/bin/su", "/system_ext/bin/su", "/data/adb/ksu/bin/su")) {
            val cmd = "if [ -x '" + su + "' ]; then out=$(" + d + "(" + su +
                " -c 'timeout 20 /data/adb/ksud soft-reboot' 2>&1); rc=" + d +
                "?; echo \"" + su + " rc=" + d + "rc\"; exit " + d + "rc; fi"
            val r = ShizukuShell.run(cmd, 60)
            log("用户态重启输出: " + r.output.trim().take(400))
            if (r.code == 0) return true
        }
        log("用户态重启请求失败，已阻止重复；5 分钟后可再次尝试")
        return false
    }
}
