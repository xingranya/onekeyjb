package com.pyb110.onekey.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.theme.COUITheme

internal enum class StepState {
    PENDING,
    RUNNING,
    SUCCESS,
    ERROR
}

internal val runFlowLabels = listOf("准备", "提权", "加载", "验证", "完成")

internal fun runFlowIndex(progress: Float): Int = when {
    progress >= 0.95f -> 4
    progress >= 0.70f -> 3
    progress >= 0.45f -> 2
    progress >= 0.15f -> 1
    else -> 0
}

@Composable
internal fun RunFlow(
    progress: Float,
    running: Boolean,
    lastResultOk: Boolean?,
    modifier: Modifier = Modifier,
) {
    val current = runFlowIndex(progress)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        runFlowLabels.forEachIndexed { index, label ->
            val state = when {
                !running && lastResultOk == null -> StepState.PENDING
                running -> when {
                    index < current -> StepState.SUCCESS
                    index == current -> StepState.RUNNING
                    else -> StepState.PENDING
                }
                !running && lastResultOk == true -> StepState.SUCCESS
                !running && lastResultOk == false -> when {
                    index < current -> StepState.SUCCESS
                    index == current -> StepState.ERROR
                    else -> StepState.PENDING
                }
                else -> StepState.PENDING
            }

            val c = oneKeyColors()
            val (bg, fg, glyph) = when (state) {
                StepState.SUCCESS -> Triple(c.primary, c.onPrimary, null)
                StepState.RUNNING -> Triple(c.primarySoft, c.primaryInk, (index + 1).toString())
                StepState.ERROR -> Triple(c.error, c.onPrimary, null)
                StepState.PENDING -> Triple(c.background, c.muted, (index + 1).toString())
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .scale(if (state == StepState.RUNNING) pulseScale else 1.0f)
                        .clip(CircleShape)
                        .background(bg),
                    contentAlignment = Alignment.Center
                ) {
                    if (glyph != null) {
                        Text(glyph, style = COUITheme.textStyles.body2, fontSize = 11.sp, color = fg)
                    } else {
                        Icon(
                            imageVector = if (state == StepState.ERROR) COUIIcons.Close else COUIIcons.Ok,
                            contentDescription = null,
                            tint = fg,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    style = COUITheme.textStyles.body2,
                    fontSize = 11.sp,
                    color = when (state) {
                        StepState.ERROR -> c.error
                        StepState.PENDING -> c.muted
                        else -> c.primaryInk
                    }
                )
            }

            if (index < runFlowLabels.lastIndex) {
                val nextState = when {
                    !running && lastResultOk == null -> StepState.PENDING
                    running -> if (index + 1 <= current) StepState.RUNNING else StepState.PENDING
                    !running && lastResultOk == true -> StepState.SUCCESS
                    else -> StepState.PENDING
                }
                val lineDone = state == StepState.SUCCESS && (nextState == StepState.SUCCESS || nextState == StepState.RUNNING)
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .weight(0.5f)
                        .padding(bottom = 16.dp)
                        .background(
                            if (lineDone) COUITheme.colorScheme.primary
                            else COUITheme.colorScheme.surfaceContainer
                        )
                )
            }
        }
    }
}
