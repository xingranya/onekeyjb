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

    /**
     * 三个物理量的原始读数。-1 表示这一项没读到。
     */
    data class Raw(val slab: Int, val tempMilliC: Int, val load1: Double) {
        val anyValid: Boolean get() = slab > 0 || tempMilliC > 0 || load1 >= 0.0
        fun fill(other: Raw) = Raw(
            if (slab > 0) slab else other.slab,
            if (tempMilliC > 0) tempMilliC else other.tempMilliC,
            if (load1 >= 0.0) load1 else other.load1,
        )
    }

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

    /** 读温度（毫摄氏度），失败返回 -1。逐个 zone 试，第一个读得出合理数值的就用。 */
    fun readTempMilli(): Int {
        for (i in 0..12) {
            val v = try {
                File("/sys/class/thermal/thermal_zone" + i + "/temp").readText().trim().toIntOrNull()
            } catch (_: Throwable) {
                null
            }
            if (v != null && v > 1000 && v < 200000) return v
        }
        return -1
    }

    /** 本进程自己读：普通 App 域会被 SELinux 拦在 proc_loadavg 与 sysfs 之外，读不到就是 -1。 */
    fun readLocal(): Raw = Raw(readSlab(), readTempMilli(), readLoad1())

    /**
     * 借特权通道一次读齐三个量。
     *
     * 普通 App 域（untrusted_app）在真机与模拟器上都被 SELinux 挡住
     * proc_loadavg 与 sysfs，logcat 里能直接看到 avc denied；
     * 所以必须让 Stellar 的 shell 身份去读，把三行带标签的结果回传后解析。
     */
    fun readPrivileged(): Raw {
        if (!StellarShell.available() || !StellarShell.hasPermission()) return Raw(-1, -1, -1.0)
        val script = listOf(
            "grep -m1 posix_timers_cache /proc/slabinfo 2>/dev/null | awk '{print \"SLAB=\" \$2}'",
            "awk '{print \"LOAD=\" \$1}' /proc/loadavg 2>/dev/null",
            // 先试 sysfs：绝大多数机型 thermal_zone0..N 里总有一个可读。
            "for z in /sys/class/thermal/thermal_zone*/temp; do v=\$(cat \$z 2>/dev/null); case \"\$v\" in ''|*[!0-9]*) continue;; esac; echo \"TEMP=\$v\"; break; done",
            // sysfs 全被 SELinux 挡住的机型退到 thermalservice：它给的是摄氏度一位小数，乘 1000 换成毫度。
            "dumpsys thermalservice 2>/dev/null | grep -m1 -o 'mValue=[0-9.]*' | cut -d= -f2 | awk '{ if (\$1+0 > 0) printf \"TEMP=%.0f\\n\", \$1*1000 }'",
        ).joinToString("\n")
        val out = try {
            StellarShell.run(script, 6).output
        } catch (_: Throwable) {
            return Raw(-1, -1, -1.0)
        }
        var slab = -1
        var temp = -1
        var load = -1.0
        for (line in out.lineSequence()) {
            val s = line.trim()
            when {
                s.startsWith("SLAB=") -> slab = s.removePrefix("SLAB=").trim().toIntOrNull() ?: -1
                // 两条温度来源都可能命中，只认第一条（sysfs 优先于 thermalservice）。
                s.startsWith("TEMP=") -> if (temp < 0) {
                    temp = s.removePrefix("TEMP=").trim().toIntOrNull() ?: -1
                }
                s.startsWith("LOAD=") -> load = s.removePrefix("LOAD=").trim().toDoubleOrNull() ?: -1.0
            }
        }
        return Raw(slab, temp, load)
    }

    /** 上一次特权读到的值。特权通道每次都要跨进程，降频到 3 秒一次。 */
    @Volatile
    private var lastPriv: Raw = Raw(-1, -1, -1.0)

    @Volatile
    private var lastPrivAt = 0L

    private const val PRIV_INTERVAL_MS = 3000L

    /**
     * 采一次样。
     *
     * 顺序是「特权优先、本地兜底」：特权通道能读全三项，本地读只能补它缺的那几项。
     * 两者都失败时保留上一次的读数——成功率不该因为一次采样抖动就跳成「读不到」。
     */
    fun sample(): RateResult {
        val now = System.currentTimeMillis()
        if (now - lastPrivAt >= PRIV_INTERVAL_MS) {
            val pr = readPrivileged()
            if (pr.anyValid) {
                lastPriv = pr
                lastPrivAt = now
            }
        }
        val merged = lastPriv.fill(readLocal())
        return evaluate(merged.slab, merged.tempMilliC, merged.load1, cores)
    }
}
