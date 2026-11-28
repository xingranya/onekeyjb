package com.pyb110.onekey.adb

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/**
 * 无线调试配对（adb pair）。
 *
 * 完整链路（对照 AOSP pairing_connection.cpp / pairing_auth.cpp）：
 *
 *   1. TLS 握手到配对端口。服务端不校验证书链，客户端也不校验 ——
 *      认证靠的是后面的 SPAKE2 配对码，TLS 只负责加密与信道绑定。
 *   2. 从 TLS 导出 64 字节 exporter（label "adb-label\0"，无 context），
 *      拼在 6 位配对码后面作为 SPAKE2 密码。这一步是防中间人的关键：
 *      攻击者即使知道配对码，转发到另一条 TLS 连接上导出的 exporter 也不同。
 *   3. SPAKE2 交换（我方 Alice）。
 *   4. 用导出的 64 字节做 HKDF-SHA256 得到 AES-128-GCM 密钥，
 *      双向各一个计数器，加密交换 PeerInfo（里面装着我们的 RSA 公钥）。
 *   5. 服务端把公钥写进 /data/misc/adb/adb_keys，配对完成，连接断开。
 *
 * 之后再用同一把 RSA 密钥连 _adb-tls-connect 端口就是已认证的 adb 会话。
 *
 * 这里只做客户端，且只支持配对（不做服务端、不做 mdns）。
 */
object AdbPairing {
    /** 配对结果。 */
    data class Result(val ok: Boolean, val reason: String = "")

    private const val EXPORTER_LABEL = "adb-label\u0000"
    private const val EXPORTER_SIZE = 64
    private const val HEADER_VERSION = 1

    /**
     * 包类型（对照 AOSP pairing_connection.h 的 PairingPacketType）。
     *
     * 这两个数字不能混：SPAKE2 那一步对端只接受 0，
     * 把曲线点按 1 发过去，对端会当成加密的 PeerInfo 直接解不开而断开，
     * 表现就是「TLS 连上了，然后连接被对端关掉」。
     */
    private const val TYPE_SPAKE2_MSG = 0
    private const val TYPE_PEER_INFO = 1
    private const val PEER_INFO_SIZE = 8192
    private const val TYPE_RSA_PUB_KEY = 0
    private const val HKDF_INFO = "adb pairing_auth aes-128-gcm key"
    private const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
    private const val TIMEOUT_MS = 20_000

    /**
     * 执行配对。
     *
     * [code] 是设备屏幕上显示的 6 位数字（用户手抄）。
     */
    fun pair(host: String, port: Int, code: String, key: AdbKey): Result {
        var sock: Socket? = null
        var tls: SSLSocket? = null
        try {
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), 5_000)
            plain.tcpNoDelay = true
            plain.soTimeout = TIMEOUT_MS
            sock = plain

            val ss = key.sslContext.socketFactory
                .createSocket(plain, host, port, true) as SSLSocket
            ss.soTimeout = TIMEOUT_MS
            ss.startHandshake()
            tls = ss

            // 1. 密码 = 配对码 ASCII || TLS exporter
            val exporter = exportKeyingMaterial(ss, EXPORTER_LABEL, EXPORTER_SIZE)
                ?: return Result(false, "当前 TLS 提供方不支持密钥导出，无法完成配对")
            val codeBytes = code.trim().toByteArray(Charsets.US_ASCII)
            if (codeBytes.isEmpty() || codeBytes.size > 32) return Result(false, "配对码格式不对")
            val password = codeBytes + exporter

            // 2. SPAKE2
            val session = Spake2.Session.start(password)
            val input = DataInputStream(ss.inputStream)
            val output = DataOutputStream(ss.outputStream)

            writePacket(output, TYPE_SPAKE2_MSG, session.myMessage)

            val (theirType, theirPayload) = readPacket(input)
                ?: return Result(false, "对端没有回应配对消息")
            if (theirType != TYPE_SPAKE2_MSG) return Result(false, "对端 SPAKE2 消息类型不对：" + theirType)
            val keyMaterial = session.process(theirPayload)
                ?: return Result(false, "对端消息不是合法的曲线点")

            // 3. HKDF-SHA256 → AES-128-GCM
            val aesKey = hkdf(keyMaterial, HKDF_INFO.toByteArray(Charsets.US_ASCII), 16)
            val enc = Cipher.getInstance("AES/GCM/NoPadding")
            val dec = Cipher.getInstance("AES/GCM/NoPadding")
            enc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce(0)))
            dec.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce(0)))

            // 4. 交换 PeerInfo（我方先发）
            writePacket(output, TYPE_PEER_INFO, enc.doFinal(peerInfo(key)))
            val (_, peerCipher) = readPacket(input) ?: return Result(false, "对端没有回送 PeerInfo")
            runCatching { dec.doFinal(peerCipher) }
                .getOrElse { return Result(false, "PeerInfo 解密失败，配对码可能不对") }

            return Result(true)
        } catch (e: Exception) {
            return Result(false, describe(e))
        } finally {
            runCatching { tls?.close() }
            runCatching { sock?.close() }
        }
    }

    /**
     * TLS exporter。
     *
     * Android 11+ 有公开 API android.net.ssl.SSLSockets.exportKeyingMaterial，
     * 内部就是调 Conscrypt 的 exportKeyingMaterial。API 30 才有，
     * 但配对本身也只在 Android 11+ 存在，所以这里不需要降级分支。
     */
    private fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray? =
        runCatching {
            if (!android.net.ssl.SSLSockets.isSupportedSocket(socket)) return@runCatching null
            android.net.ssl.SSLSockets.exportKeyingMaterial(socket, label, null, length)
        }.getOrNull()

    /** PeerInfo：1 字节类型 + 8191 字节数据，总共 8192。 */
    private fun peerInfo(key: AdbKey): ByteArray {
        val out = ByteArray(PEER_INFO_SIZE)
        out[0] = TYPE_RSA_PUB_KEY.toByte()
        val pub = key.adbPublicKey
        System.arraycopy(pub, 0, out, 1, minOf(pub.size, PEER_INFO_SIZE - 1))
        return out
    }

    /**
     * 包格式（pairing_connection.cpp WriteHeader/ReadHeader）：
     *   version(1) | type(1) | payload_size(4, 大端) | payload
     */
    private fun writePacket(out: DataOutputStream, type: Int, payload: ByteArray) {
        out.writeByte(HEADER_VERSION)
        out.writeByte(type)
        out.writeInt(payload.size) // DataOutputStream 默认大端，正合规范
        out.write(payload)
        out.flush()
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray>? {
        val version = runCatching { input.readUnsignedByte() }.getOrNull() ?: return null
        if (version != HEADER_VERSION) return null
        val type = input.readUnsignedByte()
        val size = input.readInt()
        if (size <= 0 || size > MAX_PAYLOAD) return null
        val buf = ByteArray(size)
        input.readFully(buf)
        return type to buf
    }

    /** GCM nonce：8 字节小端计数器 + 4 字节零。 */
    private fun nonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        var v = counter
        for (i in 0 until 8) {
            n[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return n
    }

    /**
     * HKDF-SHA256（RFC 5869）。
     *
     * Java 标准库没有 HKDF，自己拼：extract = HMAC(salt, ikm)，expand 用 T(i) 迭代。
     * salt 为 null 时按规范用 32 字节零。
     */
    private fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val salt = ByteArray(32)
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }

    private fun describe(e: Exception): String = when (e) {
        is javax.net.ssl.SSLException -> "TLS 握手失败：" + (e.message ?: "未知原因")
        is java.net.SocketTimeoutException -> "配对超时，请确认配对窗口仍开着"
        is java.net.ConnectException -> "连不上配对端口，配对窗口可能已关闭"
        else -> e.message ?: e.javaClass.simpleName
    }
}
