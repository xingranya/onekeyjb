package com.pyb110.onekey.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * 一条 ADB 消息。
 *
 * 线上的布局是固定 24 字节头 + 可选数据体，全部小端：
 *   command | arg0 | arg1 | data_length | data_crc32 | magic
 *
 * magic 是 command 的按位取反。它存在的意义是自同步：
 * 一旦字节流错位，magic 校验就会失败，而不是把后面的字节当成合法命令继续解析。
 */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataCrc32: Int,
    val magic: Int,
    val data: ByteArray?,
) {
    constructor(command: Int, arg0: Int, arg1: Int, text: String) :
        this(command, arg0, arg1, ("$text\u0000").toByteArray(Charsets.UTF_8))

    constructor(command: Int, arg0: Int, arg1: Int, data: ByteArray?) : this(
        command, arg0, arg1,
        data?.size ?: 0,
        crc32(data),
        command.inv(),
        data,
    )

    fun validate(): Boolean =
        command == magic.inv() && (dataLength == 0 || crc32(data) == dataCrc32)

    fun writeTo(out: DataOutputStream) {
        val head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(command)
        head.putInt(arg0)
        head.putInt(arg1)
        head.putInt(dataLength)
        head.putInt(dataCrc32)
        head.putInt(magic)
        out.write(head.array())
        if (data != null && data.isNotEmpty()) out.write(data)
        out.flush()
    }

    fun dataAsString(): String =
        data?.toString(Charsets.UTF_8)?.trimEnd('\u0000') ?: ""

    override fun toString(): String =
        "AdbMessage(cmd=0x" + Integer.toHexString(command) + ", arg0=" + arg0 +
            ", arg1=" + arg1 + ", len=" + dataLength + ")"

    companion object {
        fun readFrom(input: DataInputStream): AdbMessage {
            val head = ByteArray(24)
            input.readFully(head)
            val buf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val len = buf.int
            val crc = buf.int
            val magic = buf.int
            // 上下界一起判：len 是 int，错位时可能读到负数（如 0xFFFFFFFF）。
            // 只判上界会让负长度静默变成 null data、dataLength 带着负值往下传，
            // 下游把它当空帧回 A_OKAY，与 adbd 的状态机彻底错位。
            if (len < 0 || len > 1024 * 1024) {
                throw IllegalStateException("ADB 帧长度非法: " + len)
            }
            val data = if (len > 0) ByteArray(len).also { input.readFully(it) } else null
            return AdbMessage(command, arg0, arg1, len, crc, magic, data)
        }

        fun crc32(data: ByteArray?): Int {
            if (data == null || data.isEmpty()) return 0
            val c = CRC32()
            c.update(data)
            return c.value.toInt()
        }
    }
}
