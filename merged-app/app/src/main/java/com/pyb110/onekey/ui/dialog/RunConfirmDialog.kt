package com.pyb110.onekey.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.shape.CouiShape
import com.pyb110.onekey.BootClass
import com.pyb110.onekey.RateResult
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.components.DialogInset
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.theme.COUIIcons
import com.pyb110.onekey.ui.theme.COUITheme
import com.pyb110.onekey.ui.theme.Text
import com.pyb110.onekey.ui.theme.WindowDialog

/**
 * 运行前确认。
 *
 * 拦一次的条件不再是"分数低"，而是"概率低"或"同一开机内重复运行"——
 * 后者是实测里最容易失败的情形（内核 slab 已被上一次跑脏），
 * 但它在环境读数上完全看不出来，只能靠 boot 桶判定。
 */
@Composable
internal fun RunConfirmDialog(
    show: Boolean = true,
    rate: RateResult,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = oneKeyColors()
    val low = rate.valid && rate.displayable && rate.probability < com.pyb110.onekey.SuccessRate.RECOMMEND_MIN
    val rerun = rate.valid && rate.bootClass == BootClass.COLD_RERUN
    val block = low || rerun
    WindowDialog(
        title = when {
            !rate.valid -> "当前不建议运行"
            rerun -> "本次开机已经跑过"
            low -> "现在跑成功率偏低"
            else -> "98260 运行前确认"
        },
        show = show,
        onDismissRequest = { onDismiss() },
        actions = listOf(
            DialogAction("取消", COUIIcons.Close) { onDismiss() },
            DialogAction(if (block) "仍然开始" else "确认开始", COUIIcons.Play, primary = true) { onConfirm() },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = DialogInset)) {
            when {
                // 硬闸未过：只讲原因，不给数字。给数字等于给假信息。
                !rate.valid -> {
                    rate.blockers.forEach { b ->
                        Text(
                            "· " + b,
                            style = COUITheme.textStyles.body2,
                            fontSize = 12.sp,
                            color = c.warning
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                block -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(CouiShape(12.dp, 2f))
                            .background(c.warningSoft)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (rate.displayable) String.format(java.util.Locale.US, "%.0f%%", rate.probability) else "--",
                            style = COUITheme.textStyles.headline2,
                            fontFamily = FontFamily.Monospace,
                            color = c.warning
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            rate.advice(),
                            style = COUITheme.textStyles.body2,
                            fontSize = 12.sp,
                            color = c.ink
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (rate.displayable) {
                        Text(
                            "区间 " + rate.rangeText + " · 样本 " + rate.samples + " 次尝试 · 环境修正 " +
                                String.format(java.util.Locale.US, "%.2f×", rate.envFactor),
                            style = COUITheme.textStyles.body2,
                            fontSize = 11.sp,
                            color = c.muted
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        rate.bootText + " · 预计 " + rate.attemptsPlanned + " 次尝试 · 单次约 " +
                            String.format(java.util.Locale.US, "%.1f", rate.msPerAttempt / 1000.0) + " 秒 · 预算 " +
                            (rate.budgetMs / 1000L) + " 秒",
                        style = COUITheme.textStyles.body2,
                        fontSize = 11.sp,
                        color = c.muted
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (rerun) "同一开机内重复运行，内核状态已被上一次改变；重启后再跑成功率会明显更高。"
                        else "现在跑有一定概率让内核进入不稳定状态，之后需要重启手机才能重试。",
                        style = COUITheme.textStyles.body2,
                        fontSize = 11.sp,
                        color = c.muted
                    )
                }
                else -> {
                    Text("失败后可直接再点一次；只有内核状态异常时才需要硬重启。", style = COUITheme.textStyles.body2)
                    Text(
                        "执行过程中将短暂提权并向内核装载特权模块。",
                        style = COUITheme.textStyles.body2,
                        color = c.muted,
                        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                    )
                }
            }
        }
    }
}
