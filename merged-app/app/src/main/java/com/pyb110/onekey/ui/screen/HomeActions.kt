package com.pyb110.onekey.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.components.ButtonIconText
import com.pyb110.onekey.ui.components.ListCard
import com.pyb110.onekey.ui.components.ListRow
import com.pyb110.onekey.ui.components.PanelButton
import com.pyb110.onekey.ui.components.RunFlow
import com.pyb110.onekey.ui.oneKeyCardColor
import com.pyb110.onekey.ui.oneKeyCardOutline
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Replace
import io.github.suqi8.coui.kmp.icon.extended.Report
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.icon.extended.Tasks
import io.github.suqi8.coui.kmp.icon.extended.Tune
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect

@Composable
internal fun ResultBanner(
    controller: JailbreakController,
    onOpenManager: () -> Unit,
    onOpenLog: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val isOk = controller.lastResultOk ?: return
    val is98260 = controller.lastResultExploitKind == "cve98260"

    val c = oneKeyColors()
    val containerColor = if (isOk) c.successSoft else c.errorSoft
    val contentColor = if (isOk) c.success else c.error

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(containerColor)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isOk) COUIIcons.Ok else COUIIcons.Close,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (isOk) "越狱执行成功" else "越狱执行未完成",
                        style = COUITheme.textStyles.subtitle,
                        color = contentColor
                    )
                    Text(
                        text = if (isOk) {
                            "内核提权与模块加载完成 · Root 服务已就绪"
                        } else if (is98260) {
                            "98260 执行未完成；内核状态可能已改变，重试前必须先硬重启设备。"
                        } else {
                            "在「${controller.stage}」阶段遇到错误，请查看日志排查问题。"
                        },
                        style = COUITheme.textStyles.body2,
                        color = c.ink
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isOk) {
                    TextButton(text = "查看日志", onClick = onOpenLog)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onOpenManager) {
                        ButtonIconText(COUIIcons.Settings, "打开管理器")
                    }
                } else {
                    TextButton(text = "环境诊断", onClick = onDiagnostics)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onOpenLog) {
                        ButtonIconText(COUIIcons.Notes, "查看详细日志")
                    }
                }
            }
        }
    }
}

@Composable
internal fun ActionCard(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    onDiagnostics: () -> Unit,
    onRunJailbreak: () -> Unit,
) {
    val c = oneKeyColors()
    val managerLabel = controller.managers.firstOrNull { it.kind == controller.selectedManager }?.label ?: "未选择"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(oneKeyCardColor())
            .border(1.dp, oneKeyCardOutline(), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        controller.running -> "正在运行"
                        controller.suCompatReady -> "运行完成"
                        controller.canRunExploit -> "准备就绪"
                        else -> "等待条件"
                    },
                    style = COUITheme.textStyles.headline2,
                    color = c.ink
                )
                Text(
                    managerLabel + " · 98260 运行链",
                    style = COUITheme.textStyles.body2,
                    color = c.muted
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.primarySoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (controller.suCompatReady) COUIIcons.Ok else COUIIcons.Lock,
                    contentDescription = null,
                    tint = c.primary,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PanelButton(
            text = if (controller.running) "运行中，请稍候" else "开始运行",
            primary = true,
            enabled = controller.canRunExploit && !controller.running,
            modifier = Modifier.fillMaxWidth()
        ) { haptic(CouiHapticEffect.Switch); onRunJailbreak() }
        controller.platformReason?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = c.error, style = COUITheme.textStyles.body2)
        }
        Spacer(Modifier.height(12.dp))
        RunFlow(controller.progress, controller.running, controller.lastResultOk)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    controller.running -> controller.stage.ifBlank { "运行中" }
                    controller.suCompatReady -> "本次运行已完成"
                    else -> "等待开始"
                },
                style = COUITheme.textStyles.body2,
                color = c.muted,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (!controller.running && controller.suCompatReady && controller.progress < 0.99f) "100%"
                else (controller.progress * 100).toInt().toString() + "%",
                style = COUITheme.textStyles.body2,
                color = c.primaryInk
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = controller.progress, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(
            "失败可直接重试，内核异常时才需重启",
            style = COUITheme.textStyles.body2,
            color = c.muted
        )
    }
}

@Composable
internal fun OptionsCard(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    onOpenSheet: (Int) -> Unit,
    onDiagnostics: () -> Unit,
) {
    val hotSummary = when {
        controller.hotSwitchAvailable -> "当前 " + controller.activeKernelManager.ifBlank { "未知" }
        controller.hotSwitchLoaderInstalled -> "通用加载器已安装，重启后运行一次即可启用"
        else -> "安装通用加载器后启用"
    }
    ListCard(
        rows = listOf(
            ListRow(
                title = "管理器热切换",
                summary = hotSummary,
                icon = COUIIcons.Replace,
                arrow = true,
                onClick = { haptic(CouiHapticEffect.Switch); onOpenSheet(1) }
            ),
            ListRow(
                title = "越狱管理器",
                summary = controller.managers.firstOrNull { it.kind == controller.selectedManager }?.label ?: "未选择",
                icon = COUIIcons.Tasks,
                arrow = true,
                onClick = { haptic(CouiHapticEffect.Switch); onOpenSheet(5) }
            ),
            ListRow(
                title = "管理器下载",
                summary = "五家官方最新正式版",
                icon = COUIIcons.Download,
                arrow = true,
                onClick = { haptic(CouiHapticEffect.Switch); onOpenSheet(2) }
            ),
            ListRow(
                title = "拓展开关",
                summary = "SUSFS / VFS / 卸载保护 / 反检测 / 软重启",
                icon = COUIIcons.Tune,
                arrow = true,
                onClick = { haptic(CouiHapticEffect.Switch); onOpenSheet(3) }
            ),
            ListRow(
                title = "环境诊断",
                summary = "检查服务通道、SU 权限与内核状态",
                icon = COUIIcons.Report,
                arrow = true,
                onClick = { haptic(CouiHapticEffect.Switch); onDiagnostics() }
            ),
        )
    )
}
