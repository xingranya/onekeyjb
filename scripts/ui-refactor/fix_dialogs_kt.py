p="/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/src/main/java/com/pyb110/onekey/ui/dialog/Dialogs.kt"
s=open(p,encoding="utf-8").read()

# 1) DiagnosticsDialog：去掉自绘 Button，改成 COUI 弹窗的 actions
s = s.replace('''    WindowDialog(title = "环境诊断", show = true, onDismissRequest = onDismiss) {''',
'''    WindowDialog(
        title = "环境诊断",
        show = true,
        onDismissRequest = onDismiss,
        actions = listOf(DialogAction("关闭", COUIIcons.Close, primary = true) { onDismiss() }),
    ) {''')
s = s.replace('''            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                ButtonIconText(COUIIcons.Close, "关闭")
            }
''', '''            Spacer(Modifier.height(4.dp))
''')

# 2) AppreciationDialog：DialogActions 改成 actions 参数
s = s.replace('''    WindowDialog(title = "免费声明 · 禁止倒卖", show = true, onDismissRequest = { close() }) {''',
'''    WindowDialog(
        title = "免费声明 · 禁止倒卖",
        show = true,
        onDismissRequest = { close() },
        actions = listOf(
            DialogAction("复制群号", COUIIcons.Copy) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("qq", "1121682811"))
                onMessage("群号已复制")
            },
            DialogAction("保存相册", COUIIcons.Download) { saveAppreciation(context, onMessage) },
            DialogAction("知道了", COUIIcons.Ok, primary = true) { close() },
        ),
    ) {''')
s = s.replace('''            DialogActions(
                DialogAction("复制群号", COUIIcons.Copy) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("qq", "1121682811"))
                    onMessage("群号已复制")
                },
                DialogAction("保存相册", COUIIcons.Download) {
                    saveAppreciation(context, onMessage)
                },
                DialogAction("知道了", COUIIcons.Ok, primary = true) { close() }
            )
''', '')

s = s.replace("import com.pyb110.onekey.ui.components.DialogActions\n", "")
open(p,"w",encoding="utf-8").write(s)
print("Dialogs.kt 已改")
