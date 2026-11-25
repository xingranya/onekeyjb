package com.pyb110.onekey

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志等级。界面上一行一个事件，等级决定左侧色条与标签。 */
enum class LogLevel(val label: String) {
    INFO("信息"),
    SUCCESS("成功"),
    WARN("警告"),
    ERROR("失败");

    companion object {
        fun fromTag(tag: String): LogLevel = when (tag) {
            "成功" -> SUCCESS
            "失败" -> ERROR
            "警告" -> WARN
            else -> INFO
        }
    }
}

/**
 * 一条结构化日志。
 *
 * 落盘格式固定为 `HH:mm:ss  [标签] 标题 · 详情 · 耗时`，
 * 既能人读，也能被 [parse] 无损还原。旧日志没有标签前缀，[parse] 会退回关键字判定。
 */
data class LogEntry(
    val time: String,
    val level: LogLevel,
    val title: String,
    val detail: String = "",
    val ms: Long? = null,
) {
    fun format(): String = buildString {
        if (time.isNotBlank()) append(time).append("  ")
        append("[").append(level.label).append("] ").append(title)
        if (detail.isNotBlank()) append(" · ").append(detail)
        if (ms != null && ms > 0) append(" · ").append(RunLog.duration(ms))
    }

    /** 给人看的一行纯文本（不带等级标签），用于分享与复制。 */
    fun plainText(): String = buildString {
        append(time).append("  ").append(title)
        if (detail.isNotBlank()) append(" · ").append(detail)
        if (ms != null && ms > 0) append(" · ").append(RunLog.duration(ms))
    }
}

/**
 * 日志标准化：引擎事件解析 + 中文标签 + 耗时格式化。
 *
 * 引擎（runner）输出两类行：
 * 1. 人读行：`[1/4]`、`MODULE_LOADED`、`DONE`、`FAIL=xxx` 等；
 * 2. 结构化行：`EV|v=1|phase=..|step=..|status=..|scope=..|apply=..|ms=..|code=..|detail=..`。
 *
 * 结构化行能表达"跳过"和"耗时"，人读行表达不了，所以优先解析结构化行。
 */
object RunLog {
    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }

    fun now(): String = timeFmt.get()!!.format(Date())

    /** 耗时格式化：小于 1 秒给毫秒，否则给秒并保留一位小数。 */
    fun duration(ms: Long): String =
        if (ms < 1000L) "${ms}ms" else String.format(Locale.US, "%.1fs", ms / 1000.0)

    // ---------------------------------------------------------------- 构造

    fun info(title: String, detail: String = "", ms: Long? = null): LogEntry =
        LogEntry(now(), LogLevel.INFO, title, detail, ms)

    fun success(title: String, detail: String = "", ms: Long? = null): LogEntry =
        LogEntry(now(), LogLevel.SUCCESS, title, detail, ms)

    fun warn(title: String, detail: String = "", ms: Long? = null): LogEntry =
        LogEntry(now(), LogLevel.WARN, title, detail, ms)

    fun error(title: String, detail: String = "", ms: Long? = null): LogEntry =
        LogEntry(now(), LogLevel.ERROR, title, detail, ms)

    /** 旧调用点：把纯文本按关键字归类成一条日志。 */
    fun plain(line: String): LogEntry = LogEntry(now(), classify(line), line.trim())

    // ---------------------------------------------------------------- 解析

    private val stampedRe = Regex("^(\\d{2}:\\d{2}:\\d{2})\\s{1,4}\\[(信息|成功|警告|失败)]\\s*(.*)$")

    /** 解析一条已落盘的日志行；无法识别等级时按关键字归类。 */
    fun parse(line: String): LogEntry {
        val m = stampedRe.find(line)
        if (m != null) {
            val body = m.groupValues[3]
            val parts = body.split(" · ")
            val title = parts.firstOrNull().orEmpty()
            var detail = ""
            var ms: Long? = null
            if (parts.size > 1) {
                val last = parts.last()
                ms = parseDuration(last)
                detail = if (ms != null) parts.drop(1).dropLast(1).joinToString(" · ") else parts.drop(1).joinToString(" · ")
            }
            return LogEntry(m.groupValues[1], LogLevel.fromTag(m.groupValues[2]), title, detail, ms)
        }
        // 旧格式或分隔行：没有等级标签
        val hasTime = line.length >= 8 && line[2] == ':' && line[5] == ':'
        if (!hasTime) return LogEntry("", classify(line), line.trim())
        val time = line.take(8)
        val body = if (line.length > 10) line.drop(10) else line
        return LogEntry(time, classify(body), body.trim())
    }

    private fun parseDuration(s: String): Long? {
        val t = s.trim()
        if (t.endsWith("ms")) return t.dropLast(2).toLongOrNull()
        if (t.endsWith("s")) return t.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
        return null
    }

    /**
     * 关键字兜底归类，供旧日志与未经结构化包装的行使用。
     *
     * 判定顺序是本函数的全部设计：中文里否定式和肯定式共用词根，
     * 「连续 4 次尝试未成功」含「成功」、「未完成」含「完成」，
     * 若先判肯定式，整条失败日志会被染成绿色，比不分类更误导。
     * 所以一律先判特例与否定式，最后才判肯定式。
     */
    fun classify(line: String): LogLevel {
        // 特例：「未发现已知冲突模块；可以正常越狱」是结论良好的句子。
        if (line.contains("未发现")) return LogLevel.SUCCESS
        // 「发现已知冲突模块」反过来是坏消息，必须在肯定式之前拦下。
        if (line.contains("发现已知冲突") || line.contains("发现冲突")) return LogLevel.ERROR
        return when {
            // 否定式与故障词优先
            line.contains("未成功") || line.contains("没有成功") || line.contains("不成功") ||
                line.contains("失败") || line.contains("中止") || line.contains("错误") ||
                line.contains("异常") || line.contains("已拒绝") || line.contains("拒绝开始") ||
                line.contains("未安装") || line.contains("未运行") || line.contains("没有可") ||
                line.contains("不在列表") || line.contains("已不在") ||
                line.contains("找不到") || line.contains("未找到") ||
                // 「未在管理器 APK 里找到 ksud」——中文语序把否定词放在动词前，
                // 单查「未找到」抓不到，用「未」+「找到」双条件兜住这类语序。
                (line.contains("未") && line.contains("找到")) ||
                line.contains("rc=-1") || line.contains("FAIL=") || line.contains("MISSING") ||
                line.contains("PLANT_FAIL") || line.contains("LATE_LOAD_FAIL") -> LogLevel.ERROR
            // 未完成类：跑到了但没跑完，或用户主动关掉了某能力
            line.contains("未完成") || line.contains("警告") || line.contains("提示：") ||
                line.contains("跳过") || line.contains("未开启") || line.contains("不可用") ||
                line.contains("超时") || line.contains("未获得") || line.contains("暂不") ||
                // 「第 2 次尝试未命中，继续重试」是重试提示，不是最终失败
                line.contains("未命中") ||
                // 服务断开、被拦下的操作、需要用户先做点什么：都不是故障，但都该被看见
                line.contains("已断开") || line.contains("不能") || line.contains("请先") ||
                line.contains("正在切换") || line.contains("运行中") -> LogLevel.WARN
            // 「未完成」已被上面拦下，这里再收「完成」是安全的。
            // 「已用…补齐」「已接管」「已归一化」都是动作做成了，同样归成功。
            line.contains("成功") || line.contains("完成") || line.contains("已加载") ||
                line.contains("已用") || line.contains("补齐") || line.contains("已接管") ||
                line.contains("已归一化") || line.contains("已授予") ||
                line.contains("DONE") || line.contains("MODULE_LOADED") -> LogLevel.SUCCESS
            else -> LogLevel.INFO
        }
    }

    // ---------------------------------------------------------------- 引擎事件

    /** phase → 中文阶段名。 */
    private val phaseText = mapOf(
        "plant" to "布置利用原语",
        "load" to "加载内核模块",
        "post" to "内核收尾",
        "verify" to "校验 root",
        "summary" to "运行结果",
        "diag" to "诊断缓冲",
    )

    /** (phase, step) → 中文动作名，覆盖上游全部 ev() 调用点。 */
    private val stepText = mapOf(
        "plant.result" to "布置利用原语",
        "load.module" to "加载内核模块",
        "verify.su" to "校验 root",
        "post.manager" to "启动管理器",
        "post.ksud_install" to "安装加载器",
        "post.antiroot" to "处理安全弹窗",
        "post.screenshot" to "修复截图",
        "post.susfs" to "加载 SUSFS",
        "post.unload_guard" to "卸载内核防护",
        "post.apk_install" to "安装管理器",
        "post.dynamic" to "配置动态管理器",
        "summary.result" to "运行结果",
        "diag.dump" to "诊断缓冲已导出",
    )

    private val stepDetail = mapOf(
        "post.antiroot" to "ColorOS 安全弹窗",
        "post.screenshot" to "ColorOS 截图服务",
        "post.susfs" to "SUSFS 内核模块",
        "post.unload_guard" to "内核安全防护模块",
    )

    /**
     * 解析引擎的结构化事件行。不是事件行返回 null，调用方退回人读映射。
     *
     * 形如：`EV|v=1|phase=post|step=susfs|status=skip|scope=cap:susfs|apply=0|detail=not applicable`
     */
    fun fromEvent(line: String): LogEntry? {
        val marker = line.indexOf("EV|v=1|")
        if (marker < 0) return null
        val kv = HashMap<String, String>(8)
        line.substring(marker + 7).split('|').forEach { seg ->
            val eq = seg.indexOf('=')
            if (eq > 0) kv[seg.substring(0, eq)] = unescape(seg.substring(eq + 1))
        }
        val phase = kv["phase"] ?: return null
        val step = kv["step"].orEmpty()
        val status = kv["status"].orEmpty()
        val key = if (step.isBlank()) phase else "$phase.$step"
        val title = stepText[key] ?: phaseText[phase] ?: phase
        val ms = kv["ms"]?.toLongOrNull()
        val rawDetail = kv["detail"].orEmpty()
        val code = kv["code"]?.toIntOrNull()

        // 诊断缓冲是内部信息，压缩成一行，不展开原文
        if (phase == "diag") {
            return LogEntry(now(), LogLevel.INFO, "诊断缓冲已导出", if (rawDetail.isBlank()) "供排查使用" else rawDetail)
        }

        val level = when (status) {
            "ok" -> LogLevel.SUCCESS
            "fail" -> LogLevel.ERROR
            "warn" -> LogLevel.WARN
            else -> LogLevel.INFO
        }
        val detail = buildString {
            if (status == "skip") append("已跳过")
            if (status == "fail" && code != null) {
                if (isNotEmpty()) append(" · ")
                append("返回码 ").append(code)
            }
            if (rawDetail.isNotBlank() && rawDetail != "not applicable") {
                if (isNotEmpty()) append(" · ")
                append(rawDetail)
            }
            if (isEmpty()) append(stepDetail[key].orEmpty())
        }.trim(' ', '·')
        return LogEntry(now(), level, title, detail, ms)
    }

    private fun unescape(s: String): String = s
        .replace("%7C", "|").replace("%0A", "\n").replace("%0D", "").replace("%25", "%")

    /** 把一行引擎原始输出翻译成结构化日志；解析不出来就走人读映射。 */
    fun fromEngine(line: String): LogEntry {
        fromEvent(line)?.let { return it }
        return when {
            line.startsWith("[1/4]") -> info("布置利用原语", "第 1 步，共 4 步")
            line.startsWith("[2/4]") -> info("加载内核模块", "第 2 步，共 4 步")
            line.startsWith("[3/4]") -> info("校验 root", "第 3 步，共 4 步")
            line.startsWith("[4/4]") -> info("内核收尾", "第 4 步，共 4 步")
            line == "MODULE_LOADED" -> success("内核模块已加载")
            line.startsWith("SU_ID_RC=0") -> success("root 校验通过", line.substringAfter("=").trim())
            line.startsWith("SU_ID_RC=") -> warn("root 校验未通过", "返回码 " + line.substringAfter("=").trim())
            line.startsWith("UNIVERSAL_LOADER=") -> success("通用加载器已接管模块加载")
            line.startsWith("MANAGER_SWITCH_RC=") -> success("管理器切换完成", line.substringAfter("=").trim())
            line.startsWith("MANAGER_SWITCH_NO_APK") -> error("管理器切换失败", "找不到 APK")
            line.startsWith("MANAGER_SWITCH_NO_UID") -> error("管理器切换失败", "找不到应用数据目录")
            line == "DONE" -> success("运行完成")
            line.startsWith("KSUD_MISSING") -> error("管理器 APK 里没有 libksud.so", "改用其他管理器先越狱")
            line.startsWith("PLANT_OK") -> success("root 凭证已就绪")
            line.startsWith("PLANT_FAIL") -> error("布置利用原语失败", line.substringAfter("(").trimEnd(')'))
            line.startsWith("LATE_LOAD_FAIL") -> error("模块未生效", "一次性 late-load 未成功")
            line.startsWith("SU_WAIT_TIMEOUT") -> warn("等待 su 超时", "继续尝试管理器收尾")
            line.startsWith("DIR_MISSING") -> error("工作目录不存在", line.substringAfter(":").trim())
            line.startsWith("FAIL=") -> {
                val kind = line.substringAfter("FAIL=").substringBefore(" ").substringBefore("\t")
                error(failTitle(kind), failHint(kind))
            }
            else -> LogEntry(now(), classify(line), line.trim())
        }
    }

    private fun failTitle(kind: String): String = when (kind) {
        "plantresultmodule" -> "模块未能加载"
        "cred" -> "未拿到 root 凭证"
        "contract", "profile" -> "内核契约不符"
        "tracefs" -> "tracefs 不可用"
        "mem", "probe" -> "内存回收时机不匹配"
        else -> "执行器报错"
    }

    private fun failHint(kind: String): String = when (kind) {
        "plantresultmodule" -> "重启后重试"
        "cred" -> "确认管理器已授权本应用"
        "contract", "profile" -> "确认机型档案是否正确"
        "tracefs" -> "重启后重试"
        "mem", "probe" -> "等系统空闲后重试"
        else -> "查看诊断信息"
    }
}
