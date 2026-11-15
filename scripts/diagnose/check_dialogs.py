import glob, re
J="/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/src/main/java/com/pyb110/onekey/ui/dialog"
for f in sorted(glob.glob(J+"/*.kt")):
    s=open(f,encoding="utf-8").read()
    has_actions = "DialogActions(" in s
    has_param = re.search(r"actions\s*=\s*listOf", s) is not None
    print(f.split("/")[-1], "| DialogActions:", has_actions, "| 已用actions参数:", has_param)
