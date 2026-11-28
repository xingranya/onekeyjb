package com.pyb110.onekey.ui.screen

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.components.ButtonIconText
import com.pyb110.onekey.ui.components.ListCard
import com.pyb110.onekey.ui.components.ListRow
import com.pyb110.onekey.ui.components.PanelButton
import com.pyb110.onekey.ui.components.RunFlow
import com.pyb110.onekey.ui.oneKeyCardColor
import com.pyb110.onekey.ui.oneKeyCardOutline
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.oneKeyDeviceColor
import com.pyb110.onekey.ui.shortKernelVersion
import com.pyb110.onekey.ui.theme.COUIIcons
import com.pyb110.onekey.ui.theme.COUITheme
import com.pyb110.onekey.ui.theme.*
import com.itos.coui.shape.CouiShape

@Composable
internal fun HeroCard(controller: JailbreakController) {
    val c = oneKeyColors()
    val suReady = controller.suCompatReady
    val adapterValue = when {
        controller.hotSwitchAvailable -> "可用"
        controller.hotSwitchLoaderInstalled -> "待重启"
        else -> "待启用"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CouiShape(20.dp, 2f))
            .background(oneKeyDeviceColor())
            .border(1.dp, oneKeyCardOutline(), CouiShape(20.dp, 2f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    controller.profile?.displayName ?: "OnePlus",
                    style = COUITheme.textStyles.headline1,
                    color = c.deviceInk
                )
                Text(
                    (controller.profile?.model ?: "PYB110") + " · Android " + Build.VERSION.RELEASE,
                    style = COUITheme.textStyles.body2,
                    color = c.deviceMuted
                )
            }
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (controller.serviceReady) c.deviceStatusBg else c.warningSoft)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (controller.serviceReady) COUIIcons.Ok else COUIIcons.Info,
                    contentDescription = null,
                    tint = if (controller.serviceReady) c.deviceStatusInk else c.warning,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (controller.serviceReady) "服务在线" else "服务离线",
                    style = COUITheme.textStyles.body2,
                    color = if (controller.serviceReady) c.deviceStatusInk else c.warning,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = c.deviceOutline)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            DeviceCell(
                label = "服务通道",
                // 显示具体是哪条通道在跑：自研通道与 Stellar 的行为差别（是否需要权限授予）
                // 会直接影响用户下一步该点什么，只写「已连接」等于什么都没说。
                value = if (controller.serviceReady && controller.serviceGranted) controller.shellLabel()
                        else if (controller.serviceReady) "待授权" else "未连接",
                icon = COUIIcons.Link,
                positive = controller.serviceReady && controller.serviceGranted,
                modifier = Modifier.weight(1f)
            )
            DeviceCell(
                label = "SU 权限",
                value = if (suReady) "已就绪" else "未就绪",
                icon = COUIIcons.Lock,
                positive = suReady,
                modifier = Modifier.weight(1f)
            )
            DeviceCell(
                label = "热切换",
                value = adapterValue,
                icon = COUIIcons.Replace,
                positive = controller.hotSwitchAvailable,
                modifier = Modifier.weight(1f)
            )
            DeviceCell(
                label = "本次开机",
                value = if (controller.rootedThisBoot) "已运行" else "未运行",
                icon = COUIIcons.Refresh,
                positive = controller.rootedThisBoot,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(14.dp))
        RateBlock(controller)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("内核版本", style = COUITheme.textStyles.body2, fontSize = 11.sp, color = c.deviceMuted)
            Spacer(Modifier.width(12.dp))
            Spacer(Modifier.weight(1f))
            Text(
                shortKernelVersion(),
                style = COUITheme.textStyles.body2,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = c.deviceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 设备卡里的单列状态：图标 + 标签一行，值在下方。 */
@Composable
internal fun DeviceCell(
    label: String,
    value: String,
    icon: ImageVector,
    positive: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = oneKeyColors()
    Column(modifier.padding(end = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = c.deviceMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = COUITheme.textStyles.body2,
                fontSize = 11.sp,
                color = c.deviceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = COUITheme.textStyles.body2,
            color = if (positive) c.devicePositive else c.deviceInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 成功率区：一个百分比 + 三个物理量。
 *
 * 百分比来自 [com.pyb110.onekey.SuccessRate]，三个物理量各自带状态词，
 * 这样用户看到的不是"一个玄学数字"，而是"为什么是这个数字"。
 */
@Composable
internal fun RateBlock(controller: JailbreakController) {
    val c = oneKeyColors()
    val r = controller.successRate
    val stats = controller.historyStats
    val tone = when {
        !r.valid -> c.warning
        !r.displayable -> c.deviceMuted
        r.level <= 1 -> c.devicePositive
        r.level == 2 -> c.warning
        else -> c.error
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CouiShape(14.dp, 2f))
            .background(c.deviceStatusBg.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // 硬闸未过：不显示任何百分比，只显示原因。给数字等于给假信息。
        if (!r.valid) {
            Text(
                "当前不建议运行",
                style = COUITheme.textStyles.body2,
                fontSize = 13.sp,
                color = c.warning
            )
            Spacer(Modifier.height(4.dp))
            r.blockers.forEach { b ->
                Text(
                    "· " + b,
                    style = COUITheme.textStyles.body2,
                    fontSize = 11.sp,
                    color = c.deviceMuted
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                RateCell("CPU 温度", r.tempText, r.tempState, Modifier.weight(1f))
                RateCell("线程运行", r.busyText, r.busyState, Modifier.weight(1f))
                RateCell("电量", r.batteryText, r.batteryState, Modifier.weight(1f))
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (r.displayable) String.format(java.util.Locale.US, "%.0f", r.probability) else "--",
                        style = COUITheme.textStyles.headline1,
                        fontFamily = FontFamily.Monospace,
                        color = tone
                    )
                    if (r.displayable) {
                        Text(
                            "%",
                            style = COUITheme.textStyles.body2,
                            color = tone,
                            modifier = Modifier.padding(start = 1.dp, bottom = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (r.displayable) r.rangeText else "本次成功率",
                        style = COUITheme.textStyles.body2,
                        fontSize = 11.sp,
                        color = c.deviceMuted,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
                Text(
                    r.advice(),
                    style = COUITheme.textStyles.body2,
                    fontSize = 11.sp,
                    color = c.deviceMuted
                )
            }
            if (stats.hasData) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        String.format(java.util.Locale.US, "%.0f%%", stats.rate),
                        style = COUITheme.textStyles.body2,
                        fontFamily = FontFamily.Monospace,
                        color = c.deviceInk
                    )
                    Text(
                        "历史 " + stats.ok + "/" + stats.total,
                        style = COUITheme.textStyles.body2,
                        fontSize = 10.sp,
                        color = c.deviceMuted
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 依据行：用户看到的不是玄学数字，而是"凭什么这么算"。
        Text(
            r.bootText + " · 预计 " + r.attemptsPlanned + " 次尝试 · 单次约 " +
                String.format(java.util.Locale.US, "%.1f", r.msPerAttempt / 1000.0) + " 秒",
            style = COUITheme.textStyles.body2,
            fontSize = 10.sp,
            color = c.deviceMuted
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            // 内存占用（slab）只对 6.12 链有意义：6.18 引擎根本不读 slabinfo。
            if (r.slabRelevant) RateCell("内存占用", r.slabText, r.slabState, Modifier.weight(1f))
            RateCell("CPU 温度", r.tempText, r.tempState, Modifier.weight(1f))
            RateCell("线程运行", r.busyText, r.busyState, Modifier.weight(1f))
            RateCell("电量", r.batteryText, r.batteryState, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RateCell(label: String, value: String, state: String, modifier: Modifier = Modifier) {
    val c = oneKeyColors()
    Column(modifier.padding(end = 6.dp)) {
        Text(label, style = COUITheme.textStyles.body2, fontSize = 10.sp, color = c.deviceMuted, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = COUITheme.textStyles.body2,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = c.deviceInk,
                maxLines = 1
            )
            Spacer(Modifier.width(4.dp))
            Text(
                state,
                style = COUITheme.textStyles.body2,
                fontSize = 10.sp,
                color = c.deviceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

