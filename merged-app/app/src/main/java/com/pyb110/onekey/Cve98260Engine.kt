package com.pyb110.onekey

import android.content.Context
import com.pyb110.onekey.ghost.engine.GhostKeyEngine
import dev.onekey.jb.ShizukuShell

/**
 * CVE-2026-98260 on OnePlus 16 (6.18) & Ace 7 Pro (6.12).
 *
 * Supervisor:
 *   - one attempt per boot, tracked by /proc/sys/kernel/random/boot_id
 *   - hardened execution through Onuce 1.11 / GhostKey backend
 *   - never soft-reboots; requires hard reboot on failure
 */
object Cve98260Engine {

    fun run(
        context: Context,
        pkg: String,
        kind: String,
        adapter: RuntimeExtractor.Adapter?,
        antiRoot: Boolean,
        suCompat: Boolean,
        softReboot: Boolean,
        logSink: (String) -> Unit,
        stageSink: (String) -> Unit,
        progressSink: (Int) -> Unit,
    ): Boolean {
        val profile = DeviceProfiles.current()
        if (profile == null) {
            logSink("98260 当前设备不在支持范围：" + android.os.Build.MODEL + " / " + (System.getProperty("os.version") ?: ""))
            return false
        }

        val zygoteNext = ShizukuShell.run("getprop init.svc.zygote_next 2>/dev/null", 10).output.trim()
        if (zygoteNext.isNotBlank()) {
            logSink("98260 zygote_next=" + zygoteNext + "；本路径不会执行任何软重启")
        }

        val bootId = ShizukuShell.run("cat /proc/sys/kernel/random/boot_id 2>/dev/null", 10)
            .output.trim()
        if (bootId.isBlank()) {
            logSink("98260 读取 boot_id 失败，拒绝运行")
            return false
        }
        logSink("98260 boot=" + bootId.take(8) + "，本次开机可重复尝试；仅在内核状态异常时才需要硬重启")

        stageSink("98260 检查已有 root")
        progressSink(2)
        val existing = ShizukuShell.run("/system/bin/su -c id 2>&1; su -c id 2>&1", 15)
        if (existing.output.contains("uid=0")) {
            logSink("98260 已有可用 root，跳过 plant/rootd，避免重复加载和硬重启")
            logSink("98260 已检测到: " + existing.output.trim().take(200))
            progressSink(100)
            stageSink("98260 已就绪")
            return true
        }

        val targetPkg = if (pkg.isNotBlank()) pkg else "com.resukisu.resukisu"
        val targetKind = if (kind.isNotBlank()) kind else "sukisu"

        var runAdapter = adapter
        if (runAdapter == null && targetKind != "finasu") {
            stageSink("98260 准备管理器匹配组件")
            val cert = managerCert(context, targetPkg)
            if (cert != null) {
                runAdapter = runCatching {
                    RuntimeExtractor.prepare(context, targetPkg, targetKind, cert) { logSink(it) }
                }.getOrNull()
            }
            if (runAdapter != null) {
                logSink("98260 将使用安装管理器自身的模块/ksud")
            } else {
                logSink("98260 未能提取安装管理器组件")
            }
        }

        if (profile.id == "pms110") {
            logSink("98260 使用 GhostKey 原生后端")
            val ghost = GhostKeyEngine.run(
                context, targetPkg, targetKind, logSink, stageSink, progressSink,
                beforeNative = { }
            )
            if (ghost != null) {
                return ghost
            }
            logSink("98260 GhostKey 未启用，回退 Onuce")
        }

        // OnePlus 16: never fall back to GhostKey 6.18 automatically. Its profile
        // is not device-verified and a wrong slide/task offset can panic the kernel.
        logSink("98260 使用新越狱执行器（" + targetKind + "）")
        // 标记本次开机已尝试原生提权，防止失败后重试导致硬重启死循环
        logSink("98260 开始执行（boot=" + bootId.take(8) + "），失败可直接重试")
        val ok = OnuceEngine.run(context, targetPkg, targetKind, runAdapter, logSink, stageSink, progressSink)
        if (!ok) {
            if (OnuceEngine.lastAbortStage == "slide") {
                RunReport.stop("校准内核地址", "滑动量校验未通过")
            } else {
                RunReport.stop("提权", "执行器未拿到 root")
            }
        }
        if (!ok && OnuceEngine.lastAbortStage == "slide") {
            logSink("本次只在校准阶段停止，未写入内核；可以直接再点一次")
        }
        return ok
    }

    private fun managerCert(context: Context, pkg: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        val der = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return@runCatching null
        java.security.MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { String.format("%02x", it) }
    }.getOrNull()
}
