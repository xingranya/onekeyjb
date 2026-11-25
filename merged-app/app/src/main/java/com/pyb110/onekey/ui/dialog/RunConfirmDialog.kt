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
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.shape.CouiShape
import com.pyb110.onekey.RateResult
import com.pyb110.onekey.ui.components.DialogAction
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.theme.COUIIcons
import com.pyb110.onekey.ui.theme.COUITheme
import com.pyb110.onekey.ui.theme.Text
import com.pyb110.onekey.ui.theme.WindowDialog

/**
 * 运行前确认。
 *
 * 成功率偏低时把三个瓶颈量摊开讲清楚，让用户自己决定现在跑还是等一等；
 * 成功率正常时只给一句常规提示，不打扰。
 */
@Composable
internal fun RunConfirmDialog(rate: RateResult, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = oneKeyColors()
    val low = rate.valid && rate.score < 55.0
    WindowDialog(
        title = if (low) "现在跑成功率偏低" else "98260 运行前确认",
        show = true,
        onDismissRequest = { onDismiss() },
        actions = listOf(
            DialogAction("取消", COUIIcons.Close) { onDismiss() },
            DialogAction(if (low) "仍然开始" else "确认开始", COUIIcons.Play, primary = true) { onConfirm() },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = CouiDialogDefaults.contentPadding)) {
            if (low) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(CouiShape(12.dp, 2f))
                        .background(c.warningSoft)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        String.format(java.util.Locale.US, "%.0f%%", rate.score),
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
                Text(
                    "内存占用 " + rate.slabText + "（" + rate.slabState + "）· CPU " + rate.tempText +
                        "（" + rate.tempState + "）· 负载 " + rate.loadText + "（" + rate.loadState + "）",
                    style = COUITheme.textStyles.body2,
                    fontSize = 11.sp,
                    color = c.muted
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "现在跑有一定概率让内核进入不稳定状态，之后需要重启手机才能重试。",
                    style = COUITheme.textStyles.body2,
                    fontSize = 11.sp,
                    color = c.muted
                )
            } else {
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
