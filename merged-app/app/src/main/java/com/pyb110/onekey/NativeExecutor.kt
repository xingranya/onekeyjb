package com.pyb110.onekey

import dev.onekey.jb.ShizukuShell
import java.io.File

/**
 * 自研执行器通道：把 assets/core/exec/onekey-exec 投放到工作目录并按其行协议解析。
 * 协议：VER/STAGE/PROGRESS/SLIDE_OK/WARN/ERROR/DONE，见 native/executor/main.c。
 */
object NativeExecutor {
    private const val ROOT = "/data/local/tmp/onekey-exec"

    data class Outcome(val ok: Boolean, val log: List<String>)

    fun run(
        context: android.content.Context,
        backend: String,
        log: (String) -> Unit,
        stage: (String) -> Unit,
        progress: (Int) -> Unit,
    ): Boolean {
        val suffix = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val ws = "$ROOT/$suffix"
        log("自研执行器：准备工作目录")
        if (!ShizukuShell.run("mkdir -p $ws && chmod 700 $ws && echo PREP", 20).output.contains("PREP")) {
            log("工作目录创建失败")
            return false
        }
        // 投放执行器与符号表（符号表按机型档案选）
        val profile = DeviceProfiles.current()
        val symbols = profile?.symbolsAsset
        if (ShizukuShell.stageAsset(context, "core/exec/onekey-exec", "$ws/onekey-exec", 755).code != 0) {
            log("执行器投放失败")
            return false
        }
        // 无内置符号表的机型不投放，也不拿其它机型的符号表顶替：内核地址不同，用错会写错位置。
        if (symbols != null) {
            if (ShizukuShell.stageAsset(context, symbols, "$ws/device-symbols.txt", 644).code != 0) {
                log("符号表投放失败：" + symbols)
                return false
            }
        } else {
            log("该机型无内置符号表，自研执行器不可用")
            return false
        }
        val cmd = "cd $ws && ./onekey-exec run $ws $backend 2>&1"
        log("执行器启动")
        var ok = false
        var lastStage = ""
        try {
            val proc = ShizukuShell.start(cmd) { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@start
                when {
                    line.startsWith("STAGE=") -> {
                        val s = line.removePrefix("STAGE=")
                        if (s != lastStage) { lastStage = s; RunReport.stoppedAt = stageName(s); stage(stageName(s)) }
                    }
                    line.startsWith("PROGRESS=") -> {
                        val body = line.removePrefix("PROGRESS=")
                        val pct = body.substringBefore(' ').toIntOrNull() ?: 0
                        progress(pct)
                        val note = body.substringAfter(' ', "").trim()
                        if (note.isNotEmpty()) log(note)
                    }
                    line.startsWith("SLIDE_OK=") -> log("滑动量已确认：" + line.removePrefix("SLIDE_OK="))
                    line.startsWith("WARN=") -> log("提示：" + line.removePrefix("WARN="))
                    line.startsWith("ERROR=") -> {
                        val msg = line.removePrefix("ERROR=")
                        log("失败：" + msg)
                        RunReport.stop(lastStage.ifBlank { "执行器" }, msg)
                    }
                    line.startsWith("DONE=") -> ok = line.endsWith("ok")
                    line.startsWith("VER=") || line.startsWith("INFO=") -> log(line)
                    else -> if (line.length in 3..200) log("  " + line)
                }
            }
            val deadline = System.currentTimeMillis() + 120_000
            while (proc.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(300)
            if (proc.isRunning()) {
                proc.destroy()
                log("执行器超时，已终止")
            }
        } catch (e: Throwable) {
            log("执行器异常：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            ShizukuShell.run("rm -rf $ws; true", 10)
        }
        return ok
    }

    private fun stageName(key: String) = when (key) {
        "preflight" -> "环境检查"
        "leak" -> "推导内核滑动量"
        "plant" -> "布置提权原语"
        "load" -> "加载内核模块"
        "verify" -> "验证 root"
        else -> key
    }

    /** assets 里是否带自研执行器。 */
    fun available(context: android.content.Context): Boolean = try {
        context.assets.open("core/exec/onekey-exec").close(); true
    } catch (e: Throwable) { false }
}
