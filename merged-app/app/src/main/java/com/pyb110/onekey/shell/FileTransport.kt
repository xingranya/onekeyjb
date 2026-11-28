package com.pyb110.onekey.shell

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * 文件队列传输层。
 *
 * 为什么需要它：抽象 Unix socket 在 SELinux 上是跨域连接，
 *   avc: denied { connectto } ... scontext=u:r:untrusted_app:s0 tcontext=u:r:shell:s0
 * 这条策略无法从应用侧绕过（不是路径权限，是域对不匹配）。
 * 而文件访问是通的 —— token、start.sh 都是这么传的，实测可读可写。
 *
 * 目录布局（全在 getExternalFilesDir/onekey-shell 下，分区存储保护）：
 *   q/<id>.sh  .env  .cwd  .to  请求体，最后写 .go 作为原子触发
 *   r/<id>.out .err .rc         结果体，.rc 出现即完成
 *   k/<id>                      终止请求
 *
 * 服务端每 40 ms 扫一次 q/，客户端每 30 ms 扫一次 r/。
 * 这个延迟对「读日志行决定是否中止」完全够用，而换来的是：
 * 不依赖 SELinux 域、不依赖隐藏 API、不依赖 binder，
 * 服务端或 App 任一方重启后队列仍在磁盘上，不会丢请求。
 */
object FileTransport {

    private const val POLL_MS = 40L

    fun root(context: Context): File = ShellSecrets.dir(context)
    private fun q(context: Context) = File(root(context), "q").also { it.mkdirs() }
    private fun r(context: Context) = File(root(context), "r").also { it.mkdirs() }
    private fun k(context: Context) = File(root(context), "k").also { it.mkdirs() }

    // ------------------------------------------------------------ 客户端

    class Handle internal constructor(
        private val ctx: Context,
        val id: Int,
        private val sink: ((String) -> Unit)?,
    ) {
        private val outFile get() = File(r(ctx), id.toString() + ".out")
        private val errFile get() = File(r(ctx), id.toString() + ".err")
        private val rcFile get() = File(r(ctx), id.toString() + ".rc")

        private var outOff = 0L
        private var errOff = 0L
        private val lineBuf = StringBuilder()
        private val history = ArrayList<String>()

        @Volatile var done = false
        @Volatile var code: Int? = null

        /** 读增量输出并推给 sink；返回是否已结束。 */
        fun poll(): Boolean {
            drain(outFile, true)
            drain(errFile, false)
            if (!done && rcFile.isFile) {
                val v = runCatching { rcFile.readText().trim().toInt() }.getOrDefault(-1)
                code = v
                done = true
                drain(outFile, true)
                drain(errFile, false)
            }
            return done
        }

        private fun drain(f: File, isOut: Boolean) {
            val len = f.length()
            if (len <= (if (isOut) outOff else errOff)) return
            runCatching {
                RandomAccessFile(f, "r").use { raf ->
                    val start = if (isOut) outOff else errOff
                    raf.seek(start)
                    val buf = ByteArray((len - start).toInt().coerceAtMost(1 shl 20))
                    val n = raf.read(buf)
                    if (n <= 0) return
                    if (isOut) outOff = start + n else errOff = start + n
                    emit(String(buf, 0, n, Charsets.UTF_8))
                }
            }
        }

        private fun emit(text: String) {
            if (text.isEmpty()) return
            val lines = ArrayList<String>()
            synchronized(lineBuf) {
                for (ch in text) {
                    if (ch == '\n') {
                        val l = lineBuf.toString().trimEnd('\r')
                        lineBuf.setLength(0)
                        if (l.isNotEmpty()) lines.add(l)
                    } else {
                        lineBuf.append(ch)
                    }
                }
                if (lineBuf.length > 64 * 1024) lineBuf.setLength(0)
            }
            if (lines.isEmpty()) return
            synchronized(history) {
                for (l in lines) {
                    if (history.size >= 2048) history.removeAt(0)
                    history.add(l)
                }
            }
            val cb = sink ?: return
            for (l in lines) runCatching { cb.invoke(l) }
        }

        fun text(): String = runCatching { outFile.readText().trim() }.getOrDefault("")

        fun tail(n: Int): String = synchronized(history) { history.takeLast(n).joinToString("\n") }

        fun waitFor(needle: String, timeoutSec: Long): String? {
            val end = System.currentTimeMillis() + timeoutSec * 1000
            while (System.currentTimeMillis() < end) {
                synchronized(history) { for (l in history) if (l.contains(needle)) return l }
                if (done) return null
                Thread.sleep(50)
            }
            return null
        }

        fun kill() {
            runCatching { File(k(ctx), id.toString()).writeText("1") }
        }

        fun cleanup() {
            runCatching { outFile.delete() }
            runCatching { errFile.delete() }
            runCatching { rcFile.delete() }
            runCatching { File(k(ctx), id.toString()).delete() }
        }
    }

    /** 提交一次执行；返回句柄，失败返回 null。 */
    fun submit(
        context: Context,
        id: Int,
        script: String,
        timeoutSec: Long,
        env: List<String>,
        cwd: String,
        sink: ((String) -> Unit)?,
    ): Handle? {
        return try {
            val qd = q(context)
            val base = File(qd, id.toString())
            File(base.path + ".sh").writeText(script)
            File(base.path + ".env").writeText(env.joinToString("\n"))
            File(base.path + ".cwd").writeText(cwd)
            File(base.path + ".to").writeText(timeoutSec.toString())
            // .go 最后写：它的出现就是「请求完整」的信号，
            // 服务端看到 .go 才读其它文件，避免读到写了一半的脚本。
            File(base.path + ".go").writeText("1")
            Handle(context, id, sink)
        } catch (_: Throwable) {
            null
        }
    }

    /** 清理历史遗留的结果文件，避免外部目录无限增长。 */
    fun sweep(context: Context, keep: Int = 200) {
        runCatching {
            val files = r(context).listFiles()?.sortedByDescending { it.lastModified() } ?: return
            files.drop(keep).forEach { runCatching { it.delete() } }
            q(context).listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 600_000 }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    // ------------------------------------------------------------ 服务端

    /**
     * 服务端主循环：扫 q/，派发执行。
     *
     * 每个请求一个线程，互不阻塞；主循环只做扫描与派发，
     * 因此单个长任务不会拖住后续请求（引擎流式输出 + 环境探测会并发）。
     */
    fun serveLoop(context: Context?, dir: File, log: (String) -> Unit) {
        val qd = File(dir, "q").also { it.mkdirs() }
        val rd = File(dir, "r").also { it.mkdirs() }
        val kd = File(dir, "k").also { it.mkdirs() }
        // 心跳文件：客户端靠它的 mtime 判断服务端还活着。
        // 没有它，客户端无法区分「服务端没起来」和「服务端起来了但请求还在排队」，
        // 前者应该立刻回落 Stellar，后者应该再等等。
        val hb = File(dir, "heartbeat")
        var lastBeat = 0L
        val seen = HashSet<Int>()
        while (true) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastBeat > 3000) {
                    runCatching { hb.writeText(now.toString()) }
                    lastBeat = now
                }
                val gos = qd.listFiles { f -> f.name.endsWith(".go") } ?: emptyArray()
                for (go in gos) {
                    val id = go.name.removeSuffix(".go").toIntOrNull() ?: continue
                    if (!seen.add(id)) continue
                    val base = File(qd, id.toString())
                    val script = runCatching { File(base.path + ".sh").readText() }.getOrNull() ?: continue
                    val env = runCatching { File(base.path + ".env").readText() }.getOrNull()
                        ?.lines()?.filter { it.isNotBlank() } ?: emptyList()
                    val cwd = runCatching { File(base.path + ".cwd").readText().trim() }.getOrNull() ?: "/"
                    val to = runCatching { File(base.path + ".to").readText().trim().toLong() }.getOrDefault(120L)
                    Thread {
                        runOne(id, script, env, cwd, to, rd, kd, base, log)
                        seen.remove(id)
                    }.start()
                }
            } catch (t: Throwable) {
                log("文件队列扫描异常：" + t)
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun runOne(
        id: Int,
        script: String,
        env: List<String>,
        cwd: String,
        timeoutSec: Long,
        rd: File,
        kd: File,
        base: File,
        log: (String) -> Unit,
    ) {
        val outFile = File(rd, id.toString() + ".out")
        val errFile = File(rd, id.toString() + ".err")
        val rcFile = File(rd, id.toString() + ".rc")
        val killFile = File(kd, id.toString())
        val started = System.currentTimeMillis()
        var p: Process? = null
        try {
            val scriptFile = File.createTempFile("okx-", ".sh", File("/data/local/tmp").takeIf { it.isDirectory && it.canWrite() })
            scriptFile.writeText(script)
            scriptFile.setExecutable(true, false)
            val pb = ProcessBuilder("/system/bin/sh", scriptFile.absolutePath)
            pb.directory(File(cwd.ifBlank { "/" }))
            val em = pb.environment()
            em.clear()
            em["PATH"] = "/system/bin:/system/xbin:/system_ext/bin:/data/adb/ksu/bin:/data/adb/modules"
            em["HOME"] = "/data/local/tmp"
            em["TMPDIR"] = "/data/local/tmp"
            for (kv in env) {
                val i = kv.indexOf('=')
                if (i > 0) em[kv.substring(0, i)] = kv.substring(i + 1)
            }
            val proc = pb.start()
            p = proc
            val tOut = Thread { copy(proc.inputStream, outFile) }
            val tErr = Thread { copy(proc.errorStream, errFile) }
            tOut.start(); tErr.start()
            val deadline = started + timeoutSec.coerceIn(1, 3600) * 1000
            var code = -1
            while (true) {
                if (killFile.isFile) {
                    log("收到终止请求 id=" + id)
                    proc.destroyForcibly()
                    code = -1
                    break
                }
                if (!proc.isAlive) { code = proc.exitValue(); break }
                if (System.currentTimeMillis() > deadline) {
                    log("执行超时 id=" + id)
                    proc.destroyForcibly()
                    code = -1
                    break
                }
                Thread.sleep(30)
            }
            tOut.join(2000); tErr.join(2000)
            rcFile.writeText(code.toString())
            runCatching { scriptFile.delete() }
        } catch (t: Throwable) {
            log("执行异常 id=" + id + "：" + t)
            runCatching { rcFile.writeText("-2") }
        } finally {
            runCatching { p?.destroyForcibly() }
            for (suffix in listOf(".sh", ".env", ".cwd", ".to", ".go")) {
                runCatching { File(base.path + suffix).delete() }
            }
            runCatching { killFile.delete() }
        }
    }

    private fun copy(input: java.io.InputStream, dest: File) {
        try {
            java.io.FileOutputStream(dest, true).use { fos ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    fos.flush()
                }
            }
        } catch (_: Throwable) {
        }
    }
}
