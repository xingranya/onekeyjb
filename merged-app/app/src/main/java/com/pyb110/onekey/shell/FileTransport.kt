package com.pyb110.onekey.shell

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

    /**
     * 请求签名。
     *
     * 文件队列必须自己鉴权：q/ 在外部私有目录下，能写它的主体不止本 App
     * （adb shell、shell 域进程、root 都能）。没有签名的话，
     * 「特权 shell 只对持有 token 的调用方开放」这条承诺在兜底路径上就不成立 ——
     * 服务端会以自身 uid（可能是 root）执行任何投进来的脚本。
     *
     * 签名材料就是 token 本身：它本来就以文件形式放在同一个目录，
     * shell 读得到、App 读得到，而其它 App 读不到（分区存储保护）。
     */
    private fun sign(token: String, id: Int, timeoutSec: Long, cwd: String, env: List<String>, script: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hexToBytes(token), "HmacSHA256"))
        mac.update((id.toString() + "\n" + timeoutSec + "\n" + cwd + "\n").toByteArray(Charsets.UTF_8))
        mac.update(env.joinToString("\n").toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(script.toByteArray(Charsets.UTF_8))
        return mac.doFinal().joinToString("") { String.format("%02x", it) }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

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

        /** 读增量输出并推给 sink；返回是否已结束。幂等，可任意次调用。 */
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
                // done 之后要再扫一遍：标记可能正好在最后一批数据里，
                // 上一轮扫描发生在它落盘之前，此时直接 return null 会把成功判成失败。
                if (done) {
                    poll()
                    synchronized(history) { for (l in history) if (l.contains(needle)) return l }
                    return null
                }
                poll()
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
            // 必须先清掉上一次同名 id 的结果。
            // execSeq 是进程内计数器，App 重启后从 1 重新开始，
            // 而 r/<id>.rc 还留在磁盘上 —— 不清的话第一次 poll() 立刻看到旧的 rc，
            // 调用方拿到的是「上一次」的退出码和残留输出，脚本可能根本没跑。
            // 这类 bug 不报错、只给错数据，比直接失败更难查。
            for (suffix in listOf(".out", ".err", ".rc")) {
                runCatching { File(r(context), id.toString() + suffix).delete() }
            }
            runCatching { File(k(context), id.toString()).delete() }
            val tok = runCatching { File(root(context), "token").readText().trim() }.getOrDefault("")
            if (tok.length != 64) return null
            File(base.path + ".sh").writeText(script)
            File(base.path + ".env").writeText(env.joinToString("\n"))
            File(base.path + ".cwd").writeText(cwd)
            File(base.path + ".to").writeText(timeoutSec.toString())
            File(base.path + ".sig").writeText(sign(tok, id, timeoutSec, cwd, env, script))
            // .go 最后写：它的出现就是「请求完整」的信号，
            // 服务端看到 .go 才读其它文件，避免读到写了一半的脚本。
            File(base.path + ".go").writeText("1")
            Handle(context, id, sink)
        } catch (_: Throwable) {
            null
        }
    }

    /** 清理历史遗留的结果文件，避免外部目录无限增长。 */
    fun sweep(context: Context, keep: Int = 200) = sweepDir(root(context), keep)

    /** 同 [sweep]，但按目录而不是按 Context —— 服务端没有 Context。 */
    fun sweepDir(dir: File, keep: Int = 200) {
        runCatching {
            val rd = File(dir, "r")
            val files = rd.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            files.drop(keep).forEach { runCatching { it.delete() } }
            File(dir, "q").listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 600_000 }
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
        // 并发上限：每个请求一个线程，不设上限的话
        // 「引擎流式输出 + 每秒采样 + UI 探测 + 诊断脚本」叠起来能瞬间起上百个线程，
        // 每个都 fork 一个 sh，root 服务端被自己的线程数拖垮。
        // 满了就先不派发（从 seen 撤掉，下一轮重试），客户端只是多等一个轮询周期。
        val running = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrent = 16
        // 队列签名用的 token：与 socket 鉴权同一份材料，就在同一个目录里。
        val tokenHex = runCatching { File(dir, "token").readText().trim() }.getOrDefault("")
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
                    // 读不到 .sh 说明写端还没落盘（submit 是 4 次独立 writeText，没有整体原子性），
                    // 必须把 id 从 seen 里撤掉，否则这个请求永久丢失、客户端只能等到超时。
                    val script = runCatching { File(base.path + ".sh").readText() }.getOrNull()
                    if (script == null) {
                        seen.remove(id)
                        continue
                    }
                    val env = runCatching { File(base.path + ".env").readText() }.getOrNull()
                        ?.lines()?.filter { it.isNotBlank() } ?: emptyList()
                    val cwd = runCatching { File(base.path + ".cwd").readText().trim() }.getOrNull() ?: "/"
                    val to = runCatching { File(base.path + ".to").readText().trim().toLong() }.getOrDefault(120L)
                    val sig = runCatching { File(base.path + ".sig").readText().trim() }.getOrDefault("")
                    // 验签失败一律拒绝执行：q/ 目录不只本 App 可写（adb shell、shell 域进程、root 都能写），
                    // 服务端以自身 uid 执行，不验签等于给全设备开了一个 root 执行口。
                    val want = if (tokenHex.length == 64) sign(tokenHex, id, to, cwd, env, script) else ""
                    if (want.isEmpty() || !MessageDigest.isEqual(want.toByteArray(), sig.toByteArray())) {
                        log("文件队列请求验签失败，已拒绝 id=" + id)
                        runCatching {
                            val t = File(rd, id.toString() + ".rc.tmp")
                            t.writeText("-3")
                            t.renameTo(File(rd, id.toString() + ".rc"))
                        }
                        for (suffix in listOf(".sh", ".env", ".cwd", ".to", ".go", ".sig")) {
                            runCatching { File(base.path + suffix).delete() }
                        }
                        seen.remove(id)
                        continue
                    }
                    if (running.get() >= maxConcurrent) {
                        seen.remove(id)
                        continue
                    }
                    running.incrementAndGet()
                    Thread {
                        try {
                            runOne(id, script, env, cwd, to, rd, kd, base, log)
                        } finally {
                            seen.remove(id)
                            running.decrementAndGet()
                        }
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
            // 拷完再写 rc：rc 是「结果完整」的信号，提前写会让客户端读到截断的输出。
            // 输出量大时 2 秒可能不够，但宁可让客户端多等一轮 poll，也不能给半个结果。
            tOut.join(30_000); tErr.join(30_000)
            // rc 用临时文件 + rename 原子替换：客户端随时可能在读，
            // 直接 writeText 会出现「文件存在但内容为空」的瞬间，读出来就是 code=-1。
            val tmp = File(rd, id.toString() + ".rc.tmp")
            tmp.writeText(code.toString())
            runCatching { tmp.renameTo(rcFile) }
            runCatching { scriptFile.delete() }
        } catch (t: Throwable) {
            log("执行异常 id=" + id + "：" + t)
            runCatching {
                val tmp = File(rd, id.toString() + ".rc.tmp")
                tmp.writeText("-2")
                tmp.renameTo(rcFile)
            }
        } finally {
            runCatching { p?.destroyForcibly() }
            for (suffix in listOf(".sh", ".env", ".cwd", ".to", ".go", ".sig")) {
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
