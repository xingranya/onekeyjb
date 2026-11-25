package com.pyb110.onekey

import dev.onekey.jb.ShizukuShell

/**
 * 运行期快照：用来给「软重启」归因。
 *
 * 应用侧只有一处显式软重启（受开关控制）。开关关着却仍重启时，
 * 触发者必然在引擎收尾阶段（停安全服务、卸内核模块）或管理器自身。
 * 这里在每个阶段边界采一次样，一旦发现 zygote / system_server 变了，
 * 就能直接点名是哪个阶段之后发生的，而不是事后猜。
 */
data class BootSnapshot(
    val bootId: String,
    val systemServerPid: String,
    val zygoteState: String,
    val uptimeSec: Long,
) {
    val valid: Boolean get() = bootId.isNotBlank()

    /** 与上一份快照比对，返回变化描述；无变化返回 null。 */
    fun diff(prev: BootSnapshot): String? {
        if (!valid || !prev.valid) return null
        val changes = ArrayList<String>(3)
        if (bootId != prev.bootId) changes += "boot_id 变化（系统已重启）"
        if (systemServerPid.isNotBlank() && prev.systemServerPid.isNotBlank() &&
            systemServerPid != prev.systemServerPid
        ) {
            changes += "system_server 重建（" + prev.systemServerPid + " → " + systemServerPid + "）"
        }
        if (zygoteState.isNotBlank() && prev.zygoteState.isNotBlank() &&
            zygoteState != prev.zygoteState
        ) {
            changes += "zygote 状态变化（" + prev.zygoteState + " → " + zygoteState + "）"
        }
        return if (changes.isEmpty()) null else changes.joinToString("；")
    }
}

object BootWatch {
    /** 采一次样。全部走 Stellar，失败字段留空，不阻断运行。 */
    fun snapshot(): BootSnapshot {
        val boot = read("cat /proc/sys/kernel/random/boot_id 2>/dev/null")
        val pid = read("pidof system_server 2>/dev/null")
        val zygote = read("getprop init.svc.zygote 2>/dev/null")
        val up = read("cut -d. -f1 /proc/uptime 2>/dev/null").toLongOrNull() ?: 0L
        return BootSnapshot(boot, pid, zygote, up)
    }

    private fun read(cmd: String): String =
        runCatching { ShizukuShell.run(cmd, 8).output.trim() }.getOrDefault("")

    /**
     * 阶段边界检查。返回需要写进日志的提示，null 表示没变化。
     *
     * @param stageLabel 刚结束的阶段名，用于日志定位
     */
    fun check(prev: BootSnapshot, stageLabel: String): String? {
        val now = snapshot()
        val d = now.diff(prev) ?: return null
        return "「" + stageLabel + "」之后检测到 " + d
    }
}
