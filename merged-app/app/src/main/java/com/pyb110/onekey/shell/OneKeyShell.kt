package com.pyb110.onekey.shell

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OneKeyShell 客户端。
 *
 * 与 StellarShell 保持同名同签名，迁移时只换前缀：
 *   StellarShell.run(...)  →  OneKeyShell.run(...)
 *
 * 两条传输并存，自动选择：
 *   socket —— 抽象 Unix socket + 帧{{TERM_2y25qziz}}，延迟最低（<1 ms）
 *   文件   —— getExternalFilesDir 下的请求/结果队列，延迟约 70 ms
 *
 * 为什么必须有文件这条路：SELinux 把跨域连接拦了，
 *   avc: denied { connectto } scontext=u:r:untrusted_app:s0 tcontext=u:r:shell:s0
 * 这不是路径权限而是域对不匹配，应用侧绕过不了。
 * 文件访问是通的（token、start.sh 都靠它），所以文件队列当兜底。
 * 两条路都失败才算通道不可用。
 */
object OneKeyShell {
    const val TMP = "/data/local/tmp"
    const val WORK = "/data/local/tmp/onekey"

    private const val CONNECT_TIMEOUT_MS = 1500
    private const val AVAIL_TTL_MS = 2000L
    private const val HEARTBEAT_STALE_MS = 8_000L
    private const val PING_TIMEOUT_MS = 1_500L

    /** 单次执行保留的输出上限（字符）。长驻引擎会连续吐几十 MB，不设上限就是 OOM。 */
    private const val MAX_ACC_CHARS = 256 * 1024

    private val execSeq = AtomicInteger(1)
    private val lock = Any()

    @Volatile private var conn: Conn? = null
    @Volatile private var lastAvailCheck = 0L
    @Volatile private var lastAvail = false
    @Volatile private var fileMode = false

    // ---------------------------------------------------------------- socket 连接

    internal class Conn(val socket: LocalSocket) {
        val input = DataInputStream(socket.inputStream)
        val out: OutputStream = socket.outputStream
        val execs = ConcurrentHashMap<Int, Exec>()
        @Volatile var alive = true
        @Volatile var uid = -1
        var reader: Thread? = null

        fun shutdown() {
            alive = false
            // 显式关 fd：只置 alive=false 会让 socket 靠 GC 回收，
            // 反复重连时 fd 越积越多，最终 connect 报 EMFILE。
            runCatching { socket.close() }
        }
    }

    private fun tokenBytes(context: Context): ByteArray {
        val hex = ShellSecrets.token(context)
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun bootId(): String =
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")

    /**
     * 鉴权用的 HMAC。
     *
     * dir = "C" 是客户端证明，dir = "S" 是服务端证明。
     * 必须分开：同一个值的话，能观察流量的人可以把客户端的 AUTH 帧
     * 原样当成服务端的 AUTH_OK 回放，双向证明就退化成单向。
     */
    private fun hmac(key: ByteArray, nonce: ByteArray, dir: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(nonce)
        mac.update(bootId().toByteArray(Charsets.UTF_8))
        mac.update(dir.toByteArray(Charsets.UTF_8))
        return mac.doFinal()
    }

    private fun connect(context: Context): Conn? {
        val name = ShellSecrets.socketName(context)
        val s = LocalSocket()
        return try {
            s.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            s.soTimeout = CONNECT_TIMEOUT_MS
            val out = s.outputStream
            val input = DataInputStream(s.inputStream)

            ShellProtocol.write(out, ShellProtocol.T_HELLO, ByteArray(0))
            val hello = ShellProtocol.read(input) ?: throw IllegalStateException("无 HELLO 回应")
            if (hello.type != ShellProtocol.T_HELLO) throw IllegalStateException("非 HELLO 帧")
            val h = ShellProtocol.decodeHello(hello.body)
            if (h.version != ShellProtocol.VERSION) throw IllegalStateException("{{TERM_2y25qziz}}版本不符: " + h.version)

            ShellProtocol.write(out, ShellProtocol.T_AUTH, hmac(tokenBytes(context), h.nonce, "C"))
            val auth = ShellProtocol.read(input) ?: throw IllegalStateException("无鉴权回应")
            if (auth.type != ShellProtocol.T_AUTH_OK) throw IllegalStateException("鉴权被拒绝")
            // 必须验服务端的 proof：socket 名由 token 前 64 bit 派生，
            // 而 /proc/net/unix 对同设备任意进程可读 —— 攻击者可以抢先 bind
            // 同名 socket 冒充服务端，接收我们的脚本再回放假输出。
            // 它算不出 HMAC，所以这一验就把冒充挡住了。
            val ok = runCatching { ShellProtocol.decodeAuthOk(auth.body) }.getOrNull()
                ?: throw IllegalStateException("鉴权回应格式非法")
            if (!java.security.MessageDigest.isEqual(ok.proof, hmac(tokenBytes(context), h.nonce, "S")))
                throw IllegalStateException("服务端未通过身份校验")

            s.soTimeout = 0
            val c = Conn(s)
            c.uid = ok.uid
            val t = Thread({ readerLoop(c) }, "okshell-reader")
            t.isDaemon = true
            c.reader = t
            t.start()
            c
        } catch (t: Throwable) {
            runCatching { s.close() }
            null
        }
    }

    /**
     * socket 被 SELinux 拦住之后的冷却期。
     *
     * connectto 拒绝是策略级的、不会自己恢复，而 connOrNull 每次要试 3 轮、
     * 共空等 400 ms。available() 在 46 个调用点被反复调用，
     * 每 2 秒一次的探测如果都空等 400 ms，UI 线程上的成功率采样就会明显卡顿。
     * 记下失败时间，冷却期内直接走文件队列。
     */
    @Volatile private var socketBlockedAt = 0L
    private const val SOCKET_RETRY_MS = 60_000L

    private fun connOrNull(context: Context): Conn? {
        conn?.let { if (it.alive) return it }
        if (socketBlockedAt > 0 && System.currentTimeMillis() - socketBlockedAt < SOCKET_RETRY_MS) {
            return null
        }
        synchronized(lock) {
            conn?.let { if (it.alive) return it }
            for (delayMs in longArrayOf(0L, 100L, 300L)) {
                if (delayMs > 0) runCatching { Thread.sleep(delayMs) }
                val c = connect(context) ?: continue
                conn = c
                fileMode = false
                socketBlockedAt = 0L
                return c
            }
            conn = null
            // 一次探测里三轮全失败，几乎只能是 SELinux 拦截（服务端没起的话
            // 文件队列的心跳也不在，两条路都不通，此时记冷却期没有副作用）
            socketBlockedAt = System.currentTimeMillis()
            return null
        }
    }

    private fun readerLoop(c: Conn) {
        try {
            while (true) {
                val f = ShellProtocol.read(c.input) ?: break
                when (f.type) {
                    ShellProtocol.T_OUT -> {
                        val o = ShellProtocol.decodeOut(f.body)
                        c.execs[o.id]?.feed(o.text)
                    }
                    ShellProtocol.T_EXIT -> {
                        val e = ShellProtocol.decodeExit(f.body)
                        // 结束就摘掉：不摘的话长驻连接（引擎一轮几十次执行、
                        // 每秒的成功率采样、UI 反复探测）会把 execs 越堆越大，
                        // 每个 Exec 还各自留着 256 KiB 输出缓冲 —— 典型的慢性 OOM。
                        // 客户端已经通过 LineProc 持有引用，摘掉不影响读取。
                        c.execs.remove(e.id)?.finish(e.code)
                    }
                    else -> Unit
                }
            }
        } catch (_: Throwable) {
        } finally {
            // 必须真的关掉 fd：只置 alive=false 的话，
            // socket 要等 GC 才回收，而每次重连都会新建一个 ——
            // 引擎一轮几十次执行加上每秒采样，fd 很快就耗尽（EMFILE），
            // 表现是「通道莫名其妙再也连不上」。
            c.shutdown()
            for (e in c.execs.values) e.finish(-1)
            c.execs.clear()
        }
    }

    // ---------------------------------------------------------------- socket 单次执行

    internal class Exec(val id: Int, val sink: ((String) -> Unit)?) {
        private val acc = StringBuilder()
        private val lineBuf = StringBuilder()
        private val history = ArrayList<String>()

        @Volatile var done = false
        @Volatile var code: Int? = null
        @Volatile var killed = false

        fun feed(bytes: ByteArray) {
            // 服务端按 8 KiB 或 40 ms 切块，切点落在多字节字符中间是常态。
            // 直接 String(bytes, UTF_8) 会把半个汉字解成 U+FFFD，
            // 而日志里到处是中文（「连续 N 次尝试未命中」），
            // 于是每 8 KiB 就多一个乱码字符，看起来像引擎吐了脏数据。
            // 这里保留不完整尾字节，拼到下一块再解。
            val text = decodeChunk(bytes)
            synchronized(acc) {
                acc.append(text)
                // 长驻引擎会连续吐几十 MB 日志，不设上限就是 OOM。
                // 保留尾部 256 KiB 足够给 output() 与失败诊断用。
                if (acc.length > MAX_ACC_CHARS) acc.delete(0, acc.length - MAX_ACC_CHARS)
            }
            // 提到局部变量：sink 是构造属性，跨 synchronized 块后编译器不再做智能转换
            val cb = sink ?: return
            val out = ArrayList<String>()
            synchronized(lineBuf) {
                for (ch in text) {
                    if (ch == '\n') {
                        val line = lineBuf.toString().trimEnd('\r')
                        lineBuf.setLength(0)
                        if (line.isNotEmpty()) out.add(line)
                    } else {
                        lineBuf.append(ch)
                    }
                }
                if (lineBuf.length > 64 * 1024) lineBuf.setLength(0)
            }
            if (out.isEmpty()) return
            synchronized(history) {
                for (l in out) {
                    if (history.size >= 2048) history.removeAt(0)
                    history.add(l)
                }
            }
            for (l in out) runCatching { cb.invoke(l) }
        }

        fun finish(c: Int) {
            if (done) return
            code = c
            done = true
        }

        /**
         * 跨块安全的 UTF-8 解码。
         *
         * 保留末尾 1~3 个「可能是多字节字符前半段」的字节，
         * 下次 feed 时拼在前面。判据是首字节的高位 1 的个数：
         * 0xxxxxxx = 1 字节、110xxxxx = 2、1110xxxx = 3、11110xxx = 4。
         */
        private fun decodeChunk(bytes: ByteArray): String {
            val pending = carry
            val all = if (pending.isEmpty()) bytes else pending + bytes
            var cut = all.size
            var i = all.size - 1
            var back = 0
            while (i >= 0 && back < 4) {
                val b = all[i].toInt() and 0xFF
                if (b and 0xC0 == 0x80) { i--; back++; continue }
                val need = when {
                    b and 0x80 == 0x00 -> 1
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
                if (all.size - i < need) cut = i
                break
            }
            carry = if (cut < all.size) all.copyOfRange(cut, all.size) else ByteArray(0)
            return String(all, 0, cut, Charsets.UTF_8)
        }

        private var carry: ByteArray = ByteArray(0)

        fun text(): String = synchronized(acc) { acc.toString().trim() }

        fun snapshot(): List<String> = synchronized(history) { ArrayList(history) }

        fun tail(n: Int): String = synchronized(history) { history.takeLast(n).joinToString("\n") }

        fun waitFor(needle: String, timeoutSec: Long): String? {
            val end = System.nanoTime() + timeoutSec * 1_000_000_000L
            while (System.nanoTime() < end) {
                synchronized(history) { for (l in history) if (l.contains(needle)) return l }
                // done 之后要再扫一遍：标记可能正好在最后一批输出里，
                // 而上一轮扫描发生在它落地之前 —— 直接 return null 会把成功判成失败。
                if (done) {
                    synchronized(history) { for (l in history) if (l.contains(needle)) return l }
                    return null
                }
                runCatching { Thread.sleep(50) }
            }
            return null
        }
    }

    /** start() 返回的句柄，语义对齐 ShizukuShell.LineProc。 */
    class LineProc internal constructor(
        private val context: Context,
        private val sockConn: Conn?,
        private val exec: Exec?,
        internal val file: FileTransport.Handle?,
    ) {
        val lines: List<String> get() = exec?.snapshot() ?: emptyList()

        /**
         * socket 是推送模型（reader 线程主动写 done），文件是拉取模型（必须有人去读 rc）。
         * 引擎的等待循环只调 isRunning()，不会自己 tick，所以这里必须顺手轮询一次 ——
         * 否则文件模式下 done 永远不更新，循环会一直转到超时。
         */
        fun isRunning(): Boolean {
            val e = exec
            if (e != null) return !e.done
            val f = file ?: return false
            f.poll()
            return !f.done
        }

        fun exitCode(): Int? {
            val e = exec
            if (e != null) return e.code
            val f = file ?: return null
            // 文件模式必须顺手 poll：rc 文件是拉取模型，
            // 调用方常常「先看 isRunning 再读 exitCode」，
            // 若这里不推进，最后一次 poll 之后落盘的 rc 永远读不到，返回 null 被当成失败。
            f.poll()
            return f.code
        }

        fun waitFor(needle: String, timeoutSec: Long): String? =
            exec?.waitFor(needle, timeoutSec) ?: file?.waitFor(needle, timeoutSec)

        fun tail(n: Int): String = exec?.tail(n) ?: file?.tail(n) ?: ""

        /** 文件队列是拉取模型，必须显式轮询；socket 是推送模型，这里是空操作。 */
        internal fun tick() {
            file?.poll()
        }

        internal fun output(): String = exec?.text() ?: file?.text() ?: ""

        /**
         * 只终止这一次执行，不断开连接。
         *
         * 引擎在「竞态失败即中止」时会调 destroy()，之后还要接着读日志、
         * 跑后续清理脚本；断掉整条连接会让这些后续动作全部失败，
         * 日志里就只剩一句「通道不可用」，查不到真正的失败原因。
         */
        fun destroy() {
            if (!isRunning()) return
            val e = exec
            val c = sockConn
            if (e != null && c != null) {
                e.killed = true
                runCatching {
                    // 必须与 submit() 用同一把锁：T_KILL 与 T_EXEC 并发写同一个流，
                    // 5 字节头和 body 会交错，readerLoop 解出垃圾长度后整条连接报废。
                    synchronized(c.out) {
                        ShellProtocol.write(c.out, ShellProtocol.T_KILL, ShellProtocol.pack { writeInt(e.id) })
                    }
                }
                e.finish(-1)
            }
            file?.kill()
        }
    }

    // ---------------------------------------------------------------- 传输选择

    /**
     * 文件队列是否还活着。
     *
     * 只看心跳文件的 mtime 不够：心跳每 3 秒写一次，而阈值 20 秒，
     * 服务端被杀之后最长还有 17 秒会被判成「活着」——
     * 实测表现为「点了启动 Shell 通道，日志说复用已运行的服务端，
     * 但 ps 里根本没有 onekey_shell」。
     * 所以这里做一次真实的往返：投一条 echo，等回执。
     * 代价只在 socket 不通时发生，且结果有 2 秒缓存。
     */
    private fun fileModeUsable(context: Context): Boolean {
        val hb = File(FileTransport.root(context), "heartbeat")
        if (!hb.isFile || System.currentTimeMillis() - hb.lastModified() >= HEARTBEAT_STALE_MS) return false
        val id = execSeq.getAndIncrement()
        val h = FileTransport.submit(context, id, "echo OKX_PING", 3, emptyList(), "/", null) ?: return false
        try {
            val end = System.currentTimeMillis() + PING_TIMEOUT_MS
            while (!h.done && System.currentTimeMillis() < end) {
                h.poll()
                Thread.sleep(40)
            }
            return h.done && h.code == 0
        } finally {
            runCatching { h.cleanup() }
        }
    }

    private fun submit(
        context: Context,
        script: String,
        timeoutSec: Long,
        extraEnv: Array<String>?,
        sink: ((String) -> Unit)?,
        cwd: String,
    ): LineProc? {
        val id = execSeq.getAndIncrement()

        // 先试 socket：延迟最低。失败（多半是 SELinux 跨域拦截）就落到文件队列。
        val c = connOrNull(context)
        if (c != null) {
            val exec = Exec(id, sink)
            c.execs[id] = exec
            try {
                val req = ShellProtocol.ExecRequest(id, timeoutSec, extraEnv?.toList() ?: emptyList(), cwd, script)
                synchronized(c.out) {
                    ShellProtocol.write(c.out, ShellProtocol.T_EXEC, ShellProtocol.encodeExec(req))
                }
                return LineProc(context, c, exec, null)
            } catch (_: Throwable) {
                c.execs.remove(id)
                c.alive = false
            }
        }

        if (!fileModeUsable(context)) return null
        fileMode = true
        val h = FileTransport.submit(context, id, script, timeoutSec, extraEnv?.toList() ?: emptyList(), cwd, sink)
            ?: return null
        return LineProc(context, null, null, h)
    }

    // ---------------------------------------------------------------- 对外 API

    fun available(): Boolean {
        val now = System.currentTimeMillis()
        conn?.let { if (it.alive) { lastAvail = true; lastAvailCheck = now; return true } }
        if (now - lastAvailCheck < AVAIL_TTL_MS) return lastAvail
        val ctx = ShellRuntime.appContext ?: return false
        val ok = try {
            val c = connOrNull(ctx)
            if (c != null) {
                val id = execSeq.getAndIncrement()
                val exec = Exec(id, null)
                c.execs[id] = exec
                synchronized(c.out) {
                    ShellProtocol.write(c.out, ShellProtocol.T_EXEC, ShellProtocol.encodeExec(
                        ShellProtocol.ExecRequest(id, 5, emptyList(), "/", "id -u")
                    ))
                }
                // 只等 600 ms：这是存活探测，不是执行。
                // 原来等 2500 ms，而 available() 被 46 处调用点反复调用
                // （含 Compose 组合期的 shellLabel()），每次 TTL 过期都要卡满 2.5 秒。
                val end = System.currentTimeMillis() + 600
                while (!exec.done && System.currentTimeMillis() < end) Thread.sleep(25)
                exec.done
            } else {
                fileModeUsable(ctx)
            }
        } catch (_: Throwable) { false }
        lastAvail = ok
        lastAvailCheck = now
        return ok
    }

    /**
     * 只读快照，绝不做 IO。
     *
     * 供 Compose 组合期调用：available() 最坏会阻塞 2.9 秒
     * （socket 三轮重连 400 ms + 存活探测 600 ms + 文件心跳往返 1.5 s），
     * 主线程同步调用必然 ANR。
     */
    fun snapshotAlive(): Boolean = conn?.alive == true || lastAvail

    /** 当前是否走文件队列（供 UI 显示通道质量）。 */
    fun usingFileTransport(): Boolean = fileMode

    /**
     * 绕过可用性缓存的一次探测。
     *
     * available() 会把结果缓存 2 秒，而引导阶梯在 su 拉起服务端后
     * 立刻开始轮询 —— 缓存还没过期，就会把刚起来的服务端判成「不可用」，
     * 于是阶梯继续往下一级走，白付一次 adb 的代价。
     */
    fun probe(): Boolean {
        lastAvailCheck = 0L
        return available()
    }

    fun hasPermission(): Boolean = available()

    /**
     * 服务端 uid。
     *
     * 文件队列模式下没有握手，拿不到真实 uid；但能跑通就说明服务端活着，
     * 而它要么是 shell(2000) 要么是 root(0)。这里报 2000 而不是 -1：
     * UI 上显示「uid=-1」会让人以为通道坏了，实际只是没走 socket。
     */
    fun serviceUid(): Int {
        conn?.let { return it.uid }
        val ctx = ShellRuntime.appContext ?: return -1
        if (!(fileMode || fileModeUsable(ctx))) return -1
        // 文件模式没有握手，真实 uid 由服务端写在 <root>/uid。
        // 不能一律报 2000：开机自动越狱靠它判断「有没有 root」，
        // 2000 跑引擎必然失败，然后开关会被自动关掉。
        return runCatching {
            File(ShellSecrets.dir(ctx), "uid").readText().trim().toInt()
        }.getOrDefault(2000)
    }

    fun requestPermission(code: Int) {
        // 自有通道没有权限授予流程，保留空实现以兼容既有调用点。
    }

    data class Result(val code: Int, val output: String)

    fun run(script: String, timeoutSec: Long = 60, extraEnv: Array<String>? = null): Result {
        val ctx = ShellRuntime.appContext ?: return Result(-1, "Shell 通道未初始化")
        val proc = submit(ctx, "(" + script + ") 2>&1", timeoutSec, extraEnv, null, TMP)
            ?: return Result(-1, "Shell 通道不可用")
        return await(proc, timeoutSec)
    }

    fun runStreaming(script: String, timeoutSec: Long = 60, sink: ((String) -> Unit)? = null): Result {
        val ctx = ShellRuntime.appContext ?: return Result(-1, "Shell 通道未初始化")
        val proc = submit(ctx, "(" + script + ") 2>&1", timeoutSec, null, sink, TMP)
            ?: return Result(-1, "Shell 通道不可用")
        return await(proc, timeoutSec)
    }

    private fun await(proc: LineProc, timeoutSec: Long): Result {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000 + 2000
        while (proc.isRunning() && System.currentTimeMillis() < deadline) {
            proc.tick()
            Thread.sleep(25)
        }
        proc.tick()
        if (proc.isRunning()) {
            proc.destroy()
            proc.tick()
        }
        val r = Result(proc.exitCode() ?: -1, proc.output())
        proc.file?.cleanup()
        return r
    }

    @Throws(Exception::class)
    fun start(script: String, sink: ((String) -> Unit)?): LineProc {
        val ctx = ShellRuntime.appContext ?: throw IllegalStateException("Shell 通道未初始化")
        return submit(ctx, "(" + script + ") 2>&1", 3600, null, sink, TMP)
            ?: throw IllegalStateException("Shell 通道不可用")
    }

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 把字节写到 dest。
     *
     * 必须分片：整个 base64 塞进一次 exec 会撞上 MAX_FRAME（4 MiB），
     * 而我们的资产 base64 后分别是 16.87 MB（ksud-universal）、4.61 MB、
     * 4.34 MB、521 KB、623 KB —— 前三个全超限。
     * 超限时 ShellProtocol.write 抛 IllegalArgumentException，
     * 被 submit 的 catch 吞掉并把连接置为 dead，
     * 结果不只是这次写失败，还会打断正在跑的长驻引擎连接。
     *
     * 每片 2 MiB 原始字节（base64 后约 2.7 MB），逐片追加写同一个临时文件，
     * 最后一次性 chmod + mv，保证 dest 要么是完整文件要么不存在。
     */
    fun stageBytes(data: ByteArray, dest: String, mode: Int = 755): Result {
        val dir = dest.substringBeforeLast('/', "/data/local/tmp")
        val tag = "okx" + Integer.toHexString(System.nanoTime().toInt()) + "_" + dest.hashCode().toUInt()
        val tmp = dir + "/." + tag + ".part"
        // 2 MiB 原始字节 → base64 后约 2.7 MB，留足余量给帧头与脚本外壳（上限 4 MiB）。
        val chunk = 2 * 1024 * 1024
        val pieces = maxOf(1, (data.size + chunk - 1) / chunk)

        val prep = "set -e; mkdir -p " + quote(dir) + "; : > " + quote(tmp)
        val r0 = run(prep, 20)
        if (r0.code != 0) return Result(r0.code, "准备临时文件失败：" + r0.output.take(200))

        var off = 0
        var index = 0
        while (off < data.size) {
            val n = minOf(chunk, data.size - off)
            val slice = if (off == 0 && n == data.size) data else data.copyOfRange(off, off + n)
            val b64 = java.util.Base64.getEncoder().encodeToString(slice)
            // 每片单独一次 exec：把 7 片拼进同一个 script 会让整帧超过 4 MiB，
            // 等于没解决「帧过大」这个根因，只是把报错推迟到更靠后的地方。
            val piece = buildString {
                append("base64 -d >> ").append(quote(tmp)).append(" <<'OKXEOF'\n")
                append(b64).append("\nOKXEOF\n")
            }
            val r = run(piece, 90)
            if (r.code != 0) {
                runCatching { run("rm -f " + quote(tmp), 10) }
                return Result(r.code, "写入第 " + (index + 1) + "/" + pieces + " 片失败：" + r.output.take(200))
            }
            off += n
            index += 1
        }

        val fin = "set -e; chmod " + mode + " " + quote(tmp) + "; mv -f " + quote(tmp) + " " + quote(dest) +
            "; echo OKX_STAGED=" + dest
        val rf = run(fin, 30)
        if (rf.code != 0) runCatching { run("rm -f " + quote(tmp), 10) }
        return rf
    }

    fun stageAsset(context: Context, asset: String, dest: String, mode: Int = 755): Result {
        val bytes = try {
            context.assets.open(asset).use { it.readBytes() }
        } catch (t: Throwable) {
            return Result(-1, "读取资产失败：" + asset)
        }
        return stageBytes(bytes, dest, mode)
    }
}
