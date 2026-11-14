package com.pyb110.onekey.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.components.DialogActions
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog

@Composable
internal fun TermsDialog(onAccept: () -> Unit) {
        val ctx = LocalContext.current
        val termsText = remember {
            runCatching {
                ctx.assets.open("TERMS.md").bufferedReader().use { it.readText() }
                    .replace("**", "").replace("## ", "").replace("# ", "")
            }.getOrDefault("协议加载失败")
        }
        val scrollState = rememberScrollState()
        var remain by remember { mutableStateOf(10) }
        val reachedBottom = remember { derivedStateOf { scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 4 } }
        LaunchedEffect(Unit) {
            while (remain > 0) {
                kotlinx.coroutines.delay(1000)
                remain -= 1
            }
        }
        val canAgree = remain <= 0 && (reachedBottom.value || scrollState.maxValue == 0)
        WindowDialog(title = "用户协议与免责声明", show = true, onDismissRequest = { }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 470.dp)
                    .padding(horizontal = 20.dp)
            ) {
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        termsText,
                        style = COUITheme.textStyles.body2,
                        color = oneKeyColors().muted
                    )
                }
                Text(
                    when {
                        canAgree -> "已阅读并滚动到底部，可以继续"
                        scrollState.maxValue > 0 && !reachedBottom.value -> "请向下滚动阅读全文（还需 " + remain + " 秒）"
                        else -> "请阅读 " + remain + " 秒后继续"
                    },
                    style = COUITheme.textStyles.body2,
                    color = if (canAgree) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
                DialogActions(
                    DialogAction("退出", COUIIcons.Close) {
                        (ctx as? android.app.Activity)?.finish()
                    },
                    DialogAction("同意并继续", COUIIcons.Ok, primary = true, enabled = canAgree) {
                        onAccept()
                    }
                )
            }
        }
}
