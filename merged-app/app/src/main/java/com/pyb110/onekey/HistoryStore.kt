package com.pyb110.onekey

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

data class HistoryRecord(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val exploit: String,
    val manager: String,
    val versionName: String,
    val adaptive: Boolean,
    val status: String,
    val stage: String,
    val durationSec: Long,
    val logFile: String,
) {
    // 64560 稳定链已废除，任何历史记录都归到现行运行链下。
    // 旧版本写下的记录里 exploit 可能是 "-"/"unknown"/"cve64560"，一律按现行链显示，
    // 否则历史页会出现早已不存在的链路名称。
    val title: String get() = "98260 运行链"
    val stateText: String
        get() = when (status) {
            "ok" -> "成功"
            "failed" -> "失败"
            "interrupted" -> "异常中断"
            else -> "未完成"
        }
}

/** Persistent per-run history. Written incrementally so a soft reboot cannot erase it. */
object HistoryStore {
    private const val DIR = "onekey-history"
    private const val MAX_RECORDS = 60
    private val lock = Any()
    private var activeId: String? = null
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun fileCount(context: Context): Int =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
    private fun metaFile(context: Context, id: String): File = File(dir(context), id + ".json")
    private fun logFile(context: Context, id: String): File = File(dir(context), id + ".log")

    fun start(context: Context, exploit: String, manager: String, adaptive: Boolean, versionName: String): String {
        val ctx = context.applicationContext
        appContext = ctx
        synchronized(lock) {
            activeId?.let { old -> finishLocked(ctx, old, false, "上一次运行被新任务中断", 0) }
            val now = System.currentTimeMillis()
            val id = "run-" + now + "-" + Random.nextInt(1000, 9999)
            activeId = id
            logFile(ctx, id).writeText("")
            writeMeta(ctx, id, JSONObject().apply {
                put("id", id)
                put("startedAt", now)
                put("endedAt", 0L)
                put("exploit", exploit)
                put("manager", manager)
                put("adaptive", adaptive)
                put("versionName", versionName)
                put("status", "running")
                put("stage", "启动中")
                put("durationSec", 0L)
            })
            return id
        }
    }

    /**
     * 追加一行日志。
     *
     * 只有当 [RunState.active] 为 true（真正在跑一次越狱）时才允许凭空建记录。
     * 以前这里无条件兜底，导致「启动 App」「拨开关」这类普通日志都会各造一条
     * exploit=unknown 的记录，永远停在 running，历史页因此堆满「未完成 0.00s」。
     */
    fun append(line: String) {
        val ctx = appContext ?: return
        // 没有活动记录就不写：以前这里会凭空 start 一条 exploit=unknown 的记录，
        // 结果是启动 App、拨开关、看提示都会各留一条永远 running 的幽灵记录。
        val id = activeId ?: return
        synchronized(lock) {
            runCatching {
                val f = logFile(ctx, id)
                if (f.length() < 2L * 1024 * 1024) f.appendText(line + "\n")
            }
        }
    }

    /**
     * 把仍在 running 的记录标成 interrupted。
     *
     * 进程被系统杀掉、用户强退、软重启都会留下 running 记录；
     * 没人收尾的话它们会一直显示「未完成」，还会污染成功率统计。
     */
    fun abandon(context: Context, reason: String = "未执行完就退出") {
        val ctx = context.applicationContext
        synchronized(lock) {
            val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
            for (f in files) {
                val id = f.name.removeSuffix(".json")
                runCatching {
                    val j = readMeta(ctx, id) ?: return@runCatching
                    if (j.optString("status") != "running") return@runCatching
                    j.put("status", "interrupted")
                    j.put("stage", reason)
                    j.put("endedAt", System.currentTimeMillis())
                    writeMeta(ctx, id, j)
                }
            }
            activeId = null
        }
    }

    /**
     * 统计口径：只算真正跑完的（ok / failed）。
     * interrupted 与 unknown 不计入分母，否则强退一次就永久拉低成功率。
     */
    fun stats(context: Context, recent: Int = 10): Stats {
        val all = list(context).filter { it.status == "ok" || it.status == "failed" }
        val ok = all.count { it.status == "ok" }
        val recentList = all.take(recent)
        return Stats(
            total = all.size,
            ok = ok,
            recentTotal = recentList.size,
            recentOk = recentList.count { it.status == "ok" },
        )
    }

    data class Stats(
        val total: Int,
        val ok: Int,
        val recentTotal: Int,
        val recentOk: Int,
    ) {
        val rate: Double get() = if (total == 0) -1.0 else ok * 100.0 / total
        val recentRate: Double get() = if (recentTotal == 0) -1.0 else recentOk * 100.0 / recentTotal
        val hasData: Boolean get() = total > 0
    }

    /** 当前是否有一次运行记录处于打开状态（start 之后、finish 之前）。 */
    fun hasActive(): Boolean = activeId != null

    fun updateStage(stage: String) {
        val ctx = appContext ?: return
        val id = activeId ?: return
        synchronized(lock) {
            runCatching {
                val j = readMeta(ctx, id) ?: return
                j.put("stage", stage)
                writeMeta(ctx, id, j)
            }
        }
    }

    fun finish(ok: Boolean, stage: String, durationSec: Long) {
        val ctx = appContext ?: return
        val id = activeId ?: return
        synchronized(lock) {
            finishLocked(ctx, id, ok, stage, durationSec)
        }
    }

    private fun finishLocked(ctx: Context, id: String, ok: Boolean, stage: String, durationSec: Long) {
        runCatching {
            val j = readMeta(ctx, id) ?: JSONObject()
            j.put("endedAt", System.currentTimeMillis())
            j.put("status", if (ok) "ok" else "failed")
            j.put("stage", stage)
            j.put("durationSec", durationSec)
            writeMeta(ctx, id, j)
        }
        if (activeId == id) activeId = null
        prune(ctx)
    }

    fun list(context: Context): List<HistoryRecord> {
        val ctx = context.applicationContext
        val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            readMeta(ctx, f.name.removeSuffix(".json"))?.let { j ->
                HistoryRecord(
                    id = j.optString("id", f.name.removeSuffix(".json")),
                    startedAt = j.optLong("startedAt"),
                    endedAt = j.optLong("endedAt"),
                    exploit = j.optString("exploit", "cve98260"),
                    manager = j.optString("manager", "finasu"),
                    versionName = j.optString("versionName", ""),
                    adaptive = j.optBoolean("adaptive", true),
                    status = j.optString("status", "interrupted"),
                    stage = j.optString("stage", ""),
                    durationSec = j.optLong("durationSec"),
                    logFile = j.optString("id", f.name.removeSuffix(".json")) + ".log",
                )
            }
        }.sortedByDescending { it.startedAt }
    }

    fun readLog(context: Context, record: HistoryRecord): String {
        return runCatching { File(dir(context), record.logFile).readText() }.getOrDefault("")
    }

    fun clear(context: Context) {
        synchronized(lock) {
            activeId = null
            dir(context).listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    private fun readMeta(ctx: Context, id: String): JSONObject? = try {
        val af = AtomicFile(metaFile(ctx, id))
        af.openRead().bufferedReader().use { JSONObject(it.readText()) }
    } catch (_: Throwable) {
        null
    }

    private fun writeMeta(ctx: Context, id: String, j: JSONObject) {
        val af = AtomicFile(metaFile(ctx, id))
        var out: FileOutputStream? = null
        try {
            out = af.startWrite()
            out.write(j.toString(2).toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
        } catch (_: Throwable) {
            if (out != null) runCatching { af.failWrite(out) }
        }
    }

    private fun prune(ctx: Context) {
        val records = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (records.size <= MAX_RECORDS) return
        records.sortedBy { it.lastModified() }.dropLast(MAX_RECORDS).forEach { f ->
            runCatching { f.delete() }
            runCatching { File(dir(ctx), f.name.removeSuffix(".json") + ".log").delete() }
        }
    }

    fun formatTime(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
