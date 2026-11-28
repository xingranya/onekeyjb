package com.pyb110.onekey.shell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * OneKeyShell 帧协议。
 *
 * 帧 = [type:1][len:4 BE][body:len]，单帧上限 4 MiB，body 内部一律大端。
 *
 * 设计取舍：
 * - 不用 JSON：服务端跑在 app_process 里，JSON 要走 org.json，
 *   而 org.json 在 JVM 单测里是 stub，协议层就再也测不了了。二进制编解码可以纯单测。
 * - 不依赖 binder：binder 需要 aidl 生成 + 系统权限校验，
 *   而我们只需要「以另一个 uid 跑 shell」，socket 足够且不撞 binder 的权限模型。
 */
object ShellProtocol {
    const val VERSION = 1
    const val MAX_FRAME = 4 * 1024 * 1024

    const val T_HELLO = 0x01
    const val T_AUTH = 0x02
    const val T_AUTH_OK = 0x03
    const val T_AUTH_FAIL = 0x04
    const val T_EXEC = 0x05
    const val T_OUT = 0x06
    const val T_EXIT = 0x07
    const val T_PING = 0x08
    const val T_PONG = 0x09
    const val T_BYE = 0x0A
    const val T_STAT = 0x0B
    const val T_KILL = 0x0C

    const val STREAM_OUT = 1
    const val STREAM_ERR = 2

    class Frame(val type: Int, val body: ByteArray)

    fun pack(block: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { it.block() }
        return bos.toByteArray()
    }

    fun writeStr(out: DataOutputStream, s: String) {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
    }

    fun readStr(input: DataInputStream): String {
        val n = input.readInt()
        if (n < 0 || n > MAX_FRAME) throw EOFException("字符串长度非法: " + n)
        val b = ByteArray(n)
        input.readFully(b)
        return String(b, StandardCharsets.UTF_8)
    }

    fun write(out: OutputStream, type: Int, body: ByteArray) {
        require(body.size <= MAX_FRAME) { "帧过大: " + body.size }
        val head = ByteArray(5)
        head[0] = type.toByte()
        head[1] = (body.size ushr 24).toByte()
        head[2] = (body.size ushr 16).toByte()
        head[3] = (body.size ushr 8).toByte()
        head[4] = body.size.toByte()
        out.write(head)
        out.write(body)
        out.flush()
    }

    /** 读一帧；对端正常关闭返回 null。 */
    fun read(input: InputStream): Frame? {
        val head = ByteArray(5)
        if (!readFully(input, head)) return null
        val type = head[0].toInt() and 0xFF
        val len = ((head[1].toInt() and 0xFF) shl 24) or
            ((head[2].toInt() and 0xFF) shl 16) or
            ((head[3].toInt() and 0xFF) shl 8) or
            (head[4].toInt() and 0xFF)
        if (len < 0 || len > MAX_FRAME) throw EOFException("帧长度非法: " + len)
        val body = ByteArray(len)
        if (len > 0 && !readFully(input, body)) throw EOFException("帧体截断: 期望 " + len + " 字节")
        return Frame(type, body)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = try {
                input.read(buf, off, buf.size - off)
            } catch (_: java.io.InterruptedIOException) {
                throw EOFException("读取超时")
            }
            if (n < 0) return false
            off += n
        }
        return true
    }

    fun parser(body: ByteArray): DataInputStream = DataInputStream(ByteArrayInputStream(body))

    class ExecRequest(
        val id: Int,
        val timeoutSec: Long,
        val env: List<String>,
        val cwd: String,
        val script: String,
    )

    fun encodeExec(req: ExecRequest): ByteArray = pack {
        writeInt(req.id)
        writeInt(req.timeoutSec.toInt())
        writeInt(req.env.size)
        for (e in req.env) writeStr(this, e)
        writeStr(this, req.cwd)
        writeStr(this, req.script)
    }

    fun decodeExec(body: ByteArray): ExecRequest {
        val din = parser(body)
        val id = din.readInt()
        val timeoutSec = din.readInt().toLong()
        val envCount = din.readInt()
        if (envCount < 0 || envCount > 512) throw EOFException("环境变量数量非法: " + envCount)
        val env = ArrayList<String>(envCount)
        repeat(envCount) { env.add(readStr(din)) }
        val cwd = readStr(din)
        val script = readStr(din)
        return ExecRequest(id, timeoutSec, env, cwd, script)
    }

    fun encodeOut(id: Int, stream: Int, text: ByteArray): ByteArray = pack {
        writeInt(id)
        writeByte(stream)
        writeInt(text.size)
        write(text)
    }

    class OutFrame(val id: Int, val stream: Int, val text: ByteArray)

    fun decodeOut(body: ByteArray): OutFrame {
        val din = parser(body)
        val id = din.readInt()
        val stream = din.readUnsignedByte()
        val n = din.readInt()
        if (n < 0 || n > MAX_FRAME) throw EOFException("输出长度非法: " + n)
        val text = ByteArray(n)
        din.readFully(text)
        return OutFrame(id, stream, text)
    }

    fun encodeExit(id: Int, code: Int, durMs: Int): ByteArray = pack {
        writeInt(id)
        writeInt(code)
        writeInt(durMs)
    }

    class ExitFrame(val id: Int, val code: Int, val durMs: Int)

    fun decodeExit(body: ByteArray): ExitFrame {
        val din = parser(body)
        return ExitFrame(din.readInt(), din.readInt(), din.readInt())
    }

    fun encodeHello(uid: Int, nonce: ByteArray): ByteArray = pack {
        writeShort(VERSION)
        writeInt(uid)
        writeByte(nonce.size)
        write(nonce)
    }

    class Hello(val version: Int, val uid: Int, val nonce: ByteArray)

    fun decodeHello(body: ByteArray): Hello {
        val din = parser(body)
        val ver = din.readShort().toInt()
        val uid = din.readInt()
        val n = din.readUnsignedByte()
        val nonce = ByteArray(n)
        din.readFully(nonce)
        return Hello(ver, uid, nonce)
    }
}
