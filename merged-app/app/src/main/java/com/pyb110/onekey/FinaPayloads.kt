package com.pyb110.onekey

import android.content.Context
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Extracts FinaSU's own libksud.so so the daemon always matches the installed manager. */
object FinaPayloads {
    fun ksud(context: Context): ByteArray? {
        val apk = SecureAssets.bytes(context, "FinaSU.apk") ?: return null
        return try {
            ZipInputStream(ByteArrayInputStream(apk)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name == "lib/arm64-v8a/libksud.so") return z.readBytes()
                    e = z.nextEntry
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
