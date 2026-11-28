package com.pyb110.onekey.adb

import java.math.BigInteger

/**
 * Edwards25519 曲线运算（仿射坐标 + 完备加法公式）。
 *
 * 为什么用仿射而不是扩展坐标：这个实现只在无线调试配对时用一次，
 * 每次配对做两次标量乘（约 500 次点加）。仿射坐标每次点加要一次模逆，
 * BigInteger.modInverse 对 255 位整数是微秒级，整条配对链路多花十几毫秒，
 * 用户完全感觉不到。换来的是公式可以直接对着 RFC 8032 抄，没有坐标系转换的坑。
 *
 * 安全边界：BigInteger 的运算不是常数时间的，理论上存在时序侧信道。
 * 但这里的密钥是「本机回环上的一次性配对码」，攻击者要能测时序
 * 就已经在同一台设备上有代码执行权限了，那时根本不需要侧信道。
 * 真要做常数时间就得自己写 5×51 位域运算，收益与成本不成比例。
 */
internal object Ed25519 {
    val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** 群的阶。 */
    val L: BigInteger = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    /** 曲线常数 d = -121665/121666 mod p。 */
    val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)

    /** sqrt(-1) mod p，解压时用来修正符号。 */
    private val SQRT_M1: BigInteger =
        BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    /** 单位元 (0, 1)。 */
    val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)

    /** 一个曲线点。 */
    data class Point(val x: BigInteger, val y: BigInteger)

    /** 基点 G。 */
    val G: Point by lazy { decompress(hexToBytes("5866666666666666666666666666666666666666666666666666666666666666"))!! }

    /** 完备加法（twisted Edwards，a = -1）。对 ed25519 而言这个公式对所有点都成立，不需要特判。 */
    fun add(p1: Point, p2: Point): Point {
        val x1y2 = p1.x.multiply(p2.y).mod(P)
        val y1x2 = p1.y.multiply(p2.x).mod(P)
        val y1y2 = p1.y.multiply(p2.y).mod(P)
        val x1x2 = p1.x.multiply(p2.x).mod(P)
        val dxxyy = D.multiply(x1x2).mod(P).multiply(y1y2).mod(P)

        val xNum = x1y2.add(y1x2).mod(P)
        val xDen = BigInteger.ONE.add(dxxyy).mod(P)
        val yNum = y1y2.add(x1x2).mod(P)
        val yDen = BigInteger.ONE.subtract(dxxyy).mod(P)

        return Point(
            xNum.multiply(xDen.modInverse(P)).mod(P),
            yNum.multiply(yDen.modInverse(P)).mod(P),
        )
    }

    fun neg(p: Point): Point = Point(P.subtract(p.x).mod(P), p.y)

    fun sub(p1: Point, p2: Point): Point = add(p1, neg(p2))

    /**
     * 标量乘（double-and-add）。
     *
     * 不做 mod L 归约 —— BoringSSL 的 SPAKE2 里有几处故意保留未归约的大标量
     * （w*M 用原始 w，x*G 用左移后的 x），归约了结果就错。
     */
    fun mul(scalar: BigInteger, p: Point): Point {
        if (scalar.signum() == 0) return IDENTITY
        var k = scalar
        var acc = IDENTITY
        var base = p
        while (k.signum() > 0) {
            if (k.testBit(0)) acc = add(acc, base)
            base = add(base, base)
            k = k.shiftRight(1)
        }
        return acc
    }

    /** 压缩成 32 字节（小端 y，最高位放 x 的奇偶）。 */
    fun compress(p: Point): ByteArray {
        val out = ByteArray(32)
        val yb = leBytes(p.y, 32)
        System.arraycopy(yb, 0, out, 0, 32)
        if (p.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** 解压 32 字节。失败（不在曲线上）返回 null。 */
    fun decompress(b: ByteArray): Point? {
        if (b.size != 32) return null
        val copy = b.copyOf(32)
        val sign = (copy[31].toInt() and 0x80) != 0
        copy[31] = (copy[31].toInt() and 0x7F).toByte()
        val y = fromLe(copy)
        if (y >= P) return null

        // x^2 = (y^2 - 1) / (d*y^2 + 1)
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).mod(P).add(BigInteger.ONE).mod(P)
        if (v.signum() == 0) return null

        // 标准 ed25519 开方：x = u * v^3 * (u * v^7)^((p-5)/8)
        //
        // 注意 (u*v^7) 整个在模幂底数里，不能拆成 u * v^((p-5)/8)。
        // 拆错了对某些点仍然能算出一个「看起来像」的值，只有校验步骤
        // v*x^2 == ±u 才抓得出来，所以下面那两行判断不能省。
        val v3 = v.modPow(BigInteger.valueOf(3), P)
        val v7 = v3.multiply(v3).mod(P).multiply(v).mod(P)
        val uv7 = u.multiply(v7).mod(P)
        var x = u.multiply(v3).mod(P)
            .multiply(uv7.modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P))
            .mod(P)
        val vx2 = v.multiply(x.multiply(x).mod(P)).mod(P)
        if (vx2 == u) {
            // ok
        } else if (vx2 == P.subtract(u).mod(P)) {
            x = x.multiply(SQRT_M1).mod(P)
        } else {
            return null
        }
        if (x.signum() == 0 && sign) return null
        if (x.testBit(0) != sign) x = P.subtract(x).mod(P)
        return Point(x, y)
    }

    /** 取小端字节。 */
    fun leBytes(v: BigInteger, size: Int): ByteArray {
        val out = ByteArray(size)
        var t = v
        val mask = BigInteger.valueOf(0xFF)
        for (i in 0 until size) {
            out[i] = t.and(mask).toInt().toByte()
            t = t.shiftRight(8)
        }
        return out
    }

    fun fromLe(b: ByteArray): BigInteger {
        val be = ByteArray(b.size)
        for (i in b.indices) be[i] = b[b.size - 1 - i]
        return BigInteger(1, be)
    }

    fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
