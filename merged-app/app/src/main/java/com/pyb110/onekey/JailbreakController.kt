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
    private val UNIVERSAL_KSUD_SIZE = 12335680
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
    )


    val profile: DeviceProfile? = DeviceProfiles.current()
    var platformReason: String? by mutableStateOf(null); private set
    var managers: List<ManagerOption> by mutableStateOf(emptyList()); private set
    var selectedManager: String by mutableStateOf(loadSelectedManager()); private set
    /** 不再提供开关：内核模块与 ksud 一律从已安装管理器 APK 运行时提取。 */
    val adaptiveManagerEnabled: Boolean = true
    var runtimeAdapter: RuntimeExtractor.Adapter? by mutableStateOf(null); private set
    var preparing: Boolean by mutableStateOf(false); private set
    var antiRootGuard: Boolean by mutableStateOf(prefs.getBoolean("antiroot", true)); private set
    var cloudAuto: Boolean by mutableStateOf(prefs.getBoolean("cloud_auto", true)); private set
    var updateStatus: String by mutableStateOf("尚未检查更新"); private set
    var checkingUpdate: Boolean by mutableStateOf(false); private set
    /** ColorOS 17 安全机制会拦截原生内核调用；越狱后软重启用户态可恢复 su（内核 LKM 保持加载）。 */
    var softRebootFix: Boolean by mutableStateOf(prefs.getBoolean("soft_reboot_fix", true)); private set
    /** 越狱后卸载内核安全防护（oplus_secure_guard 等）。 */
    var unloadKernelGuard: Boolean by mutableStateOf(prefs.getBoolean("unload_kernel_guard", true)); private set
    /** SUSFS 隐藏支持（需要执行器侧加载模块）。 */
    var susfsEnabled: Boolean by mutableStateOf(prefs.getBoolean("susfs_enabled", false)); private set
    /** VFS（hybridmount）支持，仅 6.18 内核。 */
    var vfsEnabled: Boolean by mutableStateOf(prefs.getBoolean("vfs_enabled", false)); private set
    var running: Boolean by mutableStateOf(false); private set
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
    var loaderBusy: Boolean by mutableStateOf(false); private set
    /** 本次开机是否已成功越狱（与 boot_id 绑定，重启后自动失效）。 */
    var rootedThisBoot: Boolean by mutableStateOf(false); private set

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

    private fun refreshRootedFlag() {
        val saved = prefs.getString("success_boot_id", "").orEmpty()
        rootedThisBoot = saved.isNotBlank() && saved == localBootId()
    }

    init {
        HistoryStore.attach(context)
        refreshRootedFlag()
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
                val tail = f.readLines().takeLast(400)
                if (tail.isNotEmpty()) {
                    logs = tail + "—— 以上为上次运行记录（可能被软重启中断）——"
                }
            }
        }
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
        managers = buildManagers()
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
                    managerLabelFromProfile(raw)
                } else ""
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

    private fun buildManagers(): List<ManagerOption> {
        val entries = installedEntries()
        val byCert = entries.groupBy { it.cert }
        val defs = listOf(
            Triple("next", "KernelSU-Next", "com.rifsxd.ksunext") to 33355,
            Triple("kernelsu", "KernelSU 官方", "me.weishu.kernelsu") to 32680,
            Triple("sukisu", "SukiSU Ultra", "com.sukisu.ultra") to 41140,
            Triple("bakasu", "BakaSU", "com.resukisu.resukisu") to 35171,
            Triple("finasu", "FinaSU", "me.yuki.fina") to 33168,
        )
        return defs.map { (triple, expected) ->
            val (kind, label, staticPkg) = triple
            val cert = expectedCert[kind]
            val candidates = if (cert == null) emptyList() else byCert[cert].orEmpty()
            val chosen = when {
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
        addLog(if (enabled) "已开启：越狱后临时禁用 ColorOS 安全弹窗" else "已关闭安全弹窗处理")
    }

    fun updateCloudAuto(enabled: Boolean) {
        cloudAuto = enabled
        prefs.edit().putBoolean("cloud_auto", enabled).apply()
        addLog(if (enabled) "已开启自动匹配云端组件" else "已关闭自动匹配云端组件")
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
        addLog(if (enabled) "已开启：越狱后卸载内核安全防护" else "已关闭内核安全防护卸载")
    }

    fun updateSusfs(enabled: Boolean) {
        susfsEnabled = enabled
        prefs.edit().putBoolean("susfs_enabled", enabled).apply()
        addLog(if (enabled) "已开启 SUSFS 支持（执行器加载模块后生效）" else "已关闭 SUSFS 支持")
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
            try {
                val staged = "/data/local/tmp/onekey-universal-ksud"
                if (ShizukuShell.stageAsset(context, "core/exec/ksud-universal", staged, 755).code != 0) {
                    return@launch finishLoader(false, "通用加载器投放失败", onDone)
                }
                val r = StellarShell.run("su -c '" + loaderScript("install", staged) + "'", 60)
                val ok = r.output.contains("OK") && !r.output.contains("FAIL")
                if (ok) {
                    hotSwitchLoaderInstalled = true
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
                if (ok) { hotSwitchLoaderInstalled = false; addLog("已还原管理器原加载器") }
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
        addLog(if (enabled) "已开启 VFS 支持（仅 6.18 内核生效）" else "已关闭 VFS 支持")
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

    fun updateSoftRebootFix(enabled: Boolean) {
        softRebootFix = enabled
        prefs.edit().putBoolean("soft_reboot_fix", enabled).apply()
        addLog(if (enabled) "已开启「越狱后软重启修复」：ColorOS 安全机制拦截时自动软重启恢复 su" else "已关闭「越狱后软重启修复」")
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

    fun addLog(line: String) {
        val stamped = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + line
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
            HistoryStore.append(stamped)
        }
    }

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
        running = true
        val runAdaptive = adaptiveManagerEnabled
        val runKind = selectedManager
        runCatching { JailbreakRunService.start(context) }
        val startBootId = bootNow
        progress = 0.01f
        stage = "检查 Stellar 服务"
        elapsedSeconds = 0L
        lastResultOk = null
        logs = emptyList()
        runCatching { File(context.filesDir, "onekey-live.log").delete() }
        HistoryStore.start(context, "cve98260", runKind, runAdaptive, BuildConfig.VERSION_NAME)
        addLog("========== OneKey · " + (profile?.displayName ?: "OnePlus") + " · 98260 ==========")
        val t0 = System.currentTimeMillis()
        scope.launch { while (running) { elapsedSeconds = (System.currentTimeMillis() - t0) / 1000L; delay(1000L) } }
        scope.launch {
            var ok = false
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
                // 新内核优先：98260 执行引擎（若已放入 core/exec/onekey-98260）→ 自研执行器 → onuce
                val isNewKernel = profile?.onuceAsset?.contains("_201") == true
                if (isNewKernel && OneKeyEngine.available(context)) {
                    stage = "运行 98260 执行引擎"
                    progress = 0.05f
                    ok = OneKeyEngine.run(
                        context, chosen.kind,
                        { addLog(it) },
                        { stage = it; HistoryStore.updateStage(it) },
                        { progress = it / 100f },
                        susfsEnabled, vfsEnabled, unloadKernelGuard, antiRootGuard, softRebootFix,
                        universalModuleEnabled
                    )
                } else if (isNewKernel && NativeExecutor.available(context)) {
                    stage = "运行自研执行器"
                    progress = 0.05f
                    ok = NativeExecutor.run(
                        context, chosen.kind,
                        { addLog(it) },
                        { stage = it; HistoryStore.updateStage(it) },
                        { progress = it / 100f }
                    )
                } else {
                    stage = "运行 98260 实验链"
                    progress = 0.05f
                    ok = Cve98260Engine.run(
                        context,
                        chosen.pkg,
                        runKind,
                        adapter,
                        antiRootGuard,
                        false,
                        softRebootFix,
                        logSink = { addLog(it) },
                        stageSink = { stage = it; HistoryStore.updateStage(it) },
                        progressSink = { progress = it / 100f }
                    )
                }
            } catch (e: Throwable) {
                addLog("98260 异常: " + e)
                ok = false
            } finally {
                running = false
                runCatching {
                    JailbreakRunService.update(context, if (ok) "完成" else "未完成", if (ok) 1f else progress)
                }
                if (ok) {
                    scope.launch { kotlinx.coroutines.delay(3000); runCatching { JailbreakRunService.stop(context) } }
                } else {
                    runCatching { JailbreakRunService.stop(context) }
                }
                releaseRunLock()
                lastResultOk = ok
                lastResultExploitKind = "cve98260"
                if (ok) hardenAfterSuccess()
                // 本 App 不主动软重启（避免与引擎/系统行为混淆）；
                // 只做归因：若 boot_id 变了，说明发生了用户态重启
                if (ok) {
                    val nowBoot = ShizukuShell.run("cat /proc/sys/kernel/random/boot_id 2>/dev/null", 8).output.trim()
                    if (startBootId.isNotBlank() && nowBoot.isNotBlank() && nowBoot != startBootId) {
                        addLog("提示：检测到用户态重启（boot_id 变化），这不是 App 主动触发的软重启")
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
                } else if (RunReport.wroteKernel) {
                    prefs.edit().putString("failed_boot_id", startBootId).apply()
                    addLog("已记录：下次运行前需要重启手机")
                }
                HistoryStore.finish(ok, stage, elapsedSeconds)
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
