package com.pyb110.onekey.shell

import dev.onekey.jb.ShizukuShell

/**
 * 流式执行句柄。
 *
 * 引擎需要一边跑一边读输出（它靠日志行判断要不要中止），
 * 而两条通道的「长驻进程」句柄类型不同（一个是 java.lang.Process，一个是 socket 上的执行 id），
 * 所以这里抽一个共同接口，让 OneKeyEngine 只认这一种。
 */
interface ShellStream {
    fun isRunning(): Boolean
    fun exitCode(): Int?
    fun tail(n: Int): String
    fun waitFor(needle: String, timeoutSec: Long): String?
    fun destroy()
}

object ShellStreams {

    /** 按当前可用通道启动流式执行。 */
    @Throws(Exception::class)
    fun start(script: String, sink: ((String) -> Unit)?): ShellStream {
        if (OneKeyShell.available()) {
            return FromOneKey(OneKeyShell.start(script, sink))
        }
        return FromShizuku(ShizukuShell.start(script) { line -> sink?.invoke(line) })
    }

    private class FromOneKey(private val p: OneKeyShell.LineProc) : ShellStream {
        override fun isRunning() = p.isRunning()
        override fun exitCode() = p.exitCode()
        override fun tail(n: Int) = p.tail(n)
        override fun waitFor(needle: String, timeoutSec: Long) = p.waitFor(needle, timeoutSec)
        override fun destroy() = p.destroy()
    }

    private class FromShizuku(private val p: ShizukuShell.LineProc) : ShellStream {
        override fun isRunning() = p.isRunning()
        override fun exitCode() = p.exitCode()
        override fun tail(n: Int) = p.tail(n)
        override fun waitFor(needle: String, timeoutSec: Long) = p.waitFor(needle, timeoutSec)
        override fun destroy() = p.destroy()
    }
}
