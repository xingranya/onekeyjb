package com.pyb110.onekey

/** KSU-Universal 的公开控制协议；只切换已加载模块的管理器身份。 */
internal object ManagerSwitchProtocol {
    const val NODE = "/proc/kernelsu_manager"
    data class Status(val packageName: String, val profile: String, val versionCode: Long, val featureMax: Long)

    /** 忽略 su 附加提示，只接受完整的四列状态行。 */
    fun parse(output: String): Status? = output.lineSequence().mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size != 4 || !fields[0].matches(Regex("[a-z][a-z0-9_.]+"))) return@mapNotNull null
        val version = fields[2].toLongOrNull() ?: return@mapNotNull null
        val features = fields[3].toLongOrNull() ?: return@mapNotNull null
        if (version <= 0 || features < 0) return@mapNotNull null
        Status(fields[0], fields[1], version, features)
    }.firstOrNull()

    fun payload(packageName: String, uid: Int, apkPath: String): String {
        require(packageName.matches(Regex("[a-z][a-z0-9_.]+"))) { "管理器包名无效" }
        require(uid >= 10000) { "管理器 UID 无效" }
        require(apkPath.startsWith("/data/app/") && apkPath.endsWith("/base.apk") && apkPath.none { it.isWhitespace() || it == '\u0000' }) { "管理器 APK 路径无效" }
        return "$packageName $uid $apkPath".also {
            require(it.toByteArray(Charsets.UTF_8).size < 512) { "管理器信息超出内核接口长度" }
        }
    }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun command(payload: String): String = "timeout 10 su -c " + quote(
        "printf '%s' " + quote(payload) + " > $NODE && cat $NODE"
    )
}
