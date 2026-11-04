package com.pyb110.onekey

import android.content.Context
import dev.onekey.jb.ShizukuShell
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-128-CBC encrypted 98260 payloads, verified by SHA-256 before use. */
object SecureAssets {
    private fun key(): ByteArray {
        val h = SecurePayloads.KEY_HEX
        return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun bytes(context: Context, name: String): ByteArray? = try {
        val asset = SecurePayloads.ASSETS[name] ?: return null
        val blob = context.assets.open(asset).use { it.readBytes() }
        if (blob.size < 32) return null
        val iv = blob.copyOfRange(0, 16)
        val body = blob.copyOfRange(16, blob.size)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), IvParameterSpec(iv))
        val plain = c.doFinal(body)
        val md = MessageDigest.getInstance("SHA-256")
        val got = md.digest(plain).joinToString("") { String.format("%02x", it) }
        if (!got.equals(SecurePayloads.HASHES[name], ignoreCase = true)) {
            null
        } else {
            plain
        }
    } catch (_: Throwable) { null }

    /** Stage a decrypted payload. Returns null on success or an error string. */
    fun stage(context: Context, name: String, dest: String, mode: Int): String? {
        val data = bytes(context, name) ?: return "解密/校验失败：" + name
        val r = ShizukuShell.stageBytes(data, dest, mode)
        return if (r.code == 0) null else "投放失败：" + name + " " + r.output
    }
}
