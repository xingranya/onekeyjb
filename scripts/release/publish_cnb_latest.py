import hashlib, json, os, urllib.request

APK = "/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/build/outputs/apk/release/app-release.apk"
BASE = "https://api.cnb.cool/xingranya/onekeyjb/-/releases/2115388998399250432"
TOK = os.environ["CNB_TOKEN"]
R = "/Users/xingranya/Downloads/onekeyjb-private"

size = os.path.getsize(APK)
sha = hashlib.sha256(open(APK, "rb").read()).hexdigest()
print("size", size, "sha", sha)

with urllib.request.urlopen("https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/update.json", timeout=60) as r:
    doc = json.load(r)

notes = [
    "修正顶栏与底栏：改为 COUI 原生栏位，模糊真正采样到页面内容，不再出现黑块",
    "修正弹窗：改用 COUI 原生弹窗，背景按 COUI 规范压暗（浅色 0.2），不再是死黑",
    "修正弹窗第二次点不出来的问题：窗口常驻，反复打开不再被上一个未销毁的窗口挡住",
    "全部按钮与圆角改用 COUI 原生形状与按压辉光，观感与系统控件一致",
    "弹窗按钮移入库的按钮面板，八个弹窗统一",
    "顶栏压成 COUI 的单行规范，标题不再溢出",
]
doc["versionCode"] = 210
doc["versionName"] = "2.21.1"
doc["publishedAt"] = "2026-11-15T14:35:00+08:00"
doc["size"] = size
doc["sha256"] = sha
doc["changelog"] = "v2.21.1\n\n" + "\n".join("- " + n for n in notes)
vs = [v for v in doc.get("versions", []) if v.get("versionCode") != 210]
vs.insert(0, {"versionName": "2.21.1", "versionCode": 210, "publishedAt": doc["publishedAt"], "size": size, "notes": notes})
doc["versions"] = vs
open("/tmp/upd-2211.json", "w", encoding="utf-8").write(json.dumps(doc, ensure_ascii=False, indent=2))
print("versions", len(vs), "head", vs[0]["versionName"], vs[0]["versionCode"])

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
upload("update.json", "/tmp/upd-2211.json")
upload("OneKey-Knowledge.md", R + "/dist/OneKey-Knowledge.md")
upload("onekey-kb.json", R + "/knowledge/ai-kb/onekey-kb.json")
upload("onekey-kb.docs.json", R + "/knowledge/ai-kb/onekey-kb.docs.json")
print("done")
