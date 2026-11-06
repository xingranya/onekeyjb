package com.pyb110.onekey.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.DividerDefaults
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 分组列表里的一行。整行交给 COUI 的 [BasicComponent] 渲染，不自己画布局。
 *
 * @param title 主标题
 * @param summary 副标题，null 时不占位
 * @param icon 左侧图标
 * @param end 右侧自定义内容（开关、标签等）
 * @param arrow 是否显示右箭头
 * @param enabled 为 false 时置灰且不可点
 * @param onClick 点击回调，null 表示不可点
 * @param iconTint 图标颜色，null 时用主题主色
 */
data class ListRow(
    val title: String,
    val summary: String? = null,
    val icon: ImageVector? = null,
    val end: (@Composable () -> Unit)? = null,
    val arrow: Boolean = false,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null,
    val iconTint: Color? = null,
)

/** 页面左右边距，与 COUI 设置页同一档。 */
val PageMargin = 12.dp

/**
 * 一张分组卡片：行之间自动插入分隔线，首末行由 [Card] 自身圆角收口。
 *
 * 行数由 [rows] 决定，调用方不需要自己数、也不需要自己插分隔线。
 */
@Composable
fun ListCard(
    rows: List<ListRow>,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    val hasIcon = rows.any { it.icon != null }
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = PageMargin)) {
        Column(Modifier.fillMaxWidth()) {
            rows.forEachIndexed { index, row ->
                BasicComponent(
                    title = row.title,
                    summary = row.summary,
                    enabled = row.enabled,
                    startAction = row.icon?.let { icon ->
                        {
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 14.dp),
                                tint = row.iconTint ?: COUITheme.colorScheme.primary,
                            )
                        }
                    },
                    endActions = when {
                        row.end != null -> { { row.end.invoke() } }
                        row.arrow -> {
                            {
                                Icon(
                                    COUIIcons.ChevronForward,
                                    contentDescription = null,
                                    tint = COUITheme.colorScheme.onSurfaceContainerVariant,
                                )
                            }
                        }
                        else -> null
                    },
                    onClick = row.onClick,
                )
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = if (hasIcon) 52.dp else DividerDefaults.CardInset, end = DividerDefaults.CardInset),
                    )
                }
            }
        }
    }
}

/** 分组标题：与卡片左边缘对齐，位于卡片之外。 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PageMargin + 4.dp, end = PageMargin + 4.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = COUITheme.textStyles.body1,
            color = COUITheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}