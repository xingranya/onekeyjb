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
import com.pyb110.onekey.ui.components.ListCard
import com.pyb110.onekey.ui.components.ListRow
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Hide
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Tasks
import io.github.suqi8.coui.kmp.icon.extended.Unlock
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect

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
                summary = if (controller.autoJailbreak) "开机后自动运行一次，需 Stellar 已开启开机启动"
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
        Spacer(Modifier.height(10.dp))
        Text(
            "开机自动越狱需要三个前提：① 在 Stellar 里开启「开机启动」；② 在系统设置里允许本应用自启动；③ 忽略电池优化。上次自动运行失败会自动关闭该开关，避免反复重启。",
            style = COUITheme.textStyles.body2,
            color = c.muted
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("忽略电池优化", Modifier.weight(1f)) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            PanelButton("打开 Stellar", Modifier.weight(1f)) {
                runCatching {
                    context.packageManager.getLaunchIntentForPackage("roro.stellar.manager")
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { context.startActivity(it) }
                }
            }
        }
    }
}
