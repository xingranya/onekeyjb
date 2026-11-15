package com.pyb110.onekey.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.theme.COUIIcons
import com.pyb110.onekey.ui.theme.COUITheme
import com.pyb110.onekey.ui.theme.*
import com.itos.coui.dialog.CouiDialogDefaults

@Composable
internal fun TermsDialog(onAccept: () -> Unit) {
    val ctx = LocalContext.current
    val termsText = remember {
        runCatching {
            ctx.assets.open("TERMS.md").bufferedReader().use { it.readText() }
                .replace("**", "").replace("## ", "").replace("# ", "")
        }.getOrDefault("协议加载失败")
    }
    // 滚动由 COUI 弹窗的 content 槽负责，外部拿不到它的 ScrollState，
    // 所以这里只按阅读时长放行：倒计时结束即可同意。
    var remain by remember { mutableIntStateOf(10) }
    LaunchedEffect(Unit) {
        while (remain > 0) {
            kotlinx.coroutines.delay(1000)
            remain -= 1
        }
    }
    val canAgree = remain <= 0
    WindowDialog(
        title = "用户协议与免责声明",
        show = true,
        onDismissRequest = { },
        actions = listOf(
            DialogAction("退出", COUIIcons.Close) { (ctx as? android.app.Activity)?.finish() },
            DialogAction("同意并继续", COUIIcons.Ok, primary = true, enabled = canAgree) { onAccept() },
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CouiDialogDefaults.contentPadding)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    termsText,
                    style = COUITheme.textStyles.body2,
                    color = oneKeyColors().muted
                )
            }
            Text(
                if (canAgree) "已阅读，可以继续" else "请阅读 " + remain + " 秒后继续",
                style = COUITheme.textStyles.body2,
                color = if (canAgree) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
            )
        }
    }
}
