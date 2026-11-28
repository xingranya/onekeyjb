package com.pyb110.onekey.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2（BoringSSL 的 SPAKE25519 构造）。
 *
 * 为什么不用 RFC 9382：BoringSSL 用的是自己的盲化基点 M/N，
 * 与 RFC 9382 的 edwards25519 表值不同（那张表按 RFC 8032 解压根本不在曲线上）。
 * adbd 认的是 BoringSSL 那一套，用 RFC 的参数必然配不上。
 *
 * 三个反直觉的地方，都照 BoringSSL 原样保留：
 *
 *   1. 临时私钥先 mod L 归约再左移 3 位（乘 8 消除余因子），
 *      但后面做标量乘时**不再归约** —— 归约了共享密钥就错。
 *
 *   2. 密码标量是 SHA-512(password) 归约后的值，随后做「harden」：
 *      若 bit0 置位就加 L、bit1 置位就加 2L、bit2 置位就加 4L（逐步在新值上判断），
 *      让低 3 位归零。这是 BoringSSL 修一个历史 copy-paste 错误的兼容做法
 *      （原本该在归约后 left_shift_3 却漏了，导致密码哈希低 3 位泄漏）。
 *      协议双方都必须这么干，否则掩码点落在余因子子群里，共享密钥对不上。
 *
 *   3. 会话密钥的哈希输入里，名字**带结尾 NUL**（"adb pair client\u0000" 共 16 字节），
 *      长度前缀是 8 字节小端。漏掉 NUL 就少一个字节，密钥全错。
 */
internal object Spake2 {
    /** 客户端身份，含结尾 NUL，共 16 字节。 */
    val CLIENT_NAME: ByteArray = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)

    /** 服务端身份，含结尾 NUL，共 16 字节。 */
    val SERVER_NAME: ByteArray = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

    /** 盲化基点 M（Alice 用），压缩编码，已验在曲线上。 */
    val M_POINT: Ed25519.Point by lazy {
        Ed25519.decompress(
            Ed25519.hexToBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
        ) ?: error("M 点解压失败")
    }

    /** 盲化基点 N（Bob 用），压缩编码，已验在曲线上。 */
    val N_POINT: Ed25519.Point by lazy {
        Ed25519.decompress(
            Ed25519.hexToBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")
        ) ?: error("N 点解压失败")
    }

    /** 把 64 字节小端数归约到 mod L。 */
    internal fun reduce(b: ByteArray): BigInteger = Ed25519.fromLe(b).mod(Ed25519.L)

    /** 左移 3 位（乘 8），超过 256 位的进位丢弃。 */
    internal fun shiftLeft3(v: BigInteger): BigInteger =
        v.shiftLeft(3).mod(BigInteger.TWO.pow(256))

    /**
     * 把密码标量的低 3 位置零（见类注释第 2 条）。
     *
     * 逐步在新值上判断，不能一次性读完三个位再改 —— L ≡ 5 (mod 8)，
     * 加一次 L 会连带影响 bit2，顺序错了结果就不对。
     */
    internal fun harden(v: BigInteger): BigInteger {
        var s = v
        if (s.testBit(0)) s += Ed25519.L
        if (s.testBit(1)) s += Ed25519.L.shiftLeft(1)
        if (s.testBit(2)) s += Ed25519.L.shiftLeft(2)
        return s.mod(BigInteger.TWO.pow(256))
    }

    /** 由 64 字节种子得到临时私钥标量 x。 */
    internal fun deriveX(seed: ByteArray): BigInteger = shiftLeft3(reduce(seed))

    /** 由密码得到硬化后的标量 w，以及参与会话哈希的 pwHash。 */
    internal fun deriveW(password: ByteArray): Pair<BigInteger, ByteArray> {
        val pwHash = MessageDigest.getInstance("SHA-512").digest(password)
        return harden(reduce(pwHash)) to pwHash
    }

    /** 8 字节小端长度前缀。 */
    private fun feed(md: MessageDigest, data: ByteArray) {
        val len = ByteArray(8)
        var v = data.size.toLong()
        for (i in 0 until 8) {
            len[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        md.update(len)
        md.update(data)
    }

    /**
     * 一次配对会话。
     *
     * [alice] 为 true 表示客户端（用 M 掩码、用 N 去掩码）；
     * 为 false 表示服务端。App 里只当客户端，服务端那份留给单测交叉验证 ——
     * 单边实现写错了自己看不出来，双边跑通才能证明公式与常量没抄错。
     */
    class Session internal constructor(
        private val alice: Boolean,
        private val x: BigInteger,
        private val w: BigInteger,
        private val pwHash: ByteArray,
    ) {
        /** 我方消息（32 字节），要发给对端。 */
        lateinit var myMessage: ByteArray
            private set

        init {
            val mask = if (alice) M_POINT else N_POINT
            val p = Ed25519.add(Ed25519.mul(x, Ed25519.G), Ed25519.mul(w, mask))
            myMessage = Ed25519.compress(p)
        }

        companion object {
            /**
             * 建立会话并生成我方消息。
             *
             * [password] 是 6 位配对码的 ASCII 字节；生产上还要在尾部拼上
             * TLS exporter 的 64 字节（信道绑定），见 AdbPairing。
             */
            fun start(password: ByteArray, alice: Boolean = true, seed: ByteArray? = null): Session {
                val rnd = seed ?: ByteArray(64).also { SecureRandom().nextBytes(it) }
                val (w, pwHash) = deriveW(password)
                return Session(alice, deriveX(rnd), w, pwHash)
            }
        }

        /**
         * 处理对端消息，导出会话密钥材料（64 字节）。
         *
         * 对端消息必须能解压成曲线上的点；解压失败说明对端不是 SPAKE2 实现，
         * 或者中间的 TLS 已经被换掉了。
         */
        fun process(theirMessage: ByteArray): ByteArray? {
            if (theirMessage.size != 32) return null
            val peer = Ed25519.decompress(theirMessage) ?: return null

            // Q = peerMsg - w*<对端角色对应的基点>
            val unmask = if (alice) N_POINT else M_POINT
            val q = Ed25519.sub(peer, Ed25519.mul(w, unmask))

            // DH = x*Q
            val dh = Ed25519.compress(Ed25519.mul(x, q))

            // keyMaterial = SHA-512(带长度前缀的六段)
            //
            // 顺序是固定的「客户端名 / 服务端名 / 客户端消息 / 服务端消息」，
            // 不随本端角色变化 —— BoringSSL 的 Bob 分支正是把两两对调，
            // 效果等同于永远按客户端在前。写成「我方在前」在单边实现里看不出错，
            // 一旦对端换角色就整条密钥对不上。
            val md = MessageDigest.getInstance("SHA-512")
            val clientMsg = if (alice) myMessage else theirMessage
            val serverMsg = if (alice) theirMessage else myMessage
            feed(md, CLIENT_NAME)
            feed(md, SERVER_NAME)
            feed(md, clientMsg)
            feed(md, serverMsg)
            feed(md, dh)
            feed(md, pwHash)
            return md.digest()
        }
    }
}
