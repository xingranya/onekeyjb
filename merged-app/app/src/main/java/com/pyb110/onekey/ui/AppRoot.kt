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
import com.pyb110.onekey.ui.dialog.ClearHistoryDialog
import com.pyb110.onekey.ui.dialog.RunDoneDialog
import com.pyb110.onekey.ui.dialog.RunConfirmDialog
import com.pyb110.onekey.ui.dialog.TermsDialog
import com.pyb110.onekey.ui.dialog.ThirdPartyDialog
import com.pyb110.onekey.ui.dialog.ManualDialog
import com.pyb110.onekey.ui.dialog.UpdateUi
import com.pyb110.onekey.ui.dialog.UpdateDialog

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

            if (showManual) ManualDialog { showManual = false }

            if (showThirdParty) ThirdPartyDialog { showThirdParty = false }

            if (showTerms) TermsDialog { termsPrefs.edit().putBoolean("terms_accepted_v2", true).apply(); showTerms = false }

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

            if (show98260Warning) RunConfirmDialog(
                onConfirm = { show98260Warning = false; controller.runJailbreak(); tab = 1 },
                onDismiss = { show98260Warning = false }
            )

            if (showFolkPrompt) RunDoneDialog(controller) { showFolkPrompt = false }

            if (showClearHistoryConfirm) ClearHistoryDialog(
                onCancel = { showClearHistoryConfirm = false },
                onConfirm = {
                    showClearHistoryConfirm = false
                    haptic(CouiHapticEffect.Switch)
                    scope.launch(Dispatchers.IO) { HistoryStore.clear(context); reloadHistoryTick++ }
                    scope.launch { snackbarHostState.showSnackbar("历史记录已清空") }
                }
            )

            updateInfo?.let { info ->
                UpdateDialog(
                    ui = UpdateUi(info, updateDownloading, updateProgress, updateError, updateExpandedVersion),
                    onToggleVersion = { v ->
                        haptic(CouiHapticEffect.Switch)
                        updateExpandedVersion = if (updateExpandedVersion == v) -1 else v
                    },
                    onDownload = {
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
                    },
                    onDismiss = { if (!updateDownloading) updateInfo = null }
                )
            }
        }
    }
}
