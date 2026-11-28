package com.pyb110.onekey.adb

import android.content.Context
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * ADB 客户端握手材料。
 *
 * ADB 用同一把 RSA 私钥做两件事：
 *   1. 回应 adbd 的 A_AUTH token 挑战（SHA1 或 SHA256 with RSA，见 sign()）
 *   2. 作为 TLS 客户端证书（Android 11+ 的无线调试走 TLS，adbd 侧校验证书）
 *
 * 公钥还要额外编码成 adb 自己的格式（`adbPublicKey`），布局是
 *   struct RSAPublicKey { u32 len; u32 n0inv; u32 n[64]; u32 rr[64]; u32 exponent; }
 * 全部小端，后面接 " name\0"。n0inv 是 -1/n[0] mod 2^32，rr 是 R^2 mod n（R = 2^2048）。
 * 这不是标准格式，但 adbd 只认这个，所以必须自己算。
 *
 * 存储：PKCS8 DER 明文落盘到 filesDir/adb-key.pk8（0600）。
 * filesDir 是 0700，同用户其它 App 读不到；但 root/su 身份可读。
 * 本机 adb 密钥泄露的后果仅限于「别人能连这台机器的 adbd」，
 * 而拿到 root 的人本来就不需要它，所以不做额外包裹 —— 加了 AndroidKeyStore
 * 也只是把明文换成「同一台机器上的 AES key」，收益抵不上每次签名都要解密的复杂度。
 */
class AdbKey private constructor(
    private val privateKey: RSAPrivateKey,
    val publicKey: RSAPublicKey,
    val certificate: X509Certificate,
    private val name: String,
) {
    companion object {
        private const val MODULUS_BYTES = 2048 / 8
        private const val MODULUS_WORDS = MODULUS_BYTES / 4
        private const val ADB_PUBKEY_SIZE = 524

        private fun storeFile(context: Context) = File(context.filesDir, "adb-key.pk8")

        /** 单测用：直接用给定的一对密钥构造，不走文件存储。 */
        internal fun forTest(priv: RSAPrivateKey, pub: RSAPublicKey): AdbKey =
            AdbKey(priv, pub, selfSigned(priv, pub), "onekey")

        /**
         * 读取或生成密钥对。
         *
         * 必须整体加锁 + 原子写。并发调用的真实场景：开机时 BootReceiver 起
         * AutoJailbreakService 走 reconnect()，而用户此刻可能同时点了「无线调试配对」。
         * 两条线程同时进这里、文件又不存在时，会各生成一把 RSA 私钥并互相覆盖：
         * 先返回的那个 AdbKey 对象仍持有**已被覆盖的旧私钥**，用它配对成功，
         * adbd 的 adb_keys 里存的是旧公钥；下一次重连从文件读出**新私钥**，
         * TLS 握手时 adbd 发现公钥不认识，立刻断连。
         * 而错误提示会判成「还没配对过」，引导用户再配一次 —— 但文件里的新私钥
         * 每次都会被同一路径读回，用户重复配对也只在 adb_keys 里堆条目，永远修不好。
         *
         * 原子写（tmp + rename）是为了防「写到一半进程被杀」留下半截 DER，
         * 那样下次启动解析失败 → 又生成新 key → 又一次全设备重新配对。
         */
        @Synchronized
        fun get(context: Context): AdbKey {
            val f = storeFile(context)
            val pk = runCatching {
                if (!f.isFile) return@runCatching null
                val der = f.readBytes()
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
            }.getOrNull()

            if (pk != null) {
                val pub = derivePublic(pk)
                return AdbKey(pk, pub, selfSigned(pk, pub), "onekey")
            }

            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048, SecureRandom())
            val kp = gen.generateKeyPair()
            val priv = kp.private as RSAPrivateKey
            val pub = kp.public as RSAPublicKey
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeBytes(priv.encoded)
                tmp.setReadable(false, false)
                tmp.setReadable(true, true)
                // rename 在同一文件系统内是原子的：要么看到完整新文件，要么还是旧文件，
                // 不存在「半截 DER」这个中间态。
                if (!tmp.renameTo(f)) {
                    f.writeBytes(priv.encoded)
                    f.setReadable(false, false)
                    f.setReadable(true, true)
                    runCatching { tmp.delete() }
                }
            }
            // 回读校验：写盘失败（分区满、SELinux 拦住）时不要把一个
            // 「下次启动就消失」的 key 当成已持久化交给调用方。
            val persisted = runCatching {
                KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(f.readBytes())) as RSAPrivateKey
            }.getOrNull()
            return if (persisted != null) {
                val p2 = derivePublic(persisted)
                AdbKey(persisted, p2, selfSigned(persisted, p2), "onekey")
            } else {
                AdbKey(priv, pub, selfSigned(priv, pub), "onekey")
            }
        }

        internal fun derivePublic(priv: RSAPrivateKey): RSAPublicKey =
            KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(priv.modulus, BigInteger.valueOf(65537))
            ) as RSAPublicKey

        /**
         * 自签名证书。
         *
         * 不引入 BouncyCastle（bcpkix 约 4 MB，只为一个证书不值），
         * 手工拼 DER。字段全部用最短编码：CN=00、序列号 1、有效期 1970 → 2048。
         * adbd 只校验证书里的公钥与 TLS 握手，不看签名链、不看有效期，
         * 所以这张「假」证书完全够用。
         */
        /**
         * 生成自签证书。
         *
         * internal 而不是 private：字段序写错过一次（subject 跑到 validity 前面，
         * CertificateFactory 报 MSTRING_WRONG_TAG），单测要能直接构造它来守住这个顺序。
         */
        internal fun selfSigned(priv: RSAPrivateKey, pub: RSAPublicKey): X509Certificate {
            val tbs = DerWriter().apply {
                sequence {
                    // version [0] EXPLICIT INTEGER 2 (v3)
                    tagged(0xA0) { integer(BigInteger.valueOf(2)) }
                    integer(BigInteger.ONE)
                    // signature AlgorithmIdentifier: sha256WithRSAEncryption
                    sequence {
                        oid("1.2.840.113549.1.1.11")
                        nul()
                    }
                    // issuer: CN=00
                    sequence {
                        set {
                            sequence {
                                oid("2.5.4.3")
                                utf8("00")
                            }
                        }
                    }
                    // validity: 1970-01-01 → 2048-01-01
                    //
                    // 顺序不能动：TBSCertificate 的字段序是
                    // version → serial → signature → issuer → validity → subject → spki。
                    // 把 subject 写在 validity 前面，解析器会把 subject 的 SEQUENCE
                    // 当成 Validity 期望的 UTCTime，报 MSTRING_WRONG_TAG。
                    sequence {
                        utcTime("700101000000Z")
                        generalizedTime("20480101000000Z")
                    }
                    // subject: CN=00
                    sequence {
                        set {
                            sequence {
                                oid("2.5.4.3")
                                utf8("00")
                            }
                        }
                    }
                    // subjectPublicKeyInfo: 直接复用平台给的编码
                    raw(pub.encoded)
                }
            }.bytes()

            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(priv)
                update(tbs)
                sign()
            }

            val cert = DerWriter().apply {
                sequence {
                    raw(tbs)
                    sequence {
                        oid("1.2.840.113549.1.1.11")
                        nul()
                    }
                    bitString(sig)
                }
            }.bytes()

            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
        }
    }

    /**
     * 回应 A_AUTH 的 token 挑战。
     *
     * 算法按 token 长度选，这不是遗留代码：AOSP 的 adb_auth_openssl.cpp
     * 对 20 字节 token 用 EVP_sha1()、32 字节用 EVP_sha256()。
     * 无线调试走 TLS 证书，几乎不会走到这里；但明文 USB 调试的 A_AUTH
     * 挑战就是 20 字节，选错会被 adbd 直接拒绝。
     */
    fun sign(token: ByteArray): ByteArray {
        val algo = if (token.size >= 32) "SHA256withRSA" else "SHA1withRSA"
        val s = Signature.getInstance(algo)
        s.initSign(privateKey)
        s.update(token)
        return s.sign()
    }

    /** adb 私有公钥格式（见类注释）。 */
    val adbPublicKey: ByteArray by lazy {
        val r32 = BigInteger.ZERO.setBit(32)
        val n0inv = publicKey.modulus.remainder(r32).modInverse(r32).negate()
        val r = BigInteger.ZERO.setBit(MODULUS_BYTES * 8)
        val rr = r.modPow(BigInteger.valueOf(2), publicKey.modulus)

        val buf = ByteBuffer.allocate(ADB_PUBKEY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MODULUS_WORDS)
        buf.putInt(n0inv.toInt())
        words(publicKey.modulus).forEach { buf.putInt(it) }
        words(rr).forEach { buf.putInt(it) }
        buf.putInt(publicKey.publicExponent.toInt())

        // 用 java.util.Base64 而不是 android.util.Base64：后者在 JVM 单测里是桩，
        // 返回 null，公钥布局这条最该被测的逻辑反而测不到。
        val b64 = Base64.getEncoder().encode(buf.array())
        val suffix = (" " + name + "\u0000").toByteArray(Charsets.UTF_8)
        return@lazy b64 + suffix
    }

    private fun words(v: BigInteger): IntArray {
        val out = IntArray(MODULUS_WORDS)
        var tmp = v
        val r32 = BigInteger.ZERO.setBit(32)
        for (i in 0 until MODULUS_WORDS) {
            val (q, m) = tmp.divideAndRemainder(r32)
            tmp = q
            out[i] = m.toInt()
        }
        return out
    }

    /** TLS 客户端上下文：用自签证书，信任所有服务端证书（连的是本机 adbd）。 */
    val sslContext: SSLContext by lazy {
        val km = object : X509ExtendedKeyManager() {
            private val alias = "key"
            // keyTypes 在 TLS 1.3 下可能是 ["EC", "RSA"] 或 ["RSASSA-PSS"]，
            // 精确比较 "RSA" 会在某些 ROM 上匹配不上，导致「no suitable client certificate」。
            private fun wantsRsa(keyTypes: Array<out String>): Boolean =
                keyTypes.any { it.contains("RSA", ignoreCase = true) }

            override fun chooseClientAlias(
                keyTypes: Array<out String>, issuers: Array<out Principal>?, socket: java.net.Socket?,
            ): String? = if (wantsRsa(keyTypes)) alias else null
            override fun chooseEngineClientAlias(
                keyTypes: Array<out String>, issuers: Array<out Principal>?, engine: SSLEngine?,
            ): String? = if (wantsRsa(keyTypes)) alias else null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == this.alias) arrayOf(certificate) else null
            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == this.alias) privateKey else null
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (keyType == null || keyType.contains("RSA", ignoreCase = true)) arrayOf(alias) else null
            override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: java.net.Socket?): String? = null
        }
        val tm = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(km), arrayOf(tm), SecureRandom())
        }
    }

    /**
     * 密钥指纹，用于 UI 显示与排障。
     *
     * 32 字节 → 95 个字符（16 进制 + 冒号）。不要截断成半截：
     * 用户拿它去和另一台机器 / adb_keys 比对时，前缀相同但长度不同
     * 会被判成「不匹配」，反而误导排障。
     */
    val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
            .joinToString(":") { String.format("%02X", it) }
    }
}

/**
 * 极简 DER 编码器。
 *
 * 只实现 X.509 需要的几个类型：SEQUENCE / SET / INTEGER / OID / NULL /
 * UTF8String / UTCTime / GeneralizedTime / BIT STRING / 原始字节。
 * 长度编码只支持短形式与两字节长形式 —— 这张证书最大也就几百字节，够用。
 */
private class DerWriter {
    private val out = java.io.ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    fun sequence(block: DerWriter.() -> Unit) = tag(0x30, block)
    fun set(block: DerWriter.() -> Unit) = tag(0x31, block)

    fun tagged(tag: Int, block: DerWriter.() -> Unit) {
        val inner = DerWriter().apply(block).bytes()
        out.write(tag)
        writeLength(inner.size)
        out.write(inner)
    }

    private fun tag(t: Int, block: DerWriter.() -> Unit) {
        val inner = DerWriter().apply(block).bytes()
        out.write(t)
        writeLength(inner.size)
        out.write(inner)
    }

    fun integer(v: BigInteger) {
        val b = v.toByteArray()
        out.write(0x02)
        writeLength(b.size)
        out.write(b)
    }

    fun oid(dotted: String) {
        val parts = dotted.split('.').map { it.toLong() }
        val body = java.io.ByteArrayOutputStream()
        body.write((parts[0] * 40 + parts[1]).toInt())
        for (i in 2 until parts.size) {
            var v = parts[i]
            val stack = ArrayDeque<Int>()
            stack.addFirst((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) {
                stack.addFirst(((v and 0x7F) or 0x80).toInt())
                v = v shr 7
            }
            for (x in stack) body.write(x)
        }
        val b = body.toByteArray()
        out.write(0x06)
        writeLength(b.size)
        out.write(b)
    }

    fun nul() {
        out.write(0x05)
        out.write(0)
    }

    fun utf8(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write(0x0C)
        writeLength(b.size)
        out.write(b)
    }

    fun utcTime(s: String) {
        val b = s.toByteArray(Charsets.US_ASCII)
        out.write(0x17)
        writeLength(b.size)
        out.write(b)
    }

    fun generalizedTime(s: String) {
        val b = s.toByteArray(Charsets.US_ASCII)
        out.write(0x18)
        writeLength(b.size)
        out.write(b)
    }

    fun bitString(b: ByteArray) {
        out.write(0x03)
        writeLength(b.size + 1)
        out.write(0) // 0 个未使用位
        out.write(b)
    }

    fun raw(b: ByteArray) {
        out.write(b)
    }

    private fun writeLength(n: Int) {
        if (n < 0x80) {
            out.write(n)
        } else if (n < 0x100) {
            out.write(0x81)
            out.write(n)
        } else {
            out.write(0x82)
            out.write((n shr 8) and 0xFF)
            out.write(n and 0xFF)
        }
    }
}
