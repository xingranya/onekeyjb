package com.pyb110.onekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志标准化与成功率的纯逻辑基线。
 *
 * 这两块都是"看起来只是显示"的代码，但一旦解析错，历史页的等级、
 * 成功率的分母、引擎事件的归因会一起错，所以钉死行为。
 */
class RunLogTest {

    @Test
    fun formatRoundTrip() {
        // 落盘按 0.1s 精度，回读得到的是格式化后的值；用整秒值验证无损往返。
        val e = LogEntry("12:34:56", LogLevel.WARN, "系统负载偏高", "3.8", 1200L)
        val line = e.format()
        assertEquals("12:34:56  [警告] 系统负载偏高 · 3.8 · 1.2s", line)

        val back = RunLog.parse(line)
        assertEquals("12:34:56", back.time)
        assertEquals(LogLevel.WARN, back.level)
        assertEquals("系统负载偏高", back.title)
        assertEquals("3.8", back.detail)
        assertEquals(1200L, back.ms)

        // 亚秒耗时走毫秒分支，可精确往返
        val short = LogEntry("12:34:56", LogLevel.INFO, "加载内核模块", "", 812L)
        assertEquals(812L, RunLog.parse(short.format()).ms)
    }

    @Test
    fun formatWithoutDetailOrDuration() {
        assertEquals("01:02:03  [信息] 开始越狱", LogEntry("01:02:03", LogLevel.INFO, "开始越狱").format())
    }

    @Test
    fun durationUnits() {
        assertEquals("12ms", RunLog.duration(12L))
        assertEquals("999ms", RunLog.duration(999L))
        assertEquals("1.0s", RunLog.duration(1000L))
        assertEquals("12.3s", RunLog.duration(12345L))
    }

    @Test
    fun parseLegacyLineFallsBackToKeywords() {
        val e = RunLog.parse("09:00:00  加载器归一化失败：找不到 ksud")
        assertEquals("09:00:00", e.time)
        assertEquals(LogLevel.ERROR, e.level)

        val ok = RunLog.parse("09:00:01  MODULE_LOADED")
        assertEquals(LogLevel.SUCCESS, ok.level)

        val plain = RunLog.parse("09:00:02  正在读取管理器 APK")
        assertEquals(LogLevel.INFO, plain.level)
    }

    @Test
    fun classifyCoversEveryLevel() {
        assertEquals(LogLevel.ERROR, RunLog.classify("连续 4 次尝试未成功"))
        assertEquals(LogLevel.ERROR, RunLog.classify("FAIL=plantresultmodule"))
        assertEquals(LogLevel.ERROR, RunLog.classify("KSUD_MISSING"))
        assertEquals(LogLevel.WARN, RunLog.classify("未开启「越狱后软重启」"))
        assertEquals(LogLevel.WARN, RunLog.classify("SUSFS 已跳过"))
        assertEquals(LogLevel.SUCCESS, RunLog.classify("98260 全链完成"))
        assertEquals(LogLevel.INFO, RunLog.classify("开始采样"))
    }

    /**
     * 真实日志行的归类回归。
     *
     * 这些句子全都来自项目里的 addLog 调用点，中文否定式与肯定式共用词根，
     * 任何一处顺序写错都会把失败显示成成功——这条测试就是防止那种回归。
     */
    @Test
    fun classifyRealLogLines() {
        // 否定式：含「成功」但实际失败
        assertEquals(LogLevel.ERROR, RunLog.classify("连续 4 次尝试未成功"))
        assertEquals(LogLevel.WARN, RunLog.classify("第 2 次尝试未命中，继续重试"))
        assertEquals(LogLevel.ERROR, RunLog.classify("98260 异常: java.io.IOException"))
        assertEquals(LogLevel.ERROR, RunLog.classify("管理器未安装"))
        assertEquals(LogLevel.ERROR, RunLog.classify("当前选择的管理器未安装或版本不匹配，已拒绝开始"))
        assertEquals(LogLevel.ERROR, RunLog.classify("Stellar 未运行：请先打开 Stellar"))
        assertEquals(LogLevel.ERROR, RunLog.classify("没有可准备的管理器"))
        assertEquals(LogLevel.ERROR, RunLog.classify("加载器归一化：未在管理器 APK 里找到 ksud"))

        // 特例：含「未」但结论良好
        assertEquals(LogLevel.SUCCESS, RunLog.classify("未发现已知冲突模块；可以正常越狱。"))

        // 未完成类
        assertEquals(LogLevel.WARN, RunLog.classify("98260 未完成（已写内核，需重启后再试）"))
        assertEquals(LogLevel.WARN, RunLog.classify("未开启「越狱后软重启」"))
        assertEquals(LogLevel.WARN, RunLog.classify("当前任务运行中，暂不修复截屏"))

        // 被拦下的操作、断开、需要用户先做点什么：都不是故障，但都该被看见
        assertEquals(LogLevel.WARN, RunLog.classify("运行中不能切换管理器"))
        assertEquals(LogLevel.WARN, RunLog.classify("运行中不能重启"))
        assertEquals(LogLevel.WARN, RunLog.classify("正在切换管理器，请稍后再试"))
        assertEquals(LogLevel.WARN, RunLog.classify("Stellar 服务已断开"))
        assertEquals(LogLevel.WARN, RunLog.classify("请先打开目标管理器：com.example"))

        // 冲突自检：同一句的词序反转就是相反结论
        assertEquals(LogLevel.ERROR, RunLog.classify("发现已知冲突模块: 98260-hide"))
        assertEquals(LogLevel.SUCCESS, RunLog.classify("未发现已知冲突模块；可以正常越狱。"))

        // 肯定式
        assertEquals(LogLevel.SUCCESS, RunLog.classify("98260 全链完成；重启后需要重新越狱。"))
        assertEquals(LogLevel.SUCCESS, RunLog.classify("加载器归一化：已用 libksud.so 补齐"))
        assertEquals(LogLevel.SUCCESS, RunLog.classify("Stellar 权限已授予"))
    }

    @Test
    fun parseEventLine() {
        val e = RunLog.fromEvent("EV|v=1|phase=post|step=susfs|status=ok|scope=cap:susfs|apply=1|ms=812|detail=loaded")
        assertNotNull(e)
        assertEquals(LogLevel.SUCCESS, e!!.level)
        assertEquals("加载 SUSFS", e.title)
        assertEquals("loaded", e.detail)
        assertEquals(812L, e.ms)
    }

    @Test
    fun parseEventSkipAndFail() {
        val skip = RunLog.fromEvent("EV|v=1|phase=post|step=antiroot|status=skip|scope=cap:antiroot|apply=0|detail=not applicable")
        assertEquals(LogLevel.INFO, skip!!.level)
        assertEquals("处理安全弹窗", skip.title)
        assertEquals("已跳过", skip.detail)

        val fail = RunLog.fromEvent("EV|v=1|phase=plant|step=result|status=fail|scope=core|apply=1|code=-1|detail=UAF mode=tile")
        assertEquals(LogLevel.ERROR, fail!!.level)
        assertEquals("布置利用原语", fail.title)
        assertTrue(fail.detail.contains("返回码 -1"))
        assertTrue(fail.detail.contains("UAF mode=tile"))
    }

    @Test
    fun parseEventEscapedDetail() {
        val e = RunLog.fromEvent("EV|v=1|phase=diag|step=dump|status=warn|scope=core|apply=1|detail=buffered%7C24B")
        assertNotNull(e)
        assertEquals("诊断缓冲已导出", e!!.title)
        assertEquals("buffered|24B", e.detail)
    }

    @Test
    fun nonEventLineReturnsNull() {
        assertNull(RunLog.fromEvent("[1/4] wait for late-load"))
        assertNull(RunLog.fromEvent("MODULE_LOADED"))
    }

    @Test
    fun engineHumanLines() {
        assertEquals("布置利用原语", RunLog.fromEngine("[1/4] push payload").title)
        assertEquals(LogLevel.SUCCESS, RunLog.fromEngine("MODULE_LOADED").level)
        assertEquals(LogLevel.SUCCESS, RunLog.fromEngine("DONE").level)

        val fail = RunLog.fromEngine("FAIL=plantresultmodule")
        assertEquals(LogLevel.ERROR, fail.level)
        assertTrue(fail.detail.isNotBlank())
    }

    @Test
    fun successRateFactors() {
        // 理想条件：slab 300、35℃、空载 → 只受上限约束
        val ideal = SuccessRate.evaluate(300, 35000, 0.5, 8)
        assertEquals(SuccessRate.CEILING, ideal.score, 0.001)
        assertEquals(0, ideal.level)

        // slab 极差 + 过热 + 过载 → 分档到底
        val bad = SuccessRate.evaluate(950, 70000, 20.0, 8)
        assertTrue(bad.score < 5.0)
        assertEquals(4, bad.level)

        // 三个物理量全读不到：valid=false，界面显示「等待采样」而不是一个假百分比
        val none = SuccessRate.evaluate(-1, -1, -1.0, 8)
        assertFalse(none.valid)
        assertEquals("等待采样…", none.advice())

        // 只要有一个读到了就算有效采样
        assertTrue(SuccessRate.evaluate(300, -1, -1.0, 8).valid)
    }

    @Test
    fun successRateNeverReachesHundred() {
        for (slab in intArrayOf(1, 100, 300, 340)) {
            for (t in intArrayOf(20000, 30000, 38000)) {
                val r = SuccessRate.evaluate(slab, t, 0.0, 8)
                assertTrue(r.score <= SuccessRate.CEILING)
            }
        }
    }

    @Test
    fun successRateMonotonic() {
        // 同一温度与负载下，slab 越高分越低
        val a = SuccessRate.evaluate(300, 40000, 1.0, 8).score
        val b = SuccessRate.evaluate(500, 40000, 1.0, 8).score
        val c = SuccessRate.evaluate(800, 40000, 1.0, 8).score
        assertTrue(a > b)
        assertTrue(b > c)
    }

    @Test
    fun successRateAdvice() {
        assertEquals("等待采样…", RateResult(false, -1, -1, -1.0, 0.5, 0.5, 0.5, 0.0, 15.0, 4).advice())
        val good = SuccessRate.evaluate(300, 35000, 0.5, 8)
        assertTrue(good.advice().contains("有把握"))
    }

    @Test
    fun panicRiskBounds() {
        // slab 极差 72 分，叠加过热 +10 → 82，仍未触顶
        assertEquals(82.0, SuccessRate.panicRisk(2000, 90000), 0.001)
        // slab 极差 + 过热 60℃ 以上 + 再多也不越 95
        assertTrue(SuccessRate.panicRisk(5000, 120000) <= 95.0)
        assertTrue(SuccessRate.panicRisk(300, 30000) < SuccessRate.panicRisk(700, 30000))
    }
}
