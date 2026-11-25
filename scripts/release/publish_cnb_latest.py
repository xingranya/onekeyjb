import hashlib, json, os, urllib.request

APK = "/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/build/outputs/apk/release/app-release.apk"
BASE = "https://api.cnb.cool/xingranya/onekeyjb/-/releases/2115388998399250432"
TOK = os.environ["CNB_TOKEN"]
R = "/Users/xingranya/Downloads/onekeyjb-private"

VC, VN = 221, "2.23.0"
size = os.path.getsize(APK)
sha = hashlib.sha256(open(APK, "rb").read()).hexdigest()
print("size", size, "sha", sha)

with urllib.request.urlopen("https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/update.json", timeout=60) as r:
    doc = json.load(r)

notes = [
    "日志改为分级显示：信息 / 成功 / 警告 / 失败，一行一条，中文标签带色条",
    "日志新增阶段墓碑：进程被重启打断后，下次启动能指出重启发生在哪一步之后",
    "开关操作与退出不再被记成失败，历史页不再凭空产生「未完成」记录",
    "首页参数区新增成功率：内存占用、CPU 温度、系统负载三格实时采样",
    "成功率偏低时开始前弹窗提醒，并给出瓶颈与建议",
    "许可证说明补充成功率的计算方式与边界",
    "升级 COUI 到 0.0.1-alpha17，抽屉换用库原生 BottomSheet",
    "compileSdk 提到 37.2，解决新依赖的 AAR 元数据要求"
]
doc["versionCode"] = VC
doc["versionName"] = VN
doc["publishedAt"] = "2026-11-25T14:00:00+08:00"
doc["size"] = size
doc["sha256"] = sha
doc["changelog"] = "v" + VN + "\n\n" + "\n".join("- " + n for n in notes)
vs = [v for v in doc.get("versions", []) if v.get("versionCode") != VC]
vs.insert(0, {"versionName": VN, "versionCode": VC, "publishedAt": doc["publishedAt"], "size": size, "notes": notes})
doc["versions"] = vs
open("/tmp/upd-2300.json", "w", encoding="utf-8").write(json.dumps(doc, ensure_ascii=False, indent=2))
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
upload("update.json", "/tmp/upd-2300.json")
upload("OneKey-Knowledge.md", R + "/dist/OneKey-Knowledge.md")
upload("onekey-kb.json", R + "/knowledge/ai-kb/onekey-kb.json")
upload("onekey-kb.docs.json", R + "/knowledge/ai-kb/onekey-kb.docs.json")
print("done")