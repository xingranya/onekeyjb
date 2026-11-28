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

    // ------------------------------------------------------------ 成功率模型

    private fun sample(
        bootClass: BootClass,
        attempts: Int,
        hits: Int,
        ms: Long = 6000L,
    ) = RunSample(
        ts = 0L,
        boot = "a1b2c3d4",
        bootClass = bootClass,
        attempts = attempts,
        hits = hits,
        msPerAttempt = ms,
        totalMs = ms * attempts,
        ok = hits > 0,
        failKind = "",
        budgetMs = 300_000L,
        procsRunning = 14,
        load1 = 2.0,
        tempMilliC = 41000,
        battery = 70,
    )

    @Test
    fun estimateUsesBetaPosterior() {
        // 无样本：退回先验均值 1/(1+20) ≈ 4.76%
        val none = RunStats.estimate(emptyList(), BootClass.COLD_FIRST)
        assertEquals(RunStats.priorMean(), none.pAttempt, 1e-9)
        assertEquals(0, none.samples)

        // 21 次全失败：后验 (1+0)/(1+20+21) = 1/42 ≈ 2.38%
        val allFail = RunStats.estimate(listOf(sample(BootClass.COLD_FIRST, 21, 0)), BootClass.COLD_FIRST)
        assertEquals(1.0 / 42.0, allFail.pAttempt, 1e-9)
        assertEquals(21, allFail.samples)

        // 21 次全命中：后验 (1+21)/(1+20+21) = 22/42 ≈ 52.4%
        val allHit = RunStats.estimate(listOf(sample(BootClass.COLD_FIRST, 21, 21)), BootClass.COLD_FIRST)
        assertEquals(22.0 / 42.0, allHit.pAttempt, 1e-9)
    }

    @Test
    fun estimateBucketsAreIndependent() {
        // 首次桶 100 次全失败，重跑桶 20 次全命中：两桶互不污染
        val samples = listOf(
            sample(BootClass.COLD_FIRST, 100, 0),
            sample(BootClass.COLD_RERUN, 20, 20),
        )
        val first = RunStats.estimate(samples, BootClass.COLD_FIRST)
        val rerun = RunStats.estimate(samples, BootClass.COLD_RERUN)
        assertTrue(first.pAttempt < 0.05)
        assertTrue(rerun.pAttempt > 0.5)
    }

    @Test
    fun rerunPenaltyAppliesWithoutOwnSamples() {
        // 重跑桶没有样本时，不退回先验，而是首次桶均值 × 0.25
        val samples = listOf(sample(BootClass.COLD_FIRST, 40, 8))
        val first = RunStats.estimate(samples, BootClass.COLD_FIRST)
        val rerun = RunStats.estimate(samples, BootClass.COLD_RERUN)
        assertEquals(first.pAttempt * RunStats.RERUN_PENALTY, rerun.pAttempt, 1e-9)
        assertEquals(0, rerun.samples)
    }

    @Test
    fun betaQuantileBracketsMean() {
        // Beta(2,10) 的中位数应落在 5% 与 95% 分位之间，且分位单调
        val lo = RunStats.betaQuantile(2.0, 10.0, 0.05)
        val mid = RunStats.betaQuantile(2.0, 10.0, 0.5)
        val hi = RunStats.betaQuantile(2.0, 10.0, 0.95)
        assertTrue(lo < mid)
        assertTrue(mid < hi)
        // 均值 2/12 ≈ 0.1667，中位数应在其附近
        assertTrue(mid > 0.05 && mid < 0.30)
    }

    @Test
    fun probabilityNeverExceedsCeiling() {
        // 单次 90% 命中、跑 20 次：1-0.1^20 ≈ 100%，但必须被 92 截断
        assertEquals(SuccessRate.CEILING, SuccessRate.runProbability(0.9, 20), 1e-9)
        // 单次 5%、跑 6 次：1-0.95^6 ≈ 26.5%
        assertEquals(26.49, SuccessRate.runProbability(0.05, 6), 0.02)
        // k=0 不产生概率
        assertEquals(0.0, SuccessRate.runProbability(0.5, 0), 1e-9)
    }

    @Test
    fun probabilityMonotonicInK() {
        var prev = -1.0
        for (k in 1..10) {
            val p = SuccessRate.runProbability(0.03, k)
            assertTrue("k=" + k + " 应严格递增", p > prev)
            prev = p
        }
    }

    @Test
    fun attemptsPlannedRespectsBudgetAndAbort() {
        // 预算 300 秒、单次 6 秒 → 50 次，但被中止阈值 6 封顶
        assertEquals(SuccessRate.ABORT_AFTER, SuccessRate.attemptsPlanned(300_000L, 6_000L))
        // 预算 30 秒、单次 6 秒 → 5 次，低于中止阈值
        assertEquals(5, SuccessRate.attemptsPlanned(30_000L, 6_000L))
        // 预算再小也至少给 1 次
        assertEquals(1, SuccessRate.attemptsPlanned(1_000L, 6_000L))
    }

    @Test
    fun budgetForKernel() {
        // 6.18 走 300 秒，6.12 走 180 秒：引擎按 --auto240 设计，旧实现写死 90 秒是错的
        assertEquals(300_000L, SuccessRate.budgetFor("android17-6.18"))
        assertEquals(180_000L, SuccessRate.budgetFor("android16-6.12"))
        assertEquals(300_000L, SuccessRate.budgetFor(""))
    }

    @Test
    fun envFactorPenalizesBothExtremes() {
        // 太静是扣分项：实测 8 候选第 10 次命中，1 候选 212 次零命中
        assertEquals(0.75, SuccessRate.envFactor(4, 41000), 1e-9)
        // 合适区间是满分
        assertEquals(1.0, SuccessRate.envFactor(14, 41000), 1e-9)
        // 过热扣分
        assertEquals(0.80, SuccessRate.envFactor(14, 60000), 1e-9)
        // 乘积被锁在 [0.70, 1.10]
        assertTrue(SuccessRate.envFactor(4, 60000) >= SuccessRate.ENV_MIN)
        assertTrue(SuccessRate.envFactor(14, 41000) <= SuccessRate.ENV_MAX)
        // 读不到不惩罚
        assertEquals(1.0, SuccessRate.envFactor(-1, -1), 1e-9)
    }

    @Test
    fun classifyBoot() {
        assertEquals(BootClass.DIRTY, RunStats.classifyBoot("b1", "", "b1", false))
        assertEquals(BootClass.COLD_RERUN, RunStats.classifyBoot("b1", "", "", true))
        assertEquals(BootClass.COLD_RERUN, RunStats.classifyBoot("b1", "b1", "", false))
        assertEquals(BootClass.COLD_FIRST, RunStats.classifyBoot("b1", "b0", "", false))
        // 脏 boot 的优先级高于重跑
        assertEquals(BootClass.DIRTY, RunStats.classifyBoot("b1", "b1", "b1", true))
    }

    @Test
    fun samplesBelowFiveAreNotDisplayable() {
        val env = Env(300, 41000, 2.0, 14, 70, 8)
        val four = listOf(sample(BootClass.COLD_FIRST, 4, 1))
        val r4 = SuccessRate.evaluate(env, BootClass.COLD_FIRST, four, 300_000L, true)
        assertFalse(r4.displayable)
        assertEquals("样本不足（n=4）", r4.rangeText)

        val five = listOf(sample(BootClass.COLD_FIRST, 5, 1))
        val r5 = SuccessRate.evaluate(env, BootClass.COLD_FIRST, five, 300_000L, true)
        assertTrue(r5.displayable)
        assertTrue(r5.rangeText.contains("~"))
    }

    @Test
    fun blockersSuppressEverything() {
        val env = Env(300, 41000, 2.0, 14, 10, 8) // 电量 10%
        val samples = listOf(sample(BootClass.COLD_FIRST, 20, 2))
        val r = SuccessRate.evaluate(env, BootClass.COLD_FIRST, samples, 300_000L, true)
        assertFalse(r.valid)
        assertFalse(r.displayable)
        assertTrue(r.blockers.isNotEmpty())
        assertTrue(r.blockers.first().contains("电量"))

        // 脏 boot 也是硬闸
        val dirty = SuccessRate.evaluate(env.copy(battery = 80), BootClass.DIRTY, samples, 300_000L, true)
        assertFalse(dirty.valid)
        assertTrue(dirty.blockers.first().contains("重启"))
    }

    @Test
    fun telemetryParseUafLine() {
        var acc = EngineTelemetry()
        acc = EngineTelemetryParser.parse("UAF mode=tile ms=558 rc=0", acc)
        assertEquals(1, acc.attempts)
        assertEquals(1, acc.hits)
        assertEquals(558L, acc.avgMs)
        assertEquals(558L, acc.lastMs)
        assertTrue(acc.valid)

        acc = EngineTelemetryParser.parse("UAF mode=tile ms=538 rc=-1", acc)
        assertEquals(2, acc.attempts)
        assertEquals(1, acc.hits)
        assertEquals((558L + 538L) / 2, acc.avgMs)
    }

    @Test
    fun telemetryParseFailAndBudget() {
        var acc = EngineTelemetry()
        acc = EngineTelemetryParser.parse("FAIL=cred REASON=no-slide T=1234ms", acc)
        assertEquals("cred", acc.failKind)
        assertEquals(0, acc.attempts)

        acc = EngineTelemetryParser.parse("SLIDE=auto BUDGET=240s", acc)
        assertEquals(240_000L, acc.budgetMs)

        acc = EngineTelemetryParser.parse("SLIDE=0x7f00 BUDGET=180s", acc)
        assertEquals(180_000L, acc.budgetMs)
    }

    @Test
    fun telemetryLeakTaskOverridesCount() {
        // 引擎自报的 attempt 序号比逐行数 rc 更权威
        var acc = EngineTelemetry()
        acc = EngineTelemetryParser.parse("LEAK_TASK ptr=ffff8880 attempt=5 child=1", acc)
        assertEquals(5, acc.attempts)

        // 后续 UAF 行不该把序号退回去
        acc = EngineTelemetryParser.parse("UAF mode=tile ms=600 rc=0", acc)
        assertEquals(6, acc.attempts)
        assertEquals(1, acc.hits)
    }

    @Test
    fun telemetryIgnoresNoise() {
        val noise = listOf(
            "[1/4] run plant (in-process preflight + auto slide)",
            "MODULE_LOADED",
            "SU_ID_RC=0",
            "DONE",
            "",
        )
        val acc = EngineTelemetryParser.parseAll(noise)
        assertEquals(0, acc.attempts)
        assertEquals(0, acc.hits)
        assertEquals(0L, acc.budgetMs)
        assertFalse(acc.valid)
    }

    @Test
    fun sampleRoundTripThroughTsv() {
        // TSV 落盘必须无损往返：字段顺序错一位，统计就会静默错
        val dir = java.nio.file.Files.createTempDirectory("runstats").toFile()
        val f = java.io.File(dir, "run-stats.tsv")
        val s = sample(BootClass.COLD_RERUN, 7, 2, 6200L)
        RunStats.append(f, s)
        val back = RunStats.read(f)
        assertEquals(1, back.size)
        assertEquals(s.attempts, back[0].attempts)
        assertEquals(s.hits, back[0].hits)
        assertEquals(s.msPerAttempt, back[0].msPerAttempt)
        assertEquals(BootClass.COLD_RERUN, back[0].bootClass)
        assertEquals(s.battery, back[0].battery)
        assertEquals(s.load1, back[0].load1, 1e-9)
        dir.deleteRecursively()
    }

    @Test
    fun medianMsPerAttempt() {
        assertEquals(RunStats.DEFAULT_MS_PER_ATTEMPT, RunStats.medianMsPerAttempt(emptyList()))
        val v = listOf(
            sample(BootClass.COLD_FIRST, 1, 0, 5000L),
            sample(BootClass.COLD_FIRST, 1, 0, 6000L),
            sample(BootClass.COLD_FIRST, 1, 0, 7000L),
        )
        assertEquals(6000L, RunStats.medianMsPerAttempt(v))
    }
}
