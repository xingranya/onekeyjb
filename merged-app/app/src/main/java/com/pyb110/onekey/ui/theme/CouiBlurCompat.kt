package com.pyb110.onekey.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.itos.coui.backdrop.CouiBackdrop
import com.itos.coui.backdrop.CouiBackdropEffectScope
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.drawCouiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop

/**
 * 模糊层：直接委托 COUI 的真实背景采样与离屏模糊。
 * 旧 coui-blur 与 COUI 这套 API 同源、签名一致，所以调用点无需改动。
 */
typealias LayerBackdrop = CouiBackdrop

@Composable
fun rememberLayerBackdrop(): LayerBackdrop = rememberCouiBackdrop()

/** 标记采样层：内容会被录进 backdrop 供栏位采样。 */
fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier = this.couiBackdrop(backdrop)

/** 真实模糊：shape 裁剪采样面，effects 内 blur(radius) 走 COUI 的 RenderEffect。 */
@Composable
fun Modifier.drawBackdrop(
    backdrop: LayerBackdrop,
    shape: () -> Shape,
    effects: CouiBackdropEffectScope.() -> Unit,
): Modifier = this.drawCouiBackdrop(backdrop, shape, effects)
