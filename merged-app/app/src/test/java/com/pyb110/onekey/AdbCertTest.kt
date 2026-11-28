package com.pyb110.onekey

import com.pyb110.onekey.adb.AdbKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * 自签证书的编码校验。
 *
 * 这张证书只在无线调试配对的 TLS 握手里用一次，但字段序错了
 * 整个配对就崩在 OpenSSL 的 ASN.1 解析里，报错信息（MSTRING_WRONG_TAG）
 * 完全指不到「subject 写在了 validity 前面」这种根因。
 * 所以这里直接把它解一遍，把顺序固定下来。
 */
class AdbCertTest {

    private fun keyPair(): Pair<RSAPrivateKey, RSAPublicKey> {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        return kp.private as RSAPrivateKey to kp.public as RSAPublicKey
    }

    @Test
    fun selfSignedParsesAsX509() {
        val (priv, pub) = keyPair()
        val cert = AdbKey.selfSigned(priv, pub)
        assertNotNull(cert)
        // 能解出来就说明 TBSCertificate 的字段序是对的
        assertTrue(cert.encoded.isNotEmpty())
    }

    @Test
    fun subjectAndIssuerAreBothCn00() {
        val (priv, pub) = keyPair()
        val cert = AdbKey.selfSigned(priv, pub)
        assertEquals("CN=00", cert.subjectX500Principal.name)
        assertEquals("CN=00", cert.issuerX500Principal.name)
    }

    @Test
    fun signatureVerifiesWithOwnPublicKey() {
        val (priv, pub) = keyPair()
        val cert = AdbKey.selfSigned(priv, pub)
        // verify 会重算 TBS 的 DER，字段序或长度编码写错都会在这里失败
        cert.verify(pub)
    }

    @Test
    fun publicKeyInCertMatchesInput() {
        val (priv, pub) = keyPair()
        val cert = AdbKey.selfSigned(priv, pub)
        assertEquals(pub, cert.publicKey)
    }

    @Test
    fun adbPublicKeyLayoutMatchesAdbFormat() {
        val (priv, pub) = keyPair()
        // 走真实构造路径才能测到 adbPublicKey 的布局
        val key = AdbKey.forTest(priv, pub)
        val text = String(key.adbPublicKey, Charsets.ISO_8859_1)
        val space = text.indexOf(' ')
        assertTrue("公钥后面必须跟一个空格和用户名", space > 0)

        // 空格前是 base64，解码后必须正好是 adb 的 524 字节结构体：
        //   4 字节 len + 4 字节 n0inv + 256 字节 n + 256 字节 rr + 4 字节 exponent
        val raw = java.util.Base64.getDecoder().decode(text.substring(0, space))
        assertEquals(524, raw.size)

        val buf = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buf.getInt())          // len 是「32 位字的个数」
        buf.getInt()                            // n0inv
        // 模数的最高位字不能是 0，否则说明大端小端搞反了
        val topWord = buf.getInt(8 + 63 * 4)
        assertTrue("模数最高位字不该为 0", topWord != 0)

        // 尾部是 " onekey\u0000"
        assertTrue(text.endsWith(" onekey\u0000"))
    }
}
