package com.pyb110.onekey.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.components.PanelButton
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect

@Composable
internal fun HotSwitchPanel(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    selectedTarget: String,
    onPickTarget: () -> Unit,
) {
    val c = oneKeyColors()
    val nodeReady = controller.hotSwitchAvailable
    val loaderInstalled = controller.hotSwitchLoaderInstalled
    val canSwitch = nodeReady && controller.suCompatReady && !controller.running &&
        !controller.preparing && selectedTarget.isNotBlank() && !controller.isSwitchingManager
    val targetLabel = controller.managers.firstOrNull { it.kind == selectedTarget }?.label ?: "未安装"

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = if (nodeReady) c.primary else c.surfaceLow
            ) {
                Text(
                    if (nodeReady) "节点就绪" else "未就绪",
                    style = COUITheme.textStyles.body2,
                    color = if (nodeReady) c.onPrimary else c.muted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "当前 " + controller.activeKernelManager.ifBlank { "未启用" },
                style = COUITheme.textStyles.body1,
                color = c.primary
            )
        }
        Text(
            text = when {
                nodeReady -> "切换只改内核 profile，不重启、不重新越狱。"
                loaderInstalled -> "通用加载器已安装；重启手机后重新运行一次越狱即可启用。"
                !controller.serviceReady -> "服务离线，启动 Stellar 并授权后可检测热切换节点。"
                else -> "热切换需要通用模块。安装后重启手机并重新运行一次越狱即可启用。"
            },
            style = COUITheme.textStyles.body2,
            color = c.muted,
            modifier = Modifier.padding(top = 8.dp)
        )

        // 目标管理器：只在可以切换时才需要，未就绪时不占地方。
        if (nodeReady) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceLow)
                    .clickable(enabled = !controller.running) { onPickTarget() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("目标管理器", style = COUITheme.textStyles.body1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(targetLabel, style = COUITheme.textStyles.body1, color = c.primary)
                    Spacer(Modifier.width(6.dp))
                    Icon(COUIIcons.ChevronForward, contentDescription = null, modifier = Modifier.size(16.dp), tint = c.muted)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 按钮按当前阶段收敛：未装只给「安装」，已装未生效只给「重启」，就绪后才出现「切换」。
        when {
            !loaderInstalled -> {
                PanelButton(
                    text = "安装通用加载器",
                    primary = true,
                    enabled = !controller.loaderBusy && controller.suCompatReady,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    haptic(CouiHapticEffect.Switch)
                    controller.installHotSwitchLoader { _, msg -> controller.addLog("加载器：" + msg) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "安装会备份并替换管理器目录下的 libksud.so，可随时还原。",
                    style = COUITheme.textStyles.body2,
                    color = c.muted
                )
            }
            !nodeReady -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelButton(
                        text = "重启手机",
                        primary = true,
                        enabled = !controller.loaderBusy && controller.suCompatReady,
                        modifier = Modifier.weight(1f)
                    ) { haptic(CouiHapticEffect.Switch); controller.rebootDevice() }
                    PanelButton(
                        text = "检测",
                        modifier = Modifier.weight(1f)
                    ) { haptic(CouiHapticEffect.Switch); controller.refreshState() }
                }
                Spacer(Modifier.height(8.dp))
                PanelButton(
                    text = "还原加载器",
                    enabled = !controller.loaderBusy && controller.suCompatReady,
                    modifier = Modifier.fillMaxWidth()
                ) { haptic(CouiHapticEffect.Switch); controller.restoreHotSwitchLoader { _, _ -> } }
            }
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelButton(
                        text = if (controller.isSwitchingManager) "切换中…" else "切换",
                        primary = true,
                        enabled = canSwitch,
                        modifier = Modifier.weight(2f)
                    ) { haptic(CouiHapticEffect.Switch); controller.executeHotSwitch(selectedTarget) { _, _ -> } }
                    PanelButton(
                        text = "检测",
                        modifier = Modifier.weight(1f)
                    ) { haptic(CouiHapticEffect.Switch); controller.refreshState() }
                }
                Spacer(Modifier.height(8.dp))
                PanelButton(
                    text = "还原加载器",
                    enabled = !controller.loaderBusy && controller.suCompatReady,
                    modifier = Modifier.fillMaxWidth()
                ) { haptic(CouiHapticEffect.Switch); controller.restoreHotSwitchLoader { _, _ -> } }
            }
        }

        if (controller.hotSwitchMessage.isNotBlank()) {
            Text(
                controller.hotSwitchMessage,
                style = COUITheme.textStyles.body2,
                color = c.muted,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        if (controller.activeKernelRaw.isNotBlank()) {
            Text(
                "primary：" + controller.activeKernelRaw,
                style = COUITheme.textStyles.body2,
                color = c.muted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (controller.probeDetail.isNotBlank()) {
            Text(
                controller.probeDetail,
                style = COUITheme.textStyles.body2,
                color = c.muted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
