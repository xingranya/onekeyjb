package com.pyb110.onekey

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Hard gate: signed build + OnePlus 16 PYB110 + exact kernel. */
object SecurityCheck {
    private const val CERT_SHA256 = "a023f95cd5c4aa99d64444215c9bd605e17d90c3140fa97e1fefe1c537be487e"
    fun reason(context: Context): String? {
        val profile = DeviceProfiles.current()
        if (profile == null) {
            val model = Build.MODEL.trim().uppercase()
            val kernel = (System.getProperty("os.version") ?: "").trim()
            return "当前设备或内核不支持：$model / $kernel"
        }
        val cert = try {
            val info = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
            )
            val der = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return "签名读取失败"
            MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { String.format("%02x", it) }
        } catch (e: Throwable) {
            return "签名校验异常"
        }
        if (!cert.equals(CERT_SHA256, ignoreCase = true)) return "安装包签名校验失败"
        return null
    }
}
