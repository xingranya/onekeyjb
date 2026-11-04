package com.pyb110.onekey

import dev.onekey.jb.ShizukuShell

/** 专门修复 ColorOS 截屏组件 com.oplus.exsystemservice。 */
object ScreenshotRepair {
    private const val PKG = "com.oplus.exsystemservice"

    data class Result(
        val rootReady: Boolean,
        val installed: Boolean,
        val enabled: Boolean,
        val output: String,
    )

    fun repair(log: (String) -> Unit): Result {
        log("截屏修复：检查 root")
        val root = ShizukuShell.run("su -c 'id -u' 2>&1", 15)
        val rootReady = root.output.lineSequence().any { it.trim() == "0" }
        if (!rootReady) {
            log("截屏修复：未取得 root，切换至 Stellar/Shizuku 权限执行修复")
        }
        log("截屏修复：检查系统组件")
        val path = ShizukuShell.run(if (rootReady) "su -c 'pm path " + PKG + "' 2>&1" else "pm path " + PKG + " 2>&1", 15)
        var installed = path.output.contains("package:")
        if (!installed) {
            log("截屏修复：组件未安装，尝试恢复系统应用")
            val ins = ShizukuShell.run(
                if (rootReady) "su -c 'cmd package install-existing --user 0 " + PKG + "' 2>&1"
                else "cmd package install-existing --user 0 " + PKG + " 2>&1", 30
            )
            log("截屏修复：" + ins.output.trim().take(200))
            installed = ShizukuShell.run(if (rootReady) "su -c 'pm path " + PKG + "' 2>&1" else "pm path " + PKG + " 2>&1", 15)
                .output.contains("package:")
        }
        if (!installed) {
            log("截屏修复：找不到 " + PKG)
            return Result(rootReady, false, false, path.output.trim())
        }
        log("截屏修复：启用组件")
        val enable = ShizukuShell.run(
            if (rootReady) "su -c 'pm enable --user 0 " + PKG + "' 2>&1" else "pm enable --user 0 " + PKG + " 2>&1", 20
        )
        log("截屏修复：" + enable.output.trim().take(200))
        val stateCmd = if (rootReady) "su -c 'pm list packages -e --user 0 | grep -x \"package:" + PKG + "\"' 2>&1"
            else "pm list packages -e --user 0 | grep -x \"package:" + PKG + "\" 2>&1"
        val state = ShizukuShell.run(stateCmd, 15)
        val enabled = state.output.contains("package:" + PKG)
        if (enabled) {
            // 让可能残留的旧进程退出，下一次截屏会重新拉起服务。
            ShizukuShell.run(
                if (rootReady) "su -c 'pkill -TERM -f " + PKG + "' 2>/dev/null; true"
                else "pkill -TERM -f " + PKG + " 2>/dev/null; am force-stop " + PKG + " 2>/dev/null; true", 15
            )
            log("截屏修复：组件已启用，请直接测试截屏")
        } else {
            log("截屏修复：启用失败，状态=" + state.output.trim().take(200))
        }
        return Result(rootReady, true, enabled, (enable.output + "\n" + state.output).trim())
    }
}
