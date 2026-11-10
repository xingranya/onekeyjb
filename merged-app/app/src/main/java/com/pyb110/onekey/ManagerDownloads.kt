package com.pyb110.onekey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 五家管理器的官方发布信息。
 *
 * 版本号不写死：启动后从官方 Release 接口实时提取，提取不到的那一家直接不展示，
 * 避免出现"界面写着旧版本、实际要求另一个版本"的错位。
 */
data class ManagerDownload(
    val kind: String,
    val label: String,
    val version: String,
    val versionCode: Int,
    val apkUrl: String,
    val pageUrl: String,
)

object ManagerDownloads {

    private const val PREFS = "onekey_manager_downloads"
    private const val KEY_JSON = "catalog_json"
    private const val KEY_AT = "catalog_at"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    /** 每家管理器的官方仓库；[abi] 用于在同名资产里挑架构，[skipLabel] 用于排除变体。 */
    private data class Repo(
        val kind: String,
        val label: String,
        val slug: String,
        val abi: String? = null,
        val skipLabel: String? = null,
    )

    private val repos = listOf(
        Repo("kernelsu", "KernelSU", "tiann/KernelSU"),
        Repo("next", "KernelSU Next", "KernelSU-Next/KernelSU-Next", skipLabel = "spoofed"),
        Repo("sukisu", "SukiSU Ultra", "SukiSU-Ultra/SukiSU-Ultra"),
        Repo("bakasu", "BakaSU", "Baka-SU/BakaSU", abi = "arm64-v8a"),
        Repo("finasu", "FinaSU", "LyraVoid/FinaSU"),
    )

    @Volatile
    private var catalog: List<ManagerDownload> = emptyList()

    @Volatile
    var lastSyncAt: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 已提取到的官方版本；未提取到的管理器不在列表里。 */
    fun all(): List<ManagerDownload> = catalog

    fun latest(kind: String): ManagerDownload? = catalog.firstOrNull { it.kind == kind }

    fun isFresh(): Boolean = catalog.isNotEmpty() && System.currentTimeMillis() - lastSyncAt < CACHE_TTL_MS

    /** 读取上次成功提取的结果，冷启动时先有内容可显示。 */
    fun load(context: Context) {
        if (catalog.isNotEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_JSON, null) ?: return
        val parsed = runCatching { parseCatalog(raw) }.getOrNull() ?: return
        catalog = parsed
        lastSyncAt = prefs.getLong(KEY_AT, 0L)
    }

    /**
     * 从官方 Release 接口重新提取。
     * 单家失败只跳过该家；全部失败返回 false 且保留已有结果。
     */
    fun refresh(context: Context): Boolean {
        val fetched = LinkedHashMap<String, ManagerDownload>()
        val failed = mutableListOf<String>()
        for (repo in repos) {
            val item = runCatching { fetch(repo) }.getOrNull()
            if (item == null) failed += repo.label else fetched[repo.kind] = item
        }
        if (fetched.isEmpty()) {
            lastError = "未获取到官方版本信息"
            return false
        }
        catalog = repos.mapNotNull { fetched[it.kind] }
        lastSyncAt = System.currentTimeMillis()
        lastError = if (failed.isEmpty()) null else "未获取：" + failed.joinToString("、")
        save(context)
        return failed.isEmpty()
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        catalog.forEach { d ->
            arr.put(
                JSONObject()
                    .put("kind", d.kind)
                    .put("label", d.label)
                    .put("version", d.version)
                    .put("versionCode", d.versionCode)
                    .put("apkUrl", d.apkUrl)
                    .put("pageUrl", d.pageUrl)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, arr.toString())
            .putLong(KEY_AT, lastSyncAt)
            .apply()
    }

    private fun parseCatalog(raw: String): List<ManagerDownload> {
        val arr = JSONArray(raw)
        val out = mutableListOf<ManagerDownload>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optInt("versionCode", 0)
            if (code <= 0) continue
            out += ManagerDownload(
                kind = o.optString("kind"),
                label = o.optString("label"),
                version = o.optString("version"),
                versionCode = code,
                apkUrl = o.optString("apkUrl"),
                pageUrl = o.optString("pageUrl"),
            )
        }
        return out
    }

    /** 取最新一次发布：优先正式版，没有正式版时取最新预发布。 */
    private fun fetch(repo: Repo): ManagerDownload? {
        val conn = (URL("https://api.github.com/repos/" + repo.slug + "/releases?per_page=15")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", "OneKeyJB-managers")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val body = try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }

        val releases = JSONArray(body)
        var picked: JSONObject? = null
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            if (r.optBoolean("draft", false)) continue
            if (!r.optBoolean("prerelease", false)) { picked = r; break }
            if (picked == null) picked = r
        }
        val release = picked ?: return null

        val assets = release.optJSONArray("assets") ?: return null
        var best: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (repo.skipLabel != null && name.contains(repo.skipLabel, ignoreCase = true)) continue
            if (repo.abi != null && name.contains(repo.abi, ignoreCase = true)) { best = a; break }
            if (best == null) best = a
        }
        val asset = best ?: return null

        val code = versionCodeOf(asset.optString("name")) ?: return null
        return ManagerDownload(
            kind = repo.kind,
            label = repo.label,
            version = release.optString("tag_name"),
            versionCode = code,
            apkUrl = asset.optString("browser_download_url"),
            pageUrl = release.optString("html_url"),
        )
    }

    /** 从资产名里取 versionCode，形如 KernelSU_v3.3.1_32680-release.apk。 */
    private fun versionCodeOf(fileName: String): Int? {
        val m = Regex("_(\\d{4,})(?=[.-])").find(fileName) ?: return null
        return m.groupValues[1].toIntOrNull()
    }
}
