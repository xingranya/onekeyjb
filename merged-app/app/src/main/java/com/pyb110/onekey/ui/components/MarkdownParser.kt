package com.pyb110.onekey.ui.components

/**
 * Markdown 块级解析：从 MarkdownText 抽出的纯 Kotlin 实现，可直接跑 JVM 单测。
 * 本文件只允许纯 Kotlin，禁止 import Compose / COUI / android 类。
 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val items: List<String>) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

internal fun parseBlocks(markdown: String): List<MdBlock> {
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
