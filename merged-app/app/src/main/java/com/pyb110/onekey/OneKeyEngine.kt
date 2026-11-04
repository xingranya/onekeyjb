package com.pyb110.onekey

import android.content.Context
import dev.onekey.jb.ShizukuShell

/**
 * 98260 执行引擎桥：当 APK 内提供 core/exec/onekey-98260 时，按实测 env 协议驱动它。
 * 协议：PYB110_DIR / PYB110_MANAGER(短名) / PYB110_LATE_LOAD / PYB110_SUSFS /
 *       PYB110_VFS / PYB110_UNLOAD_GUARD / PYB110_DISABLE_ANTIROOT。
 */
object OneKeyEngine {
    private const val ROOT = "/data/local/tmp/pyb110-onekey"

    fun available(context: Context): Boolean = try {
        context.assets.open("core/exec/onekey-98260").close(); true
    } catch (e: Throwable) { false }

    fun run(
        context: Context,
        managerKind: String,
        log: (String) -> Unit,
        stage: (String) -> Unit,
        progress: (Int) -> Unit,
        susfs: Boolean,
        vfs: Boolean,
        unloadGuard: Boolean,
        disableAntiRoot: Boolean,
        softReboot: Boolean,
    ): Boolean {
        val ws = ROOT + "-" + System.currentTimeMillis()
        if (!ShizukuShell.run("mkdir -p " + ws + " && chmod 700 " + ws + " && echo OK", 20).output.contains("OK")) {
            log("工作目录创建失败"); return false
        }
        if (ShizukuShell.stageAsset(context, "core/exec/onekey-98260", ws + "/onekey-98260", 755).code != 0) {
            log("执行引擎投放失败"); return false
        }
        if (susfs) ShizukuShell.stageAsset(context, "core/exec/susfs_guard_lkm.ko", ws + "/susfs_guard_lkm.ko", 644)
        if (vfs) ShizukuShell.stageAsset(context, "core/exec/hybridmount-6.18.21.ko", ws + "/hybridmount-6.18.21.ko", 644)
        val k = when (managerKind) {
            "kernelsu" -> "kernelsu"
            "next" -> "next"
            "sukisu" -> "sukisu"
            "bakasu" -> "bakasu"
            else -> "finasu"
        }
        val env = "PYB110_DIR=" + ws + " PYB110_MANAGER=" + k + " PYB110_LATE_LOAD=1" +
            " PYB110_SUSFS=" + (if (susfs) "1" else "0") +
            " PYB110_VFS=" + (if (vfs) "1" else "0") +
            " PYB110_UNLOAD_GUARD=" + (if (unloadGuard) "1" else "0") +
            " PYB110_DISABLE_ANTIROOT=" + (if (disableAntiRoot) "1" else "0") +
            " PYB110_SOFT_REBOOT=" + (if (softReboot) "1" else "0") +
            " PYB110_REBOOT=" + (if (softReboot) "1" else "0")
        val cmd = "cd " + ws + " && " + env + " ./onekey-98260 run 2>&1"
        log("执行引擎启动（manager=" + k + "）")
        var ok = false
        try {
            val proc = ShizukuShell.start(cmd) { raw ->
                var line = raw.trim()
                if (line.isEmpty()) return@start
                // 外部引擎的标识串不外泄到我们的日志
                line = line.replace("PYB110_", "").replace("t98260", "98260-engine")
                when {
                    line.startsWith("[1/4]") -> { stage("布置提权原语"); progress(35) }
                    line.startsWith("[2/4]") -> { stage("加载内核模块"); progress(65) }
                    line.startsWith("[3/4]") -> { stage("等待 su"); progress(80) }
                    line.startsWith("[4/4]") -> { stage("管理器收尾"); progress(90) }
                    line == "MODULE_LOADED" -> log("内核模块已加载")
                    line.startsWith("SU_ID_RC=") -> log("su 校验：" + line)
                    line == "DONE" -> { ok = true; progress(100) }
                    line.startsWith("KSUD_MISSING") || line.contains("FAIL") -> RunReport.stop("执行器", line.take(120))
                    else -> if (line.length in 3..200) log("  " + line)
                }
            }
            val deadline = System.currentTimeMillis() + 180_000
            while (proc.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(300)
            if (proc.isRunning()) { proc.destroy(); log("引擎超时，已终止") }
        } catch (e: Throwable) {
            log("引擎异常：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            ShizukuShell.run("rm -rf " + ws + "; true", 10)
        }
        if (ok) {
            val check = ShizukuShell.run("timeout 5 /system/bin/su -c id 2>&1", 10).output
            ok = check.contains("uid=0")
            if (ok) RunReport.markWrote("加载内核模块")
        }
        return ok
    }
}
