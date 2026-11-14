package com.pyb110.onekey.ui.screen

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.icon.extended.Link
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Replace
import io.github.suqi8.coui.kmp.icon.extended.Report
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.icon.extended.Tasks
import io.github.suqi8.coui.kmp.icon.extended.Tune
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect

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
            .clip(RoundedCornerShape(20.dp))
            .background(oneKeyDeviceColor())
            .border(1.dp, oneKeyCardOutline(), RoundedCornerShape(20.dp))
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
                value = if (controller.serviceReady && controller.serviceGranted) "已授权"
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

