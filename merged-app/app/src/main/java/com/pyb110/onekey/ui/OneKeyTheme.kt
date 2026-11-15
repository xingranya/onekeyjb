package com.pyb110.onekey.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.itos.coui.theme.CouiTheme
import com.pyb110.onekey.ui.theme.COUITheme
import com.pyb110.onekey.ui.theme.*

/** 外观模式：浅色 / 深色 / 跟随系统。持久化在 onekey 偏好里。 */
enum class AppearanceMode(val key: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {
        fun fromKey(value: String?): AppearanceMode =
            entries.firstOrNull { it.key == value } ?: SYSTEM
    }
}

private const val PREFS = "onekey"
private const val KEY_APPEARANCE = "appearance_mode"

fun readAppearance(context: Context): AppearanceMode =
    AppearanceMode.fromKey(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_APPEARANCE, null))

fun writeAppearance(context: Context, mode: AppearanceMode) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_APPEARANCE, mode.key).apply()
}

@Composable
fun resolveDark(mode: AppearanceMode): Boolean = when (mode) {
    AppearanceMode.LIGHT -> false
    AppearanceMode.DARK -> true
    AppearanceMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * 界面语义色板。
 *
 * 全部取自 COUI 的 CouiColorScheme，深浅两套都由库给出，
 * 不再保留项目自研色值 —— 这样任何页面、抽屉、卡片都与系统原生观感一致。
 * 语义色（成功/警告/错误）保留固定值，因为 COUI 没有对应槽位。
 */
data class OneKeyColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceLow: Color,
    val ink: Color,
    val muted: Color,
    val outline: Color,
    val cardOutline: Color,
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val primaryInk: Color,
    val segmentBg: Color,
    val deviceSurface: Color,
    val deviceInk: Color,
    val deviceMuted: Color,
    val deviceOutline: Color,
    val devicePositive: Color,
    val deviceStatusBg: Color,
    val deviceStatusInk: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val error: Color,
    val errorSoft: Color,
    val disabledBg: Color,
    val disabledInk: Color,
)

/** 由当前 COUI 配色派生整套语义色。 */
@Composable
fun couiDerivedColors(dark: Boolean): OneKeyColors {
    val s = CouiTheme.colorScheme
    val card = s.card
    val accent = s.accent
    return OneKeyColors(
        isDark = dark,
        background = s.background,
        surface = s.surface,
        surfaceLow = lerp(s.surface, s.container, 0.6f),
        ink = s.onSurface,
        muted = s.onSurfaceVariant,
        outline = s.outlineVariant,
        cardOutline = s.outlineVariant,
        primary = accent,
        onPrimary = s.onAccent,
        primarySoft = lerp(card, accent, if (dark) 0.22f else 0.12f),
        primaryInk = s.accentOnSurface,
        segmentBg = s.container,
        deviceSurface = lerp(card, accent, if (dark) 0.16f else 0.09f),
        deviceInk = s.onSurface,
        deviceMuted = s.onSurfaceVariant,
        deviceOutline = lerp(s.outlineVariant, accent, 0.18f),
        devicePositive = if (dark) Color(0xFFB6E5CA) else Color(0xFF2B6B4E),
        deviceStatusBg = if (dark) Color(0xFF33483E) else Color(0xFFE3EEE7),
        deviceStatusInk = if (dark) Color(0xFFBDEBD0) else Color(0xFF295F45),
        success = if (dark) Color(0xFF9BD9B7) else Color(0xFF287150),
        successSoft = if (dark) Color(0xFF20372C) else Color(0xFFEAF4EE),
        warning = if (dark) Color(0xFFEDC783) else Color(0xFF835C19),
        warningSoft = if (dark) Color(0xFF3B3020) else Color(0xFFFBF2DF),
        error = if (dark) Color(0xFFF0A6B0) else Color(0xFFA3323E),
        errorSoft = if (dark) Color(0xFF40262E) else Color(0xFFFBECEF),
        disabledBg = lerp(s.container, s.onSurface, 0.08f),
        disabledInk = lerp(s.onSurfaceVariant, s.container, 0.3f),
    )
}

/** 预览用的固定两档，供外观抽屉展示。 */
val LightOneKeyColors = OneKeyColors(
    isDark = false, background = Color(0xFFF0F1F2), surface = Color(0xFFFFFFFF),
    surfaceLow = Color(0xFFF7F8F9), ink = Color(0xE6000000), muted = Color(0x8A000000),
    outline = Color(0x1F000000), cardOutline = Color(0x1F000000), primary = Color(0xFF0A59F7),
    onPrimary = Color(0xFFFFFFFF), primarySoft = Color(0xFFE6EEFF), primaryInk = Color(0xFF0A59F7),
    segmentBg = Color(0xFFFFFFFF), deviceSurface = Color(0xFFEFF4FF), deviceInk = Color(0xE6000000),
    deviceMuted = Color(0x8A000000), deviceOutline = Color(0x1F0A59F7), devicePositive = Color(0xFF2B6B4E),
    deviceStatusBg = Color(0xFFE3EEE7), deviceStatusInk = Color(0xFF295F45), success = Color(0xFF287150),
    successSoft = Color(0xFFEAF4EE), warning = Color(0xFF835C19), warningSoft = Color(0xFFFBF2DF),
    error = Color(0xFFA3323E), errorSoft = Color(0xFFFBECEF), disabledBg = Color(0xFFE7E8EC),
    disabledInk = Color(0xFF72737D),
)

val DarkOneKeyColors = OneKeyColors(
    isDark = true, background = Color(0xFF1E1E1E), surface = Color(0xFF1E1E1E),
    surfaceLow = Color(0xFF333333), ink = Color(0xFFFFFFFF), muted = Color(0xFFB3B3B3),
    outline = Color(0xFF333333), cardOutline = Color(0xFF333333), primary = Color(0xFF4D8DFF),
    onPrimary = Color(0xFF001A41), primarySoft = Color(0xFF24344F), primaryInk = Color(0xFF8FB8FF),
    segmentBg = Color(0xFF333333), deviceSurface = Color(0xFF243447), deviceInk = Color(0xFFFFFFFF),
    deviceMuted = Color(0xFFB3B3B3), deviceOutline = Color(0xFF35506E), devicePositive = Color(0xFFB6E5CA),
    deviceStatusBg = Color(0xFF33483E), deviceStatusInk = Color(0xFFBDEBD0), success = Color(0xFF9BD9B7),
    successSoft = Color(0xFF20372C), warning = Color(0xFFEDC783), warningSoft = Color(0xFF3B3020),
    error = Color(0xFFF0A6B0), errorSoft = Color(0xFF40262E), disabledBg = Color(0xFF35303C),
    disabledInk = Color(0xFFA59AAA),
)

val LocalOneKeyColors = staticCompositionLocalOf { LightOneKeyColors }

/** 页面里取语义色的唯一入口。 */
@Composable
fun oneKeyColors(): OneKeyColors = LocalOneKeyColors.current

/** 普通卡片底色：与 COUI 卡片同源。 */
@Composable
fun oneKeyCardColor(): Color = CouiTheme.colorScheme.card

/** 设备卡底色：卡片底色上叠一层主色。 */
@Composable
fun oneKeyDeviceColor(): Color {
    val c = oneKeyColors()
    return lerp(oneKeyCardColor(), c.primary, if (c.isDark) 0.12f else 0.07f)
}

/** 卡片描边：与 COUI 同一档。 */
@Composable
fun oneKeyCardOutline(): Color = CouiTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

/** 由 COUI 配色派生语义色，随动态取色与深浅主题一起变。 */
@Composable
fun ProvideOneKeyColors(dark: Boolean, content: @Composable () -> Unit) {
    val derived = couiDerivedColors(dark)
    CompositionLocalProvider(LocalOneKeyColors provides derived, content = content)
}

/** 页面级尺寸。 */
object OneKeyDimens {
    val pageMargin = 12
    val cardRadius = 20
    val buttonRadius = 14
    val smallRadius = 8
    val rowLeadingSize = 32
    val iconSize = 21
}
