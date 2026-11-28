package com.pyb110.onekey

import java.io.File
import java.util.Locale

/**
 * 环境读数。
 *
 * 与旧实现的关键区别：这三个量**不再直接决定百分比**。
 * 它们只作为环境修正系数（±30%）作用在「实测单次命中率」上，
 * 所以某个量读不到也不会让成功率变成假数字，只是失去一点修正能力。
 */
data class Env(
    val slab: Int,
    val tempMilliC: Int,
    val load1: Double,
    val procsRunning: Int,
    val battery: Int,
    val cores: Int,
) {
    val anyValid: Boolean
        get() = slab > 0 || tempMilliC > 0 || load1 >= 0.0 || procsRunning >= 0

    /**
     * 修正系数所需的量有没有缺。
     *
     * 只看 [procsRunning] 与 [tempMilliC]：它们才是环境修正的输入。
     * slab 与 load1 只用于展示（slab 更是只有 6.12 链才有意义），
     * 把它们算进来会让 6.18 设备永远拿不到温度与线程数的修正。
     */
    val partial: Boolean
        get() = tempMilliC <= 0 || procsRunning < 0

    fun fill(other: Env) = Env(
        if (slab > 0) slab else other.slab,
        if (tempMilliC > 0) tempMilliC else other.tempMilliC,
        if (load1 >= 0.0) load1 else other.load1,
        if (procsRunning >= 0) procsRunning else other.procsRunning,
        if (battery >= 0) battery else other.battery,
        if (cores > 0) cores else other.cores,
    )

    companion object {
        val EMPTY = Env(-1, -1, -1.0, -1, -1, 8)
    }
}

/**
 * 成功率评估结果。
 *
 * [probability] 不是「打分」，是「按本机实测统计，这次运行能拿到 root 的概率」。
 * 它由三件事决定，任何一件缺失都会让数字失去意义，因此缺失时宁可不显示：
 * 1. 单次尝试命中率（Beta 后验，来自真实 attempt 记录）；
 * 2. 本次预算内能跑几次（引擎预算 ÷ 实测单次耗时，再受中止阈值封顶）；
 * 3. 环境修正（线程运行数、温度），上下界锁死在 ±30%。
 */
data class RateResult(
    val valid: Boolean,
    val blockers: List<String>,
    val displayable: Boolean,
    val probability: Double,
    val low: Double,
    val high: Double,
    val samples: Int,
    val bootClass: BootClass,
    val attemptsPlanned: Int,
    val msPerAttempt: Long,
    val budgetMs: Long,
    val envFactor: Double,
    val envNote: String,
    val slab: Int,
    val tempMilliC: Int,
    val load1: Double,
    val procsRunning: Int,
    val battery: Int,
    val slabRelevant: Boolean,
    val level: Int,
) {
    val slabText: String get() = if (slab <= 0) "--" else slab.toString()

    val slabState: String
        get() = when {
            slab <= 0 -> "读不到"
            slab <= 340 -> "理想"
            slab <= 430 -> "良好"
            slab <= 540 -> "偏高"
            slab <= 660 -> "很差"
            else -> "极差"
        }

    val tempText: String
        get() = if (tempMilliC <= 0) "--" else String.format(Locale.US, "%.1f°C", tempMilliC / 1000.0)

    val tempState: String
        get() = when {
            tempMilliC <= 0 -> "读不到"
            tempMilliC <= 40000 -> "凉爽"
            tempMilliC <= 45000 -> "正常"
            tempMilliC <= 52000 -> "偏温"
            tempMilliC <= 60000 -> "偏热"
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

    val busyText: String get() = if (procsRunning < 0) "--" else procsRunning.toString()

    val busyState: String
        get() = when {
            procsRunning < 0 -> "读不到"
            procsRunning <= 8 -> "偏静"
            procsRunning <= 24 -> "合适"
            procsRunning <= 64 -> "偏忙"
            else -> "过载"
        }

    val batteryText: String get() = if (battery < 0) "--" else battery.toString() + "%"

    val batteryState: String
        get() = when {
            battery < 0 -> "读不到"
            battery >= 50 -> "充足"
            battery >= 30 -> "偏低"
            else -> "不足"
        }

    val bootText: String
        get() = when (bootClass) {
            BootClass.COLD_FIRST -> "本次开机首次运行"
            BootClass.COLD_RERUN -> "同一开机内重复运行"
            BootClass.DIRTY -> "上次未完成，需重启"
        }

    val rangeText: String
        get() = if (!displayable) "样本不足（n=" + samples + "）"
        else String.format(Locale.US, "%.0f~%.0f%%", low, high)

    /** 一句话建议：先看硬闸，再看样本量，最后看环境。 */
    fun advice(): String {
        if (!valid) return blockers.firstOrNull() ?: "当前条件不适合运行"
        if (!displayable) {
            return "还没有足够本机数据（" + samples + "/" + RunStats.MIN_SAMPLES + " 次尝试），跑几次后才有可信概率"
        }
        if (bootClass == BootClass.COLD_RERUN) {
            return "本次开机已经跑过，内核状态已被上一次改变；重启后再跑成功率会明显更高"
        }
        if (tempMilliC > 52000) return "温度偏高，散热几分钟再跑更稳"
        if (procsRunning in 1..8) return "系统过于空闲，实测命中率反而下降；正常使用状态下跑更稳"
        if (procsRunning > 24) return "后台线程偏多，等它降下来更稳"
        if (probability >= SuccessRate.RECOMMEND_MIN) {
            return "预计可尝试 " + attemptsPlanned + " 次，单次约 " + String.format(Locale.US, "%.1f", msPerAttempt / 1000.0) + " 秒"
        }
        return "概率偏低，建议先重启手机清掉上一次的内核改动"
    }
}

object SuccessRate {
    /** 理论上限：利用存在时序竞争，永不给 100%。 */
    const val CEILING = 92.0

    /** 低于该值在开始前拦一次。 */
    const val RECOMMEND_MIN = 40.0

    /** slab 理想上限（仅 6.12 链有意义）。 */
    const val SLAB_IDEAL = 310

    /** 采样间隔。 */
    const val SAMPLE_MS = 1000L

    /** 连续多少次 attempt 未命中就中止。旧值 4 把理论上限压到 11.5%，抬到 6。 */
    const val ABORT_AFTER = 6

    /** 环境修正的上下界：只是修正，不允许把概率放大或缩小到失真。 */
    const val ENV_MIN = 0.70
    const val ENV_MAX = 1.10

    /** 电量硬底：低于此值不给跑。 */
    const val BATTERY_MIN = 30

    val cores: Int get() = Runtime.getRuntime().availableProcessors()

    /**
     * 按内核类型给预算。
     *
     * 引擎自身按 `--auto240`（240 秒）设计，实测命中间隔 3~5 分钟；
     * 旧实现写死 90 秒，等于只给引擎 37% 的预算就判死。
     */
    fun budgetFor(kmi: String): Long =
        if (kmi.contains("6.12")) 180_000L else 300_000L

    /**
     * 环境修正系数。
     *
     * 两个方向都可能有反直觉：系统**太静**时命中率反而下降
     * （fullchain docs 实测：8 个候选进程在场第 10 次命中，
     * 只剩 1 个候选时 212 次零命中），所以低线程数是扣分项而不是加分项。
     */
    fun envFactor(procsRunning: Int, tempMilliC: Int): Double {
        val busy = when {
            procsRunning < 0 -> 1.0
            procsRunning <= 8 -> 0.75
            procsRunning <= 24 -> 1.0
            procsRunning <= 64 -> 0.85
            else -> 0.70
        }
        val temp = when {
            tempMilliC <= 0 -> 1.0
            tempMilliC <= 45000 -> 1.0
            tempMilliC <= 52000 -> 0.92
            else -> 0.80
        }
        return (busy * temp).coerceIn(ENV_MIN, ENV_MAX)
    }

    /** 本次预算内最多能跑几次 attempt。 */
    fun attemptsPlanned(budgetMs: Long, msPerAttempt: Long): Int {
        val per = if (msPerAttempt > 0) msPerAttempt else RunStats.DEFAULT_MS_PER_ATTEMPT
        val byBudget = (budgetMs / per).toInt().coerceAtLeast(1)
        return minOf(ABORT_AFTER, byBudget)
    }

    /** k 次独立尝试里至少命中一次的概率。 */
    fun runProbability(pAttempt: Double, k: Int): Double {
        if (k <= 0) return 0.0
        val p = pAttempt.coerceIn(0.0, 0.95)
        val miss = Math.pow(1.0 - p, k.toDouble())
        return ((1.0 - miss) * 100.0).coerceIn(0.0, CEILING)
    }

    fun level(probability: Double): Int = when {
        probability >= 60.0 -> 0
        probability >= RECOMMEND_MIN -> 1
        probability >= 20.0 -> 2
        probability >= 8.0 -> 3
        else -> 4
    }

    /**
     * 主入口。
     *
     * [samples] 是历史运行样本（含本机所有 boot 桶），[bootClass] 决定用哪个桶的后验。
     * [budgetOverrideMs] 用于把引擎自报的预算喂回来；0 表示用档案默认值。
     */
    fun evaluate(
        env: Env,
        bootClass: BootClass,
        samples: List<RunSample>,
        budgetMs: Long,
        slabRelevant: Boolean,
    ): RateResult {
        val est = RunStats.estimate(samples, bootClass)
        val envF = if (env.partial) 1.0 else envFactor(env.procsRunning, env.tempMilliC)
        val perAttempt = RunStats.medianMsPerAttempt(samples)
        val k = attemptsPlanned(budgetMs, perAttempt)
        val p = (est.pAttempt * envF).coerceIn(0.0, 0.95)
        val prob = runProbability(p, k)
        val low = runProbability((est.pLow * envF).coerceIn(0.0, 0.95), k)
        val high = runProbability((est.pHigh * envF).coerceIn(0.0, 0.95), k)

        val blockers = ArrayList<String>()
        if (bootClass == BootClass.DIRTY) {
            blockers.add("上次运行已写入内核但未拿到 root，需重启手机后再试")
        }
        if (env.battery in 0 until BATTERY_MIN) {
            blockers.add("电量 " + env.battery + "%，低于 " + BATTERY_MIN + "% 不建议运行")
        }
        if (env.procsRunning > 64) {
            blockers.add("系统可运行线程 " + env.procsRunning + " 个，开机负载未退，此时跑容易失败")
        }

        val note = if (env.partial) {
            "环境数据不全，修正按 1.00×"
        } else {
            String.format(Locale.US, "环境修正 %.2f×（%d 线程运行，%.1f°C）", envF, env.procsRunning, env.tempMilliC / 1000.0)
        }

        return RateResult(
            valid = blockers.isEmpty(),
            blockers = blockers,
            displayable = est.samples >= RunStats.MIN_SAMPLES && blockers.isEmpty(),
            probability = prob,
            low = low,
            high = high,
            samples = est.samples,
            bootClass = bootClass,
            attemptsPlanned = k,
            msPerAttempt = perAttempt,
            budgetMs = budgetMs,
            envFactor = envF,
            envNote = note,
            slab = env.slab,
            tempMilliC = env.tempMilliC,
            load1 = env.load1,
            procsRunning = env.procsRunning,
            battery = env.battery,
            slabRelevant = slabRelevant,
            level = level(prob),
        )
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

    /** 读 /proc/stat 的 procs_running，失败返回 -1。 */
    fun readProcsRunning(): Int = try {
        var v = -1
        File("/proc/stat").useLines { seq ->
            for (line in seq) {
                if (line.startsWith("procs_running")) {
                    v = line.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: -1
                    break
                }
            }
        }
        v
    } catch (_: Throwable) {
        -1
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
    fun readLocal(): Env = Env(
        slab = readSlab(),
        tempMilliC = readTempMilli(),
        load1 = readLoad1(),
        procsRunning = readProcsRunning(),
        battery = -1,
        cores = cores,
    )

    /**
     * 借特权通道一次读齐四个量。
     *
     * 普通 App 域（untrusted_app）在真机与模拟器上都被 SELinux 挡住
     * proc_loadavg、/proc/stat 与 sysfs，logcat 里能直接看到 avc denied；
     * 所以必须让 Stellar 的 shell 身份去读，把带标签的结果回传后解析。
     */
    fun readPrivileged(): Env {
        // 自研通道没有「权限」概念，起来就能跑；Stellar 需要 binder + 权限双满足。
        // 用「任意通道可执行」判断，避免只装了自研通道时成功率读不到任何量。
        val selfUp = runCatching { com.pyb110.onekey.shell.OneKeyShell.available() }.getOrDefault(false)
        if (!selfUp && (!StellarShell.available() || !StellarShell.hasPermission())) return Env.EMPTY
        val script = listOf(
            "grep -m1 posix_timers_cache /proc/slabinfo 2>/dev/null | awk '{print \"SLAB=\" \$2}'",
            "awk '{print \"LOAD=\" \$1}' /proc/loadavg 2>/dev/null",
            "awk '/^procs_running/{print \"RUN=\" \$2}' /proc/stat 2>/dev/null",
            // 先试 sysfs：绝大多数机型 thermal_zone0..N 里总有一个可读。
            "for z in /sys/class/thermal/thermal_zone*/temp; do v=\$(cat \$z 2>/dev/null); case \"\$v\" in ''|*[!0-9]*) continue;; esac; echo \"TEMP=\$v\"; break; done",
            // sysfs 全被 SELinux 挡住的机型退到 thermalservice：它给的是摄氏度一位小数，乘 1000 换成毫度。
            "dumpsys thermalservice 2>/dev/null | grep -m1 -o 'mValue=[0-9.]*' | cut -d= -f2 | awk '{ if (\$1+0 > 0) printf \"TEMP=%.0f\\n\", \$1*1000 }'",
        ).joinToString("\n")
        val out = try {
            StellarShell.run(script, 6).output
        } catch (_: Throwable) {
            return Env.EMPTY
        }
        var slab = -1
        var temp = -1
        var load = -1.0
        var run = -1
        for (line in out.lineSequence()) {
            val s = line.trim()
            when {
                s.startsWith("SLAB=") -> slab = s.removePrefix("SLAB=").trim().toIntOrNull() ?: -1
                // 两条温度来源都可能命中，只认第一条（sysfs 优先于 thermalservice）。
                s.startsWith("TEMP=") -> if (temp < 0) {
                    temp = s.removePrefix("TEMP=").trim().toIntOrNull() ?: -1
                }
                s.startsWith("LOAD=") -> load = s.removePrefix("LOAD=").trim().toDoubleOrNull() ?: -1.0
                s.startsWith("RUN=") -> run = s.removePrefix("RUN=").trim().toIntOrNull() ?: -1
            }
        }
        return Env(slab, temp, load, run, -1, cores)
    }

    /** 上一次特权读到的值。特权通道每次都要跨进程，降频到 3 秒一次。 */
    @Volatile
    private var lastPriv: Env = Env.EMPTY

    @Volatile
    private var lastPrivAt = 0L

    private const val PRIV_INTERVAL_MS = 3000L

    /**
     * 采一次环境：特权优先、本地兜底。
     *
     * [battery] 由调用方从 BatteryManager 取（读电量不需要权限，但需要 Context）。
     */
    fun sample(battery: Int = -1): Env {
        val now = System.currentTimeMillis()
        if (now - lastPrivAt >= PRIV_INTERVAL_MS) {
            val pr = readPrivileged()
            if (pr.anyValid) {
                lastPriv = pr
                lastPrivAt = now
            }
        }
        val merged = lastPriv.fill(readLocal())
        return if (battery >= 0) merged.copy(battery = battery) else merged
    }
}
