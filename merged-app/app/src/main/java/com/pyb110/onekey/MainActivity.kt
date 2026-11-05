package com.pyb110.onekey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.blur.drawBackdrop
import io.github.suqi8.coui.kmp.blur.blur
import io.github.suqi8.coui.kmp.blur.progressiveBlur
import io.github.suqi8.coui.kmp.blur.ProgressiveBlur
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.icon.extended.Clear
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Help
import io.github.suqi8.coui.kmp.icon.extended.Home
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.icon.extended.Link
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.icon.extended.Recent
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Report
import io.github.suqi8.coui.kmp.icon.extended.ScreenCapture
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.icon.extended.Tasks
import io.github.suqi8.coui.kmp.icon.extended.Theme
import io.github.suqi8.coui.kmp.icon.extended.Unlock
import io.github.suqi8.coui.kmp.icon.extended.Update
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.LocalDismissState
import io.github.suqi8.coui.kmp.theme.ThemeController
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
import io.github.suqi8.coui.kmp.utils.rememberCouiHaptic
import io.github.suqi8.coui.kmp.window.WindowDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private fun loadThemeMode(): String =
        getSharedPreferences("onekey", MODE_PRIVATE).getString("theme_mode", "pink") ?: "pink"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { JailbreakController.get(applicationContext) }
            var themeMode by remember { mutableStateOf(loadThemeMode()) }
            val themeController = remember(themeMode) {
                ThemeController(
                    ColorSchemeMode.MonetSystem,
                    keyColor = if (themeMode == "pink") Color(0xFFFFC0CB) else null
                )
            }
            COUITheme(controller = themeController) {
                AppRoot(
                    controller = controller,
                    themeMode = themeMode,
                    onToggleTheme = {
                        themeMode = if (themeMode == "pink") "dynamic" else "pink"
                        getSharedPreferences("onekey", MODE_PRIVATE)
                            .edit().putString("theme_mode", themeMode).apply()
                    }
                )
            }
        }
    }
}

@Composable
private fun AppRoot(controller: JailbreakController, themeMode: String, onToggleTheme: () -> Unit) {
    val haptic = rememberCouiHaptic()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val noticePrefs = context.getSharedPreferences("onekey", Context.MODE_PRIVATE)
    var showNotice by remember { mutableStateOf(!noticePrefs.getBoolean("notice_suppressed", false)) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.Info?>(null) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(UpdateChecker.Progress(0, 0L, 0L, 0L)) }
    var updateExpandedVersion by remember { mutableIntStateOf(0) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var show98260Warning by remember { mutableStateOf(false) }
    var showFolkPrompt by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showThirdParty by remember { mutableStateOf(false) }
    var reloadHistoryTick by remember { mutableIntStateOf(0) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        controller.refreshState()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val act = context as? android.app.Activity
        if (act?.intent?.getBooleanExtra("prepare_adaptive", false) == true) {
            kotlinx.coroutines.delay(1200)
            controller.prepareRuntimeAdapter()
        }
        withContext(Dispatchers.IO) {
            runCatching { UpdateChecker.check(BuildConfig.VERSION_CODE) }.getOrNull()
        }?.let {
            updateExpandedVersion = it.versionCode
            updateInfo = it
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(controller.logs.joinToString("\n").toByteArray())
                }
                scope.launch { snackbarHostState.showSnackbar("日志已导出") }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        containerColor = COUITheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        val backdrop = rememberLayerBackdrop()
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val screenContentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = statusBarTop + 92.dp,
            bottom = navBarBottom + 92.dp
        )

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            ) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "tab"
                ) { currentTab ->
                    when (currentTab) {
                        0 -> HomeScreen(
                            controller = controller,
                            haptic = haptic,
                            onDiagnostics = { showDiagnostics = true },
                            onOpenLog = { tab = 1 },
                            onRunJailbreak = {
                                show98260Warning = true
                            },
                            contentPadding = screenContentPadding
                        )
                        1 -> LogScreen(
                            controller = controller,
                            haptic = haptic,
                            snackbarHostState = snackbarHostState,
                            scope = scope,
                            contentPadding = screenContentPadding,
                            onGoHome = { tab = 0 },
                            onExport = { exportLauncher.launch("onekey-log-" + System.currentTimeMillis() + ".txt") }
                        )
                        2 -> HistoryScreen(
                            controller = controller,
                            haptic = haptic,
                            scope = scope,
                            snackbarHostState = snackbarHostState,
                            contentPadding = screenContentPadding,
                            reloadTick = reloadHistoryTick,
                            onGoHome = { tab = 0 },
                            onClearRequest = { showClearHistoryConfirm = true }
                        )
                        3 -> AboutScreen(
                            controller = controller,
                            themeMode = themeMode,
                            onToggleTheme = onToggleTheme,
                            haptic = haptic,
                            onOpenLog = { tab = 1 },
                            onNotice = { showNotice = true },
                            onManual = { showManual = true },
                            onThirdParty = { showThirdParty = true },
                            contentPadding = screenContentPadding
                        )
                    }
                }
            }

            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusBarTop + 1.dp)
                    .background(COUITheme.colorScheme.background)
            )

            TopBar(
                backdrop = backdrop,
                controller = controller,
                tab = tab,
                onLogCopy = { onCopy(context, controller, scope, snackbarHostState) },
                onLogExport = { exportLauncher.launch("onekey-log-" + System.currentTimeMillis() + ".txt") },
                onLogClear = { controller.clearLogs(); scope.launch { snackbarHostState.showSnackbar("日志已清空") } },
                onHistoryRefresh = { reloadHistoryTick++; scope.launch { snackbarHostState.showSnackbar("已刷新历史记录") } },
                onHistoryClear = { showClearHistoryConfirm = true },
                modifier = Modifier.align(Alignment.TopCenter)
            )

            AppBottomBar(
                backdrop = backdrop,
                selected = tab,
                onSelect = { haptic(CouiHapticEffect.Switch); tab = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            if (showDiagnostics) {
                DiagnosticsDialog(
                    controller = controller,
                    onDismiss = { showDiagnostics = false },
                    onOpenLog = { tab = 1 },
                    onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                )
            }

            if (showManual) {
                WindowDialog(title = "使用手册", show = true, onDismissRequest = { showManual = false }) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp)
                    ) {
                        ManualSection("一、准备", listOf(
                            "安装要用的管理器（KernelSU / KernelSU-Next / SukiSU / BakaSU / FinaSU 之一），App 不再内置管理器和模块。",
                            "启动 Stellar（推荐）或 Shizuku 服务，回到 App 授予权限；首页显示「服务已连接并授权」才能继续。"
                        ))
                        ManualSection("二、一键越狱", listOf(
                            "点「运行 98260 越狱链」，确认后等待日志出现「越狱完成」。",
                            "失败时日志会写明停在哪一步；只有在真的写入内核后才会提示重启，未写入可直接再试。"
                        ))
                        ManualSection("三、拓展功能（5 项）", listOf(
                            "越狱后禁用安全弹窗：停用安全守护/安全中心，不影响截屏。",
                            "越狱后软重启：系统拦截原生调用时，软重启用户态恢复 root。",
                            "越狱后卸载内核安全防护：卸载 oplus_secure_guard 一类内核防护模块。",
                            "SUSFS 支持：越狱后加载隐藏模块（需要执行器侧配合）。",
                            "VFS 支持：加载 hybridmount 做路径重定向，仅 6.18 内核生效。"
                        ))
                        ManualSection("四、异常处理", listOf(
                            "提示「未写内核，可直接重试」：直接再点一次即可。",
                            "提示「已写内核但未拿到 root」：重启手机后再试，并导出日志。",
                            "管理器未授权：先在管理器里给 OneKey 授权 root，再回到 App 运行。"
                        ))
                        DialogActions(
                            DialogAction("知道了", COUIIcons.Ok, primary = true) { showManual = false }
                        )
                    }
                }
            }

            if (showThirdParty) {
                WindowDialog(title = "第三方组件说明", show = true, onDismissRequest = { showThirdParty = false }) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp)
                    ) {
                        ManualSection("感谢以下项目", listOf(
                            "COUI（github.com/suqi8/coui）",
                            "Stellar（github.com/roro2239/Stellar）",
                            "onuce",
                            "Shark",
                            "InfinityCore",
                            "KernelSU、KernelSU-Next、SukiSU Ultra、BakaSU、FinaSU"
                        ))
                        ManualSection("说明", listOf(
                            "以上项目的版权归各自作者所有，在此一并致谢。",
                            "完整许可见项目 THIRD_PARTY_NOTICES.md 与各上游仓库。"
                        ))
                        DialogActions(
                            DialogAction("知道了", COUIIcons.Ok, primary = true) { showThirdParty = false }
                        )
                    }
                }
            }
            if (showNotice) {
                AppreciationDialog(
                    onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    onDismiss = { suppress ->
                        showNotice = false
                        noticePrefs.edit().putBoolean("notice_suppressed", suppress).apply()
                    }
                )
            }

            LaunchedEffect(controller.lastResultOk, controller.running, controller.lastResultExploitKind) {
                if (controller.lastResultOk == true && !controller.running && controller.lastResultExploitKind == "cve98260") {
                    showFolkPrompt = true
                }
            }

            if (show98260Warning) {
                WindowDialog(title = "98260 运行前确认", show = true, onDismissRequest = { show98260Warning = false }) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        Text("失败后可直接再点一次；只有内核状态异常时才需要硬重启。", style = COUITheme.textStyles.body2)
                        Text(
                            "执行过程中将短暂提权并向内核装载特权模块。",
                            style = COUITheme.textStyles.body2,
                            color = COUITheme.colorScheme.onSurfaceContainerVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        DialogActions(
                            DialogAction("取消", COUIIcons.Close) { show98260Warning = false },
                            DialogAction("确认开始", COUIIcons.Play, primary = true) {
                                show98260Warning = false
                                controller.runJailbreak()
                                tab = 1
                            }
                        )
                    }
                }
            }

            if (showFolkPrompt) {
                WindowDialog(title = "98260 越狱完成", show = true, onDismissRequest = { showFolkPrompt = false }) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        Text("98260 临时 root 通道已就绪，特权内核模块已加载。本次开机不再允许重复运行。", style = COUITheme.textStyles.body2)
                        DialogActions(
                            DialogAction("稍后", COUIIcons.Close) { showFolkPrompt = false },
                            DialogAction("打开管理器", COUIIcons.Settings, primary = true) {
                                showFolkPrompt = false
                                val pkg = controller.managers.firstOrNull { it.kind == controller.selectedManager }?.pkg
                                runCatching {
                                    context.startActivity(
                                        context.packageManager.getLaunchIntentForPackage(pkg ?: "")
                                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        )
                    }
                }
            }

            if (showClearHistoryConfirm) {
                WindowDialog(
                    title = "清空历史记录",
                    show = true,
                    onDismissRequest = { showClearHistoryConfirm = false }
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        Text(
                            "确定清空全部越狱历史与诊断日志吗？\n此操作为物理删除且无法恢复。",
                            style = COUITheme.textStyles.body2
                        )
                        DialogActions(
                            DialogAction("取消", COUIIcons.Close) { showClearHistoryConfirm = false },
                            DialogAction("确认清空", COUIIcons.Delete, primary = true) {
                                showClearHistoryConfirm = false
                                haptic(CouiHapticEffect.Switch)
                                scope.launch(Dispatchers.IO) {
                                    HistoryStore.clear(context)
                                    reloadHistoryTick++
                                }
                                scope.launch { snackbarHostState.showSnackbar("历史记录已清空") }
                            }
                        )
                    }
                }
            }

            updateInfo?.let { info ->
                WindowDialog(
                    title = "发现新版本 · v" + info.versionName + " (" + info.versionCode + ")",
                    show = true,
                    onDismissRequest = { if (!updateDownloading) updateInfo = null }
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        Text(
                            "更新时间 " + UpdateChecker.formatTime(info.publishedAt) +
                                (if (info.size > 0) " · 安装包 " + UpdateChecker.formatSize(info.size) else ""),
                            style = COUITheme.textStyles.body2,
                            color = COUITheme.colorScheme.onSurfaceContainerVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        if (info.versions.isEmpty()) {
                            Text(
                                info.changelog.ifBlank { "无更新说明" },
                                style = COUITheme.textStyles.body2,
                                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 240.dp)
                            )
                        } else {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                info.versions.forEach { note ->
                                    VersionNoteRow(
                                        note = note,
                                        expanded = updateExpandedVersion == note.versionCode,
                                        onToggle = {
                                            haptic(CouiHapticEffect.Switch)
                                            updateExpandedVersion =
                                                if (updateExpandedVersion == note.versionCode) -1 else note.versionCode
                                        }
                                    )
                                }
                            }
                        }
                        updateError?.let { err ->
                            Text(err, color = COUITheme.colorScheme.error, style = COUITheme.textStyles.body2, modifier = Modifier.padding(top = 8.dp))
                        }
                        if (updateDownloading) {
                            LinearProgressIndicator(
                                progress = updateProgress.percent / 100f,
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "下载中 " + updateProgress.percent + "%",
                                    style = COUITheme.textStyles.body2,
                                    color = COUITheme.colorScheme.primary
                                )
                                Text(
                                    UpdateChecker.formatSpeed(updateProgress.bytesPerSec) +
                                        " · 剩余 " + UpdateChecker.formatSize(updateProgress.remaining),
                                    style = COUITheme.textStyles.body2,
                                    color = COUITheme.colorScheme.onSurfaceContainerVariant
                                )
                            }
                        }
                        DialogActions(
                            DialogAction("稍后", COUIIcons.Close, enabled = !updateDownloading) { updateInfo = null },
                            DialogAction(
                                if (updateDownloading) "下载中…" else "下载并安装",
                                COUIIcons.Download,
                                primary = true,
                                enabled = !updateDownloading
                            ) {
                                updateDownloading = true
                                updateError = null
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        updateProgress = UpdateChecker.Progress(0, 0L, info.size, 0L)
                                        val apk = UpdateChecker.download(context, info) { p ->
                                            scope.launch(Dispatchers.Main) { updateProgress = p }
                                        }
                                        withContext(Dispatchers.Main) {
                                            updateDownloading = false
                                            UpdateChecker.install(context, apk)
                                        }
                                    } catch (e: Throwable) {
                                        withContext(Dispatchers.Main) {
                                            updateDownloading = false
                                            updateError = "下载失败：" + (e.message ?: "网络错误")
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 更新弹窗里的单个版本卡片：点开后显示该小版本的逐条更新日志。 */
@Composable
private fun VersionNoteRow(
    note: UpdateChecker.VersionNote,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(COUITheme.colorScheme.surfaceContainer)
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "v" + note.versionName + " · " + note.versionCode,
                style = COUITheme.textStyles.body2,
                color = if (expanded) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurface
            )
            Text(
                if (note.size > 0) UpdateChecker.formatSize(note.size) else "大小未标注",
                style = COUITheme.textStyles.body2,
                fontSize = 10.sp,
                color = COUITheme.colorScheme.onSurfaceContainerVariant
            )
        }
        Text(
            "更新时间 " + UpdateChecker.formatTime(note.publishedAt),
            style = COUITheme.textStyles.body2,
            fontSize = 10.sp,
            color = COUITheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(Modifier.padding(top = 6.dp)) {
                note.notes.ifEmpty { listOf("该版本无单独说明") }.forEach { line ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            "· ",
                            style = COUITheme.textStyles.body2,
                            color = COUITheme.colorScheme.primary
                        )
                        Text(
                            line,
                            style = COUITheme.textStyles.body2,
                            color = COUITheme.colorScheme.onSurfaceContainerVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualSection(title: String, lines: List<String>) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(title, style = COUITheme.textStyles.headline2, color = COUITheme.colorScheme.primary)
        lines.forEach { line ->
            Row(Modifier.padding(top = 4.dp)) {
                Text("· ", style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.primary)
                Text(line, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceContainerVariant)
            }
        }
    }
}

@Composable
private fun Avatar(size: Int, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.avatar),
        contentDescription = "头像",
        modifier = modifier.size(size.dp).clip(CircleShape)
    )
}

@Composable
private fun ButtonIconText(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text)
    }
}

private enum class StepState {
    PENDING,
    RUNNING,
    SUCCESS,
    ERROR
}

private val runFlowLabels = listOf("准备", "提权", "加载", "验证", "完成")

private fun runFlowIndex(progress: Float): Int = when {
    progress >= 0.95f -> 4
    progress >= 0.70f -> 3
    progress >= 0.45f -> 2
    progress >= 0.15f -> 1
    else -> 0
}

@Composable
private fun RunFlow(
    progress: Float,
    running: Boolean,
    lastResultOk: Boolean?,
    modifier: Modifier = Modifier,
) {
    val current = runFlowIndex(progress)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        runFlowLabels.forEachIndexed { index, label ->
            val state = when {
                !running && lastResultOk == null -> StepState.PENDING
                running -> when {
                    index < current -> StepState.SUCCESS
                    index == current -> StepState.RUNNING
                    else -> StepState.PENDING
                }
                !running && lastResultOk == true -> StepState.SUCCESS
                !running && lastResultOk == false -> when {
                    index < current -> StepState.SUCCESS
                    index == current -> StepState.ERROR
                    else -> StepState.PENDING
                }
                else -> StepState.PENDING
            }

            val (bg, fg, icon) = when (state) {
                StepState.SUCCESS -> Triple(
                    COUITheme.colorScheme.primary,
                    COUITheme.colorScheme.onPrimary,
                    COUIIcons.Ok
                )
                StepState.RUNNING -> Triple(
                    COUITheme.colorScheme.primary.copy(alpha = 0.22f),
                    COUITheme.colorScheme.primary,
                    COUIIcons.Play
                )
                StepState.ERROR -> Triple(
                    COUITheme.colorScheme.error,
                    COUITheme.colorScheme.onError,
                    COUIIcons.Close
                )
                StepState.PENDING -> Triple(
                    COUITheme.colorScheme.surfaceContainer,
                    COUITheme.colorScheme.onSurfaceContainerVariant,
                    COUIIcons.Info
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .scale(if (state == StepState.RUNNING) pulseScale else 1.0f)
                        .clip(CircleShape)
                        .background(bg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    style = COUITheme.textStyles.body2,
                    fontSize = 11.sp,
                    color = when (state) {
                        StepState.ERROR -> COUITheme.colorScheme.error
                        StepState.PENDING -> COUITheme.colorScheme.onSurfaceContainerVariant
                        else -> COUITheme.colorScheme.primary
                    }
                )
            }

            if (index < runFlowLabels.lastIndex) {
                val nextState = when {
                    !running && lastResultOk == null -> StepState.PENDING
                    running -> if (index + 1 <= current) StepState.RUNNING else StepState.PENDING
                    !running && lastResultOk == true -> StepState.SUCCESS
                    else -> StepState.PENDING
                }
                val lineDone = state == StepState.SUCCESS && (nextState == StepState.SUCCESS || nextState == StepState.RUNNING)
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .weight(0.5f)
                        .padding(bottom = 16.dp)
                        .background(
                            if (lineDone) COUITheme.colorScheme.primary
                            else COUITheme.colorScheme.surfaceContainer
                        )
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    backdrop: LayerBackdrop,
    controller: JailbreakController,
    tab: Int,
    onLogCopy: () -> Unit,
    onLogExport: () -> Unit,
    onLogClear: () -> Unit,
    onHistoryRefresh: () -> Unit,
    onHistoryClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp) },
                effects = { blur(18f) }
            )
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White,
                        0.65f to Color.White,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
            .background(
                Brush.verticalGradient(listOf(COUITheme.colorScheme.background.copy(alpha = 0.35f), COUITheme.colorScheme.background.copy(alpha = 0f))),
                RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
            )
            .padding(top = 3.dp, bottom = 2.dp)
    ) {
        Text(
            "v" + BuildConfig.VERSION_NAME + " · #" + BuildConfig.VERSION_CODE,
            style = COUITheme.textStyles.body2,
            fontSize = 10.sp,
            color = COUITheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        when (tab) {
            0 -> {
                Avatar(40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("OneKey", style = COUITheme.textStyles.headline1)
                    Text(
                        "v" + BuildConfig.VERSION_NAME + " · " + (controller.profile?.displayName ?: "OnePlus"),
                        style = COUITheme.textStyles.body2,
                        color = COUITheme.colorScheme.onSurfaceContainerVariant
                    )
                }
            }
            1 -> {
                Column(Modifier.weight(1f)) {
                    Text("运行日志", style = COUITheme.textStyles.headline1)
                    Text(
                        "${controller.logs.size} 条记录",
                        style = COUITheme.textStyles.body2,
                        color = COUITheme.colorScheme.onSurfaceContainerVariant
                    )
                }
                IconButton(onClick = onLogCopy) {
                    Icon(COUIIcons.Copy, contentDescription = "复制日志")
                }
                IconButton(onClick = onLogExport) {
                    Icon(COUIIcons.Download, contentDescription = "导出 TXT")
                }
                IconButton(onClick = onLogClear) {
                    Icon(COUIIcons.Delete, contentDescription = "清空日志")
                }
            }
            2 -> {
                Column(Modifier.weight(1f)) {
                    Text("越狱历史", style = COUITheme.textStyles.headline1)
                    Text(
                        "提权与诊断归档",
                        style = COUITheme.textStyles.body2,
                        color = COUITheme.colorScheme.onSurfaceContainerVariant
                    )
                }
                IconButton(onClick = onHistoryRefresh) {
                    Icon(COUIIcons.Refresh, contentDescription = "刷新记录")
                }
                IconButton(onClick = onHistoryClear) {
                    Icon(COUIIcons.Delete, contentDescription = "清空记录")
                }
            }
            else -> {
                Column(Modifier.weight(1f)) {
                    Text("关于与设置", style = COUITheme.textStyles.headline1)
                    Text(
                        "OneKey v" + BuildConfig.VERSION_NAME,
                        style = COUITheme.textStyles.body2,
                        color = COUITheme.colorScheme.onSurfaceContainerVariant
                    )
                }
            }
        }
    }
}

}

@Composable
private fun AppBottomBar(
    backdrop: LayerBackdrop,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(30.dp)
    val labels = listOf("首页", "日志", "历史", "关于")
    val icons = listOf(COUIIcons.Home, COUIIcons.Notes, COUIIcons.Recent, COUIIcons.Info)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
            .height(70.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { blur(20f) }
            )
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.35f to Color.White,
                        1f to Color.White
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
            .background(
                Brush.verticalGradient(
                    listOf(
                        COUITheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                        COUITheme.colorScheme.surfaceContainer.copy(alpha = 0.80f)
                    )
                ),
                shape
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                val tint by animateColorAsState(
                    if (active) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurfaceContainerVariant,
                    tween(180), label = "navTint"
                )
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    color = if (active) COUITheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                    contentColor = tint
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icons[index], contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(label, style = COUITheme.textStyles.body2, fontSize = 10.sp, color = tint)
                    }
                }
            }
        }
    }
}

data class DialogAction(
    val label: String,
    val icon: ImageVector,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun DialogActions(vararg actions: DialogAction) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            Surface(
                onClick = action.onClick,
                enabled = action.enabled,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (action.primary) COUITheme.colorScheme.primary else COUITheme.colorScheme.surfaceContainer,
                contentColor = if (action.primary) COUITheme.colorScheme.onPrimary else COUITheme.colorScheme.onSurface
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(action.label, style = COUITheme.textStyles.body2)
                }
            }
        }
    }
}

@Composable
private fun ResultBanner(
    controller: JailbreakController,
    onOpenManager: () -> Unit,
    onOpenLog: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val isOk = controller.lastResultOk ?: return
    val is98260 = controller.lastResultExploitKind == "cve98260"

    val containerColor = if (isOk) {
        COUITheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        COUITheme.colorScheme.error.copy(alpha = 0.12f)
    }
    val contentColor = if (isOk) COUITheme.colorScheme.primary else COUITheme.colorScheme.error

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(containerColor)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isOk) COUIIcons.Ok else COUIIcons.Close,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (isOk) "越狱执行成功" else "越狱执行未完成",
                        style = COUITheme.textStyles.subtitle,
                        color = contentColor
                    )
                    Text(
                        text = if (isOk) {
                            "内核提权与模块加载完成 · Root 服务已就绪"
                        } else if (is98260) {
                            "98260 执行未完成；内核状态可能已改变，重试前必须先硬重启设备。"
                        } else {
                            "在「${controller.stage}」阶段遇到错误，请查看日志排查问题。"
                        },
                        style = COUITheme.textStyles.body2,
                        color = COUITheme.colorScheme.onSurface
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isOk) {
                    TextButton(text = "查看日志", onClick = onOpenLog)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onOpenManager) {
                        ButtonIconText(COUIIcons.Settings, "打开管理器")
                    }
                } else {
                    TextButton(text = "环境诊断", onClick = onDiagnostics)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onOpenLog) {
                        ButtonIconText(COUIIcons.Notes, "查看详细日志")
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    onDiagnostics: () -> Unit,
    onOpenLog: () -> Unit,
    onRunJailbreak: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    var showAdvanced by remember { mutableStateOf(true) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { HeroCard(controller) }
        if (controller.lastResultOk != null) {
            item {
                ResultBanner(
                    controller = controller,
                    onOpenManager = {
                        val pkg = controller.managers.firstOrNull { it.kind == controller.selectedManager }?.pkg
                        runCatching {
                            context.startActivity(
                                context.packageManager.getLaunchIntentForPackage(pkg ?: "")
                                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onOpenLog = onOpenLog,
                    onDiagnostics = onDiagnostics
                )
            }
        }
        // 高级设置（含拓展功能）放在运行卡片上方
        item {
            Card {
                BasicComponent(
                    title = if (showAdvanced) "收起高级设置" else "高级设置",
                    summary = if (showAdvanced) "已展开：安全组件、软重启与拓展功能" else "安全组件、软重启与拓展功能",
                    startAction = { Icon(COUIIcons.Settings, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                    onClick = { haptic(CouiHapticEffect.Switch); showAdvanced = !showAdvanced }
                )
            }
        }
        if (showAdvanced) {
            item { OptionsCard(controller, haptic, onOpenLog) }
            item { ManagerCard(controller, haptic) }
        }
        item { ActionCard(controller, haptic, onDiagnostics, onRunJailbreak) }
        item { ExploitCard(controller, haptic) }
    }
}

@Composable
private fun HiddenEnvCard(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    onOpenLog: () -> Unit,
) {
    Card {
        Column(Modifier.padding(4.dp)) {
            BasicComponent(
                title = "环境隐藏",
                summary = "原生方式不触碰任何系统挂载，环境隐藏最彻底。"
            )
        }
    }
}

@Composable
private fun HeroCard(controller: JailbreakController) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    controller.profile?.displayName ?: "OnePlus",
                    style = COUITheme.textStyles.headline1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "98260 越狱链" +
                            " · " + (if (controller.suCompatReady) "SU 就绪" else "未提权"),
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceContainerVariant
                )
            }
            Surface(
                shape = CircleShape,
                color = if (controller.serviceReady) COUITheme.colorScheme.primary.copy(alpha = 0.16f)
                        else COUITheme.colorScheme.surfaceContainer
            ) {
                Text(
                    if (controller.serviceReady) "服务在线" else "服务离线",
                    color = if (controller.serviceReady) COUITheme.colorScheme.primary
                            else COUITheme.colorScheme.onSurfaceContainerVariant,
                    style = COUITheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusCard(controller: JailbreakController) {
    Card {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicComponent(
                title = if (controller.serviceReady && controller.serviceGranted) "Stellar 服务已连接并授权"
                        else if (controller.serviceReady) "Stellar 运行中，等待授权" else "Stellar 未运行",
                summary = "Root 服务通道 · 重启后需重新启动 Stellar",
                startAction = {
                    Icon(
                        imageVector = if (controller.serviceReady && controller.serviceGranted) COUIIcons.Ok else COUIIcons.Info,
                        contentDescription = null,
                        tint = if (controller.serviceReady && controller.serviceGranted) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.padding(end = 14.dp)
                    )
                }
            )
            if (controller.deviceInfo.isNotBlank()) {
                Text(controller.deviceInfo, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceContainerVariant, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp))
            }
            Text(
                when {
                    controller.suCompatReady -> "默认 SU：已就绪"
                    else -> "默认 SU：未就绪（重新运行一次越狱即可）"
                },
                style = COUITheme.textStyles.body2,
                color = if (controller.suCompatReady) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
            )
        }
    }
}

private fun managerHint(kind: String): String = when (kind) {
    "next" -> "KernelSU-Next v3.4.2（versionCode 33355，支持自适应与云端模块）"
    "kernelsu" -> "KernelSU 官方 v3.3.1（versionCode 32680，支持自适应与云端模块）"
    "sukisu" -> "SukiSU Ultra v4.3.0-r1（versionCode 41140，支持自适应与云端模块）"
    "bakasu" -> "BakaSU v4.2.0-rc3（versionCode 35171，支持自适应与云端模块）"
    "finasu" -> "FinaSU v1.4.5（versionCode 33168，支持自适应与云端模块）"
    else -> ""
}

@Composable
private fun ExploitCard(controller: JailbreakController, haptic: (CouiHapticEffect) -> Unit) {
    Card {
        Column(Modifier.padding(4.dp)) {
            BasicComponent(
                title = "越狱方式",
                summary = "CVE-2026-98260 · 唯一链路"
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { haptic(CouiHapticEffect.Switch) },
                    modifier = Modifier.weight(1f),
                    enabled = false,
                    colors = ButtonDefaults.buttonColors(
                        color = COUITheme.colorScheme.primary,
                        contentColor = COUITheme.colorScheme.onPrimary
                    )
                ) { ButtonIconText(COUIIcons.Unlock, "98260 越狱链") }
            }
            Text(
                "64560 已被官方修复（157 → 201 反汇编确认），App 只保留 98260。失败可直接重试，只有内核异常才需要硬重启。",
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 4.dp)
            )
        }
    }
}

@Composable
private fun FinaOnlyCard() {
    Card {
        Column(Modifier.padding(4.dp)) {
            BasicComponent(
                title = "FinaSU（固定支持）",
                summary = "98260 模式优先与 FinaSU 配合运行。"
            )
            BasicComponent(
                title = "使用建议",
                summary = "Zygisk Next 可正常使用；如遇模块异常，可暂时关闭对应模块排查。"
            )
        }
    }
}

@Composable
private fun ManagerCard(controller: JailbreakController, haptic: (CouiHapticEffect) -> Unit) {
    Card {
        Column {
            if (controller.visibleManagers.isEmpty()) {
                BasicComponent(
                    title = "未检测到管理器",
                    summary = "请先安装列表中的固定版本。"
                )
            }
            controller.visibleManagers.forEach { manager ->
                val selectable = !controller.running && manager.supported && manager.installedVersionCode != null && manager.certOk &&
                    (if (controller.adaptiveManagerEnabled) true else (manager.kind != "finasu" && manager.installedVersionCode == manager.expectedVersionCode))
                BasicComponent(
                    title = manager.label,
                    summary = manager.statusText,
                    startAction = { Icon(COUIIcons.Tasks, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                    onClick = { if (selectable) { haptic(CouiHapticEffect.Switch); controller.selectManager(manager.kind) } },
                    endActions = {
                        RadioButton(
                            selected = controller.selectedManager == manager.kind,
                            onClick = { if (selectable) { haptic(CouiHapticEffect.Switch); controller.selectManager(manager.kind) } },
                            enabled = selectable
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun OptionsCard(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    onOpenLog: () -> Unit,
) {
    Card {
        Column(Modifier.padding(4.dp)) {
            BasicComponent(
                title = "拓展功能",
                summary = "越狱成功后自动生效，可随时关掉",
                startAction = { Icon(COUIIcons.Settings, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) }
            )
            BasicComponent(
                title = "越狱后禁用安全弹窗",
                summary = "停用安全守护与安全中心，不影响截屏",
                startAction = { Icon(COUIIcons.Lock, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                endActions = {
                    Switch(
                        checked = controller.antiRootGuard,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateAntiRootGuard(it) },
                        enabled = !controller.running
                    )
                }
            )
            BasicComponent(
                title = "越狱后软重启",
                summary = "ColorOS 拦截原生调用时，软重启用户态恢复 root",
                startAction = { Icon(COUIIcons.Refresh, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                endActions = {
                    Switch(
                        checked = controller.softRebootFix,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateSoftRebootFix(it) },
                        enabled = !controller.running
                    )
                }
            )
            BasicComponent(
                title = "越狱后卸载内核安全防护",
                summary = "卸载 oplus_secure_guard 等内核防护模块",
                startAction = { Icon(COUIIcons.Unlock, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                endActions = {
                    Switch(
                        checked = controller.unloadKernelGuard,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateUnloadKernelGuard(it) },
                        enabled = !controller.running
                    )
                }
            )
            BasicComponent(
                title = "SUSFS 支持",
                summary = "加载 SUSFS 隐藏模块（需要执行器侧配合）",
                startAction = { Icon(COUIIcons.Lock, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                endActions = {
                    Switch(
                        checked = controller.susfsEnabled,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateSusfs(it) },
                        enabled = !controller.running
                    )
                }
            )
            BasicComponent(
                title = "VFS 支持",
                summary = "加载 hybridmount VFS 重定向（仅 6.18 内核）",
                startAction = { Icon(COUIIcons.Tasks, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                endActions = {
                    Switch(
                        checked = controller.vfsEnabled,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateVfs(it) },
                        enabled = !controller.running
                    )
                }
            )
        }
    }
}

@Composable
private fun ActionCard(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    onDiagnostics: () -> Unit,
    onRunJailbreak: () -> Unit,
) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { haptic(CouiHapticEffect.Switch); onRunJailbreak() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = controller.canRunExploit,
                colors = ButtonDefaults.buttonColors(
                    color = COUITheme.colorScheme.primary,
                    disabledColor = COUITheme.colorScheme.primary.copy(alpha = 0.35f),
                    contentColor = COUITheme.colorScheme.onPrimary,
                    disabledContentColor = COUITheme.colorScheme.onPrimary.copy(alpha = 0.65f)
                )
            ) {
                if (controller.running) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("越狱中 (${controller.elapsedSeconds}s)")
                } else {
                    ButtonIconText(COUIIcons.Play, "运行 98260 越狱链")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val progress by animateFloatAsState(controller.progress, spring(dampingRatio = 0.8f, stiffness = 180f), label = "progress")
                Text(controller.stage, style = COUITheme.textStyles.body2, modifier = Modifier.weight(1f))
                Text((progress * 100).toInt().toString() + "%", style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.primary)
            }
            controller.platformReason?.let { Text(it, color = COUITheme.colorScheme.error, style = COUITheme.textStyles.body2) }
            RunFlow(controller.progress, controller.running, controller.lastResultOk)
            LinearProgressIndicator(progress = controller.progress, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(text = "环境诊断", onClick = onDiagnostics)
            }
        }
    }
}

@Composable
private fun DiagnosticsDialog(
    controller: JailbreakController,
    onDismiss: () -> Unit,
    onOpenLog: () -> Unit,
    onMessage: (String) -> Unit,
) {
    WindowDialog(title = "环境诊断", show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "环境检查",
                summary = "服务通道、已装管理器与 SU 挂载",
                startAction = { Icon(COUIIcons.Search, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                onClick = { controller.selfCheck(); onMessage("正在检查环境"); onDismiss(); onOpenLog() }
            )
            BasicComponent(
                title = "修复截屏扩展",
                summary = "启用 exsystemservice 组件",
                startAction = { Icon(COUIIcons.ScreenCapture, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                onClick = { controller.repairScreenshot(); onMessage("正在修复截屏"); onDismiss(); onOpenLog() }
            )
            BasicComponent(
                title = "恢复安全组件",
                summary = "重新启用 securityguard 与 safecenter",
                startAction = { Icon(COUIIcons.Unlock, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                onClick = { controller.restoreSecurityComponents(); onMessage("正在恢复安全组件"); onDismiss(); onOpenLog() }
            )
            BasicComponent(
                title = "导出设备诊断",
                summary = "提取内核与 SELinux 状态至日志",
                startAction = { Icon(COUIIcons.Report, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                onClick = { controller.collectDiagnostics(); onMessage("已导出诊断信息"); onDismiss(); onOpenLog() }
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                ButtonIconText(COUIIcons.Close, "关闭")
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    reloadTick: Int,
    onGoHome: () -> Unit,
    onClearRequest: () -> Unit,
) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<HistoryRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<HistoryRecord?>(null) }
    var logText by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> out.write(logText.toByteArray()) }
                scope.launch { snackbarHostState.showSnackbar("历史日志已导出") }
            }.onFailure { err ->
                scope.launch { snackbarHostState.showSnackbar("导出失败：" + (err.message ?: "未知错误")) }
            }
        }
    }

    LaunchedEffect(reloadTick, controller.lastResultOk, controller.running) {
        records = withContext(Dispatchers.IO) { HistoryStore.list(context) }
        if (selected != null && records.none { it.id == selected?.id }) selected = null
    }
    LaunchedEffect(selected?.id) {
        val s = selected ?: run { logText = ""; return@LaunchedEffect }
        logText = withContext(Dispatchers.IO) { HistoryStore.readLog(context, s) }
    }

    if (records.isEmpty()) {
        FullPageStatement(
            title = "暂无历史记录",
            content = "尚未检测到越狱执行记录。每次任务结束后，耗时、所选架构及详细日志将自动归档在此。",
            primaryButtonText = "前往首页",
            onPrimaryButtonClick = onGoHome,
            modifier = Modifier.fillMaxSize().padding(contentPadding)
        )
    } else if (selected != null) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { haptic(CouiHapticEffect.Switch); selected = null }) {
                    Icon(COUIIcons.Back, contentDescription = "返回列表")
                }
                Spacer(Modifier.width(8.dp))
                Text("记录详情 · ${HistoryStore.formatTime(selected?.startedAt ?: 0L)}", style = COUITheme.textStyles.subtitle, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("onekey-history", logText))
                    haptic(CouiHapticEffect.Switch)
                    scope.launch { snackbarHostState.showSnackbar("历史日志已复制") }
                }, enabled = logText.isNotBlank()) {
                    Icon(COUIIcons.Copy, contentDescription = "复制历史日志")
                }
                IconButton(onClick = {
                    haptic(CouiHapticEffect.Switch)
                    exportLauncher.launch("onekey-history-" + System.currentTimeMillis() + ".txt")
                }, enabled = logText.isNotBlank()) {
                    Icon(COUIIcons.Download, contentDescription = "导出历史日志")
                }
            }
            Card(Modifier.fillMaxSize()) {
                val scroll = rememberScrollState()
                Text(
                    logText.ifBlank { "此记录未包含日志" },
                    style = COUITheme.textStyles.body2,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(scroll)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(records.size) { idx ->
                val r = records[idx]
                val isOk = r.status == "ok"
                Card {
                    BasicComponent(
                        title = (if (isOk) "越狱成功 · " else "越狱中断 · ") + (if (r.exploit == "cve98260") "98260" else "64560") + (if (r.adaptive) " (自适应)" else ""),
                        summary = HistoryStore.formatTime(r.startedAt) + " · 管理器 " + r.manager + " · 耗时 " + r.durationSec + "s · v" + r.versionName,
                        startAction = {
                            Icon(
                                if (isOk) COUIIcons.Ok else COUIIcons.Close,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 12.dp),
                                tint = if (isOk) COUITheme.colorScheme.primary else COUITheme.colorScheme.error
                            )
                        },
                        onClick = { haptic(CouiHapticEffect.Switch); selected = r }
                    )
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { haptic(CouiHapticEffect.Switch); onClearRequest() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ButtonIconText(COUIIcons.Delete, "清空全部历史记录")
                }
            }
        }
    }
}

@Composable
private fun LogScreen(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    contentPadding: PaddingValues,
    onGoHome: () -> Unit,
    onExport: () -> Unit,
) {
    val logs = controller.logs
    val sec = controller.elapsedSeconds
    val elapsedText = (sec / 60).toString().padStart(2, '0') + ":" + (sec % 60).toString().padStart(2, '0')
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }

    if (logs.isEmpty()) {
        FullPageStatement(
            title = "暂无运行日志",
            content = "启动越狱任务或运行环境诊断后，系统底层与模块加载的实时输出将在此处串流呈现。",
            primaryButtonText = "前往首页",
            onPrimaryButtonClick = onGoHome,
            modifier = Modifier.fillMaxSize().padding(contentPadding)
        )
    } else {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            Text("已用时间 " + elapsedText, style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            RunFlow(controller.progress, controller.running, controller.lastResultOk)
            LinearProgressIndicator(progress = controller.progress, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Card(Modifier.fillMaxSize().padding(top = 8.dp)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    items(logs.size) { idx ->
                        Text(
                            logs[idx],
                            style = COUITheme.textStyles.body2,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallTitle(text: String) {
    Text(
        text = text,
        style = COUITheme.textStyles.subtitle,
        color = COUITheme.colorScheme.onSurfaceContainerVariant,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun AboutScreen(
    controller: JailbreakController,
    themeMode: String,
    onToggleTheme: () -> Unit,
    haptic: (CouiHapticEffect) -> Unit,
    onOpenLog: () -> Unit,
    onNotice: () -> Unit,
    onManual: () -> Unit,
    onThirdParty: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("OneKey v" + BuildConfig.VERSION_NAME, style = COUITheme.textStyles.subtitle)
                    Text("作者 星苒鸭 · github.com/xingranya · xran.uk", style = COUITheme.textStyles.body2)
                }
            }
        }
        item { SmallTitle("个性化与组件") }
        item {
            Card {
                Column {
                    BasicComponent(
                        title = "主题色 · 星苒粉 #FFC0CB",
                        summary = if (themeMode == "pink") "当前：星苒粉（切换为动态取色）" else "当前：动态取色（跟随壁纸）",
                        startAction = { Icon(COUIIcons.Theme, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                        onClick = { haptic(CouiHapticEffect.Switch); onToggleTheme() },
                        endActions = {
                            Switch(
                                checked = themeMode == "pink",
                                onCheckedChange = { haptic(CouiHapticEffect.Switch); onToggleTheme() }
                            )
                        }
                    )
                    BasicComponent(
                        title = "自动匹配云端组件",
                        summary = if (controller.cloudAuto) "按当前设备和管理器自动获取模块" else "仅使用本机固定组件",
                        startAction = { Icon(COUIIcons.Update, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                        endActions = {
                            Switch(
                                checked = controller.cloudAuto,
                                onCheckedChange = {
                                    haptic(CouiHapticEffect.Switch)
                                    controller.updateCloudAuto(it)
                                },
                                enabled = !controller.running
                            )
                        }
                    )
                    BasicComponent(
                        title = "检查更新",
                        summary = if (controller.checkingUpdate) "正在检查…" else controller.updateStatus,
                        startAction = { Icon(COUIIcons.Refresh, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                        onClick = { haptic(CouiHapticEffect.Switch); controller.checkUpdates() }
                    )
                }
            }
        }
        item { SmallTitle("开源说明与致谢") }
        item {
            Card {
                Column {
                    BasicComponent(title = "使用手册", summary = "首次使用、拓展功能说明与异常处理", startAction = { Icon(COUIIcons.Notes, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) }, onClick = onManual)
                    BasicComponent(title = "免费声明与赞赏码", summary = "群内免费 · 禁止倒卖", startAction = { Icon(COUIIcons.Help, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) }, onClick = onNotice)
                    BasicComponent(title = "COUI 组件库（开源）", summary = "github.com/suqi8/coui", startAction = { Icon(COUIIcons.Link, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) }, onClick = { openUrl(context, "https://github.com/suqi8/coui") })
                    BasicComponent(title = "Stellar（开源）", summary = "github.com/roro2239/Stellar", startAction = { Icon(COUIIcons.Link, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) }, onClick = { openUrl(context, "https://github.com/roro2239/Stellar") })
                    BasicComponent(title = "第三方组件说明", summary = "感谢以下开源项目与实现", startAction = { Icon(COUIIcons.Info, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) }, onClick = onThirdParty)
                    BasicComponent(title = "版本", summary = "v" + BuildConfig.VERSION_NAME + " · 双模式 · 管理器自适应/固定", startAction = { Icon(COUIIcons.Info, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) })
                }
            }
        }
    }
}

@Composable
private fun AppreciationDialog(onMessage: (String) -> Unit, onDismiss: (Boolean) -> Unit) {
    val context = LocalContext.current
    val dismiss = LocalDismissState.current
    var dontShow by remember { mutableStateOf(true) }
    val close = {
        val s = dontShow
        dismiss?.invoke()
        onDismiss(s)
    }
    WindowDialog(title = "免费声明 · 禁止倒卖", show = true, onDismissRequest = { close() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("倒卖必究", style = COUITheme.textStyles.subtitle, color = COUITheme.colorScheme.error)
            Text("本工具免费分享，禁止倒卖。QQ群：1121682811。赞赏完全自愿，不影响任何功能。", style = COUITheme.textStyles.body2, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
            Image(painterResource(R.drawable.appreciation), contentDescription = "作者赞赏码", modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("不再提示", style = COUITheme.textStyles.body2, modifier = Modifier.weight(1f))
                Switch(checked = dontShow, onCheckedChange = { dontShow = it })
            }
            DialogActions(
                DialogAction("复制群号", COUIIcons.Copy) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("qq", "1121682811"))
                    onMessage("群号已复制")
                },
                DialogAction("保存相册", COUIIcons.Download) {
                    saveAppreciation(context, onMessage)
                },
                DialogAction("知道了", COUIIcons.Ok, primary = true) { close() }
            )
        }
    }
}

private fun saveAppreciation(context: Context, onMessage: (String) -> Unit) {
    val resolver = context.contentResolver
    val cv = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "onekey-appreciation-" + System.currentTimeMillis() + ".png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OneKey")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
    if (uri == null) {
        onMessage("保存失败：无法创建图片记录")
        return
    }
    try {
        val input = context.assets.open("appreciation.png")
        val output = resolver.openOutputStream(uri)
        if (output == null) {
            onMessage("保存失败：无法打开写入流")
            return
        }
        input.use { i -> output.use { o -> i.copyTo(o) } }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, update, null, null)
        }
        onMessage("赞赏码已保存到相册（Pictures/OneKey）")
    } catch (e: Exception) {
        onMessage("保存失败：" + (e.message ?: "未知错误"))
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}

private fun onCopy(
    context: Context,
    controller: JailbreakController,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("onekey-log", controller.logs.joinToString("\n")))
    scope.launch { snackbar.showSnackbar("日志已复制到剪贴板（" + controller.logs.size + " 行）") }
}
