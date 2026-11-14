package com.pyb110.onekey.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.components.ManagerMark
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 目标管理器选择列表。 */
@Composable
internal fun TargetPickerPanel(
    controller: JailbreakController,
    selected: String,
    onPick: (String) -> Unit,
) {
    val c = oneKeyColors()
    Column(Modifier.fillMaxWidth()) {
        controller.managers.forEach { option ->
            val available = option.installedVersionCode != null && option.certOk
            val isSelected = option.kind == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = available) { onPick(option.kind) }
                    .padding(vertical = 10.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ManagerMark(option.pkg, option.label)
                Column(Modifier.weight(1f)) {
                    Text(
                        option.label,
                        style = COUITheme.textStyles.body1,
                        color = when {
                            !available -> c.disabledInk
                            isSelected -> c.primaryInk
                            else -> c.ink
                        }
                    )
                    Text(
                        when {
                            option.installedVersionCode == null -> "未安装"
                            !option.certOk -> "签名不符"
                            else -> "v" + (option.installedVersion ?: "-") + " · 已安装"
                        },
                        style = COUITheme.textStyles.body2,
                        color = if (available) c.muted else c.error
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) c.primary else Color.Transparent)
                        .then(
                            if (isSelected) Modifier
                            else Modifier.border(1.5.dp, c.disabledInk.copy(alpha = 0.6f), CircleShape)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(COUIIcons.Ok, contentDescription = "已选", tint = c.onPrimary, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}
