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

    /**
     * 按当前可用通道启动流式执行。
     *
     * 自研通道失败必须回落到 Stellar：OneKeyShell.start() 在
     * 「available() 为真但 submit() 拿不到连接」时会抛 IllegalStateException
     * （典型场景：服务端刚好在这两个调用之间死掉）。
     * 不接住的话异常会一路冒到 OneKeyEngine 的 catch(Throwable)，
     * 整轮判定失败、finally 还把工作目录删了 ——
     * 而此刻 Stellar 可能好好的，白白浪费一次越狱机会。
     */
    @Throws(Exception::class)
    fun start(script: String, sink: ((String) -> Unit)?): ShellStream {
        if (OneKeyShell.available()) {
            val ok = runCatching { OneKeyShell.start(script, sink) }.getOrNull()
            if (ok != null) return FromOneKey(ok)
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
