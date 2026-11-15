import re, os, glob
R="/Users/xingranya/Downloads/onekeyjb-private"
p=R+"/knowledge/ai-kb/build_kb.py"
s=open(p,encoding="utf-8").read().replace("VERSION = '2.21.0'","VERSION = '2.21.1'")
open(p,"w",encoding="utf-8").write(s)

p2=R+"/knowledge/12-版本变更史.md"
s=open(p2,encoding="utf-8").read().replace("（2.18.0 → 2.21.0）","（2.18.0 → 2.21.1）")
row=("| **2.21.1** | 210 | 修正 COUI 接入方式 | 四处必须由库接管的点：① 顶底栏移进 COUI Scaffold 的 "
     "topBar/bottomBar 槽（库为槽位提供与正文同源的 backdrop，手动传采样源会画成黑块）；"
     "② 弹窗改用库 Dialog 的 visible 参数常驻组合，不再 if(!show) return（窗口销毁异步，"
     "连续开关会被上一个未销毁窗口挡住）；③ 弹窗 dim 用 CouiDialogColors.dimAmount（浅色 0.2），"
     "系统默认 0.6 会死黑；④ 全部按钮与圆角换 COUI 原语 CouiShape/CouiCapsuleShape + "
     "couiButtonFeedback + rememberCouiSpotlight 辉光，RoundedCornerShape 归零。"
     "顶栏压成库的 52dp 单行规范 |\n")
s=re.sub(r"(\| \*\*2\.21\.0\*\* \|)", row.replace("\\","\\\\") + r"\1", s, count=1)
open(p2,"w",encoding="utf-8").write(s)
print("版本史已更新")
