package com.pyb110.onekey

import android.content.Context
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * 运行样本与成功率后验。
 *
 * 为什么不用「三个物理量加权」：那套分数没有实测校准，任何条件下都不可证伪。
 * 这里的做法是把每次运行的真实 attempt / hit / 耗时落盘，用 Beta 后验给出
 * 单次尝试命中率，再按本次预算能跑几次折算成整体成功率。
 *
 * 先验取值依据（fullchain 仓 docs/150、docs/151、docs/161 的实测）：
 * - 单次 attempt 命中量级 3%；
 * - 每次 attempt 6~8 秒；
 * - 同一开机内第二次运行若没有脏基线保护会掉到 0。
 * 先验 alpha=1 / beta=20 → 均值 4.8%，等价于 21 次伪观测，
 * 真实跑满 50 次 attempt 后先验权重降到 30% 以下。
 */
enum class BootClass {
    /** 本次开机后第一次运行。 */
    COLD_FIRST,

    /** 同一开机内第二次及以后：内核 slab 已被上一次跑脏。 */
    COLD_RERUN,

    /** 上次写入了内核但没拿到 root，必须先冷启动。 */
    DIRTY,
}

/**
 * 一次运行的完整样本。
 *
 * 落盘用 TSV 而不是 JSON：解析不需要 org.json（单元测试里 org.json 是 stub，会直接抛异常），
 * 且字段全是数字与短标识，切 tab 就够，不必引入解析器。
 */
data class RunSample(
    val ts: Long,
    val boot: String,
    val bootClass: BootClass,
    val attempts: Int,
    val hits: Int,
    val msPerAttempt: Long,
    val totalMs: Long,
    val ok: Boolean,
    val failKind: String,
    val budgetMs: Long,
    val procsRunning: Int,
    val load1: Double,
    val tempMilliC: Int,
    val battery: Int,
)

/**
 * 单次尝试命中率的后验估计。
 *
 * [samples] 是 attempt 总数而不是运行次数：Beta 后验的 n 就是伯努利试验次数，
 * 一次运行里跑 6 次 attempt 就等于 6 次观测，不能压成 1。
 */
data class Estimate(
    val pAttempt: Double,
    val pLow: Double,
    val pHigh: Double,
    val samples: Int,
)

object RunStats {
    private const val FILE = "run-stats.tsv"

    /** 只留最近这么多条运行样本，避免文件无限长。 */
    const val MAX_SAMPLES = 200

    /** 先验：均值 1/(1+20) ≈ 4.76%。 */
    const val PRIOR_ALPHA = 1.0
    const val PRIOR_BETA = 20.0

    /** 少于这么多次 attempt 就不显示点值，只显示「样本不足」。 */
    const val MIN_SAMPLES = 5

    /** 重跑桶无样本时，用首次桶均值乘这个系数（依据同 boot 第二次 0/60~0/248 的实测）。 */
    const val RERUN_PENALTY = 0.25

    /** 没有历史样本时的单次耗时假设（docs 实测 6~8 秒，取低端保守）。 */
    const val DEFAULT_MS_PER_ATTEMPT = 6000L

    private const val COLS = 14

    fun priorMean(): Double = PRIOR_ALPHA / (PRIOR_ALPHA + PRIOR_BETA)

    /**
     * 判定本次运行属于哪个桶。
     *
     * 优先级：脏 boot > 同 boot 重跑 > 首次。
     * 脏 boot 是硬闸，交回 DIRTY 让上层拒绝运行，不参与概率计算。
     */
    fun classifyBoot(
        currentBootId: String,
        lastSuccessBootId: String,
        failedBootId: String,
        ranThisBoot: Boolean,
    ): BootClass {
        if (failedBootId.isNotBlank() && currentBootId.isNotBlank() && failedBootId == currentBootId) {
            return BootClass.DIRTY
        }
        // 本次开机已经成功过：再跑就是重跑，且内核模块已加载，成功率叙事完全不同。
        if (ranThisBoot) return BootClass.COLD_RERUN
        if (lastSuccessBootId.isNotBlank() && currentBootId.isNotBlank() &&
            lastSuccessBootId == currentBootId
        ) {
            return BootClass.COLD_RERUN
        }
        return BootClass.COLD_FIRST
    }

    /**
     * Beta 后验。
     *
     * 重跑桶没有自己的样本时，不直接退回先验（那会让重跑看起来和首次一样好），
     * 而是拿首次桶的均值乘 [RERUN_PENALTY]：实测同一开机内第二次运行的命中率
     * 会掉到接近零，把惩罚写进先验比事后解释更有用。
     */
    fun estimate(samples: List<RunSample>, bootClass: BootClass): Estimate {
        val bucket = samples.filter { it.bootClass == bootClass }
        var hits = 0
        var total = 0
        for (s in bucket) {
            hits += s.hits.coerceAtLeast(0)
            total += s.attempts.coerceAtLeast(0)
        }
        if (total <= 0 && bootClass == BootClass.COLD_RERUN) {
            val first = samples.filter { it.bootClass == BootClass.COLD_FIRST }
            val fh = first.sumOf { it.hits.coerceAtLeast(0) }
            val ft = first.sumOf { it.attempts.coerceAtLeast(0) }
            val base = if (ft <= 0) priorMean() else (PRIOR_ALPHA + fh) / (PRIOR_ALPHA + PRIOR_BETA + ft)
            val p = (base * RERUN_PENALTY).coerceIn(0.002, 0.9)
            return Estimate(p, (p * 0.4).coerceAtLeast(0.001), min(0.95, p * 2.2), 0)
        }
        val a = PRIOR_ALPHA + hits
        val b = PRIOR_BETA + (total - hits).coerceAtLeast(0)
        val mean = a / (a + b)
        val lo = betaQuantile(a, b, 0.05)
        val hi = betaQuantile(a, b, 0.95)
        return Estimate(mean, lo, hi, total)
    }

    /** 最近样本的单次耗时中位数；没有样本返回 [DEFAULT_MS_PER_ATTEMPT]。 */
    fun medianMsPerAttempt(samples: List<RunSample>): Long {
        val v = samples.filter { it.msPerAttempt > 0 }.map { it.msPerAttempt }.sorted()
        if (v.isEmpty()) return DEFAULT_MS_PER_ATTEMPT
        return v[v.size / 2]
    }

    // ------------------------------------------------------------ Beta 分位

    /**
     * Beta(a,b) 的 [p] 分位。
     *
     * 用二分而不是 Newton：不完全 Beta 函数在 a 或 b 很小时导数极不稳定，
     * 二分 80 次精度已经远超显示需要（0.01% 量级），且不会发散。
     */
    fun betaQuantile(a: Double, b: Double, p: Double): Double {
        if (a <= 0.0 || b <= 0.0) return p
        if (p <= 0.0) return 0.0
        if (p >= 1.0) return 1.0
        var lo = 0.0
        var hi = 1.0
        repeat(80) {
            val mid = (lo + hi) / 2.0
            if (betaInc(a, b, mid) < p) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }

    /** 正则化不完全 Beta 函数 I_x(a,b)。 */
    fun betaInc(a: Double, b: Double, x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val bt = exp(
            logGamma(a + b) - logGamma(a) - logGamma(b) +
                a * ln(x) + b * ln(1.0 - x)
        )
        return if (x < (a + 1.0) / (a + b + 2.0)) {
            bt * betaCf(a, b, x) / a
        } else {
            1.0 - bt * betaCf(b, a, 1.0 - x) / b
        }
    }

    private fun betaCf(a: Double, b: Double, x: Double): Double {
        val maxIter = 200
        val eps = 3e-12
        val fpMin = 1e-300
        val qab = a + b
        val qap = a + 1.0
        val qam = a - 1.0
        var c = 1.0
        var d = 1.0 - qab * x / qap
        if (abs(d) < fpMin) d = fpMin
        d = 1.0 / d
        var h = d
        for (m in 1..maxIter) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa * d
            if (abs(d) < fpMin) d = fpMin
            c = 1.0 + aa / c
            if (abs(c) < fpMin) c = fpMin
            d = 1.0 / d
            h *= d * c
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa * d
            if (abs(d) < fpMin) d = fpMin
            c = 1.0 + aa / c
            if (abs(c) < fpMin) c = fpMin
            d = 1.0 / d
            val del = d * c
            h *= del
            if (abs(del - 1.0) < eps) break
        }
        return h
    }

    /** Lanczos 近似的 ln Γ(x)。 */
    private fun logGamma(x: Double): Double {
        val c = doubleArrayOf(
            0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905,
            -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
        )
        if (x < 0.5) return ln(PI / sin(PI * x)) - logGamma(1.0 - x)
        val xx = x - 1.0
        var acc = c[0]
        for (i in 1..8) acc += c[i] / (xx + i)
        val t = xx + 7.5
        return 0.5 * ln(2.0 * PI) + (xx + 0.5) * ln(t) - t + ln(acc)
    }

    // ------------------------------------------------------------ 落盘

    fun append(context: Context, sample: RunSample) {
        runCatching { append(File(context.filesDir, FILE), sample) }
    }

    fun append(file: File, sample: RunSample) {
        val all = read(file).toMutableList()
        all.add(sample)
        val kept = if (all.size > MAX_SAMPLES) all.subList(all.size - MAX_SAMPLES, all.size) else all
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(kept.joinToString("\n") { it.toTsv() } + "\n")
        if (!tmp.renameTo(file)) {
            // rename 在某些 filesystem 上跨目录失败，退回直接覆盖。
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun read(context: Context): List<RunSample> = runCatching {
        read(File(context.filesDir, FILE))
    }.getOrDefault(emptyList())

    fun read(file: File): List<RunSample> {
        if (!file.isFile) return emptyList()
        val out = ArrayList<RunSample>()
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val p = line.split('\t')
                // 坏行直接跳过：宁少一条样本，也不要因为一行损坏整份统计失效。
                if (p.size != COLS) return@forEachLine
                val s = runCatching {
                    RunSample(
                        ts = p[0].toLong(),
                        boot = p[1],
                        bootClass = runCatching { BootClass.valueOf(p[2]) }.getOrDefault(BootClass.COLD_FIRST),
                        attempts = p[3].toInt(),
                        hits = p[4].toInt(),
                        msPerAttempt = p[5].toLong(),
                        totalMs = p[6].toLong(),
                        ok = p[7] == "1",
                        failKind = p[8],
                        budgetMs = p[9].toLong(),
                        procsRunning = p[10].toInt(),
                        load1 = p[11].toDouble(),
                        tempMilliC = p[12].toInt(),
                        battery = p[13].toInt(),
                    )
                }.getOrNull()
                if (s != null) out.add(s)
            }
        }
        return out
    }

    private fun RunSample.toTsv(): String = listOf(
        ts.toString(), boot, bootClass.name, attempts.toString(), hits.toString(),
        msPerAttempt.toString(), totalMs.toString(), if (ok) "1" else "0",
        failKind, budgetMs.toString(), procsRunning.toString(),
        load1.toString(), tempMilliC.toString(), battery.toString(),
    ).joinToString("\t")
}
