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
    val title: String get() = if (exploit == "cve98260") "98260 新链" else "64560 稳定链"
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

    fun append(line: String) {
        val ctx = appContext ?: return
        if (activeId == null && line.isNotBlank()) {
            // Safety net: even if a run path forgot start(), keep the log.
            start(ctx, "unknown", "-", false, "")
        }
        val id = activeId ?: return
        synchronized(lock) {
            runCatching {
                val f = logFile(ctx, id)
                if (f.length() < 2L * 1024 * 1024) f.appendText(line + "\n")
            }
        }
    }

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
                    exploit = j.optString("exploit", "cve64560"),
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
