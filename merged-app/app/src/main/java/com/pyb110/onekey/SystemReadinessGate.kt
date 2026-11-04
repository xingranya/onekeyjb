package com.pyb110.onekey

import dev.onekey.jb.ShizukuShell

/** 在执行器启动前等待系统从开机/高负载状态进入可操作状态。 */
object SystemReadinessGate {
    private const val MAX_RUNNABLE = 64
    private const val STABLE_SAMPLES = 3

    fun await(log: (String) -> Unit, maxWaitSec: Int = 75): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitSec * 1000L
        var stable = 0
        var lastLog = 0L
        while (System.currentTimeMillis() < deadline) {
            val boot = ShizukuShell.run("getprop sys.boot_completed 2>/dev/null", 5)
                .output.trim()
            if (boot != "1") {
                stable = 0
                if (System.currentTimeMillis() - lastLog > 10000) {
                    lastLog = System.currentTimeMillis()
                    log("等待系统启动完成…")
                }
                Thread.sleep(1500)
                continue
            }
            val awake = ShizukuShell.run(
                "dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake' && echo AWAKE", 8
            ).output.contains("AWAKE")
            if (!awake) {
                stable = 0
                if (System.currentTimeMillis() - lastLog > 10000) {
                    lastLog = System.currentTimeMillis()
                    log("等待屏幕亮起并解锁…")
                }
                Thread.sleep(1500)
                continue
            }
            val raw = ShizukuShell.run("awk '/^procs_running/{print \$2}' /proc/stat 2>/dev/null", 5)
                .output.trim()
            val runnable = raw.toIntOrNull()
            if (runnable == null) {
                stable = 0
            } else if (runnable <= MAX_RUNNABLE) {
                stable++
                if (stable >= STABLE_SAMPLES) return true
            } else {
                stable = 0
                if (System.currentTimeMillis() - lastLog > 10000) {
                    lastLog = System.currentTimeMillis()
                    log("系统繁忙（runnable " + runnable + "），等待空闲后重试…")
                }
            }
            Thread.sleep(1500)
        }
        log("系统在 " + maxWaitSec + " 秒内仍未进入空闲状态，暂不启动执行器")
        return false
    }
}
