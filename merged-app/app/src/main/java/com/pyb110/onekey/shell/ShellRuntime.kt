package com.pyb110.onekey.shell

import android.content.Context

/**
 * Shell 通道的进程级上下文。
 *
 * OneKeyShell 的 API 刻意保持与 StellarShell 一样的「无 Context 参数」签名，
 * 以便 46 处调用点零改动迁移；Context 在这里由 Application 启动时注入一次。
 */
object ShellRuntime {
    @Volatile var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }
}
