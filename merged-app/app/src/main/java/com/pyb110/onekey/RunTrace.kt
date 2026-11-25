package com.pyb110.onekey

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 阶段墓碑：把「当前跑到哪一步」实时写进磁盘，用来在系统重启后追责。
 *
 * 为什么必须有这个东西：软重启会把本进程连根拔掉，finally 里的归因代码
 * 一行都执行不到，日志里只剩下"跑到某一步就没了"。事后靠 boot_id 反推
 * 只能知道"重启过"，不能知道"重启发生在哪一步之后"。
 *
 * 所以每进入一个危险阶段就先落一块墓碑（write + fsync），跑完再清掉。
 * 下次启动时若发现墓碑还在、且 boot_id 已经变了，就说明：
 * 上一次运行停在这个阶段之后，紧接着发生了用户态重启。
 * 这条结论是唯一能在进程死亡后存活的证据链。
 *
 * 墓碑还记一个 [intentional] 位：本应用自己下发的软重启也是"进程死了"，
 * 但那是用户要的，不能报成"疑似异常重启"。没有这个位，归因就会自相矛盾。
 */
object RunTrace {
    private const val FILE = "onekey-run-trace.json"

    private fun file(context: Context): File = File(context.filesDir, FILE)

    private fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    /**
     * 进入某阶段：落墓碑。
     *
     * @param intentional 本应用即将主动重启（用户开了「越狱后软重启」）时传 true，
     *   这样下次启动不会把它报成异常重启。
     */
    fun mark(context: Context, stage: String, intentional: Boolean = false) {
        if (stage.isBlank()) return
        val boot = bootId()
        if (boot.isBlank()) return
        runCatching {
            val o = JSONObject()
                .put("stage", stage)
                .put("boot", boot)
                .put("ts", System.currentTimeMillis())
                .put("intent", intentional)
            val f = file(context)
            java.io.FileOutputStream(f).use { out ->
                out.write(o.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }
    }

    /** 正常跑完：清墓碑。 */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * 启动时结算上一块墓碑。
     *
     * @return 需要写进日志的结论；没有墓碑或 boot_id 未变返回 null。
     */
    fun settle(context: Context): LogEntry? {
        val f = file(context)
        if (!f.isFile) return null
        val stage: String
        val oldBoot: String
        val ts: Long
        val intentional: Boolean
        try {
            val o = JSONObject(f.readText())
            stage = o.optString("stage")
            oldBoot = o.optString("boot")
            ts = o.optLong("ts")
            intentional = o.optBoolean("intent", false)
        } catch (_: Throwable) {
            runCatching { f.delete() }
            return null
        }
        runCatching { f.delete() }
        if (stage.isBlank()) return null
        val now = bootId()
        if (now.isBlank() || oldBoot.isBlank() || now == oldBoot) {
            // boot_id 没变：进程是被强杀或崩溃的，不是重启，交给 HistoryStore.abandon 处理。
            return null
        }
        val mins = if (ts > 0) ((System.currentTimeMillis() - ts) / 60000L).coerceAtLeast(0L) else 0L
        return if (intentional) {
            RunLog.info(
                "上次运行在「" + stage + "」后按设置软重启",
                "墓碑写在 " + mins + " 分钟前，属正常收尾"
            )
        } else {
            RunLog.warn(
                "上次运行在「" + stage + "」之后系统重启",
                "墓碑写在 " + mins + " 分钟前，boot_id 已变化；该阶段本应用没有下发重启命令 · " + hint(stage)
            )
        }
    }

    /**
     * 按阶段给可操作的下一步。
     *
     * 「加载内核模块」之后的重启几乎都是 ColorOS 的安全守护模块对 LKM 的反应，
     * 不是本应用在重启——把它说清楚，用户才知道该拨哪个开关，而不是反复重试。
     */
    private fun hint(stage: String): String = when {
        stage.contains("内核模块") || stage.contains("加载内核") ->
            "多半是系统安全守护对内核模块的反应，可在「拓展开关」开启「越狱后卸载内核安全防护」"
        stage.contains("管理器") || stage.contains("收尾") ->
            "管理器启动阶段的重启通常由管理器自身触发，可在管理器设置里关闭其自动重启"
        stage.contains("安全守护") ->
            "停用安全组件时被系统判定为异常，建议关闭「反 root 守护」后重试"
        else -> "可导出日志反馈，日志里已记录完整阶段链"
    }
}
