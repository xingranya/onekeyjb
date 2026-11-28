package com.pyb110.onekey

/**
 * 执行引擎的结构化遥测。
 *
 * 引擎每跑一次 attempt 都会打出实测行（`UAF mode=tile ms=558 rc=0`、
 * `RECLAIM_WRITES=4096 HIT=1 base=…`、`LEAK_TASK ptr=… attempt=3`），
 * 但过去这些行被当作普通文本打进日志，成功率拿不到任何真实数据，
 * 只能靠猜。这里把它们抽出来，成为成功率的唯一事实来源。
 *
 * 只解析引擎自己打印的、格式稳定的行；解析不到就保持原值，
 * 绝不用推断填空——推断出来的数会让统计重新变成玄学。
 */
data class EngineTelemetry(
    /** 引擎自报的 attempt 序号最大值，比逐行数 rc 更准。 */
    val attempts: Int = 0,
    /** 命中次数（rc=0 或 HIT=1，同一 attempt 内去重）。 */
    val hits: Int = 0,
    /** 单次 attempt 的累计毫秒与计数，用于求均值。 */
    val msSum: Long = 0L,
    val msCount: Int = 0,
    /** 引擎自报的预算毫秒（来自 `SLIDE=… BUDGET=…s`），0 表示没报。 */
    val budgetMs: Long = 0L,
    /** 失败类型（来自 `FAIL=<kind>`），空串表示没失败。 */
    val failKind: String = "",
    /** 最近一次 attempt 的毫秒数。 */
    val lastMs: Long = 0L,
) {
    val avgMs: Long get() = if (msCount <= 0) 0L else msSum / msCount

    val valid: Boolean get() = attempts > 0 || hits > 0
}

object EngineTelemetryParser {
    /** `UAF mode=tile ms=558 rc=0` */
    private val uafRe = Regex("""UAF mode=(\S+)\s+ms=(\d+)\s+rc=(-?\d+)""")

    /** `RECLAIM_WRITES=4096 HIT=1 base=ffff…` */
    private val reclaimRe = Regex("""RECLAIM_WRITES=(\d+)\s+HIT=(\d+)""")

    /** `LEAK_TASK ptr=… attempt=3 child=…` */
    private val leakRe = Regex("""LEAK_TASK ptr=\S+\s+attempt=(\d+)""")

    /** `SLIDE=0x1 BUDGET=240s` 或 `SLIDE=auto BUDGET=240s` */
    private val budgetRe = Regex("""BUDGET=(\d+)s""")

    /** `FAIL=cred REASON=… T=1234ms` */
    private val failRe = Regex("""FAIL=(\S+)""")

    /**
     * 把一行引擎输出并入累计值。
     *
     * 纯函数：同样的输入必然得到同样的输出，单元测试直接喂字符串即可，
     * 不需要 Android 运行时。
     */
    fun parse(line: String, acc: EngineTelemetry): EngineTelemetry {
        var out = acc

        uafRe.find(line)?.let { m ->
            val ms = m.groupValues[2].toLongOrNull() ?: 0L
            val rc = m.groupValues[3].toIntOrNull() ?: -1
            out = out.copy(
                // 一行 UAF 就是一次 attempt 的完整结论，命中与否都在这里。
                attempts = out.attempts + 1,
                hits = out.hits + if (rc == 0) 1 else 0,
                msSum = out.msSum + ms,
                msCount = out.msCount + 1,
                lastMs = ms,
            )
            return out
        }

        leakRe.find(line)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            // 引擎自报的序号更权威：它会跳过不产出 UAF 行的内部重试。
            if (n > out.attempts) out = out.copy(attempts = n)
            return out
        }

        reclaimRe.find(line)?.let { m ->
            val hit = m.groupValues[2].toIntOrNull() ?: 0
            // HIT=1 是命中的独立证据；与 UAF rc=0 可能同时出现，这里只在
            // 还没有任何 UAF 行时补记，避免同一次命中被算两遍。
            if (hit > 0 && out.attempts == 0) out = out.copy(attempts = 1, hits = 1)
            return out
        }

        budgetRe.find(line)?.let { m ->
            val sec = m.groupValues[1].toLongOrNull() ?: 0L
            if (sec > 0) out = out.copy(budgetMs = sec * 1000L)
            return out
        }

        failRe.find(line)?.let { m ->
            val kind = m.groupValues[1]
            if (kind.isNotBlank()) out = out.copy(failKind = kind)
            return out
        }

        return out
    }

    /** 批量解析一个完整输出块，主要给单元测试与日志回放用。 */
    fun parseAll(lines: Iterable<String>): EngineTelemetry {
        var acc = EngineTelemetry()
        for (l in lines) acc = parse(l, acc)
        return acc
    }
}
