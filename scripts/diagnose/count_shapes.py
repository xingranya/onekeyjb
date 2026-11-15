import glob, re
J="/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/src/main/java/com/pyb110/onekey"
# 把界面层所有 RoundedCornerShape 换成 COUI 的 CouiShape，保证圆角曲率与系统一致
targets=[]
for f in glob.glob(J+"/**/*.kt", recursive=True):
    if "/ui/theme/" in f: continue
    s=open(f,encoding="utf-8").read()
    if "RoundedCornerShape(" not in s: continue
    n=len(re.findall(r"RoundedCornerShape\(\s*([0-9.]+)\.dp\s*\)", s))
    targets.append((f,n))
for f,n in targets: print(n, f.split("/onekey/")[1])
print("总计:", sum(n for _,n in targets))
