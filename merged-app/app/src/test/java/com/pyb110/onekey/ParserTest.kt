package com.pyb110.onekey

import com.pyb110.onekey.ui.components.MdBlock
import com.pyb110.onekey.ui.components.parseBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 四个纯解析器的行为基线：Markdown 分块、版本号提取、ksud 归一、切核协议状态行。 */
class ParserTest {

    @Test
    fun markdown() {
        val md = """
            | a | b |
            | --- | --- |
            | 1 | 2 |
            ## 标题
            - 条目
              缩进续行
        """.trimIndent()
        val blocks = parseBlocks(md)
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("a", "b"), table.rows[0])
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), table.rows)
        assertTrue(table.rows.none { row -> row.all { cell -> cell.all { it == '-' } } })
        assertEquals(2, blocks.filterIsInstance<MdBlock.Heading>().single().level)
        assertEquals("标题", blocks.filterIsInstance<MdBlock.Heading>().single().text)
        assertEquals(listOf("条目 缩进续行"), blocks.filterIsInstance<MdBlock.Bullets>().single().items)
        assertTrue(blocks.none { it is MdBlock.Paragraph })
    }

    @Test
    fun versionCode() {
        assertEquals(35171, ManagerDownloads.versionCodeOf("ReSukiSU_v4.2.0-rc3_35171-arm64-v8a-release.apk"))
        assertEquals(33194, ManagerDownloads.versionCodeOf("FinaSU_v1.6.0_33194-release.apk"))
        assertNull(ManagerDownloads.versionCodeOf("no-version-here.apk"))
    }

    @Test
    fun ksudNormalize() {
        assertEquals(KsudNormalizer.Result.Exists, KsudNormalizer.parse("EXISTS"))
        val materialized = KsudNormalizer.parse("MATERIALIZED:libtqqfthevwb.so")
        assertTrue(materialized is KsudNormalizer.Result.Materialized)
        assertEquals("libtqqfthevwb.so", (materialized as KsudNormalizer.Result.Materialized).source)
        assertEquals(KsudNormalizer.Result.Missing, KsudNormalizer.parse("KSUD_MISSING"))
    }

    @Test
    fun switchStatus() {
        val status = ManagerSwitchProtocol.parse("me.weishu.kernelsu 10231 32680 7\nsu: request rejected")
        assertEquals(ManagerSwitchProtocol.Status("me.weishu.kernelsu", "10231", 32680L, 7L), status)
        assertNull(ManagerSwitchProtocol.parse("su: request rejected"))
        assertNull(ManagerSwitchProtocol.parse("me.weishu.kernelsu 10231 0 7"))
    }
}
