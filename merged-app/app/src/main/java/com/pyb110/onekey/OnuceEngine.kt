package com.pyb110.onekey

import android.content.Context
import android.content.pm.PackageManager
import com.pyb110.onekey.ghost.device.KernelProbe
import dev.onekey.jb.ShizukuShell
import java.io.File
import java.security.MessageDigest

/** 98260 backend running the Onuce 1.0.4 native supervisor in a per-run workspace. */
object OnuceEngine {
    private const val ROOT = "/data/local/tmp/onuce"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format("%02x", it) }

    private fun backend(kind: String): String? = when (kind) {
        "kernelsu" -> "official"
        "next" -> "next"
        "bakasu" -> "bakasu"
        "sukisu" -> "sukisu"
        "finasu" -> "finasu"
        else -> null
    }

    private fun koAsset(b: String) = "core/" + if (b == "official") "official.ko" else b + ".ko"
    private fun ksudAsset(b: String) = "core/ksud_" + b

    /** Onuce 1.11：64560 变体带 6.12，98260 变体用于 6.18。 */
    private fun runtimeAsset(): String =
        if (KernelProbe.read().trim().startsWith("6.12.")) "core/onuce/onuce_111_64560"
        else "core/onuce/onuce_111_98260"

    fun run(
        context: Context,
        pkg: String,
        kind: String,
        adapter: RuntimeExtractor.Adapter?,
        log: (String) -> Unit,
        stage: (String) -> Unit,
        progress: (Int) -> Unit,
    ): Boolean {
        val profile = DeviceProfiles.current()
        if (profile != null && !profile.onuceVerified) {
            log("提示：98260 执行器按旧内核构建标定，当前系统是 " + profile.kernelRelease)
            log("      本次为首次适配，失败请导出日志并硬重启；64560 稳定链不受影响。")
        }
        if (profile?.id == "pms110") {
            return runAce(context, pkg, kind, adapter, log, stage, progress)
        }
        val pm = context.packageManager
        val pi = try {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: Throwable) {
            log("管理器未安装：" + pkg)
            return false
        }
        val b = backend(kind) ?: run {
            log("不支持的管理器类型：" + kind)
            return false
        }
        val cert = pi.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let { sha256(it) }
            ?: run { log("无法读取管理器签名"); return false }
        val version = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        val source = pi.applicationInfo?.sourceDir ?: run { log("无法定位管理器 APK"); return false }
        val apkHash = ShizukuShell.run("sha256sum '" + source.replace("'", "'\\''") + "' 2>/dev/null | awk '{print \$1}'", 30).output.trim()
        if (apkHash.length != 64) {
            log("无法读取管理器 APK 摘要：" + apkHash.take(100))
            return false
        }
        val autoCloud = context.getSharedPreferences("merged", Context.MODE_PRIVATE)
            .getBoolean("cloud_auto", true)
        val cloudModule = if (autoCloud) {
            CloudComponents.ensureModule(
                context, b, version.toInt(), cert, KernelProbe.read().trim(),
                log, progress
            )
        } else null
        val rel = KernelProbe.read().trim()
        val bundledAsset = if (b == "sukisu") "core/onuce/sukisu.ko" else koAsset(b)
        val bundled = runCatching {
            context.assets.open(bundledAsset).use { RuntimeExtractor.alignVermagic(it.readBytes(), rel) }
        }.getOrNull()
        val moduleBytes = cloudModule?.let { runCatching { it.readBytes() }.getOrNull() }
            ?: adapter?.takeIf { it.modulePath.isNotBlank() }
                ?.let { runCatching { File(it.modulePath).readBytes() }.getOrNull() }
            ?: bundled
            ?: run { log("缺少 LKM 组件：" + b); return false }
        val ksudBytes = if (adapter != null) {
            runCatching { File(adapter.ksudPath).readBytes() }.getOrNull()
        } else {
            val asset = if (b == "sukisu") "core/onuce/sukisu.ksud" else ksudAsset(b)
            runCatching { context.assets.open(asset).use { it.readBytes() } }.getOrNull()
        } ?: run { log("缺少 ksud 组件：" + b); return false }
        val expected = version
        val conf = "onuce-runtime-v2\t" + b + "\t" + pkg + "\t" + version + "\t" + expected +
            "\t6\t" + cert + "\t" + sha256(ksudBytes) + "\t" + apkHash + "\n"
        val suffix = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val ws = ROOT + "/" + suffix + "-2000-" + b
        stage("执行器准备运行环境")
        progress(5)
        if (!ShizukuShell.run("mkdir -p " + ROOT + " && chmod 755 " + ROOT + " && rm -rf " + ws + " && mkdir -p " + ws + " && chmod 700 " + ws + " && echo PREP", 30).output.contains("PREP")) {
            log("运行环境创建失败")
            return false
        }
        val assetStages = listOf(
            runtimeAsset() to ws + "/onuce",
            (profile?.onuceSymbolsAsset ?: "core/symbols.txt") to ws + "/device-symbols.txt",
        )
        for ((asset, dest) in assetStages) {
            if (ShizukuShell.stageAsset(context, asset, dest, 755).code != 0) {
                log("组件投放失败 " + asset)
                return false
            }
        }
        if (ShizukuShell.stageBytes(moduleBytes, ws + "/" + b + ".ko", 644).code != 0 ||
            ShizukuShell.stageBytes(ksudBytes, ws + "/" + b + ".ksud", 755).code != 0) {
            log("管理器组件投放失败")
            return false
        }
        if (ShizukuShell.stageBytes(conf.toByteArray(Charsets.UTF_8), ws + "/manager.conf", 644).code != 0) {
            log("运行配置投放失败")
            return false
        }
        ShizukuShell.run("cd " + ws + " && cp -f " + b + ".ko current.ko && echo READY", 20)
        val prep = ShizukuShell.run("cd " + ws + " && ls -l 2>&1 && echo RC=\$?", 20)
        log("运行目录: " + prep.output.trim().take(1200))
        stage("执行器启动")
        progress(15)
        val exec = ws + "/onuce"
        val cmd = "env ONUCE_SESSION_ID=onekey-" + System.currentTimeMillis() +
            " ONUCE_TIMEOUT=180 " + exec + " run " + ws + " " + b + " 2>&1"
        log("开始执行越狱流程")
        var r: ShizukuShell.Result
        var busyAttempts = 0
        while (true) {
            if (!SystemReadinessGate.await(log, 75)) {
                ShizukuShell.run("su -c \"pkill -9 -f 'onuce --plant' 2>/dev/null; true\" 2>&1", 20)
                return false
            }
            r = ShizukuShell.run(cmd, 180)
            log("执行退出码: " + r.code)
            log("执行输出: " + r.output.replace("ONUCE", "执行器").trim().take(3000))
            val busy = r.code == 40 || r.output.contains("系统繁忙") || r.output.contains("runnable")
            if (!busy) break
            ShizukuShell.run("su -c \"pkill -9 -f 'onuce --plant' 2>/dev/null; true\" 2>&1", 20)
            busyAttempts++
            if (busyAttempts >= 2) {
                log("系统持续繁忙，执行器已安全停止；请关闭后台应用后稍后重试")
                return false
            }
            log("系统繁忙，执行器主动停止；等待 15 秒后重试一次")
            Thread.sleep(15000)
        }
        // Verify root before any finalize/cleanup. A timeout does not mean
        // the native helper failed, but it must never be reported as a clean
        // successful run either.
        val check = ShizukuShell.run(
            "timeout 5 /system/bin/su -c id 2>&1; timeout 5 su -c id 2>&1", 15
        )
        log("root 检查: " + check.output.trim().take(300))
        val ok = check.output.contains("uid=0")
        if (ok) {
            // Stop the plant helper first. finalize is only a best-effort step:
            // it can refuse when kernel identity differs from the selected
            // backend, and must not block the result or the UI.
            val fin = ShizukuShell.run(
                "su -c \"timeout 8 " + exec + " finalize " + ws + " " + b + "\" 2>&1", 12
            )
            val finText = fin.output.replace("ONUCE", "执行器").trim()
            if (finText.isNotBlank() && !finText.contains("身份") && !finText.contains("identity")) {
                log("执行器收尾: " + finText.take(400))
            }
            val stop = ShizukuShell.run(
                "su -c \"pkill -9 -f 'onuce --plant' 2>/dev/null; true\" 2>&1", 20
            )
            log("残留执行器: " + if (stop.output.isBlank()) "已清理" else stop.output.trim().take(200))
            val shot = ScreenshotRepair.repair(log)
            log("截屏组件: " + if (shot.enabled) "已启用" else "恢复失败")
            progress(100)
            stage(if (r.code == 124) "root 已获取（执行器超时）" else "越狱完成")
            if (r.code == 124) {
                log("执行器超时，但 root 已获取；残留进程已清理，请勿重复越狱")
            } else {
                log("越狱完成：uid=0，manager=" + pkg)
            }
        } else {
            ShizukuShell.run(
                "su -c \"pkill -9 -f 'onuce --plant' 2>/dev/null; true\" 2>&1", 20
            )
            log("未取得 root；不执行旧链路")
        }
        return ok
    }
    /** OnePlus Ace 7 Pro: Onuce 1.09 manager mode, 6.12 symbols, manager APK module. */
    private fun runAce(
        context: Context,
        pkg: String,
        kind: String,
        adapter: RuntimeExtractor.Adapter?,
        log: (String) -> Unit,
        stage: (String) -> Unit,
        progress: (Int) -> Unit,
    ): Boolean {
        val b = backend(kind) ?: run { log("不支持的管理器类型：" + kind); return false }
        val pm = context.packageManager
        val pi = try { pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES) } catch (e: Throwable) {
            log("管理器未安装：" + pkg); return false
        }
        val signer = pi.signingInfo?.apkContentsSigners?.firstOrNull() ?: run { log("无法读取管理器签名"); return false }
        val der = signer.toByteArray()
        val cert = sha256(der)
        val certSize = der.size
        val version = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        val source = pi.applicationInfo?.sourceDir ?: run { log("无法定位管理器 APK"); return false }
        val apkHash = ShizukuShell.run("sha256sum " + shQuote(source) + " 2>/dev/null | awk '{print \$1}'", 30).output.trim()
        if (apkHash.length != 64) { log("无法读取管理器 APK 摘要：" + apkHash.take(100)); return false }
        val ksudBytes = if (adapter != null && adapter.pkg == pkg) {
            runCatching { File(adapter.ksudPath).readBytes() }.getOrNull()
        } else null
        val ksud = ksudBytes ?: runCatching { context.assets.open(ksudAsset(b)).use { it.readBytes() } }.getOrNull()
        if (ksud == null) { log("缺少 ksud 组件：" + b); return false }
        val uapi = if (b == "official") 5 else 6
        val conf = listOf(
            "onuce-runtime-manager-v1", b, pkg, version.toString(), version.toString(),
            uapi.toString(), cert, sha256(ksud), apkHash, certSize.toString()
        ).joinToString("\t") + "\n"
        val suffix = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val ws = ROOT + "/" + suffix + "-2000-" + b
        stage("Ace 7 Pro 准备运行环境"); progress(5)
        if (!ShizukuShell.run("mkdir -p " + ROOT + " && chmod 755 " + ROOT + " && rm -rf " + ws + " && mkdir -p " + ws + " && chmod 700 " + ws + " && echo PREP", 30).output.contains("PREP")) {
            log("运行环境创建失败"); return false
        }
        val assets = listOf(
            runtimeAsset() to ws + "/onuce",
            "core/onuce/device-symbols-612.txt" to ws + "/device-symbols.txt",
            "core/onuce/device-symbols-612.txt" to ws + "/device-symbols-612.txt",
        )
        for ((asset, dest) in assets) {
            if (ShizukuShell.stageAsset(context, asset, dest, if (asset.contains("onuce_111")) 755 else 644).code != 0) {
                log("组件投放失败 " + asset); return false
            }
        }
        if (ShizukuShell.stageBytes(ksud, ws + "/" + b + ".ksud", 755).code != 0 ||
            ShizukuShell.stageBytes(conf.toByteArray(Charsets.UTF_8), ws + "/manager.conf", 644).code != 0) {
            log("管理器组件投放失败"); return false
        }
        val prep = ShizukuShell.run("cd " + ws + " && ls -l 2>&1 && echo RC=\$?", 20)
        log("Ace 运行目录: " + prep.output.trim().take(1200))
        stage("Ace 7 Pro 启动新链"); progress(15)
        val exec = ws + "/onuce"
        val cmd = "env ONUCE_SESSION_ID=onekey-" + System.currentTimeMillis() + " ONUCE_TIMEOUT=180 " + exec + " run " + ws + " " + b + " 2>&1"
        log("开始执行 Ace 7 Pro 新链")
        var r: ShizukuShell.Result
        var busyAttempts = 0
        while (true) {
            if (!SystemReadinessGate.await(log, 75)) {
                ShizukuShell.run("su -c \"pkill -9 -f 'onuce --plant' 2>/dev/null; true\" 2>&1", 20)
                return false
            }
            r = ShizukuShell.run(cmd, 180)
            log("执行退出码: " + r.code)
            log("执行输出: " + r.output.replace("ONUCE", "执行器").trim().take(3000))
            val busy = r.code == 40 || r.output.contains("系统繁忙") || r.output.contains("runnable")
            if (!busy) break
            ShizukuShell.run("su -c \"pkill -9 -f 'onuce --plant' 2>/dev/null; true\" 2>&1", 20)
            busyAttempts++
            if (busyAttempts >= 2) { log("系统持续繁忙，执行器已安全停止；请关闭后台应用后稍后重试"); return false }
            log("系统繁忙，等待 15 秒后重试一次"); Thread.sleep(15000)
        }
        val check = ShizukuShell.run("timeout 5 /system/bin/su -c id 2>&1; timeout 5 su -c id 2>&1", 15)
        log("root 检查: " + check.output.trim().take(300))
        val ok = check.output.contains("uid=0")
        if (ok) {
            val fin = ShizukuShell.run("su -c \"timeout 8 " + exec + " finalize " + ws + " " + b + "\" 2>&1", 12)
            log("执行器收尾: " + fin.output.replace("ONUCE", "执行器").trim().take(400))
            ShizukuShell.run("su -c \"pkill -9 -f 'onuce --plant' 2>/dev/null; true\" 2>&1", 20)
            val shot = ScreenshotRepair.repair(log)
            log("截屏组件: " + if (shot.enabled) "已启用" else "恢复失败")
            progress(100); stage("Ace 7 Pro 越狱完成")
        } else {
            ShizukuShell.run("su -c \"pkill -9 -f 'onuce --plant' 2>/dev/null; true\" 2>&1", 20)
            log("未取得 root；不执行旧链路")
        }
        return ok
    }

    private fun shQuote(v: String): String = "'" + v.replace("'", "'\\''") + "'"
}
