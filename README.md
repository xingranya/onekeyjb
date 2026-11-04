# OneKey · OnePlus 越狱工具（私有备份）

OnePlus 16（PYB110 / 6.18.21）与 OnePlus Ace 7 Pro（PMS110 / 6.12.69）的一键越狱 App。
稳定链 CVE-2026-64560，实验链 CVE-2026-98260；内置 KernelSU 系管理器适配、云端模块与在线更新。

- 当前版本：**2.17.16（versionCode 164）**
- 产物：`artifacts/OneKeyJB-v2.17.16.apk`
- 发布源：`https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/`

## 目录

| 路径 | 说明 |
| --- | --- |
| `merged-app/` | Android 工程（Kotlin + Compose，COUI 组件库） |
| `merged-app/app/src/main/assets/core/` | 越狱载荷、各管理器 6.18/6.12 内核模块与 ksud |
| `tools/ko_stamp.py` | 内核模块 `.modinfo` 重写工具（vermagic 对齐 + onuce 契约字段） |
| `artifacts/` | 发布产物：APK、update 清单、云端模块与 catalog |
| `knowledge/` | **技术知识库**：产品矩阵、链路原理、模块协议、更新协议、排障、UI 规范，含 AI 可导入 JSON |
| `docs/` | 漏洞链研究记录（历史归档，非构建依赖） |

## 知识库

`knowledge/` 是给用户、二次开发和 AI 助手看的技术资料，入口见 [knowledge/README.md](knowledge/README.md)：

| 文件 | 内容 |
| --- | --- |
| `01-产品概览与支持矩阵.md` | 能力边界、机型/内核矩阵、功能清单 |
| `02-越狱链路原理.md` | 64560 阶段表、98260 一次性许可与后端选择 |
| `03-管理器适配.md` | 五管理器版本/证书、三档取材顺序、自适应细节 |
| `04-云端模块与校验协议.md` | catalog 结构、`onuce_*` 契约、校验清单、发模块步骤 |
| `05-更新协议.md` | `update.json` 字段、弹窗行为、下载与安装 |
| `06-构建与发布.md` | JDK 21、签名、发版流程、CNB 上传 |
| `07-故障排查.md` | 报错对照表、日志标记、诊断命令 |
| `08-UI与交互规范.md` | 页面结构、组件规格、文案基调 |

AI 导入用：`knowledge/ai-kb/onekey-kb.json`（段落式，44 段）、`knowledge/ai-kb/onekey-kb.docs.json`（整篇式，8 篇）。
改完 Markdown 跑 `python3 knowledge/ai-kb/build_kb.py` 同步。

## 五个管理器支持状态

| 管理器 | 包名 | 适配版本 | versionCode | 证书 SHA-256（前 16 位） |
| --- | --- | --- | --- | --- |
| KernelSU 官方 | `me.weishu.kernelsu` | v3.3.1 | 32680 | `c371061b19d8c7d7` |
| KernelSU-Next | `com.rifsxd.ksunext` | v3.4.2 | 33355 | `79e590113c4c4c0c` |
| SukiSU Ultra | `com.sukisu.ultra` | v4.3.0-r1 | 41140 | `947ae944f3de4ed4` |
| BakaSU | `com.resukisu.resukisu` | v4.2.0-rc3 | 35171 | `d3469712b6214462` |
| FinaSU | `me.yuki.fina` | v1.4.4 | 33167 | `2d89b50aaf1db013` |

三档取材顺序：**云端模块 → 管理器 APK 运行时自适应 → 内置模块**。
云端模块按 `manager + versionCode + vermagic` 精确匹配，vermagic 对不上会直接跳过并回落本地组件。

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # Stellar API 的 class 文件是 65.0，JDK 17 编译不过
cd merged-app && ./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名配置见 `keystore.properties`（不入库），`keystore.properties.example` 是模板。

## 发布

1. 改 `merged-app/app/build.gradle.kts` 的 `versionCode` / `versionName`。
2. `./gradlew assembleRelease`，把 APK 复制成 `artifacts/OneKeyJB-v<版本>.apk`。
3. 生成 `update.json`（`versionCode/versionName/publishedAt/size/sha256/versions[]`），
   其中 `versions[]` 每项含 `versionName/versionCode/publishedAt/size/notes[]`，供 App 内按版本展开查看。
4. 需要新云端模块时：
   ```bash
   python3 tools/ko_stamp.py IN.ko OUT.ko --release "$(uname -r 对应的设备 release)" \
     --set onuce_schema=2 --set onuce_key=<manager> --set onuce_version=<versionCode> \
     --set onuce_uapi=6 --set onuce_certificate=<管理器证书> --set onuce_kernel=<内核 commit> \
     --set onuce_su=manager-v1 --set onuce_allowlist=cred-v1
   ```
   把 `.ko` 与 `cloud-catalog.json` 传到发布源 `update` 标签下。

## 说明

- 仅用于自有设备的越狱与维护；内核态写内存存在把设备跑崩的概率。
- 仓库只保留当前源码与发布产物，历史研究与一次性脚本不再保留。
