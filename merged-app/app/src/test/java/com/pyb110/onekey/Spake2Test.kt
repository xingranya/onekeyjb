package com.pyb110.onekey

import com.pyb110.onekey.adb.Ed25519
import com.pyb110.onekey.adb.Spake2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * SPAKE2 与 Ed25519 的回归测试。
 *
 * 这些用例的价值不在「跑通」，而在「固定住跨实现的密钥」：
 * SPAKE2 的失败模式是双方都能跑完但导出的密钥不同，
 * 单边实现写错了自己永远看不出来。所以这里既做双边自洽，
 * 也用一组由独立 Python 实现（同一份 BoringSSL 参数）算出的固定向量钉死输出。
 */
class Spake2Test {

    private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02x", it) }

    @Test
    fun basePointIsOnCurve() {
        assertNotNull("基点 G 应在曲线上", Ed25519.G)
        // RFC 8032 的 G.x 是标准的 0x21 0x69 ... 0x1a（大端显示），
        // 这里用 BigInteger.toString(16) 对齐，别用 leBytes —— 后者会把字节序反过来。
        assertEquals(
            "基点 x 坐标应与 RFC 8032 一致",
            "216936d3cd6e53fec0a4e231fdd6dc5c692cc7609525a7b2c9562d608f25d51a",
            Ed25519.G.x.toString(16),
        )
        assertEquals(
            "基点 y 坐标应与 RFC 8032 一致",
            "6666666666666666666666666666666666666666666666666666666666666658",
            Ed25519.G.y.toString(16),
        )
    }

    @Test
    fun compressRoundTrip() {
        val p = Ed25519.mul(BigInteger.valueOf(12345), Ed25519.G)
        val enc = Ed25519.compress(p)
        val back = Ed25519.decompress(enc)
        assertNotNull("解压应成功", back)
        assertEquals("x 应往返一致", p.x, back!!.x)
        assertEquals("y 应往返一致", p.y, back.y)
    }

    @Test
    fun offCurvePointRejected() {
        // 全 0xFF 解压出来不在曲线上
        val bad = ByteArray(32) { 0xFF.toByte() }
        assertNull("非曲线点应被拒绝", Ed25519.decompress(bad))
    }

    @Test
    fun blindPointsOnCurve() {
        assertNotNull("M 点应在曲线上", Spake2.M_POINT)
        assertNotNull("N 点应在曲线上", Spake2.N_POINT)
        // 验算曲线方程：-x^2 + y^2 = 1 + d*x^2*y^2
        for (p in listOf(Spake2.M_POINT, Spake2.N_POINT)) {
            val lhs = p.y.multiply(p.y).subtract(p.x.multiply(p.x)).mod(Ed25519.P)
            val rhs = BigInteger.ONE
                .add(Ed25519.D.multiply(p.x).multiply(p.x).mod(Ed25519.P)
                    .multiply(p.y).multiply(p.y).mod(Ed25519.P))
                .mod(Ed25519.P)
            assertEquals("盲化基点应满足曲线方程", lhs, rhs)
        }
    }

    @Test
    fun hardenClearsLowThreeBits() {
        // 低 3 位无论怎么设，harden 后都必须是 0
        for (v in 0L..7L) {
            val s = Spake2.harden(BigInteger.valueOf(v).add(BigInteger.TWO.pow(200)))
            assertFalse("harden 后 bit0 应为 0（输入 $v）", s.testBit(0))
            assertFalse("harden 后 bit1 应为 0（输入 $v）", s.testBit(1))
            assertFalse("harden 后 bit2 应为 0（输入 $v）", s.testBit(2))
        }
    }

    @Test
    fun hardenKeepsValueModOrder() {
        // 加 L 的倍数不改变 mod L 的余数（这正是「加小阶点」等价于「加阶的倍数」的原因）
        val v = BigInteger.TWO.pow(180).add(BigInteger.valueOf(12345))
        val h = Spake2.harden(v)
        assertEquals("harden 不应改变 mod L 余数", v.mod(Ed25519.L), h.mod(Ed25519.L))
    }

    @Test
    fun namesAreSixteenBytes() {
        assertEquals("客户端名应为 16 字节（含 NUL）", 16, Spake2.CLIENT_NAME.size)
        assertEquals("服务端名应为 16 字节（含 NUL）", 16, Spake2.SERVER_NAME.size)
        assertEquals("名字结尾应为 NUL", 0.toByte(), Spake2.CLIENT_NAME[15])
        assertEquals("名字结尾应为 NUL", 0.toByte(), Spake2.SERVER_NAME[15])
    }

    @Test
    fun bothSidesDeriveSameKey() {
        val pw = "515109".toByteArray(Charsets.US_ASCII)
        val a = Spake2.Session.start(pw, alice = true, seed = ByteArray(64) { it.toByte() })
        val b = Spake2.Session.start(pw, alice = false, seed = ByteArray(64) { (it + 64).toByte() })

        val ka = a.process(b.myMessage)
        val kb = b.process(a.myMessage)
        assertNotNull("客户端应能导出密钥", ka)
        assertNotNull("服务端应能导出密钥", kb)
        assertArrayEquals("双方导出的密钥必须相同", ka, kb)
        assertEquals("会话密钥应为 64 字节", 64, ka!!.size)
    }

    /**
     * 固定向量：由独立 Python 实现（同一份 BoringSSL 参数）算出。
     *
     * 种子 A = 0x00..0x3F，种子 B = 0x40..0x7F，密码 "515109"。
     * 这组数字的意义是：只要有人改动曲线公式、harden 顺序、名字长度前缀
     * 或 key material 的拼接顺序，这里立刻红。
     */
    @Test
    fun fixedVectorMatchesReference() {
        val pw = "515109".toByteArray(Charsets.US_ASCII)
        val seedA = ByteArray(64) { it.toByte() }
        val seedB = ByteArray(64) { (it + 64).toByte() }

        val a = Spake2.Session.start(pw, alice = true, seed = seedA)
        val b = Spake2.Session.start(pw, alice = false, seed = seedB)

        assertEquals(
            "客户端消息应与参考实现一致",
            "54be5a5fa0bc10ad0ff60fad84a8e2e117b7e3125d27b087acefec0a2f00db11",
            hex(a.myMessage),
        )
        assertEquals(
            "服务端消息应与参考实现一致",
            "b04ee2191454e14da91ef5188e91b1317e42519a4052a3e0123f2d1798480e9a",
            hex(b.myMessage),
        )
        assertEquals(
            "会话密钥应与参考实现一致",
            "a129887188328f585057a7e4dc950fd69f4135a9cb650aae514048c43de86bfe" +
                "a4a082ea0c5ec7769af934a4d063186a93f2f63d0dd05f76947098ab37692129",
            hex(a.process(b.myMessage)!!),
        )
    }

    @Test
    fun wrongPasswordProducesDifferentKey() {
        val a = Spake2.Session.start("111111".toByteArray(Charsets.US_ASCII), alice = true, seed = ByteArray(64) { it.toByte() })
        val b = Spake2.Session.start("222222".toByteArray(Charsets.US_ASCII), alice = false, seed = ByteArray(64) { (it + 64).toByte() })
        val ka = a.process(b.myMessage)
        val kb = b.process(a.myMessage)
        assertNotNull(ka)
        assertNotNull(kb)
        assertFalse("密码不同必须导出不同密钥", ka!!.contentEquals(kb!!))
    }

    @Test
    fun invalidPeerMessageRejected() {
        val a = Spake2.Session.start("515109".toByteArray(Charsets.US_ASCII), alice = true, seed = ByteArray(64) { it.toByte() })
        assertNull("长度不对应拒绝", a.process(ByteArray(31)))
        assertNull("非曲线点应拒绝", a.process(ByteArray(32) { 0xFF.toByte() }))
    }

    @Test
    fun hkdfMatchesRfc5869() {
        // RFC 5869 Test Case 1
        val ikm = ByteArray(22) { 0x0b }
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val out = hkdfSha256(ikm, salt, info, 42)
        assertEquals(
            "HKDF-SHA256 应符合 RFC 5869 测试向量 1",
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            hex(out),
        )
    }

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) {
            ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
        }

    /** 与 AdbPairing 里同一套 HKDF，独立写一份用于验证。 */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            mac.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
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
}
