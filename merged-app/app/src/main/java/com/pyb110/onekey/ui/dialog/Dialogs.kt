package com.pyb110.onekey.ui.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.R
import com.pyb110.onekey.UpdateChecker
import com.pyb110.onekey.ui.components.ButtonIconText
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.components.DialogActions
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Report
import io.github.suqi8.coui.kmp.icon.extended.ScreenCapture
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.icon.extended.Unlock
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.LocalDismissState
import io.github.suqi8.coui.kmp.window.WindowDialog

@Composable
internal fun DiagnosticsDialog(
    controller: JailbreakController,
    onDismiss: () -> Unit,
    onOpenLog: () -> Unit,
    onMessage: (String) -> Unit,
) {
    WindowDialog(title = "环境诊断", show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "环境检查",
                summary = "服务通道、已装管理器与 SU 挂载",
                startAction = { Icon(COUIIcons.Search, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                onClick = { controller.selfCheck(); onMessage("正在检查环境"); onDismiss(); onOpenLog() }
            )
            BasicComponent(
                title = "修复截屏扩展",
                summary = "启用 exsystemservice 组件",
                startAction = { Icon(COUIIcons.ScreenCapture, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                onClick = { controller.repairScreenshot(); onMessage("正在修复截屏"); onDismiss(); onOpenLog() }
            )
            BasicComponent(
                title = "恢复安全组件",
                summary = "重新启用 securityguard 与 safecenter",
                startAction = { Icon(COUIIcons.Unlock, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                onClick = { controller.restoreSecurityComponents(); onMessage("正在恢复安全组件"); onDismiss(); onOpenLog() }
            )
            BasicComponent(
                title = "导出设备诊断",
                summary = "提取内核与 SELinux 状态至日志",
                startAction = { Icon(COUIIcons.Report, contentDescription = null, modifier = Modifier.padding(end = 14.dp), tint = COUITheme.colorScheme.onSurfaceContainerVariant) },
                onClick = { controller.collectDiagnostics(); onMessage("已导出诊断信息"); onDismiss(); onOpenLog() }
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                ButtonIconText(COUIIcons.Close, "关闭")
            }
        }
    }
}

@Composable
internal fun AppreciationDialog(onMessage: (String) -> Unit, onDismiss: (Boolean) -> Unit) {
    val context = LocalContext.current
    val dismiss = LocalDismissState.current
    var dontShow by remember { mutableStateOf(true) }
    val close = {
        val s = dontShow
        dismiss?.invoke()
        onDismiss(s)
    }
    WindowDialog(title = "免费声明 · 禁止倒卖", show = true, onDismissRequest = { close() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("倒卖必究", style = COUITheme.textStyles.subtitle, color = oneKeyColors().error)
            Text("本工具免费分享，禁止倒卖。QQ群：1121682811。赞赏完全自愿，不影响任何功能。", style = COUITheme.textStyles.body2, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
            Image(painterResource(R.drawable.appreciation), contentDescription = "作者赞赏码", modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("不再提示", style = COUITheme.textStyles.body2, modifier = Modifier.weight(1f))
                Switch(checked = dontShow, onCheckedChange = { dontShow = it })
            }
            DialogActions(
                DialogAction("复制群号", COUIIcons.Copy) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("qq", "1121682811"))
                    onMessage("群号已复制")
                },
                DialogAction("保存相册", COUIIcons.Download) {
                    saveAppreciation(context, onMessage)
                },
                DialogAction("知道了", COUIIcons.Ok, primary = true) { close() }
            )
        }
    }
}

internal fun saveAppreciation(context: Context, onMessage: (String) -> Unit) {
    val resolver = context.contentResolver
    val cv = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "onekey-appreciation-" + System.currentTimeMillis() + ".png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OneKey")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
    if (uri == null) {
        onMessage("保存失败：无法创建图片记录")
        return
    }
    try {
        val input = context.assets.open("appreciation.png")
        val output = resolver.openOutputStream(uri)
        if (output == null) {
            onMessage("保存失败：无法打开写入流")
            return
        }
        input.use { i -> output.use { o -> i.copyTo(o) } }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, update, null, null)
        }
        onMessage("赞赏码已保存到相册（Pictures/OneKey）")
    } catch (e: Exception) {
        onMessage("保存失败：" + (e.message ?: "未知错误"))
    }
}

/** 更新弹窗里的单个版本卡片：点开后显示该小版本的逐条更新日志。 */
@Composable
internal fun VersionNoteRow(
    note: UpdateChecker.VersionNote,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(COUITheme.colorScheme.surfaceContainer)
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "v" + note.versionName + " · " + note.versionCode,
                style = COUITheme.textStyles.body2,
                color = if (expanded) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurface
            )
            Text(
                if (note.size > 0) UpdateChecker.formatSize(note.size) else "大小未标注",
                style = COUITheme.textStyles.body2,
                fontSize = 10.sp,
                color = oneKeyColors().muted
            )
        }
        Text(
            "更新时间 " + UpdateChecker.formatTime(note.publishedAt),
            style = COUITheme.textStyles.body2,
            fontSize = 10.sp,
            color = oneKeyColors().muted,
            modifier = Modifier.padding(top = 2.dp)
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(Modifier.padding(top = 6.dp)) {
                note.notes.ifEmpty { listOf("该版本无单独说明") }.forEach { line ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            "· ",
                            style = COUITheme.textStyles.body2,
                            color = COUITheme.colorScheme.primary
                        )
                        Text(
                            line,
                            style = COUITheme.textStyles.body2,
                            color = oneKeyColors().muted
                        )
                    }
                }
            }
        }
    }
}
