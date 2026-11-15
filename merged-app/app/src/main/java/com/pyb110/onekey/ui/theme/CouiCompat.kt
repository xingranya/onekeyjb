package com.pyb110.onekey.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.input.CouiHapticFeedback
import com.itos.coui.input.performCouiHapticFeedback
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Item
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.rememberCouiTextStyle
import com.itos.coui.theme.CouiTheme

/**
 * COUI 兼容层：把项目里既有的调用名映射到 io.github.itoseo:coui 的真实组件与主题。
 *
 * 存在的意义是让 29 个界面文件的调用点保持不变，避免大规模改写引入错配；
 * 所有实际渲染都交给 COUI，本文件不含自绘视觉。
 */
object COUITheme {
    val colorScheme: AppColorScheme
        @Composable get() = AppColorScheme

    val textStyles: AppTextStyles
        @Composable get() = AppTextStyles
}

/** 语义色 → COUI 配色。 */
object AppColorScheme {
    val primary: Color @Composable get() = CouiTheme.colorScheme.accent
    val onPrimary: Color @Composable get() = CouiTheme.colorScheme.onAccent
    val surfaceContainer: Color @Composable get() = CouiTheme.colorScheme.container
    val onSurfaceContainerVariant: Color @Composable get() = CouiTheme.colorScheme.onSurfaceVariant
    val onSurface: Color @Composable get() = CouiTheme.colorScheme.onSurface
    val outline: Color @Composable get() = CouiTheme.colorScheme.outlineVariant
    val background: Color @Composable get() = CouiTheme.colorScheme.background
    val accent: Color @Composable get() = CouiTheme.colorScheme.accent
}

/** 语义字号 → COUI 排版。 */
object AppTextStyles {
    val body1: TextStyle @Composable get() = rememberCouiTextStyle(CouiTextKind.ItemTitle)
    val body2: TextStyle @Composable get() = rememberCouiTextStyle(CouiTextKind.ItemSummary)
    val headline1: TextStyle @Composable get() = rememberCouiTextStyle(CouiTextKind.HomeTitle)
    val headline2: TextStyle @Composable get() = rememberCouiTextStyle(CouiTextKind.TopBar)
    val subtitle: TextStyle @Composable get() = rememberCouiTextStyle(CouiTextKind.GroupTitle)
}

/** 与既有调用签名一致的文本入口，样式取自 COUI 排版。 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = AppTextStyles.body2,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(
            color = color.takeOrElse { CouiTheme.colorScheme.onSurface },
            textAlign = textAlign ?: style.textAlign,
            fontSize = if (fontSize == androidx.compose.ui.unit.TextUnit.Unspecified) style.fontSize else fontSize,
            fontFamily = fontFamily ?: style.fontFamily,
            lineHeight = if (lineHeight == androidx.compose.ui.unit.TextUnit.Unspecified) style.lineHeight else lineHeight,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** 图标入口：COUI 图标集为 ImageVector，这里只做 tint。 */
@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    Image(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = tint.takeIf { it != Color.Unspecified }?.let { ColorFilter.tint(it) },
    )
}

/**
 * 卡片分组：用 COUI 的卡片形状与卡片底色自装配。
 *
 * 不用 [Group] 是因为它自带 16dp 横向外边距，而页面本身已经给了 12dp，
 * 叠加会变成 28dp、整页变窄。这里只取它的形状与配色，边距交给页面。
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                title,
                style = AppTextStyles.body2,
                color = CouiTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(CouiPageDefaults.shape)
                .background(CouiTheme.colorScheme.card),
            content = content,
        )
    }
}

/** 列表项：直接用 COUI Item，按压反馈由库内置。 */
@Composable
fun BasicComponent(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    endAction: (@Composable () -> Unit)? = null,
    endActions: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Item(
        modifier = modifier,
        icon = startAction,
        trailing = {
            if (endActions != null) Row(verticalAlignment = Alignment.CenterVertically) { endActions() }
            else endAction?.invoke()
        },
        onClick = onClick,
        enabled = enabled,
        summary = summary?.let { { Text(it, style = AppTextStyles.body2, color = AppColorScheme.onSurfaceContainerVariant) } },
        title = { Text(title, style = AppTextStyles.body1) },
    )
}

/** 触感：沿用既有枚举名，底层走 COUI 的 View 触觉门控。 */
enum class CouiHapticEffect { Switch, Click, LongPress }

@Composable
fun rememberCouiHaptic(): (CouiHapticEffect) -> Unit {
    val view = LocalView.current
    return { effect ->
        view.performCouiHapticFeedback(CouiHapticFeedback.Switch)
    }
}
