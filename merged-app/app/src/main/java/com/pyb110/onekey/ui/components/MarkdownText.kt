package com.pyb110.onekey.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.theme.COUITheme
import com.pyb110.onekey.ui.oneKeyColors

/**
 * 轻量 Markdown 渲染：标题、段落、无序列表、表格、分隔线，
 * 行内支持粗体 / 斜体 / 等宽代码 / 可点击链接。
 *
 * 表格在窄屏上按「首列作标题 + 其余列逐行展开」呈现，避免多列挤压不可读。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val c = oneKeyColors()
    val blocks = remember(markdown) { parseBlocks(markdown) }

    @Composable
    fun body(text: String, style: TextStyle, color: Color, mod: Modifier = Modifier) {
        val annotated = inline(text, c.primary, c.primarySoft)
        ClickableText(
            text = annotated,
            style = style.copy(color = color),
            modifier = mod,
            onClick = { offset ->
                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                    onLinkClick?.invoke(it.item)
                }
            },
        )
    }

    Column(modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    Spacer(Modifier.height(if (block.level == 1) 2.dp else 12.dp))
                    body(
                        block.text,
                        when (block.level) {
                            1 -> COUITheme.textStyles.headline1
                            2 -> COUITheme.textStyles.headline2
                            else -> COUITheme.textStyles.subtitle
                        },
                        if (block.level <= 2) c.ink else c.primaryInk,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                is MdBlock.Paragraph -> {
                    body(block.text, COUITheme.textStyles.body2, c.ink)
                    Spacer(Modifier.height(8.dp))
                }

                is MdBlock.Bullets -> {
                    block.items.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            ClickableText(
                                text = AnnotatedString("•  "),
                                style = COUITheme.textStyles.body2.copy(color = c.primary),
                                onClick = {},
                            )
                            body(item, COUITheme.textStyles.body2, c.ink, Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                is MdBlock.Table -> {
                    val header = block.rows.firstOrNull().orEmpty()
                    block.rows.drop(1).forEach { row ->
                        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                            body(row.firstOrNull().orEmpty(), COUITheme.textStyles.body1, c.ink)
                            row.drop(1).forEachIndexed { i, cell ->
                                if (cell.isNotBlank()) {
                                    val label = header.getOrElse(i + 1) { "" }
                                    val merged = buildAnnotatedString {
                                        if (label.isNotBlank()) {
                                            withStyle(SpanStyle(color = c.muted)) { append(label + "：") }
                                        }
                                        append(inline(cell, c.primary, c.primarySoft))
                                    }
                                    ClickableText(
                                        text = merged,
                                        style = COUITheme.textStyles.body2.copy(color = c.ink),
                                        modifier = Modifier.padding(top = 2.dp),
                                        onClick = { offset ->
                                            merged.getStringAnnotations("URL", offset, offset)
                                                .firstOrNull()?.let { onLinkClick?.invoke(it.item) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                MdBlock.Rule -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .height(1.dp)
                            .background(c.outline)
                    )
                }
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val items: List<String>) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private fun parseBlocks(markdown: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    val bullets = mutableListOf<String>()
    val table = mutableListOf<List<String>>()

    fun flushPara() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
        para.setLength(0)
    }
    fun flushBullets() {
        if (bullets.isNotEmpty()) out += MdBlock.Bullets(bullets.toList())
        bullets.clear()
    }
    fun flushTable() {
        if (table.isNotEmpty()) out += MdBlock.Table(table.toList())
        table.clear()
    }
    fun flushAll() { flushPara(); flushBullets(); flushTable() }

    markdown.lines().forEach { raw ->
        val t = raw.trim()
        when {
            t.isEmpty() -> flushAll()

            t.startsWith("|") && t.endsWith("|") && t.length > 2 -> {
                flushPara(); flushBullets()
                val cells = t.trim('|').split("|").map { it.trim() }
                val isSeparator = cells.all { cell ->
                    cell.isNotEmpty() && cell.all { it == '-' || it == ':' || it == ' ' }
                }
                if (!isSeparator) table += cells
            }

            t.startsWith("#") -> {
                flushAll()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 3)
                out += MdBlock.Heading(level, t.dropWhile { it == '#' }.trim())
            }

            t == "---" || t == "***" -> { flushAll(); out += MdBlock.Rule }

            t.startsWith("- ") || t.startsWith("* ") -> {
                flushPara(); flushTable()
                bullets += t.substring(2).trim()
            }

            // 列表项的缩进续行：并入上一条，避免长条目被拆成独立段落
            raw.startsWith("  ") && bullets.isNotEmpty() -> {
                bullets[bullets.lastIndex] = bullets.last() + " " + t
            }

            else -> {
                flushBullets(); flushTable()
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
        }
    }
    flushAll()
    return out
}

/** 行内格式：粗体、斜体、等宽代码、[文字](链接)。 */
private fun inline(text: String, linkColor: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }

            text[i] == '[' -> {
                val close = text.indexOf(']', i + 1)
                val open = if (close > 0) text.indexOf('(', close + 1) else -1
                val shut = if (open == close + 1) text.indexOf(')', open + 1) else -1
                if (close > 0 && open == close + 1 && shut > 0) {
                    pushStringAnnotation("URL", text.substring(open + 1, shut))
                    withStyle(SpanStyle(color = linkColor)) { append(text.substring(i + 1, close)) }
                    pop()
                    i = shut + 1
                } else { append(text[i]); i++ }
            }

            text[i] == '_' && (i == 0 || text[i - 1].isWhitespace()) &&
                i + 1 < text.length && text[i + 1] != ' ' -> {
                val end = text.indexOf('_', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }

            else -> { append(text[i]); i++ }
        }
    }
}
