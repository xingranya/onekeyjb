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
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect

/** 越狱管理器选择列表。 */
@Composable
internal fun JailManagerPanel(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
) {
    val c = oneKeyColors()
    Column(Modifier.fillMaxWidth()) {
        if (controller.visibleManagers.isEmpty()) {
            Text(
                "未检测到管理器，请先安装列表中的固定版本。",
                style = COUITheme.textStyles.body2,
                color = c.muted,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        controller.visibleManagers.forEach { manager ->
            val selectable = !controller.running && manager.supported &&
                manager.installedVersionCode != null && manager.certOk &&
                (if (controller.adaptiveManagerEnabled) true
                else (manager.kind != "finasu" && manager.installedVersionCode == manager.expectedVersionCode))
            val isSelected = controller.selectedManager == manager.kind
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = selectable) {
                        haptic(CouiHapticEffect.Switch); controller.selectManager(manager.kind)
                    }
                    .padding(vertical = 10.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ManagerMark(manager.pkg, manager.label)
                Column(Modifier.weight(1f)) {
                    Text(
                        manager.label,
                        style = COUITheme.textStyles.body1,
                        color = when {
                            !selectable -> c.disabledInk
                            isSelected -> c.primaryInk
                            else -> c.ink
                        }
                    )
                    Text(
                        manager.statusText,
                        style = COUITheme.textStyles.body2,
                        color = c.muted
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
        Spacer(Modifier.height(12.dp))
        Text(
            "运行前选择管理器。未安装时，请先前往管理器下载。",
            style = COUITheme.textStyles.body2,
            color = c.muted
        )
    }
}
