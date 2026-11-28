package com.pyb110.onekey

import com.pyb110.onekey.shell.ShellProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * OneKeyShell 帧协议单测。
 *
 * 这一层是纯字节运算，不碰 Android、不碰 socket、不碰 Context，
 * 因此可以在 JVM 上跑 —— 这正是把协议做成二进制而不是 JSON 的原因：
 * org.json 在单测里是 stub，用 JSON 就再也测不到编码层了。
 */
class ShellProtocolTest {

    private fun roundTrip(type: Int, body: ByteArray): ShellProtocol.Frame {
        val bos = ByteArrayOutputStream()
        ShellProtocol.write(bos, type, body)
        val got = ShellProtocol.read(ByteArrayInputStream(bos.toByteArray()))
        assertNotNull("应当读回一帧", got)
        return got!!
    }

    @Test
    fun frameRoundTrip() {
        val body = byteArrayOf(1, 2, 3, 4, 5)
        val f = roundTrip(ShellProtocol.T_EXEC, body)
        assertEquals(ShellProtocol.T_EXEC, f.type)
        assertArrayEquals(body, f.body)
    }

    @Test
    fun emptyBodyRoundTrip() {
        val f = roundTrip(ShellProtocol.T_BYE, ByteArray(0))
        assertEquals(ShellProtocol.T_BYE, f.type)
        assertEquals(0, f.body.size)
    }

    @Test
    fun largeBodyRoundTrip() {
        // 接近上限但不超过：验证 4 字节大端长度能正确表达 1 MiB
        val body = ByteArray(1024 * 1024) { (it and 0xFF).toByte() }
        val f = roundTrip(ShellProtocol.T_OUT, body)
        assertEquals(body.size, f.body.size)
        assertArrayEquals(body, f.body)
    }

    @Test
    fun oversizedFrameRejectedOnWrite() {
        val tooBig = ByteArray(ShellProtocol.MAX_FRAME + 1)
        var threw = false
        try {
            ShellProtocol.write(ByteArrayOutputStream(), ShellProtocol.T_OUT, tooBig)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("超过上限的帧必须写不出去", threw)
    }

    @Test
    fun truncatedHeaderReturnsNull() {
        // 只有 3 字节，读不满 5 字节帧头 —— 视为对端关闭
        val got = ShellProtocol.read(ByteArrayInputStream(byteArrayOf(1, 0, 0, 0)))
        assertNull(got)
    }

    @Test
    fun truncatedBodyThrows() {
        // 帧头声明 10 字节，实际只给 4 字节
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use {
            it.writeByte(ShellProtocol.T_OUT)
            it.writeInt(10)
            it.write(byteArrayOf(1, 2, 3, 4))
        }
        var threw = false
        try {
            ShellProtocol.read(ByteArrayInputStream(bos.toByteArray()))
        } catch (_: EOFException) {
            threw = true
        }
        assertTrue("帧体不足必须报错而不是静默返回半帧", threw)
    }

    @Test
    fun absurdLengthRejected() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use {
            it.writeByte(ShellProtocol.T_OUT)
            it.writeInt(-1)
        }
        var threw = false
        try {
            ShellProtocol.read(ByteArrayInputStream(bos.toByteArray()))
        } catch (_: EOFException) {
            threw = true
        }
        assertTrue("负长度必须拒绝", threw)
    }

    @Test
    fun execRequestRoundTrip() {
        val req = ShellProtocol.ExecRequest(
            id = 42,
            timeoutSec = 90,
            env = listOf("A=1", "B=中文", "C=x=y"),
            cwd = "/data/local/tmp",
            script = "echo '\u4f60\u597d'\nid -u",
        )
        val back = ShellProtocol.decodeExec(ShellProtocol.encodeExec(req))
        assertEquals(42, back.id)
        assertEquals(90L, back.timeoutSec)
        assertEquals(listOf("A=1", "B=中文", "C=x=y"), back.env)
        assertEquals("/data/local/tmp", back.cwd)
        assertEquals(req.script, back.script)
    }

    @Test
    fun execRequestRejectsAbsurdEnvCount() {
        val body = ShellProtocol.pack {
            writeInt(1)
            writeInt(10)
            writeInt(99999)
        }
        var threw = false
        try {
            ShellProtocol.decodeExec(body)
        } catch (_: EOFException) {
            threw = true
        }
        assertTrue("环境变量数量超限必须拒绝", threw)
    }

    @Test
    fun outFramePreservesBinary() {
        // 引擎输出可能带非 UTF-8 字节（二进制探针的原始 hex dump），必须原样透传
        val raw = byteArrayOf(0x00, 0x7F, -1, -128, 0x0A)
        val back = ShellProtocol.decodeOut(ShellProtocol.encodeOut(7, ShellProtocol.STREAM_ERR, raw))
        assertEquals(7, back.id)
        assertEquals(ShellProtocol.STREAM_ERR, back.stream)
        assertArrayEquals(raw, back.text)
    }

    @Test
    fun exitFrameRoundTrip() {
        val back = ShellProtocol.decodeExit(ShellProtocol.encodeExit(9, -1, 12345))
        assertEquals(9, back.id)
        assertEquals(-1, back.code)
        assertEquals(12345, back.durMs)
    }

    @Test
    fun helloRoundTrip() {
        val nonce = ByteArray(32) { (it * 7).toByte() }
        val back = ShellProtocol.decodeHello(ShellProtocol.encodeHello(2000, nonce))
        assertEquals(ShellProtocol.VERSION, back.version)
        assertEquals(2000, back.uid)
        assertArrayEquals(nonce, back.nonce)
    }

    @Test
    fun authOkCarriesProof() {
        val proof = ByteArray(32) { (it + 1).toByte() }
        val back = ShellProtocol.decodeAuthOk(ShellProtocol.encodeAuthOk(0, proof))
        assertEquals(0, back.uid)
        assertArrayEquals(proof, back.proof)
    }

    @Test
    fun authOkUidIsPreservedForShell() {
        // 文件队列模式下客户端靠这个字段判断「有没有 root」，
        // 2000 与 0 必须能区分开，否则开机自动越狱会在 shell 身份下开跑。
        val back = ShellProtocol.decodeAuthOk(ShellProtocol.encodeAuthOk(2000, ByteArray(32)))
        assertEquals(2000, back.uid)
    }

    @Test
    fun stringRoundTripWithSurrogates() {
        // 中文、emoji、换行都必须能过，否则日志里的中文会被截断
        val s = "失败：\u672a\u627e\u5230 su \uD83D\uDE3F\n第二行"
        val body = ShellProtocol.pack { ShellProtocol.writeStr(this, s) }
        val back = ShellProtocol.readStr(ShellProtocol.parser(body))
        assertEquals(s, back)
    }
}
