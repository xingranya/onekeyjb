package com.pyb110.onekey.shell

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OneKeyShell 服务端：由 app_process 以 shell(2000) 或 root(0) 身份运行。
 *
 * 启动方式见 ShellSecrets.ensureScript 生成的 start.sh：
 *   CLASSPATH=<apk> setsid app_process --nice-name=onekey_shell /system/bin \
 *       com.pyb110.onekey.shell.OneKeyShellServer --socket <name> --token-file <path> --log <path>
 *
 * 生命周期设计：
 * - 与 App 进程完全解耦，App 被杀、被升级、被强停都不影响；只要不被系统回收就一直可用。
 * - 同名 socket 已存在且能连上 → 说明已有实例，本进程直接退出，避免双实例抢 socket。
 * - 单进程多连接，每个连接一个线程；连接断开只结束该线程。
 *
 * 鉴权：HELLO 发 32 字节 nonce，客户端回 HMAC-SHA256(token, nonce || boot_id)。
 * 目的不是防 root（root 想干什么都拦不住），而是防同设备其它 App
 * 通过 /proc/net/unix 枚举到抽象 socket 名后复用我们的 root shell。
 */
object OneKeyShellServer {

    private const val TAG = "OneKeyShellServer"
    private const val AUTH_WINDOW_MS = 10_000L

    @Volatile private var token: ByteArray = ByteArray(0)
    @Volatile private var bootId: String = ""
    @Volatile private var logFile: File? = null

    /**
     * 执行中的请求：id -> (进程, marker)。
     *
     * 必须带上 marker：T_KILL 只 destroyForcibly 直接子 sh 是杀不干净的，
     * 脚本里的 `sh -c`、管道、`&` 都会 fork 出孙进程，
     * 父进程死了它们还在跑（还占着端口/文件/CPU）。
     * 超时路径本来就靠 killByMarker 收尾，kill 路径必须一致。
     */
    private val activeExecs = ConcurrentHashMap<Int, Pair<Process, String>>()
    private val execSeq = AtomicInteger(1000)

    /** 帧写入的唯一出口。多线程并发写同一个 OutputStream 会把 5 字节头和 body 交错，客户端解出垃圾长度后整条连接报废。 */
    private fun writeFrame(output: OutputStream, type: Int, body: ByteArray) {
        synchronized(output) { ShellProtocol.write(output, type, body) }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        var socketName = ""
        var tokenPath = ""
        var logPath = ""
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--socket" -> { socketName = args.getOrElse(i + 1) { "" }; i += 2 }
                "--token-file" -> { tokenPath = args.getOrElse(i + 1) { "" }; i += 2 }
                "--log" -> { logPath = args.getOrElse(i + 1) { "" }; i += 2 }
                else -> i++
            }
        }
        if (socketName.isBlank() || tokenPath.isBlank()) {
            System.err.println(TAG + ": 缺少 --socket / --token-file")
            System.exit(70)
        }
        logFile = logPath.takeIf { it.isNotBlank() }?.let { File(it) }
        token = runCatching { File(tokenPath).readText().trim() }
            .getOrElse { "" }
            .let { if (it.length == 64) hexToBytes(it) else ByteArray(0) }
        if (token.isEmpty()) {
            log("token 读取失败：" + tokenPath)
            System.exit(71)
        }
        bootId = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")
        log("启动 uid=" + uid() + " socket=" + socketName + " boot=" + bootId.take(8))

        // 已有实例在跑就直接退出，避免两个服务端抢同一个抽象 socket。
        if (probeExisting(socketName)) {
            log("同名 socket 已存在且可连接，本实例退出")
            return
        }

        // LocalServerSocket(String) 绑定的是抽象命名空间（不需要文件系统节点，
        // 也不会被 /data 的 SELinux 标签挡住），正是我们要的。
        // bind 失败时先重试探测：旧实例可能正处在启动中（还没回 T_PONG），
        // 直接 exit 会让 App 侧看到「服务端不存在」，白等到下次拉起。
        var server: LocalServerSocket? = null
        for (attempt in 0 until 3) {
            server = try {
                LocalServerSocket(socketName)
            } catch (t: Throwable) {
                log("绑定 socket 失败（第 " + (attempt + 1) + " 次）：" + t)
                null
            }
            if (server != null) break
            if (probeExisting(socketName)) {
                log("同名 socket 已被在跑的实例占用，本实例退出")
                return
            }
            Thread.sleep(300)
        }
        val bound = server ?: run {
            log("三次绑定都失败，退出")
            System.exit(72)
            return
        }
        log("监听中")

        // 文件队列与 socket 并行跑，两条路都能进来。
        // 必须并行的原因：SELinux 把 untrusted_app → shell 的 connectto 拦了，
        // socket 只有同域（shell/root）才连得上；而文件访问是通的。
        // 两条路同时开，客户端自动挑能用的那条。
        val dir = tokenPath.substringBeforeLast('/', "")
        if (dir.isNotEmpty()) {
            val d = File(dir).also { it.mkdirs() }
            // uid 文件：文件队列模式下客户端没有握手，拿不到真实 uid，
            // 而「有没有 root」决定开机自动越狱该不该开跑（2000 跑必然失败）。
            runCatching { File(d, "uid").writeText(uid().toString()) }
            runCatching { File(d, "server.pid").writeText(android.os.Process.myPid().toString()) }
            if (!File(d, "q").let { it.exists() || it.mkdirs() } || !File(d, "q").canWrite()) {
                log("文件队列目录不可写，仅保留 socket：" + d)
            } else {
                Thread({ FileTransport.serveLoop(null, d) { log(it) } }, "okshell-filequeue").start()
                log("文件队列已启动：" + d)
                // 结果文件（r/）靠调用方 cleanup() 清；引擎超时路径不 cleanup，
                // 长期跑会把外部目录填满，所以服务端自己定期收。
                Thread({
                    while (true) {
                        Thread.sleep(3_600_000L)
                        runCatching { FileTransport.sweepDir(d, keep = 200) }
                    }
                }, "okshell-sweep").apply { isDaemon = true }.start()
            }
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            log("收到退出信号，关闭")
            runCatching { bound.close() }
        })

        while (true) {
            val client = try {
                bound.accept()
            } catch (t: Throwable) {
                log("accept 失败：" + t)
                break
            }
            Thread({ serve(client) }, "okshell-" + client.hashCode()).start()
        }
        runCatching { bound.close() }
    }

    private fun probeExisting(name: String): Boolean = try {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            val out = s.outputStream
            val input = s.inputStream
            ShellProtocol.write(out, ShellProtocol.T_PING, ShellProtocol.pack { writeLong(System.currentTimeMillis()) })
            s.soTimeout = 800
            val f = ShellProtocol.read(input)
            f != null && f.type == ShellProtocol.T_PONG
        }
    } catch (_: Throwable) {
        false
    }

    private fun serve(client: LocalSocket) {
        var authed = false
        var helloAt = 0L
        // nonce 必须是连接局部变量：它是「这次握手」的一次性挑战，
        // 放成共享字段会让两个并发连接的 nonce 互相覆盖，先握手的那条必然鉴权失败。
        var nonce: ByteArray? = null
        try {
            client.soTimeout = 0
            val input = DataInputStream(client.inputStream)
            val output = client.outputStream
            while (true) {
                val frame = ShellProtocol.read(input) ?: break
                if (!authed) {
                    when (frame.type) {
                        ShellProtocol.T_PING -> {
                            writeFrame(output, ShellProtocol.T_PONG, ShellProtocol.pack { writeLong(System.currentTimeMillis()) })
                        }
                        ShellProtocol.T_HELLO -> {
                            val n = ByteArray(32)
                            SecureRandom().nextBytes(n)
                            nonce = n
                            writeFrame(output, ShellProtocol.T_HELLO,
                                ShellProtocol.encodeHello(uid(), n))
                            helloAt = System.currentTimeMillis()
                        }
                        ShellProtocol.T_AUTH -> {
                            val n = nonce
                            val ok = n != null && helloAt > 0 &&
                                System.currentTimeMillis() - helloAt <= AUTH_WINDOW_MS &&
                                MessageDigest.isEqual(frame.body, expectedHmac(n, "C"))
                            if (!ok) {
                                writeFrame(output, ShellProtocol.T_AUTH_FAIL, ByteArray(0))
                                log("鉴权失败，断开：" + client.hashCode())
                                return
                            }
                            authed = true
                            // 双向证明：客户端已证明持有 token（上面那句校验），
                            // 这里服务端再证一次自己持有 token，方向用不同后缀区分，
                            // 否则同一份 HMAC 可以在两个方向重放。
                            val proof = expectedHmac(n, "S")
                            nonce = null
                            writeFrame(output, ShellProtocol.T_AUTH_OK,
                                ShellProtocol.encodeAuthOk(uid(), proof))
                            log("客户端接入：" + client.hashCode() + " uid=" + uid())
                        }
                        else -> {
                            log("未鉴权帧 0x" + Integer.toHexString(frame.type) + "，断开")
                            return
                        }
                    }
                    continue
                }
                when (frame.type) {
                    ShellProtocol.T_EXEC -> handleExec(output, ShellProtocol.decodeExec(frame.body))
                    ShellProtocol.T_PING -> writeFrame(output, ShellProtocol.T_PONG,
                        ShellProtocol.pack { writeLong(System.currentTimeMillis()) })
                    ShellProtocol.T_KILL -> {
                        val din = ShellProtocol.parser(frame.body)
                        val id = din.readInt()
                        activeExecs.remove(id)?.let { (p, mk) ->
                            log("客户端要求终止 id=" + id + " 标记=" + mk)
                            // 与超时路径保持一致：先按 marker 清孙进程，再杀直接子进程。
                            runCatching { killByMarker(mk) }
                            runCatching { p.destroyForcibly() }
                        }
                    }
                    ShellProtocol.T_BYE -> return
                    else -> log("忽略未知帧 0x" + Integer.toHexString(frame.type))
                }
            }
        } catch (_: Throwable) {
            // 客户端断开属正常，不记日志避免刷屏
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * 鉴权用的 HMAC。
     *
     * dir = "C" 是客户端证明，dir = "S" 是服务端证明 —— 必须分开：
     * 用同一个值的话，一个只在中间观察的人可以把客户端的 AUTH 帧原样当成
     * 服务端的 AUTH_OK 回放，冒充就成立了。
     */
    private fun expectedHmac(nonce: ByteArray, dir: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token, "HmacSHA256"))
        mac.update(nonce)
        mac.update(bootId.toByteArray(Charsets.UTF_8))
        mac.update(dir.toByteArray(Charsets.UTF_8))
        return mac.doFinal()
    }

    private fun handleExec(output: OutputStream, req: ShellProtocol.ExecRequest) {
        val started = System.currentTimeMillis()
        val marker = "okx-" + req.id + "-" + started
        val scriptFile = File.createTempFile("okx-", ".sh", File("/data/local/tmp").takeIf { it.isDirectory && it.canWrite() })
        try {
            scriptFile.writeText(req.script)
            scriptFile.setExecutable(true, false)

            val pb = ProcessBuilder("/system/bin/sh", scriptFile.absolutePath)
            pb.directory(File(req.cwd.ifBlank { "/" }))
            val envMap = pb.environment()
            envMap.clear()
            envMap["PATH"] = "/system/bin:/system/xbin:/system_ext/bin:/data/adb/ksu/bin:/data/adb/modules"
            envMap["HOME"] = "/data/local/tmp"
            envMap["TMPDIR"] = "/data/local/tmp"
            envMap["ONEKEY_EXEC_MARK"] = marker
            for (kv in req.env) {
                val idx = kv.indexOf('=')
                if (idx > 0) envMap[kv.substring(0, idx)] = kv.substring(idx + 1)
            }

            val p = pb.start()
            activeExecs[req.id] = p to marker
            val out = Thread { pump(p.inputStream, output, req.id, ShellProtocol.STREAM_OUT) }
            val err = Thread { pump(p.errorStream, output, req.id, ShellProtocol.STREAM_ERR) }
            out.start()
            err.start()

            // 必须用带超时的 waitFor 轮询。
            // 无参 waitFor() 会一直阻塞，而连接线程从不被 interrupt，
            // 于是「超时就杀」这段代码永远走不到 —— 被执行的脚本只要有一个子进程
            // 不退出（cat 等 stdin、常驻服务、卡在网络 IO），这条连接就永久挂死，
            // T_EXIT 永不发出，客户端 await() 超时返回 -1，服务端却泄漏线程+进程+临时文件。
            val deadline = started + req.timeoutSec.coerceIn(1, 3600) * 1000
            var code = -1
            while (true) {
                if (p.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    code = p.exitValue()
                    break
                }
                if (System.currentTimeMillis() > deadline) {
                    log("执行超时，终止 id=" + req.id + " 标记=" + marker)
                    killByMarker(marker)
                    p.destroyForcibly()
                    code = -1
                    break
                }
            }
            out.join(3000)
            err.join(3000)
            activeExecs.remove(req.id)
            val dur = (System.currentTimeMillis() - started).toInt()
            writeFrame(output, ShellProtocol.T_EXIT, ShellProtocol.encodeExit(req.id, code, dur))
        } catch (t: Throwable) {
            log("执行异常 id=" + req.id + "：" + t)
            runCatching {
                writeFrame(output, ShellProtocol.T_EXIT,
                    ShellProtocol.encodeExit(req.id, -2, (System.currentTimeMillis() - started).toInt()))
            }
        } finally {
            runCatching { scriptFile.delete() }
        }
    }

    /**
     * 逐块上抛输出。
     *
     * 按 8 KiB 或 40 ms 为界切块：纯按行切会把没有换行的进度输出憋到最后，
     * 纯按字节切会让每一帧都带 9 字节头、帧数爆炸。
     */
    private fun pump(input: InputStream, output: OutputStream, id: Int, stream: Int) {
        val buf = ByteArray(8192)
        var lastFlush = System.currentTimeMillis()
        val acc = java.io.ByteArrayOutputStream(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                acc.write(buf, 0, n)
                val now = System.currentTimeMillis()
                if (acc.size() >= 8192 || now - lastFlush >= 40) {
                    flushAcc(acc, output, id, stream)
                    lastFlush = now
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { flushAcc(acc, output, id, stream) }
        }
    }

    private fun flushAcc(acc: java.io.ByteArrayOutputStream, output: OutputStream, id: Int, stream: Int) {
        if (acc.size() == 0) return
        val bytes = acc.toByteArray()
        acc.reset()
        writeFrame(output, ShellProtocol.T_OUT, ShellProtocol.encodeOut(id, stream, bytes))
    }

    /** 按 ONEKEY_EXEC_MARK 环境变量杀掉本次执行留下的所有子进程。 */
    private fun killByMarker(marker: String) {
        val needle = "ONEKEY_EXEC_MARK=" + marker
        val procDir = File("/proc")
        val pids = procDir.list()?.filter { it.all { c -> c.isDigit() } } ?: return
        for (pid in pids) {
            val envFile = File("/proc/" + pid + "/environ")
            val hit = runCatching {
                val bytes = envFile.readBytes()
                String(bytes, Charsets.ISO_8859_1).split('\u0000').any { it == needle }
            }.getOrDefault(false)
            if (hit) {
                runCatching { android.os.Process.killProcess(pid.toInt()) }
            }
        }
    }

    private fun uid(): Int = android.os.Process.myUid()

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private val logLock = Any()

    private fun log(msg: String) {
        val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + "  " + msg
        val f = logFile
        // 有日志文件时只写文件。
        // start.sh 里 stderr 已经被重定向到同一个文件（>>log 2>&1），
        // 再 println 一次会让每行都出现两遍 —— 排查时极易误以为有并发双写。
        if (f == null) {
            System.err.println(line)
            return
        }
        // 多连接并发 + 轮转同时发生时，不加锁会丢行或写到已被截断的 inode 上。
        synchronized(logLock) {
            runCatching {
                f.parentFile?.mkdirs()
                FileOutputStream(f, true).use { it.write((line + "\n").toByteArray()); it.flush() }
            }
            // 日志超过 512 KiB 时把旧的挪成 .1 再重建，避免开机自启长期运行把分区写满。
            // 不原地重写：另一个线程可能正握着旧 inode 的追加句柄，重写会让它的写入落进空洞。
            runCatching {
                if (f.length() > 512 * 1024) {
                    val bak = File(f.parentFile, f.name + ".1")
                    runCatching { bak.delete() }
                    f.renameTo(bak)
                }
            }
        }
    }
}
