package com.pyb110.onekey.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.BuildConfig
import com.pyb110.onekey.HistoryStore
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.UpdateChecker
import com.pyb110.onekey.ui.AppearanceMode
import com.pyb110.onekey.ui.components.AppBottomBar
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.components.DialogActions
import com.pyb110.onekey.ui.components.ManualSection
import com.pyb110.onekey.ui.components.MarkdownText
import com.pyb110.onekey.ui.components.TopBar
import com.pyb110.onekey.ui.dialog.AppreciationDialog
import com.pyb110.onekey.ui.dialog.DiagnosticsDialog
import com.pyb110.onekey.ui.dialog.VersionNoteRow
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.screen.AboutScreen
import com.pyb110.onekey.ui.screen.HistoryScreen
import com.pyb110.onekey.ui.screen.HomeScreen
import com.pyb110.onekey.ui.screen.LogScreen
import com.pyb110.onekey.ui.sheet.FeatureTogglePanel
import com.pyb110.onekey.ui.sheet.HotSwitchPanel
import com.pyb110.onekey.ui.sheet.JailManagerPanel
import com.pyb110.onekey.ui.sheet.ManagerDownloadPanel
import com.pyb110.onekey.ui.sheet.TargetPickerPanel
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.COUIPopupUtils.Companion.COUIPopupHost
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
import io.github.suqi8.coui.kmp.utils.rememberCouiHaptic
import io.github.suqi8.coui.kmp.window.WindowBottomSheet
import io.github.suqi8.coui.kmp.window.WindowDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AppRoot(
    controller: JailbreakController,
    appearance: AppearanceMode,
    isDark: Boolean,
    onChangeAppearance: (AppearanceMode) -> Unit,
) {
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
    val termsPrefs = LocalContext.current.getSharedPreferences("onekey", Context.MODE_PRIVATE)
    var showTerms by remember { mutableStateOf(!termsPrefs.getBoolean("terms_accepted_v2", false)) }
    var reloadHistoryTick by remember { mutableIntStateOf(0) }
    // 底部抽屉的状态放在根级：overlay 组件必须挂在浮层宿主上，不能留在列表 item 里。
    var sheet by remember { mutableIntStateOf(0) }
    var sheetVisible by remember { mutableStateOf(false) }
    LaunchedEffect(sheet) { if (sheet != 0) sheetVisible = true }
    var hwTarget by remember { mutableStateOf("") }
    val certManagers = controller.managers.filter { it.installedVersionCode != null && it.certOk }
    LaunchedEffect(certManagers.map { it.kind }) {
        if (certManagers.none { it.kind == hwTarget }) hwTarget = certManagers.firstOrNull()?.kind.orEmpty()
    }
    val hwTargetLabel = controller.managers.firstOrNull { it.kind == hwTarget }?.label ?: "未安装"

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
        containerColor = oneKeyColors().background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        val backdrop = rememberLayerBackdrop()
        var topBarPx by remember { mutableStateOf(0) }
        var navBarPx by remember { mutableStateOf(0) }
        val density = LocalDensity.current
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val screenContentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            // topBarPx 是栏体内容高度（不含状态栏内边距），所以这里要再加一次 statusBarTop
            top = if (topBarPx > 0) statusBarTop + with(density) { topBarPx.toDp() } + 10.dp
                  else statusBarTop + 92.dp,
            bottom = if (navBarPx > 0) navBarBottom + with(density) { navBarPx.toDp() } + 6.dp
                     else navBarBottom + 92.dp
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
                            onOpenAbout = { tab = 3 },
                            onOpenManual = { showManual = true },
                            onOpenSheet = { sheet = it },
                            contentPadding = screenContentPadding
                        )
                        1 -> LogScreen(
                            controller = controller,
                            haptic = haptic,
                            snackbarHostState = snackbarHostState,
                            scope = scope,
                            contentPadding = screenContentPadding,
                            onGoHome = { tab = 0 },
                            onExport = { exportLauncher.launch("onekey-log-" + System.currentTimeMillis() + ".txt") },
                            onCopy = { onCopy(context, controller, scope, snackbarHostState) },
                            onClear = {
                                controller.clearLogs()
                                scope.launch { snackbarHostState.showSnackbar("日志已清空") }
                            }
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
                            appearance = appearance,
                            onChangeAppearance = onChangeAppearance,
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
                isDark = isDark,
                onToggleAppearance = {
                    haptic(CouiHapticEffect.Switch)
                    onChangeAppearance(if (isDark) AppearanceMode.LIGHT else AppearanceMode.DARK)
                },
                onHeight = { topBarPx = it },
                modifier = Modifier.align(Alignment.TopCenter)
            )

            AppBottomBar(
                backdrop = backdrop,
                selected = tab,
                onSelect = { haptic(CouiHapticEffect.Switch); tab = it },
                onHeight = { navBarPx = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // 浮层宿主：底部抽屉等 overlay 组件需要它才能挂到窗口层。
            COUIPopupHost()

            // 抽屉的可见状态提到根级：内容里的开关/筛选变化不会重建它，避免整块弹一下。
            if (sheet != 0) {
                WindowBottomSheet(
                    show = sheetVisible,
                    title = sheetTitle(sheet),
                    onDismissRequest = { sheetVisible = false },
                    onDismissFinished = { sheet = 0; sheetVisible = false }
                ) {
                    when (sheet) {
                        1 -> Column(Modifier.fillMaxWidth().height(520.dp).verticalScroll(rememberScrollState())) {
                            HotSwitchPanel(
                                controller = controller,
                                haptic = haptic,
                                selectedTarget = hwTarget,
                                onPickTarget = { sheet = 4 }
                            )
                        }
                        2 -> Column(Modifier.fillMaxWidth().height(460.dp).verticalScroll(rememberScrollState())) {
                            ManagerDownloadPanel()
                        }
                        3 -> Column(Modifier.fillMaxWidth().height(420.dp).verticalScroll(rememberScrollState())) {
                            FeatureTogglePanel(controller, haptic)
                        }
                        4 -> Column(Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState())) {
                            TargetPickerPanel(
                                controller = controller,
                                selected = hwTarget,
                                // 选完回到热切换面板本身，不用再重新进一次。
                                onPick = { hwTarget = it; sheet = 1 }
                            )
                        }
                        5 -> Column(Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState())) {
                            JailManagerPanel(controller, haptic)
                        }
                    }
                }
            }

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
                            .padding(horizontal = 20.dp)
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
                            "越狱后禁用安全弹窗：停掉安全守护/安全中心进程并抑制反 root 弹窗，不改包状态、不重启。",
                            "越狱后软重启：默认关闭。开启后越狱成功会执行一次 setprop ctl.restart zygote；关闭则不重启。",
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
                val ctx = LocalContext.current
                val noticeText = remember {
                    runCatching {
                        ctx.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
                    }.getOrDefault("第三方声明加载失败，请查看应用仓库中的 THIRD_PARTY_NOTICES.md")
                }
                WindowDialog(title = "第三方组件说明", show = true, onDismissRequest = { showThirdParty = false }) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                    ) {
                        MarkdownText(
                            markdown = noticeText,
                            onLinkClick = { url -> openUrl(ctx, url) },
                        )
                        DialogActions(
                            DialogAction("复制全文", COUIIcons.Copy) {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("notices", noticeText))
                            },
                            DialogAction("知道了", COUIIcons.Ok, primary = true) { showThirdParty = false }
                        )
                    }
                }
            }

            if (showTerms) {
                val ctx = LocalContext.current
                val termsText = remember {
                    runCatching {
                        ctx.assets.open("TERMS.md").bufferedReader().use { it.readText() }
                            .replace("**", "").replace("## ", "").replace("# ", "")
                    }.getOrDefault("协议加载失败")
                }
                val scrollState = rememberScrollState()
                var remain by remember { mutableStateOf(10) }
                val reachedBottom = remember { derivedStateOf { scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 4 } }
                LaunchedEffect(Unit) {
                    while (remain > 0) {
                        kotlinx.coroutines.delay(1000)
                        remain -= 1
                    }
                }
                val canAgree = remain <= 0 && (reachedBottom.value || scrollState.maxValue == 0)
                WindowDialog(title = "用户协议与免责声明", show = true, onDismissRequest = { }) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 470.dp)
                            .padding(horizontal = 20.dp)
                    ) {
                        Column(
                            Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                termsText,
                                style = COUITheme.textStyles.body2,
                                color = oneKeyColors().muted
                            )
                        }
                        Text(
                            when {
                                canAgree -> "已阅读并滚动到底部，可以继续"
                                scrollState.maxValue > 0 && !reachedBottom.value -> "请向下滚动阅读全文（还需 " + remain + " 秒）"
                                else -> "请阅读 " + remain + " 秒后继续"
                            },
                            style = COUITheme.textStyles.body2,
                            color = if (canAgree) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurfaceContainerVariant,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        DialogActions(
                            DialogAction("退出", COUIIcons.Close) {
                                (ctx as? android.app.Activity)?.finish()
                            },
                            DialogAction("同意并继续", COUIIcons.Ok, primary = true, enabled = canAgree) {
                                termsPrefs.edit().putBoolean("terms_accepted_v2", true).apply()
                                showTerms = false
                            }
                        )
                    }
                }
            }

            if (showNotice && !showTerms) {
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
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Text("失败后可直接再点一次；只有内核状态异常时才需要硬重启。", style = COUITheme.textStyles.body2)
                        Text(
                            "执行过程中将短暂提权并向内核装载特权模块。",
                            style = COUITheme.textStyles.body2,
                            color = oneKeyColors().muted,
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
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
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
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
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
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Text(
                            "更新时间 " + UpdateChecker.formatTime(info.publishedAt) +
                                (if (info.size > 0) " · 安装包 " + UpdateChecker.formatSize(info.size) else ""),
                            style = COUITheme.textStyles.body2,
                            color = oneKeyColors().muted
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
                            Text(err, color = oneKeyColors().error, style = COUITheme.textStyles.body2, modifier = Modifier.padding(top = 8.dp))
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
                                    color = oneKeyColors().muted
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
