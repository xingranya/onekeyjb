package com.pyb110.onekey.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.components.DialogActions
import com.pyb110.onekey.ui.components.ManualSection
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.window.WindowDialog

@Composable
internal fun ManualDialog(onDismiss: () -> Unit) {
        WindowDialog(title = "使用手册", show = true, onDismissRequest = { onDismiss() }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                ManualSection("一、准备", listOf(
                    "安装要用的管理器（KernelSU / KernelSU-Next / SukiSU / BakaSU / FinaSU 之一），App 不再内置管理器和模块。",
                    "启动 Stellar（推荐）或 Shizuku 服务，回到 App 授予权限；首页显示「服务已连接并授权」才能继续。"
                ))
                ManualSection("二、一键越狱", listOf(
                    "点「运行 98260 越狱链」，确认后等待日志出现「越狱完成」。",
                    "失败时日志会写明停在哪一步；只有在真的写入内核后才会提示重启，未写入可直接再试。"
                ))
                ManualSection("三、拓展功能（5 项）", listOf(
                    "越狱后禁用安全弹窗：停掉安全守护/安全中心进程并抑制反 root 弹窗，不改包状态、不重启。",
                    "越狱后软重启：默认关闭。开启后越狱成功会执行一次 setprop ctl.restart zygote；关闭则不重启。",
                    "越狱后卸载内核安全防护：卸载 oplus_secure_guard 一类内核防护模块。",
                    "SUSFS 支持：越狱后加载隐藏模块（需要执行器侧配合）。",
                    "VFS 支持：加载 hybridmount 做路径重定向，仅 6.18 内核生效。"
                ))
                ManualSection("四、异常处理", listOf(
                    "提示「未写内核，可直接重试」：直接再点一次即可。",
                    "提示「已写内核但未拿到 root」：重启手机后再试，并导出日志。",
                    "管理器未授权：先在管理器里给 OneKey 授权 root，再回到 App 运行。"
                ))
                DialogActions(
                    DialogAction("知道了", COUIIcons.Ok, primary = true) { onDismiss() }
                )
            }
        }
}
