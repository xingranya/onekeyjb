package com.pyb110.onekey

import android.content.Context
import dev.onekey.jb.ShizukuShell

/**
 * /data/local/tmp 的精确清理器。
 * 只处理 onekey 自己的目录/文件，绝不做全目录 rm -rf，也不碰 /data/adb 下的管理器数据。
 */
object TempJanitor {
    private const val TMP = "/data/local/tmp"

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun versionCode(ctx: Context): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }

    fun cacheDir(ctx: Context): String = TMP + "/onekey-cache-" + versionCode(ctx)

    /**
     * @param activeWorkDir 本次仍在使用的目录，不能删；null 表示清理所有非缓存残留。
     */
    fun sweep(ctx: Context, activeWorkDir: String?): String {
        val keepDir = activeWorkDir ?: ""
        val cache = cacheDir(ctx)
        val cmd = buildString {
            append("rm -rf ")
            append(TMP).append("/onekey.old.* ")
            append(TMP).append("/onekey-su ")
            append(TMP).append("/onekey-fix ")
            append(TMP).append("/onekey_mgr_apk ")
            append(TMP).append("/onekey_mgr_pkg ")
            append(TMP).append("/onekey_dir ")
            append(TMP).append("/post_stage.ok ")
            append(TMP).append("/post.sh ")
            append(TMP).append("/ksu_grant ")
            append(TMP).append("/root_stage.sh ")
            append(TMP).append("/root_run ")
            append(TMP).append("/root_backend ")
            append(TMP).append("/root_manager_pkg ")
            append(TMP).append("/root_manager_apk ")
            append(TMP).append("/root_expected_uapi ")
            append(TMP).append("/root_expected_version ")
            append(TMP).append("/root_strict ")
            append(TMP).append("/root_antiroot ")
            append(TMP).append("/root_su_compat ")
            append(TMP).append("/ksu-official.ko ")
            append(TMP).append("/ksu-op3.ko ")
            append(TMP).append("/ksud_official ")
            append(TMP).append("/ksud342 ")
            append(TMP).append("/ksud_next ")
            append(TMP).append("/ksud_bakasu ")
            append(TMP).append("/ksud_sukisu ")
            append(TMP).append("/ksud_kernelsu ")
            append(TMP).append("/CMD ")
            append(TMP).append("/CMD.run ")
            append(TMP).append("/KSU_RESULT ")
            append(TMP).append("/KSU_RESULT2 ")
            append(TMP).append("/NOLOAD ")
            append(TMP).append("/DOLOAD ")
            append(TMP).append("/su_repair_launch.log ")
            append(TMP).append("/su_repair.log ")
            append("2>/dev/null; ")
            // 清理所有旧的 onekey-<数字>/onekey-<数字>-* workDir，跳过本次
            append("for d in ").append(TMP).append("/onekey-[0-9]*; do ")
            append("[ -e \"\$d\" ] || continue; ")
            append("[ \"\$d\" = ").append(shq(keepDir)).append(" ] && continue; ")
            append("rm -rf \"\$d\"; done; ")
            // 缓存只保留当前 versionCode；其它版本清理
            append("for d in ").append(TMP).append("/onekey-cache-*; do ")
            append("[ -e \"\$d\" ] || continue; ")
            append("[ \"\$d\" = ").append(shq(cache)).append(" ] && continue; ")
            append("rm -rf \"\$d\"; done; ")
            // 日志截断，保留最近一份
            append("for f in ").append(TMP).append("/onekey-console.log ").append(TMP).append("/onekey-softreboot.log; do ")
            append("[ -f \"\$f\" ] || continue; ")
            append("sz=\$(wc -c < \"\$f\" 2>/dev/null || echo 0); ")
            append("[ \"\$sz\" -gt 262144 ] && tail -c 131072 \"\$f\" > \"\$f.tmp\" 2>/dev/null && mv -f \"\$f.tmp\" \"\$f\"; done; ")
            append("echo JANITOR_DONE")
        }
        val r = ShizukuShell.run(cmd, 60)
        return r.output.trim()
    }

    fun diskUsage(ctx: Context): String {
        val r = ShizukuShell.run(
            "du -sh " + shq(TMP + "/onekey-cache-" + versionCode(ctx)) + " " +
                shq(TMP) + " 2>/dev/null; echo JANITOR_DU", 20
        )
        return r.output.trim()
    }
}
