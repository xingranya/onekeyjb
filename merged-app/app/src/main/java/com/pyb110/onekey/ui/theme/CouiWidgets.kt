package com.pyb110.onekey.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.layout.Scaffold as CouiScaffold
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiContentColor
import kotlinx.coroutines.delay

/** 开关：直接用 COUI Switch，自带拖动与触感。 */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = CouiSwitch(modifier = modifier, checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)

/** 页面骨架：直接用 COUI Scaffold，内部自动建立 backdrop。 */
@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    containerColor: Color = CouiTheme.colorScheme.background,
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    content: @Composable (PaddingValues) -> Unit,
) = CouiScaffold(
    modifier = modifier, topBar = topBar, bottomBar = bottomBar,
    containerColor = containerColor, windowInsets = contentWindowInsets, content = content,
)

/** 弹窗窗口：用 COUI 圆角/配色/内边距，内容交给调用方（库 Dialog 强制按钮，故不用）。 */
@Composable
fun WindowDialog(
    title: String,
    show: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!show) return
    val colors = CouiDialogDefaults.themeColors(CouiTheme.isDark)
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDismissState provides onDismissRequest) {
            Column(
                Modifier.padding(horizontal = CouiDialogDefaults.horizontalMargin).fillMaxWidth()
                    .clip(CouiDialogDefaults.shape).background(colors.surface).padding(vertical = 8.dp),
            ) {
                if (title.isNotBlank()) {
                    Text(
                        title, style = AppTextStyles.headline2, color = colors.title,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = CouiDialogDefaults.contentPadding, vertical = 12.dp),
                    )
                }
                content()
            }
        }
    }
}

/** 底部抽屉：COUI 无对应组件，用其圆角与配色装配。 */
@Composable
fun WindowBottomSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String? = null,
    onDismissFinished: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!show) return
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = onDismissRequest))
            Column(
                Modifier.padding(horizontal = 8.dp).padding(bottom = 12.dp).fillMaxWidth()
                    .clip(CouiDialogDefaults.shape).background(CouiTheme.colorScheme.surface).padding(vertical = 12.dp),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp)
                        .heightIn(min = 4.dp).fillMaxWidth(0.12f)
                        .clip(RoundedCornerShape(2.dp)).background(CouiTheme.colorScheme.outlineVariant)
                )
                if (!title.isNullOrBlank()) {
                    Text(title, style = AppTextStyles.headline2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp))
                }
                content()
            }
        }
    }
}

class SnackbarHostState {
    var message by mutableStateOf<String?>(null)
        private set

    suspend fun showSnackbar(text: String) {
        message = text
        delay(2200)
        if (message == text) message = null
    }
}

@Composable
fun SnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val text = state.message ?: return
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text, style = AppTextStyles.body2, color = CouiTheme.colorScheme.onAccent,
            modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(CouiTheme.colorScheme.accent)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

@Composable
fun COUIPopupHost() = Unit

/** 静态或可点击表面：COUI 配色 + 库按压反馈。 */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    color: Color = CouiTheme.colorScheme.container,
    contentColor: Color = CouiTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val base = modifier.clip(shape).background(color)
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
    Box(
        if (onClick != null) base.couiButtonFeedback(CouiTheme.isDark, interaction, enabled)
            .clickable(interaction, null, enabled, onClick = onClick) else base,
        contentAlignment = Alignment.Center,
    ) { CompositionLocalProvider(LocalCouiContentColor provides contentColor) { content() } }
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) = Surface(
    modifier = modifier.size(40.dp), onClick = onClick, shape = CircleShape,
    color = Color.Transparent, enabled = enabled, content = content,
)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable RowScope.() -> Unit,
) = Surface(
    modifier = modifier, onClick = onClick, shape = shape,
    color = CouiTheme.colorScheme.accent, contentColor = CouiTheme.colorScheme.onAccent, enabled = enabled,
) {
    Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = Surface(
    modifier = modifier, onClick = onClick, shape = RoundedCornerShape(22.dp),
    color = Color.Transparent, contentColor = CouiTheme.colorScheme.accent, enabled = enabled,
) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { content() }
}

/** 文本按钮的便捷重载：直接给文案。 */
@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
    Text(text, style = AppTextStyles.body1)
}

@Composable
fun LinearProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = CouiTheme.colorScheme.accent,
    trackColor: Color = CouiTheme.colorScheme.outlineVariant,
) {
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(trackColor)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp)
            .clip(RoundedCornerShape(3.dp)).background(color))
    }
}

@Composable
fun FullPageStatement(
    title: String,
    content: String,
    primaryButtonText: String,
    onPrimaryButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(title, style = AppTextStyles.headline2)
        Box(Modifier.height(10.dp))
        Text(content, style = AppTextStyles.body2, color = CouiTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.height(20.dp))
        Button(onClick = onPrimaryButtonClick) { Text(primaryButtonText, style = AppTextStyles.body1) }
    }
}

object DividerDefaults {
    val CardInset: Dp = 60.dp
}

@Composable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = CouiTheme.colorScheme.outlineVariant,
) = Box(modifier.fillMaxWidth().height(thickness).background(color))

/** 弹窗关闭回调，兼容旧调用点。 */
val LocalDismissState = compositionLocalOf<(() -> Unit)?> { null }
