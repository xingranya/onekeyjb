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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.*
import com.pyb110.onekey.ui.components.ListCard
import com.pyb110.onekey.ui.components.ListRow
import com.pyb110.onekey.ui.components.SectionTitle

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
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.icon.extended.GridView
import io.github.suqi8.coui.kmp.icon.extended.Hide
import io.github.suqi8.coui.kmp.icon.extended.Replace
import io.github.suqi8.coui.kmp.icon.extended.Timer
import io.github.suqi8.coui.kmp.icon.extended.Tune
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
import android.os.Build
import com.pyb110.onekey.ui.AppearanceMode
import com.pyb110.onekey.ui.DarkOneKeyColors
import com.pyb110.onekey.ui.LightOneKeyColors
import com.pyb110.onekey.ui.OneKeyColors
import com.pyb110.onekey.ui.ProvideOneKeyColors
import com.pyb110.onekey.ui.oneKeyCardColor
import com.pyb110.onekey.ui.oneKeyCardOutline
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.oneKeyDeviceColor
import com.pyb110.onekey.ui.readAppearance
import com.pyb110.onekey.ui.resolveDark
import com.pyb110.onekey.ui.writeAppearance
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.LocalDismissState
import io.github.suqi8.coui.kmp.theme.ThemeController
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
import io.github.suqi8.coui.kmp.utils.rememberCouiHaptic
import io.github.suqi8.coui.kmp.window.WindowDialog
import io.github.suqi8.coui.kmp.window.WindowBottomSheet
import io.github.suqi8.coui.kmp.utils.COUIPopupUtils.Companion.COUIPopupHost
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { JailbreakController.get(applicationContext) }
            var appearance by remember { mutableStateOf(readAppearance(applicationContext)) }
            val dark = resolveDark(appearance)
            val themeController = remember(dark) {
                ThemeController(
                    ColorSchemeMode.MonetSystem,
                    keyColor = if (dark) Color(0xFFE4ADBD) else Color(0xFF9B4A5A),
                    isDark = dark
                )
            }
            COUITheme(controller = themeController) {
                ProvideOneKeyColors(dark) {
                    AppRoot(
                        controller = controller,
                        appearance = appearance,
                        isDark = dark,
                        onChangeAppearance = { mode ->
                            appearance = mode
                            writeAppearance(applicationContext, mode)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRoot(
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
                val ctx = LocalContext.current
                val noticeText = remember {
                    runCatching {
                        ctx.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
                            .replace("**", "").replace("## ", "").replace("# ", "")
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
                        Text(
                            noticeText,
                            style = COUITheme.textStyles.body2,
                            color = oneKeyColors().muted
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
                color = oneKeyColors().muted
            )
        }
        Text(
            "更新时间 " + UpdateChecker.formatTime(note.publishedAt),
            style = COUITheme.textStyles.body2,
            fontSize = 10.sp,
            color = oneKeyColors().muted,
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
                            color = oneKeyColors().muted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualSection(title: String, lines: List<String>) {
    val index = when {
        title.startsWith("一") -> 1
        title.startsWith("二") -> 2
        title.startsWith("三") -> 3
        title.startsWith("四") -> 4
        title.startsWith("五") -> 5
        else -> null
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp)) {
            if (index != null) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(COUITheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$index", style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = COUITheme.textStyles.body1,
                    color = if (index != null) COUITheme.colorScheme.onSurface else COUITheme.colorScheme.primary
                )
                lines.forEach { line ->
                    Row(Modifier.padding(top = 6.dp)) {
                        Text("·", style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            line,
                            style = COUITheme.textStyles.body2,
                            color = oneKeyColors().muted
                        )
                    }
                }
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

            val c = oneKeyColors()
            val (bg, fg, glyph) = when (state) {
                StepState.SUCCESS -> Triple(c.primary, c.onPrimary, null)
                StepState.RUNNING -> Triple(c.primarySoft, c.primaryInk, (index + 1).toString())
                StepState.ERROR -> Triple(c.error, c.onPrimary, null)
                StepState.PENDING -> Triple(c.background, c.muted, (index + 1).toString())
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
                    if (glyph != null) {
                        Text(glyph, style = COUITheme.textStyles.body2, fontSize = 11.sp, color = fg)
                    } else {
                        Icon(
                            imageVector = if (state == StepState.ERROR) COUIIcons.Close else COUIIcons.Ok,
                            contentDescription = null,
                            tint = fg,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    style = COUITheme.textStyles.body2,
                    fontSize = 11.sp,
                    color = when (state) {
                        StepState.ERROR -> c.error
                        StepState.PENDING -> c.muted
                        else -> c.primaryInk
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

private object BarBlurDefaults {
    const val topRadius = 18f
    const val bottomRadius = 16f
    val topFade = 0.dp          // 顶栏不做淡出，滚动内容彻底被挡住
    val bottomFade = 16.dp
    const val topScrim = 0.82f  // 磨砂可透视，但下方内容已被模糊，不再出现清晰叠字
    const val bottomScrim = 0.88f
}

/** 只对调用它的那一层做竖向 alpha 淡出。 */
private fun Modifier.edgeFade(fadeDp: Dp, fromBottom: Boolean): Modifier = this.drawWithCache {
    val h = size.height
    if (h <= 0f || fadeDp.value <= 0f) {
        onDrawWithContent { drawContent() }
    } else {
        val fade = fadeDp.toPx().coerceAtMost(h * 0.6f)
        val solid = (h - fade) / h
        val brush = if (fromBottom) {
            Brush.verticalGradient(0f to Color.White, solid to Color.White, 1f to Color.Transparent, startY = 0f, endY = h)
        } else {
            Brush.verticalGradient(0f to Color.Transparent, (1f - solid) to Color.White, 1f to Color.White, startY = 0f, endY = h)
        }
        onDrawWithContent {
            drawContent()
            drawRect(brush = brush, blendMode = BlendMode.DstIn)
        }
    }
}

/** 磨砂栏：模糊层 + 实底层 + 内容层，内容永远不参与遮罩。 */
@Composable
private fun BlurBar(
    backdrop: LayerBackdrop,
    shape: Shape,
    radius: Float,
    scrim: Float,
    fade: Dp,
    fromBottom: Boolean,
    modifier: Modifier = Modifier,
    onHeight: (Int) -> Unit = {},
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.onSizeChanged { onHeight(it.height) },
        contentAlignment = contentAlignment
    ) {
        // 模糊层与实底层只填充父布局，不参与尺寸计算
        Box(
            Modifier
                .matchParentSize()
                .drawBackdrop(backdrop = backdrop, shape = { shape }, effects = { blur(radius) })
                .edgeFade(fade, fromBottom)
        )
        Box(
            Modifier
                .matchParentSize()
                .background(COUITheme.colorScheme.surfaceContainer.copy(alpha = scrim), shape)
        )
        // 内容层负责撑开尺寸，绝对不能用 matchParentSize
        content()
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
    isDark: Boolean,
    onToggleAppearance: () -> Unit,
    onHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BlurBar(
        backdrop = backdrop,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        radius = BarBlurDefaults.topRadius,
        scrim = BarBlurDefaults.topScrim,
        fade = BarBlurDefaults.topFade,
        fromBottom = true,
        modifier = modifier.fillMaxWidth().statusBarsPadding(),
        onHeight = onHeight,
    ) {
        TopBarContent(
            controller = controller,
            tab = tab,
            onLogCopy = onLogCopy,
            onLogExport = onLogExport,
            onLogClear = onLogClear,
            onHistoryRefresh = onHistoryRefresh,
            onHistoryClear = onHistoryClear,
            isDark = isDark,
            onToggleAppearance = onToggleAppearance,
        )
    }
}

@Composable
private fun AppBottomBar(
    backdrop: LayerBackdrop,
    selected: Int,
    onSelect: (Int) -> Unit,
    onHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BlurBar(
        backdrop = backdrop,
        shape = RoundedCornerShape(30.dp),
        radius = BarBlurDefaults.bottomRadius,
        scrim = BarBlurDefaults.bottomScrim,
        fade = BarBlurDefaults.bottomFade,
        fromBottom = false,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
            .height(70.dp),
        onHeight = onHeight,
        contentAlignment = Alignment.Center,
    ) {
        AppBottomBarContent(selected = selected, onSelect = onSelect)
    }
}

@Composable
private fun TopBarContent(
    controller: JailbreakController,
    tab: Int,
    onLogCopy: () -> Unit,
    onLogExport: () -> Unit,
    onLogClear: () -> Unit,
    onHistoryRefresh: () -> Unit,
    onHistoryClear: () -> Unit,
    isDark: Boolean,
    onToggleAppearance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = oneKeyColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 3.dp, bottom = 6.dp)
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
                        color = oneKeyColors().muted
                    )
                }
            }
            1 -> {
                Avatar(40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("运行日志", style = COUITheme.textStyles.headline1)
                    Text(
                        "${controller.logs.size} 条记录",
                        style = COUITheme.textStyles.body2,
                        color = oneKeyColors().muted
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
                Avatar(40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("越狱历史", style = COUITheme.textStyles.headline1)
                    Text(
                        "提权与诊断归档",
                        style = COUITheme.textStyles.body2,
                        color = oneKeyColors().muted
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
                Avatar(40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("关于", style = COUITheme.textStyles.headline1)
                    Text(
                        "OneKey v" + BuildConfig.VERSION_NAME,
                        style = COUITheme.textStyles.body2,
                        color = c.muted
                    )
                }
            }
        }
            Spacer(Modifier.width(6.dp))
            Surface(
                onClick = onToggleAppearance,
                shape = CircleShape,
                color = c.primarySoft,
                border = androidx.compose.foundation.BorderStroke(1.dp, c.cardOutline),
                modifier = Modifier.size(40.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        COUIIcons.Theme,
                        contentDescription = if (isDark) "切换为浅色外观" else "切换为深色外观",
                        tint = c.primaryInk,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBottomBarContent(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = listOf("首页", "日志", "历史", "关于")
    val icons = listOf(COUIIcons.Home, COUIIcons.Notes, COUIIcons.Recent, COUIIcons.Info)
    Box(
        modifier = modifier.fillMaxSize(),
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
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp),
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

    val c = oneKeyColors()
    val containerColor = if (isOk) c.successSoft else c.errorSoft
    val contentColor = if (isOk) c.success else c.error

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
                        color = c.ink
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
    onOpenAbout: () -> Unit,
    onOpenManual: () -> Unit,
    onOpenSheet: (Int) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
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
        item { ActionCard(controller, haptic, onDiagnostics, onRunJailbreak) }
        item { SectionTitle("拓展功能") }
        item {
            OptionsCard(
                controller = controller,
                haptic = haptic,
                onOpenSheet = onOpenSheet,
                onDiagnostics = onDiagnostics
            )
        }
        item { SectionTitle("更多") }
        item {
            ListCard(
                rows = listOf(
                    ListRow(
                        title = "用户手册",
                        summary = "从零开始，5 秒钟完成越狱",
                        icon = COUIIcons.Help,
                        arrow = true,
                        onClick = { haptic(CouiHapticEffect.Switch); onOpenManual() }
                    ),
                    ListRow(
                        title = "关于 OneKey",
                        summary = "v" + BuildConfig.VERSION_NAME + " · " + BuildConfig.VERSION_CODE,
                        icon = COUIIcons.Info,
                        arrow = true,
                        onClick = { haptic(CouiHapticEffect.Switch); onOpenAbout() }
                    )
                )
            )
        }
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
    val c = oneKeyColors()
    val suReady = controller.suCompatReady
    val adapterValue = when {
        controller.hotSwitchAvailable -> "可用"
        controller.hotSwitchLoaderInstalled -> "待重启"
        else -> "待启用"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(oneKeyDeviceColor())
            .border(1.dp, oneKeyCardOutline(), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    controller.profile?.displayName ?: "OnePlus",
                    style = COUITheme.textStyles.headline1,
                    color = c.deviceInk
                )
                Text(
                    (controller.profile?.model ?: "PYB110") + " · Android " + Build.VERSION.RELEASE,
                    style = COUITheme.textStyles.body2,
                    color = c.deviceMuted
                )
            }
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (controller.serviceReady) c.deviceStatusBg else c.warningSoft)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (controller.serviceReady) COUIIcons.Ok else COUIIcons.Info,
                    contentDescription = null,
                    tint = if (controller.serviceReady) c.deviceStatusInk else c.warning,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (controller.serviceReady) "服务在线" else "服务离线",
                    style = COUITheme.textStyles.body2,
                    color = if (controller.serviceReady) c.deviceStatusInk else c.warning,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = c.deviceOutline)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            DeviceCell(
                label = "服务通道",
                value = if (controller.serviceReady && controller.serviceGranted) "已授权"
                        else if (controller.serviceReady) "待授权" else "未连接",
                icon = COUIIcons.Link,
                positive = controller.serviceReady && controller.serviceGranted,
                modifier = Modifier.weight(1f)
            )
            DeviceCell(
                label = "SU 权限",
                value = if (suReady) "已就绪" else "未就绪",
                icon = COUIIcons.Lock,
                positive = suReady,
                modifier = Modifier.weight(1f)
            )
            DeviceCell(
                label = "热切换",
                value = adapterValue,
                icon = COUIIcons.Replace,
                positive = controller.hotSwitchAvailable,
                modifier = Modifier.weight(1f)
            )
            DeviceCell(
                label = "本次开机",
                value = if (controller.rootedThisBoot) "已运行" else "未运行",
                icon = COUIIcons.Refresh,
                positive = controller.rootedThisBoot,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("内核版本", style = COUITheme.textStyles.body2, fontSize = 11.sp, color = c.deviceMuted)
            Spacer(Modifier.width(12.dp))
            Spacer(Modifier.weight(1f))
            Text(
                shortKernelVersion(),
                style = COUITheme.textStyles.body2,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = c.deviceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 设备卡里的单列状态：图标 + 标签一行，值在下方。 */
@Composable
private fun DeviceCell(
    label: String,
    value: String,
    icon: ImageVector,
    positive: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = oneKeyColors()
    Column(modifier.padding(end = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = c.deviceMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = COUITheme.textStyles.body2,
                fontSize = 11.sp,
                color = c.deviceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = COUITheme.textStyles.body2,
            color = if (positive) c.devicePositive else c.deviceInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
                Text(controller.deviceInfo, style = COUITheme.textStyles.body2, color = oneKeyColors().muted, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp))
            }
            Text(
                when {
                    controller.suCompatReady -> "默认 SU：已就绪"
                    controller.rootedThisBoot -> "默认 SU：本次开机已越狱；服务离线无法实时校验，启动 Stellar 即可复查"
                    else -> "默认 SU：未就绪（重新运行一次越狱即可）"
                },
                style = COUITheme.textStyles.body2,
                color = if (controller.suCompatReady) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
            )
        }
    }
}

/** 读取已安装管理器的真实图标；读不到时退回字母标记。 */
@Composable
private fun ManagerMark(pkg: String, label: String, size: Int = 36) {
    val context = LocalContext.current
    val c = oneKeyColors()
    val bitmap = remember(pkg) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.primarySoft),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size((size - 8).dp).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Text(
                label.take(1).uppercase(),
                style = COUITheme.textStyles.body2,
                color = c.primaryInk
            )
        }
    }
}

private fun sheetTitle(sheet: Int): String = when (sheet) {
    1 -> "管理器热切换"
    2 -> "管理器下载"
    3 -> "拓展开关"
    4 -> "选择目标管理器"
    5 -> "选择越狱管理器"
    else -> ""
}

/** 内核版本取到 android 代次为止，避免长串 SMP 信息把一行挤爆。 */
private fun shortKernelVersion(): String {
    val raw = System.getProperty("os.version") ?: return "未知"
    val cut = raw.substringBefore("-SMP").substringBefore(" SMP")
    return if (cut.length > 34) cut.take(34) + "…" else cut
}

internal fun appearanceLabel(mode: AppearanceMode): String = when (mode) {
    AppearanceMode.LIGHT -> "浅色 · 灰白配深玫瑰"
    AppearanceMode.DARK -> "深色 · 炭黑配雾玫瑰"
    AppearanceMode.SYSTEM -> "跟随系统"
}

private fun managerHint(kind: String): String {
    val d = ManagerDownloads.latest(kind) ?: return "支持自适应与云端模块"
    return d.label + " " + d.version + "（versionCode " + d.versionCode + "，支持自适应与云端模块）"
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
                color = oneKeyColors().muted,
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
    onOpenSheet: (Int) -> Unit,
    onDiagnostics: () -> Unit,
) {
    val hotSummary = when {
        controller.hotSwitchAvailable -> "当前 " + controller.activeKernelManager.ifBlank { "未知" }
        controller.hotSwitchLoaderInstalled -> "通用加载器已安装，重启后运行一次即可启用"
        else -> "安装通用加载器后启用"
    }
    ListCard(
        rows = listOf(
            ListRow(
                title = "管理器热切换",
                summary = hotSummary,
                icon = COUIIcons.Replace,
                arrow = true,
                onClick = { haptic(CouiHapticEffect.Switch); onOpenSheet(1) }
            ),
            ListRow(
                title = "越狱管理器",
                summary = controller.managers.firstOrNull { it.kind == controller.selectedManager }?.label ?: "未选择",
                icon = COUIIcons.Tasks,
                arrow = true,
                onClick = { haptic(CouiHapticEffect.Switch); onOpenSheet(5) }
            ),
            ListRow(
                title = "管理器下载",
                summary = "五家官方最新正式版",
                icon = COUIIcons.Download,
                arrow = true,
                onClick = { haptic(CouiHapticEffect.Switch); onOpenSheet(2) }
            ),
            ListRow(
                title = "拓展开关",
                summary = "SUSFS / VFS / 卸载保护 / 反检测 / 软重启",
                icon = COUIIcons.Tune,
                arrow = true,
                onClick = { haptic(CouiHapticEffect.Switch); onOpenSheet(3) }
            ),
            ListRow(
                title = "环境诊断",
                summary = "检查服务通道、SU 权限与内核状态",
                icon = COUIIcons.Report,
                arrow = true,
                onClick = { haptic(CouiHapticEffect.Switch); onDiagnostics() }
            ),
        )
    )
}

@Composable
private fun HotSwitchPanel(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    selectedTarget: String,
    onPickTarget: () -> Unit,
) {
    val c = oneKeyColors()
    val nodeReady = controller.hotSwitchAvailable
    val loaderInstalled = controller.hotSwitchLoaderInstalled
    val canSwitch = nodeReady && controller.suCompatReady && !controller.running &&
        !controller.preparing && selectedTarget.isNotBlank() && !controller.isSwitchingManager
    val targetLabel = controller.managers.firstOrNull { it.kind == selectedTarget }?.label ?: "未安装"

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = if (nodeReady) c.primary else c.surfaceLow
            ) {
                Text(
                    if (nodeReady) "节点就绪" else "未就绪",
                    style = COUITheme.textStyles.body2,
                    color = if (nodeReady) c.onPrimary else c.muted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "当前 " + controller.activeKernelManager.ifBlank { "未启用" },
                style = COUITheme.textStyles.body1,
                color = c.primary
            )
        }
        Text(
            text = when {
                nodeReady -> "切换只改内核 profile，不重启、不重新越狱。"
                loaderInstalled -> "通用加载器已安装；重启手机后重新运行一次越狱即可启用。"
                !controller.serviceReady -> "服务离线，启动 Stellar 并授权后可检测热切换节点。"
                else -> "热切换需要通用模块。安装后重启手机并重新运行一次越狱即可启用。"
            },
            style = COUITheme.textStyles.body2,
            color = c.muted,
            modifier = Modifier.padding(top = 8.dp)
        )

        // 目标管理器：只在可以切换时才需要，未就绪时不占地方。
        if (nodeReady) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceLow)
                    .clickable(enabled = !controller.running) { onPickTarget() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("目标管理器", style = COUITheme.textStyles.body1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(targetLabel, style = COUITheme.textStyles.body1, color = c.primary)
                    Spacer(Modifier.width(6.dp))
                    Icon(COUIIcons.ChevronForward, contentDescription = null, modifier = Modifier.size(16.dp), tint = c.muted)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 按钮按当前阶段收敛：未装只给「安装」，已装未生效只给「重启」，就绪后才出现「切换」。
        when {
            !loaderInstalled -> {
                PanelButton(
                    text = "安装通用加载器",
                    primary = true,
                    enabled = !controller.loaderBusy && controller.suCompatReady,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    haptic(CouiHapticEffect.Switch)
                    controller.installHotSwitchLoader { _, msg -> controller.addLog("加载器：" + msg) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "安装会备份并替换管理器目录下的 libksud.so，可随时还原。",
                    style = COUITheme.textStyles.body2,
                    color = c.muted
                )
            }
            !nodeReady -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelButton(
                        text = "重启手机",
                        primary = true,
                        enabled = !controller.loaderBusy && controller.suCompatReady,
                        modifier = Modifier.weight(1f)
                    ) { haptic(CouiHapticEffect.Switch); controller.rebootDevice() }
                    PanelButton(
                        text = "检测",
                        modifier = Modifier.weight(1f)
                    ) { haptic(CouiHapticEffect.Switch); controller.refreshState() }
                }
                Spacer(Modifier.height(8.dp))
                PanelButton(
                    text = "还原加载器",
                    enabled = !controller.loaderBusy && controller.suCompatReady,
                    modifier = Modifier.fillMaxWidth()
                ) { haptic(CouiHapticEffect.Switch); controller.restoreHotSwitchLoader { _, _ -> } }
            }
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelButton(
                        text = if (controller.isSwitchingManager) "切换中…" else "切换",
                        primary = true,
                        enabled = canSwitch,
                        modifier = Modifier.weight(2f)
                    ) { haptic(CouiHapticEffect.Switch); controller.executeHotSwitch(selectedTarget) { _, _ -> } }
                    PanelButton(
                        text = "检测",
                        modifier = Modifier.weight(1f)
                    ) { haptic(CouiHapticEffect.Switch); controller.refreshState() }
                }
                Spacer(Modifier.height(8.dp))
                PanelButton(
                    text = "还原加载器",
                    enabled = !controller.loaderBusy && controller.suCompatReady,
                    modifier = Modifier.fillMaxWidth()
                ) { haptic(CouiHapticEffect.Switch); controller.restoreHotSwitchLoader { _, _ -> } }
            }
        }

        if (controller.hotSwitchMessage.isNotBlank()) {
            Text(
                controller.hotSwitchMessage,
                style = COUITheme.textStyles.body2,
                color = c.muted,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        if (controller.activeKernelRaw.isNotBlank()) {
            Text(
                "primary：" + controller.activeKernelRaw,
                style = COUITheme.textStyles.body2,
                color = c.muted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (controller.probeDetail.isNotBlank()) {
            Text(
                controller.probeDetail,
                style = COUITheme.textStyles.body2,
                color = c.muted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** 目标管理器选择列表。 */
@Composable
private fun TargetPickerPanel(
    controller: JailbreakController,
    selected: String,
    onPick: (String) -> Unit,
) {
    val c = oneKeyColors()
    Column(Modifier.fillMaxWidth()) {
        controller.managers.forEach { option ->
            val available = option.installedVersionCode != null && option.certOk
            val isSelected = option.kind == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = available) { onPick(option.kind) }
                    .padding(vertical = 10.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ManagerMark(option.pkg, option.label)
                Column(Modifier.weight(1f)) {
                    Text(
                        option.label,
                        style = COUITheme.textStyles.body1,
                        color = when {
                            !available -> c.disabledInk
                            isSelected -> c.primaryInk
                            else -> c.ink
                        }
                    )
                    Text(
                        when {
                            option.installedVersionCode == null -> "未安装"
                            !option.certOk -> "签名不符"
                            else -> "v" + (option.installedVersion ?: "-") + " · 已安装"
                        },
                        style = COUITheme.textStyles.body2,
                        color = if (available) c.muted else c.error
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) c.primary else Color.Transparent)
                        .then(
                            if (isSelected) Modifier
                            else Modifier.border(1.5.dp, c.disabledInk.copy(alpha = 0.6f), CircleShape)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(COUIIcons.Ok, contentDescription = "已选", tint = c.onPrimary, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}
/** 越狱管理器选择列表。 */
@Composable
private fun JailManagerPanel(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
) {
    val c = oneKeyColors()
    Column(Modifier.fillMaxWidth()) {
        if (controller.visibleManagers.isEmpty()) {
            Text(
                "未检测到管理器，请先安装列表中的固定版本。",
                style = COUITheme.textStyles.body2,
                color = c.muted,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        controller.visibleManagers.forEach { manager ->
            val selectable = !controller.running && manager.supported &&
                manager.installedVersionCode != null && manager.certOk &&
                (if (controller.adaptiveManagerEnabled) true
                else (manager.kind != "finasu" && manager.installedVersionCode == manager.expectedVersionCode))
            val isSelected = controller.selectedManager == manager.kind
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = selectable) {
                        haptic(CouiHapticEffect.Switch); controller.selectManager(manager.kind)
                    }
                    .padding(vertical = 10.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ManagerMark(manager.pkg, manager.label)
                Column(Modifier.weight(1f)) {
                    Text(
                        manager.label,
                        style = COUITheme.textStyles.body1,
                        color = when {
                            !selectable -> c.disabledInk
                            isSelected -> c.primaryInk
                            else -> c.ink
                        }
                    )
                    Text(
                        manager.statusText,
                        style = COUITheme.textStyles.body2,
                        color = c.muted
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) c.primary else Color.Transparent)
                        .then(
                            if (isSelected) Modifier
                            else Modifier.border(1.5.dp, c.disabledInk.copy(alpha = 0.6f), CircleShape)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(COUIIcons.Ok, contentDescription = "已选", tint = c.onPrimary, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "运行前选择管理器。未安装时，请先前往管理器下载。",
            style = COUITheme.textStyles.body2,
            color = c.muted
        )
    }
}
/** 抽屉内的操作按钮：主色实心或浅底两种。 */
@Composable
private fun PanelButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(14.dp),
        enabled = enabled,
        color = if (primary && enabled) COUITheme.colorScheme.primary else COUITheme.colorScheme.surfaceContainer
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = COUITheme.textStyles.body1,
                color = when {
                    primary && enabled -> COUITheme.colorScheme.onPrimary
                    enabled -> COUITheme.colorScheme.primary
                    else -> COUITheme.colorScheme.onSurfaceContainerVariant
                }
            )
        }
    }
}

@Composable
private fun ManagerDownloadPanel(
) {
    val downloadContext = LocalContext.current
    val c = oneKeyColors()
    val controller = remember { JailbreakController.get(downloadContext) }
    val pkgByKind = remember { controller.managers.associate { it.kind to it.pkg } }
    var items by remember { mutableStateOf(ManagerDownloads.all()) }
    var syncing by remember { mutableStateOf(false) }
    val panelScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (ManagerDownloads.isFresh()) {
            items = ManagerDownloads.all()
            return@LaunchedEffect
        }
        syncing = true
        withContext(Dispatchers.IO) { controller.refreshManagerCatalog(true) }
        items = ManagerDownloads.all()
        syncing = false
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            if (items.isEmpty()) "正在获取官方最新版本…" else "版本取自官方最新发布，点击下载打开对应发布页。",
            style = COUITheme.textStyles.body2,
            color = c.muted,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        items.forEach { item ->
            val pkg = pkgByKind[item.kind].orEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { openUrl(downloadContext, item.apkUrl) }
                    .padding(vertical = 10.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ManagerMark(pkg, item.label)
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = COUITheme.textStyles.body1, color = c.ink)
                    Text(
                        item.version + " · " + item.versionCode,
                        style = COUITheme.textStyles.body2,
                        color = c.muted
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下载", style = COUITheme.textStyles.body2, color = c.primaryInk)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        COUIIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = c.primary
                    )
                }
            }
        }
        if (items.isEmpty() && !syncing) {
            Text(
                "暂未获取到官方版本信息，请检查网络后重新获取。",
                style = COUITheme.textStyles.body2,
                color = c.muted
            )
            Spacer(Modifier.height(10.dp))
            PanelButton("重新获取") {
                panelScope.launch {
                    syncing = true
                    withContext(Dispatchers.IO) { controller.refreshManagerCatalog(true) }
                    items = ManagerDownloads.all()
                    syncing = false
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "打不开下载页时，可复制发布页链接到浏览器打开。",
            style = COUITheme.textStyles.body2,
            color = c.muted
        )
    }
}

@Composable
private fun FeatureTogglePanel(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
) {
    ListCard(
        rows = listOf(
            ListRow(
                title = "越狱后禁用安全弹窗",
                summary = "停用安全守护与安全中心，不影响截屏",
                icon = COUIIcons.Lock,
                end = {
                    Switch(
                        checked = controller.antiRootGuard,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateAntiRootGuard(it) },
                        enabled = !controller.running
                    )
                }
            ),
            ListRow(
                title = "越狱后软重启",
                summary = "ColorOS 拦截原生调用时，软重启用户态恢复 root",
                icon = COUIIcons.Refresh,
                end = {
                    Switch(
                        checked = controller.softRebootFix,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateSoftRebootFix(it) },
                        enabled = !controller.running
                    )
                }
            ),
            ListRow(
                title = "越狱后卸载内核安全防护",
                summary = "卸载 oplus_secure_guard 等内核防护模块",
                icon = COUIIcons.Unlock,
                end = {
                    Switch(
                        checked = controller.unloadKernelGuard,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateUnloadKernelGuard(it) },
                        enabled = !controller.running
                    )
                }
            ),
            ListRow(
                title = "SUSFS 支持",
                summary = "加载 SUSFS 隐藏模块（需要执行器侧配合）",
                icon = COUIIcons.Hide,
                end = {
                    Switch(
                        checked = controller.susfsEnabled,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateSusfs(it) },
                        enabled = !controller.running
                    )
                }
            ),
            ListRow(
                title = "VFS 支持",
                summary = "加载 hybridmount VFS 重定向（仅 6.18 内核）",
                icon = COUIIcons.Tasks,
                end = {
                    Switch(
                        checked = controller.vfsEnabled,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateVfs(it) },
                        enabled = !controller.running
                    )
                }
            )
        )
    )
}

@Composable
private fun ActionCard(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    onDiagnostics: () -> Unit,
    onRunJailbreak: () -> Unit,
) {
    val c = oneKeyColors()
    val managerLabel = controller.managers.firstOrNull { it.kind == controller.selectedManager }?.label ?: "未选择"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(oneKeyCardColor())
            .border(1.dp, oneKeyCardOutline(), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        controller.running -> "正在运行"
                        controller.suCompatReady -> "运行完成"
                        controller.canRunExploit -> "准备就绪"
                        else -> "等待条件"
                    },
                    style = COUITheme.textStyles.headline2,
                    color = c.ink
                )
                Text(
                    managerLabel + " · 98260 运行链",
                    style = COUITheme.textStyles.body2,
                    color = c.muted
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.primarySoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (controller.suCompatReady) COUIIcons.Ok else COUIIcons.Lock,
                    contentDescription = null,
                    tint = c.primary,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PanelButton(
            text = if (controller.running) "运行中，请稍候" else "开始运行",
            primary = true,
            enabled = controller.canRunExploit && !controller.running,
            modifier = Modifier.fillMaxWidth()
        ) { haptic(CouiHapticEffect.Switch); onRunJailbreak() }
        controller.platformReason?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = c.error, style = COUITheme.textStyles.body2)
        }
        Spacer(Modifier.height(12.dp))
        RunFlow(controller.progress, controller.running, controller.lastResultOk)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    controller.running -> controller.stage.ifBlank { "运行中" }
                    controller.suCompatReady -> "本次运行已完成"
                    else -> "等待开始"
                },
                style = COUITheme.textStyles.body2,
                color = c.muted,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (!controller.running && controller.suCompatReady && controller.progress < 0.99f) "100%"
                else (controller.progress * 100).toInt().toString() + "%",
                style = COUITheme.textStyles.body2,
                color = c.primaryInk
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = controller.progress, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(
            "失败可直接重试，内核异常时才需重启",
            style = COUITheme.textStyles.body2,
            color = c.muted
        )
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
        var filter by remember { mutableIntStateOf(0) }
        val filtered = records.filter {
            when (filter) {
                1 -> it.status == "ok"
                2 -> it.status != "ok"
                else -> true
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) {
                    listOf("全部", "成功", "失败").forEachIndexed { index, label ->
                        val on = filter == index
                        Surface(
                            onClick = { haptic(CouiHapticEffect.Switch); filter = index },
                            shape = CircleShape,
                            color = if (on) COUITheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                label,
                                style = COUITheme.textStyles.body2,
                                color = if (on) COUITheme.colorScheme.onPrimary else COUITheme.colorScheme.onSurfaceContainerVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
            listOf(0 to "今日", 1 to "昨日", 2 to "更早").forEach { (bucket, label) ->
                val groupRows = filtered.filter { historyDayBucket(it.startedAt) == bucket }
                if (groupRows.isNotEmpty()) {
                    item { SectionTitle(label + " · " + groupRows.size + " 条") }
                    item {
                        ListCard(
                            rows = groupRows.map { rec ->
                                val isOk = rec.status == "ok"
                                ListRow(
                                    title = "98260 运行链",
                                    summary = rec.manager + " · " +
                                        HistoryStore.formatTime(rec.startedAt) + " · " +
                                        (if (isOk) "运行成功" else "未完成") + " " +
                                        String.format(java.util.Locale.US, "%.2fs", rec.durationSec.toDouble()),
                                    icon = if (isOk) COUIIcons.Ok else COUIIcons.Close,
                                    iconTint = if (isOk) oneKeyColors().success else oneKeyColors().error,
                                    arrow = true,
                                    onClick = { haptic(CouiHapticEffect.Switch); selected = rec }
                                )
                            }
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "当前筛选下没有记录",
                            style = COUITheme.textStyles.body2,
                            color = oneKeyColors().muted,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Surface(
                    onClick = { haptic(CouiHapticEffect.Switch); onClearRequest() },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = COUITheme.colorScheme.surfaceContainer
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("清空全部历史记录", style = COUITheme.textStyles.body1, color = oneKeyColors().error)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** 历史记录按天分桶：0 今日、1 昨日、2 更早。 */
private fun historyDayBucket(ts: Long): Int {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val today = cal.timeInMillis
    val yesterday = today - 86_400_000L
    return when {
        ts >= today -> 0
        ts >= yesterday -> 1
        else -> 2
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
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    val c = oneKeyColors()
    val logs = controller.logs
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    var filter by remember { mutableIntStateOf(0) }

    // 运行时长精确到小数点后两位：运行中按毫秒计时，结束后沿用控制器记录的秒数。
    var startMs by remember { mutableStateOf<Long?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(controller.running) {
        if (controller.running) {
            startMs = System.currentTimeMillis()
            while (controller.running) {
                tick++
                kotlinx.coroutines.delay(50)
            }
            startMs = null
        }
    }
    val elapsedSeconds = if (controller.running && startMs != null) {
        tick.let { (System.currentTimeMillis() - (startMs ?: 0L)) / 1000.0 }
    } else {
        controller.elapsedSeconds.toDouble()
    }
    val elapsedText = String.format(java.util.Locale.US, "%.2f", elapsedSeconds)

    val filtered = when (filter) {
        1 -> logs.filter { logLevel(it) >= 1 }
        2 -> logs.filter { logLevel(it) == 2 }
        else -> logs
    }
    LaunchedEffect(filtered.size, autoScroll, filter) {
        if (autoScroll && filtered.isNotEmpty()) listState.animateScrollToItem(filtered.size - 1)
    }

    if (logs.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))
            Icon(COUIIcons.Notes, contentDescription = null, tint = c.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text("还没有运行日志", style = COUITheme.textStyles.headline2, color = c.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "开始运行或执行环境诊断后，输出会出现在这里。",
                style = COUITheme.textStyles.body2,
                color = c.muted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            PanelButton(text = "前往首页", modifier = Modifier.fillMaxWidth()) { onGoHome() }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(oneKeyCardColor())
                .border(1.dp, oneKeyCardOutline(), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (controller.running) "任务进行中" else if (logs.isNotEmpty()) "本次运行" else "等待运行",
                        style = COUITheme.textStyles.headline2,
                        color = c.ink
                    )
                    Text(
                        if (controller.running) controller.stage.ifBlank { "运行中" }
                        else if (controller.lastResultOk == true) "运行结束" else "运行输出",
                        style = COUITheme.textStyles.body2,
                        color = c.muted
                    )
                }
                Text(
                    elapsedText + "s",
                    style = COUITheme.textStyles.body2,
                    fontFamily = FontFamily.Monospace,
                    color = c.muted
                )
            }
            Spacer(Modifier.height(12.dp))
            RunFlow(controller.progress, controller.running, controller.lastResultOk)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.segmentBg)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("全部", "警告", "错误").forEachIndexed { index, label ->
                val selected = filter == index
                Surface(
                    onClick = { haptic(CouiHapticEffect.Switch); filter = index },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) c.surface else Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = COUITheme.textStyles.body2,
                            color = if (selected) c.primaryInk else c.muted
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).clickable { autoScroll = !autoScroll },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
                Spacer(Modifier.width(8.dp))
                Text("自动滚动", style = COUITheme.textStyles.body2, color = c.muted)
            }
            IconButton(onClick = onCopy, enabled = logs.isNotEmpty()) {
                Icon(COUIIcons.Copy, contentDescription = "复制全部日志", tint = c.muted)
            }
            IconButton(onClick = onExport, enabled = logs.isNotEmpty()) {
                Icon(COUIIcons.Download, contentDescription = "导出全部日志", tint = c.muted)
            }
            IconButton(onClick = onClear, enabled = logs.isNotEmpty() && !controller.running) {
                Icon(COUIIcons.Delete, contentDescription = "清空日志", tint = c.muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(oneKeyCardColor())
                .border(1.dp, oneKeyCardOutline(), RoundedCornerShape(20.dp))
        ) {
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (logs.isEmpty()) "还没有运行日志" else "当前筛选下没有记录",
                        style = COUITheme.textStyles.body2,
                        color = c.muted
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    items(filtered.size) { idx ->
                        val line = filtered[idx]
                        val level = logLevel(line)
                        val tint = when {
                            level == 2 -> c.error
                            level == 1 -> c.warning
                            isSuccessLog(line) -> c.success
                            else -> c.muted
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                line,
                                style = COUITheme.textStyles.body2,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = when {
                                    level == 2 -> c.error
                                    level == 1 -> c.warning
                                    isSuccessLog(line) -> c.success
                                    else -> c.ink
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 0 = 普通/成功，1 = 警告，2 = 错误。 */
private fun logLevel(line: String): Int = when {
    line.contains("失败") || line.contains("中止") || line.contains("错误") ||
        line.contains("rc=-1") || line.contains("FAIL") || line.contains("MISSING") -> 2
    line.contains("未命中") || line.contains("跳过") || line.contains("SKIP") ||
        line.contains("警告") || line.contains("离线") -> 1
    else -> 0
}

private fun isSuccessLog(line: String): Boolean =
    line.contains("SU_ID_RC=0") || line.contains("已加载") || line.contains("成功") ||
        line.contains("就绪") || line.contains("完成")

@Composable
private fun SmallTitle(text: String) {
    Text(
        text = text,
        style = COUITheme.textStyles.subtitle,
        color = oneKeyColors().muted,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun AboutScreen(
    controller: JailbreakController,
    appearance: AppearanceMode,
    onChangeAppearance: (AppearanceMode) -> Unit,
    haptic: (CouiHapticEffect) -> Unit,
    onOpenLog: () -> Unit,
    onNotice: () -> Unit,
    onManual: () -> Unit,
    onThirdParty: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    var showAppearanceSheet by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Avatar(72)
                Spacer(Modifier.height(10.dp))
                Text("OneKey", style = COUITheme.textStyles.headline1)
                Text(
                    "v" + BuildConfig.VERSION_NAME + " · " + BuildConfig.VERSION_CODE,
                    style = COUITheme.textStyles.body2,
                    color = oneKeyColors().muted
                )
            }
        }
        item { SectionTitle("设备") }
        item {
            ListCard(
                rows = listOf(
                    ListRow(
                        title = "设备",
                        summary = (controller.profile?.displayName ?: "OnePlus") + " · " +
                            (controller.profile?.model ?: "UNKNOWN"),
                        icon = COUIIcons.Info
                    ),
                    ListRow(
                        title = "内核",
                        summary = System.getProperty("os.version") ?: "未知",
                        icon = COUIIcons.Tasks
                    ),
                    ListRow(
                        title = "模块",
                        summary = if (controller.hotSwitchLoaderInstalled) "通用多管理器（已安装加载器）" else "管理器自带模块",
                        icon = COUIIcons.Replace
                    )
                )
            )
        }
        item { SectionTitle("个性化") }
        item {
            ListCard(
                rows = listOf(
                    ListRow(
                        title = "外观",
                        summary = appearanceLabel(appearance),
                        icon = COUIIcons.Theme,
                        arrow = true,
                        onClick = { haptic(CouiHapticEffect.Switch); showAppearanceSheet = true }
                    ),
                    ListRow(
                        title = "自动匹配云端组件",
                        summary = if (controller.cloudAuto) "按设备与管理器自动获取模块" else "仅使用本机固定组件",
                        icon = COUIIcons.Update,
                        end = {
                            Switch(
                                checked = controller.cloudAuto,
                                onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateCloudAuto(it) },
                                enabled = !controller.running
                            )
                        }
                    )
                )
            )
        }
        item { SectionTitle("支持与说明") }
        item {
            ListCard(
                rows = listOf(
                    ListRow(
                        title = "检查更新",
                        summary = if (controller.checkingUpdate) "正在检查…" else controller.updateStatus,
                        icon = COUIIcons.Refresh,
                        arrow = true,
                        onClick = { haptic(CouiHapticEffect.Switch); controller.checkUpdates() }
                    ),
                    ListRow(
                        title = "用户手册",
                        summary = "首次使用、拓展功能说明与异常处理",
                        icon = COUIIcons.Notes,
                        arrow = true,
                        onClick = onManual
                    ),
                    ListRow(
                        title = "免费声明与赞赏码",
                        summary = "群内免费 · 禁止倒卖",
                        icon = COUIIcons.Help,
                        arrow = true,
                        onClick = onNotice
                    ),
                    ListRow(
                        title = "COUI 组件库",
                        summary = "github.com/suqi8/coui",
                        icon = COUIIcons.Link,
                        arrow = true,
                        onClick = { openUrl(context, "https://github.com/suqi8/coui") }
                    ),
                    ListRow(
                        title = "Stellar",
                        summary = "github.com/roro2239/Stellar",
                        icon = COUIIcons.Link,
                        arrow = true,
                        onClick = { openUrl(context, "https://github.com/roro2239/Stellar") }
                    ),
                    ListRow(
                        title = "第三方组件说明",
                        summary = "感谢以下开源项目与实现",
                        icon = COUIIcons.Report,
                        arrow = true,
                        onClick = onThirdParty
                    )
                )
            )
        }
        item {
            Text(
                "GPL-3.0 · 作者 星苒鸭 · github.com/xingranya",
                style = COUITheme.textStyles.body2,
                color = oneKeyColors().muted,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp),
                textAlign = TextAlign.Center
            )
        }
    }

    var appearanceVisible by remember { mutableStateOf(false) }
    LaunchedEffect(showAppearanceSheet) { if (showAppearanceSheet) appearanceVisible = true }
    if (showAppearanceSheet) {
        WindowBottomSheet(
            show = appearanceVisible,
            title = "外观",
            onDismissRequest = { appearanceVisible = false },
            onDismissFinished = { showAppearanceSheet = false; appearanceVisible = false }
        ) {
            AppearanceSheetContent(
                current = appearance,
                onPick = { mode -> haptic(CouiHapticEffect.Switch); onChangeAppearance(mode) }
            )
        }
    }
}

/** 外观抽屉：三种模式 + 两档配色预览。 */
@Composable
private fun AppearanceSheetContent(
    current: AppearanceMode,
    onPick: (AppearanceMode) -> Unit,
) {
    val c = oneKeyColors()
    Column(Modifier.fillMaxWidth().height(420.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppearancePreview("浅色", LightOneKeyColors, Modifier.weight(1f))
            AppearancePreview("深色", DarkOneKeyColors, Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(oneKeyCardColor())) {
            AppearanceMode.entries.forEachIndexed { index, mode ->
                val selected = current == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(mode) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (mode) {
                            AppearanceMode.LIGHT -> COUIIcons.Theme
                            AppearanceMode.DARK -> COUIIcons.Hide
                            AppearanceMode.SYSTEM -> COUIIcons.Settings
                        },
                        contentDescription = null,
                        tint = c.primary,
                        modifier = Modifier.size(21.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (mode) {
                                AppearanceMode.LIGHT -> "浅色"
                                AppearanceMode.DARK -> "深色"
                                AppearanceMode.SYSTEM -> "跟随系统"
                            },
                            style = COUITheme.textStyles.body1,
                            color = if (selected) c.primaryInk else c.ink
                        )
                        Text(
                            when (mode) {
                                AppearanceMode.LIGHT -> "灰白界面，搭配深玫瑰色"
                                AppearanceMode.DARK -> "炭黑界面，搭配雾玫瑰色"
                                AppearanceMode.SYSTEM -> "随设备的深浅外观自动变化"
                            },
                            style = COUITheme.textStyles.body2,
                            color = c.muted
                        )
                    }
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (selected) c.primary else Color.Transparent)
                            .then(if (selected) Modifier else Modifier.border(1.5.dp, c.muted, CircleShape)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(COUIIcons.Ok, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(15.dp))
                        }
                    }
                }
                if (index != AppearanceMode.entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth().padding(start = 49.dp, end = 16.dp),
                        color = c.outline
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "外观应用于全部页面、抽屉与提示，选择会被保留。",
            style = COUITheme.textStyles.body2,
            color = c.muted
        )
    }
}

@Composable
private fun AppearancePreview(label: String, palette: OneKeyColors, modifier: Modifier = Modifier) {
    val c = oneKeyColors()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(palette.background)
            .border(if (c.isDark == palette.isDark) 2.dp else 1.dp, if (c.isDark == palette.isDark) c.primary else c.outline, RoundedCornerShape(20.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.deviceSurface)
                .border(1.dp, palette.deviceOutline, RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)).background(palette.primary))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(4.dp)).background(palette.surface))
        Spacer(Modifier.height(8.dp))
        Text(label, style = COUITheme.textStyles.body2, color = palette.ink)
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
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("倒卖必究", style = COUITheme.textStyles.subtitle, color = oneKeyColors().error)
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
