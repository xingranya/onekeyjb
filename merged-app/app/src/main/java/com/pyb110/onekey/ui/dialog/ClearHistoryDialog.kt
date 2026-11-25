package com.pyb110.onekey.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.ui.components.DialogInset
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.theme.COUIIcons
import com.pyb110.onekey.ui.theme.COUITheme
import com.pyb110.onekey.ui.theme.*

@Composable
internal fun ClearHistoryDialog(show: Boolean = true, onCancel: () -> Unit, onConfirm: () -> Unit) {
    WindowDialog(
        title = "清空历史记录",
        show = show,
        onDismissRequest = { onCancel() },
        actions = listOf(
            DialogAction("取消", COUIIcons.Close) { onCancel() },
            DialogAction("确认清空", COUIIcons.Delete, primary = true) { onConfirm() },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = DialogInset)) {
            Text(
                "确定清空全部越狱历史与诊断日志吗？\n此操作为物理删除且无法恢复。",
                style = COUITheme.textStyles.body2
            )
        }
    }
}
