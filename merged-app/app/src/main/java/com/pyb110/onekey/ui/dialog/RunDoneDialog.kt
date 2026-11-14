package com.pyb110.onekey.ui.dialog

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.components.DialogActions
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog
import androidx.compose.ui.platform.LocalContext
import com.pyb110.onekey.JailbreakController

@Composable
internal fun RunDoneDialog(controller: JailbreakController, onDismiss: () -> Unit) {
        val context = LocalContext.current
        WindowDialog(title = "98260 越狱完成", show = true, onDismissRequest = { onDismiss() }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("98260 临时 root 通道已就绪，特权内核模块已加载。本次开机不再允许重复运行。", style = COUITheme.textStyles.body2)
                DialogActions(
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
                    }
                )
            }
        }
}
