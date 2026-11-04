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

    /**
     * 把内置模块的 vermagic 对齐到设备实际 release。
     * 同一 6.18.21 换 OTA 时 release 串会变（-g<commit>-ab<build>），内置模块不能硬跟旧串。
     */
    fun alignVermagic(bytes: ByteArray, release: String): ByteArray {
        if (release.isBlank()) return bytes
        val info = inspect(bytes) ?: return bytes
        if (info.first == release || info.first.startsWith(release + " ")) return bytes
        return patchVermagic(Module(bytes, sha256(bytes), info.first, info.second), release).bytes
    }

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
        val libs = ArrayList<Pair<String, ByteArray>>()
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
                    for (e in z.entries().asSequence().filter { it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }) {
                        libs.add(e.name to z.getInputStream(e).use { it.readBytes() })
                    }
                }
            }
            if (libs.isEmpty()) throw IllegalStateException("APK 不含 arm64 库")
            val apkSha = sha256(apkHashes.joinToString("").toByteArray(Charsets.US_ASCII))
            emit("APK: " + paths.size + " 个, 合并 sha256=" + apkSha.take(16) + "…")
            // ksud 永远是 libksud.so；个别分支改了库名，退化为最大库。
            val ksud = libs.firstOrNull { it.first.endsWith("/libksud.so") } ?: libs.maxByOrNull { it.second.size }!!
            // 内核模块可能放在任意库里：按体积从大到小逐个找，命中即停。
            var mod: Module? = null
            for ((name, data) in libs.sortedByDescending { it.second.size }) {
                val found = findModule(data, name, emit)
                if (found != null) {
                    mod = found
                    break
                }
            }
            val rel = ShizukuShell.run("uname -r", 5).output.trim()
            val dir = File(ctx.filesDir, "adapters/" + apkSha.take(24))
            dir.mkdirs()
            val ksudFile = File(dir, "ksud").apply { writeBytes(ksud.second) }
            if (mod == null) {
                // 当前各家管理器的 APK 都不再内嵌 LKM：ksud 照用管理器的，模块交给云端/内置。
                emit("管理器 APK 未内嵌内核模块（已检查 " + libs.size + " 个 arm64 库），内核模块改用云端或内置组件")
                return Adapter(pkg, kind, versionCode, versionName, apkSha, "", ksudFile.absolutePath, "", 0, "", certSha)
            }
            val patched = patchVermagic(mod, rel)
            emit("module: " + patched.bytes.size + " B, vermagic=" + patched.vermagic + ", parms=" + patched.parms)
            val moduleFile = File(dir, "module.ko").apply { writeBytes(patched.bytes) }
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
            val dir = File(ctx.filesDir, "adapters/fina-" + versionCode + "-" + sha256(apkBytes).take(12))
            dir.mkdirs()
            val ksudOut = File(dir, "ksud").apply { writeBytes(ksud) }
            // 不再内置 LKM：模块交给云端，未命中时由执行器用管理器 ksud 自带的 late-load 数据。
            val moduleAsset = if (rel.startsWith("6.12")) "core/finasu-6.12.ko" else "core/finasu.ko"
            val rawModule = runCatching { ctx.assets.open(moduleAsset).use { it.readBytes() } }.getOrNull()
            if (rawModule == null) {
                log("FinaSU 自适应：仅取管理器 libksud（" + ksud.size + " B），内核模块改用云端或 ksud 内置")
                return Adapter(pkg, "finasu", versionCode, versionName, sha256(apkBytes), "", ksudOut.absolutePath, "", 0, "", certSha)
            }
            val info = inspect(rawModule) ?: throw IllegalStateException("finasu.ko ELF 结构无效")
            val rawMod = Module(rawModule, sha256(rawModule), info.first, info.second)
            val patched = if (rel.isNotEmpty() && !info.first.startsWith(rel)) {
                log("FinaSU 自适应：内核 release ($rel) 与 finasu.ko (${info.first}) 不一致，执行 vermagic 动态对齐")
                patchVermagic(rawMod, rel)
            } else rawMod
            val mf = File(dir, "module.ko").apply { writeBytes(patched.bytes) }
            val kf = ksudOut
            log("FinaSU 自适应：libksud.so " + ksud.size + " B，LKM " + moduleAsset + " " + patched.bytes.size + " B (vermagic=" + patched.vermagic + ")")
            return Adapter(pkg, "finasu", versionCode, versionName, sha256(apkBytes), mf.absolutePath, kf.absolutePath, patched.sha256, patched.bytes.size, patched.vermagic, certSha)
        } finally {
            ShizukuShell.run("rm -f " + shq(tmp), 10)
        }
    }

    /**
     * 在管理器 .so 里找内核模块。两条路径，都带时间上限：
     * 1) 裸 ELF（当前各家管理器的实际做法，模块被原样拼进 libksud.so 一类的大库里）；
     * 2) 裸 DEFLATE 流（历史格式，起始字节多为 0xED）。
     * 找不到返回 null —— 由调用方回落到云端或内置模块，绝不在这里抛超时。
     */
    private fun findModule(raw: ByteArray, name: String, log: (String) -> Unit): Module? {
        val started = System.currentTimeMillis()
        val elf = rawElf(raw)
        if (elf != null) {
            log("库 " + name + " 内嵌裸 ELF 模块 " + elf.bytes.size + " B")
            return elf
        }
        val fast = deflate(raw, false, 12_000L)
        val picked = pick618(fast)
        if (picked != null) {
            log("库 " + name + " 内嵌压缩模块 " + picked.bytes.size + " B（候选 " + fast.size + "）")
            return picked
        }
        // 只有体积不大的库才值得全量扫描，避免把 6 MB 的二进制拖成 60 秒。
        if (raw.size <= 3_000_000) {
            val full = deflate(raw, true, 15_000L)
            val deep = pick618(full)
            if (deep != null) {
                log("库 " + name + " 全量扫描命中模块 " + deep.bytes.size + " B")
                return deep
            }
        }
        log("库 " + name + " 无内核模块（" + (System.currentTimeMillis() - started) + " ms）")
        return null
    }

    /** 6.18 候选选择：唯一最好；多个时取体积最大的并说明，避免整条链路无谓失败。 */
    private fun pick618(list: List<Module>): Module? {
        val target = list.filter { it.vermagic.contains("6.18") || it.vermagic.startsWith("6.18") }
        if (target.isEmpty()) return null
        if (target.size == 1) return target[0]
        return target.maxByOrNull { it.bytes.size }
    }

    /** 直接在库里扫裸 ELF（ET_REL + AArch64 + 有 .modinfo），按 section 表算真实长度。 */
    private fun rawElf(raw: ByteArray): Module? {
        var i = 0
        var seen = 0
        while (i < raw.size - 64 && seen < 64) {
            if (raw[i] != 0x7f.toByte() || raw[i + 1] != 'E'.code.toByte() ||
                raw[i + 2] != 'L'.code.toByte() || raw[i + 3] != 'F'.code.toByte()
            ) {
                i++
                continue
            }
            seen++
            readElfAt(raw, i)?.let { return it }
            i++
        }
        return null
    }

    private fun readElfAt(raw: ByteArray, off: Int): Module? {
        if (off + 64 > raw.size) return null
        if (raw[off + 4] != 2.toByte() || raw[off + 5] != 1.toByte()) return null
        val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (bb.getShort(off + 16).toInt() != 1) return null      // ET_REL
        if (bb.getShort(off + 18).toInt() != 183) return null    // AArch64
        val shoff = bb.getLong(off + 0x28)
        val shent = bb.getShort(off + 0x3A).toInt() and 0xffff
        val shnum = bb.getShort(off + 0x3C).toInt() and 0xffff
        val shstr = bb.getShort(off + 0x3E).toInt() and 0xffff
        if (shoff <= 0 || shent != 64 || shnum <= 0 || shnum > 256 || shstr >= shnum) return null
        val base = off + shoff.toInt()
        if (base.toLong() + shnum.toLong() * shent > raw.size) return null
        var end = (base + shnum * shent).toLong()
        for (i in 0 until shnum) {
            val b = base + i * shent
            val type = bb.getInt(b + 4)
            val sOff = bb.getLong(b + 0x18)
            val sSize = bb.getLong(b + 0x20)
            if (type != 8 && sOff > 0 && sSize > 0) {           // 非 NOBITS
                val e = off + sOff + sSize
                if (e > raw.size || e < off) return null
                if (e > end) end = e
            }
        }
        if (end - off < 4096) return null
        val bytes = raw.copyOfRange(off, end.toInt())
        val info = inspect(bytes) ?: return null
        return Module(bytes, sha256(bytes), info.first, info.second)
    }

    /** 扫裸 DEFLATE 流；full=true 时逐个偏移尝试（耗时，调用方必须限体积与时间）。 */
    private fun deflate(raw: ByteArray, full: Boolean, budgetMs: Long): List<Module> {
        val started = System.currentTimeMillis()
        val out = ArrayList<Module>()
        var i = 0
        while (i < raw.size - 2) {
            if (System.currentTimeMillis() - started > budgetMs) break
            if (!full && (raw[i].toInt() and 0xff) != 0xED) { i++; continue }
            if (((raw[i].toInt() shr 1) and 3) == 3) { i++; continue }
            val inf = Inflater(true)
            try {
                inf.setInput(raw, i, raw.size - i)
                val probe = ByteArray(4)
                val n0 = try { inf.inflate(probe) } catch (e: Exception) { 0 }
                if (n0 < 4 || probe[0] != 0x7f.toByte() || probe[1] != 'E'.code.toByte() ||
                    probe[2] != 'L'.code.toByte() || probe[3] != 'F'.code.toByte()
                ) {
                    i++; continue
                }
                var produced = 4
                val buf = ByteArray(64 * 1024)
                while (!inf.finished() && produced <= 900_000) {
                    val n = try { inf.inflate(buf) } catch (e: Exception) { break }
                    if (n <= 0) break
                    produced += n
                }
                if (produced in 65536..900_000) {
                    val inf2 = Inflater(true)
                    inf2.setInput(raw, i, raw.size - i)
                    val data = ByteArray(produced)
                    var pos = 0
                    while (!inf2.finished() && pos < data.size) {
                        val n = try { inf2.inflate(data, pos, data.size - pos) } catch (e: Exception) { break }
                        if (n <= 0) break
                        pos += n
                    }
                    inf2.end()
                    if (inf2.finished() && pos == data.size) {
                        val info = inspect(data)
                        if (info != null) {
                            out.add(Module(data, sha256(data), info.first, info.second))
                            i += maxOf(1, inf.bytesRead.toInt())
                            continue
                        }
                    }
                }
            } finally {
                inf.end()
            }
            i++
        }
        return out
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
