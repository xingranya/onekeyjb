import hashlib, json, os, urllib.request

APK = "/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/build/outputs/apk/release/app-release.apk"
BASE = "https://api.cnb.cool/xingranya/onekeyjb/-/releases/2115388998399250432"
TOK = os.environ["CNB_TOKEN"]
R = "/Users/xingranya/Downloads/onekeyjb-private"

VC, VN = 222, "2.23.1"
size = os.path.getsize(APK)
sha = hashlib.sha256(open(APK, "rb").read()).hexdigest()
print("size", size, "sha", sha)

with urllib.request.urlopen("https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/update.json", timeout=60) as r:
    doc = json.load(r)

notes = [
    "修复日志页点开闪退：COUI 圆角权重下限是 2，两处写了 1",
    "修复四个抽屉按下不显示：首帧就被自己的关闭回调关掉",
    "修复弹窗没有退场动画：九个弹窗改为常驻组合，隐藏后保留动画窗口",
    "内存占用 / CPU 温度 / 系统负载改走特权通道读取，温度再加 thermalservice 兜底",
    "历史页不再出现已废除的「64560 稳定链」与满屏「未完成 0.00s」",
    "弹窗统一到同一套网格：左右缩进 16dp，按钮纵向堆叠整宽，次要动作不再被染红",
    "使用手册去掉嵌套卡片，改为扁平分节排版",
]
doc["versionCode"] = VC
doc["versionName"] = VN
doc["publishedAt"] = "2026-11-25T15:20:00+08:00"
doc["size"] = size
doc["sha256"] = sha
doc["changelog"] = "v" + VN + "\n\n" + "\n".join("- " + n for n in notes)
vs = [v for v in doc.get("versions", []) if v.get("versionCode") != VC]
vs.insert(0, {"versionName": VN, "versionCode": VC, "publishedAt": doc["publishedAt"], "size": size, "notes": notes})
doc["versions"] = vs
open("/tmp/upd-2310.json", "w", encoding="utf-8").write(json.dumps(doc, ensure_ascii=False, indent=2))
print("prepared", doc["versionCode"], doc["versionName"], "versions", len(vs))

def api(url, data=None, method="POST"):
    h = {"Authorization": "Bearer " + TOK, "Accept": "application/vnd.cnb.api+json"}
    body = data
    if data is not None and not isinstance(data, (bytes, bytearray)):
        body = json.dumps(data).encode(); h["Content-Type"] = "application/vnd.cnb.api+json"
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    with urllib.request.urlopen(req, timeout=900) as r:
        return r.status, r.read()

def upload(name, path):
    st, b = api(BASE + "/asset-upload-url", {"asset_name": name, "size": os.path.getsize(path)})
    j = json.loads(b)
    req = urllib.request.Request(j["upload_url"], data=open(path, "rb").read(), method="PUT",
                                 headers={"Content-Type": "application/octet-stream"})
    with urllib.request.urlopen(req, timeout=900) as r:
        put = r.status
    st2, _ = api(j["verify_url"], b"", "POST")
    print("upload", name, "put", put, "verify", st2, flush=True)

upload("OneKeyJB-latest.apk", APK)
upload("update.json", "/tmp/upd-2310.json")
upload("OneKey-Knowledge.md", R + "/dist/OneKey-Knowledge.md")
upload("onekey-kb.json", R + "/knowledge/ai-kb/onekey-kb.json")
upload("onekey-kb.docs.json", R + "/knowledge/ai-kb/onekey-kb.docs.json")
print("done")