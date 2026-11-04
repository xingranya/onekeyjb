package com.pyb110.onekey

import android.content.Context
import roro.stellar.Stellar
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Stellar (Shizuku deep-fork) privileged shell channel. */
object StellarShell {
    const val PERMISSION = "stellar"
    const val TMP = "/data/local/tmp"

    fun available(): Boolean = try { Stellar.pingBinder() } catch (t: Throwable) { false }

    fun hasPermission(): Boolean = try { available() && Stellar.checkSelfPermission(PERMISSION) } catch (t: Throwable) { false }

    fun serviceUid(): Int = try { Stellar.uid } catch (t: Throwable) { -1 }

    fun requestPermission(code: Int) { try { if (available()) Stellar.requestPermission(PERMISSION, code) } catch (t: Throwable) { } }

    private fun env(extra: Array<String>? = null): Array<String> {
        val base = mutableListOf(
            "PATH=/system/bin:/system/xbin:/system_ext/bin:/data/adb/ksu/bin",
            "HOME=" + TMP,
            "TMPDIR=" + TMP
        )
        if (extra != null) base.addAll(extra)
        return base.toTypedArray()
    }

    @Suppress("UNCHECKED_CAST")
    fun process(cmd: Array<String>, extraEnv: Array<String>? = null, dir: String = TMP): Process {
        val c = cmd as Array<String?>
        val e = env(extraEnv) as Array<String?>
        return Stellar.newProcess(c, e, dir)
    }

    private fun waitTimeout(p: Process, timeoutSec: Long): Boolean {
        val t = Thread { try { p.waitFor() } catch (_: Exception) { } }
        t.start()
        return try {
            t.join(timeoutSec * 1000)
            !t.isAlive
        } catch (_: InterruptedException) { false }
    }

    fun shell(script: String, extraEnv: Array<String>? = null): Process {
        return process(arrayOf("sh", "-c", script), extraEnv)
    }

    data class Result(val code: Int, val output: String)

    fun run(script: String, timeoutSec: Long = 60, extraEnv: Array<String>? = null): Result {
        return try {
            val p = shell("(" + script + ") 2>&1", extraEnv)
            val out = StringBuilder()
            val t = Thread { drain(p.inputStream, out) }
            t.start()
            val finished = waitTimeout(p, timeoutSec)
            if (!finished) { p.destroy(); t.join(1000) } else t.join(2000)
            Result(if (finished) p.exitValue() else -1, out.toString().trim())
        } catch (e: Exception) {
            Result(-1, "Stellar 执行失败: " + e)
        }
    }

    fun runStreaming(script: String, timeoutSec: Long = 60, sink: ((String) -> Unit)? = null): Result {
        return try {
            val p = shell("(" + script + ") 2>&1")
            val out = StringBuilder()
            val t = Thread {
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) { synchronized(out) { out.append(line).append('\n') }; sink?.invoke(line) }
                    }
                } catch (_: Exception) { }
            }
            t.start()
            val finished = waitTimeout(p, timeoutSec)
            if (!finished) { p.destroy(); t.join(1000) } else t.join(2000)
            Result(if (finished) p.exitValue() else -1, synchronized(out) { out.toString().trim() })
        } catch (e: Exception) {
            Result(-1, "Stellar 执行失败: " + e)
        }
    }

    private fun drain(input: InputStream, sb: StringBuilder) {
        try { input.bufferedReader().useLines { lines -> for (line in lines) synchronized(sb) { sb.append(line).append('\n') } } } catch (_: Exception) { }
    }

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun stageBytes(data: ByteArray, dest: String, mode: Int = 755): Result {
        return try {
            val tmp = dest + ".tmp." + System.nanoTime()
            val dir = dest.substringBeforeLast('/')
            val p = shell("set -e; mkdir -p " + quote(dir) + "; cat > " + quote(tmp))
            p.outputStream.use { it.write(data) }
            val done = waitTimeout(p, 120)
            if (!done) { p.destroy(); return Result(-1, "写入超时: " + dest) }
            run("set -e; mv -f " + quote(tmp) + " " + quote(dest) + "; chmod " + mode + " " + quote(dest) + "; [ $(wc -c < " + quote(dest) + ") -eq " + data.size + " ]", 60)
        } catch (e: Exception) {
            Result(-1, "投放失败: " + dest + " " + e)
        }
    }

    fun stageAsset(ctx: Context, asset: String, dest: String, mode: Int = 755): Result {
        return try { ctx.assets.open(asset).use { stageBytes(it.readBytes(), dest, mode) } }
            catch (e: Exception) { Result(-1, "读取组件失败: " + asset + " " + e) }
    }

    fun execSu(script: String, timeoutSec: Long = 60): Result = run("su -c " + quote(script), timeoutSec)
}
