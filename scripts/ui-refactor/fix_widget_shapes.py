p="/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/src/main/java/com/pyb110/onekey/ui/theme/CouiWidgets.kt"
s=open(p,encoding="utf-8").read()
s=s.replace("RoundedCornerShape(22.dp)","CouiCapsuleShape")
s=s.replace(".clip(RoundedCornerShape(3.dp)).background(trackColor)",".clip(CouiShape(3.dp, 2f)).background(trackColor)")
s=s.replace(".clip(RoundedCornerShape(3.dp)).background(color)",".clip(CouiShape(3.dp, 2f)).background(color)")
open(p,"w",encoding="utf-8").write(s)
print("剩余形状已统一")
