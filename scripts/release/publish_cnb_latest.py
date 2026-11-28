import hashlib, json, os, urllib.request

APK = "/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/build/outputs/apk/release/app-release.apk"
BASE = "https://api.cnb.cool/xingranya/onekeyjb/-/releases/2115388998399250432"
TOK = os.environ["CNB_TOKEN"]
R = "/Users/xingranya/Downloads/onekeyjb-private"

VC, VN = 225, "2.25.1"
size = os.path.getsize(APK)
sha = hashlib.sha256(open(APK, "rb").read()).hexdigest()
print("size", size, "sha", sha)

with urllib.request.urlopen("https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/update.json", timeout=60) as r:
    doc = json.load(r)

notes = [
    "修复配对成功后永久连不上：私钥生成加锁 + 原子写，重连与配对互斥",
    "修复配对配额被烧光：不再对配对端口做 TCP 活性探测",
    "修复 shell 会话永久挂死：不再多读一帧，空闲超时改为 30 秒",
    "修复帧错位无感知：magic 与 crc32 校验真正生效",
    "修复 TLS 升级后多发一帧被当成二次认证",
    "修复跨块中文被碎成替换字符",
    "修复配对服务在等待输入时被系统杀掉",
    "修复 socket 泄漏与 mDNS 重试线程堆积",
]
doc["versionCode"] = VC
doc["versionName"] = VN
doc["publishedAt"] = "2026-11-28T22:40:00+08:00"
doc["size"] = size
doc["sha256"] = sha
doc["changelog"] = "v" + VN + "\n\n" + "\n".join("- " + n for n in notes)
vs = [v for v in doc.get("versions", []) if v.get("versionCode") != VC]
vs.insert(0, {"versionName": VN, "versionCode": VC, "publishedAt": doc["publishedAt"], "size": size, "notes": notes})
doc["versions"] = vs
open("/tmp/upd-2251.json", "w", encoding="utf-8").write(json.dumps(doc, ensure_ascii=False, indent=2))
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
upload("update.json", "/tmp/upd-2251.json")
upload("OneKey-Knowledge.md", R + "/dist/OneKey-Knowledge.md")
upload("onekey-kb.json", R + "/knowledge/ai-kb/onekey-kb.json")
upload("onekey-kb.docs.json", R + "/knowledge/ai-kb/onekey-kb.docs.json")
print("done")