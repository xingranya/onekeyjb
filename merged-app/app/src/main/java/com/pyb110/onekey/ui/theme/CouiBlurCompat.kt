package com.pyb110.onekey.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import com.itos.coui.backdrop.CouiBackdrop
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop

/**
 * 模糊层兼容：旧 coui-blur 的 LayerBackdrop 概念映射到 COUI 的 CouiBackdrop。
 * 顶底栏的真实渐变模糊由 COUI 的 TopAppBar / NavigationBar 内部完成，
 * 这里只保留调用点需要的类型与修饰符。
 */
typealias LayerBackdrop = CouiBackdrop

@Composable
fun rememberLayerBackdrop(): LayerBackdrop = rememberCouiBackdrop()

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier = this.couiBackdrop(backdrop)

/** 旧 API 的模糊修饰符：COUI 组件自带材质，这里不再叠加自绘模糊。 */
fun Modifier.blur(
    radius: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    shape: Any? = null,
    effects: Any? = null,
): Modifier = this

/** 旧模糊作用域：COUI 组件自带材质，这里只保留调用形状。 */
class CouiBackdropEffectScope {
    fun blur(radius: Float) = Unit
    fun blur(radius: androidx.compose.ui.unit.Dp) = Unit
}

fun Modifier.drawBackdrop(
    backdrop: LayerBackdrop,
    shape: Any? = null,
    effects: (CouiBackdropEffectScope.() -> Unit)? = null,
    onDraw: (Any.() -> Unit)? = null,
): Modifier = this.couiBackdrop(backdrop)
