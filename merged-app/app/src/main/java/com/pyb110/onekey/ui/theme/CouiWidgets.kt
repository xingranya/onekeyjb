package com.pyb110.onekey.ui.theme

import android.os.Build
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itos.coui.backdrop.CouiBackdrop
import com.itos.coui.sheet.BottomSheet as CouiBottomSheet
import com.itos.coui.sheet.CouiSheetDefaults
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog as CouiDialog
import com.pyb110.onekey.ui.components.DialogAction
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.layout.Scaffold as CouiScaffold
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.theme.CouiTheme
import androidx.compose.ui.draw.drawBehind
import com.itos.coui.effect.SpotlightPreset
import com.itos.coui.effect.rememberCouiSpotlight
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.LocalCouiContentColor
import kotlinx.coroutines.delay
import android.view.WindowManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

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

/**
 * 弹窗窗口：直接委托 COUI 的 [CouiDialog]，外观与窗口行为全部由库负责。
 *
 * 两点是必须交给库的：
 * 1. 窗口压暗强度用 CouiDialogColors.dimAmount（浅色 0.2），自建 Compose Dialog
 *    会落到系统默认 0.6，整个背景变成死黑；
 * 2. visible 参数专门用来保留退场窗口。自建方案在 show 变 false 时直接移除组件，
 *    窗口销毁是异步的，紧接着再次 show 会被上一个尚未销毁的窗口挡住，
 *    这就是「点一次后第二次点不出来」。
 *
 * 因此这里始终留在组合中，只切 visible，绝不用 if (!show) return。
 */
@Composable
fun WindowDialog(
    title: String,
    show: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<DialogAction> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = CouiTheme.isDark
    val accent = CouiTheme.colorScheme.accent
    val backdrop = LocalDialogBackdrop.current

    fun DialogAction.toCoui(isPrimary: Boolean) = CouiDialogAction(
        text = label,
        onClick = onClick,
        enabled = enabled,
        style = if (isPrimary) CouiDialogActionStyle.Primary else CouiDialogActionStyle.Secondary,
        colors = if (isPrimary) CouiDialogDefaults.primaryActionColors(accent) else null,
    )

    // 主操作放 confirmAction；其余按顺序落到 dismiss / neutral，与 COUI 的两三键布局一致。
    val primary = actions.lastOrNull { it.primary } ?: actions.lastOrNull()
    val secondary = actions.filter { it !== primary }
    val material = if (CouiDialogDefaults.isMaterialSupported() && backdrop != null) {
        CouiDialogDefaults.material(dark)
    } else null

    // 不能把 onDismissRequest 塞进 LocalDismissState：调用方的 close() 内部会调 dismiss()，
    // 若它等于 onDismissRequest 就绕回自身，形成无限递归 StackOverflow 闪退。
    CompositionLocalProvider(LocalDismissState provides null) {
        CouiDialog(
            onDismissRequest = onDismissRequest,
            confirmAction = primary?.toCoui(true) ?: CouiDialogAction("关闭", onDismissRequest),
            dismissAction = secondary.getOrNull(0)?.toCoui(false),
            neutralAction = secondary.getOrNull(1)?.toCoui(false),
            dark = dark,
            title = title.takeIf { it.isNotBlank() },
            material = material,
            backdrop = if (material != null) backdrop else null,
            visible = show,
        ) { content() }
    }
}

/** 弹窗材质用的窗口采样源，由 AppRoot 提供；缺省时弹窗退回 COUI 实心表面色。 */
val LocalDialogBackdrop = androidx.compose.runtime.staticCompositionLocalOf<CouiBackdrop?> { null }

/**
 * 底部抽屉：COUI alpha17 起自带 [com.itos.coui.sheet.BottomSheet]，不再用 placement = Bottom 的弹窗冒充。
 *
 * 之前用 Bottom 弹窗时踩过一串坑：内容槽是 weight(1f) 会把短内容撑满整屏、
 * 弹窗自己的滚动槽与内层滚动冲突、遮罩铺满导致下滑被系统当成返回手势。
 * 原生抽屉把这些都做对了：拖拽手柄、吸附高度、内容区独立滚动、下滑关闭。
 *
 * 唯一保留的兼容点是 [visible] 需要是 MutableState —— 库自己驱动进出场动画，
 * 不能每次重组重新传一个常量，否则关闭动画会被打断。
 */
@Composable
fun WindowBottomSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String? = null,
    onDismissFinished: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = CouiTheme.isDark
    val colors = CouiDialogDefaults.themeColors(dark)
    // 库自己驱动进出场动画，visible 必须是稳定的 MutableState；
    // 每次重组传新常量会打断关闭动画，看起来像闪一下。
    val visible = remember { mutableStateOf(show) }
    LaunchedEffect(show) { visible.value = show }
    // 关闭流程分两步：先让库跑完收起动画（visible 由库置 false），
    // 动画结束后再回调 onDismissFinished 让外部复位状态。
    // 一步做完会把子树提前拆掉，动画被掐断，看起来像"啪一下消失"。
    CouiBottomSheet(
        visible = visible,
        onDismissRequest = { onDismissRequest() },
        dark = dark,
        colors = colors,
        showDragHandle = true,
        titleContent = title?.takeIf { it.isNotBlank() }?.let { t ->
            { Text(t, style = COUITheme.textStyles.headline2) }
        },
        backdrop = LocalDialogBackdrop.current,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CouiSheetDefaults.toolbarHorizontalPadding)
        ) { content() }
    }
    // 库把 visible 置回 false 即代表收起完成，此时才通知外部复位状态；
    // 提前拆子树会掐断收起动画。
    LaunchedEffect(visible.value) {
        if (!visible.value) onDismissFinished()
    }
}

/** 轻提示状态：COUI 无 Snackbar，这里只保留文本与自动消失。 */
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
            modifier = Modifier.clip(CouiCapsuleShape).background(CouiTheme.colorScheme.accent)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

@Composable
fun COUIPopupHost() = Unit

/**
 * 表面：COUI 的形状、配色与按压反馈。
 *
 * 形状统一用 CouiShape（COUI 的平滑圆角，与系统控件同一套曲线），
 * 不再用 Compose 的 RoundedCornerShape —— 后者圆角曲率与 COUI 不一致，放在一起会显脏。
 */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = CouiShape(14.dp, 2f),
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

/**
 * 主按钮：COUI 胶囊形状 + 主题强调色 + 跟手辉光。
 *
 * 辉光用库的 rememberCouiSpotlight：按下时白点在按钮表面跟手扩散，
 * 这是 ColorOS 17 原生按钮的观感，自绘无法还原。
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CouiCapsuleShape,
    content: @Composable RowScope.() -> Unit,
) {
    val dark = CouiTheme.isDark
    val (lightModifier, drawLight) = if (Build.VERSION.SDK_INT >= 33) {
        rememberCouiSpotlight(interactive = enabled, preset = SpotlightPreset.OpaqueMedium1, dark = dark)
    } else Modifier to null
    Surface(
        // 必须给高度上限：放进 fillMaxSize 的 Column 时，Surface 会吃掉全部剩余高度，
        // 胶囊形状被拉成巨大椭圆。COUI 按钮就是 44dp 单行。
        modifier = modifier.height(CouiDialogDefaults.buttonHeight), onClick = onClick, shape = shape,
        color = CouiTheme.colorScheme.accent, contentColor = CouiTheme.colorScheme.onAccent, enabled = enabled,
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) { content() }
            if (drawLight != null) Box(Modifier.fillMaxSize().then(lightModifier).drawBehind(drawLight))
        }
    }
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = Surface(
    modifier = modifier, onClick = onClick, shape = CouiCapsuleShape,
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
    Box(modifier.height(6.dp).clip(CouiShape(3.dp, 2f)).background(trackColor)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp)
            .clip(CouiShape(3.dp, 2f)).background(color))
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
        Button(onClick = onPrimaryButtonClick, modifier = Modifier.widthIn(min = 160.dp)) {
            Text(primaryButtonText, style = AppTextStyles.body1)
        }
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
