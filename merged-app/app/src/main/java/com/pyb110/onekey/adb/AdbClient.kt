package com.pyb110.onekey.adb

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * 一条到本机 adbd 的连接。
 *
 * 握手顺序（无线调试 / Android 11+）：
 *   1. 连上回环端口，adbd 先发一帧
 *      - A_STLS  → 走 TLS：回 A_STLS_VERSION，然后在同一 socket 上升级 SSL，
 *                  用自签客户端证书做双向认证，之后所有帧走 TLS
 *      - A_AUTH  → 走明文：回 A_AUTH/ADB_AUTH_SIGNATURE 签名应答；
 *                  若 adbd 还不认（不是 A_CNXN），再补发 ADB_AUTH_RSAPUBLICKEY
 *                  让设备弹「允许 USB 调试」，用户在设备上点允许后即可
 *   2. 收到 A_CNXN 即握手完成
 *
 * 之后 shell 命令走 A_OPEN "shell:<cmd>"，输出由 A_WRTE 分批回来，
 * 每收一批回一个 A_OKAY（流控），A_CLSE 结束。
 *
 * 这里刻意不实现 forward / sync / 多路复用：本 App 只需要跑 shell 命令，
 * 单路足够，少一层状态机就少一处出错的地方。
 */
class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) : Closeable {
    init {
        // 信任边界钉在调用点：本类的 TrustManager 是 trust-all（adbd 用自签证书，
        // 没有可校验的信任根），一旦有人把 host 传成非回环地址，就是裸奔。
        // 现在所有调用点都传 127.0.0.1，这条 require 让「将来有人改坏」直接炸在构造期。
        require(host == "127.0.0.1" || host == "::1") { "只允许连回环地址，收到：" + host }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 3_000
        private const val AUTH_TIMEOUT_MILLIS = 6_000
        private const val MAX_MESSAGE = 1024 * 1024

        /**
         * shell 会话的空闲超时。
         *
         * 不能设成 0（无限等待）：一旦 adbd 那边的通道已经关闭而我们漏掉了一帧
         * A_CLSE，读就会永久阻塞，调用线程卡死、配对服务的 busy 标志永不释放，
         * 用户看到的是「一直在转圈」而日志里什么都没有。
         * 30 秒足够跑完 pm grant / start.sh 这类短命令，又不会让死锁无限期挂着。
         */
        private const val SHELL_IDLE_TIMEOUT_MILLIS = 30_000
    }

    private lateinit var socket: Socket
    private lateinit var plainIn: DataInputStream
    private lateinit var plainOut: DataOutputStream

    private var tls: SSLSocket? = null
    private lateinit var tlsIn: DataInputStream
    private lateinit var tlsOut: DataOutputStream

    private val input: DataInputStream get() = if (tls != null) tlsIn else plainIn
    private val output: DataOutputStream get() = if (tls != null) tlsOut else plainOut

    /** 握手是否已完成。 */
    var connected = false
        private set

    /** 握手时协商到的设备信息（A_CNXN 的 banner），排障用。 */
    var banner: String = ""
        private set

    /** 是否走了 TLS。 */
    var secure = false
        private set

    /** 在已连接的 socket 上升级为 TLS（无线调试的两种端口都要走这一步）。 */
    private fun upgradeTls() {
        val factory = key.sslContext.socketFactory
        val ss = factory.createSocket(socket, host, port, true) as SSLSocket
        ss.soTimeout = AUTH_TIMEOUT_MILLIS
        ss.startHandshake()
        tls = ss
        tlsIn = DataInputStream(ss.inputStream)
        tlsOut = DataOutputStream(ss.outputStream)
        secure = true
    }

    fun connect() {
        val s = Socket()
        // 先挂到字段上再 connect：connect 抛异常时 close() 才能关掉它。
        // 否则每次开机重连失败都漏一个 fd，长期累积会 EMFILE。
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        s.tcpNoDelay = true
        plainIn = DataInputStream(s.inputStream)
        plainOut = DataOutputStream(s.outputStream)
        s.soTimeout = AUTH_TIMEOUT_MILLIS

        // ADB 协议里永远是客户端先说话：设备只会响应，从不主动开场。
        // 无线调试端口也一样 —— 先发 A_CNXN，adbd 才会回 A_STLS 要求升级 SSL。
        // 反过来等设备先发，双方就互相等，adbd 那边表现为
        // "New TLS connection" 紧跟 "read failed / connection terminated"。
        sendConnect()
        var msg = read()

        if (msg.command == AdbProtocol.A_STLS) {
            // 设备要求升级 TLS：回版本号 → 在同一 socket 上升级 → 在 TLS 里重新自报家门。
            write(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0)
            upgradeTls()
            socket.soTimeout = AUTH_TIMEOUT_MILLIS
            tls?.soTimeout = AUTH_TIMEOUT_MILLIS
            // TLS 升级完成后 adbd 会**主动**发 A_CNXN banner（实测 344 字节 device:: 串）。
            // 这里绝不能再 sendConnect()：多发的 A_CNXN 会被 adbd 当成新的一次握手，
            // 它回一个 A_STLS 要求二次升级，客户端在 shell 阶段就会读到 0x534c5453。
            msg = read()
        }

        if (msg.command == AdbProtocol.A_AUTH) msg = answerAuth(msg)

        if (msg.command != AdbProtocol.A_CNXN) {
            error("握手失败：期望 A_CNXN，实际 0x" + Integer.toHexString(msg.command))
        }
        banner = msg.dataAsString()
        connected = true
        socket.soTimeout = SHELL_IDLE_TIMEOUT_MILLIS
        tls?.soTimeout = SHELL_IDLE_TIMEOUT_MILLIS
    }

    /**
     * 发 A_CNXN 自我介绍。
     *
     * ADB 协议里客户端必须先说话：设备只会响应，不会主动开场。
     * 漏了这一步双方就互相等，adbd 那边表现为「read failed / connection terminated」。
     */
    private fun sendConnect() {
        write(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "host::onekey")
    }

    /** 应答设备的 A_AUTH 挑战，必要时补发公钥。 */
    private fun answerAuth(challenge: AdbMessage): AdbMessage {
        if (challenge.arg0 != AdbProtocol.ADB_AUTH_TOKEN) error("设备要求非 token 认证")
        val token = challenge.data ?: error("认证挑战为空")
        write(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0, key.sign(token))
        var msg = read()
        if (msg.command != AdbProtocol.A_CNXN) {
            // 设备还不认识这把密钥，发公钥，等用户在设备上点「允许」。
            write(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
            msg = read()
        }
        return msg
    }

    /**
     * 跑一条 shell 命令，输出逐块交给 [onChunk]。
     *
     * 不解析退出码：adb shell 协议本身不带退出码（要的是 shell_v2），
     * 我们的脚本自己用 `echo XXX_RC=$?` 回传，比依赖传输层的退出码更可控。
     */
    fun shellCommand(command: String, onChunk: ((ByteArray) -> Unit)?) {
        val localId = 1
        write(AdbProtocol.A_OPEN, localId, 0, "shell:" + command)

        // 第一帧单独处理：它可能是 A_OKAY（通道就绪，后续输出才来），
        // 也可能直接是 A_WRTE（短命令，数据先于 OKAY 到）。
        // 这里刻意不「多读一帧再看」—— 早先的写法会在首帧是 A_WRTE 时
        // 额外 read() 一次再把结果丢掉，若丢掉的那帧正好是 A_CLSE，
        // 后续循环就阻塞在一个已关闭的会话上（配合 soTimeout=0 即永久挂死）。
        var first = true
        while (true) {
            val msg = read()
            val remote = msg.arg0
            if (first) {
                first = false
                if (msg.command != AdbProtocol.A_OKAY && msg.command != AdbProtocol.A_WRTE) {
                    error("shell 通道未建立：0x" + Integer.toHexString(msg.command))
                }
            }
            when (msg.command) {
                AdbProtocol.A_WRTE -> {
                    msg.data?.let { if (it.isNotEmpty()) onChunk?.invoke(it) }
                    write(AdbProtocol.A_OKAY, localId, remote)
                }

                AdbProtocol.A_CLSE -> {
                    write(AdbProtocol.A_CLSE, localId, remote)
                    return
                }

                AdbProtocol.A_OKAY -> Unit

                else -> {
                    // 未知帧不静默吞掉：adbd 在会话过期时会发 A_AUTH / A_STLS，
                    // 静默 return 会让调用方拿到半截输出，排查时毫无线索。
                    android.util.Log.w(
                        "OneKeyAdb",
                        "shell 通道收到未处理帧 0x" + Integer.toHexString(msg.command),
                    )
                    return
                }
            }
        }
    }

    /** 跑一条命令并收集全部输出（有上限，防止 cat 大文件把内存吃爆）。 */
    fun shellCapture(command: String, limitBytes: Int = 256 * 1024): String {
        // 先收原始字节再统一解码：A_MAXDATA 是 4096，逐块 String(chunk) 会把
        // 正好跨块边界的多字节 UTF-8 字符碎成 U+FFFD（中文回执必踩）。
        val buf = java.io.ByteArrayOutputStream()
        shellCommand(command) { chunk ->
            if (buf.size() < limitBytes) buf.write(chunk, 0, minOf(chunk.size, limitBytes - buf.size()))
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    private fun write(command: Int, arg0: Int, arg1: Int, text: String) {
        AdbMessage(command, arg0, arg1, text).writeTo(output)
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray?) {
        AdbMessage(command, arg0, arg1, data).writeTo(output)
    }

    private fun write(command: Int, arg0: Int, arg1: Int) {
        AdbMessage(command, arg0, arg1, null as ByteArray?).writeTo(output)
    }

    private fun read(): AdbMessage {
        val m = AdbMessage.readFrom(input)
        if (m.dataLength > MAX_MESSAGE) error("ADB 帧过大：" + m.dataLength)
        // 必须校验 magic 与 crc：一旦发生帧错位（版本差异、代理注入、协商不一致），
        // 后续每个 read() 都会拿到「长度对、内容乱」的帧。不校验的话表现是
        // 「命令发出去了但回执是乱码」，完全无从排查。
        if (!m.validate()) {
            error(
                "ADB 帧校验失败：cmd=0x" + Integer.toHexString(m.command) +
                    " magic=0x" + Integer.toHexString(m.magic),
            )
        }
        return m
    }

    override fun close() {
        runCatching { tls?.close() }
        runCatching { socket.close() }
        tls = null
        connected = false
    }
}
