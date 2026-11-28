package com.pyb110.onekey

import android.content.Context
import roro.stellar.Stellar
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Stellar (Shizuku deep-fork) privileged shell channel.
 *
 * 2026-11 起本类同时承担「路由」职责：自研 OneKeyShell 可用时，
 * 所有执行类调用先走自研通道，Stellar 只在自研通道不可用时兜底。
 *
 * 这样做而不是逐个改 106 处调用点，有两个理由：
 * 1. 调用点分散在 11 个文件里，逐个替换必然漏改，而漏改的那一处会在
 *    「用户已经卸载 Stellar」时静默失败 —— 这类 bug 只能靠真机撞。
 * 2. 自研通道需要一次引导（root 或 adb）才能起来，引导之前 Stellar 仍然可用；
 *    保留原类名让两条路并存，任何一条活着 App 就完整可用。
 *
 * 注意：available() / hasPermission() / serviceUid() 保持「只反映 Stellar 自身状态」，
 * 不做路由 —— 否则 ShellBootstrap 就没法判断「Stellar 能不能兜底」了。
 * 需要「任意通道可用」的语义请用 OneKeyShell.available() || StellarShell.available()。
 */
object StellarShell {
    const val PERMISSION = "stellar"
    const val TMP = "/data/local/tmp"

    /** 自研通道是否接管执行。 */
    private fun self(): Boolean = com.pyb110.onekey.shell.OneKeyShell.available()

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
        if (self()) {
            val r = com.pyb110.onekey.shell.OneKeyShell.run(script, timeoutSec, extraEnv)
            return Result(r.code, r.output)
        }
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
        if (self()) {
            val r = com.pyb110.onekey.shell.OneKeyShell.runStreaming(script, timeoutSec, sink)
            return Result(r.code, r.output)
        }
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
        if (self()) {
            val r = com.pyb110.onekey.shell.OneKeyShell.stageBytes(data, dest, mode)
            return Result(r.code, r.output)
        }
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

    /**
     * 绕过路由，强制走 Stellar 本体。
     *
     * ShellBootstrap 用 su 拉起自研服务端时必须是这条路：
     * 那时自研通道还没起来，走路由会直接失败；而一旦它起来了，
     * 又不需要再拉一次。两个方向都得用「真 Stellar」。
     */
    fun runDirect(script: String, timeoutSec: Long = 60): Result {
        return try {
            val p = shell("(" + script + ") 2>&1")
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
}
