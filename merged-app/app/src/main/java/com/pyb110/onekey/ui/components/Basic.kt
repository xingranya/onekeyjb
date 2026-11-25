package com.pyb110.onekey.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.R
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.theme.COUITheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import com.itos.coui.dialog.CouiDialogDefaults
import androidx.compose.ui.graphics.graphicsLayer
import com.itos.coui.effect.SpotlightPreset
import com.itos.coui.motion.rememberCouiPressMotion
import com.itos.coui.motion.rememberCouiTouchMotion
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.effect.rememberCouiSpotlight
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.theme.CouiTheme
import com.pyb110.onekey.ui.theme.*

data class DialogAction(
    val label: String,
    val icon: ImageVector,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
internal fun DialogActions(vararg actions: DialogAction) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            DialogActionButton(action, Modifier.weight(1f))
        }
    }
}

/** 单个弹窗动作按钮：COUI 胶囊、强调色与跟手辉光。 */
@Composable
private fun DialogActionButton(action: DialogAction, modifier: Modifier = Modifier) {
    val dark = CouiTheme.isDark
    val accent = CouiTheme.colorScheme.accent
    val (lightModifier, drawLight) = if (android.os.Build.VERSION.SDK_INT >= 33 && action.enabled) {
        rememberCouiSpotlight(
            interactive = true,
            preset = if (action.primary) SpotlightPreset.OpaqueMedium1 else SpotlightPreset.OpaqueMedium2,
            dark = dark,
        )
    } else Modifier to null
    val interaction = remember { MutableInteractionSource() }
    val shape = CouiCapsuleShape
    val motion = rememberCouiTouchMotion(action.enabled)
    val press = rememberCouiPressMotion()
    Box(
        modifier
            .height(CouiDialogDefaults.buttonHeight)
            .graphicsLayer {
                scaleX = motion.scaleX.value + press.scale.value - 1f
                scaleY = motion.scaleY.value + press.scale.value - 1f
                translationX = motion.translationX.value
                translationY = motion.translationY.value
            }
            .clip(shape)
            .background(if (action.primary) accent else CouiTheme.colorScheme.container)
            .couiButtonFeedback(dark, interaction, action.enabled)
            .clickable(interaction, null, action.enabled, onClick = action.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    action.icon, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = if (action.primary) CouiTheme.colorScheme.onAccent
                           else if (action.enabled) accent
                           else CouiTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    action.label, style = COUITheme.textStyles.body2,
                    color = if (action.primary) CouiTheme.colorScheme.onAccent
                            else if (action.enabled) accent
                            else CouiTheme.colorScheme.onSurfaceVariant
                )
            }
            if (drawLight != null) Box(Modifier.fillMaxSize().then(lightModifier).drawBehind(drawLight))
        }
    }
}

@Composable
internal fun Avatar(size: Int, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.avatar),
        contentDescription = "头像",
        modifier = modifier.size(size.dp).clip(CircleShape)
    )
}

@Composable
internal fun ButtonIconText(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text)
    }
}

/** 抽屉内的操作按钮：主色实心或浅底两种。 */
@Composable
internal fun PanelButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val dark = CouiTheme.isDark
    val accent = CouiTheme.colorScheme.accent
    val (lightModifier, drawLight) = if (android.os.Build.VERSION.SDK_INT >= 33 && enabled) {
        rememberCouiSpotlight(
            interactive = true,
            preset = if (primary) SpotlightPreset.OpaqueMedium1 else SpotlightPreset.OpaqueMedium2,
            dark = dark,
        )
    } else Modifier to null
    val interaction = remember { MutableInteractionSource() }
    val shape = CouiCapsuleShape
    // COUI 按钮的弹性：按压缩放 + 释放回弹，与库内 DialogButton 同一套实现。
    val motion = rememberCouiTouchMotion(enabled)
    val press = rememberCouiPressMotion()
    Box(
        modifier
            .height(CouiDialogDefaults.buttonHeight)
            .graphicsLayer {
                scaleX = motion.scaleX.value + press.scale.value - 1f
                scaleY = motion.scaleY.value + press.scale.value - 1f
                translationX = motion.translationX.value
                translationY = motion.translationY.value
            }
            .clip(shape)
            .background(
                when {
                    primary && enabled -> accent
                    // COUI 次级按钮的实色表面：浅色 0xFFEBEBEB / 深色 0xFF353535
                    enabled -> if (CouiTheme.isDark) Color(0xFF353535) else Color(0xFFEBEBEB)
                    else -> CouiTheme.colorScheme.container
                }
            )
            .couiButtonFeedback(dark, interaction, enabled)
            .clickable(interaction, null, enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text,
                    style = COUITheme.textStyles.body2,
                    color = when {
                        primary && enabled -> CouiTheme.colorScheme.onAccent
                        enabled -> CouiTheme.colorScheme.onSurface
                        else -> CouiTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (drawLight != null) Box(Modifier.fillMaxSize().then(lightModifier).drawBehind(drawLight))
        }
    }
}

/**
 * 弹窗内容统一外边距。
 *
 * 取值必须与 COUI Item（[BasicComponent]）的内部内边距一致：环境诊断的行列表直接铺在
 * 内容槽里，库自己给 16dp；其它弹窗若沿用 CouiDialogDefaults.contentPadding（24dp），
 * 左右边界就比环境诊断多缩进一圈，几个弹窗摆在一起明显不搭。
 */
internal val DialogInset = 16.dp

/** 弹窗正文段落：与环境诊断的行共享同一条左右边界。 */
@Composable
internal fun DialogParagraph(text: String, muted: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text,
        style = COUITheme.textStyles.body2,
        color = if (muted) oneKeyColors().muted else COUITheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().padding(horizontal = DialogInset)
    )
}

/**
 * 手册分节：扁平排版，不用卡片。
 *
 * 早先每节套一张 Card，卡片内 14dp 再叠上外层 24dp 内边距，缩进比环境诊断多出 38dp，
 * 摆在一起像两套 UI。现在与环境诊断同一套网格：只有 16dp 外边距，标题用行标题字号，
 * 条目用正文加圆点。
 */
@Composable
internal fun ManualSection(title: String, lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DialogInset, vertical = 8.dp)
    ) {
        Text(
            title,
            style = COUITheme.textStyles.body1,
            color = COUITheme.colorScheme.onSurface
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
