package com.pyb110.onekey.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.BuildConfig
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.AppearanceMode
import com.pyb110.onekey.ui.DarkOneKeyColors
import com.pyb110.onekey.ui.LightOneKeyColors
import com.pyb110.onekey.ui.OneKeyColors
import com.pyb110.onekey.ui.appearanceLabel
import com.pyb110.onekey.ui.components.Avatar
import com.pyb110.onekey.ui.components.ListCard
import com.pyb110.onekey.ui.components.ListRow
import com.pyb110.onekey.ui.components.SectionTitle
import com.pyb110.onekey.ui.oneKeyCardColor
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.openUrl
import com.pyb110.onekey.ui.theme.COUIIcons
import com.pyb110.onekey.ui.theme.COUITheme
import com.pyb110.onekey.ui.theme.*
import com.itos.coui.shape.CouiShape

@Composable
internal fun AboutScreen(
    controller: JailbreakController,
    appearance: AppearanceMode,
    dynamicColor: Boolean,
    onChangeAppearance: (AppearanceMode) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    haptic: (CouiHapticEffect) -> Unit,
    onOpenLog: () -> Unit,
    onNotice: () -> Unit,
    onManual: () -> Unit,
    onThirdParty: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    var showAppearanceSheet by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Avatar(72)
                Spacer(Modifier.height(10.dp))
                Text("OneKey", style = COUITheme.textStyles.headline1)
                Text(
                    "v" + BuildConfig.VERSION_NAME + " · " + BuildConfig.VERSION_CODE,
                    style = COUITheme.textStyles.body2,
                    color = oneKeyColors().muted
                )
            }
        }
        item { SectionTitle("设备") }
        item {
            ListCard(
                rows = listOf(
                    ListRow(
                        title = "设备",
                        summary = (controller.profile?.displayName ?: "OnePlus") + " · " +
                            (controller.profile?.model ?: "UNKNOWN"),
                        icon = COUIIcons.Info
                    ),
                    ListRow(
                        title = "内核",
                        summary = System.getProperty("os.version") ?: "未知",
                        icon = COUIIcons.Tasks
                    ),
                    ListRow(
                        title = "模块",
                        summary = if (controller.hotSwitchLoaderInstalled) "通用多管理器（已安装加载器）" else "管理器自带模块",
                        icon = COUIIcons.Replace
                    )
                )
            )
        }
        item { SectionTitle("个性化") }
        item {
            ListCard(
                rows = listOf(
                    ListRow(
                        title = "外观",
                        summary = appearanceLabel(appearance),
                        icon = COUIIcons.Theme,
                        arrow = true,
                        onClick = { haptic(CouiHapticEffect.Switch); showAppearanceSheet = true }
                    ),
                    ListRow(
                        title = "自动匹配云端组件",
                        summary = if (controller.cloudAuto) "按设备与管理器自动获取模块" else "仅使用本机固定组件",
                        icon = COUIIcons.Update,
                        end = {
                            Switch(
                                checked = controller.cloudAuto,
                                onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateCloudAuto(it) },
                                enabled = !controller.running
                            )
                        }
                    )
                )
            )
        }
        item { SectionTitle("支持与说明") }
        item {
            ListCard(
                rows = listOf(
                    ListRow(
                        title = "检查更新",
                        summary = if (controller.checkingUpdate) "正在检查…" else controller.updateStatus,
                        icon = COUIIcons.Refresh,
                        arrow = true,
                        onClick = { haptic(CouiHapticEffect.Switch); controller.checkUpdates() }
                    ),
                    ListRow(
                        title = "用户手册",
                        summary = "首次使用、拓展功能说明与异常处理",
                        icon = COUIIcons.Notes,
                        arrow = true,
                        onClick = onManual
                    ),
                    ListRow(
                        title = "免费声明与赞赏码",
                        summary = "群内免费 · 禁止倒卖",
                        icon = COUIIcons.Help,
                        arrow = true,
                        onClick = onNotice
                    ),
                    ListRow(
                        title = "COUI 组件库",
                        summary = "github.com/ItosEO/COUI-Kit · MPL-2.0",
                        icon = COUIIcons.Link,
                        arrow = true,
                        onClick = { openUrl(context, "https://github.com/ItosEO/COUI-Kit") }
                    ),
                    ListRow(
                        title = "Stellar",
                        summary = "github.com/roro2239/Stellar",
                        icon = COUIIcons.Link,
                        arrow = true,
                        onClick = { openUrl(context, "https://github.com/roro2239/Stellar") }
                    ),
                    ListRow(
                        title = "第三方组件说明",
                        summary = "感谢以下开源项目与实现",
                        icon = COUIIcons.Report,
                        arrow = true,
                        onClick = onThirdParty
                    )
                )
            )
        }
        item {
            Text(
                "GPL-3.0 · 作者 星苒鸭 · github.com/xingranya",
                style = COUITheme.textStyles.body2,
                color = oneKeyColors().muted,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp),
                textAlign = TextAlign.Center
            )
        }
    }

    var appearanceVisible by remember { mutableStateOf(false) }
    LaunchedEffect(showAppearanceSheet) { if (showAppearanceSheet) appearanceVisible = true }
    if (showAppearanceSheet) {
        WindowBottomSheet(
            show = appearanceVisible,
            title = "外观",
            onDismissRequest = { appearanceVisible = false },
            onDismissFinished = { showAppearanceSheet = false; appearanceVisible = false }
        ) {
            AppearanceSheetContent(
                current = appearance,
                onPick = { mode -> haptic(CouiHapticEffect.Switch); onChangeAppearance(mode) },
                dynamicColor = dynamicColor,
                onToggleDynamicColor = { haptic(CouiHapticEffect.Switch); onToggleDynamicColor(it) },
            )
        }
    }
}

@Composable
internal fun AppearancePreview(label: String, palette: OneKeyColors, modifier: Modifier = Modifier) {
    val c = oneKeyColors()
    Column(
        modifier = modifier
            .clip(CouiShape(20.dp, 2f))
            .background(palette.background)
            .border(if (c.isDark == palette.isDark) 2.dp else 1.dp, if (c.isDark == palette.isDark) c.primary else c.outline, CouiShape(20.dp, 2f))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(CouiShape(8.dp, 2f))
                .background(palette.deviceSurface)
                .border(1.dp, palette.deviceOutline, CouiShape(8.dp, 2f))
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(12.dp).clip(CouiShape(4.dp, 2f)).background(palette.primary))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(20.dp).clip(CouiShape(4.dp, 2f)).background(palette.surface))
        Spacer(Modifier.height(8.dp))
        Text(label, style = COUITheme.textStyles.body2, color = palette.ink)
    }
}

/** 外观抽屉：三种模式 + 两档配色预览。 */
@Composable
internal fun AppearanceSheetContent(
    current: AppearanceMode,
    onPick: (AppearanceMode) -> Unit,
    dynamicColor: Boolean = false,
    onToggleDynamicColor: (Boolean) -> Unit = {},
) {
    val c = oneKeyColors()
    Column(Modifier.fillMaxWidth().height(500.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppearancePreview("浅色", LightOneKeyColors, Modifier.weight(1f))
            AppearancePreview("深色", DarkOneKeyColors, Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().clip(CouiShape(20.dp, 2f)).background(oneKeyCardColor())) {
            AppearanceMode.entries.forEachIndexed { index, mode ->
                val selected = current == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(mode) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (mode) {
                            AppearanceMode.LIGHT -> COUIIcons.Theme
                            AppearanceMode.DARK -> COUIIcons.Hide
                            AppearanceMode.SYSTEM -> COUIIcons.Settings
                        },
                        contentDescription = null,
                        tint = c.primary,
                        modifier = Modifier.size(21.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (mode) {
                                AppearanceMode.LIGHT -> "浅色"
                                AppearanceMode.DARK -> "深色"
                                AppearanceMode.SYSTEM -> "跟随系统"
                            },
                            style = COUITheme.textStyles.body1,
                            color = if (selected) c.primaryInk else c.ink
                        )
                        Text(
                            when (mode) {
                                AppearanceMode.LIGHT -> "灰白界面，搭配深玫瑰色"
                                AppearanceMode.DARK -> "炭黑界面，搭配雾玫瑰色"
                                AppearanceMode.SYSTEM -> "随设备的深浅外观自动变化"
                            },
                            style = COUITheme.textStyles.body2,
                            color = c.muted
                        )
                    }
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (selected) c.primary else Color.Transparent)
                            .then(if (selected) Modifier else Modifier.border(1.5.dp, c.muted, CircleShape)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(COUIIcons.Ok, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(15.dp))
                        }
                    }
                }
                if (index != AppearanceMode.entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth().padding(start = 49.dp, end = 16.dp),
                        color = c.outline
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 动态取色：跟随壁纸调色板。关闭时使用系统原生预设配色。
        Column(Modifier.fillMaxWidth().clip(CouiShape(20.dp, 2f)).background(oneKeyCardColor())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(COUIIcons.Theme, contentDescription = null, tint = c.primary, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("动态取色", style = COUITheme.textStyles.body1)
                    Text(
                        if (dynamicColorSupported()) "跟随壁纸配色；关闭时使用系统原生配色"
                        else "当前系统版本不支持，需要 Android 12 及以上",
                        style = COUITheme.textStyles.body2,
                        color = c.muted
                    )
                }
                Switch(
                    checked = dynamicColor,
                    onCheckedChange = { onToggleDynamicColor(it) },
                    enabled = dynamicColorSupported(),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "外观应用于全部页面、抽屉与提示，选择会被保留。",
            style = COUITheme.textStyles.body2,
            color = c.muted
        )
    }
}
