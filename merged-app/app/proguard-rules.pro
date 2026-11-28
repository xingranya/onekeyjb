-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class com.pyb110.onekey.MainActivity { *; }
-keep class com.pyb110.onekey.OneKeyApp { *; }
# OneKeyShell 服务端由 app_process 按类名反射启动，类名与 main 方法必须原样保留
-keep class com.pyb110.onekey.shell.OneKeyShellServer { *; }
-keep class com.pyb110.onekey.shell.ShellProtocol { *; }
-keep class com.pyb110.onekey.shell.ShellSecrets { *; }
-keepclassmembers class com.pyb110.onekey.shell.OneKeyShellServer {
    public static void main(java.lang.String[]);
}
-dontwarn moe.shizuku.**
-dontwarn rikka.shizuku.**
