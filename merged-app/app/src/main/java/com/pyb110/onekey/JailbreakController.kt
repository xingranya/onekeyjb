package com.pyb110.onekey

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import dev.onekey.jb.ShizukuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roro.stellar.Stellar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ManagerOption(
    val kind: String,
    val label: String,
    val pkg: String,
    val installedVersion: String?,
    val expectedVersionCode: Int,
    val installedVersionCode: Int?,
    val statusText: String,
    val supported: Boolean,
    val certOk: Boolean
)

object RunState {
    @Volatile var active: Boolean = false
    /** 由开机自动越狱服务驱动时为 true：此时已有前台服务保活，不再另起保活通知。 */
    @Volatile var headless: Boolean = false
}

class JailbreakController private constructor(private val context: Context) {
    companion object {
        @Volatile private var shared: JailbreakController? = null
        fun get(context: Context): JailbreakController =
            shared ?: synchronized(this) {
                shared ?: JailbreakController(context.applicationContext).also { shared = it }
            }
    }

    private val prefs = context.getSharedPreferences("merged", Context.MODE_PRIVATE)
    private val UNIVERSAL_KSUD_SIZE = 12652520
    private val legacyPrefs = context.getSharedPreferences("onekey", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)
    // 必须早于 init 块初始化：init 里的 refreshState() 可能触发 addLog 的 synchronized(logLock)
    private val logLock = Any()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val expectedCert = mapOf(
        "next" to "79e590113c4c4c0c222978e413a5faa801666957b1212a328e46c00c69821bf7",
        "kernelsu" to "c371061b19d8c7d7d6133c6a9bafe198fa944e50c1b31c9d8daa8d7f1fc2d2d6",
        "sukisu" to "947ae944f3de4ed4c21a7e4f7953ecf351bfa2b36239da37a34111ad29993eef",
        "bakasu" to "d3469712b6214462764a1d8d3e5cbe1d6819a0b629791b9f4101867821f1df64",
        "finasu" to "2d89b50aaf1db013c2b6738f973f48b62ea21a3a8d7216bfec3521065d448458",
        // YukiSU v1.9.0（org.rouyashiki.yukisu）
        "yukisu" to "45bd30fe301b6a6ff798e0ae930606d5f443c16f078a95ec9d82d326498ed1c3",
        // SukiSU Nova v3.0.0（com.sukisu.nova）；与 SukiSU Ultra 共用同一签名证书。
        "sukisu_nova" to "947ae944f3de4ed4c21a7e4f7953ecf351bfa2b36239da37a34111ad29993eef",
    )


    val profile: DeviceProfile? = DeviceProfiles.current()
    var platformReason: String? by mutableStateOf(null); private set
    var managers: List<ManagerOption> by mutableStateOf(emptyList()); private set
    var selectedManager: String by mutableStateOf(loadSelectedManager()); private set
    /** 不再提供开关：内核模块与 ksud 一律从已安装管理器 APK 运行时提取。 */
    val adaptiveManagerEnabled: Boolean = true
    var runtimeAdapter: RuntimeExtractor.Adapter? by mutableStateOf(null); private set
    var preparing: Boolean by mutableStateOf(false); private set
    var antiRootGuard: Boolean by mutableStateOf(prefs.getBoolean("antiroot", false)); private set
    var cloudAuto: Boolean by mutableStateOf(prefs.getBoolean("cloud_auto", true)); private set
    var updateStatus: String by mutableStateOf("尚未检查更新"); private set
    var checkingUpdate: Boolean by mutableStateOf(false); private set
    /** 越狱成功后是否软重启（只重启用户态，内核模块保持加载）。默认关闭，关闭时绝不主动重启。 */
    var softRebootAfter: Boolean by mutableStateOf(prefs.getBoolean("soft_reboot_after", false)); private set
    /** 开机后自动运行一次越狱（需 Stellar 已开启开机自启）。默认关闭。 */
    var autoJailbreak: Boolean by mutableStateOf(prefs.getBoolean("auto_jailbreak", false)); private set
    /** 越狱后卸载内核安全防护（oplus_secure_guard 等）。默认关闭。 */
    var unloadKernelGuard: Boolean by mutableStateOf(prefs.getBoolean("unload_kernel_guard", false)); private set
    /** SUSFS 隐藏支持（需要执行器侧加载模块）。 */
    var susfsEnabled: Boolean by mutableStateOf(prefs.getBoolean("susfs_enabled", false)); private set
    /** VFS（hybridmount）支持，仅 6.18 内核。 */
    var vfsEnabled: Boolean by mutableStateOf(prefs.getBoolean("vfs_enabled", false)); private set
    var running: Boolean by mutableStateOf(false); private set
    /** 同一次运行最多下发一次软重启。 */
    @Volatile private var softRebootIssued = false
    var elapsedSeconds: Long by mutableStateOf(0L); private set
    var progress: Float by mutableStateOf(0f); private set
    var stage: String by mutableStateOf("就绪"); private set
    var logs: List<String> by mutableStateOf(emptyList()); private set
    var deviceInfo: String by mutableStateOf(""); private set
    var serviceReady: Boolean by mutableStateOf(false); private set
    var serviceGranted: Boolean by mutableStateOf(false); private set
    var lastResultOk: Boolean? by mutableStateOf(null); private set
    var lastResultExploitKind: String? by mutableStateOf(null); private set
    var suCompatReady: Boolean by mutableStateOf(false); private set
    var hotSwitchAvailable: Boolean by mutableStateOf(false); private set
    var activeKernelManager: String by mutableStateOf(""); private set
    var isSwitchingManager: Boolean by mutableStateOf(false); private set
    var hotSwitchMessage: String by mutableStateOf(""); private set
    /** 状态探测的诊断摘要，便于定位"显示未越狱"这类问题。 */
    var probeDetail: String by mutableStateOf(""); private set
    /** 是否在越狱时加载通用多管理器模块（热切换前提，默认关闭以保持既有成功率）。 */
    /** 执行引擎不支持外部 ksud（实测 PYB110_KSUD/PYB110_MANAGER_PKG 均被忽略），固定关闭。 */
    val universalModuleEnabled: Boolean = false
    /** 管理器加载器是否已被替换为通用加载器（决定热切换能否真正启用）。 */
    var hotSwitchLoaderInstalled: Boolean by mutableStateOf(false); private set
    /** /proc/kernelsu_manager 的原始内容：包名 版本号 feature_max。 */
    var activeKernelRaw: String by mutableStateOf(""); private set
    var loaderBusy: Boolean by mutableStateOf(false); private set
    /** 用户是否希望长期启用热切换；管理器升级导致加载器被还原时会自动重装。 */
    var hotSwitchWanted: Boolean by mutableStateOf(prefs.getBoolean("hot_switch_wanted", false)); private set
    /** 本次开机是否已成功越狱（与 boot_id 绑定，重启后自动失效）。 */
    var rootedThisBoot: Boolean by mutableStateOf(false); private set
    /** 当前成功率评估（每秒刷新一次）。 */
    var successRate: RateResult by mutableStateOf(
        SuccessRate.evaluate(
            Env.EMPTY,
            BootClass.COLD_FIRST,
            emptyList(),
            SuccessRate.budgetFor(""),
            true,
        )
    ); private set
    /** 历史成功率统计。 */
    var historyStats: HistoryStore.Stats by mutableStateOf(HistoryStore.Stats(0, 0, 0, 0)); private set

    private var autoRepairTried = false

    private val binderListener = Stellar.OnBinderReceivedListener {
        serviceReady = StellarShell.available()
        serviceGranted = StellarShell.hasPermission()
        if (serviceReady) {
            addLog("Stellar 服务已连接")
            scope.launch { cleanupStaleExecutor() }
        }
    }
    private val deadListener = Stellar.OnBinderDeadListener {
        serviceReady = false; serviceGranted = false; addLog("Stellar 服务已断开")
    }
    private val permissionListener = Stellar.OnRequestPermissionResultListener { code, granted, _ ->
        serviceGranted = granted
        addLog(if (granted) "Stellar 权限已授予" else "Stellar 权限被拒绝")
    }

    private fun localBootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    /** 每秒采一次样，界面上的成功率跟着动。 */
    private fun startRateSampler() {
        scope.launch {
            while (true) {
                refreshRate()
                delay(SuccessRate.SAMPLE_MS)
            }
        }
    }

    /** 读电量百分比；读不到返回 -1。读电量不需要任何权限。 */
    private fun readBattery(): Int = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val v = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (v in 0..100) v else -1
    }.getOrDefault(-1)

    /**
     * 重算成功率。
     *
     * 关键点：百分比不是从环境读数推出来的，而是从历史实测样本推出来的；
     * 环境只做 ±30% 的修正。这样即使某个量读不到，数字也不会变成假值。
     */
    fun refreshRate() {
        val env = SuccessRate.sample(readBattery())
        val kmi = profile?.kmi ?: ""
        val budget = SuccessRate.budgetFor(kmi)
        val slabRelevant = kmi.contains("6.12")
        runCatching {
            successRate = SuccessRate.evaluate(
                env = env,
                bootClass = currentBootClass(),
                samples = cachedSamples(),
                budgetMs = budget,
                slabRelevant = slabRelevant,
            )
        }
    }

    /**
     * 样本缓存。
     *
     * 采样每秒一次，而样本文件只在一次运行结束后才变；每秒读盘毫无意义。
     * 缓存 15 秒，运行结束时由 [invalidateSamples] 立刻作废。
     */
    private var samplesCache: List<RunSample> = emptyList()
    private var samplesAt = 0L
    private val samplesLock = Any()

    private fun cachedSamples(): List<RunSample> = synchronized(samplesLock) {
        val now = System.currentTimeMillis()
        if (now - samplesAt > 15_000L) {
            samplesCache = runCatching { RunStats.read(context) }.getOrDefault(emptyList())
            samplesAt = now
        }
        samplesCache
    }

    private fun invalidateSamples() = synchronized(samplesLock) {
        samplesAt = 0L
        samplesCache = emptyList()
    }

    /** 当前 boot 桶：脏 boot / 同 boot 重跑 / 首次。 */
    private fun currentBootClass(): BootClass = RunStats.classifyBoot(
        currentBootId = localBootId(),
        lastSuccessBootId = prefs.getString("success_boot_id", "").orEmpty(),
        failedBootId = prefs.getString("failed_boot_id", "").orEmpty(),
        ranThisBoot = rootedThisBoot,
    )

    fun refreshStats() {
        historyStats = runCatching { HistoryStore.stats(context) }.getOrDefault(HistoryStore.Stats(0, 0, 0, 0))
    }

    /**
     * 把一次运行落成统计样本。
     *
     * 只有引擎真的跑过 attempt 才记录：中途因权限、设备不匹配等原因早退的运行
     * 没有任何 attempt，记进去会把命中率稀释成假数据。
     */
    private fun recordRunSample(t: EngineTelemetry, ok: Boolean, bootId: String, t0: Long) {
        if (t.attempts <= 0) return
        val env = successRate
        val budget = successRate.budgetMs
        RunStats.append(
            context,
            RunSample(
                ts = System.currentTimeMillis(),
                boot = bootId.take(8),
                bootClass = currentBootClass(),
                attempts = t.attempts,
                hits = t.hits,
                msPerAttempt = if (t.avgMs > 0) t.avgMs else RunStats.DEFAULT_MS_PER_ATTEMPT,
                totalMs = System.currentTimeMillis() - t0,
                ok = ok,
                failKind = t.failKind,
                budgetMs = if (t.budgetMs > 0) t.budgetMs else budget,
                procsRunning = env.procsRunning,
                load1 = env.load1,
                tempMilliC = env.tempMilliC,
                battery = env.battery,
            )
        )
        addLog(
            RunLog.info(
                "本次运行已计入统计",
                "尝试 " + t.attempts + " 次、命中 " + t.hits + " 次、单次约 " +
                    (if (t.avgMs > 0) t.avgMs else 0L) + "ms"
            )
        )
    }

    private fun refreshRootedFlag() {
        val saved = prefs.getString("success_boot_id", "").orEmpty()
        rootedThisBoot = saved.isNotBlank() && saved == localBootId()
    }

    init {
        HistoryStore.attach(context)
        // 进程被杀、强退、软重启都会留下 running 记录；启动时统一收尾成「异常中断」，
        // 否则历史页会永远挂着「未完成」，还会把成功率算错。
        runCatching { HistoryStore.abandon(context) }
        refreshRootedFlag()
        refreshStats()
        startRateSampler()
        runCatching {
            Stellar.addBinderReceivedListenerSticky(binderListener, null)
            Stellar.addBinderDeadListener(deadListener, null)
            Stellar.addRequestPermissionResultListener(permissionListener, null)
        }
        serviceReady = StellarShell.available()
        serviceGranted = StellarShell.hasPermission()
        refreshState()
        scope.launch {
            snapshotFlow { stage to progress }.collect { (s, p) ->
                if (running) runCatching { JailbreakRunService.update(context, s, p) }
            }
        }
    }

    init {
        platformReason = SecurityCheck.reason(context)
        // 软重启/进程被杀后仍能看到上一次运行的日志
        runCatching {
            val f = File(context.filesDir, "onekey-live.log")
            if (f.isFile) {
                val tail = f.readLines().takeLast(400).filter { it.isNotBlank() }
                if (tail.isNotEmpty()) {
                    // 分隔行必须自己也符合落盘格式，否则它会被 parse 当成正文，
                    // 标题里带着一对方括号出现在日志页第一行。
                    logs = tail + RunLog.info("以上为上次运行记录", "可能被软重启中断").format()
                }
            }
        }
        // 墓碑结算必须排在日志恢复之后：前面那句 logs = tail + ... 是整体赋值，
        // 先写结论会被它覆盖掉。进程已死，磁盘墓碑是唯一还活着的证据。
        runCatching { RunTrace.settle(context) }.getOrNull()?.let { logs = logs + it.format() }
    }

    private fun loadSelectedManager(): String {
        val saved = prefs.getString("manager", null)
        if (saved != null) return saved
        val legacy = legacyPrefs.getString("manager", null)
        return when (legacy) {
            "ksu" -> "next"
            "sukisu" -> "sukisu"
            else -> "next"
        }
    }

    fun refreshState() {
        if (isSwitchingManager) return
        refreshRootedFlag()
        // 服务在线但未授权时主动申请，否则所有探测都会是空结果。
        if (serviceReady && !serviceGranted) runCatching { StellarShell.requestPermission(1001) }
        serviceReady = StellarShell.available()
        serviceGranted = StellarShell.hasPermission()
        if (!RunState.active && lockFile().isFile()) {
            lockFile().delete()
            addLog("已清理上次中断留下的运行锁，可直接重试")
        }
        ManagerDownloads.load(context)
        managers = buildManagers()
        refreshManagerCatalog()
        platformReason = SecurityCheck.reason(context)
        if (adaptiveManagerEnabled) {
            val valid = managers.filter { it.supported && it.installedVersionCode != null && it.certOk }
            if (valid.none { it.kind == selectedManager }) {
                valid.firstOrNull()?.let { selectedManager = it.kind }
            }
        }
        scope.launch {
            runCatching {
                // Never re-enable security components from status refresh; only Safer restores
                // exactly what the current session disabled.
                if (serviceReady) StellarShell.run("echo SECURITY_CHECKED", 10)
            }
            val kernel = System.getProperty("os.version") ?: "未知"
            val selinux = if (serviceReady) StellarShell.run("getenforce", 10).output else "未知"
            val mod = if (serviceReady) StellarShell.run("grep -i kernelsu /proc/modules | tr '\n' ' '", 10).output else ""
            val overlay = if (serviceReady) StellarShell.run("grep -i onekey /proc/mounts 2>/dev/null | wc -l", 10).output else "0"
            // root 检测多路尝试：不同管理器/软重启后的 su 行为不完全一致。
            var suOk = false
            if (serviceReady) {
                for (probe in listOf(
                    "su -c 'id -u'",
                    "/system/bin/su -c 'id -u'",
                    "su 0 id -u",
                    "su -c id"
                )) {
                    val out = StellarShell.run("timeout 8 " + probe + " 2>&1", 15).output
                    if (out.contains("uid=0") || out.lineSequence().any { it.trim() == "0" }) { suOk = true; break }
                }
            }
            val overlaySu = if (serviceReady) StellarShell.run("[ -x /system_ext/bin/su ] && grep -q su_compat /proc/mounts && echo READY || echo MISSING", 15).output else "MISSING"
            suCompatReady = suOk || overlaySu.contains("READY")
            probeDetail = "服务" + (if (serviceReady) "在线" else "离线") +
                (if (serviceReady) (if (serviceGranted) "/已授权" else "/未授权") else "") +
                " · su " + (if (suOk) "可用" else "不可用") +
                " · 本次开机" + (if (rootedThisBoot) "已越狱" else "无成功记录")

            // 节点探测与 root 检测解耦：/proc 节点本身对 shell 可读，不需要 su。
            if (serviceReady && serviceGranted && !running && !isSwitchingManager) {
                val plain = StellarShell.run("test -e /proc/kernelsu_manager && echo EXISTS || echo NO", 10).output.trim()
                val viaSu = if (!plain.contains("EXISTS") && suCompatReady)
                    StellarShell.run("timeout 5 su -c 'test -e /proc/kernelsu_manager && echo EXISTS || echo NO'", 12).output.trim()
                else ""
                hotSwitchAvailable = plain.contains("EXISTS") || viaSu.contains("EXISTS")
                activeKernelManager = if (hotSwitchAvailable) {
                    val raw = StellarShell.run("cat /proc/kernelsu_manager 2>/dev/null", 10).output.trim()
                        .ifBlank { StellarShell.run("timeout 5 su -c 'cat /proc/kernelsu_manager' 2>/dev/null", 12).output.trim() }
                    activeKernelRaw = raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                    managerLabelFromProfile(raw)
                } else { activeKernelRaw = ""; "" }
            } else {
                hotSwitchAvailable = false
                activeKernelManager = ""
            }
            if (serviceReady && serviceGranted) checkLoaderState()
            deviceInfo = buildString {
                append((profile?.model ?: "UNKNOWN") + " · Android ").append(Build.VERSION.RELEASE)
                append(" · SELinux ").append(selinux)
                append(" · 内核 ").append(kernel)
                if (mod.isNotBlank()) append(" · 模块 ").append(mod.take(40))
                append(" · 可见挂载 ").append(overlay.trim().ifBlank { "0" })
            }
        }
    }


    private data class PkgEntry(val pkg: String, val code: Int, val version: String?, val cert: String)

    private fun signerCert(pi: android.content.pm.PackageInfo): String? = try {
        val der = pi.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return null
        java.security.MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { String.format("%02x", it) }
    } catch (e: Throwable) { null }

    private fun installedEntries(): List<PkgEntry> {
        return try {
            context.packageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES).mapNotNull { pi ->
                val cert = signerCert(pi) ?: return@mapNotNull null
                val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
                PkgEntry(pi.packageName, code, pi.versionName, cert)
            }
        } catch (e: Throwable) { emptyList() }
    }

    /** 后台同步五家官方版本；提取不到的那一家不会出现在「管理器下载」里。 */
    fun refreshManagerCatalog(force: Boolean = false) {
        if (!force && ManagerDownloads.isFresh()) {
            managers = buildManagers()
            return
        }
        scope.launch {
            val ok = runCatching { ManagerDownloads.refresh(context) }.getOrDefault(false)
            managers = buildManagers()
            val detail = ManagerDownloads.all().joinToString("，") { it.label + " " + it.version }
            addLog(
                if (ok) "管理器版本已同步：" + detail
                else "管理器版本未完全获取" + (ManagerDownloads.lastError?.let { "（" + it + "）" } ?: "") +
                    if (detail.isNotBlank()) "；已获取：" + detail else ""
            )
        }
    }

    private fun buildManagers(): List<ManagerOption> {
        val entries = installedEntries()
        val byCert = entries.groupBy { it.cert }
        // 期望版本不写死：取官方 Release 实际提取到的 versionCode；未提取到记 0（自适应模式下不参与拦截）。
        val defs = listOf(
            Triple("next", "KernelSU-Next", "com.rifsxd.ksunext"),
            Triple("kernelsu", "KernelSU 官方", "me.weishu.kernelsu"),
            Triple("sukisu", "SukiSU Ultra", "com.sukisu.ultra"),
            Triple("bakasu", "BakaSU", "com.resukisu.resukisu"),
            Triple("finasu", "FinaSU", "me.yuki.fina"),
            Triple("yukisu", "YukiSU", "org.rouyashiki.yukisu"),
            Triple("sukisu_nova", "SukiSU Nova", "com.sukisu.nova"),
        ).map { it to (ManagerDownloads.latest(it.first)?.versionCode ?: 0) }
        return defs.map { (triple, expected) ->
            val (kind, label, staticPkg) = triple
            val cert = expectedCert[kind]
            // SukiSU Ultra 与 SukiSU Nova 共用同一签名证书，只按证书分组会把两者混为一谈，
            // 进而按版本号挑错管理器。所以先按包名精确命中，再退回证书候选。
            val exact = entries.firstOrNull { it.pkg == staticPkg && it.cert == cert }
            val candidates = if (cert == null) emptyList() else byCert[cert].orEmpty()
            val chosen = when {
                exact != null -> exact
                adaptiveManagerEnabled -> candidates.maxByOrNull { it.code }
                else -> candidates.firstOrNull { it.code == expected } ?: candidates.maxByOrNull { it.code }
            }
            val pkg = chosen?.pkg ?: staticPkg
            val installedCode = chosen?.code
            val supported = true
            val certOk = chosen != null && certSha256(pkg) == expectedCert[kind]
            val status = when {
                !supported -> "适配验证中，暂不可用"
                kind == "finasu" && !adaptiveManagerEnabled -> "FinaSU 请开启自适应适配"
                installedCode == null -> "未安装 · 请安装后再选择"
                adaptiveManagerEnabled && !certOk -> "签名不匹配，已拒绝"
                adaptiveManagerEnabled -> "已安装 " + (chosen?.version ?: "") + " · 可运行时适配"
                expected <= 0 -> "已安装 " + (chosen?.version ?: "") + " · 未取得官方最新版本"
                installedCode != expected -> "版本不匹配：需要 " + expected + "，当前 " + installedCode
                !certOk -> "签名不匹配，已拒绝"
                else -> "已安装 · 版本与签名匹配"
            }
            ManagerOption(kind, label, pkg, chosen?.version, expected, installedCode, status, supported, certOk)
        }
    }

    val visibleManagers: List<ManagerOption>
        get() = if (adaptiveManagerEnabled) managers.filter { it.installedVersionCode != null } else managers

    val canRunSelected: Boolean
        get() = managers.firstOrNull { it.kind == selectedManager }?.let {
            it.supported && it.installedVersionCode != null && it.certOk && (if (adaptiveManagerEnabled) true else it.installedVersionCode == it.expectedVersionCode)
        } == true

    val canRunExploit: Boolean
        get() = !running && canRunSelected && platformReason == null

    private fun installedVersionCode(pkg: String): Int? = try {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    } catch (e: PackageManager.NameNotFoundException) { null }

    private fun certSha256(pkg: String): String? = try {
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        val der = signers.firstOrNull()?.toByteArray() ?: return null
        java.security.MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { String.format("%02x", it) }
    } catch (e: Exception) { null }

    fun prepareRuntimeAdapter() {
        if (preparing || running) return
        val m = managers.firstOrNull { it.kind == selectedManager && it.installedVersionCode != null && it.certOk }
            ?: managers.firstOrNull { it.installedVersionCode != null && it.certOk }
            ?: run {
                addLog("没有可准备的管理器"); return
            }
        if (m.installedVersionCode == null) { addLog("管理器未安装"); return }
        preparing = true
        addLog("开始读取 " + m.label + " 的实际 APK 并提取 6.18 模块…")
        scope.launch {
            try {
                val cert = expectedCert[m.kind] ?: throw IllegalStateException("缺少管理器信任证书")
                val a = if (m.kind == "finasu") RuntimeExtractor.prepareFina(context, m.pkg, cert) { addLog(it) } else RuntimeExtractor.prepare(context, m.pkg, m.kind, cert) { addLog(it) }
                runtimeAdapter = a
                addLog("准备完成：模块 " + a.moduleSize + " B，vermagic=" + a.vermagic)
            } catch (e: Throwable) {
                addLog("准备失败：" + e.message)
            } finally {
                preparing = false
            }
        }
    }

    private fun installedVersion(pkg: String): String? = try {
        context.packageManager.getPackageInfo(pkg, 0).versionName ?: "已安装"
    } catch (e: PackageManager.NameNotFoundException) { null }

    fun selectManager(kind: String) {
        if (running) { addLog("运行中不能切换管理器"); return }
        selectedManager = kind
        prefs.edit().putString("manager", kind).apply()
        addLog("已选择管理器: " + kind)
    }

    fun updateAntiRootGuard(enabled: Boolean) {
        antiRootGuard = enabled
        prefs.edit().putBoolean("antiroot", enabled).apply()
        addUiLog(
            if (enabled) RunLog.info("已开启安全弹窗处理", "越狱后临时停用 ColorOS 安全守护")
            else RunLog.info("已关闭安全弹窗处理")
        )
    }

    fun updateCloudAuto(enabled: Boolean) {
        cloudAuto = enabled
        prefs.edit().putBoolean("cloud_auto", enabled).apply()
        addUiLog(if (enabled) RunLog.info("已开启云端组件匹配") else RunLog.info("已关闭云端组件匹配"))
    }

    fun checkUpdates() {
        if (checkingUpdate || running) return
        checkingUpdate = true
        updateStatus = "正在检查…"
        scope.launch {
            val app = runCatching { UpdateChecker.check(BuildConfig.VERSION_CODE) }.getOrNull()
            val m = managers.firstOrNull { it.kind == selectedManager }
            val installed = m?.installedVersionCode
            val cloud = if (cloudAuto && installed != null) {
                CloudComponents.inspect(selectedManager, installed, com.pyb110.onekey.ghost.device.KernelProbe.read().trim())
            } else CloudComponents.Status("自动匹配已关闭或管理器未安装")
            val text = (if (app != null) "App 有更新 " + app.versionName else "App 已是最新") + " · " + cloud.text
            withContext(Dispatchers.Main) {
                updateStatus = text
                checkingUpdate = false
                addLog("检查更新: " + text)
            }
        }
    }

    fun updateUnloadKernelGuard(enabled: Boolean) {
        unloadKernelGuard = enabled
        prefs.edit().putBoolean("unload_kernel_guard", enabled).apply()
        addUiLog(
            if (enabled) RunLog.warn("已开启内核防护卸载", "成功后卸载内核安全防护模块，可能引起系统重建")
            else RunLog.info("已关闭内核防护卸载")
        )
    }

    fun updateSusfs(enabled: Boolean) {
        susfsEnabled = enabled
        prefs.edit().putBoolean("susfs_enabled", enabled).apply()
        addUiLog(
            if (enabled) RunLog.info("已开启 SUSFS 支持", "加载模块后生效")
            else RunLog.info("已关闭 SUSFS 支持")
        )
    }

    private fun selectedPkg(): String? = managers.firstOrNull { it.kind == selectedManager }?.pkg

    private fun loaderScript(action: String, staged: String): String {
        val pkg = selectedPkg() ?: return "echo NO_PKG; exit 9"
        return "P=\$(pm path " + pkg + " 2>/dev/null | head -1 | sed 's/^package://'); " +
            "[ -n \"\$P\" ] || { echo NO_APK; exit 10; }; " +
            "D=\$(dirname \"\$P\"); F=\"\$D/lib/arm64/libksud.so\"; " +
            "B=/data/local/tmp/onekey-libksud-backup.so; " +
            when (action) {
                "install" -> "if [ ! -f \"\$B\" ]; then cp \"\$F\" \"\$B\" || { echo BACKUP_FAIL; exit 11; }; fi; " +
                    "cp " + staged + " \"\$F.new\" || { echo COPY_FAIL; exit 12; }; " +
                    "chmod 755 \"\$F.new\" && mv -f \"\$F.new\" \"\$F\" || { echo MOVE_FAIL; exit 13; }; " +
                    "echo OK; ls -l \"\$F\""
                else -> "[ -f \"\$B\" ] || { echo NO_BACKUP; exit 14; }; " +
                    "cp \"\$B\" \"\$F.new\" || { echo COPY_FAIL; exit 15; }; " +
                    "chmod 755 \"\$F.new\" && mv -f \"\$F.new\" \"\$F\" || { echo MOVE_FAIL; exit 16; }; " +
                    "echo OK; ls -l \"\$F\""
            }
    }

    /** 整机重启：通用模块必须在开机后第一次越狱时加载。 */
    fun rebootDevice() {
        if (running) { addLog("运行中不能重启"); return }
        addLog("正在重启手机…")
        scope.launch {
            val r = StellarShell.run("su -c reboot", 15)
            if (r.code != 0) {
                runCatching {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    pm.reboot(null)
                }.onFailure { addLog("重启失败：" + (it.message ?: "无权限")) }
            }
        }
    }

    fun checkLoaderState() {
        val pkg = selectedPkg() ?: return
        scope.launch {
            val out = runCatching {
                StellarShell.run("P=\$(pm path " + pkg + " | head -1 | sed 's/^package://'); " +
                    "D=\$(dirname \"\$P\"); stat -c %s \"\$D/lib/arm64/libksud.so\" 2>/dev/null", 12).output
            }.getOrDefault("")
            hotSwitchLoaderInstalled = out.trim() == UNIVERSAL_KSUD_SIZE.toString()
        }
    }

    fun installHotSwitchLoader(onDone: (Boolean, String) -> Unit) {
        if (loaderBusy || running) return
        if (!suCompatReady) return onDone(false, "请先完成一键越狱")
        loaderBusy = true
        addLog("正在安装通用加载器（会备份原文件，可随时还原）")
        scope.launch {
            runCatching {
                StellarShell.run("su -c '" + KsudNormalizer.script(selectedPkg().orEmpty()) + "'", 40)
            }.getOrNull()?.let { pre ->
                if (KsudNormalizer.parse(pre.output) is KsudNormalizer.Result.Materialized) {
                    addLog("已先归一化管理器 ksud，再安装通用加载器")
                }
            }
            try {
                val staged = "/data/local/tmp/onekey-universal-ksud"
                if (ShizukuShell.stageAsset(context, "core/exec/ksud-universal", staged, 755).code != 0) {
                    return@launch finishLoader(false, "通用加载器投放失败", onDone)
                }
                val r = StellarShell.run("su -c '" + loaderScript("install", staged) + "'", 60)
                val ok = r.output.contains("OK") && !r.output.contains("FAIL")
                if (ok) {
                    hotSwitchLoaderInstalled = true
                    hotSwitchWanted = true
                    prefs.edit().putBoolean("hot_switch_wanted", true).apply()
                    addLog("通用加载器已安装：重启手机后重新运行越狱即可启用热切换")
                } else {
                    addLog("安装失败：" + r.output.trim().take(120))
                }
                finishLoader(ok, if (ok) "已安装，需重启后运行越狱" else "安装失败", onDone)
            } catch (e: Throwable) {
                finishLoader(false, "安装异常：" + (e.message ?: e.javaClass.simpleName), onDone)
            }
        }
    }

    fun restoreHotSwitchLoader(onDone: (Boolean, String) -> Unit) {
        if (loaderBusy || running) return
        if (!suCompatReady) return onDone(false, "请先完成一键越狱")
        loaderBusy = true
        addLog("正在还原管理器原加载器")
        scope.launch {
            try {
                val r = StellarShell.run("su -c '" + loaderScript("restore", "") + "'", 60)
                val ok = r.output.contains("OK")
                if (ok) {
                    hotSwitchLoaderInstalled = false
                    hotSwitchWanted = false
                    prefs.edit().putBoolean("hot_switch_wanted", false).apply()
                    addLog("已还原管理器原加载器")
                }
                else addLog("还原失败：" + r.output.trim().take(120))
                finishLoader(ok, if (ok) "已还原" else "还原失败", onDone)
            } catch (e: Throwable) {
                finishLoader(false, "还原异常：" + (e.message ?: e.javaClass.simpleName), onDone)
            }
        }
    }

    private fun finishLoader(ok: Boolean, msg: String, onDone: (Boolean, String) -> Unit) {
        loaderBusy = false
        onDone(ok, msg)
    }

    fun updateVfs(enabled: Boolean) {
        vfsEnabled = enabled
        prefs.edit().putBoolean("vfs_enabled", enabled).apply()
        addUiLog(
            if (enabled) RunLog.info("已开启 VFS 支持", "仅 6.18 内核生效")
            else RunLog.info("已关闭 VFS 支持")
        )
    }

    fun executeHotSwitch(targetKind: String, onDone: (Boolean, String) -> Unit) {
        if (isSwitchingManager || running || preparing) return
        if (!hotSwitchAvailable) return finishSwitch(false, "当前内核模块未启用热切换", onDone)
        val target = managers.firstOrNull { it.kind == targetKind }
        if (target == null || target.installedVersionCode == null) return finishSwitch(false, "目标管理器未安装，无法切换", onDone)
        if (!target.certOk) return finishSwitch(false, "目标管理器签名不匹配，已拒绝切换", onDone)
        if (!serviceReady || !suCompatReady) return finishSwitch(false, "请先完成一键越狱", onDone)
        isSwitchingManager = true
        hotSwitchMessage = "正在切换到 " + target.label
        addLog("发起管理器热切换 → " + target.label + " (" + target.pkg + ")")
        scope.launch {
            try {
                val payload = managerSwitchPayload(target.pkg)
                val command = ManagerSwitchProtocol.command(payload)
                val result = StellarShell.run(command, 20)
                val output = result.output.trim()
                val active = managerLabelFromProfile(output)
                val rootOk = StellarShell.run("timeout 5 su -c 'id -u'", 12).output.trim() == "0"
                if (result.code == 0 && active == target.label && rootOk) {
                    activeKernelManager = active
                    hotSwitchMessage = "已切换到 " + active
                    addLog("热切换成功，内核 profile 已指向 " + active)
                    // 通用模块在 BakaSU profile 下会把所有已装管理器都注册为 manager，
                    // 各管理器界面会同时显示"工作中"；切换只改 primary profile。
                    // 这里把全部管理器停掉，重开时才会按新的 primary 刷新状态。
                    managers.forEach { m -> StellarShell.run("am force-stop " + m.pkg, 10) }
                    val stopped = StellarShell.run("am force-stop " + target.pkg, 10)
                    if (stopped.code != 0) addLog("目标管理器状态刷新失败，请手动关闭后重开：" + stopped.output.take(100))
                    withContext(Dispatchers.Main) {
                        runCatching {
                            context.packageManager.getLaunchIntentForPackage(target.pkg)?.let { intent ->
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        }.onFailure { addLog("请手动打开目标管理器：" + it.message) }
                    }
                    finishSwitch(true, "已切换到 " + target.label, onDone)
                } else {
                    val reason = when {
                        output.contains("ERR_NO_APK") -> "未获取到目标 APK 路径"
                        output.contains("ERR_NO_UID") -> "未获取到目标应用 UID"
                        output.isBlank() -> "内核节点不存在或拒绝写入"
                        else -> output.lineSequence().first().take(120)
                    }
                    finishSwitch(false, "热切换失败：" + reason, onDone)
                }
            } catch (e: Throwable) {
                finishSwitch(false, "热切换失败：" + (e.message ?: e.javaClass.simpleName), onDone)
            }
        }
    }

    private fun finishSwitch(ok: Boolean, message: String, onDone: (Boolean, String) -> Unit) {
        isSwitchingManager = false
        hotSwitchMessage = message
        if (!ok) addLog(message)
        onDone(ok, message)
    }

    private fun managerSwitchPayload(pkg: String): String {
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        check(signerCert(info) == expectedCert[managers.first { it.pkg == pkg }.kind]) { "管理器签名已变化，请刷新后重试" }
        val app = info.applicationInfo ?: error("未获取到管理器信息")
        return ManagerSwitchProtocol.payload(pkg, app.uid, app.sourceDir)
    }

    private fun managerLabelFromProfile(profile: String): String {
        val status = ManagerSwitchProtocol.parse(profile) ?: return ""
        return managers.firstOrNull { it.pkg == status.packageName }?.label.orEmpty()
    }

    fun updateAutoJailbreak(enabled: Boolean) {
        autoJailbreak = enabled
        prefs.edit().putBoolean("auto_jailbreak", enabled).apply()
        addUiLog(
            if (enabled) RunLog.info("已开启开机自动越狱", "需在 Stellar 里开启开机启动，并允许本应用自启动")
            else RunLog.info("已关闭开机自动越狱")
        )
    }

    /** 供开机服务判断：用户是否已同意协议（未同意时不自动运行）。 */
    fun termsAccepted(): Boolean = legacyPrefs.getBoolean("terms_accepted_v2", false)

    /** 供开机服务判断：上次开机是否留下了失败标记。 */
    fun lastBootFailed(): Boolean = prefs.getString("failed_boot_id", "").orEmpty().isNotBlank()

    /** 自动越狱失败后由服务调用，关闭开关避免重启循环。 */
    fun disableAutoJailbreakAfterFailure() {
        autoJailbreak = false
        prefs.edit().putBoolean("auto_jailbreak", false).apply()
        addLog(RunLog.warn("自动越狱未成功", "已关闭开机自动越狱，避免重启循环"))
    }

    /** 实时读落盘值：软重启这种不可逆动作只认 prefs，不认内存态。 */
    private fun softRebootWanted(): Boolean = prefs.getBoolean("soft_reboot_after", false)

    fun updateSoftRebootAfter(enabled: Boolean) {
        softRebootAfter = enabled
        prefs.edit().putBoolean("soft_reboot_after", enabled).apply()
        addUiLog(
            if (enabled) RunLog.info("已开启越狱后软重启", "成功后重启一次用户态，内核模块保持加载")
            else RunLog.info("已关闭越狱后软重启", "成功后保持当前会话")
        )
    }


    private fun cleanupStaleExecutor() {
        if (running) return
        val r = ShizukuShell.run(
            """su -c 'pkill -9 -f "onuce --plant" 2>/dev/null; true' 2>&1""", 20
        )
        if (r.output.isNotBlank()) addLog("已清理残留执行器")
    }


    fun repairScreenshot() {
        if (running) { addLog("当前任务运行中，暂不修复截屏"); return }
        scope.launch {
            val r = ScreenshotRepair.repair(::addLog)
            stage = if (r.enabled) "截屏修复完成" else "截屏修复未完成"
        }
    }

    fun restoreSecurityComponents() {
        if (running) {
            addLog("运行中不能恢复安全组件")
            return
        }
        scope.launch {
            if (!ensurePermission()) return@launch
            addLog("开始恢复安全组件…")
            val ok = Safer.restoreLast(::addLog)
            addLog(if (ok) "安全组件恢复完成" else "没有可恢复的记录，或恢复未执行")
        }
    }

    /**
     * 写一条结构化日志。
     *
     * @param toHistory 是否同时写进当前运行记录。开关操作、界面提示这类
     *   与越狱结果无关的事件传 false，避免污染历史与成功率统计。
     */
    fun addLog(entry: LogEntry, toHistory: Boolean = HistoryStore.hasActive()) {
        val stamped = entry.format()
        synchronized(logLock) {
            val next = logs + stamped
            logs = if (next.size > 3000) next.takeLast(3000) else next
            runCatching {
                java.io.FileOutputStream(File(context.filesDir, "onekey-live.log"), true).use { out ->
                    out.write((stamped + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
            }
            if (toHistory) HistoryStore.append(stamped)
        }
    }

    /** 兼容旧调用点：纯文本按关键字自动归类成 信息/成功/警告/失败。 */
    fun addLog(line: String) = addLog(RunLog.plain(line))

    /**
     * 界面事件日志：拨开关、点按钮、页面提示。
     *
     * 这些与「一次越狱的结果」无关，写进历史只会让历史页出现
     * 一堆「未完成 0.00s」的记录，还会把成功率的分母撑大。所以固定 toHistory=false。
     */
    fun addUiLog(entry: LogEntry) = addLog(entry, toHistory = false)

    fun clearLogs() = synchronized(logLock) { logs = emptyList() }

    private fun ensurePermission(): Boolean {
        if (!StellarShell.available()) { addLog("Stellar 未运行：请先打开 Stellar 并通过无线调试启动服务"); return false }
        if (StellarShell.hasPermission()) return true
        addLog("请求 Stellar 权限…（弹窗请点允许）")
        android.os.Handler(android.os.Looper.getMainLooper()).post { StellarShell.requestPermission(1001) }
        for (i in 0 until 40) {
            if (StellarShell.hasPermission()) { serviceGranted = true; addLog("Stellar 权限已授予"); return true }
            try { Thread.sleep(500) } catch (_: InterruptedException) { return false }
        }
        addLog("等待 Stellar 权限超时，请手动授予后重试")
        return false
    }

    private fun lockFile() = java.io.File(context.filesDir, "run.lock")

    private fun acquireRunLock(): Boolean {
        if (RunState.active) {
            addLog("上一次运行尚未结束，请等待完成")
            return false
        }
        val f = lockFile()
        // 进程重启/设备重启后，本进程没有活动运行，旧锁一律视为陈旧并清理。
        if (f.isFile()) f.delete()
        return try {
            f.writeText(System.currentTimeMillis().toString())
            RunState.active = true
            true
        } catch (e: Exception) { false }
    }

    private fun releaseRunLock() {
        RunState.active = false
        runCatching { lockFile().delete() }
    }

    fun runJailbreak() {
        if (running) return
        platformReason = SecurityCheck.reason(context)
        if (platformReason != null) {
            addLog("设备、内核或签名校验失败：" + platformReason)
            return
        }
        if (!canRunSelected) {
            addLog("当前选择的管理器未安装或版本不匹配，已拒绝开始；请安装固定版本后重试")
            return
        }
        runInfinity98260()
    }

    private fun runInfinity98260() {
        if (isSwitchingManager) { addLog("正在切换管理器，请稍后再试"); return }
        platformReason = SecurityCheck.reason(context)
        if (platformReason != null) {
            addLog("设备、内核或签名校验失败：" + platformReason)
            return
        }
        if (!acquireRunLock()) return
        // 引擎自带约束：失败后必须冷启动再重跑，否则会反复冲击已污染的内核。
        // 读取失败（例如 Stellar 未就绪）不阻断运行，交给后续服务检查处理。
        val bootNow = localBootId()
        val dirtyBoot = prefs.getString("failed_boot_id", "").orEmpty()
        if (bootNow.isNotBlank() && dirtyBoot.isNotBlank() && bootNow == dirtyBoot) {
            addLog("上次越狱未完成，内核可能已被污染；请重启手机后再试（本次已阻止运行）")
            stage = "需重启后再试"
            releaseRunLock()
            return
        }
        // 内核已加载单管理器模块时，late-load 会直接跳过，通用模块无法生效。
        // 此时继续运行只会让人误以为开关生效，必须要求冷启动。
        if (universalModuleEnabled && serviceReady) {
            val loaded = StellarShell.run("grep -c '^kernelsu ' /proc/modules 2>/dev/null || echo 0", 10).output.trim()
            val node = StellarShell.run("test -e /proc/kernelsu_manager && echo YES || echo NO", 10).output.trim()
            if ((loaded.toIntOrNull() ?: 0) > 0 && !node.contains("YES")) {
                addLog("内核已加载单管理器模块，通用模块无法在本次开机生效；请重启手机后再运行")
                stage = "需重启后再试"
                releaseRunLock()
                return
            }
        }
        // 有些管理器把 ksud 改名成随机库，引擎按 libksud.so 找不到会直接失败。
        // 已经有 root 时先把真 ksud 归一化回固定路径，保证本次与后续运行都可用。
        if (suCompatReady) {
            runCatching {
                StellarShell.run("su -c '" + KsudNormalizer.script(selectedPkg().orEmpty()) + "'", 40)
            }.getOrNull()?.let { r ->
                when (val parsed = KsudNormalizer.parse(r.output)) {
                    is KsudNormalizer.Result.Materialized -> addLog("加载器归一化：已用 " + parsed.source + " 补齐 libksud.so")
                    KsudNormalizer.Result.Missing -> addLog("加载器归一化：未在管理器 APK 里找到 ksud（-V 无版本行）")
                    else -> Unit
                }
            }
        }
        running = true
        softRebootIssued = false
        val runAdaptive = adaptiveManagerEnabled
        val runKind = selectedManager
        if (!RunState.headless) runCatching { JailbreakRunService.start(context) }
        val startBootId = bootNow
        progress = 0.01f
        stage = "检查 Stellar 服务"
        elapsedSeconds = 0L
        lastResultOk = null
        logs = emptyList()
        runCatching { File(context.filesDir, "onekey-live.log").delete() }
        HistoryStore.start(context, "cve98260", runKind, runAdaptive, BuildConfig.VERSION_NAME)
        addLog(RunLog.info("开始越狱", (profile?.displayName ?: "OnePlus") + " · " + BuildConfig.VERSION_NAME))
        // 开跑前拍一份环境快照：日志里能对上"跑之前的系统状态"，成功率也能复盘。
        val startSnap = BootWatch.snapshot()
        if (startSnap.valid) {
            addLog(
                RunLog.info(
                    "系统状态",
                    "boot_id " + startSnap.bootId.take(8) + " · system_server " +
                        startSnap.systemServerPid.ifBlank { "?" } + " · 已开机 " + startSnap.uptimeSec + "s"
                )
            )
        }
        // 开跑前重算一次：boot 桶、电量、线程数都要取"这一刻"的值。
        refreshRate()
        val preRate = successRate
        addLog(
            RunLog.info(
                "开跑前成功率",
                if (preRate.displayable) {
                    String.format(Locale.US, "%.0f%%（区间 %.0f~%.0f%%，样本 %d 次尝试）", preRate.probability, preRate.low, preRate.high, preRate.samples)
                } else {
                    preRate.rangeText + " · " + preRate.advice()
                }
            )
        )
        addLog(
            RunLog.info(
                "运行条件",
                preRate.bootText + " · 预计 " + preRate.attemptsPlanned + " 次尝试 · 单次约 " +
                    String.format(Locale.US, "%.1f", preRate.msPerAttempt / 1000.0) + " 秒 · 预算 " +
                    (preRate.budgetMs / 1000L) + " 秒 · " + preRate.envNote
            )
        )
        val t0 = System.currentTimeMillis()
        scope.launch { while (running) { elapsedSeconds = (System.currentTimeMillis() - t0) / 1000L; delay(1000L) } }
        scope.launch {
            var ok = false
            // 引擎遥测：本次运行真实跑了几次 attempt、命中几次、单次多久。
            // 成功率的统计样本就来自这里，而不是任何推断。
            var runTelemetry = EngineTelemetry()
            try {
                if (!ensurePermission()) { stage = "未获得权限"; return@launch }
                val chosen = managers.firstOrNull { it.kind == runKind } ?: run {
                    addLog("选择的管理器已不在列表中，已停止")
                    return@launch
                }
                var adapter: RuntimeExtractor.Adapter? = if (runAdaptive) runtimeAdapter else null
                if (runAdaptive && runKind == "finasu") {
                    if (adapter == null || adapter.pkg != chosen.pkg || adapter.versionCode != chosen.installedVersionCode) {
                        preparing = true
                        stage = "读取 FinaSU APK 并匹配 libksud"
                        adapter = try {
                            RuntimeExtractor.prepareFina(context, chosen.pkg, expectedCert[runKind] ?: throw IllegalStateException("缺少管理器信任证书")) { addLog(it) }
                                .also { runtimeAdapter = it }
                        } catch (e: Throwable) {
                            addLog("FinaSU 自适应失败，改用内置兼容组件：" + e.message)
                            adapter = null
                            runtimeAdapter = null
                            null
                        } finally {
                            preparing = false
                        }
                    }
                } else if (runAdaptive && (adapter == null || adapter.pkg != chosen.pkg || adapter.versionCode != chosen.installedVersionCode)) {
                    preparing = true
                    stage = "读取管理器 APK 并提取模块"
                    addLog("开始运行时适配：" + selectedManager)
                    adapter = try {
                        RuntimeExtractor.prepare(context, chosen.pkg, runKind, expectedCert[runKind] ?: throw IllegalStateException("缺少管理器信任证书")) { addLog(it) }
                            .also { runtimeAdapter = it }
                    } catch (e: Throwable) {
                        addLog("自适应提取失败，改用内置组件：" + e.message)
                        runtimeAdapter = null
                        null
                    } finally {
                        preparing = false
                    }
                }
                RunReport.reset()
                // 全部机型统一走按内核拆分的 98260 执行器（唯一通道）。
                val runner = profile
                when {
                    runner != null && OneKeyEngine.available(context, runner) -> {
                        stage = "运行 98260 执行引擎"
                        progress = 0.05f
                        ok = OneKeyEngine.run(
                            context, runner, chosen.kind,
                            { addLog(RunLog.fromEngine(it)) },
                            {
                                stage = it
                                HistoryStore.updateStage(it)
                                // 每进入一个阶段就落墓碑：进程若在此之后被重启拔掉，
                                // 下次启动能直接说出"停在哪一步之后"，不用靠时间戳猜。
                                RunTrace.mark(context, it)
                            },
                            { progress = it / 100f },
                            susfsEnabled, vfsEnabled, unloadKernelGuard, antiRootGuard,
                            universalModuleEnabled,
                            budgetMs = SuccessRate.budgetFor(runner.kmi),
                            onTelemetry = { t -> runTelemetry = t },
                        )
                    }
                    else -> {
                        stage = "执行器不可用"
                        addLog("当前内核的执行器不可用，已停止；请确认安装包完整，或重启手机后重试")
                        RunReport.stop("选择执行器", "执行器资产缺失或上次运行未正常退出")
                        ok = false
                    }
                }
            } catch (e: Throwable) {
                addLog("98260 异常: " + e)
                ok = false
            } finally {
                running = false
                if (!RunState.headless) {
                    runCatching {
                        JailbreakRunService.update(context, if (ok) "完成" else "未完成", if (ok) 1f else progress)
                    }
                    if (ok) {
                        scope.launch { kotlinx.coroutines.delay(3000); runCatching { JailbreakRunService.stop(context) } }
                    } else {
                        runCatching { JailbreakRunService.stop(context) }
                    }
                }
                releaseRunLock()
                lastResultOk = ok
                lastResultExploitKind = "cve98260"
                // 收尾阶段逐段归因：开关全关却仍然重启时，靠这两次快照点名到底是谁干的。
                if (ok) {
                    RunTrace.mark(context, "停用安全守护")
                    val beforeHarden = BootWatch.snapshot()
                    hardenAfterSuccess()
                    BootWatch.check(beforeHarden, "停用安全守护")?.let { addLog(RunLog.warn(it, "不是本应用发起的重启")) }
                    val nowBoot = ShizukuShell.run("cat /proc/sys/kernel/random/boot_id 2>/dev/null", 8).output.trim()
                    if (startBootId.isNotBlank() && nowBoot.isNotBlank() && nowBoot != startBootId) {
                        addLog(RunLog.warn("检测到用户态重启", "boot_id 变化，不是本应用发起"))
                    }
                }
                val wrote = RunReport.wroteKernel
                val where = RunReport.stoppedAt.ifBlank { stage }
                stage = when {
                    ok -> "98260 全链完成"
                    !wrote -> "停在「" + where + "」（未写内核，可直接重试）"
                    else -> "98260 未完成（已写内核，需重启后再试）"
                }
                if (ok) progress = 1f
                addLog(
                    when {
                        ok -> "98260 全链完成；重启后需要重新越狱。"
                        !wrote -> "本次停在「" + where + "」，没有写入内核；可以直接再点一次，不用重启。" +
                            (if (RunReport.reason.isNotBlank()) "（原因：" + RunReport.reason + "）" else "")
                        else -> "98260 已写入内核但未拿到 root；请重启后再试，或导出日志反馈。"
                    }
                )
                persistLogs()
                if (serviceReady) {
                    val nativeSu = StellarShell.run("timeout 6 su -c 'id -u' 2>/dev/null; true", 15).output.trim()
                    val overlaySu = StellarShell.run("[ -x /system_ext/bin/su ] && grep -q su_compat /proc/mounts && echo READY || echo MISSING", 15)
                    suCompatReady = nativeSu == "0" || overlaySu.output.contains("READY")
                }
                // 失败且已进入内核写入阶段 → 记录本次 boot，强制下次先重启。
                if (ok) {
                    prefs.edit().remove("failed_boot_id").putString("success_boot_id", startBootId).apply()
                    rootedThisBoot = startBootId.isNotBlank() && startBootId == localBootId()
                    // 管理器升级会重建 lib 目录，加载器被还原；此时自动补装，避免下次开机又变回单管理器。
                    if (hotSwitchWanted && !hotSwitchLoaderInstalled) {
                        addLog(RunLog.info("通用加载器缺失", "管理器可能已升级，正在自动重装"))
                        installHotSwitchLoader { _, _ -> }
                    }
                    // 软重启只由本应用按开关显式执行；关闭时保持当前会话，绝不自动重启。
                    // 这里刻意重新读一次 prefs，而不是用内存里的 softRebootAfter：
                    // 内存态可能被组合快照、进程重建、旧版本残留值带偏，只有落盘值才是用户当下真正选的。
                    val rebootWanted = softRebootWanted()
                    if (rebootWanted != softRebootAfter) {
                        addLog(RunLog.warn("软重启开关状态已校正", "内存 " + softRebootAfter + " · 落盘 " + rebootWanted))
                        softRebootAfter = rebootWanted
                    }
                    if (rebootWanted && !softRebootIssued) {
                        softRebootIssued = true
                        // 墓碑必须在下发前落盘：setprop 之后进程可能立刻没了。
                        // intentional=true：这是用户开的开关，下次启动别报成异常重启。
                        RunTrace.mark(context, "软重启下发", intentional = true)
                        addLog(RunLog.info("按设置执行软重启", "只重启用户态，内核模块保持加载"))
                        val before = BootWatch.snapshot()
                        val rc = StellarShell.run("su -c 'setprop ctl.restart zygote' 2>&1", 20).code
                        addLog(
                            if (rc == 0) RunLog.success("软重启已下发", "zygote 将重启一次")
                            else RunLog.error("软重启下发失败", "返回码 " + rc)
                        )
                        BootWatch.check(before, "软重启下发")?.let { addLog(RunLog.info("重启结果", it)) }
                    } else if (!rebootWanted) {
                        addLog(RunLog.info("保持当前会话", "未开启越狱后软重启，本次不下发任何重启命令"))
                    }
                } else if (RunReport.wroteKernel) {
                    prefs.edit().putString("failed_boot_id", startBootId).apply()
                    addLog(RunLog.warn("下次运行前需要重启手机", "本次已写入内核但未拿到 root"))
                }
                HistoryStore.finish(ok, stage, elapsedSeconds)
                // 把本次真实 attempt / 命中落成统计样本：成功率从此有实测出处。
                runCatching { recordRunSample(runTelemetry, ok, startBootId, t0) }
                invalidateSamples()
                // 走到这里说明本进程活到了收尾：墓碑使命完成，清掉，
                // 否则下次启动会把"正常结束"误报成"死在某个阶段"。
                RunTrace.clear(context)
                refreshStats()
                refreshRootedFlag()
                refreshRate()
                refreshState()
            }
        }
    }

    /** 权限修复：不跑漏洞链，只修 su_compat / 授权刷新 / 管理器登记。 */
    fun repairPermissions() {
        platformReason = SecurityCheck.reason(context)
        if (platformReason != null) { addLog("校验失败，已拒绝：" + platformReason); return }
        if (running) {
            addLog("正在运行，请稍候")
            return
        }
        running = true
        logs = emptyList()
        stage = "权限修复"
        progress = 0.1f
        lastResultOk = null
        addLog("========== 权限修复 ==========")
        scope.launch {
            var ok = false
            try {
                if (!ensurePermission()) { stage = "未获得权限"; return@launch }
                val probe = StellarShell.run("su -c id", 15)
                if (probe.code != 0 || !probe.output.contains("uid=0")) {
                    addLog("当前没有 root：请先点「一键越狱」，成功后再执行权限修复。")
                    if (probe.output.isNotBlank()) addLog(probe.output.trim())
                    stage = "无 root，请先越狱"
                    return@launch
                }
                progress = 0.5f
                addLog("root 已就绪，开始刷新 su_compat / 授权 / 管理器登记…")
                val script = "su -c '/data/adb/ksud feature set su_compat 1' 2>&1; " +
                    "su -c '/data/adb/ksud debug mark refresh' 2>&1; " +
                    "su -c '/data/adb/ksud manager set-enabled true' 2>&1; " +
                    "su -c '/data/adb/ksud manager list' 2>&1; " +
                    "su -c 'id' 2>&1"
                val r = StellarShell.run(script, 60)
                r.output.lines().forEach { if (it.isNotBlank()) addLog(it) }
                ok = r.code == 0
                addLog("权限修复结束 (exit=" + r.code + ")")
                addLog("已授权应用请彻底关闭后重新打开，即可通过默认 su 获取 root。")
                stage = if (ok) "权限修复完成" else "权限修复未完成"
                progress = 1f
            } catch (e: Throwable) {
                addLog("异常: " + e)
                stage = "权限修复失败"
            } finally {
                running = false
                lastResultOk = ok
                persistLogs()
                refreshState()
            }
        }
    }

    fun repair() {
        if (running) {
            addLog("正在运行，请稍候")
            return
        }
        addLog("修复模式：内核已加载时只修复用户态/SU/管理器；未加载时执行完整越狱。")
        runJailbreak()
    }

    private fun persistLogs() {
        val text = logs.joinToString("\n")
        runCatching { File(context.filesDir, "onekey-last.log").writeText(text) }
        runCatching {
            if (ShizukuShell.available() && ShizukuShell.hasPermission()) {
                ShizukuShell.stageBytes(text.toByteArray(Charsets.UTF_8), "/data/local/tmp/onekey-last.log", 644)
            }
        }
    }

    fun selfCheck() {
        if (running) { addLog("正在运行，请稍候"); return }
        scope.launch {
            if (!ensurePermission()) return@launch
            stage = "自检中"
            val script = "id; getenforce; uname -r; grep -i kernelsu /proc/modules; " +
                "su -c id; su -M -c id; /system/bin/su -c id; " +
                "[ -x /data/adb/ksud ] && { su -c '/data/adb/ksud debug info' 2>/dev/null; su -c '/data/adb/ksud feature list' 2>/dev/null; } || echo KSUD_MISSING; " +
                "ls -l /system_ext/bin/su 2>&1; grep -i onekey /proc/mounts 2>&1; " +
                "ls -ld /data/local/tmp/onekey* 2>&1; pm list packages -d | grep exsystemservice"
            val r = StellarShell.run(script, 60)
            r.output.lines().forEach { if (it.isNotBlank()) addLog(it) }
            addLog("自检结束 (exit=" + r.code + ")")
            refreshState()
            stage = "就绪"
        }
    }

    /** 采集设备诊断（供远程定位）：内核模块、管理器、su 通道、ksud 状态。 */
    fun collectDiagnostics() {
        scope.launch {
            stage = "采集诊断"
            addLog("===== 设备诊断 =====")
            val script = """
                echo "[uname]"; uname -r
                echo "[selinux]"; getenforce
                echo "[modules]"; grep -i kernelsu /proc/modules
                echo "[system/bin/su]"; ls -l /system/bin/su 2>&1
                echo "[ksud]"; ls -l /data/adb/ksud 2>&1
                echo "[ksu bin]"; ls -l /data/adb/ksu/bin 2>&1
                echo "[mounts]"; grep -iE 'onekey|ksu' /proc/mounts 2>&1 | head -10
                echo "[current app uid su]"; /system/bin/su -c 'id -u' 2>&1
                echo "[ksud]"; [ -x /data/adb/ksud ] && { su -c '/data/adb/ksud debug info' 2>&1 | head -20; su -c '/data/adb/ksud feature list' 2>&1 | head -20; su -c '/data/adb/ksud manager list' 2>&1 | head -10; } || echo KSUD_MISSING
                echo "[dmesg kernelsu]"; su -c 'dmesg | grep -i kernelsu | tail -40' 2>&1
                echo "[console tail]"; tail -80 /data/local/tmp/onekey-console.log 2>&1
                echo "[post marker]"; cat /data/local/tmp/onekey-*/post_stage.ok 2>&1 | tail -3
                echo "[oplus modules]"; grep -iE 'oplus_secure|oplus_security' /proc/modules 2>&1
                echo "[ksud soft-reboot]"; /data/adb/ksud debug version 2>&1 | head -2
            """.trimIndent()
            val r = StellarShell.run(script, 90)
            r.output.lines().forEach { line -> if (line.isNotBlank()) addLog(line) }
            addLog("===== 诊断结束 =====")
            stage = "诊断完成，请导出 TXT"
        }
    }

    /** 直接从本应用 UID 执行 su，验证管理器授权是否真正生效。 */
    fun diagnoseRoot() {
        scope.launch {
            stage = "root 诊断"
            addLog("== root 诊断（本应用 UID=" + android.os.Process.myUid() + "） ==")
            addLog("请先在管理器里给「OneKey」授权 root，再点一次诊断。")
            val probe = dev.onekey.jb.RootDiagnostics.PROBE
            val commands = listOf(
                "应用进程：绝对路径 su" to arrayOf("/system/bin/su", "-c", probe),
                "应用进程：PATH 查找 su（libsu 常用）" to arrayOf("su", "-c", probe),
                "应用进程 sh + 绝对路径 su" to arrayOf("/system/bin/sh", "-c", "/system/bin/su -c " + probe),
                "应用进程 sh + PATH su" to arrayOf("/system/bin/sh", "-c", "su -c " + probe),
                "兼容入口 /system_ext/bin/su" to arrayOf("/system_ext/bin/su", "-c", probe)
            )
            var appDirectOk = false
            var appPathOk = false
            var shellAbsoluteOk = false
            for ((index, entry) in commands.withIndex()) {
                val (label, cmd) = entry
                val result = dev.onekey.jb.RootDiagnostics.run(cmd, "", 20)
                addLog(label + "：退出码=" + result.code)
                for (line in result.output.split("\n")) if (line.trim().isNotEmpty()) addLog("  " + line.trim())
                val ok = dev.onekey.jb.RootDiagnostics.suPassed(result.code, result.output) ||
                    (result.code == 0 && result.output.contains("uid=0(root)"))
                if (index == 0) appDirectOk = ok
                if (index == 1) appPathOk = ok
                if (index == 2) shellAbsoluteOk = ok
            }
            val appRootOk = appDirectOk || appPathOk || shellAbsoluteOk
            addLog("应用进程 root：" + if (appRootOk) "已生效 ✓" else "未生效 ✗")
            if (!appPathOk) {
                addLog("提示：PATH 查找 su 未通过，说明该 shell 入口被系统策略拦截；使用 libsu/绝对路径的应用不受影响。")
            }
            if (!appRootOk) {
                addLog("若管理器里已授权仍失败：在管理器中先撤销再重新授权，然后彻底关闭本应用再打开。")
            }
            stage = if (appRootOk) "root 已生效" else "root 未生效"
        }
    }

    fun moduleConflictCheck() {
        scope.launch {
            if (!ensurePermission()) return@launch
            addLog("== 模块冲突自检 ==")
            val mods = StellarShell.run("ls /data/adb/modules 2>/dev/null; echo ---; su -c 'ls /data/adb/modules' 2>/dev/null", 30)
            mods.output.lines().forEach { if (it.isNotBlank() && it.trim() != "---") addLog("  模块: " + it.trim()) }
            val conflicts = listOf("zygisksu", "zygisk_next", "lsposed", "mountify", "fix_soft_reboot", "scene_systemless")
            val installed = conflicts.filter { mods.output.contains(it) }
            if (installed.isEmpty()) {
                addLog("未发现已知冲突模块；可以正常越狱。")
            } else {
                addLog("发现已知冲突模块: " + installed.joinToString(", "))
                addLog("建议在管理器中先禁用以上模块并重启，再执行一键越狱。")
            }
        }
    }

    fun cleanupOldCompatMounts() {
        scope.launch {
            val script = "for m in \$(awk '\$1==\"onekey-su\"{print \$2}' /proc/mounts 2>/dev/null); do umount \"\$m\" 2>/dev/null; done; " +
                "rm -rf /data/adb/onekey-su-compat /data/local/tmp/onekey /data/local/tmp/ksu-op3.ko; " +
                "echo CLEAN_DONE; grep -i onekey /proc/mounts 2>/dev/null || true"
            val r = StellarShell.execSu(script, 30)
            r.output.lines().forEach { if (it.isNotBlank()) addLog(it) }
            addLog("旧兼容挂载清理结束 (exit=" + r.code + ")")
            refreshState()
        }
    }

    fun openStellar() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("roro.stellar.manager")
            if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent) }
            else addLog("未安装 Stellar 管理器")
        } catch (e: Exception) { addLog("打开 Stellar 失败: " + e) }
    }

    /** 与 Onuce 的 harden_after 对齐：仅会话成功后停用安全守护/安全中心，不动截屏组件。 */
    private fun hardenAfterSuccess() {
        if (!antiRootGuard) return
        addLog("会话完成：按设置停用安全守护/安全中心（未停用截屏组件）")
        Safer.disable(::addLog)
    }
}
