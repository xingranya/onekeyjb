package com.pyb110.onekey

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** CNB 固定标签发布源更新器：资产统一覆盖在 update 标签下。 */
object UpdateChecker {
    const val BASE = "https://cnb.cool/xingranya/onekeyjb/-/releases/download/update"
    const val MANIFEST = "$BASE/update.json"
    const val APK_URL = "$BASE/OneKeyJB-latest.apk"

    /** 单个版本条目：名称、版本号、发布时间、安装包大小与分成条目的更新日志。 */
    data class VersionNote(
        val versionName: String,
        val versionCode: Int,
        val publishedAt: String,
        val size: Long,
        val notes: List<String>,
    )

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val changelog: String,
        val apkUrl: String,
        val sha256: String,
        val publishedAt: String,
        val size: Long,
        val versions: List<VersionNote>,
    )

    /** 下载进度：百分比 + 已下载 + 总量 + 实时速度（B/s）。 */
    data class Progress(
        val percent: Int,
        val downloaded: Long,
        val total: Long,
        val bytesPerSec: Long,
    ) {
        val remaining: Long get() = (total - downloaded).coerceAtLeast(0L)
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "OneKeyJB-updater")
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP " + c.responseCode)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun parseVersions(o: JSONObject): List<VersionNote> {
        val array = o.optJSONArray("versions") ?: return emptyList()
        val result = ArrayList<VersionNote>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val notes = ArrayList<String>()
            item.optJSONArray("notes")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val line = arr.optString(j).trim()
                    if (line.isNotEmpty()) notes += line
                }
            }
            if (notes.isEmpty()) {
                item.optString("changelog").lines().forEach { line ->
                    val t = line.trim().removePrefix("-").trim()
                    if (t.isNotEmpty()) notes += t
                }
            }
            result += VersionNote(
                versionName = item.optString("versionName", ""),
                versionCode = item.optInt("versionCode", 0),
                publishedAt = item.optString("publishedAt", ""),
                size = item.optLong("size", 0L),
                notes = notes,
            )
        }
        return result.sortedByDescending { it.versionCode }
    }

    fun check(currentCode: Int): Info? {
        val o = JSONObject(String(get(MANIFEST), Charsets.UTF_8))
        val code = o.optInt("versionCode", 0)
        if (code <= currentCode) return null
        return Info(
            versionCode = code,
            versionName = o.optString("versionName", "新版本"),
            changelog = o.optString("changelog", ""),
            apkUrl = o.optString("apkUrl", APK_URL),
            sha256 = o.optString("sha256", ""),
            publishedAt = o.optString("publishedAt", ""),
            size = o.optLong("size", 0L),
            versions = parseVersions(o),
        )
    }

    /** 字节数转两位小数：1.00 MB / 823.42 KB。 */
    fun formatSize(bytes: Long): String {
        val kb = bytes.coerceAtLeast(0L) / 1024.0
        return if (kb < 1024.0) String.format(Locale.US, "%.2f KB", kb)
        else String.format(Locale.US, "%.2f MB", kb / 1024.0)
    }

    fun formatSpeed(bytesPerSec: Long): String =
        if (bytesPerSec <= 0L) "测速中" else formatSize(bytesPerSec) + "/s"

    /** ISO-8601 → 本地时间 yyyy-MM-dd HH:mm；解析失败按原样展示。 */
    fun formatTime(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return "未标注"
        runCatching {
            val dt = OffsetDateTime.parse(text)
            val local = dt.atZoneSameInstant(ZoneId.systemDefault())
            return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date.from(local.toInstant()))
        }
        runCatching {
            val millis = text.toLong()
            if (millis > 1_000_000_000_000L) {
                return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
            }
        }
        return text
    }

    fun download(context: Context, info: Info, onProgress: (Progress) -> Unit): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        dir.mkdirs()
        val out = File(dir, "OneKeyJB-update.apk")
        val c = URL(info.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 120000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "OneKeyJB-updater")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP " + c.responseCode)
            val header = c.contentLengthLong
            val total = if (header > 0) header else info.size.coerceAtLeast(0L)
            var read = 0L
            var lastTick = System.currentTimeMillis()
            var lastBytes = 0L
            var speed = 0L
            onProgress(Progress(0, 0L, total, 0L))
            c.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        val span = now - lastTick
                        if (span >= 250L) {
                            val instant = (read - lastBytes) * 1000L / span
                            // 速度做一次指数平滑，避免进度条上的数字剧烈跳动。
                            speed = if (speed <= 0L) instant else (speed * 3 + instant) / 4
                            lastTick = now
                            lastBytes = read
                            val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else 0
                            onProgress(Progress(pct, read, total, speed))
                        }
                    }
                }
            }
            onProgress(Progress(100, read, if (total > 0) total else read, speed))
        } finally {
            c.disconnect()
        }
        if (info.sha256.isNotBlank()) {
            val md = MessageDigest.getInstance("SHA-256")
            out.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            val got = md.digest().joinToString("") { String.format("%02x", it) }
            if (!got.equals(info.sha256, ignoreCase = true)) {
                out.delete()
                throw IllegalStateException("APK SHA-256 校验失败")
            }
        }
        return out
    }

    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
