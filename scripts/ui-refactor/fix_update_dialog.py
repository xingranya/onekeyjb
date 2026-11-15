p="/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/src/main/java/com/pyb110/onekey/ui/dialog/UpdateDialog.kt"
s=open(p,encoding="utf-8").read()
s=s.replace('''        WindowDialog(
            title = "发现新版本 · v" + ui.info.versionName + " (" + ui.info.versionCode + ")",
            show = true,
            onDismissRequest = { if (!ui.downloading) onDismiss() }
        ) {''','''        WindowDialog(
            title = "发现新版本 · v" + ui.info.versionName + " (" + ui.info.versionCode + ")",
            show = true,
            onDismissRequest = { if (!ui.downloading) onDismiss() },
            actions = listOf(
                DialogAction("稍后", COUIIcons.Close, enabled = !ui.downloading) { onDismiss() },
                DialogAction(
                    if (ui.downloading) "下载中…" else "下载并安装",
                    COUIIcons.Download,
                    primary = true,
                    enabled = !ui.downloading
                ) { onDownload() },
            ),
        ) {''')
s=s.replace('''                DialogActions(
                    DialogAction("稍后", COUIIcons.Close, enabled = !ui.downloading) { onDismiss() },
                    DialogAction(
                        if (ui.downloading) "下载中…" else "下载并安装",
                        COUIIcons.Download,
                        primary = true,
                        enabled = !ui.downloading
                    ) { onDownload() }
                )
''','')
s=s.replace("import com.pyb110.onekey.ui.components.DialogActions\n","")
open(p,"w",encoding="utf-8").write(s)
print("UpdateDialog 已改")
