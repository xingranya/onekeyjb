package com.pyb110.onekey

/**
 * 一次运行的客观结果：是否真的写入了内核、停在哪一步。
 * 只有 wroteKernel=true 时才允许提示"重启"，避免"重启也没用"的误导。
 */
object RunReport {
    @Volatile var wroteKernel: Boolean = false
    @Volatile var stoppedAt: String = ""
    @Volatile var reason: String = ""

    fun reset() {
        wroteKernel = false
        stoppedAt = ""
        reason = ""
    }

    fun markWrote(stage: String) {
        wroteKernel = true
        stoppedAt = stage
    }

    fun stop(stage: String, why: String) {
        if (stoppedAt.isEmpty()) stoppedAt = stage
        reason = why
    }
}
