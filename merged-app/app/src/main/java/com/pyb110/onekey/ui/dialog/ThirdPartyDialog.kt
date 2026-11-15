package com.pyb110.onekey.ui.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.components.MarkdownText
import com.pyb110.onekey.ui.openUrl
import com.pyb110.onekey.ui.theme.COUIIcons
import com.pyb110.onekey.ui.theme.*
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
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CouiDialogDefaults.contentPadding)
        ) {
            MarkdownText(
                markdown = noticeText,
                onLinkClick = { url -> openUrl(ctx, url) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
