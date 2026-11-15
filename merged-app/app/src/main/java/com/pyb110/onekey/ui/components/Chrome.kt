package com.pyb110.onekey.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyb110.onekey.BuildConfig
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.theme.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.theme.CouiTheme
import com.itos.coui.navigation.CouiNavigationItem
import com.itos.coui.navigation.NavigationBar as CouiNavigationBar
import com.itos.coui.topappbar.TopAppBar as CouiTopAppBar
import com.itos.coui.shape.CouiShape

internal object BarBlurDefaults {
    // COUI 的材质观感靠真实离屏模糊，不靠厚遮罩；scrim 只补一点对比度。
    const val topRadius = 28f
    const val bottomRadius = 28f
    val topFade = 0.dp          // 顶栏不做淡出，滚动内容彻底被挡住
    val bottomFade = 16.dp
    const val topScrim = 0.34f
    const val bottomScrim = 0.40f
}

/** 只对调用它的那一层做竖向 alpha 淡出。 */
internal fun Modifier.edgeFade(fadeDp: Dp, fromBottom: Boolean): Modifier = this.drawWithCache {
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
internal fun BlurBar(
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
internal fun TopBar(
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
    // COUI 原生顶栏：渐变模糊、圆角与状态栏内边距都由库负责，
    // 采样源由 Scaffold 的 topBar 槽自动提供，不需要手动传。
    CouiTopAppBar(
        modifier = modifier.onSizeChanged { onHeight(it.height) },
        title = {
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
        },
    )
}

@Composable
internal fun TopBarContent(
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
    // COUI 顶栏是固定 52dp 单行：左头像、中标题+副标题、右操作，全部一行排布。
    val c = oneKeyColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(36)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                when (tab) {
                    0 -> "OneKey"
                    1 -> "运行日志"
                    2 -> "\u8d8a\u72f1\u5386\u53f2"
                    else -> "关于"
                },
                style = COUITheme.textStyles.headline2,
                maxLines = 1
            )
            Text(
                when (tab) {
                    0 -> "v" + BuildConfig.VERSION_NAME + " · " + (controller.profile?.displayName ?: "OnePlus")
                    1 -> controller.logs.size.toString() + " 条记录"
                    2 -> "\u8017\u65f6\u4e0e\u8bca\u65ad\u5f52\u6863"
                    else -> "OneKey v" + BuildConfig.VERSION_NAME
                },
                style = COUITheme.textStyles.body2,
                fontSize = 11.sp,
                color = c.muted,
                maxLines = 1
            )
        }
        when (tab) {
            1 -> {
                TopIconButton(onLogCopy) { Icon(COUIIcons.Copy, contentDescription = "复制日志") }
                TopIconButton(onLogExport) { Icon(COUIIcons.Download, contentDescription = "导出 TXT") }
                TopIconButton(onLogClear) { Icon(COUIIcons.Delete, contentDescription = "清空日志") }
            }
            2 -> {
                TopIconButton(onHistoryRefresh) { Icon(COUIIcons.Refresh, contentDescription = "刷新记录") }
                TopIconButton(onHistoryClear) { Icon(COUIIcons.Delete, contentDescription = "清空记录") }
            }
        }
        TopIconButton(onToggleAppearance) {
            Icon(
                COUIIcons.Theme,
                contentDescription = if (isDark) "切换为浅色外观" else "切换为深色外观",
                tint = c.primary
            )
        }
    }
}

/** 顶栏操作按钮：COUI 工具栏的 40dp 圆形热区 + 库的按压反馈。 */
@Composable
private fun TopIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .couiButtonFeedback(CouiTheme.isDark, source, true)
            .clickable(source, null, onClick = onClick)
            .padding(9.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
internal fun AppBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    onHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Android 14+ 用 COUI 原生底栏：渐变模糊、metaball 融合与选中辉光都由库负责。
    // 它放在 COUI Scaffold 的 bottomBar 槽里，由库为该槽提供与正文同源的采样源；
    // 自行塞进正文 Box 再手动传 backdrop 会因采样源错位画成黑色块。
    val backdrop = LocalAppBackdrop.current
    if (android.os.Build.VERSION.SDK_INT >= 34 && backdrop != null) {
        val renderer = remember { runCatching { CouiFloatingRenderer() }.getOrNull() }
        if (renderer != null) {
            CouiNavigationBar(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .onSizeChanged { onHeight(it.height) },
                backdrop = backdrop,
                renderer = renderer,
                items = remember {
                    listOf(
                        CouiNavigationItem("首页", COUIIcons.Home),
                        CouiNavigationItem("日志", COUIIcons.Notes),
                        CouiNavigationItem("历史", COUIIcons.Recent),
                        CouiNavigationItem("关于", COUIIcons.Info),
                    )
                },
                selectedIndex = selected,
                onSelection = onSelect,
            )
            return
        }
    }
    // Android 13 或采样源未就绪：退回实心栏，功能一致。
    if (backdrop == null) {
        Box(
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
                .height(70.dp)
                .clip(CouiShape(30.dp, 2f))
                .background(COUITheme.colorScheme.surfaceContainer)
                .onSizeChanged { onHeight(it.height) },
            contentAlignment = Alignment.Center,
        ) { AppBottomBarContent(selected = selected, onSelect = onSelect) }
        return
    }
    BlurBar(
        backdrop = backdrop,
        shape = CouiShape(30.dp, 2f),
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
internal fun AppBottomBarContent(
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
                    shape = CouiShape(20.dp, 2f),
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
