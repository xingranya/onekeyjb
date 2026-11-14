package com.pyb110.onekey.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.UpdateChecker
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.components.DialogActions
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog

internal data class UpdateUi(
    val info: UpdateChecker.Info,
    val downloading: Boolean,
    val progress: UpdateChecker.Progress,
    val error: String?,
    val expandedVersion: Int,
)

@Composable
internal fun UpdateDialog(
    ui: UpdateUi,
    onToggleVersion: (Int) -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
        WindowDialog(
            title = "发现新版本 · v" + ui.info.versionName + " (" + ui.info.versionCode + ")",
            show = true,
            onDismissRequest = { if (!ui.downloading) onDismiss() }
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    "更新时间 " + UpdateChecker.formatTime(ui.info.publishedAt) +
                        (if (ui.info.size > 0) " · 安装包 " + UpdateChecker.formatSize(ui.info.size) else ""),
                    style = COUITheme.textStyles.body2,
                    color = oneKeyColors().muted
                )
                Spacer(Modifier.height(10.dp))
                if (ui.info.versions.isEmpty()) {
                    Text(
                        ui.info.changelog.ifBlank { "无更新说明" },
                        style = COUITheme.textStyles.body2,
                        modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 240.dp)
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        ui.info.versions.forEach { note ->
                            VersionNoteRow(
                                note = note,
                                expanded = ui.expandedVersion == note.versionCode,
                                onToggle = { onToggleVersion(note.versionCode) }
                            )
                        }
                    }
                }
                ui.error?.let { err ->
                    Text(err, color = oneKeyColors().error, style = COUITheme.textStyles.body2, modifier = Modifier.padding(top = 8.dp))
                }
                if (ui.downloading) {
                    LinearProgressIndicator(
                        progress = ui.progress.percent / 100f,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "下载中 " + ui.progress.percent + "%",
                            style = COUITheme.textStyles.body2,
                            color = COUITheme.colorScheme.primary
                        )
                        Text(
                            UpdateChecker.formatSpeed(ui.progress.bytesPerSec) +
                                " · 剩余 " + UpdateChecker.formatSize(ui.progress.remaining),
                            style = COUITheme.textStyles.body2,
                            color = oneKeyColors().muted
                        )
                    }
                }
                DialogActions(
                    DialogAction("稍后", COUIIcons.Close, enabled = !ui.downloading) { onDismiss() },
                    DialogAction(
                        if (ui.downloading) "下载中…" else "下载并安装",
                        COUIIcons.Download,
                        primary = true,
                        enabled = !ui.downloading
                    ) { onDownload() }
                )
            }
        }
}
