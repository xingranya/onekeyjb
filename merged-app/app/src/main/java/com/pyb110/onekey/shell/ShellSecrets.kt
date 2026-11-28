package com.pyb110.onekey.shell

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * OneKeyShell 的凭据材料与启动脚本。
 *
 * 落点选择：
 * - 优先外部私有目录 /sdcard/Android/data/<pkg>/files/onekey-shell ——
 *   以 shell(uid 2000) 身份启动的服务端必须读得到 token，而 App 自己的 filesDir(0700) 它进不去。
 *   该目录受分区存储保护，其它 App 读不到，shell 与 root 读得到。
 * - 外部目录不可写时退回内部 filesDir —— 此时只有 root 身份的服务端能读，
 *   即 L1（su 引导）可用、L2（无线调试）不可用。
 *
 * token 同时决定抽象 socket 名（前 16 个十六进制字符），因此 socket 名不可猜；
 * 完整 token 只以文件路径传给服务端（不走命令行明文，避免 /proc/PID/cmdline 泄露）。
 */
object ShellSecrets {
    private const val DIR = "onekey-shell"
    private const val TOKEN_CHARS = 64

    /**
     * 通道目录。
     *
     * 落点必须在两次调用之间保持稳定：token、start.sh、q/、r/ 全在这里，
     * 一旦目录漂移，客户端和服务端会各看各的 token（鉴权全失败）
     * 且各看各的队列（请求投进没人读的目录）。
     *
     * 漂移的真实场景：外部存储没挂载（开机早期、USB 挂载到电脑、用户手动卸载），
     * getExternalFilesDir 返回 null 或不可写，于是退回 filesDir；
     * 等外部存储挂载好了，下一次调用又跳回外部目录。
     *
     * 所以一旦选定就钉住：用 <filesDir>/onekey-shell.pin 记住选过哪个根。
     * 钉住的目录后来不可写时不再换，宁可报错让上层显式重建 ——
     * 静默切换会让「通道时好时坏」变成无法复现的玄学。
     */
    fun dir(context: Context): File {
        val pin = File(context.filesDir, DIR + ".pin")
        val pinned = runCatching { pin.readText().trim() }.getOrDefault("")
        if (pinned.isNotEmpty()) {
            val d = File(pinned, DIR)
            if (d.isDirectory || d.mkdirs()) return d
        }
        val ext = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        val base = if (ext != null && (ext.exists() || ext.mkdirs()) && ext.canWrite()) ext else context.filesDir
        runCatching { pin.writeText(base.absolutePath) }
        val d = File(base, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun tokenFile(context: Context) = File(dir(context), "token")
    fun scriptFile(context: Context) = File(dir(context), "start.sh")
    fun logFile(context: Context) = File(dir(context), "server.log")
    fun pidFile(context: Context) = File(dir(context), "server.pid")

    /** 读取或生成 token（64 个十六进制字符 = 256 bit）。 */
    fun token(context: Context): String {
        val f = tokenFile(context)
        val cur = runCatching { f.readText().trim() }.getOrDefault("")
        if (cur.length == TOKEN_CHARS && cur.all { it in '0'..'9' || it in 'a'..'f' }) return cur
        val rnd = ByteArray(32)
        SecureRandom().nextBytes(rnd)
        val hex = rnd.joinToString("") { String.format("%02x", it) }
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(hex)
            // 必须让 shell(uid 2000) 读得到：服务端是以 shell 或 root 身份起的另一个进程。
            // 落点已在 /sdcard/Android/data/<pkg>/ 内，受分区存储保护，
            // 其它 App 即使同组也进不来，因此 0644 不扩大暴露面。
            f.setReadable(true, false)
            f.setWritable(false, false)
            f.setWritable(true, true)
        }
        return hex
    }

    /** 抽象 socket 名：泄露 64 bit，剩余 192 bit 未知。 */
    fun socketName(context: Context): String = "onekey_shell_" + token(context).substring(0, 16)

    /**
     * 生成启动脚本。
     *
     * app_process 的参数顺序是 [选项...] PARENT_DIR CLASS [ARGS...]，
     * --nice-name 必须排在 PARENT_DIR 之前，否则会被当成类名解析失败。
     */
    fun ensureScript(context: Context): File {
        val tok = token(context)
        val sock = "onekey_shell_" + tok.substring(0, 16)
        val script = scriptFile(context)
        val log = logFile(context)
        val pid = pidFile(context)
        val pkg = context.packageName
        val cls = "com.pyb110.onekey.shell.OneKeyShellServer"
        val body = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# OneKeyShell 服务端启动脚本（由 App 生成，勿手改）")
            appendLine("APK=\$(pm path " + pkg + " 2>/dev/null | head -1 | cut -d: -f2)")
            appendLine("[ -z \"\$APK\" ] && APK=\$(ls -d /data/app/*/" + pkg + "-* 2>/dev/null | head -1)/base.apk")
            appendLine("if [ -z \"\$APK\" ]; then echo no-apk; exit 3; fi")
            // 参数顺序是 app_process [vm-opts] <parent-dir> [opts] <class> [args]：
            // --nice-name 放在 /system/bin 之前会被当成 parent-dir 的兄弟选项而整条失败（实测无输出、无报错）。
            appendLine("CLASSPATH=\"\$APK\" setsid app_process /system/bin --nice-name=onekey_shell " +
                cls +
                " --socket " + sock +
                " --token-file '" + tokenFile(context).absolutePath + "'" +
                " --log '" + log.absolutePath + "'" +
                " </dev/null >>'" + log.absolutePath + "' 2>&1 &")
            appendLine("echo \$! > '" + pid.absolutePath + "'")
            // 必须等到 socket 真正绑定再返回：app_process 起 JVM 要 300~600ms，
            // 而 adb 的 shell 会话在脚本退出那一刻就关闭，会话一关它拉起的子进程会被一起清掉，
            // 表现为「adbd 回执 started，但 ps 里没有 onekey_shell」。
            // 轮询 /proc/net/unix 而不是 sleep 固定值：命中就立刻返回，失败最多等 5 秒。
            appendLine("i=0")
            appendLine("while [ \$i -lt 50 ]; do")
            appendLine("  grep -q '" + sock + "' /proc/net/unix 2>/dev/null && break")
            appendLine("  i=\$((i+1)); sleep 0.1")
            appendLine("done")
            appendLine("echo started")
        }
        runCatching {
            script.writeText(body)
            script.setExecutable(true, false)
            script.setReadable(true, false)
        }
        return script
    }
}
