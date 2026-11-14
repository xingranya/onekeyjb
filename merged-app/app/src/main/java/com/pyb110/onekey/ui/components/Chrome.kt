package com.pyb110.onekey.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.blur
import io.github.suqi8.coui.kmp.blur.drawBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Home
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.icon.extended.Recent
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Theme
import io.github.suqi8.coui.kmp.theme.COUITheme

internal object BarBlurDefaults {
    const val topRadius = 18f
    const val bottomRadius = 16f
    val topFade = 0.dp          // 顶栏不做淡出，滚动内容彻底被挡住
    val bottomFade = 16.dp
    const val topScrim = 0.82f  // 磨砂可透视，但下方内容已被模糊，不再出现清晰叠字
    const val bottomScrim = 0.88f
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
internal fun AppBottomBar(
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
