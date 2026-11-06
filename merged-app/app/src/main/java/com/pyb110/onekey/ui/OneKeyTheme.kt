package com.pyb110.onekey.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.suqi8.coui.kmp.theme.COUITheme

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
 * 取值来自已确认的网页原型（docs 中的 onekey-ui-prototype），浅色与深色各自一套；
 * 页面里所有自绘表面都从这里取色，不再各自硬编码。
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

val LightOneKeyColors = OneKeyColors(
    isDark = false,
    background = Color(0xFFF3F2F5),
    surface = Color(0xFFFFFFFF),
    surfaceLow = Color(0xFFF7F5F8),
    ink = Color(0xFF27232B),
    muted = Color(0xFF6B626E),
    outline = Color(0xFFE9E4EB),
    cardOutline = Color(0xFFEAE5EC),
    primary = Color(0xFF9B4A5A),
    onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0xFFF3E4E9),
    primaryInk = Color(0xFF793646),
    segmentBg = Color(0xFFE9E5EC),
    deviceSurface = Color(0xFFF8EEF2),
    deviceInk = Color(0xFF422C36),
    deviceMuted = Color(0xFF76616C),
    deviceOutline = Color(0xFFE6D4DC),
    devicePositive = Color(0xFF2B6B4E),
    deviceStatusBg = Color(0xFFE3EEE7),
    deviceStatusInk = Color(0xFF295F45),
    success = Color(0xFF287150),
    successSoft = Color(0xFFEAF4EE),
    warning = Color(0xFF835C19),
    warningSoft = Color(0xFFFBF2DF),
    error = Color(0xFFA3323E),
    errorSoft = Color(0xFFFBECEF),
    disabledBg = Color(0xFFE7E8EC),
    disabledInk = Color(0xFF72737D),
)

val DarkOneKeyColors = OneKeyColors(
    isDark = true,
    background = Color(0xFF121115),
    surface = Color(0xFF1D1B21),
    surfaceLow = Color(0xFF26232B),
    ink = Color(0xFFF1EAF2),
    muted = Color(0xFFB4A9BA),
    outline = Color(0xFF36303D),
    cardOutline = Color(0xFF2E2934),
    primary = Color(0xFFE4ADBD),
    onPrimary = Color(0xFF3B1826),
    primarySoft = Color(0xFF382631),
    primaryInk = Color(0xFFF1C0D0),
    segmentBg = Color(0xFF242028),
    deviceSurface = Color(0xFF28232E),
    deviceInk = Color(0xFFFFF3FB),
    deviceMuted = Color(0xFFC9BACF),
    deviceOutline = Color(0xFF493C52),
    devicePositive = Color(0xFFB6E5CA),
    deviceStatusBg = Color(0xFF33483E),
    deviceStatusInk = Color(0xFFBDEBD0),
    success = Color(0xFF9BD9B7),
    successSoft = Color(0xFF20372C),
    warning = Color(0xFFEDC783),
    warningSoft = Color(0xFF3B3020),
    error = Color(0xFFF0A6B0),
    errorSoft = Color(0xFF40262E),
    disabledBg = Color(0xFF35303C),
    disabledInk = Color(0xFFA59AAA),
)

val LocalOneKeyColors = staticCompositionLocalOf { LightOneKeyColors }

/** 页面里取语义色的唯一入口。 */
@Composable
fun oneKeyColors(): OneKeyColors = LocalOneKeyColors.current

/**
 * 普通卡片底色。
 *
 * 直接沿用 COUI 的 surfaceContainer —— 这样自绘卡片与 ListCard / Card 里由库渲染的卡片
 * 永远是同一个颜色，深浅主题下都不会出现「有的卡片偏灰黑」。
 */
@Composable
fun oneKeyCardColor(): Color = COUITheme.colorScheme.surfaceContainer

/** 设备卡底色：在卡片底色上叠一层主色，保持与其它卡片同源，只是偏玫瑰。 */
@Composable
fun oneKeyDeviceColor(): Color {
    val c = oneKeyColors()
    return lerp(oneKeyCardColor(), c.primary, if (c.isDark) 0.12f else 0.07f)
}

/** 卡片描边：与 COUI 卡片同一档。 */
@Composable
fun oneKeyCardOutline(): Color = COUITheme.colorScheme.outline.copy(alpha = 0.5f)

@Composable
fun ProvideOneKeyColors(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalOneKeyColors provides if (dark) DarkOneKeyColors else LightOneKeyColors,
        content = content
    )
}

/** 页面级尺寸，与原型保持同一档。 */
object OneKeyDimens {
    val pageMargin = 12
    val cardRadius = 20
    val buttonRadius = 14
    val smallRadius = 8
    val rowLeadingSize = 32
    val iconSize = 21
}
