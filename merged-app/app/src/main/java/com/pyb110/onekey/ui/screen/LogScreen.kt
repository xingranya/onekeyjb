package com.pyb110.onekey.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.shape.CouiShape
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.LogEntry
import com.pyb110.onekey.LogLevel
import com.pyb110.onekey.RunLog
import com.pyb110.onekey.ui.components.PanelButton
import com.pyb110.onekey.ui.components.RunFlow
import com.pyb110.onekey.ui.oneKeyCardColor
import com.pyb110.onekey.ui.oneKeyCardOutline
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.theme.COUIIcons
import com.pyb110.onekey.ui.theme.COUITheme
import com.pyb110.onekey.ui.theme.CouiHapticEffect
import com.pyb110.onekey.ui.theme.Icon
import com.pyb110.onekey.ui.theme.IconButton
import com.pyb110.onekey.ui.theme.SnackbarHostState
import com.pyb110.onekey.ui.theme.Surface
import com.pyb110.onekey.ui.theme.Switch
import com.pyb110.onekey.ui.theme.Text
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun LogScreen(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    contentPadding: PaddingValues,
    onGoHome: () -> Unit,
    onExport: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    val c = oneKeyColors()
    val logs = controller.logs
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    var filter by remember { mutableIntStateOf(0) }

    // 运行时长精确到小数点后两位：运行中按毫秒计时，结束后沿用控制器记录的秒数。
    var startMs by remember { mutableStateOf<Long?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(controller.running) {
        if (controller.running) {
            startMs = System.currentTimeMillis()
            while (controller.running) {
                tick++
                kotlinx.coroutines.delay(50)
            }
            startMs = null
        }
    }
    val elapsedSeconds = if (controller.running && startMs != null) {
        (System.currentTimeMillis() - (startMs ?: 0L)) / 1000.0
    } else {
        controller.elapsedSeconds.toDouble()
    }
    val elapsedText = String.format(java.util.Locale.US, "%.2f", elapsedSeconds)

    // 解析只做一次：日志行不变时复用同一批 LogEntry，避免每次重组都跑正则。
    val parsed: List<LogEntry> = remember(logs) { logs.map { RunLog.parse(it) } }
    val counts = remember(parsed) {
        intArrayOf(
            parsed.count { it.level == LogLevel.SUCCESS },
            parsed.count { it.level == LogLevel.WARN },
            parsed.count { it.level == LogLevel.ERROR },
        )
    }
    val filtered = when (filter) {
        1 -> parsed.filter { it.level == LogLevel.SUCCESS }
        2 -> parsed.filter { it.level == LogLevel.WARN }
        3 -> parsed.filter { it.level == LogLevel.ERROR }
        else -> parsed
    }
    LaunchedEffect(filtered.size, autoScroll, filter) {
        if (autoScroll && filtered.isNotEmpty()) listState.animateScrollToItem(filtered.size - 1)
    }

    if (logs.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))
            Icon(COUIIcons.Notes, contentDescription = null, tint = c.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text("还没有运行日志", style = COUITheme.textStyles.headline2, color = c.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "越狱一次之后，这里会按时间顺序列出每一步的结果。",
                style = COUITheme.textStyles.body2,
                color = c.muted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            PanelButton(text = "前往首页", modifier = Modifier.fillMaxWidth()) { onGoHome() }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(CouiShape(20.dp, 2f))
                .background(oneKeyCardColor())
                .border(1.dp, oneKeyCardOutline(), CouiShape(20.dp, 2f))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (controller.running) "任务进行中" else if (logs.isNotEmpty()) "本次运行" else "等待运行",
                        style = COUITheme.textStyles.headline2,
                        color = c.ink
                    )
                    Text(
                        if (controller.running) controller.stage.ifBlank { "运行中" }
                        else if (controller.lastResultOk == true) "运行结束" else "运行输出",
                        style = COUITheme.textStyles.body2,
                        color = c.muted
                    )
                }
                Text(
                    elapsedText + "s",
                    style = COUITheme.textStyles.body2,
                    fontFamily = FontFamily.Monospace,
                    color = c.muted
                )
            }
            Spacer(Modifier.height(12.dp))
            RunFlow(controller.progress, controller.running, controller.lastResultOk)
        }
        Spacer(Modifier.height(12.dp))
        // 筛选条：每档带条数，一眼看出这次跑得干不干净。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CouiShape(14.dp, 2f))
                .background(c.segmentBg)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                "全部" to parsed.size,
                "成功" to counts[0],
                "警告" to counts[1],
                "失败" to counts[2],
            ).forEachIndexed { index, item ->
                val selected = filter == index
                Surface(
                    onClick = { haptic(CouiHapticEffect.Switch); filter = index },
                    shape = CouiShape(10.dp, 2f),
                    color = if (selected) c.surface else Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (item.second > 0) item.first + " " + item.second else item.first,
                            style = COUITheme.textStyles.body2,
                            color = if (selected) c.primaryInk else c.muted,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).clickable { autoScroll = !autoScroll },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
                Spacer(Modifier.width(8.dp))
                Text("自动滚动", style = COUITheme.textStyles.body2, color = c.muted)
            }
            IconButton(onClick = onCopy, enabled = logs.isNotEmpty()) {
                Icon(COUIIcons.Copy, contentDescription = "复制全部日志", tint = c.muted)
            }
            IconButton(onClick = onExport, enabled = logs.isNotEmpty()) {
                Icon(COUIIcons.Download, contentDescription = "导出全部日志", tint = c.muted)
            }
            IconButton(onClick = onClear, enabled = logs.isNotEmpty() && !controller.running) {
                Icon(COUIIcons.Delete, contentDescription = "清空日志", tint = c.muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(CouiShape(20.dp, 2f))
                .background(oneKeyCardColor())
                .border(1.dp, oneKeyCardOutline(), CouiShape(20.dp, 2f))
        ) {
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "当前筛选下没有记录",
                        style = COUITheme.textStyles.body2,
                        color = c.muted
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered.size) { idx -> LogRow(filtered[idx]) }
                }
            }
        }
    }
}

/**
 * 一行日志：左侧等级色条 + 时间 + 等级标签 + 正文。
 *
 * 等级同时用三种方式表达——色条、标签文字、标题颜色。只靠颜色不行：
 * 色弱用户分不出红绿，而"信息/成功/警告/失败"四个中文标签一眼就懂。
 */
@Composable
private fun LogRow(entry: LogEntry) {
    val c = oneKeyColors()
    val accent = when (entry.level) {
        LogLevel.ERROR -> c.error
        LogLevel.WARN -> c.warning
        LogLevel.SUCCESS -> c.success
        LogLevel.INFO -> c.muted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CouiShape(10.dp, 2f))
            .background(accent.copy(alpha = 0.06f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .width(3.dp)
                .height(13.dp)
                .clip(CouiShape(2.dp, 1f))
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            entry.time,
            style = COUITheme.textStyles.body2,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = c.muted
        )
        Spacer(Modifier.width(6.dp))
        LevelTag(entry.level, accent)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = COUITheme.textStyles.body2,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = if (entry.level == LogLevel.INFO) c.ink else accent
            )
            if (entry.detail.isNotBlank() || entry.ms != null) {
                Text(
                    buildString {
                        if (entry.detail.isNotBlank()) append(entry.detail)
                        if (entry.ms != null && entry.ms > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(RunLog.duration(entry.ms))
                        }
                    },
                    style = COUITheme.textStyles.body2,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = c.muted
                )
            }
        }
    }
}

/**
 * 等级标签：固定宽度的小色块，四种等级宽度一致，列表左边缘不会参差。
 * 用中文标签而不是 [+]/[-]/[!] 之类的符号——符号得先学，中文直接读。
 */
@Composable
private fun LevelTag(level: LogLevel, accent: Color) {
    Box(
        Modifier
            .padding(top = 1.dp)
            .width(26.dp)
            .clip(CouiShape(4.dp, 1f))
            .background(accent.copy(alpha = 0.14f))
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            level.label,
            style = COUITheme.textStyles.body2,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = accent,
            maxLines = 1
        )
    }
}

/** 保留给外部调用的等级判定（历史页等仍按行判定）。 */
internal fun logLevel(line: String): Int = when (RunLog.parse(line).level) {
    LogLevel.ERROR -> 2
    LogLevel.WARN -> 1
    else -> 0
}

internal fun isSuccessLog(line: String): Boolean = RunLog.parse(line).level == LogLevel.SUCCESS
