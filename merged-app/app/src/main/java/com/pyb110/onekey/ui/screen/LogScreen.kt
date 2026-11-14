package com.pyb110.onekey.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.components.PanelButton
import com.pyb110.onekey.ui.components.RunFlow
import com.pyb110.onekey.ui.onCopy
import com.pyb110.onekey.ui.oneKeyCardColor
import com.pyb110.onekey.ui.oneKeyCardOutline
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
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
        tick.let { (System.currentTimeMillis() - (startMs ?: 0L)) / 1000.0 }
    } else {
        controller.elapsedSeconds.toDouble()
    }
    val elapsedText = String.format(java.util.Locale.US, "%.2f", elapsedSeconds)

    val filtered = when (filter) {
        1 -> logs.filter { logLevel(it) >= 1 }
        2 -> logs.filter { logLevel(it) == 2 }
        else -> logs
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
                "开始运行或执行环境诊断后，输出会出现在这里。",
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
                .clip(RoundedCornerShape(20.dp))
                .background(oneKeyCardColor())
                .border(1.dp, oneKeyCardOutline(), RoundedCornerShape(20.dp))
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.segmentBg)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("全部", "警告", "错误").forEachIndexed { index, label ->
                val selected = filter == index
                Surface(
                    onClick = { haptic(CouiHapticEffect.Switch); filter = index },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) c.surface else Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = COUITheme.textStyles.body2,
                            color = if (selected) c.primaryInk else c.muted
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
                .clip(RoundedCornerShape(20.dp))
                .background(oneKeyCardColor())
                .border(1.dp, oneKeyCardOutline(), RoundedCornerShape(20.dp))
        ) {
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (logs.isEmpty()) "还没有运行日志" else "当前筛选下没有记录",
                        style = COUITheme.textStyles.body2,
                        color = c.muted
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    items(filtered.size) { idx ->
                        val line = filtered[idx]
                        val level = logLevel(line)
                        val tint = when {
                            level == 2 -> c.error
                            level == 1 -> c.warning
                            isSuccessLog(line) -> c.success
                            else -> c.muted
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                line,
                                style = COUITheme.textStyles.body2,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = when {
                                    level == 2 -> c.error
                                    level == 1 -> c.warning
                                    isSuccessLog(line) -> c.success
                                    else -> c.ink
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 0 = 普通/成功，1 = 警告，2 = 错误。 */
internal fun logLevel(line: String): Int = when {
    line.contains("失败") || line.contains("中止") || line.contains("错误") ||
        line.contains("rc=-1") || line.contains("FAIL") || line.contains("MISSING") -> 2
    line.contains("未命中") || line.contains("跳过") || line.contains("SKIP") ||
        line.contains("警告") || line.contains("离线") -> 1
    else -> 0
}

internal fun isSuccessLog(line: String): Boolean =
    line.contains("SU_ID_RC=0") || line.contains("已加载") || line.contains("成功") ||
        line.contains("就绪") || line.contains("完成")
