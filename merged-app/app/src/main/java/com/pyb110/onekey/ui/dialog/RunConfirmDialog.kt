package com.pyb110.onekey.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.components.DialogActions
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog

@Composable
internal fun RunConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
        WindowDialog(title = "98260 运行前确认", show = true, onDismissRequest = { onDismiss() }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("失败后可直接再点一次；只有内核状态异常时才需要硬重启。", style = COUITheme.textStyles.body2)
                Text(
                    "执行过程中将短暂提权并向内核装载特权模块。",
                    style = COUITheme.textStyles.body2,
                    color = oneKeyColors().muted,
                    modifier = Modifier.padding(top = 6.dp)
                )
                DialogActions(
                    DialogAction("取消", COUIIcons.Close) { onDismiss() },
                    DialogAction("确认开始", COUIIcons.Play, primary = true) { onConfirm() }
                )
            }
        }
}
