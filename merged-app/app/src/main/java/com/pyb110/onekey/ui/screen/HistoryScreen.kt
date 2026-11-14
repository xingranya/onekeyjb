package com.pyb110.onekey.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyb110.onekey.HistoryRecord
import com.pyb110.onekey.HistoryStore
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.components.ListCard
import com.pyb110.onekey.ui.components.ListRow
import com.pyb110.onekey.ui.components.SectionTitle
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun HistoryScreen(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    reloadTick: Int,
    onGoHome: () -> Unit,
    onClearRequest: () -> Unit,
) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<HistoryRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<HistoryRecord?>(null) }
    var logText by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> out.write(logText.toByteArray()) }
                scope.launch { snackbarHostState.showSnackbar("历史日志已导出") }
            }.onFailure { err ->
                scope.launch { snackbarHostState.showSnackbar("导出失败：" + (err.message ?: "未知错误")) }
            }
        }
    }

    LaunchedEffect(reloadTick, controller.lastResultOk, controller.running) {
        records = withContext(Dispatchers.IO) { HistoryStore.list(context) }
        if (selected != null && records.none { it.id == selected?.id }) selected = null
    }
    LaunchedEffect(selected?.id) {
        val s = selected ?: run { logText = ""; return@LaunchedEffect }
        logText = withContext(Dispatchers.IO) { HistoryStore.readLog(context, s) }
    }

    if (records.isEmpty()) {
        FullPageStatement(
            title = "暂无历史记录",
            content = "尚未检测到越狱执行记录。每次任务结束后，耗时、所选架构及详细日志将自动归档在此。",
            primaryButtonText = "前往首页",
            onPrimaryButtonClick = onGoHome,
            modifier = Modifier.fillMaxSize().padding(contentPadding)
        )
    } else if (selected != null) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { haptic(CouiHapticEffect.Switch); selected = null }) {
                    Icon(COUIIcons.Back, contentDescription = "返回列表")
                }
                Spacer(Modifier.width(8.dp))
                Text("记录详情 · ${HistoryStore.formatTime(selected?.startedAt ?: 0L)}", style = COUITheme.textStyles.subtitle, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("onekey-history", logText))
                    haptic(CouiHapticEffect.Switch)
                    scope.launch { snackbarHostState.showSnackbar("历史日志已复制") }
                }, enabled = logText.isNotBlank()) {
                    Icon(COUIIcons.Copy, contentDescription = "复制历史日志")
                }
                IconButton(onClick = {
                    haptic(CouiHapticEffect.Switch)
                    exportLauncher.launch("onekey-history-" + System.currentTimeMillis() + ".txt")
                }, enabled = logText.isNotBlank()) {
                    Icon(COUIIcons.Download, contentDescription = "导出历史日志")
                }
            }
            Card(Modifier.fillMaxSize()) {
                val scroll = rememberScrollState()
                Text(
                    logText.ifBlank { "此记录未包含日志" },
                    style = COUITheme.textStyles.body2,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(scroll)
                )
            }
        }
    } else {
        var filter by remember { mutableIntStateOf(0) }
        val filtered = records.filter {
            when (filter) {
                1 -> it.status == "ok"
                2 -> it.status != "ok"
                else -> true
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) {
                    listOf("全部", "成功", "失败").forEachIndexed { index, label ->
                        val on = filter == index
                        Surface(
                            onClick = { haptic(CouiHapticEffect.Switch); filter = index },
                            shape = CircleShape,
                            color = if (on) COUITheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                label,
                                style = COUITheme.textStyles.body2,
                                color = if (on) COUITheme.colorScheme.onPrimary else COUITheme.colorScheme.onSurfaceContainerVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
            listOf(0 to "今日", 1 to "昨日", 2 to "更早").forEach { (bucket, label) ->
                val groupRows = filtered.filter { historyDayBucket(it.startedAt) == bucket }
                if (groupRows.isNotEmpty()) {
                    item { SectionTitle(label + " · " + groupRows.size + " 条") }
                    item {
                        ListCard(
                            rows = groupRows.map { rec ->
                                val isOk = rec.status == "ok"
                                ListRow(
                                    title = "98260 运行链",
                                    summary = rec.manager + " · " +
                                        HistoryStore.formatTime(rec.startedAt) + " · " +
                                        (if (isOk) "运行成功" else "未完成") + " " +
                                        String.format(java.util.Locale.US, "%.2fs", rec.durationSec.toDouble()),
                                    icon = if (isOk) COUIIcons.Ok else COUIIcons.Close,
                                    iconTint = if (isOk) oneKeyColors().success else oneKeyColors().error,
                                    arrow = true,
                                    onClick = { haptic(CouiHapticEffect.Switch); selected = rec }
                                )
                            }
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "当前筛选下没有记录",
                            style = COUITheme.textStyles.body2,
                            color = oneKeyColors().muted,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Surface(
                    onClick = { haptic(CouiHapticEffect.Switch); onClearRequest() },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = COUITheme.colorScheme.surfaceContainer
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("清空全部历史记录", style = COUITheme.textStyles.body1, color = oneKeyColors().error)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** 历史记录按天分桶：0 今日、1 昨日、2 更早。 */
internal fun historyDayBucket(ts: Long): Int {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val today = cal.timeInMillis
    val yesterday = today - 86_400_000L
    return when {
        ts >= today -> 0
        ts >= yesterday -> 1
        else -> 2
    }
}
