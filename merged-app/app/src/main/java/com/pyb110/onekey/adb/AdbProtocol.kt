package com.pyb110.onekey.adb

/**
 * ADB 传输协议常量。
 *
 * 命令字是 4 字节 ASCII 的小端序整数（"SYNC" → 0x434e5953）。
 * 每条消息头 24 字节：command(4) arg0(4) arg1(4) data_length(4) data_crc32(4) magic(4)，
 * magic = command XOR 0xFFFFFFFF，接收方靠它校验对齐。
 */
object AdbProtocol {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534c5453

    const val A_VERSION = 0x01000000
    const val A_MAXDATA = 4096
    const val A_STLS_VERSION = 0x01000000

    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3
}
