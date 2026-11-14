package com.pyb110.onekey.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.AppearanceMode
import io.github.suqi8.coui.kmp.basic.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun openUrl(context: Context, url: String) {
    runCatching {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}

internal fun onCopy(
    context: Context,
    controller: JailbreakController,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("onekey-log", controller.logs.joinToString("\n")))
    scope.launch { snackbar.showSnackbar("日志已复制到剪贴板（" + controller.logs.size + " 行）") }
}

internal fun sheetTitle(sheet: Int): String = when (sheet) {
    1 -> "管理器热切换"
    2 -> "管理器下载"
    3 -> "拓展开关"
    4 -> "选择目标管理器"
    5 -> "选择越狱管理器"
    else -> ""
}

/** 内核版本取到 android 代次为止，避免长串 SMP 信息把一行挤爆。 */
internal fun shortKernelVersion(): String {
    val raw = System.getProperty("os.version") ?: return "未知"
    val cut = raw.substringBefore("-SMP").substringBefore(" SMP")
    return if (cut.length > 34) cut.take(34) + "…" else cut
}

internal fun appearanceLabel(mode: AppearanceMode): String = when (mode) {
    AppearanceMode.LIGHT -> "浅色 · 灰白配深玫瑰"
    AppearanceMode.DARK -> "深色 · 炭黑配雾玫瑰"
    AppearanceMode.SYSTEM -> "跟随系统"
}
