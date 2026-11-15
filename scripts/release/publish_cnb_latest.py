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
    "底栏恢复 COUI 样式：真实背景模糊与 metaball 融合生效，不再是普通色块",
    "弹窗内边距改为 COUI 规范的 24dp，八个弹窗统一，内容不再贴边",
    "按钮按 COUI 规范：高度 44dp、字号 14sp，蓝色主按钮与系统一致",
    "其余交互与越狱逻辑未改动",
]
doc["versionCode"] = 211
doc["versionName"] = "2.21.2"
doc["publishedAt"] = "2026-11-15T14:50:00+08:00"
doc["size"] = size
doc["sha256"] = sha
doc["changelog"] = "v2.21.2\n\n" + "\n".join("- " + n for n in notes)
vs = [v for v in doc.get("versions", []) if v.get("versionCode") != 211]
vs.insert(0, {"versionName": "2.21.2", "versionCode": 211, "publishedAt": doc["publishedAt"], "size": size, "notes": notes})
doc["versions"] = vs
open("/tmp/upd-2212.json", "w", encoding="utf-8").write(json.dumps(doc, ensure_ascii=False, indent=2))
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
upload("update.json", "/tmp/upd-2212.json")
upload("OneKey-Knowledge.md", R + "/dist/OneKey-Knowledge.md")
upload("onekey-kb.json", R + "/knowledge/ai-kb/onekey-kb.json")
upload("onekey-kb.docs.json", R + "/knowledge/ai-kb/onekey-kb.docs.json")
print("done")
