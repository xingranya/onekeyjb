package com.pyb110.onekey.ui.sheet

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.components.PanelButton
import com.pyb110.onekey.ui.oneKeyColors
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.pyb110.onekey.ui.components.ListCard
import com.pyb110.onekey.ui.components.ListRow
import com.pyb110.onekey.ui.theme.COUIIcons
import com.pyb110.onekey.ui.theme.COUITheme
import com.pyb110.onekey.ui.theme.*

@Composable
internal fun FeatureTogglePanel(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
) {
    val context = LocalContext.current
    val c = oneKeyColors()
    Column(Modifier.fillMaxWidth()) {
    ListCard(
        rows = listOf(
            ListRow(
                title = "开机自动越狱",
                summary = if (controller.autoJailbreak) "开机后自动运行一次，需 Shell 通道就绪"
                          else "关闭：开机后需手动运行",
                icon = COUIIcons.Play,
                end = {
                    Switch(
                        checked = controller.autoJailbreak,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateAutoJailbreak(it) },
                        enabled = !controller.running
                    )
                }
            ),
            ListRow(
                title = "越狱后禁用安全弹窗",
                summary = "停用安全守护与安全中心，不影响截屏",
                icon = COUIIcons.Lock,
                end = {
                    Switch(
                        checked = controller.antiRootGuard,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateAntiRootGuard(it) },
                        enabled = !controller.running
                    )
                }
            ),
            ListRow(
                title = "越狱后软重启",
                summary = if (controller.softRebootAfter) "开启：成功后重启一次用户态（内核模块保持加载）"
                          else "关闭：成功后保持当前会话，不会自动重启",
                icon = COUIIcons.Refresh,
                end = {
                    Switch(
                        checked = controller.softRebootAfter,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateSoftRebootAfter(it) },
                        enabled = !controller.running
                    )
                }
            ),
            ListRow(
                title = "越狱后卸载内核安全防护",
                summary = "卸载 oplus_secure_guard 等内核防护模块",
                icon = COUIIcons.Unlock,
                end = {
                    Switch(
                        checked = controller.unloadKernelGuard,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateUnloadKernelGuard(it) },
                        enabled = !controller.running
                    )
                }
            ),
            ListRow(
                title = "SUSFS 支持",
                summary = "加载 SUSFS 隐藏模块（需要执行器侧配合）",
                icon = COUIIcons.Hide,
                end = {
                    Switch(
                        checked = controller.susfsEnabled,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateSusfs(it) },
                        enabled = !controller.running
                    )
                }
            ),
            ListRow(
                title = "VFS 支持",
                summary = "加载 hybridmount VFS 重定向（仅 6.18 内核）",
                icon = COUIIcons.Tasks,
                end = {
                    Switch(
                        checked = controller.vfsEnabled,
                        onCheckedChange = { haptic(CouiHapticEffect.Switch); controller.updateVfs(it) },
                        enabled = !controller.running
                    )
                }
            )
        )
    )
        Spacer(Modifier.height(12.dp))
        // 说明放进 COUI 的 Group 卡片，与上面的开关分组同宽、同圆角、同内边距。
        Group(footer = "上次自动运行失败会自动关闭该开关，避免反复重启。") {
            Item(
                title = { Text("使用前提", style = COUITheme.textStyles.body1) },
                summary = {
                    Text(
                        "① 启动 Shell 通道（首次需一次 adb 或已有 root）\n② 在系统设置里允许本应用自启动\n③ 忽略电池优化",
                        style = COUITheme.textStyles.body2,
                        color = c.muted
                    )
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        // 与 COUI 弹窗按钮面板对齐：左右各留 buttonEdgePadding。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CouiDialogDefaults.buttonEdgePadding),
            horizontalArrangement = Arrangement.spacedBy(CouiDialogDefaults.buttonInsidePadding),
        ) {
            PanelButton("忽略电池优化", Modifier.weight(1f)) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            PanelButton("启动 Shell 通道", Modifier.weight(1f)) {
                haptic(CouiHapticEffect.Switch)
                // 引导是同步阻塞的（su 拉起要等 socket 就绪），放后台线程再回主线程报结果
                Thread {
                    val outcome = com.pyb110.onekey.shell.ShellBootstrap.start(context) { line ->
                        controller.addUiLog(com.pyb110.onekey.RunLog.info(line))
                    }
                    controller.addUiLog(
                        if (outcome.ok) com.pyb110.onekey.RunLog.success(
                            "Shell 通道已就绪（" + outcome.label + "，uid=" + outcome.uid + "）"
                        ) else com.pyb110.onekey.RunLog.warn(
                            "Shell 通道未就绪",
                            "可手动执行：" + outcome.manualCommand
                        )
                    )
                    // 全部引导都失败时，走无线调试配对这条最后的路。
                    // 必须用前台服务 + 通知栏回码：配对端口只在设置页对话框可见期间存活，
                    // 弹应用内对话框会让用户切走、端口立刻关闭。
                    if (!outcome.ok) {
                        com.pyb110.onekey.adb.AdbPairingService.start(context)
                        controller.addUiLog(
                            com.pyb110.onekey.RunLog.info(
                                "已发起无线调试配对",
                                "请在设置里打开「使用配对码配对设备」，再从通知栏回复 6 位配对码"
                            )
                        )
                    }
                }.start()
            }
        }
    }
}
