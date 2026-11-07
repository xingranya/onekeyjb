package com.pyb110.onekey

/**
 * 把管理器的 ksud 归一到引擎认得的固定位置。
 *
 * 背景：98260 执行引擎按「管理器 APK 目录下 lib/arm64/libksud.so」找加载器，
 * 但部分分支（SukiSU 的 r1/r2 等）把 ksud 改名成随机库（libfdbqiupsrx.so、libtqqfthevwb.so 等），
 * 该文件不存在时引擎会直接报 KSUD_MISSING。
 *
 * 判定方式：逐个把 lib/arm64 下的 so 拷到可执行目录跑 `-V`，
 * 只有真正的 ksud 会输出形如 `ksud 4.3.0-r2 (uapi: 6)` 的版本行。
 * 找到后回写成 libksud.so，之后首次运行、再次运行与热切换都走原路径。
 *
 * 需要 root：/data/app 下的库文件只有 root 可读可写。
 */
object KsudNormalizer {

    /** 探测脚本：找到真 ksud 就回写 libksud.so，并回报来源库名。 */
    fun script(pkg: String): String =
        "P=\$(pm path " + pkg + " 2>/dev/null | head -1 | sed 's/^package://'); " +
            "[ -n \"\$P\" ] || { echo NO_APK; exit 0; }; " +
            "D=\$(dirname \"\$P\"); F=\"\$D/lib/arm64/libksud.so\"; " +
            "if [ -f \"\$F\" ]; then echo EXISTS; exit 0; fi; " +
            "T=/data/local/tmp/onekey-ksud-probe; " +
            "for f in \"\$D\"/lib/arm64/*.so; do " +
            "[ -f \"\$f\" ] || continue; " +
            "cp -f \"\$f\" \"\$T\" 2>/dev/null || continue; " +
            "chmod 755 \"\$T\" 2>/dev/null || continue; " +
            "OUT=\$(timeout 8 \"\$T\" -V 2>&1); " +
            "case \"\$OUT\" in *ksud*uapi:* " +
            "cp -f \"\$f\" \"\$F\" && chmod 755 \"\$F\" && echo \"MATERIALIZED:\$(basename \"\$f\")\" && rm -f \"\$T\" && exit 0;; " +
            "esac; done; " +
            "rm -f \"\$T\"; echo MISSING"

    /** 归一化结果。 */
    sealed interface Result {
        data object Exists : Result
        data class Materialized(val source: String) : Result
        data object Missing : Result
        data object NoApk : Result
    }

    fun parse(output: String): Result {
        val text = output.trim()
        return when {
            text.contains("NO_APK") -> Result.NoApk
            text.contains("EXISTS") -> Result.Exists
            text.contains("MATERIALIZED:") ->
                Result.Materialized(text.substringAfter("MATERIALIZED:").lineSequence().first().trim())
            else -> Result.Missing
        }
    }
}
