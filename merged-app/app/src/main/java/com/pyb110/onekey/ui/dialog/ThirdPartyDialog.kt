package com.pyb110.onekey.ui.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.components.MarkdownText
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.openUrl
import com.pyb110.onekey.ui.theme.COUIIcons
import com.pyb110.onekey.ui.theme.COUITheme
import com.pyb110.onekey.ui.theme.Text
import com.pyb110.onekey.ui.theme.WindowDialog
import com.itos.coui.dialog.CouiDialogDefaults

@Composable
internal fun ThirdPartyDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val noticeText = remember {
        runCatching {
            ctx.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
        }.getOrDefault("第三方声明加载失败，请查看应用仓库中的 THIRD_PARTY_NOTICES.md")
    }
    WindowDialog(
        title = "第三方组件说明",
        show = true,
        onDismissRequest = { onDismiss() },
        actions = listOf(
            DialogAction("复制全文", COUIIcons.Copy) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("notices", noticeText))
            },
            DialogAction("知道了", COUIIcons.Ok, primary = true) { onDismiss() },
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CouiDialogDefaults.contentPadding)
        ) {
            MarkdownText(
                markdown = noticeText,
                onLinkClick = { url -> openUrl(ctx, url) },
            )
            Spacer(Modifier.height(10.dp))
            // 成功率的口径必须写在这里：用户看到的百分比来自本应用自己的估算，
            // 不是任何第三方组件的承诺，也不是历史命中率。
            Text(
                "关于成功率的说明",
                style = COUITheme.textStyles.body2,
                color = oneKeyColors().ink
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "首页显示的百分比由本应用按内存占用、CPU 温度、系统负载实时估算，上限 92%，" +
                    "不构成对结果的保证；历史成功率只统计真正跑完的记录，强退与重启中断不计入。",
                style = COUITheme.textStyles.body2,
                fontSize = 11.sp,
                color = oneKeyColors().muted
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
