package com.pyb110.onekey.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.components.DialogActions
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog

@Composable
internal fun ClearHistoryDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
        WindowDialog(
            title = "清空历史记录",
            show = true,
            onDismissRequest = { onCancel() }
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    "确定清空全部越狱历史与诊断日志吗？\n此操作为物理删除且无法恢复。",
                    style = COUITheme.textStyles.body2
                )
                DialogActions(
                    DialogAction("取消", COUIIcons.Close) { onCancel() },
                    DialogAction("确认清空", COUIIcons.Delete, primary = true) {
                        onConfirm()
                    }
                )
            }
        }
}
