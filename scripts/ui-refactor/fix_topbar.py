import re
p="/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/src/main/java/com/pyb110/onekey/ui/components/Chrome.kt"
s=open(p,encoding="utf-8").read()
start=s.index("    val c = oneKeyColors()\n    Column(")
end=s.index("@Composable\ninternal fun AppBottomBar")
new = '''    // COUI 顶栏是固定 52dp 单行：左头像、中标题+副标题、右操作，全部一行排布。
    val c = oneKeyColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(36)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                when (tab) {
                    0 -> "OneKey"
                    1 -> "运行日志"
                    2 -> "\\u8d8a\\u72f1\\u5386\\u53f2"
                    else -> "关于"
                },
                style = COUITheme.textStyles.headline2,
                maxLines = 1
            )
            Text(
                when (tab) {
                    0 -> "v" + BuildConfig.VERSION_NAME + " · " + (controller.profile?.displayName ?: "OnePlus")
                    1 -> controller.logs.size.toString() + " 条记录"
                    2 -> "\\u8017\\u65f6\\u4e0e\\u8bca\\u65ad\\u5f52\\u6863"
                    else -> "OneKey v" + BuildConfig.VERSION_NAME
                },
                style = COUITheme.textStyles.body2,
                fontSize = 11.sp,
                color = c.muted,
                maxLines = 1
            )
        }
        when (tab) {
            1 -> {
                TopIconButton(onLogCopy) { Icon(COUIIcons.Copy, contentDescription = "复制日志") }
                TopIconButton(onLogExport) { Icon(COUIIcons.Download, contentDescription = "导出 TXT") }
                TopIconButton(onLogClear) { Icon(COUIIcons.Delete, contentDescription = "清空日志") }
            }
            2 -> {
                TopIconButton(onHistoryRefresh) { Icon(COUIIcons.Refresh, contentDescription = "刷新记录") }
                TopIconButton(onHistoryClear) { Icon(COUIIcons.Delete, contentDescription = "清空记录") }
            }
        }
        TopIconButton(onToggleAppearance) {
            Icon(
                COUIIcons.Theme,
                contentDescription = if (isDark) "切换为浅色外观" else "切换为深色外观",
                tint = c.primary
            )
        }
    }
}

/** 顶栏操作按钮：COUI 工具栏的 40dp 圆形热区 + 库的按压反馈。 */
@Composable
private fun TopIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .couiButtonFeedback(CouiTheme.isDark, source, true)
            .clickable(source, null, onClick = onClick)
            .padding(9.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

'''
s = s[:start] + new + s[end:]
s = s.replace("import com.itos.coui.effect.CouiFloatingRenderer",
  "import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.interaction.MutableInteractionSource\nimport com.itos.coui.effect.CouiFloatingRenderer\nimport com.itos.coui.effect.couiButtonFeedback\nimport com.itos.coui.theme.CouiTheme")
open(p,"w",encoding="utf-8").write(s)
print("已重写顶栏内容")
