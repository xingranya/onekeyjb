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

    /** 最近一次运行停在哪：slide=仅校准失败（内核未被写入），其它为空。 */
    @Volatile var lastAbortStage: String? = null

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

    /** 执行器按机型档案选：不同内核构建用各自放行过的二进制。 */
    private fun runtimeAsset(): String {
        DeviceProfiles.current()?.let { return it.onuceAsset }
        return if (KernelProbe.read().trim().startsWith("6.12.")) "core/onuce/onuce_111_64560"
        else "core/onuce/onuce_111_98260"
    }

    fun run(
        context: Context,
        pkg: String,
        kind: String,
        adapter: RuntimeExtractor.Adapter?,
        log: (String) -> Unit,
        stage: (String) -> Unit,
        progress: (Int) -> Unit,
    ): Boolean {
        lastAbortStage = null
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
        if (moduleBytes == null) {
            log("未提供内核模块：交给执行器使用管理器 ksud 自带的 late-load 数据")
        }
        val ksudBytes = adapter?.let { runCatching { File(it.ksudPath).readBytes() }.getOrNull() }
            ?: runCatching { context.assets.open(ksudAsset(b)).use { it.readBytes() } }.getOrNull()
            ?: run { log("缺少 ksud：请先安装所选管理器，App 不再内置 ksud 副本"); return false }
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
        if (moduleBytes != null && ShizukuShell.stageBytes(moduleBytes, ws + "/" + b + ".ko", 644).code != 0) {
            log("内核模块投放失败")
            return false
        }
        if (ShizukuShell.stageBytes(ksudBytes, ws + "/" + b + ".ksud", 755).code != 0) {
            log("管理器 ksud 投放失败")
            return false
        }
        if (ShizukuShell.stageBytes(conf.toByteArray(Charsets.UTF_8), ws + "/manager.conf", 644).code != 0) {
            log("运行配置投放失败")
            return false
        }
        if (moduleBytes != null) {
            ShizukuShell.run("cd " + ws + " && cp -f " + b + ".ko current.ko && echo READY", 20)
        }
        val prep = ShizukuShell.run("cd " + ws + " && ls -l 2>&1 && echo RC=\$?", 20)
        log("运行目录: " + prep.output.trim().take(1200))
        stage("执行器启动")
        progress(15)
        val exec = ws + "/onuce"
        val cmd = "env ONUCE_SESSION_ID=onekey-" + System.currentTimeMillis() +
            " ONUCE_TIMEOUT=180 " + exec + " run " + ws + " " + b + " 2>&1"
        log("执行器已启动，正在寻找提权入口（快路径通常 10 秒内完成）")
        stage("执行器运行中")
        progress(20)
        var exitCode = -1
        var lastLineAt = System.currentTimeMillis()
        var warnedIdle = false
        var slideRound = 0
        var abortRequested = false
        var proc: ShizukuShell.LineProc? = null
        val startedAt = System.currentTimeMillis()
        try {
            proc = ShizukuShell.start(cmd) { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@start
                lastLineAt = System.currentTimeMillis()
                // 执行器输出是 tab 分隔：ONUCE <kind> <detail...>
                val body = line.removePrefix("ONUCE").trim().removePrefix("执行器").trim()
                val parts = body.split('	', ' ').filter { it.isNotEmpty() }
                val kind = parts.getOrNull(0).orEmpty()
                val detail = parts.drop(1).joinToString(" ")
                when {
                    line.startsWith("SLIDE_TRY=") -> {
                        slideRound = line.removePrefix("SLIDE_TRY=").trim().toIntOrNull() ?: (slideRound + 1)
                        stage("校准内核地址（第 " + slideRound + " 轮）")
                    }
                    line.startsWith("SLIDE_MISS=") -> {
                        val n = line.substringAfter('=').substringBefore(' ').toIntOrNull() ?: slideRound
                        slideRound = n
                        if (n == 1 || n % 4 == 0) log("第 " + n + " 轮滑动量校验未通过，继续重试")
                        if (n >= 12 && !abortRequested) {
                            abortRequested = true
                            lastAbortStage = "slide"
                            log("连续 12 轮校验未通过：当前内核的滑动量锚点与执行器不一致，已停止")
                            log("下一步：需要为该内核构建重新标定锚点；本轮未改动内核状态")
                        }
                    }
                    kind == "stage" -> stage(stageName(detail.substringBefore(' ')))
                    kind == "detail" -> if (detail.isNotBlank()) log("  " + readable(detail))
                    kind == "timing" -> Unit
                    kind == "error" -> log("执行器：" + detail)
                    kind == "backend" || kind == "device" || kind == "boot" -> Unit
                    line.startsWith("UAF mode=") || line.startsWith("LEAK_") ||
                        line.startsWith("RETRY=") || line.startsWith("SLIDE=") ||
                        line.startsWith("SLIDE_PROBE") || line.startsWith("SLIDE_DERIVED") -> Unit
                    else -> if (line.length in 3..200 && !line.startsWith("[")) log("  " + line)
                }
            }
            while (proc.isRunning()) {
                Thread.sleep(500)
                if (abortRequested) { proc.destroy(); break }
                val now = System.currentTimeMillis()
                val idle = (now - lastLineAt) / 1000L
                val total = (now - startedAt) / 1000L
                if (!warnedIdle && idle >= 15) {
                    warnedIdle = true
                    log("执行器 " + idle + " 秒没有输出，仍在运行；若超过 2 分钟建议停止后重试")
                    stage("执行器响应较慢")
                }
                if (total >= 200) {
                    log("执行器超过 200 秒未结束，已终止本次运行")
                    proc.destroy()
                    return false
                }
            }
            if (abortRequested) {
                log("执行器已按计划停止，用时 " + ((System.currentTimeMillis() - startedAt) / 1000) + " 秒")
                return false
            }
            exitCode = runCatching { proc.exitCode() }.getOrNull() ?: -1
        } catch (e: Throwable) {
            log("执行器异常：" + (e.message ?: e.javaClass.simpleName))
            return false
        } finally {
            runCatching { proc?.destroy() }
        }
        if (exitCode == 5 || exitCode == 40) {
            log("检测到上一轮残留任务，已清理并重试一次")
            ShizukuShell.run("pkill -9 -x onuce 2>/dev/null; pkill -9 -f '/data/local/tmp/onuce/' 2>/dev/null; true", 10)
            Thread.sleep(1500)
            val retry = ShizukuShell.run(cmd, 60)
            log("重试退出码: " + retry.code)
            exitCode = retry.code
        }
        log("执行器结束，退出码 " + exitCode + "，用时 " + ((System.currentTimeMillis() - startedAt) / 1000) + " 秒")
        // 中止或异常时残留的 plant 会占住下一次运行，这里统一清掉
        if (abortRequested || exitCode != 0) {
            ShizukuShell.run("pkill -9 -x onuce 2>/dev/null; pkill -9 -f '/data/local/tmp/onuce/' 2>/dev/null; true", 10)
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
            stage(if (exitCode == 124) "root 已获取（执行器超时）" else "越狱完成")
            if (exitCode == 124) {
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

    /** 执行器阶段键 → 界面文案。 */
    private fun stageName(key: String): String = when (key) {
        "capture" -> "采集内核事件"
        "calibration" -> "校准内核地址"
        "credential" -> "提权与定位凭证"
        "selinux" -> "切换安全策略"
        "module" -> "加载内核模块"
        "manager" -> "登记管理器"
        "finalize" -> "收尾"
        else -> if (key.isBlank()) "执行器运行中" else key
    }

    /** 执行器 detail 行 → 可读文案（已经是中文的原样保留）。 */
    private fun readable(detail: String): String {
        val body = detail.substringAfter(' ', detail).trim()
        return when {
            detail.startsWith("capture ") -> "采集内核事件 " + body
            detail.startsWith("calibration ") -> body
            detail.startsWith("module ") -> body
            detail.startsWith("policy ") -> body
            detail.startsWith("automatic ") -> body
            detail.startsWith("chain ") -> body
            else -> body.ifBlank { detail }
        }
    }

    private fun shQuote(v: String): String = "'" + v.replace("'", "'\\''") + "'"
}
