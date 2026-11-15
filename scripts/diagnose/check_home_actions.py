p="/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/src/main/java/com/pyb110/onekey/ui/screen/HomeActions.kt"
s=open(p,encoding="utf-8").read()
# TextButton(text=...) 与 Button{} 已经是 COUI 胶囊+辉光（兼容层已改），只需确认无 RoundedCornerShape 硬编码
import re
n = len(re.findall(r"RoundedCornerShape", s))
print("HomeActions RoundedCornerShape 数量:", n)
for m in re.finditer(r".*RoundedCornerShape.*", s):
    print("  ", m.group().strip()[:100])
