package com.pyb110.onekey

import android.app.Application
import com.pyb110.onekey.shell.ShellRuntime
import com.pyb110.onekey.shell.ShellSecrets

/**
 * 进程级初始化。
 *
 * 只做三件必须早于任何 UI/服务的事：
 * 1. 把 ApplicationContext 交给 Shell 通道（OneKeyShell 的 API 刻意不带 Context 参数）；
 * 2. 生成/复用 token 与 start.sh（幂等，已存在就只读一次）；
 * 3. 预置 Shell 通道，但不主动拉起服务端 —— 拉起要花 root 或麻烦用户，
 *    留到用户真正需要时（进入首页、点「启动通道」、开机自动越狱）再做。
 */
class OneKeyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { ShellRuntime.init(this) }
        runCatching { ShellSecrets.ensureScript(this) }
    }
}
