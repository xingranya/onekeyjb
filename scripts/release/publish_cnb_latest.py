import hashlib, json, os, urllib.request

APK = "/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/build/outputs/apk/release/app-release.apk"
BASE = "https://api.cnb.cool/xingranya/onekeyjb/-/releases/2115388998399250432"
TOK = os.environ["CNB_TOKEN"]
R = "/Users/xingranya/Downloads/onekeyjb-private"

VC, VN = 224, "2.25.0"
size = os.path.getsize(APK)
sha = hashlib.sha256(open(APK, "rb").read()).hexdigest()
print("size", size, "sha", sha)

with urllib.request.urlopen("https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/update.json", timeout=60) as r:
    doc = json.load(r)

notes = [
    "自研特权 Shell 通道：服务端以 app_process 常驻，与 App 进程完全解耦",
    "双传输：抽象 Unix socket 优先，被 SELinux 拦时自动降级到文件队列",
    "无线调试自配对：通知栏回 6 位配对码，无需 root 即可拉起通道",
    "通道状态每秒重算，首页不再出现通道已通却显示离线",
    "修复 shell 会话结束连带清掉服务端：启动脚本等到 socket 就绪才返回",
    "修复心跳文件判活的幽灵就绪：改为真实往返探测",
    "调用点零改动：StellarShell / ShizukuShell 保留原名原签名，内部路由",
]
doc["versionCode"] = VC
doc["versionName"] = VN
doc["publishedAt"] = "2026-11-28T21:20:00+08:00"
doc["size"] = size
doc["sha256"] = sha
doc["changelog"] = "v" + VN + "\n\n" + "\n".join("- " + n for n in notes)
vs = [v for v in doc.get("versions", []) if v.get("versionCode") != VC]
vs.insert(0, {"versionName": VN, "versionCode": VC, "publishedAt": doc["publishedAt"], "size": size, "notes": notes})
doc["versions"] = vs
open("/tmp/upd-2250.json", "w", encoding="utf-8").write(json.dumps(doc, ensure_ascii=False, indent=2))
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
upload("update.json", "/tmp/upd-2250.json")
upload("OneKey-Knowledge.md", R + "/dist/OneKey-Knowledge.md")
upload("onekey-kb.json", R + "/knowledge/ai-kb/onekey-kb.json")
upload("onekey-kb.docs.json", R + "/knowledge/ai-kb/onekey-kb.docs.json")
print("done")