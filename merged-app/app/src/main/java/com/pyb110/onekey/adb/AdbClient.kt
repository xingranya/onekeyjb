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
    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 3_000
        private const val AUTH_TIMEOUT_MILLIS = 6_000
        private const val MAX_MESSAGE = 1024 * 1024

        /**
         * 判断端口是不是「TLS 优先」用的探测超时。
         *
         * 无线调试端口不会先发帧，只能靠读超时来区分；
         * 800ms 足够真实 adbd 把首帧吐出来，也不会让人等太久。
         */
        private const val PROBE_TIMEOUT_MILLIS = 800
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
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
        s.tcpNoDelay = true
        socket = s
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
        socket.soTimeout = 0
        tls?.soTimeout = 0
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

        var msg = read()
        if (msg.command != AdbProtocol.A_OKAY && msg.command != AdbProtocol.A_WRTE) {
            error("shell 通道未建立：0x" + Integer.toHexString(msg.command))
        }
        if (msg.command == AdbProtocol.A_WRTE) {
            val remote = msg.arg0
            msg.data?.let { if (it.isNotEmpty()) onChunk?.invoke(it) }
            write(AdbProtocol.A_OKAY, localId, remote)
            msg = read()
        }

        while (true) {
            msg = read()
            val remote = msg.arg0
            when (msg.command) {
                AdbProtocol.A_WRTE -> {
                    msg.data?.let { if (it.isNotEmpty()) onChunk?.invoke(it) }
                    write(AdbProtocol.A_OKAY, localId, remote)
                }

                AdbProtocol.A_CLSE -> {
                    write(AdbProtocol.A_CLSE, localId, remote)
                    return
                }

                else -> return
            }
        }
    }

    /** 跑一条命令并收集全部输出（有上限，防止 cat 大文件把内存吃爆）。 */
    fun shellCapture(command: String, limitBytes: Int = 256 * 1024): String {
        val sb = StringBuilder()
        var total = 0
        shellCommand(command) { chunk ->
            if (total < limitBytes) {
                val s = String(chunk, Charsets.UTF_8)
                sb.append(s)
                total += chunk.size
            }
        }
        return sb.toString()
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
        return m
    }

    override fun close() {
        runCatching { tls?.close() }
        runCatching { socket.close() }
        tls = null
        connected = false
    }
}
