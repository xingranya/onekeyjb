package com.pyb110.onekey

import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * 实时成功率评估。
 *
 * 三个物理量决定一次越狱能否成功：
 * - slab 占用：内核 slab 缓存越满，利用原语可用的内存窗口越小；
 * - CPU 温度：过热会触发降频与调度抖动，时序敏感的利用最容易失败；
 * - 系统负载：负载高时内存回收时机不可控，抢占与迁移会打乱时序。
 *
 * 三因子相乘得到百分比，上限 92%——利用本身存在不可控的时序竞争，
 * 任何条件下都不给出 100%。
 */
data class RateResult(
    val valid: Boolean,
    val slab: Int,
    val tempMilliC: Int,
    val load1: Double,
    val slabFactor: Double,
    val tempFactor: Double,
    val loadFactor: Double,
    val score: Double,
    val panicRisk: Double,
    val level: Int,
) {
    val slabText: String get() = if (slab <= 0) "--" else slab.toString()
    val slabState: String get() = when {
        slab <= 0 -> "读不到"
        slab <= 340 -> "理想"
        slab <= 430 -> "良好"
        slab <= 540 -> "偏高"
        slab <= 660 -> "很差"
        else -> "极差"
    }
    val tempText: String get() = if (tempMilliC <= 0) "--" else String.format(Locale.US, "%.1f°C", tempMilliC / 1000.0)
    val tempState: String get() = when {
        tempMilliC <= 0 -> "读不到"
        tempMilliC <= 38000 -> "凉爽"
        tempMilliC <= 43000 -> "正常"
        tempMilliC <= 48000 -> "偏温"
        tempMilliC <= 56000 -> "偏热"
        tempMilliC <= 65000 -> "很热"
        else -> "过热"
    }
    val loadText: String get() = if (load1 < 0) "--" else String.format(Locale.US, "%.1f", load1)
    val loadState: String
        get() {
            if (load1 < 0) return "读不到"
            val per = if (SuccessRate.cores > 0) load1 / SuccessRate.cores else load1
            return when {
                per <= 0.25 -> "空闲"
                per <= 0.5 -> "轻微"
                per <= 1.0 -> "正常"
                per <= 2.0 -> "偏忙"
                per <= 3.5 -> "过载"
                else -> "严重过载"
            }
        }

    /** 一句话建议：先看总分，再看最弱的那个因子。 */
    fun advice(): String {
        if (!valid) return "等待采样…"
        if (level <= 1) return "状态不错，现在跑比较有把握"
        val worst = minOf(slabFactor, tempFactor, loadFactor)
        return when (worst) {
            slabFactor -> "瓶颈是内存占用，建议等到 350 以下再跑"
            tempFactor -> "瓶颈是 CPU 温度，降温后再跑更稳"
            else -> "瓶颈是系统负载，等它降下来更稳"
        }
    }
}

object SuccessRate {
    /** 理论上限：利用存在时序竞争，永不给 100%。 */
    const val CEILING = 92.0

    /** 低于该值建议先别跑。 */
    const val RECOMMEND_MIN = 55.0

    /** slab 理想上限（posix_timers_cache 活跃对象数）。 */
    const val SLAB_IDEAL = 310

    /** 采样间隔。 */
    const val SAMPLE_MS = 1000L

    val cores: Int get() = Runtime.getRuntime().availableProcessors()

    fun evaluate(slab: Int, tempMilliC: Int, load1: Double, coreCount: Int = cores): RateResult {
        val sf = slabFactor(slab)
        val tf = tempFactor(tempMilliC)
        val lf = loadFactor(load1, coreCount)
        val score = (sf * CEILING * tf * lf).coerceIn(0.0, CEILING)
        val valid = slab > 0 || tempMilliC > 0 || load1 >= 0
        return RateResult(
            valid = valid,
            slab = slab,
            tempMilliC = tempMilliC,
            load1 = load1,
            slabFactor = sf,
            tempFactor = tf,
            loadFactor = lf,
            score = score,
            panicRisk = panicRisk(slab, tempMilliC),
            level = level(score),
        )
    }

    fun level(score: Double): Int = when {
        score >= 70 -> 0
        score >= RECOMMEND_MIN -> 1
        score >= 35 -> 2
        score >= 15 -> 3
        else -> 4
    }

    fun slabFactor(i: Int): Double = when {
        i <= 0 -> 0.5
        i <= 340 -> 1.0
        i <= 430 -> lerp(i.toDouble(), 340.0, 430.0, 1.0, 0.78)
        i <= 540 -> lerp(i.toDouble(), 430.0, 540.0, 0.78, 0.42)
        i <= 660 -> lerp(i.toDouble(), 540.0, 660.0, 0.42, 0.15)
        i <= 900 -> lerp(i.toDouble(), 660.0, 900.0, 0.15, 0.03)
        else -> 0.02
    }

    fun tempFactor(i: Int): Double = when {
        i <= 0 -> 0.8
        i <= 38000 -> 1.0
        i <= 43000 -> lerp(i.toDouble(), 38000.0, 43000.0, 1.0, 0.92)
        i <= 48000 -> lerp(i.toDouble(), 43000.0, 48000.0, 0.92, 0.78)
        i <= 56000 -> lerp(i.toDouble(), 48000.0, 56000.0, 0.78, 0.58)
        i <= 65000 -> lerp(i.toDouble(), 56000.0, 65000.0, 0.58, 0.32)
        else -> 0.12
    }

    fun loadFactor(d: Double, cores: Int): Double {
        if (d < 0) return 1.0
        val per = if (cores > 0) d / cores else d
        return when {
            per <= 1.0 -> 1.0
            per <= 2.0 -> lerp(per, 1.0, 2.0, 1.0, 0.96)
            per <= 3.5 -> lerp(per, 2.0, 3.5, 0.96, 0.9)
            per <= 6.0 -> lerp(per, 3.5, 6.0, 0.9, 0.82)
            else -> 0.72
        }
    }

    /** 崩溃风险：slab 与温度各自独立地抬高内核 panic 概率。 */
    fun panicRisk(slab: Int, tempMilliC: Int): Double {
        var r = when {
            slab <= 0 -> 15.0
            slab <= 430 -> 8.0
            slab <= 540 -> 20.0
            slab <= 660 -> 45.0
            else -> 72.0
        }
        if (tempMilliC > 60000) r += 10.0
        else if (tempMilliC > 50000) r += 4.0
        return min(95.0, r)
    }

    private fun lerp(d: Double, d2: Double, d3: Double, d4: Double, d5: Double): Double {
        if (d3 <= d2) return d4
        val t = ((d - d2) / (d3 - d2)).coerceIn(0.0, 1.0)
        return d4 + (d5 - d4) * t
    }

    // ---------------------------------------------------------------- 采样

    /** 读 /proc/slabinfo 里 posix_timers_cache 的活跃对象数，失败返回 -1。 */
    fun readSlab(): Int = try {
        File("/proc/slabinfo").useLines { seq ->
            var found = -1
            for (line in seq) {
                if (line.startsWith("posix_timers_cache")) {
                    val f = line.trim().split(Regex("\\s+"))
                    if (f.size >= 2) found = f[1].toIntOrNull() ?: -1
                    break
                }
            }
            found
        }
    } catch (_: Throwable) {
        -1
    }

    /** 读 /proc/loadavg 第一字段（1 分钟平均负载），失败返回 -1。 */
    fun readLoad1(): Double = try {
        File("/proc/loadavg").readText().trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull() ?: -1.0
    } catch (_: Throwable) {
        -1.0
    }

    /** 读 thermal_zone0 温度（毫摄氏度），失败返回 -1。 */
    fun readTempMilli(): Int = try {
        File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toIntOrNull() ?: -1
    } catch (_: Throwable) {
        -1
    }

    /** 采一次样。 */
    fun sample(): RateResult = evaluate(readSlab(), readTempMilli(), readLoad1(), cores)
}
