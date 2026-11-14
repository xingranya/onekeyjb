# OneKey 知识库（合集）

> 版本 2.19.6 / 204。不含任何密钥、口令或签名文件内容。



---

# OneKey 知识库

本目录是 OneKey 越狱工具的技术资料库，面向三类读者：装机用户、二次开发、以及接手本项目的 AI 助手。

> 更新于 2.19.3（versionCode 201）。所有结论都在文中标了来源（代码位置 / 真机日志 / 上游发布说明）。
> **本知识库不含任何密钥、口令、token 或签名文件内容。**

## 阅读顺序

| 文件 | 内容 | 适合谁 |
| --- | --- | --- |
| [01-产品概览与支持矩阵.md](01-产品概览与支持矩阵.md) | 能力边界、支持机型/内核、功能清单 | 所有人 |
| [02-越狱链路原理.md](02-越狱链路原理.md) | 两条链的原理与安全闸门 | 想搞清"点按钮后发生什么"的人 |
| [03-管理器适配.md](03-管理器适配.md) | 五家版本/证书、三处版本同步点、ksud 归一化 | 换管理器、报版本不匹配的人 |
| [04-云端模块与校验协议.md](04-云端模块与校验协议.md) | catalog 结构、`.modinfo` 契约字段、校验规则 | 要发新模块的人 |
| [05-更新协议.md](05-更新协议.md) | update.json 结构、下载与安装流程 | 发版、排查更新失败的人 |
| [06-构建与发布.md](06-构建与发布.md) | 环境、签名、完整发版流程（CNB + GitHub） | 发版的人 |
| [07-故障排查.md](07-故障排查.md) | 报错对照表与取证顺序 | 遇到问题的人 |
| [08-UI与交互规范.md](08-UI与交互规范.md) | 语义色板、组件层、抽屉规则、设计稿 | 改界面的人 |
| [09-新系统17.0.1.201适配.md](09-新系统17.0.1.201适配.md) | 新内核差异与适配点 | 换机/换系统的人 |
| [10-98260执行引擎.md](10-98260执行引擎.md) | 引擎调用协议、输出→阶段映射、成功证据 | 想懂执行链的人 |
| [11-热切换与通用模块.md](11-热切换与通用模块.md) | 通用模块前提、加载器替换、切换协议、已知行为 | 用热切换的人 |
| [12-版本变更史.md](12-版本变更史.md) | 2.18.0 → 2.19.3 逐版变更 | 想知道某版改了什么的人 |
| [13-踩坑与修复记录.md](13-踩坑与修复记录.md) | 症状 → 根因 → 修法（含"以为坏了其实正常"） | 所有人，**建议先读** |
| [14-验证清单.md](14-验证清单.md) | 发版前检查、真机回归、已知未验证项 | 发版、验收的人 |

## 三分钟速览

**这是什么**：给一加手机用的一键越狱 App。漏洞链拿到内核态能力 → 加载 KernelSU 系内核模块 → 选定的管理器接管 root。**重启即失效**。

**当前真机路径**（PYB110 / ColorOS 17.0.1.201）：
```
首页「开始运行」→ 确认对话框 → SecurityCheck → 98260 执行引擎（assets/core/exec/onekey-98260）
  → 从管理器 APK 取 libksud.so → late-load 单管理器模块 → 提权链 → su 就绪
```
`onuce` 只是 `Cve98260Engine` 内部的旧通道，当前机型**不会走到**。

**热切换怎么来**：把管理器目录下的 `libksud.so` 换成内置通用加载器 → 重启 → 再跑一次越狱 → `/proc/kernelsu_manager` 出现 → 五家之间直接切。

**发版要动三处**：`versionCode/versionName`、`update.json`（CNB）、GitHub Release。

**最常踩的三个坑**：
1. 已加载单管理器模块时通用模块会静默跳过 → 必须重启；
2. 抽屉 `show` 初始为 true 就完全不显示 → 要 false→true 驱动；
3. 组件内重复加页面边距 → 卡片宽度不一致。

## 维护约定

- 每次发版：更新 `12-版本变更史.md`；遇到新坑写进 `13-踩坑与修复记录.md`；
  验证过/没验证过的项更新到 `14-验证清单.md`。
- 改了管理器版本：同步 `03-管理器适配.md` 的表格。
- 改了界面规范：同步 `08-UI与交互规范.md`。


---

# 01 · 产品概览与支持矩阵

## 一句话

OneKey 是给一加手机用的「一键越狱」App：通过漏洞链拿到内核态能力，加载 KernelSU 系内核模块，
让选定的管理器接管 root；重启后失效，需要重新执行。

当前版本 **2.19.3（versionCode 201）**。

## 支持矩阵

| 机型 | 型号 | 内核 release | 64560 稳定链 | 98260 实验链 |
| --- | --- | --- | --- | --- |
| 一加 16 | `PYB110` / `OP64E1L1` | `6.18.21-android17-5-g7fcbffcad3e1-ab16353812-4k`（17.0.1.201）<br>`6.18.21-android17-5-gf957accaf563-ab15926176-4k`（17.0.0.x） | 支持 | 支持 |
| 一加 Ace 7 Pro | `PMS110` | `6.12.69-android16-6-g242e1a07f878-ab15865446-4k` | 不支持 | 支持 |

门槛是硬校验，任一不满足直接拒绝执行，不会「尽力尝试」：

1. `Build.MODEL` + `Build.DEVICE` 与机型表一致；
2. `System.getProperty("os.version")` 与表中 release 字符串完全相等；
3. 安装包签名证书 SHA-256 必须等于本项目签名（`SecurityCheck` 内置指纹，换签名即拒绝执行）。

## 功能清单

**越狱**
- 一键越狱：当前真机走 98260 执行引擎（见 10），64560 链作为旧内核兜底
- 五阶段进度：准备 / 提权 / 加载 / 验证 / 完成
- 失败保护：同一阶段连续失败即中止；已写入内核的失败会记 boot_id，要求重启后再试
- 环境诊断、自检、收集设备诊断（可导出 TXT）

**运行通道**
- Root 服务通道用 Stellar（Shizuku 深度分支，包名 `roro.stellar.manager`），shell uid 2000
- 运行时自适应：从已安装管理器 APK 里提取 ksud 与内核模块，不锁死版本号

**管理器**：KernelSU 官方、KernelSU-Next、SukiSU Ultra、BakaSU、FinaSU（版本与证书见 03）

**热切换**：内置通用多管理器加载器（KSU-Universal），可在五家之间直接切换，见 11

**收尾与维护**
- 停用安全组件（仅 `com.coloros.securityguard`、`com.oplus.safecenter`，不动截屏组件）
- 恢复安全组件（只恢复本会话停用过的那部分）
- 越狱后软重启修复（ColorOS 拦截原生 su 时，软重启用户态恢复 su，内核模块保持加载）
- 终端 su 兼容层（`/system_ext/bin/su`，会多一个 `su_compat` 挂载）
- 截屏扩展修复（`com.oplus.exsystemservice`）、清理旧兼容挂载

**界面**
- 四项底栏：首页 / 日志 / 历史 / 关于
- 外观：浅色 / 深色 / 跟随系统，持久保存（见 08）
- 拓展功能分组：管理器热切换 / 越狱管理器 / 管理器下载 / 拓展开关 / 环境诊断

**其它**：历史记录（含每次运行完整日志，可复制/导出）、在线更新（含分版本更新日志）

## 明确不做的事

- 不刷写任何分区，不动 bootloader；
- 不改系统 APK、不持久化内核模块（重启即失效）；
- 不内置第三方 APK 本体，只提供官方发布页链接，App 做签名与版本校验；
- 不在 App 内触发重启（只有用户在热切换面板显式点「重启」才会重启）。


---

# 02 · 越狱链路原理

## 总览

两条链都是"零特权 → 内核态能力 → 加载未签名内核模块 → 管理器接管"，
区别在于漏洞与执行方式：64560 是一次性稳定链（走自研脚本编排），98260 是每次开机只能用一次的实验链（走原生执行器）。

```
64560 稳定链（一加 16 专用）
  采集内核事件 → 校准内核地址 → 生成内核补丁 → 切换安全策略 → 定位内核凭证 → 加载内核模块 → 初始化管理器 → 完成

98260 实验链（一加 16 / Ace 7 Pro）
  boot_id 一次性许可 → 已有 root 短接 → 选后端（GhostKey / Onuce）→ 原生执行器 plant → root 校验 → finalize → 清理
```

## 64560 稳定链

入口 `StableEngine.run()`，整条流程在一个可控的 shell 会话里逐阶段推进，任一阶段失败立即停，不会"带病继续"。

**执行前闸门**
1. Stellar 服务在线且已授权（没有就提示去启动，不会静默降级）；
2. `uname -r` 读得到，机型/内核在支持矩阵内；
3. `getenforce` 与 `/proc/modules` 可读，SELinux 不能处于 `Disabled`；
4. 若已有 `kernelsu` 模块加载，先用 `ksud debug info` 识别它是哪个管理器，识别不出就停，避免"跨管理器修复"把内核搞脏；
5. 运行锁：`filesDir/run.lock` + 进程内 `RunState`，防重入。

**阶段与日志标记**

| 脚本标记 | 界面阶段名 | 说明 |
| --- | --- | --- |
| `@STAGE=cap` | 采集内核事件 | `trace` 抓 300 条事件，先开 tracing 再读，读完再关，避免空采样 |
| `@STAGE=solve` | 校准内核地址 | 由事件推导 slide 与目标地址 |
| `@STAGE=patch` | 生成内核补丁 | 按当前 slide 重打模块（`SHN_ABS` 符号化 + cred/noenforce 补丁） |
| `@STAGE=permissive` | 切换安全策略 | 置 permissive（保留 initialized） |
| `@STAGE=inject` | 定位内核凭证 | 定位候选进程 cred 并两阶段写 |
| `@STAGE=load` | 加载内核模块 | 由已提权进程 `finit_module` 加载未签名 LKM |
| `@STAGE=init` | 初始化管理器 | `ksud install`、登记管理器、开特性 |
| `@STAGE=done` | 越狱完成 | 收尾校验 |

配合 `@TICK=<阶段> <计数>` 做心跳：界面据此更新"正在校准内核地址（最多约 2 分钟）"这类文案，并推进进度条。
`@WARN=` 是黄色提示，`@EXIT=` 是真实退出码。

**收尾（post.sh）**
- 管理器登记：`ksud` 侧 `set-apk` / `set-uid` / `set-hash` / `grant` 四步，FinaSU 优先走 `set-apk`（签名路径），失败再退回 `set-uid`；
- 可选 `su_compat`：为没有 `/system/bin/su` 的场景补 `/system_ext/bin/su` 挂载；
- 可选软重启：ColorOS 安全机制拦截原生 su 时，软重启用户态让 su 生效，内核模块不卸载；
- 截屏组件 `com.oplus.exsystemservice` 保持可用，不参与停用。

## 98260 实验链

入口 `Cve98260Engine.run()`。这条链**一次开机只能尝试一次**，用 `/proc/sys/kernel/random/boot_id` 记账，
失败必须硬重启，否则内核状态不可信。

**流程**
1. 读 `boot_id`，与偏好里记录的一致就拒绝："本次开机已经尝试过"；
2. 先用 `/system/bin/su -c id` / `su -c id` 探测是否已有可用 root，有就直接返回成功，避免重复 plant；
3. 取管理器组件：优先运行时自适应（`RuntimeExtractor.prepare`），失败则用内置模块；
4. 选后端：
   - **Ace 7 Pro（6.12）**：先试 GhostKey 原生后端，返回 `null` 才回落 Onuce；
   - **一加 16（6.18）**：**不会**自动回落 GhostKey——6.18 的 profile 未经真机验证，slide/task 偏移错了会直接 panic；
5. 写一次性许可（`markBootAttempt`）后启动执行器；
6. 执行器（Onuce 1.11）在独立工作目录里跑：`/data/local/tmp/onuce/<uuid>-2000-<backend>`，目录 0700，
   配置文件是 `manager.conf`（6.18 用 `onuce-runtime-v2`，Ace 用 `onuce-runtime-manager-v1`）；
7. 退出码 `40` 或输出含"系统繁忙/runnable" → 清理残留进程，等 15 秒重试一次，仍繁忙就安全停止；
8. root 校验通过后调 `finalize`（best-effort，身份不符会被执行器拒绝，不影响结论），再 `pkill -9 -f 'onuce --plant'` 清残留；
9. 退出码 `124`（超时）但 root 已拿到 → 判成功并提示"执行器超时，但 root 已获取；残留进程已清理，请勿重复越狱"。

## 安全闸门与失败语义

- `SystemReadinessGate`：执行器启动前等 `sys.boot_completed=1`、屏幕 Awake、`procs_running` 连续 3 次低于阈值，最多等 75 秒；
  开机自启/高负载时直接跑，panic 概率明显更高。
- 运行锁：串行化，避免两个任务同时改内核内存。
- 失败不"重试到底"：64560 失败就停并保留日志；98260 失败要求硬重启，App 不提供自动重试。
- 所有结论都来自实测输出（`su -c id` 里出现 `uid=0`），不看"应该成功了"。


---

# 03 · 管理器适配

## 支持的五家（截至 2.19.3）

| 管理器 | 包名 | 版本 | versionCode | 证书 SHA-256（前 12 位） |
| --- | --- | --- | --- | --- |
| KernelSU 官方 | `me.weishu.kernelsu` | v3.3.1 | 32680 | `c371061b19d8` |
| KernelSU-Next | `com.rifsxd.ksunext` | v3.4.2 | 33355 | `79e590113c4c` |
| SukiSU Ultra | `com.sukisu.ultra` | v4.3.0-r2 | 41150（CI 构建为 41158） | `947ae944f3de` |
| BakaSU | `com.resukisu.resukisu` | v4.2.0-rc3 | 35171 | `d3469712b621` |
| FinaSU | `me.yuki.fina` | v1.5.0 | 33174 | `2d89b50aaf1d` |

完整 64 位指纹在 `JailbreakController.expectedCert`，**换签名 = 拒绝执行**（越狱与热切换都校验）。

## 三处版本要一起改

改管理器版本时，以下三处必须同步，否则会出现"下载页是新版、App 里却提示版本不匹配"：

| 位置 | 作用 |
| --- | --- |
| `ManagerDownloads.all` | 「管理器下载」列表展示 + 官方 APK 直链 |
| `JailbreakController` 的 `Triple(...) to <versionCode>` | 期望版本号（非自适应模式下的判定） |
| `MainActivity.managerHint()` | 首页/诊断里的版本说明文案 |

证书只有在**上游换签名**时才需要动 `expectedCert`。截至 2.19.3，五家签名都未变。

## 下载清单口径

固定取**官方最新正式版**（stable），不跟 prerelease / CI 构建：

- SukiSU 的 `manager-ci` 标签（如 41158）属于预发布，不入表；
- BakaSU 仓库只有 prerelease，按其最新 rc 取；
- 版本号以 APK 的 `versionCode` 为准（`aapt2 dump badging` 读出来），不猜 tag。

## ksud 归一化（2.19.3 新增）

98260 执行引擎按「管理器 APK 目录下 `lib/arm64/libksud.so`」定位加载器。
SukiSU 从 r1 起把 ksud 改名成随机库（r1 `libfdbqiupsrx.so`、r2 `libtqqfthevwb.so`、CI 41158 `libdtlarbbaoa.so`），
该文件不存在时引擎直接报 `KSUD_MISSING`。

`KsudNormalizer` 的做法：

```sh
D=$(dirname $(pm path <pkg>))              # 管理器 APK 目录
[ -f "$D/lib/arm64/libksud.so" ] && echo EXISTS && exit 0
for f in "$D"/lib/arm64/*.so; do
  cp "$f" /data/local/tmp/onekey-ksud-probe && chmod 755
  OUT=$(timeout 8 probe -V 2>&1)
  case "$OUT" in *ksud*uapi:*)            # 只有真 ksud 会输出 ksud … (uapi: 6)
     cp "$f" "$D/lib/arm64/libksud.so" && chmod 755
     echo "MATERIALIZED:$(basename $f)"; exit 0;;
  esac
done
echo MISSING
```

调用时机：
1. 越狱前（已有 root 时）先归一化；
2. 安装通用加载器前也先归一化，避免备份/替换落到不存在的路径。

**边界**：归一化要写 `/data/app`，只有 root 能做。首次越狱（尚无 root）用 SukiSU 仍会失败——
那时无法读取或执行该库。合理路径是先用其他四家之一完成首次越狱，之后切到 SukiSU 会自动补齐。

## 运行时取材顺序

| 场景 | 取哪份 ksud | 取哪份内核模块 |
| --- | --- | --- |
| 98260 引擎（当前真机） | 引擎自己从管理器 APK 找 `libksud.so`（必要时先归一化） | 管理器自带 LKM（单管理器） |
| 热切换加载器 | 内置 `assets/core/exec/ksud-universal` | 通用模块（内嵌 6.18 KMI） |
| 旧链 / onuce | `RuntimeExtractor` 从 APK 提取（含"取最大库"回退） | 云端 catalog 或 APK 内嵌 |


---

# 04 · 云端模块与校验协议

云端模块的意义：把"用哪个内核模块"从客户端挪到发布侧，客户端只认签名、版本与内核指纹，
不发版也能补新管理器版本。

## 发布源

```
https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/cloud-catalog.json
https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/<manager>-<version>-<sha16>.ko
```

客户端只允许 `cnb.cool`、`asset.cnb.cool`、`github.com`、`release-assets.githubusercontent.com`、
`objects.githubusercontent.com` 这几家主机，重定向后再校验一次主机名，非 https 直接拒绝。

## catalog 结构

```json
{
  "schema": 1,
  "generated_at": "2026-11-04",
  "modules": [
    {
      "manager": "finasu",
      "version": 33167,
      "name": "finasu-33167-c39431f6998806b8.ko",
      "size": 549260,
      "sha256": "6b7a9da8...",
      "vermagic": "6.18.21-android17-5-gf957accaf563-ab15926176-4k",
      "url": "https://cnb.cool/.../finasu-33167-c39431f6998806b8.ko"
    }
  ]
}
```

字段约束（客户端会丢弃不合规条目）：

| 字段 | 约束 |
| --- | --- |
| `manager` | 仅小写字母，`^[a-z]+$` |
| `version` | 正整数 |
| `name` | `^[A-Za-z0-9_.-]{1,160}$` |
| `size` | `0 < size <= 8 MiB` |
| `sha256` | 64 位小写十六进制 |
| `url` | https + 白名单主机 |
| `vermagic` | 可选；有值时必须是合法 release 字符串，且与设备 `uname -r` 完全相等才使用 |

`vermagic` 的作用：同一管理器版本可能对不同内核各发一份模块。匹配规则是
"先找 `vermagic` 完全相等的条目，再找不带 `vermagic` 的条目"，都不中就当云端没有这个模块，
**直接回落本地组件**，不会下载完再报错。

文件名约定：`<manager>-<versionCode>-<sha256(release) 前 16 位>.ko`。

## `.modinfo` 契约字段

模块必须是 6.18/6.12 的 `.ko`，并在 `.modinfo` 里带这些键（NUL 分隔，不是换行）：

| 键 | 期望值 | 说明 |
| --- | --- | --- |
| `vermagic` | `<设备 release> SMP preempt mod_unload modversions aarch64` | 必须以设备 release 开头 |
| `onuce_schema` | `2` | 协议版本 |
| `onuce_key` | manager 名 | 与 catalog 的 `manager` 一致 |
| `onuce_version` | versionCode | 与 catalog 的 `version` 一致 |
| `onuce_uapi` | `6` | 管理器 UAPI |
| `onuce_certificate` | 管理器证书 SHA-256 | 与 APK 实际签名一致 |
| `onuce_certificate_size` | 证书 DER 字节数 | 827 / 998 / 887 / 860 / 716 |
| `onuce_kernel` | 内核 commit 前缀 | 必须等于 release 里 `-g` 与 `-ab` 之间的 commit |
| `onuce_package` | 管理器包名 | |
| `onuce_repo` | 上游仓库 | |
| `onuce_su` | `manager-v1` | |
| `onuce_allowlist` | `cred-v1` | |
| `onuce_vermagic_rewritten` | `0`/`1` | 1 表示由上游 GKI 模块重写 vermagic 得到，0 表示原生按设备内核构建 |

禁止出现 `onuce_terminal=` 与 `onuce_app_su=`（出现即判为不合规模块）。

## 客户端校验清单

下载前：主机白名单 → 声明体积不超限 → `size` 精确相等 → `sha256` 相等。
下载后逐项验证，任一项失败即 `云端模块校验失败，已拒绝` 并回落：

1. 体积、SHA-256；
2. ELF64 小端（`0x7f ELF` + class=2 + data=1）；
3. `onuce_schema=2`、`onuce_key`、`onuce_version`、`onuce_uapi=6`；
4. `onuce_certificate` 与设备上管理器实际签名一致；
5. `vermagic` == 设备 release（或以其 + 空格开头）；
6. `onuce_kernel` 以 release 里的 commit 开头；
7. `onuce_su=manager-v1`、`onuce_allowlist=cred-v1`、无 `onuce_terminal=`/`onuce_app_su=`。

通过后写入 `filesDir/cloud/modules/`，同名同内容直接复用缓存（`.part` 写完再 rename）。

## 生成模块

```bash
python3 tools/ko_stamp.py IN.ko OUT.ko \
  --release 6.18.21-android17-5-gf957accaf563-ab15926176-4k \
  --set onuce_schema=2 \
  --set onuce_key=sukisu \
  --set onuce_version=41140 \
  --set onuce_uapi=6 \
  --set onuce_certificate=947ae944f3de4ed4c21a7e4f7953ecf351bfa2b36239da37a34111ad29993eef \
  --set onuce_certificate_size=860 \
  --set onuce_source=<上游 commit> \
  --set onuce_package=com.sukisu.ultra \
  --set onuce_repo=SukiSU-Ultra/SukiSU-Ultra \
  --set onuce_kernel=f957accaf5634f841071adb4f35286ef2779d5b1 \
  --set onuce_abi=100 \
  --set onuce_vermagic_rewritten=1 \
  --set onuce_su=manager-v1 \
  --set onuce_allowlist=cred-v1
```

### 两个容易踩的坑

1. **旧 `.modinfo` 必须清零**：`ko_stamp.py` 把新 `.modinfo` 追加到文件末尾并改 section 头，
   如果不清掉旧的字节，文件里会同时出现两条 `vermagic=`，按"首个匹配"解析的校验方会读到旧值（6.18.32），
   于是体积/SHA 都对、vermagic 却永远不匹配。脚本里已内置清零。
2. **NUL 分隔**：`.ko` 的 `.modinfo` 用 `\0` 分隔条目，不是换行。校验前必须先把 `\0` 换成 `\n` 再做行解析，
   否则所有键都"找不到"。

## 发新模块的步骤

1. 取对应管理器最新 release 的 6.18 LKM（或原生按设备内核构建的产物）；
2. `ko_stamp.py` 打 `onuce_*` 与 vermagic；
3. 用与客户端同规则的校验脚本自查（体积/SHA/ELF/全部 `onuce_*`/唯一 `vermagic`）；
4. 上传 `.ko` 与新的 `cloud-catalog.json`；
5. 下载回来复核 `sha256`，确认 CDN 没有缓存旧文件。


---

# 05 · 更新协议

## 清单地址

```
https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/update.json
https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/OneKeyJB-latest.apk
```

CNB 的 `update` 是固定标签，每次发版覆盖同名资产，所以客户端地址永远不变。

## update.json

```json
{
  "versionCode": 164,
  "versionName": "2.17.16",
  "publishedAt": "2026-11-04T08:35:00+08:00",
  "size": 42839435,
  "sha256": "3f087b7d...c58cf1",
  "apkUrl": "https://cnb.cool/.../OneKeyJB-latest.apk",
  "changelog": "v2.17.16\n\n- …",
  "versions": [
    {
      "versionName": "2.17.16",
      "versionCode": 164,
      "publishedAt": "2026-11-04T08:35:00+08:00",
      "size": 42839435,
      "notes": ["五个管理器全部对齐当前最新版…", "内置 6.18 模块全部换成…"]
    }
  ]
}
```

| 字段 | 必填 | 用途 |
| --- | --- | --- |
| `versionCode` | 是 | 与 `BuildConfig.VERSION_CODE` 比较，`<= 当前` 视为已最新 |
| `versionName` | 是 | 弹窗标题里的版本名 |
| `publishedAt` | 是 | ISO-8601 带时区，弹窗显示"更新时间"，按本地时区格式化 |
| `size` | 是 | 安装包字节数，界面按两位小数渲染（`40.85 MB`） |
| `sha256` | 是 | 下载完成后的完整性校验，不匹配即删包报错 |
| `apkUrl` | 否 | 缺省用 `OneKeyJB-latest.apk` |
| `changelog` | 否 | 纯文本兜底，`versions` 缺失时直接展示 |
| `versions[]` | 否 | 分版本更新日志，客户端按 `versionCode` 倒序展示 |

`versions[]` 每项含 `versionName`、`versionCode`、`publishedAt`、`size`、`notes[]`。
`notes` 缺失时会从该条的 `changelog` 文本按行拆出来（去掉行首 `-`），所以两种写法都能被吃下。

## 界面行为

打开 App 时后台静默检查一次，有新版就弹窗：

```
发现新版本 · v2.17.16 (164)
更新时间 2026-11-04 08:35 · 安装包 40.85 MB
┌ v2.17.16 · 164                     40.85 MB ┐
│ 更新时间 2026-11-04 08:35                   │
│   · 五个管理器全部对齐当前最新版…           │
└────────────────────────────────────────────┘
┌ v2.17.15 · 163                     40.82 MB ┐
└────────────────────────────────────────────┘
[稍后]                    [下载并安装]
```

- 版本卡片可点开/收起，默认展开最新版；
- 下载中显示进度条 + `下载中 43%` + `2.41 MB/s · 剩余 23.15 MB`；
- 速度每 ≥250 ms 采一次，做指数平滑（`(3*旧 + 新)/4`），避免数字乱跳；
- 下载期间"稍后"与"下载并安装"都禁用，避免重复下载。

## 下载与安装

1. `HttpURLConnection`，连接 15 s / 读取 120 s，跟随重定向；
2. 总量优先取 `Content-Length`，没有就用 `update.json` 的 `size`；
3. 写入 `getExternalFilesDir(DOWNLOADS)/OneKeyJB-update.apk`；
4. 全量 SHA-256 比对，不一致删除文件并抛"APK SHA-256 校验失败"；
5. 未授予"安装未知应用"权限时，先跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`；
6. 通过项目 `FileProvider`（`<applicationId>.fileprovider`）拉起系统安装器。

## 发版检查清单

- [ ] `versionCode` 比线上大，`versionName` 与 commit 描述一致
- [ ] APK 用项目密钥签名（证书 `a023f95c…be487e`），否则装上也会被 `SecurityCheck` 拒绝执行
- [ ] `size` 与 `sha256` 取自实际产物（`shasum -a 256`）
- [ ] `versions[]` 补上本次条目，`notes` 写"改了什么/影响谁"
- [ ] 上传顺序：先 `update.json` 再 APK，或同时上传后立刻复核远端 `sha256`
- [ ] 远端下载回来跑一次 `aapt2 dump badging`，确认 versionCode 正确


---

# 06 · 构建与发布

## 环境

| 项 | 要求 | 说明 |
| --- | --- | --- |
| JDK | **21**（`/opt/homebrew/opt/openjdk@21`） | Stellar API 的 class 文件是 65.0，JDK 17 会报 "类文件具有错误的版本 65.0" |
| Android SDK | compileSdk 37 | build-tools 用 SDK 自带即可 |
| Gradle | wrapper 自带 | 不需要本机安装 |
| minSdk / targetSdk | 33 / 37 | |
| applicationId | `dev.onekey.jb` | 与旧版一致，便于覆盖安装 |

## 签名（不入库）

`merged-app/keystore.properties` 与 keystore 文件**都不进仓库**（`.gitignore` 已排除）。
没有它们时 `assembleRelease` 会产出 `app-release-unsigned.apk`；放进去才产出可安装的 `app-release.apk`。

> 本知识库与仓库任何位置都不保存口令、token、keystore 内容。

## 本地构建

```bash
cd merged-app
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleRelease --console=plain
ls -l app/build/outputs/apk/release/
```

## 版本号

改 `merged-app/app/build.gradle.kts`：

```kotlin
versionCode = 201        // 单调递增，必须比线上大
versionName = "2.19.3"   // 与 Release tag 一致
```

## 发布流程（一次发版的完整动作）

1. **改版本号** → `assembleRelease` → 记下 `size` 与 `shasum -a 256`；
2. **生成 update.json**：在上一版基础上追加 `versions[]` 条目，顶层字段指向本次；
3. **上传 CNB**（应用内更新用，地址固定）：
   - `update.json` → `https://cnb.cool/xingranya/onekeyjb/-/releases/download/update/update.json`
   - APK → 同目录 `OneKeyJB-latest.apk`（覆盖同名资产）
   - 走 CNB 的 release asset-upload-url → PUT → verify 三步，用环境里的 token，**token 不写进任何文件**；
4. **核对远端**：`curl` 拉回 `update.json`，确认 `versionCode` / `size` / `sha256` 是本次的；
5. **GitHub Release**：`gh release create v<版本> --title ... --notes-file ... <APK> <update.json>`；
6. **提交源码**：`git add -A && git commit && git push`；
7. **真机验证**（能连上设备时）→ 见 14 验证清单。

## 构建产物位置

```
merged-app/app/build/outputs/apk/release/app-release.apk           # 已签名
merged-app/app/build/outputs/apk/release/app-release-unsigned.apk  # 无 keystore 时
```

## 发布检查清单

- [ ] `versionCode` 比线上大，`versionName` 与 tag 一致
- [ ] APK 用项目密钥签名（否则装上也被 `SecurityCheck` 拒绝）
- [ ] `size` / `sha256` 取自实际产物
- [ ] `update.json` 的 `versions[]` 补上本次条目
- [ ] 远端 `update.json` 复核通过
- [ ] GitHub Release 两个资产都在（APK + update.json）
- [ ] 源码已 push（含知识库更新）


---

# 07 · 故障排查

## 先看这三样

1. **首页状态**："Root 服务通道"是否显示已连接并授权；
2. **日志页**最后 20 行，特别是 `@WARN=` / `执行退出码` / `root 检查`；
3. **历史 → 详情**（含当次完整日志，可复制/导出）。

## 报错对照表

| 日志/现象 | 原因 | 处理 |
| --- | --- | --- |
| `Stellar 未运行` | 没有启动服务 | 打开 Stellar，用「无线调试」启动服务后回到 App 点授权 |
| `未获得 Stellar 权限` | 授权弹窗被拒/超时 | 重新点授权，弹窗点允许 |
| `已安装 … 签名不匹配，已拒绝` | 管理器不是官方签名（改包/第三方重签） | 装官方原版管理器 |
| `版本不匹配：需要 X，当前 Y` | 关了自适应且版本不同 | 打开「运行时自适应管理器」，或装到对应版本 |
| `当前已加载内核与所选管理器不一致` | 模块没卸载就换管理器 | 完整重启手机后再切换 |
| `无法识别当前已加载的内核` | `ksud debug info` 输出异常 | 完整重启后重试，仍失败导出诊断日志 |
| `执行退出码: 2`（98260） | 配置模式/模块不匹配 | 确认不是把 6.12 模块塞给 6.18；云模块关掉再试一次 |
| `云端模块校验失败，已拒绝` | 模块被改过、vermagic 不再匹配、或 `.modinfo` 有两条 vermagic | 见 04 的"两个容易踩的坑"；也可以直接关掉"自动匹配云端组件"用本地模块 |
| `FinaSU 自适应失败…finasu.ko ELF 结构无效` | 内置模块被外部脚本预patch 坏了 | 恢复未预patch 的上游 LKM，让 `RuntimeExtractor` 运行时对齐 |
| `系统繁忙` / 退出码 40 | 设备正在高负载或刚开机 | 等 15 秒自动重试一次；仍繁忙就关后台应用再试 |
| `98260 本次开机已经尝试过` | 一次性许可已用 | 硬重启手机（不是软重启） |
| `su 通道未就绪` | 模块加载了但用户态没接上 | 点「修复」或「权限修复」；仍不行看下一条 |
| 原生 su 被 ColorOS 拦截 | 安全机制拦截 | 打开「越狱后软重启修复」，成功后约 10 秒软重启用户态 |
| 应用拿不到 root，shell 能拿到 | 部分 ROM 对 `su` 入口有策略差异 | 用「root 诊断」逐条试；必要时开「终端 su 兼容层」 |
| 截屏异常/无法截图 | `com.oplus.exsystemservice` 被停用 | 诊断 → 「修复截屏扩展」 |

## 日志标记

| 标记 | 含义 |
| --- | --- |
| `== 阶段名 ==` | 进入某个步骤 |
| `@STAGE=<key>` | 脚本阶段切换（cap/solve/patch/permissive/inject/load/init/done） |
| `@TICK=<阶段> <计数>` | 心跳，界面据此更新文案与进度 |
| `@WARN=…` | 黄色警告，不致命 |
| `@EXIT=<code>` | 真实退出码 |
| `执行退出码: N` | 98260 执行器退出码（124=超时，40=忙） |
| `root 检查: uid=0(root)` | 判定成功的唯一依据 |

## 一键诊断脚本

App 内「导出设备诊断」会跑一段等价命令，也可以手动执行：

```bash
uname -r; getenforce
grep -i kernelsu /proc/modules
ls -l /system/bin/su /system_ext/bin/su
/data/adb/ksud debug info | head -20
/data/adb/ksud feature list
grep -iE 'onekey|ksu' /proc/mounts
dmesg | grep -i kernelsu | tail -40
```

判读要点：
- `/proc/modules` 里有 `kernelsu` 但 `su` 不通 → 用户态问题，点修复；
- 有 `onekey-su`/`su_compat` 挂载但原生 su 能用 → 兼容层多余，点「清理旧兼容挂载」；
- `dmesg` 里出现 `module verification failed` → 模块与新内核不匹配，换模块或改用云端/自适应。

## 什么时候必须硬重启

- 98260 失败后（无论什么原因）；
- 出现 `内核可能处于脏状态`；
- 越狱过程中设备卡死、自动重启后仍要再越狱。

64560 失败通常可以原地重试，但同一 boot 内连续失败 3 次以上建议先重启再试。


---

# 08 · 界面与设计系统

技术栈：Jetpack Compose + COUI（`io.github.suqi8.coui.kmp`）+ `coui-blur`。
2.19.0 起按已确认的网页原型重做，色板不再依赖 Monet 动态取色。

## 语义色板（唯一取色入口）

`ui/OneKeyTheme.kt` 定义 `OneKeyColors`，浅/深各一套，页面通过 `oneKeyColors()` 取用，
不再各页硬编码颜色。

| 语义 | 浅色 | 深色 |
| --- | --- | --- |
| background | `#F3F2F5` | `#121115` |
| surface（卡片） | `#FFFFFF` | `#1D1B21` |
| ink（正文） | `#27232B` | `#F1EAF2` |
| muted（次要） | `#6B626E` | `#B4A9BA` |
| primary | `#9B4A5A` | `#E4ADBD` |
| primarySoft | `#F3E4E9` | `#382631` |
| deviceSurface | `#F8EEF2` | `#28232E` |
| success / warning / error | `#287150` / `#835C19` / `#A3323E` | `#9BD9B7` / `#EDC783` / `#F0A6B0` |

另有 `oneKeyCardColor()`（= COUI `surfaceContainer`，保证自绘卡片与 `ListCard` 同色）、
`oneKeyDeviceColor()`（在卡片色上叠主色）、`oneKeyCardOutline()`。

## 外观

`AppearanceMode`：`LIGHT` / `DARK` / `SYSTEM`，持久化在 `onekey.appearance_mode`。
顶栏右侧圆形按钮快速切换；`关于 → 个性化 → 外观` 提供完整设置（含两档配色预览）。
`resolveDark()` 决定实际深浅，并传给 `ProvideOneKeyColors(dark)` 与 `ThemeController(isDark)`。

## 尺寸与组件

| 项 | 值 |
| --- | --- |
| 页面左右边距 | 12dp（由 LazyColumn `contentPadding` 统一给） |
| 卡片圆角 | 20dp |
| 按钮圆角 | 14dp |
| 行高 | 由 `BasicComponent` 决定（约 56dp） |
| 分隔线 | 1dp，左内缩 60dp（有图标列）/ 右内缩 16dp |

组件层：`ui/components/ListCard.kt`

```kotlin
ListCard(rows = listOf(
  ListRow(title, summary, icon, arrow = true) { onClick },          // 普通行
  ListRow(title, icon, end = { Switch(...) }),                       // 开关行
  ListRow(title, summary, leading = { ManagerMark(pkg, label) })     // 真实应用图标
))
SectionTitle("分组名")
```

`ListRow` 字段：`title / summary / icon / end / arrow / enabled / onClick / iconTint / leading`。

## 页面结构

```
Scaffold(containerColor = oneKeyColors().background)
├─ 顶栏 BlurBar（模糊 + scrim + 文字三层；头像 + 标题 + 右侧外观按钮）
├─ 内容 LazyColumn（contentPadding = 12dp 左右 + 顶栏/底栏高度）
└─ 底栏 BlurBar（首页 / 日志 / 历史 / 关于）
```

首页顺序：设备卡（四列状态）→ 运行卡 → 「拓展功能」分组（5 行）→ 「更多」分组 → 底栏。

## 抽屉

统一用 `WindowBottomSheet`（COUI 的 window 版底部抽屉，自带下滑关闭与入场动画）：

- 抽屉**只有一个实例**，`show` 由根级 `sheetVisible` 驱动（初始 false，进入时置 true，否则不显示）；
- 每个面板给**固定高度**，避免内容变化触发高度动画（观感像"弹一下"）；
- 关闭：`onDismissRequest` 先置 `visible=false` 播退场，`onDismissFinished` 再清 `sheet`。

## 图标

- 一般图标：`COUIIcons.*`（`coui-icons` 的 `extended` 包，形状是细线风格）；
- 管理器图标：`PackageManager.getApplicationIcon(pkg)` 转 `ImageBitmap`，读不到时退回字母标记；
- 不要用"图标字体名称当文字"，也不要在界面里混英文词。

## 设计稿

`docs/design/v2-*.png` 与同名 `.html`：home / hotswitch / features / logs / history / about / manual。
设计稿是示意图，含虚构项（Magisk/APatch、英文日志、示例设备），**实现以真实功能为准**。

## 注意事项

1. 模糊沿用 `coui-blur`，不引入 Haze（Haze 是为有页面栈转场的场景准备的，本项目没有）；
2. 顶栏三层结构不能合并，否则会出现文字被糊或高度塌陷；
3. 底栏四项固定，不要改成悬浮胶囊/五项。


---

# 09 · ColorOS 17.0.1.201 适配说明

## 一、这次换的是什么

| 项 | 17.0.0.x（旧） | 17.0.1.201（新） |
| --- | --- | --- |
| 内核 release | `6.18.21-android17-5-gf957accaf563-ab15926176-4k` | `6.18.21-android17-5-g7fcbffcad3e1-ab16353812-4k` |
| 内核 commit | `f957accaf563` | `7fcbffcad3e1` |
| 内核基址 | `ffffffc080000000` | 同 |
| kallsyms 条数 | 137,991 | 143,616（`vmlinux-to-elf` 导出 143,616 行符号表） |
| 同名符号 | — | 137,848 条；地址相同仅 169 条（0.12%） |

也就是说：**版本号没变（还是 6.18.21），但是一次重新构建**。所有 pin 构建串的东西都要跟着换。

## 二、哪些能在电脑上适配完

1. **机型档案**：`DeviceProfiles.PYB110_201` 匹配新 release，旧档案保留，两台都能用。
2. **符号表**：从 boot.img 的 kernel 镜像里重导 kallsyms（`vmlinux-to-elf` + `analysis/tools/dump_syms.py`），
   产出 `core/symbols-1701201.txt`；64560 的 `resolve.bin` / `patch.bin` 直接读新表即可。
3. **模块 vermagic**：内置模块不再固定内核串，投放前用 `RuntimeExtractor.alignVermagic()` 现场对齐到 `uname -r`，
   以后再换 OTA 不需要重发五个管理器的模块。
4. **云端模块**：catalog 支持 `vermagic` 精确匹配；新内核上若没有对应条目，会干净跳过并回落到内置模块（不报错）。

## 三、哪些必须真机

| 项 | 原因 |
| --- | --- |
| 64560 触发与收尾 | trace 采样、slide 校准、cred 定位、LKM 加载都要在真内核上跑，符号表只是"能算对地址"的前提 |
| 98260 一次性 plant | 原生执行器内部偏移按旧构建标定，新构建属首次尝试；一次开机只能一次 |
| ColorOS 安全策略 | 17.0.1.201 是否新增 su 拦截/LKM 校验，只能实测 |
| 模块 CRC 兼容 | `modversions` 下 `module_layout` 版本号是否一致，需 load 时才知道 |

## 四、真机试的步骤

1. 系统更新完成后确认 `uname -r` 与上表新串一致（不一致说明还有小版本差异，需要再导一次符号表）；
2. 先试 **64560 稳定链**（失败可原地重试，不会要求硬重启）；
3. 若稳定链卡在 `加载内核模块`，导出诊断日志，重点看 `dmesg | grep -i module` 的 CRC 报错；
4. 稳定链通了再试 **98260**（一次开机一次，失败必须硬重启）；
5. 两条都失败时保留 `/data/local/tmp/onekey-console.log` 与 App 内导出的日志。

## 五、重新导符号表（换任何 OTA 都这么做）

```bash
cd analysis && . .venv/bin/activate
python3 - <<'PY'
import struct
p="/path/to/boot.img"; d=open(p,'rb').read()
ks=struct.unpack_from('<I',d,8)[0]; hs=struct.unpack_from('<I',d,20)[0]
off=((hs+4095)//4096)*4096
open('kernel.Image','wb').write(d[off:off+ks])
PY
vmlinux-to-elf kernel.Image vmlinux.elf
python3 tools/dump_syms.py vmlinux.elf symbols.txt
```

把 `symbols.txt` 放进 `assets/core/`，在 `DeviceProfiles` 里给对应 release 加一条档案即可。


---

# 10 · 98260 执行引擎

## 一、整体结构

新内核（ColorOS 17.0.1.201）走三条路径，按可用性依次选择：

| 顺序 | 通道 | 触发条件 | 说明 |
| --- | --- | --- | --- |
| 1 | **98260 执行引擎** | `assets/core/exec/onekey-98260` 存在 | 实测可在 3 秒内完成越狱（见 §三） |
| 2 | 自研执行器 | `assets/core/exec/onekey-exec` 存在且引擎缺失 | leak 已可用（slide 与引擎一致），plant 未完成 |
| 3 | onuce 旧通道 | 以上都缺 | 旧内核兜底 |

旧内核（17.0.0.x / f957acc 构建）继续走 onuce，不受影响。

## 二、引擎调用协议（App 侧）

工作目录 `/data/local/tmp/pyb110-onekey-<时间戳>`，通过环境变量传参：

| 变量 | 取值 | 含义 |
| --- | --- | --- |
| `PYB110_DIR` | 工作目录 | 引擎读写路径 |
| `PYB110_MANAGER` | `finasu` / `sukisu` / `next` / `bakasu` / `kernelsu` | 管理器短名（**不是包名**） |
| `PYB110_LATE_LOAD` | `1` | 允许用管理器自带 libksud 做 late-load |
| `PYB110_SUSFS` | `0/1` | 对应「SUSFS 支持」开关 |
| `PYB110_VFS` | `0/1` | 对应「VFS 支持」开关 |
| `PYB110_UNLOAD_GUARD` | `0/1` | 对应「卸载内核安全防护」 |
| `PYB110_DISABLE_ANTIROOT` | `0/1` | 对应「禁用安全弹窗」 |

引擎输出为逐行文本，App 映射为界面阶段：

| 输出 | 界面阶段 |
| --- | --- |
| `[1/4] …plant…` | 布置提权原语 |
| `[2/4] …late-load…` | 加载内核模块 |
| `[3/4] …su…` | 等待 su |
| `[4/4] …manager setup` | 管理器收尾 |
| `MODULE_LOADED` / `SU_ID_RC=` / `DONE` | 日志里程碑 |

## 三、真机成功证据（OnePlus 16 / 17.0.1.201）

```
SU_ID_RC=0
su: uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0
kernelsu 266240 2 - Live 0x0000000000000000 (O)
Enforcing
```

- 从点击到 su 可用约 **3 秒**；
- 引擎日志显示 slide 一次得出（`SLIDE_DERIVED`），tile 写一次命中（约 558ms），cred 写补一次 uid；
- 已实测：`adb shell su -c id` 返回 `uid=0`。

## 四、五个拓展功能

首页「高级设置 → 拓展功能」提供 5 个开关，默认展开：

1. **越狱后禁用安全弹窗**（默认开）→ `PYB110_DISABLE_ANTIROOT`
2. **越狱后软重启**（默认关）→ `PYB110_SOFT_REBOOT/PYB110_REBOOT`；App 自身不再主动软重启
3. **越狱后卸载内核安全防护**（默认开）→ `PYB110_UNLOAD_GUARD`（rmmod oplus_secure_guard_new）
4. **SUSFS 支持**（默认关）→ `PYB110_SUSFS`，同时投送 `susfs_guard_lkm.ko`
5. **VFS 支持**（默认关，仅 6.18）→ `PYB110_VFS`，同时投送 `hybridmount-6.18.21.ko`

开关状态持久化在 `merged` 偏好中，键名分别是
`antiroot` / `soft_reboot_fix` / `unload_kernel_guard` / `susfs_enabled` / `vfs_enabled`。

## 五、日志与状态

- 每行日志 `FileOutputStream(append) + flush + fd.sync()` **立即落盘**到
  `files/onekey-live.log`，软重启或进程被杀后重新打开 App 会自动回读最后 400 行并标注
  「以上为上次运行记录（可能被软重启中断）」。
- 结束提示按事实分三种：
  - 成功 → 「98260 全链完成」；
  - 未写内核 → 「停在「阶段」（未写内核，可直接重试）」+ 原因；
  - 已写内核未拿到 root → 「需重启后再试」。
- 若检测到 boot_id 变化，日志会写「检测到用户态重启（boot_id 变化），这不是 App 主动触发的软重启」，
  便于区分是引擎/系统行为还是 App 行为。

## 六、排障

| 现象 | 处理 |
| --- | --- |
| `KSUD_MISSING` | 先安装所选管理器；引擎只从管理器 APK 取 ksud |
| 停在「布置提权原语」 | 未写内核，可直接重试；连续失败看是否被安全组件拦截 |
| su 不可用但模块已加载 | 打开「越狱后软重启」让用户态重新挂载，或重启后再跑 |
| 提示用户态重启 | 属引擎/系统侧行为；不影响模块与 root，重启后 App 会自动回读日志 |

## 七、与自研执行器的关系

自研 `onekey-exec` 已完成 **leak**：从 trace 推导 slide，实测与引擎结果逐字节一致
（同一 boot 均为 `0x293f000000`），并可用同一 slide 算出 `selinux_state-7` 与引擎的 tile 目标完全相同。
尚未完成的是 **tile/cred 写原语**；完成后即可替换引擎、消除外部依赖。


---

# 11 · 热切换与通用模块

## 为什么要它

KernelSU 系的管理器各自编译自己的内核模块，模块里写死了"我认哪个包名/证书"。
换管理器要么刷机、要么重启，很不方便。KSU-Universal 把五家的画像（包名、证书长度、证书 SHA-256、
feature 上限、ioctl 路由）编进**同一个**内核模块，运行时通过 `/proc/kernelsu_manager` 切换 active profile。

## 硬前提：开机后第一个加载的 kernelsu 模块

通用模块必须是本次开机第一个加载的 `kernelsu`。一旦管理器自带的单管理器模块先加载，
通用模块再 `late-load` 会被直接跳过（打印 `KernelSU already loaded, skip loading ko`，且返回 0 —— 静默失败）。

而内核模块一旦加载就卸不掉（真机实测 `rmmod kernelsu` 返回 EBUSY / Try again），所以只能开机时换。

## 加载器替换（本项目的做法）

引擎固定用「管理器 APK 目录下 `lib/arm64/libksud.so`」，且实测忽略 `PYB110_KSUD` / `PYB110_MANAGER_PKG`。
所以本项目的办法是**把那个文件换掉**：

```sh
P=$(pm path <pkg> | head -1 | sed 's/^package://'); D=$(dirname "$P")
F="$D/lib/arm64/libksud.so"; B=/data/local/tmp/onekey-libksud-backup.so
[ -f "$B" ] || cp "$F" "$B"          # 首次安装先备份
cp <staged ksud_universal> "$F.new" && chmod 755 "$F.new" && mv -f "$F.new" "$F"
```

- **安装后必须重启**，重启后第一次越狱就会走通用模块，节点出现；
- 「还原加载器」把备份写回，恢复单管理器模块；
- 管理器升级会重建 `lib/` 目录，加载器被还原 —— 2.18.30 起在越狱成功后自动补装；
- 就绪判定：`stat -c %s "$F"` 等于内置 `ksud-universal` 的字节数（`UNIVERSAL_KSUD_SIZE`）。

## 用户操作流（与界面一致）

```
安装通用加载器 → 重启手机 → 再运行一次越狱 → 节点就绪 → 选择目标 → 切换
```

界面按阶段收敛按钮：未装只给「安装」；已装未生效只给「重启」+「检测」；就绪后才出现「切换」。

## 切换协议

root 写 `/proc/kernelsu_manager`：`<包名> <uid> <base.apk 路径>`（≤ 512 字节）。
内核校验 euid==0、包名在静态表、apk 目录与包名匹配、APK v2 证书长度与 SHA-256 一致，然后切换 profile。
本项目在此基础上额外做了一次签名复验（`expectedCert`），并在切换后重新读节点确认 primary 变化。

## 已知行为与坑

1. **多个管理器同时显示"工作中"**：上游 BakaSU profile 会扫描并注册所有已安装的静态管理器，
   这是模块设计，不是本项目 bug。唯一判据是面板里的 `primary：<包名> <版本> <feature>` 那行是否变化。
2. **模块静态表的 versionCode 与最新 APK 有差**（例如模块表里 FinaSU 记的是旧版本号）：
   校验以**证书**为准，版本号差异只影响管理器自身"建议更新"提示。
3. 引擎不支持外部 ksud：`PYB110_KSUD` 实测被忽略，别指望用它换加载器。

## 通用模块版本

| 项 | 值 |
| --- | --- |
| 上游 | `Yunnijian/KSU-Universal` v1.2.0 |
| 内置 `ksud-universal` | 12,652,520 B，SHA-256 `d14f50df…` |
| 内嵌 LKM | `android17-6.18_kernelsu.ko`，vermagic `6.18.21-4k` |
| 节点 | `/proc/kernelsu_manager` |


---

# 12 · 版本变更史（2.18.0 → 2.19.7）

只记录对外发布过的版本；每条对应 GitHub Release 上的 tag。

## 2.19.x

| 版本 | code | 主题 | 关键内容 |
| --- | --- | --- | --- |
| **2.19.7** | 205 | 审计问题 1–5 修复 | 残留进程清理改用 root + comm 双路匹配（`pkill` 模式写 `onekey-9826[0]` 避免杀）且先验 root 后清理；`MODULE_LOADED` 即 `markWrote` 使 `wroteKernel` 真实；自研执行器回退分支可达并如实告知 plant 未完成；删死 Java 2249 行（`readAsset` 先内联进 `ShizukuShell`）；删死资产 8,871,727 B（含 grab.sh）；抽出 `MarkdownParser` 并补 4 个 parser 单测 |
| 2.19.6 | 204 | 彻底移除 MIT 衍生代码 + 全机型换 Shark 执行器 | 删除 ghostkey 原生后端（源码/驱动/profile/产物）与 4 个 onuce 载荷；6.18.21 与 6.12.69 各内置一份 Shark runner（上游 v2.3.2 / `49c3b0e`）；新增 6.12 SUSFS 模块；VFS 仅 6.18 生效；补 `LICENSES/MIT-pc98260.txt` 与历史署名；应用内第三方声明改为 Markdown 渲染 |
| 2.19.5 | 203 | 软重启开关真正生效 + 合并 Shark v2.1 | 「越狱后软重启」默认关闭且由应用显式执行（关闭时不重启）；antiRootGuard / unloadKernelGuard 默认关闭；安全组件改为只 force-stop、不 `pm disable-user`；引擎换成上游 v2.1 的 6.18.21 runner（支持 `PYB110_UNIVERSAL` / `SUSFS_KO` / `VFS_KO` / `KMI`），KMI 按机型取值 |
| **2.19.4** | 202 | 管理器版本运行时提取 | 五家版本/资产改为从官方 Release 实时提取；提取不到不展示；结果缓存 6 小时 |
| 2.19.3 | 201 | 五家管理器同步 + ksud 归一化 | FinaSU → v1.5.0 / 33174；SukiSU 期望版本 → 41150；新增 `KsudNormalizer`（按 `-V` 输出识别真 ksud 并补回 `libksud.so`） |
| 2.19.2 | 200 | 合入 KSU-Universal v1.2.0 | 内置加载器 12,335,680 → 12,652,520 B；同步 `UNIVERSAL_KSUD_SIZE` 与第三方声明 |
| 2.19.1 | 199 | 首页精简 | 移除运行卡里的「环境诊断」按钮；移除「高级设置」与其展开的越狱方式卡 |
| 2.19.0 | 198 | 界面按网页原型重做 | 语义色板、设备卡四列、外观三态、拓展功能分组、日志/历史/关于/手册重做、抽屉单一实例 |

## 2.18.x（热切换与稳定性）

| 版本 | code | 关键内容 |
| --- | --- | --- |
| 2.18.30 | 197 | 管理器升级后自动补装加载器；还原时清长期启用标记 |
| 2.18.29 | 196 | 面板显示 primary 原文；切换后强制停止全部管理器 |
| 2.18.28 | 195 | 目标管理器随时可选；新增安装/重启/还原加载器 |
| 2.18.27 | 194 | 加载器安装与还原界面 |
| 2.18.26 | 193 | 通用模块开关标注"引擎不支持外部 ksud" |
| 2.18.25 | 192 | 运行前拦截"已加载单管理器模块"；加载结果落盘回读 |
| 2.18.24 | 191 | 检测与切换并排；状态诊断行；未授权自动申请 |
| 2.18.23 | 190 | root 多路探测；节点探测与 su 解耦 |
| 2.18.22 | 189 | 成功后即时落盘状态（软重启前也留住） |
| 2.18.21 | 188 | 按 boot_id 判定"本次开机已越狱" |
| 2.18.20 | 187 | 修复未启动 Stellar 时闪退；失败计数改为按次 |
| 2.18.19 | 186 | 恢复默认单管理器路径，通用模块改为按需 |
| 2.18.18 | 185 | 内置 ksud-universal；新增「管理器下载」 |
| 2.18.17 | 184 | 管理器热切换（首版） |
| 2.18.16 | 183 | 适配 ColorOS 17.0.1.201（新内核 g7fcbff） |

## 2.18.0 ~ 2.18.15

围绕 98260 链在不同小版本上的稳定性：重复运行锁、日志落盘、失败归因、软重启判定、
权限修复、诊断导出、安全组件停用/恢复等。细粒度条目见 GitHub Releases。

## 版本号规则

- `versionCode` 单调递增，当前 201；**必须比线上大**，否则覆盖安装与更新提示都会错乱；
- `versionName` 与 Release tag 一致（`v2.19.3` ↔ `2.19.3`）；
- 不发 pre-release，全部直接发正式版。


---

# 13 · 踩坑与修复记录

按「症状 → 根因 → 修法」记录，避免重复踩。

## 热切换相关

### 1. 装了通用模块开关、越狱也成功了，但 `/proc/kernelsu_manager` 不存在

**根因**：引擎按固定路径取 `libksud.so`，且 `PYB110_KSUD` 被忽略；线上跑的其实是管理器自带的单管理器 LKM。
**修法**：改为"替换管理器目录下的 `libksud.so`"为内置通用加载器，重启后生效。

### 2. `rmmod kernelsu` 失败，想热替换模块做不到

**根因**：模块引用计数 > 0，内核拒绝卸载（`Try again` / EBUSY）。
**结论**：通用模块只能在**开机后第一次**加载，必须重启，别指望运行时替换。

### 3. 打开开关、重启、再跑一次，仍然没有节点

**根因**：这一轮之前已经加载过单管理器模块（`late-load` 见到已存在就静默跳过，还返回 0）。
**修法**：运行前检测"已加载 kernelsu 且无节点"直接拦截，提示重启；加载结果落盘回读，日志区分成功/未生效。

### 4. 切换后五个管理器全部显示"工作中"

**根因**：上游 BakaSU profile 会注册所有已安装的静态管理器（设计如此）。
**结论**：以面板里的 `primary：…` 原文为唯一判据；切换后强制停止全部管理器，重开才刷新。

### 5. 目标选择完弹窗直接关掉，还得再点一次热切换

**根因**：选择回调里把抽屉关掉了。
**修法**：选完 `sheet = 1` 回到热切换面板本身（抽屉保持可见，不重新播入场动画）。

## 抽屉/界面相关

### 6. 切换开关时整个抽屉"弹一下"

**根因**：抽屉实例化在列表 item 内、`visible` 状态局部创建，内容变化时被重建/高度动画重跑。
**修法**：抽屉合并为单一实例、`visible` 提到根级、每个面板给固定高度。

### 7. `WindowBottomSheet` 完全不显示

**根因**：初始 `show` 就是 true，入场动画没有起点。
**修法**：`var v = remember { false }` + `LaunchedEffect { v = true }`，再由 `v` 驱动 `show`。

### 8. 分组卡片比自绘卡片窄一圈

**根因**：页面 `contentPadding` 已给 12dp，`ListCard` 内又加了一层 `padding(horizontal = 12.dp)`。
**修法**：去掉组件内边距，统一由页面给。

### 9. 深色下设备卡/运行卡发灰黑、与其它卡片不一致

**根因**：自绘卡片用了自定义色，`ListCard` 用 COUI 的 `surfaceContainer`，两套颜色。
**修法**：新增 `oneKeyCardColor()`（直接取 COUI `surfaceContainer`）与 `oneKeyDeviceColor()`（其上叠主色）。

### 10. 顶栏文字被内容盖住 / 模糊把文字糊掉

**根因**：三层都用 `matchParentSize()` 导致高度塌陷；`DstIn` 淡化作用到了文字层。
**修法**：只让内容层决定高度；模糊层、scrim、文字层分开，淡化只作用于模糊层。

### 11. 软重启后显示"未越狱"，热切换不可用

**根因**：引擎成功后立刻软重启，进程来不及写状态。
**修法**：收到 `SU_ID_RC=0` 立即同步落盘 `success_boot_id`（`commit()` 而非 `apply()`）。

### 12. 未启动 Stellar 时点开始直接闪退

**根因**：读 `/proc/sys/kernel/random/boot_id` 的调用写在 `try` 之外。
**修法**：包进 `runCatching`，读不到就不阻断，交给服务检查提示。

## 引擎/日志相关

### 13. 越狱卡死、等一分钟手机自动重启

**根因**：`cred` 阶段回收没落地（`RECLAIM_FREED=0`、`HIT=0`）时引擎仍盲写 4096 次并反复重试，把内核拖挂。
**修法**：按"每次尝试"计数，连续 4 次未命中即中止；中止后尝试 `setenforce 1` 恢复；失败且已写入内核则记 boot_id，下次先要求重启。

### 14. 一次失败被当成两次

**根因**：同一次尝试会同时输出 `HIT=0` 与 `rc=-1` 两行，按行计数就翻倍。
**修法**：只按 `rc=-1` 计一次。

### 15. 构建期 Kotlin 注释里的 `/*` 把代码吃掉一半

**现象**：写 `lib/arm64/*.so` 到 KDoc 里，报 "Unclosed comment"。
**根因**：Kotlin 块注释可嵌套，`/*` 会开新注释。
**修法**：文档里避免出现 `/*` 序列（改写措辞或断行）。

### 16. 用脚本按行号整段搬运代码，导致大面积删除

**教训**：文件改过很多轮后行号不可靠，切片容易切错。
**做法**：改代码一律用**函数名/唯一注释做锚点**，先备份，改完立刻编译；一次只动一个函数。

## 发布相关

### 17. CI 报 `Keystore file not set for signing config release`

**根因**：仓库不含 `keystore.properties`（已 gitignore），签名配置却无条件创建。
**修法**：`build.gradle.kts` 里按文件是否存在条件启用签名；本地发版时手动放入 `keystore.properties` + keystore。

### 18. 未签名包能装但装上后拒绝执行

**原因**：`SecurityCheck` 校验安装包签名指纹，非本项目签名的包一律拒绝。

### 20. 「越狱后软重启」开关拨了没反应，开与关都照样重启

**症状**：拓展功能里的「越狱后软重启」无论开还是关，越狱成功后系统都会重启一次。

**根因（三条叠加）**：
1. 开关只做了两件事：写 prefs、把 `PYB110_SOFT_REBOOT` / `PYB110_REBOOT` 拼进引擎环境变量；而引擎**根本不读这两个变量**（引擎的 `PYB110_` 全集里没有它们），所以开关是死开关。
2. 应用自身也没有任何重启调用（`RestartManager.request()` 零调用点），所以"没有自动重启代码"并不等于"不会重启"。
3. 真正扰动系统的是成功后的 `hardenAfterSuccess()` → `Safer.disable()`：它执行 `pm disable-user com.coloros.securityguard` / `com.oplus.safecenter`，改的是 PackageManager 数据，ColorOS 上很容易连带 system_server 重启；同时 `unloadKernelGuard` 默认开会去 `rmmod oplus_secure_guard_new`。

**修法**：
- 软重启改为**应用侧显式动作**：`softRebootAfter` 默认 false，只有开关打开且越狱成功才执行一次 `su -c 'setprop ctl.restart zygote'`，并用 `softRebootIssued` 保证单次运行最多一次；关闭时只记一行「保持当前会话不重启」。
- 安全组件从 `pm disable-user` 改为 `am force-stop` + `stop riskdetect`，不改包状态。
- `antiRootGuard`、`unloadKernelGuard` 默认改为 false。

**教训**：给引擎传环境变量前，先在二进制里数一遍该变量出现次数；0 次就是没人读，别指望它生效。

### 21. 上游 runner 换了命名与协议，包装脚本可以退役

**现象**：通用多管理器模块靠一段 shell 包装脚本 + `PYB110_KSUD` 假接线实现，脆弱且 `--kmi` 写死 `android17-6.18`。

**根因**：我们内置的引擎是上游改名前的旧构建（`usage: t98260 run`），不支持 `PYB110_UNIVERSAL`。

**修法**：换用上游 v2.1 的 `libk61821.so`（`usage: k61821 run | ksud | __plant`，451,536 B，SHA-256 `4c88a76a…`），env 增加 `PYB110_UNIVERSAL=1` / `PYB110_UNIVERSAL_KSUD` / `PYB110_SUSFS_KO` / `PYB110_VFS_KO`，`PYB110_KMI` 改由 `DeviceProfiles.kmi` 提供；删除 25 行包装脚本。

### 22. 引入外部 PoC 源码没补署名，且漏查了无扩展名的 assets 二进制

**症状**：上游作者在其他仓库连提三个 issue 要求补 MIT 署名；自查发现本仓库同样命中。

**根因**：
1. `cpp/ghostkey/pc98260_su.c` / `pc98260_rootd.c` 改编自 MIT 项目，保留上游 80/83 与 87/100 非空行，但文件头无版权行，声明文件里也没有原项目链接与 MIT 全文。
2. 第一轮排查只搜了 `*.so` 与 `libcve98260*`，**漏掉了无扩展名的 `assets/ghostkey/ghostkey98260`**，误判成"产物未分发"。
3. 进一步复扫发现 `onuce_*` 载荷也内嵌同一 rootd 实现（含 `[rootd] startup failed errno=%d (%s)` 原文与源文件名 `pc98260_rootd.c`）。

**修法**：
- 删除 ghostkey 全套与 4 个 onuce 载荷，改用上游按内核拆分的 Shark runner（6.18.21 / 6.12.69），二者均不含该 MIT 部分；
- 补 `LICENSES/MIT-pc98260.txt`（上游 LICENSE 原样）+ 声明第五节历史署名与来源链。

**教训**：
1. 排查二进制来源时**按内容扫全部 assets 文件**，不要按扩展名过滤——可执行产物常常没有后缀；
2. 删 HEAD 不追溯既往：历史提交与已发布 APK 仍构成"分发"，署名不能省；
3. 删资产前先查回退分支：`GhostKeyEngine` 投放失败返回 `false` 而非 `null`，只删文件会让调用方不回退，直接坏掉。

### 23. grep 被 head 截断，把活代码误判成死代码

**症状**：审计时判定 `Prep.java`（384 行）为零引用死代码，准备删除。

**根因**：核实命令写成 `grep -nE "\bPrep\b|..." ShizukuShell.java | head -5`，
5 条 `ProcessRunner` 命中占满了输出，真正的第 159 行 `Prep.readAsset(in)` 被截掉。
而 `ShizukuShell.stageAsset` 每次资产投放都走这一行 —— 删掉 `Prep.java` 会让整个 App 的投放全挂。

**修法**：先把 `readAsset` 内联进 `ShizukuShell`（改名 `readAssetBytes`），再删 `Prep.java`。

**教训**：**判断"是否死代码"的 grep 一律不加 head/tail 截断**；
输出长就换更精确的模式或按文件分组，绝不能靠截断后的前几条下结论。

### 24. 越狱后残留进程杀不掉：shell 身份杀不了 root cred 的 holder

**症状**：越狱成功后进程管理器里长期残留两个 `onekey-98260`（实测 PID 17418 / 17462）。

**根因**：
1. 收尾用 `pkill -9 -f onekey-98260; true`，以 shell（uid 2000）身份执行；
2. 上游 `plant_core.inc` 用 `prctl(PR_SET_NAME, "t98260-hold" / "t61269-hold")` 给 parked 子进程改名，
   并让它持有提权后的 **root cred** → shell 杀 uid 0 进程返回 EPERM；
3. 末尾 `; true` 把失败吞掉，日志里看不出任何异常；
4. 而且 `finally` 的清理跑在 `su -c id` 校验**之前**，顺序反了。

**修法**：
- 先验 root（`su -c id` = 0），再清理；
- 清理用 root 身份，且 **cmdline + comm 双路匹配**：`pkill -f` 之外再遍历 `/proc/*/comm`
  命中 `t98260-hold|t61269-hold|yexec` 逐个 `kill -9`；
- 用 `echo KILL_DONE` 做回执，收不到就记日志，不再 `; true` 静默。

**教训**：杀"自己启动的进程"也要考虑它可能已经提权；
`prctl` 改的是 comm 不是 cmdline，只按 `-f` 匹配会漏。

### 19. GitHub push 偶发 `TLS connect error / unexpected eof`

**处理**：重试即可（网络问题，不是仓库状态问题）；长命令用后台任务，避免前台超时把任务打断。

## 踩过的"以为坏了其实正常"

| 现象 | 结论 |
| --- | --- |
| 五个管理器都显示"工作中" | 上游 BakaSU profile 设计，看 `primary` 行 |
| 管理器提示"模块版本较旧" | 通用模块静态表的版本号是快照，以证书为准 |
| SukiSU 显示 41158 而下载列表是 41150 | 41158 是 `manager-ci` 预发布，列表只跟 stable |
| 首页"100%"但进度条没到顶 | 完成后按语义显示 100%，与实际 progress 字段解耦 |


---

# 14 · 验证清单与已知未验证项

## 每次发版前（编译层，必须做）

1. `./gradlew compileReleaseKotlin` 通过；
2. `./gradlew assembleRelease` 通过；
3. 产物大小与上一版差异合理（例如换加载器会涨 300 KB 左右）；
4. 若换了内置二进制，从 APK 里解出来比对 SHA-256。

## 真机回归（能连上设备时）

### 基础
- [ ] 冷启动进入首页，服务状态显示正确（在线/离线）
- [ ] 外观切换：浅色 / 深色 / 跟随系统，重启 App 后保持
- [ ] 顶栏外观按钮、底栏四页、模糊层无遮挡

### 越狱
- [ ] `su -c id` 正常，五阶段进度推进
- [ ] 成功后状态卡显示「SU 就绪 / 本次开机 已运行」
- [ ] 失败时日志能指出停在哪一步；已写入内核的失败要求重启

### 热切换
- [ ] 安装通用加载器 → 文件大小等于内置加载器
- [ ] 重启后运行一次 → `/proc/kernelsu_manager` 出现
- [ ] `primary：` 行随切换变化
- [ ] 还原加载器后热切换不可用、管理器自带模块恢复

### 五家管理器（2.19.3 起）
- [ ] KernelSU / Next / BakaSU / FinaSU：首次越狱直接可用
- [ ] SukiSU：已 root 的环境下越狱前自动归一化，日志出现「已用 … 补齐 libksud.so」
- [ ] 归一化后引擎不再报 `KSUD_MISSING`

## 已知未验证（写于 2.19.3）

设备在发版时处于离线状态，以下**只过了编译**，没有真机结论：

1. `KsudNormalizer` 在真机的实际输出（`/data/local/tmp` 是否允许执行、`timeout` 是否存在）；
2. SukiSU 归一化后引擎能否正常 `late-load`；
3. FinaSU v1.5.0 的首次越狱与热切换；
4. 五家全部切换后 `su -c id` 是否都为 root；
5. 抽屉固定高度在超大字体下是否裁切；
6. 真实应用图标的首次加载耗时；
7. 深色模式下 COUI 组件（开关、卡片）是否整体跟随。

> 结论口径：**编译通过 ≠ 功能通过**。上面每一条都要真机跑过才能划掉。

## 失败时的取证顺序

1. 首页状态卡四项（服务通道 / SU 权限 / 热切换 / 本次开机）；
2. 日志页最后 30 行（按级别筛「错误」）；
3. 历史 → 详情（含当次完整日志，可复制/导出）；
4. `/proc/modules` 里 `kernelsu` 是否已存在（已存在说明本次开机没机会换模块）；
5. `/proc/kernelsu_manager` 是否存在与内容（`primary` 判据）。
