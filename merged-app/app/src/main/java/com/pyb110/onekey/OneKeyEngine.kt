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
    /**
     * 成功标志必须即时落盘：引擎在拿到 root 后会立刻软重启，
     * 进程往往来不及走到 finally，导致"成功了却显示未越狱"。
     */
    private fun markRootedNow(context: Context) {
        val boot = runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")
        if (boot.isBlank()) return
        runCatching {
            context.getSharedPreferences("merged", Context.MODE_PRIVATE).edit()
                .putString("success_boot_id", boot)
                .remove("failed_boot_id")
                .commit()
        }
    }

    /** 本次运行后内核是否暴露了 /proc/kernelsu_manager。 */
    @Volatile var hotSwitchReady: Boolean = false
        private set

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
        useUniversalModule: Boolean,
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
        // 通用多管理器模块：只有它能提供 /proc/kernelsu_manager（管理器自带的 LKM 是单管理器构建）。
        var universal = false
        if (useUniversalModule &&
            ShizukuShell.stageAsset(context, "core/exec/ksud-universal", ws + "/ksud-universal", 755).code == 0) {
            // 包装脚本接管 late-load：--kmi 被拒绝时自动降级，并回报节点是否出现。
            val d = "$"
            val wrapper = listOf(
                "#!/system/bin/sh",
                "PKG=\"\"",
                "while [ " + d + "# -gt 0 ]; do",
                "  case \"" + d + "1\" in",
                "    --package-name) PKG=\"" + d + "2\"; shift 2 ;;",
                "    *) shift ;;",
                "  esac",
                "done",
                "K=\"" + ws + "/ksud-universal\"",
                "\"" + d + "K\" late-load --kmi android17-6.18 --allow-shell --package-name \"" + d + "PKG\"",
                "RC=" + d + "?",
                "if [ \"" + d + "RC\" -ne 0 ]; then",
                "  \"" + d + "K\" late-load --allow-shell --package-name \"" + d + "PKG\"",
                "  RC=" + d + "?",
                "fi",
                "if [ -f /proc/kernelsu_manager ]; then echo UNIVERSAL_NODE_OK; echo OK > \"" + ws + "/universal-result\"; else echo UNIVERSAL_NODE_MISSING; echo MISSING > \"" + ws + "/universal-result\"; fi",
                "exit " + d + "RC"
            ).joinToString("\n")
            val quoted = "'" + wrapper.replace("'", "'\\''") + "'"
            val staged = ShizukuShell.run(
                "printf '%s\\n' " + quoted + " > " + ws + "/ksud-wrapper && chmod 755 " + ws + "/ksud-wrapper && echo WOK",
                20
            )
            universal = staged.output.contains("WOK")
            if (!universal) log("通用模块包装脚本写入失败，回退到管理器自带模块")
        }
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
            " PYB110_REBOOT=" + (if (softReboot) "1" else "0") +
            (if (universal) " PYB110_KSUD=" + ws + "/ksud-wrapper" else "")
        val cmd = "cd " + ws + " && " + env + " ./onekey-98260 run 2>&1"
        log("执行引擎启动（manager=" + k + "，" + (if (universal) "通用多管理器模块" else "管理器自带模块") + "）")
        var ok = false
        var abortReason: String? = null
        var sawPermissive = false
        var failedStageSignals = 0
        try {
            val proc = ShizukuShell.start(cmd) { raw ->
                var line = raw.trim()
                if (line.isEmpty()) return@start
                // 外部引擎的标识串不外泄到我们的日志
                line = line.replace("PYB110_", "").replace("t98260", "98260-engine")
                if (line.contains("SELINUX=permissive")) sawPermissive = true
                // 竞态没落地时引擎会继续盲写并反复重试，这是内核被拖挂的直接原因：立即中止。
                // 一次尝试会同时打出 HIT=0 与 rc=-1，只按 rc 行计一次失败，避免把单次失败当成两次。
                if (abortReason == null && line.contains("rc=-1")) {
                    failedStageSignals += 1
                    if (failedStageSignals >= 4) {
                        abortReason = "连续 " + failedStageSignals + " 次尝试未成功"
                        RunReport.stop("布置提权原语", abortReason!!)
                        log("已连续失败 " + failedStageSignals + " 次，中止以避免内核进入不稳定状态")
                    } else {
                        log("第 " + failedStageSignals + " 次尝试未命中，继续重试")
                    }
                }
                when {
                    line.startsWith("[1/4]") -> { stage("布置提权原语"); progress(35) }
                    line.startsWith("[2/4]") -> { stage("加载内核模块"); progress(65) }
                    line.startsWith("[3/4]") -> { stage("等待 su"); progress(80) }
                    line.startsWith("[4/4]") -> { stage("管理器收尾"); progress(90) }
                    line == "MODULE_LOADED" -> { markRootedNow(context); log("内核模块已加载") }
                    line.startsWith("SU_ID_RC=0") -> { markRootedNow(context); log("su 校验：" + line) }
                    line.startsWith("SU_ID_RC=") -> log("su 校验：" + line)
                    line.contains("UNIVERSAL_NODE_OK") -> log("通用模块已加载，热切换节点就绪")
                    line.contains("UNIVERSAL_NODE_MISSING") -> log("通用模块未能提供热切换节点")
                    line == "DONE" -> { ok = true; progress(100) }
                    line.startsWith("KSUD_MISSING") -> {
                        RunReport.stop("执行器", "管理器 APK 里没有 libksud.so")
                        log("该管理器把加载器改了名；完成一次越狱后本应用会自动归一化，或改用其他管理器先越狱")
                    }
                    line.contains("FAIL") -> RunReport.stop("执行器", line.take(120))
                    else -> if (line.length in 3..200) log("  " + line)
                }
            }
            // 首次失败就停：引擎自身的 attempt 重试会把内核越打越乱。
            val deadline = System.currentTimeMillis() + 90_000
            while (proc.isRunning() && abortReason == null && System.currentTimeMillis() < deadline) Thread.sleep(200)
            if (proc.isRunning()) {
                proc.destroy()
                runCatching { ShizukuShell.run("pkill -9 -f onekey-98260; true", 10) }
                if (abortReason == null) { abortReason = "执行超时（90 秒）"; log("引擎超时，已终止") }
            }
        } catch (e: Throwable) {
            log("引擎异常：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            ShizukuShell.run("pkill -9 -f onekey-98260; rm -rf " + ws + "; true", 10)
        }
        // 中止后若已把 SELinux 改为 permissive，尽量恢复，减少残留影响。
        if (abortReason != null && sawPermissive) {
            val restored = ShizukuShell.run("timeout 5 su -c 'setenforce 1' 2>&1; timeout 5 su -c getenforce", 12).output.trim()
            log("已尝试恢复 SELinux：" + restored.lineSequence().lastOrNull().orEmpty())
        }
        if (ok) {
            val check = ShizukuShell.run("timeout 5 /system/bin/su -c id 2>&1", 10).output
            ok = check.contains("uid=0")
            if (ok) RunReport.markWrote("加载内核模块")
        }
        if (abortReason != null) {
            RunReport.stop("布置提权原语", abortReason!!)
            log("本次已中止：" + abortReason + "；建议重启后再试")
            return false
        }
        // 明确区分“拿到了 root”和“内核暴露了热切换节点”，避免两者混淆。
        val node = ShizukuShell.run("test -e /proc/kernelsu_manager && echo YES || echo NO", 10).output.trim()
        hotSwitchReady = node == "YES"
        if (universal) {
            val result = ShizukuShell.run("cat " + ws + "/universal-result 2>/dev/null", 10).output.trim()
            when {
                hotSwitchReady -> log("通用模块加载成功，热切换节点已就绪")
                result.contains("MISSING") -> log("通用模块未生效：内核里可能已存在管理器模块（late-load 会直接跳过）")
                result.isBlank() -> log("通用模块未回报结果，可能被引擎抑制输出")
                else -> log("通用模块状态：" + result)
            }
        }
        if (ok) {
            log(if (hotSwitchReady) "热切换节点已就绪：/proc/kernelsu_manager"
                else "本次未启用热切换节点（加载的是单管理器模块），管理器切换需重新加载通用模块")
        }
        return ok
    }
}
