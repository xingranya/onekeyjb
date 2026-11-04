package com.pyb110.onekey

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 从自有 CNB 公共 catalog 解析并缓存管理器内核模块。
 * APK 内不保存任何 API Token；catalog 与资产均走公共下载地址。
 */
object CloudComponents {
    const val CATALOG_URL =
        "https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/cloud-catalog.json"

    private val ALLOWED_HOSTS = setOf(
        "cnb.cool",
        "asset.cnb.cool",
        "github.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
    )

    data class ModuleAsset(
        val manager: String,
        val version: Int,
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String,
        /** 可选：模块对应的内核 release；为空表示不限内核。 */
        val vermagic: String? = null,
    )

    data class Status(val text: String, val module: ModuleAsset? = null)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { String.format("%02x", it) }

    private fun hostAllowed(url: String): Boolean = runCatching {
        val u = URL(url)
        if (!u.protocol.equals("https", ignoreCase = true)) return false
        val h = u.host.lowercase()
        ALLOWED_HOSTS.any { h == it || h.endsWith("." + it) }
    }.getOrDefault(false)

    private fun read(url: String, limit: Long, accept: String): ByteArray {
        if (!hostAllowed(url)) throw IllegalStateException("更新源主机不在白名单")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 30000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "OneKey-Cloud/1")
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP " + c.responseCode)
            if (!hostAllowed(c.url.toString())) throw IllegalStateException("重定向到不可信主机")
            val declared = c.contentLengthLong
            if (declared > limit) throw IllegalStateException("文件过大")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) throw IllegalStateException("文件过大")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun parse(text: String, key: String): String? =
        Regex("(?m)^" + Regex.escape(key) + "=(.*)$").find(text)?.groupValues?.get(1)?.trim()

    fun fetchModules(): List<ModuleAsset> {
        val body = String(read(CATALOG_URL, 2L * 1024 * 1024, "application/json"), Charsets.UTF_8)
        val modules = JSONObject(body).optJSONArray("modules") ?: return emptyList()
        val result = ArrayList<ModuleAsset>()
        for (i in 0 until modules.length()) {
            val a = modules.optJSONObject(i) ?: continue
            val manager = a.optString("manager")
            val version = a.optInt("version", 0)
            val name = a.optString("name")
            val sha = a.optString("sha256").trim().lowercase()
            val url = a.optString("url")
            val size = a.optLong("size")
            if (!Regex("^[a-z]+$").matches(manager)) continue
            if (version <= 0 || !Regex("^[A-Za-z0-9_.-]{1,160}$").matches(name)) continue
            if (!Regex("^[0-9a-f]{64}$").matches(sha)) continue
            if (size <= 0 || size > 8L * 1024 * 1024) continue
            if (!hostAllowed(url)) continue
            val vermagic = a.optString("vermagic").trim().ifBlank { null }
            if (vermagic != null && !Regex("^[0-9][A-Za-z0-9_.+-]{0,140}$").matches(vermagic)) continue
            result += ModuleAsset(manager, version, name, url, size, sha, vermagic)
        }
        return result
    }

    /** 同一管理器+版本可能按内核 release 分文件发布：优先取 vermagic 完全匹配的一条。 */
    private fun pick(list: List<ModuleAsset>, kind: String, version: Int, release: String): ModuleAsset? {
        val same = list.filter { it.manager == kind && it.version == version }
        val exact = same.firstOrNull { it.vermagic != null && it.vermagic == release }
        if (exact != null) return exact
        return same.firstOrNull { it.vermagic == null }
    }

    fun inspect(kind: String, version: Int, release: String? = null): Status = runCatching {
        val hit = pick(fetchModules(), kind, version, release.orEmpty())
        if (hit != null) Status("云端模块 " + hit.version + " 可用", hit)
        else Status("暂无当前管理器版本的云端模块")
    }.getOrElse { Status("更新源暂不可用：" + (it.message ?: "网络错误")) }

    private fun verify(bytes: ByteArray, asset: ModuleAsset, cert: String, release: String): Boolean {
        if (bytes.size.toLong() != asset.size || bytes.isEmpty()) return false
        if (!sha256(bytes).equals(asset.sha256, true)) return false
        // ELF64 小端可重定位目标文件，至少保证结构没被破坏。
        if (bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) return false
        if (bytes[4] != 2.toByte() || bytes[5] != 1.toByte()) return false
        // 内核 .ko 的 modinfo 以 NUL 分隔，不是换行；先归一化再做元数据校验。
        val text = String(bytes, Charsets.ISO_8859_1).replace('\u0000', '\n')
        if (parse(text, "onuce_schema") != "2") return false
        if (parse(text, "onuce_key") != asset.manager) return false
        if (parse(text, "onuce_version") != asset.version.toString()) return false
        if (parse(text, "onuce_uapi") != "6") return false
        if (!parse(text, "onuce_certificate").equals(cert, true)) return false
        val vermagic = parse(text, "vermagic") ?: return false
        if (vermagic != release && !vermagic.startsWith(release + " ")) return false
        val commit = release.substringAfter("-g", "").substringBefore("-ab")
        val kernel = parse(text, "onuce_kernel").orEmpty()
        if (commit.length < 12 || !kernel.startsWith(commit)) return false
        if (parse(text, "onuce_su") != "manager-v1") return false
        if (parse(text, "onuce_allowlist") != "cred-v1") return false
        if (text.contains("onuce_terminal=") || text.contains("onuce_app_su=")) return false
        return true
    }

    fun ensureModule(
        context: Context,
        kind: String,
        versionCode: Int,
        cert: String,
        release: String,
        log: (String) -> Unit,
        progress: (Int) -> Unit,
    ): File? = runCatching {
        val hit = pick(fetchModules(), kind, versionCode, release) ?: run {
            log("云端无 " + kind + " " + versionCode + " 的模块，使用本机组件")
            return@runCatching null
        }
        val dir = File(context.filesDir, "cloud/modules").apply { mkdirs() }
        val target = File(dir, kind + "-" + versionCode + "-" + sha256(release.toByteArray()).take(12) + ".ko")
        if (target.isFile && verify(target.readBytes(), hit, cert, release)) {
            log("云端模块已缓存: " + target.name)
            return target
        }
        log("下载云端模块 " + hit.name)
        progress(2)
        val bytes = read(hit.url, 8L * 1024 * 1024, "application/octet-stream")
        if (!verify(bytes, hit, cert, release)) {
            log("云端模块校验失败，已拒绝")
            return@runCatching null
        }
        val tmp = File(dir, target.name + ".part")
        FileOutputStream(tmp).use { it.write(bytes) }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            log("云端模块保存失败")
            return@runCatching null
        }
        progress(8)
        log("云端模块已就绪: " + target.name)
        target
    }.getOrElse {
        log("云端模块失败: " + (it.message ?: "网络错误"))
        null
    }
}
