package com.pyb110.onekey.shell

import android.content.Context
import com.pyb110.onekey.StellarShell

/**
 * Shell 通道引导阶梯。
 *
 * 每一级都是一次「把服务端拉起来」的尝试，任一级成功即停：
 *
 *   L0 连接已存在的 socket        —— 服务端还活着，直接复用（最常见）
 *   L1 root 身份拉起               —— 已有 root 时，用 su 起一个 uid 0 的服务端
 *   L1b KernelSU 开机模块          —— 写 /data/adb/modules/onekey-shell，每次开机自动起
 *   L2 无线调试自配对              —— 待实现（需 vendored adb 客户端，见方案 P6）
 *   L3 用户手动一条命令            —— 兜底，UI 展示可复制的 adb 命令
 *
 * 顺序是刻意的：先试最省事也最安全的，再试需要 root 的，最后才麻烦用户。
 */
object ShellBootstrap {

    sealed class Step {
        data class Ok(val label: String, val uid: Int) : Step()
        data class Fail(val label: String, val reason: String) : Step()
    }

    data class Outcome(val ok: Boolean, val label: String, val uid: Int, val steps: List<Step>, val manualCommand: String)

    /** 一次完整引导。 */
    fun start(context: Context, log: ((String) -> Unit)? = null): Outcome {
        ShellRuntime.init(context)
        val steps = ArrayList<Step>()
        val say: (String) -> Unit = { log?.invoke(it) }

        // L0：已存在
        if (OneKeyShell.available()) {
            val uid = OneKeyShell.serviceUid()
            steps.add(Step.Ok("复用已运行的服务端", uid))
            say("Shell：复用已运行的服务端（uid=" + uid + "）")
            return Outcome(true, "复用已运行的服务端", uid, steps, manual(context))
        }
        steps.add(Step.Fail("复用已运行的服务端", "socket 无响应"))

        // L1：su 拉起
        val su = suPath(context)
        if (su != null) {
            val r = launchWithSu(context, su)
            if (r != null) {
                steps.add(Step.Ok("root 拉起", r))
                say("Shell：已用 root 拉起服务端（uid=" + r + "）")
                return Outcome(true, "root 拉起", r, steps, manual(context))
            }
            steps.add(Step.Fail("root 拉起", "su 执行后 socket 仍无响应"))
        } else {
            steps.add(Step.Fail("root 拉起", "未找到可用的 su"))
        }

        // L1b：KernelSU 模块（仅在已有 root 且 su 可用时才有意义）
        if (su != null) {
            val r = installKsuModule(context, su)
            if (r == null) {
                steps.add(Step.Fail("KernelSU 开机模块", "写入失败"))
            } else if (r) {
                steps.add(Step.Ok("KernelSU 开机模块", 0))
                say("Shell：已安装开机自启模块，下次开机会自动就绪")
            } else {
                steps.add(Step.Fail("KernelSU 开机模块", "已存在，跳过"))
            }
        }

        // L2：无线调试自配对
        //
        // 这一步要用户抄设备上的 6 位配对码，属于交互式引导，
        // 不能塞进这条同步链路里 —— 这里只做「已配对过」的快速重连：
        // 无线调试还开着且 adb_keys 里有我们的公钥时，全自动完成。
        val wirelessOn = runCatching {
            com.pyb110.onekey.adb.AdbBootstrap.wirelessEnabled(context)
        }.getOrDefault(false)
        if (!wirelessOn) {
            steps.add(Step.Fail("无线调试重连", "无线调试未打开"))
        } else {
            say("Shell：无线调试已打开，尝试自动重连")
            val reconnected = runCatching {
                if (com.pyb110.onekey.adb.AdbBootstrap.reconnect(context) { say(it) })
                    OneKeyShell.serviceUid() else null
            }.getOrNull()
            if (reconnected != null) {
                steps.add(Step.Ok("无线调试重连", reconnected))
                say("Shell：已通过无线调试重连（uid=" + reconnected + "）")
                return Outcome(true, "无线调试重连", reconnected, steps, manual(context))
            }
            steps.add(Step.Fail("无线调试重连", "没找到连接端口或 adbd 拒绝"))
        }

        // 最后再看一眼 Stellar 能否兜底
        if (StellarShell.available() && StellarShell.hasPermission()) {
            say("Shell：自研通道未就绪，暂时使用 Stellar")
            return Outcome(true, "Stellar 回退", StellarShell.serviceUid(), steps, manual(context))
        }

        say("Shell：全部引导失败，需手动执行一次")
        return Outcome(false, "未就绪", -1, steps, manual(context))
    }

    /**
     * 只探测、不引导。
     *
     * 自动越狱的等待循环用它：每 2 秒探一次，
     * 探不到才由调用方决定要不要付一次「启动」的代价。
     */
    fun quickReady(): Boolean =
        runCatching { OneKeyShell.available() }.getOrDefault(false) ||
            runCatching { StellarShell.available() && StellarShell.hasPermission() }.getOrDefault(false)

    /** 用户可复制的一行命令。 */
    fun manual(context: Context): String {
        val script = ShellSecrets.ensureScript(context)
        return "adb shell sh " + script.absolutePath
    }

    /** 常见 su 位置，按可用性从高到低。 */
    private fun suPath(context: Context): String? {
        val candidates = arrayOf(
            "/data/adb/ksu/bin/su",
            "/data/adb/ksud/bin/su",
            "/system_ext/bin/su",
            "/system/bin/su",
            "/system/xbin/su",
        )
        val probe = candidates.joinToString("; ") { p -> "[ -x " + p + " ] && echo " + p }
        // runDirect：此刻自研通道还没起来，走路由只会再失败一次
        val r = StellarShell.runDirect("su -c 'id -u' 2>/dev/null; echo ---; " + probe, 10)
        if (r.output.contains("uid=0") || r.output.lines().any { it.trim() == "0" }) {
            return r.output.lines().map { it.trim() }.firstOrNull { it.startsWith("/") && it.endsWith("/su") }
                ?: "su"
        }
        return null
    }

    /** 用 root 拉起服务端，成功返回 uid，失败返回 null。 */
    private fun launchWithSu(context: Context, su: String): Int? {
        val script = ShellSecrets.ensureScript(context)
        val cmd = "sh " + script.absolutePath + " >/dev/null 2>&1"
        runCatching { StellarShell.runDirect(su + " -c " + OneKeyShell.quote(cmd), 20) }
        // 等服务端把 socket 建起来：su 是异步的，立刻探测必然落空。
        // 用 probe() 而不是 available()：后者对「不可用」缓存 2 秒，会漏掉刚就绪的服务端。
        for (i in 0 until 24) {
            Thread.sleep(250)
            if (OneKeyShell.probe()) return OneKeyShell.serviceUid()
        }
        return null
    }

    /**
     * 安装 KernelSU 开机自启模块。
     *
     * 返回 true 表示新写入、false 表示已存在、null 表示失败。
     * service.sh 走的是 KernelSU 的 late_start service 阶段，
     * 此时 /data 已挂载、zygote 已起，正是起 app_process 的时机。
     */
    private fun installKsuModule(context: Context, su: String): Boolean? {
        val script = ShellSecrets.ensureScript(context)
        val cls = "com.pyb110.onekey.shell.OneKeyShellServer"
        val modDir = "/data/adb/modules/onekey_shell"
        val service = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# OneKeyShell 开机自启（由 App 写入）")
            appendLine("until [ \"\$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 2; done")
            appendLine("sh " + script.absolutePath + " >/dev/null 2>&1")
        }
        val prop = buildString {
            appendLine("id=onekey_shell")
            appendLine("name=OneKey Shell Channel")
            appendLine("version=v1")
            appendLine("versionCode=1")
            appendLine("author=OneKey")
            appendLine("description=在开机后启动 OneKey 的自托管 shell 通道，供 App 免 adb 使用。")
        }
        val b64Service = java.util.Base64.getEncoder().encodeToString(service.toByteArray())
        val b64Prop = java.util.Base64.getEncoder().encodeToString(prop.toByteArray())
        val cmd = buildString {
            append("M=").append(modDir).append("; ")
            append("if [ -d \"\$M\" ]; then echo EXISTS; exit 0; fi; ")
            append("mkdir -p \"\$M\"; ")
            append("echo ").append(b64Prop).append(" | base64 -d > \"\$M/module.prop\"; ")
            append("echo ").append(b64Service).append(" | base64 -d > \"\$M/service.sh\"; ")
            append("chmod 755 \"\$M/service.sh\"; ")
            append("chmod 644 \"\$M/module.prop\"; ")
            append("chown -R 0:0 \"\$M\"; ")
            append("echo INSTALLED")
        }
        val r = runCatching { StellarShell.runDirect(su + " -c " + OneKeyShell.quote(cmd), 25) }.getOrNull() ?: return null
        if (r.code != 0) return null
        return when {
            r.output.contains("INSTALLED") -> true
            r.output.contains("EXISTS") -> false
            else -> null
        }
    }
}
