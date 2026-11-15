package com.pyb110.onekey.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.itos.coui.theme.CouiColorScheme
import com.itos.coui.theme.CouiPresets

private const val PREFS = "onekey"
private const val KEY_DYNAMIC = "dynamic_color"

/** 动态取色是否开启。默认关闭：关闭时用 COUI 原生预设，观感与 ColorOS 17 一致。 */
fun readDynamicColor(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DYNAMIC, false)

fun writeDynamicColor(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DYNAMIC, enabled).apply()
}

/** 本机是否支持动态取色（需要 Android 12 的系统调色板资源）。 */
fun dynamicColorSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 由系统壁纸调色板构造 COUI 配色。
 *
 * 只取 Android 12+ 的 system_accent1 / system_neutral1 系列，
 * 映射到 COUI 的 accent / surface / container / onSurface 等槽位；
 * 取不到时逐项回退到 COUI 预设，不会出现透明或错色。
 */
@Composable
fun rememberDynamicCouiScheme(dark: Boolean, enabled: Boolean): CouiColorScheme {
    val context = LocalContext.current
    val preset = if (dark) CouiPresets.Dark else CouiPresets.Light
    if (!enabled || !dynamicColorSupported()) return preset
    return remember(dark, enabled, context) { buildDynamicScheme(context, dark, preset) }
}

private fun buildDynamicScheme(context: Context, dark: Boolean, preset: CouiColorScheme): CouiColorScheme {
    val accent = systemColor(context, if (dark) "system_accent1_200" else "system_accent1_600") ?: preset.accent
    val accentOnSurface = systemColor(context, if (dark) "system_accent1_100" else "system_accent1_700")
        ?: preset.accentOnSurface
    val surface = systemColor(context, if (dark) "system_neutral1_900" else "system_neutral1_50") ?: preset.surface
    val container = systemColor(context, if (dark) "system_neutral1_800" else "system_neutral1_100") ?: preset.container
    val onSurface = systemColor(context, if (dark) "system_neutral1_50" else "system_neutral1_900") ?: preset.onSurface
    val onSurfaceVariant = systemColor(context, if (dark) "system_neutral2_200" else "system_neutral2_700")
        ?: preset.onSurfaceVariant
    val outline = systemColor(context, if (dark) "system_neutral2_700" else "system_neutral2_300")
        ?: preset.outlineVariant
    val onAccent = if (dark) lerp(accent, Color.Black, 0.72f) else Color.White
    return preset.copy(
        accent = accent,
        onAccent = onAccent,
        accentOnSurface = accentOnSurface,
        surface = surface,
        container = container,
        card = container,
        background = surface,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        outlineVariant = outline,
        menuSurface = container,
        checkboxAccent = accent,
        menuCheckboxAccent = accentOnSurface,
    )
}

private fun systemColor(context: Context, name: String): Color? = runCatching {
    val id = context.resources.getIdentifier(name, "color", "android")
    if (id == 0) null else Color(context.getColor(id))
}.getOrNull()
