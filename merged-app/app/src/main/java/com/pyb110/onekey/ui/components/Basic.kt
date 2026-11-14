package com.pyb110.onekey.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.R
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.theme.COUITheme

data class DialogAction(
    val label: String,
    val icon: ImageVector,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
internal fun DialogActions(vararg actions: DialogAction) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            Surface(
                onClick = action.onClick,
                enabled = action.enabled,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (action.primary) COUITheme.colorScheme.primary else COUITheme.colorScheme.surfaceContainer,
                contentColor = if (action.primary) COUITheme.colorScheme.onPrimary else COUITheme.colorScheme.onSurface
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(action.label, style = COUITheme.textStyles.body2)
                }
            }
        }
    }
}

@Composable
internal fun Avatar(size: Int, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.avatar),
        contentDescription = "头像",
        modifier = modifier.size(size.dp).clip(CircleShape)
    )
}

@Composable
internal fun ButtonIconText(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text)
    }
}

/** 抽屉内的操作按钮：主色实心或浅底两种。 */
@Composable
internal fun PanelButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(14.dp),
        enabled = enabled,
        color = if (primary && enabled) COUITheme.colorScheme.primary else COUITheme.colorScheme.surfaceContainer
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = COUITheme.textStyles.body1,
                color = when {
                    primary && enabled -> COUITheme.colorScheme.onPrimary
                    enabled -> COUITheme.colorScheme.primary
                    else -> COUITheme.colorScheme.onSurfaceContainerVariant
                }
            )
        }
    }
}

@Composable
internal fun ManualSection(title: String, lines: List<String>) {
    val index = when {
        title.startsWith("一") -> 1
        title.startsWith("二") -> 2
        title.startsWith("三") -> 3
        title.startsWith("四") -> 4
        title.startsWith("五") -> 5
        else -> null
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp)) {
            if (index != null) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(COUITheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$index", style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = COUITheme.textStyles.body1,
                    color = if (index != null) COUITheme.colorScheme.onSurface else COUITheme.colorScheme.primary
                )
                lines.forEach { line ->
                    Row(Modifier.padding(top = 6.dp)) {
                        Text("·", style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
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
