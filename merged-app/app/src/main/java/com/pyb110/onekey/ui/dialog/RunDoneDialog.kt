package com.pyb110.onekey.ui.dialog

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.theme.COUIIcons
import com.pyb110.onekey.ui.theme.COUITheme
import com.pyb110.onekey.ui.theme.*
import com.itos.coui.dialog.CouiDialogDefaults

@Composable
internal fun RunDoneDialog(controller: JailbreakController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    WindowDialog(
        title = "98260 越狱完成",
        show = true,
        onDismissRequest = { onDismiss() },
        actions = listOf(
            DialogAction("稍后", COUIIcons.Close) { onDismiss() },
            DialogAction("打开管理器", COUIIcons.Settings, primary = true) {
                onDismiss()
                val pkg = controller.managers.firstOrNull { it.kind == controller.selectedManager }?.pkg
                runCatching {
                    context.startActivity(
                        context.packageManager.getLaunchIntentForPackage(pkg ?: "")
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = CouiDialogDefaults.contentPadding)) {
            Text(
                "98260 临时 root 通道已就绪，特权内核模块已加载。本次开机不再允许重复运行。",
                style = COUITheme.textStyles.body2
            )
        }
    }
}
