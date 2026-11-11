package com.pyb110.onekey

import dev.onekey.jb.ShizukuShell

/** Stops ColorOS security components for the session; restoring is explicit, never automatic. */
object Safer {
    private val PKGS = listOf(
        "com.coloros.securityguard",
        "com.oplus.safecenter",
    )
    private var lastSaved: List<Pair<String, String>> = emptyList()

    private fun enabledState(p: String): String? {
        val d = ShizukuShell.run("pm list packages -d 2>/dev/null", 15)
        val e = ShizukuShell.run("pm list packages -e 2>/dev/null", 15)
        if (d.code != 0 || e.code != 0) return null
        val key = "package:" + p
        val disabled = d.output.lineSequence().map { it.trim() }.any { it == key }
        val enabled = e.output.lineSequence().map { it.trim() }.any { it == key }
        return when {
            disabled -> "d"
            enabled -> "e"
            else -> "-"
        }
    }

    fun disable(log: (String) -> Unit): List<Pair<String, String>> {
        val saved = ArrayList<Pair<String, String>>()
        for (p in PKGS) {
            val state = enabledState(p)
            if (state == null) {
                log("安全组件状态未知，跳过 $p")
                continue
            }
            saved.add(p to state)
            // 只停进程、不改包状态：pm disable-user 会改 PackageManager 数据，
            // 在 ColorOS 上可能连带 system_server 重启，看起来像"自动软重启"。
            val r = ShizukuShell.run("am force-stop $p 2>&1", 15)
            log("安全组件已停止 $p：" + r.output.trim().take(120))
        }
        val antiRoot = ShizukuShell.run("stop riskdetect 2>&1", 15)
        log("反 root 弹窗抑制：stop riskdetect rc=" + antiRoot.code)
        lastSaved = saved
        return saved
    }

    /** Explicit restore only; never auto-run during the load window. */
    fun restoreLast(log: (String) -> Unit): Boolean {
        val saved = lastSaved.ifEmpty {
            // After a reboot the in-memory record is gone. A manual restore
            // still needs to work, so restore the known packages to enabled.
            PKGS.map { it to "e" }
        }
        for ((p, state) in saved) {
            if (state == "-") continue
            val cmd = if (state == "e") "pm enable --user 0 $p" else "pm disable-user --user 0 $p"
            val r = ShizukuShell.run(cmd + " 2>&1", 20)
            log("安全组件恢复 $p: " + r.output.trim().take(120))
        }
        // Screenshot/system service must never stay disabled. It is not part
        // of PKGS, but older builds may have left it disabled.
        val shot = ScreenshotRepair.repair(log)
        log(if (shot.enabled) "截屏组件已恢复" else "截屏组件恢复失败：" + shot.output.take(160))
        lastSaved = emptyList()
        return true
    }
}
