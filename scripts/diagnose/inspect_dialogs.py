p="/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/src/main/java/com/pyb110/onekey/ui/dialog/Dialogs.kt"
s=open(p,encoding="utf-8").read()
print("含 DialogActions:", "DialogActions(" in s)
print("含 Button(:", "\n            Button(" in s)
# 找 DiagnosticsDialog 里的 Button 段
import re
i=s.find("            Button(")
print(repr(s[i-200:i+400]) if i>0 else "未找到")
