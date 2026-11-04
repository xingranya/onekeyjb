package com.pyb110.onekey

import android.content.Context
import android.content.pm.PackageManager
import dev.onekey.jb.ShizukuShell
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.ZipFile

/**
 * 运行时自适应：从已安装管理器 APK 提取对应 KMI 的内核模块和 ksud。
 * 只负责“准备材料”，不负责加载；失败必须明确返回原因。
 */
object RuntimeExtractor {
    data class Module(
        val bytes: ByteArray,
        val sha256: String,
        val vermagic: String,
        val parms: List<String>
    )

    data class Adapter(
        val pkg: String,
        val kind: String,
        val versionCode: Int,
        val versionName: String,
        val apkSha256: String,
        val modulePath: String,
        val ksudPath: String,
        val moduleSha256: String,
        val moduleSize: Int,
        val vermagic: String,
        val certSha256: String
    )

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { String.format("%02x", it) }

    private fun readAll(f: File): ByteArray = f.inputStream().use { it.readBytes() }

    fun prepare(ctx: Context, pkg: String, kind: String, expectedCert: String, log: (String) -> Unit): Adapter {
        val emit = { m: String -> android.util.Log.i("OneKeyAdaptive", m); log(m) }
        val pm = ctx.packageManager
        val pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
        val versionName = pi.versionName ?: ""
        val certSha = pi.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let { sha256(it) }
            ?: throw IllegalStateException("无法读取管理器签名")
        if (certSha != expectedCert) throw IllegalStateException("管理器签名与固定信任根不一致")
        val app = pi.applicationInfo ?: throw IllegalStateException("无法定位管理器 APK")
        val paths = ArrayList<String>()
        app.sourceDir?.let { paths.add(it) }
        app.splitSourceDirs?.let { for (p in it) if (!paths.contains(p)) paths.add(p) }
        if (paths.isEmpty()) throw IllegalStateException("管理器 APK 路径为空")
        val tmpDir = "/data/local/tmp/onekey-adapters/" + System.nanoTime()
        val exactLibs = ArrayList<Pair<String, ByteArray>>()
        val fallbackLibs = ArrayList<Pair<String, ByteArray>>()
        val apkHashes = ArrayList<String>()
        try {
            for ((idx, path) in paths.withIndex()) {
                val tmp = tmpDir + "/" + idx + ".apk"
                val cp = ShizukuShell.run(
                    "set -e; mkdir -p " + shq(tmpDir) + "; chmod 755 " + shq(tmpDir) + "; cp -f " + shq(path) + " " + shq(tmp) +
                        "; chmod 644 " + shq(tmp) + "; echo COPIED", 30
                )
                if (cp.code != 0 || !cp.output.contains("COPIED")) throw IllegalStateException("读取管理器 APK 失败：" + cp.output)
                val apk = File(tmp)
                val bytes = readAll(apk)
                apkHashes.add(sha256(bytes))
                ZipFile(apk).use { z ->
                    val entries = z.entries().asSequence()
                        .filter { it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                        .toList()
                    val exact = entries.filter { it.name == "lib/arm64-v8a/libksud.so" }
                    val picks = if (exact.isNotEmpty()) exact else entries.sortedByDescending { it.size }.take(1)
                    for (e in picks) {
                        val pair = e.name to z.getInputStream(e).use { it.readBytes() }
                        if (exact.isNotEmpty()) exactLibs.add(pair) else fallbackLibs.add(pair)
                    }
                }
            }
            val libs = exactLibs + fallbackLibs
            if (libs.isEmpty()) throw IllegalStateException("APK 不含 arm64 库")
            val apkSha = sha256(apkHashes.joinToString("").toByteArray(Charsets.US_ASCII))
            emit("APK: " + paths.size + " 个, 合并 sha256=" + apkSha.take(16) + "…")
            var chosen: Pair<String, ByteArray>? = null
            var mod: Module? = null
            for ((name, data) in libs) {
                emit("尝试库: " + name + " " + data.size + " B")
                val c = try { carve(data, log) } catch (e: Throwable) {
                    emit("库 " + name + " 提取失败：" + (e.message ?: e.javaClass.simpleName))
                    continue
                }
                chosen = name to data
                mod = c
                break
            }
            if (chosen == null || mod == null) throw IllegalStateException("未找到可用的 kernelsu 模块")
            val rel = ShizukuShell.run("uname -r", 5).output.trim()
            val patched = patchVermagic(mod, rel)
            emit("module: " + patched.bytes.size + " B, vermagic=" + patched.vermagic + ", parms=" + patched.parms)
            val dir = File(ctx.filesDir, "adapters/" + apkSha.take(24))
            dir.mkdirs()
            val moduleFile = File(dir, "module.ko")
            val ksudFile = File(dir, "ksud")
            moduleFile.writeBytes(patched.bytes)
            ksudFile.writeBytes(chosen.second)
            emit("已准备 adapter: " + dir.absolutePath)
            return Adapter(pkg, kind, versionCode, versionName, apkSha, moduleFile.absolutePath, ksudFile.absolutePath, patched.sha256, patched.bytes.size, patched.vermagic, certSha)
        } finally {
            ShizukuShell.run("rm -rf " + shq(tmpDir), 10)
        }
    }

    /** FinaSU: its APK exposes libksud.so but not the LKM. Pair the installed
     *  manager's libksud with the bundled matched Fina LKM. */
    fun prepareFina(ctx: Context, pkg: String, expectedCert: String, log: (String) -> Unit): Adapter {
        val pm = ctx.packageManager
        val pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
        val versionName = pi.versionName ?: ""
        val certSha = pi.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let { sha256(it) }
            ?: throw IllegalStateException("无法读取 FinaSU 签名")
        if (certSha != expectedCert) throw IllegalStateException("FinaSU 签名与固定信任根不一致")
        val source = pi.applicationInfo?.sourceDir ?: throw IllegalStateException("无法定位 FinaSU APK")
        val tmp = "/data/local/tmp/onekey-fina-" + System.nanoTime() + ".apk"
        val cp = ShizukuShell.run("cp -f " + shq(source) + " " + shq(tmp) + " && chmod 644 " + shq(tmp) + " && echo COPIED", 30)
        if (!cp.output.contains("COPIED")) throw IllegalStateException("复制 FinaSU APK 失败：" + cp.output)
        try {
            val apk = File(tmp)
            val apkBytes = readAll(apk)
            val ksud = ZipFile(apk).use { z ->
                val e = z.getEntry("lib/arm64-v8a/libksud.so")
                    ?: throw IllegalStateException("FinaSU APK 不含 libksud.so")
                z.getInputStream(e).use { it.readBytes() }
            }
            if (ksud.isEmpty()) throw IllegalStateException("FinaSU libksud 为空")
            val rel = com.pyb110.onekey.ghost.device.KernelProbe.read().trim()
            // 6.12 与 6.18 的 LKM 不能混用：按设备内核系列选择对应 KMI 的内置模块。
            val moduleAsset = if (rel.startsWith("6.12")) "core/finasu-6.12.ko" else "core/finasu.ko"
            val rawModule = ctx.assets.open(moduleAsset).use { it.readBytes() }
            val info = inspect(rawModule) ?: throw IllegalStateException("finasu.ko ELF 结构无效")
            val rawMod = Module(rawModule, sha256(rawModule), info.first, info.second)
            val patched = if (rel.isNotEmpty() && !info.first.startsWith(rel)) {
                log("FinaSU 自适应：内核 release ($rel) 与 finasu.ko (${info.first}) 不一致，执行 vermagic 动态对齐")
                patchVermagic(rawMod, rel)
            } else rawMod
            val dir = File(ctx.filesDir, "adapters/fina-" + versionCode + "-" + sha256(apkBytes).take(12))
            dir.mkdirs()
            val mf = File(dir, "module.ko").apply { writeBytes(patched.bytes) }
            val kf = File(dir, "ksud").apply { writeBytes(ksud) }
            log("FinaSU 自适应：libksud.so " + ksud.size + " B，LKM " + moduleAsset + " " + patched.bytes.size + " B (vermagic=" + patched.vermagic + ")")
            return Adapter(pkg, "finasu", versionCode, versionName, sha256(apkBytes), mf.absolutePath, kf.absolutePath, patched.sha256, patched.bytes.size, patched.vermagic, certSha)
        } finally {
            ShizukuShell.run("rm -f " + shq(tmp), 10)
        }
    }

    private fun carve(raw: ByteArray, log: (String) -> Unit, onlyEd: Boolean = true): Module {
        val started = System.currentTimeMillis()
        val out = ArrayList<Module>()
        var i = 0
        var tried = 0
        while (i < raw.size - 2) {
            if (System.currentTimeMillis() - started > 60_000) throw IllegalStateException("扫描超时（>60s），无法完成准备")
            // 实测这些管理器内嵌的裸 DEFLATE 流起始字节为 0xED/0xEB（动态 Huffman）。
            // 先按起始字节快筛，找不到再退回全量扫描，避免 5.5MB 每个偏移都创建 Inflater。
            val b = raw[i].toInt() and 0xff
            if (onlyEd && b != 0xED) { i++; continue }
            tried++
            if (tried % 20000 == 0) android.util.Log.i("OneKeyAdaptive", "carve tried=" + tried + " off=" + i)
            if (((raw[i].toInt() shr 1) and 3) == 3) { i++; continue }
            val inf = Inflater(true)
            var produced = 0
            try {
                inf.setInput(raw, i, raw.size - i)
                val probe = ByteArray(4)
                val n0 = try { inf.inflate(probe) } catch (e: Exception) { 0 }
                if (tried <= 3 || (i in 1_121_000..1_122_000)) android.util.Log.i("OneKeyAdaptive", "probe off=" + i + " n0=" + n0 + " b=" + String.format("%02x %02x %02x %02x", probe[0], probe[1], probe[2], probe[3]))
                if (n0 < 4 || probe[0] != 0x7f.toByte() || probe[1] != 'E'.code.toByte() || probe[2] != 'L'.code.toByte() || probe[3] != 'F'.code.toByte()) {
                    i++; continue
                }
                produced = 4
                val buf = ByteArray(64 * 1024)
                while (!inf.finished() && produced <= 900_000) {
                    val n = try { inf.inflate(buf) } catch (e: Exception) { break }
                    if (n <= 0) break
                    produced += n
                }
                if (produced in 65536..900_000) {
                    // 重新完整解压该流，拿到准确字节
                    val inf2 = Inflater(true)
                    inf2.setInput(raw, i, raw.size - i)
                    val data = ByteArray(produced)
                    var off = 0
                    while (!inf2.finished() && off < data.size) {
                        val n = try { inf2.inflate(data, off, data.size - off) } catch (e: Exception) { break }
                        if (n <= 0) break
                        off += n
                    }
                    inf2.end()
                    if (inf2.finished() && off == data.size) {
                        val info = inspect(data)
                        android.util.Log.i("OneKeyAdaptive", "decompressed off=" + i + " size=" + data.size + " info=" + (info != null))
                        if (info != null) {
                            out.add(Module(data, sha256(data), info.first, info.second))
                            val consumed = inf.bytesRead
                            i += maxOf(1, consumed.toInt())
                            continue
                        }
                    }
                }
            } finally {
                inf.end()
            }
            i++
        }
        android.util.Log.i("OneKeyAdaptive", "carve done tried=" + tried + " out=" + out.size)
        if (out.isEmpty()) {
            if (onlyEd) {
                log("按 0xED 快筛未命中，执行完整扫描（可能较慢）")
                return carve(raw, log, false)
            }
            throw IllegalStateException("未找到内嵌的 kernelsu 模块")
        }
        // 目标 KMI：6.18 且 vermagic 含 6.18；若多个，必须唯一
        val target = out.filter { it.vermagic.contains("6.18") || it.vermagic.startsWith("6.18") }
        if (target.size != 1) throw IllegalStateException("6.18 模块候选数量=" + target.size + "，无法唯一确定")
        return target[0]
    }

    private fun patchVermagic(m: Module, release: String): Module {
        if (release.isBlank()) throw IllegalStateException("无法读取内核 release，不能改写 vermagic")
        val elf = m.bytes
        val bb = java.nio.ByteBuffer.wrap(elf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun u16(o: Int) = bb.getShort(o).toInt() and 0xffff
        fun u32(o: Int) = bb.getInt(o)
        fun u64(o: Int) = bb.getLong(o)
        val shoff = u64(40)
        val shent = u16(58)
        val shnum = u16(60)
        val shstr = u16(62)
        if (shoff <= 0 || shent <= 0 || shnum <= 0 || shstr >= shnum) throw IllegalStateException("ELF section header 无效")
        val sOff = shoff.toInt() + shstr * shent
        val strOff = u64(sOff + 24).toInt()
        val strSize = u64(sOff + 32).toInt()
        if (strOff < 0 || strSize <= 0 || strOff + strSize > elf.size) throw IllegalStateException("ELF shstrtab 无效")
        var modIdx = -1
        for (i in 0 until shnum) {
            val o = shoff.toInt() + i * shent
            val nameOff = u32(o)
            var e = strOff + nameOff
            while (e < strOff + strSize && elf[e] != 0.toByte()) e++
            val name = String(elf, strOff + nameOff, e - (strOff + nameOff))
            if (name == ".modinfo") { modIdx = i; break }
        }
        if (modIdx < 0) throw IllegalStateException("模块缺少 .modinfo")
        val mo = shoff.toInt() + modIdx * shent
        val oldOff = u64(mo + 24).toInt()
        val oldSize = u64(mo + 32).toInt()
        if (oldOff < 0 || oldSize <= 0 || oldOff + oldSize > elf.size) throw IllegalStateException(".modinfo 范围无效")
        val old = elf.copyOfRange(oldOff, oldOff + oldSize)
        val parts = ArrayList<ByteArray>()
        var start = 0
        while (start < old.size) {
            var e = start
            while (e < old.size && old[e] != 0.toByte()) e++
            if (e > start) parts.add(old.copyOfRange(start, e))
            start = e + 1
        }
        var found = false
        val rebuilt = ArrayList<ByteArray>()
        for (part in parts) {
            val str = String(part, Charsets.US_ASCII)
            if (str.startsWith("vermagic=")) {
                val oldValue = str.removePrefix("vermagic=")
                val sp = oldValue.indexOf(' ')
                val suffix = if (sp >= 0) oldValue.substring(sp) else ""
                rebuilt.add(("vermagic=" + release + suffix).toByteArray(Charsets.US_ASCII))
                found = true
            } else rebuilt.add(part)
        }
        if (!found) throw IllegalStateException(".modinfo 缺少 vermagic")
        val out = java.io.ByteArrayOutputStream()
        for (part in rebuilt) { out.write(part); out.write(0) }
        out.write(0)
        val newInfo = out.toByteArray()
        val newOff = elf.size
        val result = elf.copyOf(elf.size + newInfo.size)
        System.arraycopy(newInfo, 0, result, newOff, newInfo.size)
        val rb = java.nio.ByteBuffer.wrap(result).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        rb.putLong(mo + 24, newOff.toLong())
        rb.putLong(mo + 32, newInfo.size.toLong())
        return Module(result, sha256(result), release, m.parms)
    }

    /** 解析 ELF .modinfo，返回 (vermagic, parmtype)。 */
    private fun inspect(elf: ByteArray): Pair<String, List<String>>? {
        try {
            if (elf.size < 64 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() || elf[2] != 'L'.code.toByte() || elf[3] != 'F'.code.toByte()) return null
            val le = java.nio.ByteBuffer.wrap(elf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (le.getShort(16).toInt() != 1) return null // ET_REL
            fun field(key: String): String? {
                val needle = key.toByteArray(Charsets.US_ASCII)
                var from = 0
                while (true) {
                    var hit = -1
                    outer@ for (i in from..elf.size - needle.size) {
                        for (j in needle.indices) if (elf[i + j] != needle[j]) continue@outer
                        hit = i; break
                    }
                    if (hit < 0) return null
                    val v = hit + needle.size
                    var e = v
                    while (e < elf.size && elf[e] != 0.toByte()) e++
                    if (e > v) return String(elf, v, e - v)
                    from = hit + 1
                }
            }
            if (field("name=") != "kernelsu") return null
            val vermagic = field("vermagic=") ?: return null
            val parms = ArrayList<String>()
            val needle = "parmtype=".toByteArray(Charsets.US_ASCII)
            var from = 0
            while (true) {
                var hit = -1
                outer@ for (i in from..elf.size - needle.size) {
                    for (j in needle.indices) if (elf[i + j] != needle[j]) continue@outer
                    hit = i; break
                }
                if (hit < 0) break
                val v = hit + needle.size
                var e = v
                while (e < elf.size && elf[e] != 0.toByte()) e++
                if (e > v) parms.add(String(elf, v, e - v))
                from = hit + needle.size
            }
            return Pair(vermagic, parms)
        } catch (e: Throwable) {
            return null
        }
    }
}
