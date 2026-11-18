import hashlib, json, os, urllib.request

APK = "/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/build/outputs/apk/release/app-release.apk"
BASE = "https://api.cnb.cool/xingranya/onekeyjb/-/releases/2115388998399250432"
TOK = os.environ["CNB_TOKEN"]
R = "/Users/xingranya/Downloads/onekeyjb-private"

VERSION_CODE = 219
VERSION_NAME = "2.22.0"

size = os.path.getsize(APK)
sha = hashlib.sha256(open(APK, "rb").read()).hexdigest()
print("size", size, "sha", sha)

with urllib.request.urlopen("https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/update.json", timeout=60) as r:
    doc = json.load(r)
print("remote before:", doc.get("versionCode"), doc.get("versionName"))

notes = [
    "执行器更新到当前效果最好的一版，一加 16 与 Ace 7 Pro 均已适配",
    "失败提示更具体：能区分模块未加载、未拿到 root、内核契约不符、tracefs 占用、内存时机等",
    "设备档案新增 4 个内核版本，覆盖更多系统更新",
    "其余交互与越狱流程未改动",
]
doc["versionCode"] = VERSION_CODE
doc["versionName"] = VERSION_NAME
doc["publishedAt"] = "2026-11-18T12:00:00+08:00"
doc["size"] = size
doc["sha256"] = sha
doc["changelog"] = "v" + VERSION_NAME + "\n\n" + "\n".join("- " + n for n in notes)
vs = [v for v in doc.get("versions", []) if v.get("versionCode") != VERSION_CODE]
vs.insert(0, {"versionName": VERSION_NAME, "versionCode": VERSION_CODE,
              "publishedAt": doc["publishedAt"], "size": size, "notes": notes})
doc["versions"] = vs
open("/tmp/upd-new.json", "w", encoding="utf-8").write(json.dumps(doc, ensure_ascii=False, indent=2))
print("prepared:", doc["versionCode"], doc["versionName"], "versions", len(vs))

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
upload("update.json", "/tmp/upd-new.json")
upload("OneKey-Knowledge.md", R + "/dist/OneKey-Knowledge.md")
upload("onekey-kb.json", R + "/knowledge/ai-kb/onekey-kb.json")
upload("onekey-kb.docs.json", R + "/knowledge/ai-kb/onekey-kb.docs.json")
print("done")
