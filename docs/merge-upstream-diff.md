# 上游 Yunnijian/OnePlus16-Jailbreak 与本地 v2.3.3 差异分析（task-1）

> 分析人：upstream-diff ｜ 日期：2026-10-20 ｜ 只读分析，未修改本地 app 源码、未操作手机。
>
> 本地基线：`OnePlus16-Jailbreak/`（git HEAD `fe6fa84` = v2.1，工作树带未提交的 v2.3.3 改动，versionName 2.3.3 / versionCode 16）
> 上游基线：`/tmp/yunni`（https://github.com/Yunnijian/OnePlus16-Jailbreak，已 `git fetch --unshallow`，共 6 个 commit，最新 `7b76272`，2026-10-20 18:22 +08）
> 真机证据：`evidence/detection-audit-2.3.3/mountinfo.*.txt`（detection-audit / task-3 于 22:50 抓取，当前工作区可直接读取）

---

## 0. 结论速览

1. **两个仓库没有 git 关系，不能 merge**：上游 GitHub API `fork=false`、独立 6 个 commit（2026-10-20 13:56～18:22 +08，含一个 "Initial commit baseline"）；本地是 6 个 commit 到 v2.1 + 未提交的 v2.3.3 工作树。只能把上游当"参考实现"逐点移植，不能 cherry-pick/merge。
2. **上游本质是重写**：Kotlin/Compose/Miuix + Gradle + Shizuku + 4 管理器 + 自编 LKM + 设备端 C 工具 + 六步 `run.sh`；本地是 Java Views + aapt2 + Stellar + 2 管理器 + 预编译 ko 模板 + App 内 Java 求解/补丁。两边共有的是公开 CVE 工具链（`forker` 字节相同，`third_party/` 19 个脚本与 `reference/CVE-2026-64560-public/tools` 逐字节一致）。
3. **上游真正"新增"的只有两条**：(a) **反 root 守卫**（`com.oplus.exsystemservice` 检测到提权会弹窗并 15s 后 reboot，上游用 disable→kill→5s→enable 规避）；(b) **4 管理器选择**（官方 KernelSU / KernelSU Next / BakaSU / SukiSU Ultra）。其余是"换实现"：LKM 源码自编、设备端 `prep_solve`/`prep_patch`、每 run 部署目录、ALREADY_LOADED 快速修复。
4. **上游没有做任何隐藏/反检测**：不隐藏 mount、不改 SELinux 终态（保持 Enforcing，反而比本地的 Next 路径干净）、不管理 allowlist/su_compat、不在结束后清理 `/data/local/tmp`。它只是"没有引入 su 路径 overlay"这一条特定痕迹。
5. **本地 v2.3.3 在 SU/用户态安装上的严谨度高于上游**：管理器版本 + 签名 SHA-256 + UAPI/version 三重校验、`ksud manager add/set-enabled` 回读、`feature set su_compat 1`、`debug mark refresh`、绝对路径/stdin/`-M` 三路 su 自检、唯一 runId、投放长度校验 + 原子替换、全命令超时、模块必须 Live、4 组单元测试。**这些不要被上游实现替换**。
6. **上游 4 个 LKM 里有 3 个没打本机 vermagic**：`ksu-base-next/bakasu/sukisu.ko` 的 `.modinfo` 仍是 `vermagic=6.18.21-4k SMP preempt mod_unload modversions aarch64`；只有 `ksu-base.ko`（官方 KernelSU）被本仓库同款 `ko_vermagic.py` 手法改成了 PYB110 的 `6.18.21-android17-5-gf957accaf563-ab15926176-4k …`。按本地已实测结论（`CONFIG_MODULE_FORCE_LOAD` 未设 ⇒ 不能 `IGNORE_VERMAGIC`，见 `docs/158`、`docs/162`），这三份 ko 在 PYB110 上应当 `finit_module` 失败 ⇒ **上游"4 管理器"资产不可直接搬用**。
7. **本地 v2.3.3 的 `/system_ext/bin/su` + `onekey-su` overlay 是可被普通 App 检测到的 root 痕迹，已由真机证据坐实**：Gallery / SystemUI / KernelSU manager / `adb shell`(uid2000) / init 五种视角的 `/proc/self/mountinfo` 都包含同一行 `overlay onekey-su … lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin`；普通 App uid（`run-as` + `u:r:untrusted_app:s0` 复核）可 stat/读/执行 `/system_ext/bin/su`（9056 B，md5 `709b42b0b88e9d49cf2e8f799cf934bd` = `app/assets/su_forward`），`statfs(/system_ext/bin)`=overlay `0x794c7630`，`mount`/`df` 输出显示 `onekey-su`；而 KSU 虚拟的 `/system/bin/su` 对 App 反而是 ENOENT（只对白名单 uid 伪造可见）。上游不创建这种挂载，但也没隐藏它自己的痕迹。
8. **加冕方式三家三种**：本地 Next 路径靠 ko cred 补丁（`track_throne` 用 `init_cred`）+ `noenforce` 补丁；本地 SukiSU 路径靠 `ksud manager add` 显式登记；上游靠 `chmod 644 /data/system/packages.list` + `mv` 回环触发 `pkg_observer`。本地方式不动系统文件权限，更干净；上游方式可作为新增管理器时的兜底。
9. **SELinux 终态不一致（本地的隐患）**：本地 `ksu-template.ko`（Next 路径）`.init.text+0x148` 是 `1f2003d5`(NOP)，模块内 `setenforce(true)` 被 NOP 掉 ⇒ 越狱后**长时间保持 Permissive**；`sukisu-template.ko` 该字节是 `94000000`(bl)，未打补丁 ⇒ 保持 Enforcing（与当前真机状态一致）。上游 4 个 ko 都未打 noenforce ⇒ 统一 Enforcing。Permissive 是比 mount 更容易被 App 检测的强特征，建议评估移除 Next 模板的 noenforce。
10. **落地优先级**：P0 = 反 root 守卫（唯一全新能力）+ `Prep.patchKo` slide 合法性守卫 + bootstrap ksud 路径统一（顺带省 5.5–7 MB 重复投放）；P1 = 四管理器（需重建 LKM + 版本/签名常量）+ su 路径 overlay 的"默认关闭/可撤销"改造；P2 = 文档/构建/UI 经验。**动手前先把 v2.3.3 未提交内容 commit**（工作树 9 改 7 新，dist APK 实际由 `ondevice-app/` 构建，产物不可追溯）。

---

## 1. 基线与证据核对

### 1.1 仓库 / 提交 / 工作树

| 项 | 本地 | 上游 |
|---|---|---|
| 路径 | `OnePlus16-Jailbreak/` | `/tmp/yunni` |
| remote | `github.com/xingranya/OnePlus16-Jailbreak` | `github.com/Yunnijian/OnePlus16-Jailbreak` |
| GitHub fork | 原仓库 | `fork=false`（API 查询），独立创建于 2026-10-20T09:43Z |
| 提交 | 6 个：v1.5 → v1.6 → v1.8 → v2.0 → v2.1（HEAD `fe6fa84`） | 6 个：`4e4e23a` baseline → `d62d45a` SukiSU → `4ab57fd` SukiSU v4.2.1 → `2511f74` 日志线程修复 → `d77840e`/`7b76272` 文档截图；tag `v1.0` |
| 工作树 | **dirty**：9 个已改（README、AndroidManifest、root_stage_mobile.sh、sukisu-template.ko、MainActivity.java、Prep.java、ShizukuShell.java、使用教程.md；删除 root_stage_sukisu.sh）+ 7 组未跟踪（appreciation.png、su_forward、su_path_compat.sh、ProcessRunner.java、RootDiagnostics.java、app/src/su/、tests/） | clean |
| 版本号 | `versionName 2.3.3` / `versionCode 16` | Gradle `versionName "1.0"` / `versionCode 1` |

> 注意：`dist/OneKeyJB-v2.3.3.apk`（9,287,747 B，sha256 `eaded2…`）**等于** `ondevice-app/build/OneKeyJB.apk`，而不是 `OnePlus16-Jailbreak/app/build/OneKeyJB.apk`（9,271,230 B，09:00 旧构建）。`ondevice-app/` 是 v2.3.3 的实际构建镜像（与 `app/` 源码仅 `perf_cred_mobile` 一个文件不同，build.sh 会从父目录 `bin/`、`out/ksud-3.4.2` 拷二进制）。**v2.3.3 的源码-产物链路目前不可复现**，建议先把工作树提交、把 build.sh 依赖收进仓库。

### 1.2 构建 / 产物 / 运行时依赖

| 项 | 本地 v2.3.3 | 上游 |
|---|---|---|
| 语言 | Java 17（无 AndroidX） | Kotlin 2.4.20 + Compose BOM 2026.09.00 |
| 构建 | `app/build.sh`：javac + d8 + aapt2 + zipalign + apksigner（debug.keystore） | Gradle 9.7.1 + AGP 9.4.1 + R8/资源压缩 + `keystore.properties` release 签名 |
| UI | 手写 Material 3 风格 Views（1819 行 MainActivity） | Miuix 0.9.4 组件（678 行 MainActivity.kt） |
| 特权后端 | **Stellar**（`roro.stellar.manager`，JitPack API 1.0.3）；代码注释说明 Shizuku 在 ColorOS 17 上报"ADB 权限受限" | **Shizuku**（`dev.rikka.shizuku:*:13.1.5` + provider） |
| 管理器 | 2：KernelSU-Next 3.4.2、SukiSU Ultra v4.2.1 | 4：KernelSU 官方 3.3.1、KernelSU Next、BakaSU（原 ReSukiSU）、SukiSU Ultra v4.2.1 |
| LKM 来源 | 预编译模板（`ksu-template.ko` 531,936 B / `sukisu-template.ko` 406,294 B），离线补丁链：cred / noenforce / vermagic；符号在 App 内 Java 按当次 slide 打 | GKI 6.18.21 源码自编（4 个 `lkm/build-*.sh`，DDK 容器 `ghcr.io/ylarod/ddk-min:android17-6.18-20260828`）；设备端 `prep_patch` 按当次 slide 打符号 |
| APK | 9.29 MB（ksud 运行时从已装管理器 APK 提取 `libksud.so`） | README 校验和 22,240,543 B（4 份 ksud 全打包） |
| 每次投放 | Next 约 16.8 MiB / SukiSU 约 13.7 MiB（含把管理器 ksud 重复投放两份） | **固定 40.9 MiB / 次**（17 个 asset 全投放，含 4 份 ksud + 4 份 ko，与所选管理器无关） |
| 测试 | `tests/`：test_root_stage.py（mock 全流程）、RootDiagnosticsTest、PrepTest | 无 |

### 1.3 二进制来源谱系（sha256 比对）

| 文件 | 关系 |
|---|---|
| `forker` | 本地/上游 **完全相同**（`82394673557b…`，公开工程 Apache-2.0） |
| `ksud_official`（上游） vs `ksu/ksud.arm64`（本地父目录） | **完全相同**（`4f7d5fd65631…`，官方 KernelSU 3.3.1，uapi 5） |
| 上游 `third_party/cve-2026-64560-tools/*` 19 个文件 | 与 `reference/CVE-2026-64560-public/tools/` **逐字节相同**（含 ko_patch_cred / ko_patch_noenforce / ko_vermagic / ko_patchsyms） |
| `t64560_v36` | 不同（本地 `05e0388b…`，上游 `ae0961b5…`） |
| `syms.txt` | 不同（本地 6,247,237 B；上游 6,534,125 B） |
| 官方 ksud 之外的 3 份 ksud、4 份 ko | 上游独有；本地模板来源为官方 release ko + 本地补丁链 |

---

## 2. 差异矩阵（按主题）

| # | 主题 | 本地 v2.3.3 | 上游 fork | 谁更好 / 备注 |
|---|---|---|---|---|
| 2.1 | 仓库关系 | 原仓库，v2.3.3 未提交 | 独立仓库、非 fork | 不能 merge |
| 2.2 | 特权后端 | Stellar（ColorOS 17 实测可用） | Shizuku（未注明 ColorOS 限制） | 本地有明确原因，勿盲换 |
| 2.3 | UI/构建 | Java + aapt2，无 AndroidX，9.3 MB | Compose/Miuix + Gradle，22 MB | 本地构建可复现性依赖 `/tmp/stellar-api` 与 `/tmp/kotlinlib`，也需修 |
| 2.4 | 管理器数量 | 2 | 4 | 上游多两个；但 3/4 管理器资产未打 vermagic、Next LKM 版本 3.4.0 与 ksud 3.4.2 不配套 |
| 2.5 | 管理器校验 | 包名 + versionName + 签名 SHA-256 + 内核 UAPI/version 双重校验；不匹配不安装 | 只显示已装版本，无任何阻断 | **保留本地** |
| 2.6 | ksud 来源 | 运行时从已装管理器 APK 提取 `lib/arm64-v8a/libksud.so`（版本天然匹配） | 打包 4 份 ksud（官方 4.99 MB / Next 7.06 MB / BakaSU 5.71 MB / SukiSU 5.52 MB） | 本地更省体积；需确认 BakaSU 管理器是否也带 libksud.so |
| 2.7 | LKM vermagic | 模板已精确改为设备 vermagic | 仅官方 KernelSU ko 改过，另 3 份未改 | **上游资产不可直接搬** |
| 2.8 | LKM 符号补丁 | App 内 Java（`Prep.patchKo`），模板内保留 UNDEF，运行时按 slide 写 SHN_ABS | 设备端 C `prep_patch`（先校验 slide 非 0 且 2 MB 对齐） | 等价；上游多一道 slide 合法性检查（可移植到 Java） |
| 2.9 | slide 求解 | App 内 Java `Prep.solveSlideBytes`：需要 **≥3 票且唯一**，否则中止；`selinux_state` 从 syms.txt 取 | 设备端 C `prep_solve`：只取最大票（**无 3 票阈值、不拒绝并列**），`SELINUX_STATE_LINK` 硬编码 `0xffffffc082979630` | **保留本地**；上游更激进/更脆 |
| 2.10 | 设备端流程 | Java 编排（进度、日志、超时、Live 校验、修复模式、root 诊断） | `run.sh` 六步 + 日志轮询；日志落盘、失败点用退出码 | 本地可维护性更好；上游 shell 更易调试但无校验 |
| 2.11 | 部署目录 | 固定 `/data/local/tmp/onekey`（覆盖式） | `/data/local/tmp/pyb110-<boot_id>-<epoch>`（**每次 40.9 MiB，从不清理**） | 固定目录有残留问题但不会累积 40 MB/次 |
| 2.12 | 投放方式 | `cat > tmp` → 校验字节数 → `chmod` → 原子 `mv` | `cat > 目标`，只记录复制字节数，无校验、无原子替换 | **保留本地** |
| 2.13 | root 获取 | `ksu_grant`（reboot magic 装 ksu fd → `KSU_IOCTL_GRANT_ROOT` → root）→ exec `root_stage.sh` | 公开 `t64560_loader2`：`stage_ksud` 铺 `/data/adb/ksud` + `finit_module(…,"allow_shell=1")`；之后纯 `su -c` | 两条路都依赖 `allow_shell=1`（本地还带 `bundled=1`） |
| 2.14 | 用户态安装 | `/data/local/tmp/onekey/ksud_manager`（从管理器 APK 提取）→ 校验 → `ksud install` → 建 `/data/adb/ksu/bin/{su,ksud}` 链接 | 固定 `/data/local/tmp/ksud_official` → `su -c "… install"` | 本地严格；上游 loader2 里固定路径 `/data/local/tmp/ksud_official` 值得借鉴 |
| 2.15 | 管理器加冕 | Next：ko cred 补丁（`track_throne` 用 `init_cred`）；SukiSU：`ksud manager add` + `set-enabled` + `manager list` 回读 | `chmod 644 /data/system/packages.list` + `mv` 回环触发 `pkg_observer` | 本地不动系统文件权限，更干净 |
| 2.16 | su_compat / 权限刷新 | 显式 `feature set su_compat 1` + `debug mark refresh`（只按现有授权刷新） | 不做（依赖默认 `su_compat=true`） | 本地更明确 |
| 2.17 | 默认 su 路径兼容 | `su_path_compat.sh` + `su_forward`（直接 execve `/system/bin/su` 绕过 shell 预检查）→ 在 `/system/xbin` 或 `/system_ext/bin` 上挂只读 overlay（source 名 `onekey-su`，`su -M` 全局挂载，**直到重启**） | 不做；仅绝对路径/默认路径调用 `su` | 本地解决了一个真实问题（MT 终端等），但**引入可检测痕迹**（见 §4） |
| 2.18 | 反 root 检测/守卫 | **无** | 有：`pm disable-user --user 0 com.oplus.exsystemservice` → `pkill -9 -f` → `sleep 5` → `pm enable`；UI 开关默认开 | **上游唯一全新能力**，建议移植（见 §3.7） |
| 2.19 | SELinux 终态 | Next：Permissive（noenforce NOP）；SukiSU：Enforcing（模板未打 noenforce） | 全部 Enforcing（未打补丁，模块 init 自己 `setenforce(true)`） | 本地 Next 路径是隐患 |
| 2.20 | 隐藏/反检测 | 无（overlay 常驻 + `/data/local/tmp/onekey` 0755 可读 + 固定名 `/data/local/tmp/ksu-op3.ko`、`/data/local/tmp/ksud342`） | 无（固定名 `/data/local/tmp/ksud_official`、`ksu-official.ko`、`ROOTED2`、`CMD`、`CMD.run`、`cand_*.pid`、`self.cred`、`rsh2.out` 等，loader2 遗留） | 都该改进（见 §4、§5） |
| 2.21 | 异常/健壮性 | 竞态期间被动等待（不 fork shell 轮询）、模块必须 Live、命令超时、唯一 runId、失败不写成功文件 | 每 5s `grep /proc/modules`（外部 grep，会 fork）、su 失败仍 `echo DONE`、无 module Live 校验、无结果唯一性校验 | **保留本地** |
| 2.22 | 测试 | 4 组（root_stage mock、诊断、Prep、Stellar 进程语义） | 0 | 保留本地 |
| 2.23 | 发布签名 | debug.keystore | release keystore.properties + R8 | 本地发布应换固定 release key（否则升级需卸载） |

---

## 3. 逐项详解

### 3.1 上游"新增/修复"清单（对应任务问题 1）

按代码/脚本级列出，便于后续移植：

| # | 上游新增/修复 | 位置 | 本地现状 | 价值 |
|---|---|---|---|---|
| A | **反 root 守卫**：ColorOS `com.oplus.exsystemservice` 检测到提权后弹窗并 15s 后 `reboot("Malicious app try to root devices")`；开关开启时 disable→kill→5s→enable | `/tmp/yunni/app/app/src/main/assets/pyb110/run.sh` L20-30（ALREADY_LOADED 分支）、L98-112（正常分支）；UI 开关 `MainActivity.kt` L290-306、L446-450，默认 true（L83、L106） | **完全没有**（本地 README 把"自动黑屏重启"归因于竞态，v1.8 已降低概率，但没有这个守卫） | 高（可能是"越狱后短时间内重启"的真因之一） |
| B | **官方 KernelSU + BakaSU 两种管理器**（4 选 1 下拉） | `MainActivity.kt` L662-667 `ManagerKind`；`run.sh` L11-16 `case "$MANAGER"` | 只有 Next/SukiSU | 中（需自编/重打 2 份 LKM） |
| C | **LKM 源码自编配方**：GKI 6.18.21 + KDIR + DDK 容器；官方需关 LTO、NOP `check_exports`；BakaSU 需 `CONFIG_KSU_MULTI_MANAGER_SUPPORT=y` + `CONFIG_KSU_TRACEPOINT_HOOK=y` | `/tmp/yunni/lkm/build-kernelsu.sh`、`build-next.sh`、`build-bakasu.sh`、`build-sukisu.sh`、`lkm/README.md` | 无（本地用官方 release ko 打补丁）；`lkm/` 目录整体缺失 | 高（新增管理器的前置） |
| D | **设备端 C 工具**：`prep_solve`（slide 投票）、`prep_patch`（UNDEF→ABS）、`t64560_w2`（两阶段 cred 写） | `/tmp/yunni/exploit/src/*.c` | 求解/打补丁在 Java（`Prep.java`），cred 链用公开 `t64560_v36/v42` + 自研 `perf_cred*` | 低（本地 Java 更严）；`prep_patch` 的 slide 校验可借 |
| E | **设备端六步 shell 流程 + ALREADY_LOADED 快速修复** | `run.sh` 全文 | Java 编排 + 修复模式（更严） | 低 |
| F | **每 run 部署目录（boot_id+epoch）** | `MainActivity.kt` L571-586 | 固定 `/data/local/tmp/onekey` | 低（且上游从不清理，40.9 MiB/次） |
| G | **4 个 ksud 打包 + 4 个 ko 打包** | `assets/pyb110/` | 运行时从管理器 APK 提取 ksud | 负面（APK 22 MB、投放 41 MB/次） |
| H | **Gradle/Compose/Miuix + R8 release 签名** | `app/` | aapt2 + Java Views + debug key | 中（UI 方向另见 task-2） |
| I | **日志读取线程异常兜底**（commit `2511f74`：close 后 `InterruptedIOException` 捕获 `Throwable`） | `MainActivity.kt` L628-655 | `ProcessRunner.drain` 已 `catch (Exception)`，等价；可对齐为 `Throwable` | 低（微加固） |
| J | **SukiSU LKM 固定 v4.2.1/uapi5**（commit `4ab57fd`，445,200→376,136 B） | `assets/pyb110/ksu-base-sukisu.ko`、`lkm/README.md` | 本地已固定 SukiSU v4.2.1/40994/uapi5 | 已对齐 |

**上游没有做的（容易误以为有）**：无 mount/进程/属性隐藏；无 allowlist 预授权或自动授权；无 `selinux_hide` / `kernel_umount` 显式设置；无 su 路径兼容；无 App 侧 root 诊断；无管理器签名校验；无测试。

### 3.2 LKM 事实与可加载性（重点：上游资产陷阱）

上游 4 份 ko 的 `.modinfo`（pyelftools 实测，非 strings 猜测）：

| 文件 | 大小 | `.modinfo` 内容 | 符号态 | 备注 |
|---|---|---|---|---|
| `ksu-base.ko` | 349,224 | `zermagic=6.18.21-4k …` + `vermagic=6.18.21-android17-5-gf957accaf563-ab15926176-4k SMP preempt mod_unload modversions aarch64` | UNDEF≈226 / ABS 28 | **已用 ko_vermagic 手法改过 vermagic**（官方 KernelSU，版本串不可见） |
| `ksu-base-next.ko` | 350,000 | `vermagic=6.18.21-4k SMP …` | UNDEF≈220 / ABS 27 | **未改 vermagic**；LKM 版本 `v3.4.0`（与 ksud_next 3.4.2 不配套） |
| `ksu-base-bakasu.ko` | 385,440 | `vermagic=6.18.21-4k SMP …` | UNDEF≈228 / ABS 28 | **未改 vermagic**；`v4.2.0-rc3-7c383cfe-dirty@BakaSU` |
| `ksu-base-sukisu.ko` | 376,136 | `vermagic=6.18.21-4k SMP …` | UNDEF≈238 / ABS 28 | **未改 vermagic**；`v4.2.1-fd2a8515@HEAD` |

本机结论（`docs/158`、`docs/162`、`docs/163` 已实测/静态校验）：
- PYB110 内核 `CONFIG_MODULE_FORCE_LOAD` 未设 ⇒ `MODULE_INIT_IGNORE_VERMAGIC` 不可用，vermagic 必须逐字节等于 `6.18.21-android17-5-gf957accaf563-ab15926176-4k SMP preempt mod_unload modversions aarch64`。
- 上游 `run.sh` 只调用 `prep_patch` 打符号（`prep_patch.c` 只改 `.symtab`），**没有任何 vermagic 补丁步骤**；`t64560_loader2` 里也没有 vermagic 相关字符串。
- ⇒ 上游仓库里的 Next/BakaSU/SukiSU 三份 ko **在 PYB110 上应当加载失败**。若上游作者确在真机成功，说明其实际运行的是另行改过 vermagic 的 ko（未提交），或还有仓库外步骤。**移植时必须重新打 vermagic（本地 `tools/ko_vermagic.py` 即可）。**

本地模板对照（同一套字节级检查）：

| 文件 | 大小 | vermagic | noenforce（`.init.text+0x148`） | cred 补丁（`.text+0x5FC8`=`0x91000108` add x8,x8,#0 + symtab 含 `init_cred`） |
|---|---|---|---|---|
| `app/assets/ksu-template.ko`（Next） | 531,936 | 已改本机 | **是**（`1f2003d5` NOP） | **是** |
| `app/assets/sukisu-template.ko` | 406,294 | 已改本机 | 否（`94000000` bl） | 否（SukiSU 走 `ksud manager add`） |

⇒ **本地 Next 路径越狱成功后 SELinux 停在 Permissive**（模块自己 `setenforce(true)` 被 NOP）；SukiSU 路径是 Enforcing（与当前真机一致）。上游统一 Enforcing。
⇒ 建议：若 Permissive 不是 SU 可用性的必要条件（`docs/163` §1.3 认为 Enforcing 下 sucompat 可用），移除 Next 模板的 noenforce，把终态统一到 Enforcing；如担心自定义 root 配置场景，可在 root 阶段末尾显式 `setenforce 1` 并验证 `su` 仍可用。

版本一致性旁证（真机 detection-audit）：当前设备 `/system/bin/su --version` 输出 `4.2.1-2-gdfae9237:KernelSU`，与本地 `sukisu-template.ko` 的版本串 `v4.2.1-dfae9237@main` 同一 git 提交；说明设备上跑的正是本仓库 SukiSU 模板（未打 noenforce）⇒ 当前真机 Enforcing 与静态分析一致。

### 3.3 root 获取与用户态安装链对比

**本地（v2.3.3）**
```
App(Java) → Stellar shell(uid2000)
  1 grab.sh 抓 sched_blocked_reason
  2 App 内 Prep.solveSlideBytes → slide/selinux_state（≥3 票唯一）
  3 App 内 Prep.patchKo 打符号 → /data/local/tmp/ksu-op3.ko
  4 t64560_v36 零写 selinux_state-7 → Permissive
  5 perf_cred/ perf_cred_sukisu 泄漏 cred → t64560_v36 零写 fsuid → t64560_v42 写 cap_effective
     （perf_cred* 在 fsuid=0 窗口里：建 /data/adb/ksu/bin、把 ksud 从 /data/local/tmp/{ksud342|onekey/ksud_sukisu} 复制到 /data/adb/ksud、finit_module(…,"allow_shell=1 bundled=1")）
  6 App 跑 ksu_grant（reboot magic 装 fd → GRANT_ROOT → root）→ exec root_stage_mobile.sh
  7 root_stage_mobile.sh：校验 manager + ksud（debug info 的 uapi/version）
       → ksud install → 建 /data/adb/ksu/bin/{su,ksud} 链接
       → （SukiSU）ksud debug get-sign 校验管理器签名 + ksud manager add/set-enabled/list 回读
       → feature set su_compat 1 → debug mark refresh
       → 三路 su 自检（绝对路径 / stdin 管道 / -M 全局挂载）
       → su_path_compat.sh（默认 su 入口 overlay）
       → （SukiSU）重启管理器并检查进程是否持有 [ksu_driver] fd
       → 写 ROOT_STAGE_OK:<runId>
```

**上游**
```
App(Kotlin) → Shizuku shell(uid2000) → /data/local/tmp/pyb110-<id>/run.sh
  [1] take_slide.sh → [2] prep_solve → [3] prep_patch → ksu-official.ko
  [4] t64560_v36 零写 → Permissive
  [5] t64560_loader2 --target（leak cred；stage_ksud → /data/adb/ksud；finit_module(…,allow_shell=1)）
      + t64560_w2 两阶段写 → 等 /proc/modules 出现 kernelsu
  [6] 反 root 守卫（可选）→ su -c "/data/local/tmp/ksud_official install"
      → su -c "chmod 644 /data/system/packages.list; mv 往返"  ← 加冕
      → am force-stop + monkey 启动管理器
      → 打印 id/getenforce/ksud debug version（不判定失败）→ echo DONE
```

差异要点：
- 上游把"铺 /data/adb/ksud"放在 loader2 里（公开二进制内置 `stage_ksud`），本地放在自研 `perf_cred*` + `ksu_grant` 后；本地对结果验证更严。
- 上游加冕依赖 `chmod 644`（改系统文件权限、瞬时生效、system_server 重写文件后可能回 0640），本地靠 ko cred 补丁 / `ksud manager add`，不动系统文件。
- 上游 `run.sh` 无论 `su` 是否可用都输出 `DONE`，App 只以退出码/日志展示；本地以 `ROOT_STAGE_OK:<runId>` + 三路 su 自检判定成功。

### 3.4 上游反 root 守卫（建议移植，但需加固）

上游逻辑（`run.sh` L98-112，ALREADY_LOADED 分支 L20-30 重复一份）：
```sh
if [ "$DISABLE_ANTIROOT" = "1" ]; then
  su -c "pm disable-user --user 0 com.oplus.exsystemservice"; echo "DISABLE_RC=$?"
  su -c "pkill -9 -f com.oplus.exsystemservice";             echo "KILL_RC=$?"
  sleep 5
  su -c "pm enable com.oplus.exsystemservice";               echo "ANTIROOT_RESTORED: ..."
fi
```
上游 README 的解释：ColorOS `com.oplus.exsystemservice` 检测到提权后弹窗并 15s 后 `reboot("Malicious app try to root devices")`；禁用太久会导致系统截图不可用，所以只禁用 5s 再恢复。

移植时必须补的加固（上游实现有三个坑）：
1. **`pm disable-user` 是持久化的**：如果 root shell 在 5s 内被杀/异常，组件会一直禁用（重启也保持）⇒ 需要 `trap` 恢复 + 独立 watchdog（例如后台 `(sleep 15; su -c 'pm enable --user 0 com.oplus.exsystemservice') &`）+ App 启动时自检（发现处于 disabled 就恢复）。
2. **恢复命令不对称**：禁用用 `--user 0`，恢复用 `pm enable`（等价默认当前用户，但建议显式 `--user 0`）。
3. **重复代码**：两处分支相同逻辑，应抽成一个 shell 函数/单独脚本，便于测试（可参考 `tests/test_root_stage.py` 的 mock 方式加用例）。
4. 建议做成**可开关**（默认开，但先检测 `pm list packages com.oplus.exsystemservice`，不存在时直接跳过，避免其他 ROM 空转），开关持久化到 SharedPreferences，并把 env 传给 root 脚本。
5. 失败不应阻塞主流程（守卫是"尽力而为"），但要在日志中显式记录 rc 与是否恢复。

验证方法（真机，只读观测）：
- 越狱完成后等 60–120s 不重启；`adb shell pm list packages -d | grep exsystemservice` 应为空；截图功能正常；日志含 `ANTIROOT_RESTORED`。
- 关闭开关再跑一轮，确认能复现上游说的弹窗/15s 重启（作为对照，务必在可承受重启的时机做）。

### 3.5 默认 SU 权限检查 / allowlist / su 路径

| 检查项 | 本地 | 上游 |
|---|---|---|
| 内核侧默认 su 能力 | `allow_shell=1`（本地还加 `bundled=1`；`bundled` 只影响 GET_INFO flag，见 `docs/163`） | `allow_shell=1`（loader2 字符串） |
| su_compat | 显式 `feature set su_compat 1`（默认也是 true，但本地写死） | 不设置（依赖默认 true） |
| allowlist / 白名单 | **不做预授权**，只 `debug mark refresh` 刷新已有授权进程 | 不做 |
| 管理器加冕 | Next：cred 补丁走 `track_throne`；SukiSU：`ksud manager add` 显式登记 + 回读 | `chmod 644 packages.list` + `mv` 回环 |
| 默认 su 搜索入口 | `su_path_compat.sh`：overlay `/system/xbin` 或 `/system_ext/bin`（用 `/system/bin/su -M` 全局挂载，重启后消失） | 不做；依赖 KSU 的 sucompat 虚拟 `/system/bin/su` |
| su 自检 | 绝对路径 + stdin 管道 + `-M` 三路，全部 `ROOT_UID=0` 且 stdout/stderr 都通才成功 | `su -c "id; getenforce; …" 2>&1` 只打印，不判定 |
| App UID 视角诊断 | `RootDiagnostics` + "root 诊断"按钮（绝对路径/交互/全局挂载/默认入口 + PRIVATE_READ/STORAGE_READ） | 无 |

结论：**默认 SU 权限检查方面本地全面领先**；上游没有任何 App 侧或脚本侧判定，`DONE` 不代表 su 可用。移植时不要用上游的 `run.sh` 收尾逻辑替代本地 `root_stage_mobile.sh`。

### 3.6 隐藏与检测面（不含 overlay 的通用差异）

| 痕迹 | 本地 v2.3.3 | 上游 |
|---|---|---|
| `/proc/modules` `kernelsu` 行 | 有（模块未 hide；manager 依赖它） | 有（同样未 hide） |
| `/system/bin/su` | KSU sucompat 虚拟路径（KSU 自身行为） | 同 |
| 额外 su 文件 | **`/system_ext/bin/su`（或 /system/xbin）真实文件 = su_forward，9,056 B，可通过 overlay 全局可见** | 无 |
| 额外 mount | **`overlay onekey-su` on `/system_ext/bin`，lowerdir 指向 `/data/adb/onekey-su-compat/bin`，直到重启** | 无 |
| `/data/local/tmp` 固定名残留 | `/data/local/tmp/onekey/`（0755**普通 App 可 ls/读**，真机实测 43 个条目）内：全工具 + `root_stage.log`（含 uid=0/ksu:Live/管理器登记）+ `bl_*.bin` 原始抓包（约 28 MB，未清理）+ `manager.apk` + 脚本；`/data/local/tmp/ksu-op3.ko`（644，已知名可读 ELF）；`/data/local/tmp/ksud342`（755）；`su_forward`、`su_path_compat.sh` 副本 | 固定名 `/data/local/tmp/ksud_official`（4.99 MB，755）、`/data/local/tmp/ksu-official.ko`（644）、`ROOTED2`、`KSU_RESULT2`、`CMD`、`CMD.run`、`self.cred`、`self.ready`、`cand_*.pid`、`rsh2.out`、`NOLOAD`、`DOLOAD` + 每次 40.9 MiB 的 `pyb110-<boot_id>-<epoch>/`（不清理） |
| `/data/adb` | `/data/adb/ksud`、`/data/adb/ksu/bin/{su,ksud,busybox,bootctl,resetprop}`、`/data/adb/onekey-su-compat/bin/su`（0700 目录，App 不可读） | `/data/adb/ksud`、`/data/adb/ksu/bin/…` |
| SELinux 终态 | Next=Permissive（强特征）/ SukiSU=Enforcing | Enforcing |
| KSU 内建隐藏 feature | 未设置（`selinux_hide`、`kernel_umount` 都是 DISABLED/ENABLED 默认值） | 未设置 |

> 两者都没做隐藏，但本地多了一条"全局 overlay + 真实 su 文件"，详见 §4。上游的 `/data/local/tmp` 固定名残留同样可被 App 按已知路径 stat/读取（`/data/local/tmp` 是 0771，App 不能列目录但能穿过已知路径），只是它没有 overlay 那条最显眼的。

### 3.7 健壮性 / 安全校验对比（不要被上游替换的部分）

| 能力 | 本地 | 上游 |
|---|---|---|
| 管理器安装/版本/签名 | 包名 + versionName + 证书 SHA-256；Next 3.4.2 / SukiSU v4.2.1 精确匹配 | 无（只显示版本） |
| 内核 UAPI/version | `ksud debug info` 必须 `uapi_version: 5` 且 version 匹配所选 backend，否则中止 | 无 |
| 安装结果判定 | `ksud install` rc + `/data/adb/ksud` 存在 + su 三路自检 + 唯一 runId | 无（打印后继续） |
| 组件投放 | 写 tmp → 校验 `wc -c` 等于 asset 长度 → chmod → `mv -f` 原子替换 | `cat > 目标` 直写 |
| 命令超时 | 全部 `timeout`（15～1500s） | 部分（无统一超时；轮询 sleep） |
| 竞态期行为 | 被动等待 loader 输出，不 fork shell 去 `grep /proc/modules`（v1.8 修复） | 每 5s `grep -qi kernelsu /proc/modules`（每次 fork 外部 grep；扰动较小但非零） |
| 模块状态 | `/proc/modules` 行必须 `Live`，并等 2s 再 GRANT_ROOT | 只等文件出现/LOADED_SLEEP |
| 失败处理 | 非零退出码/管道失败/UID≠0 一律不写成功文件 | `echo DONE` 与失败无关 |
| 测试 | 4 组（含 mock 真实脚本） | 0 |

---

## 4. 专答：本地 `/system_ext/bin/su` + `onekey-su` overlay 是不是可被 App 检测的 root 痕迹？

### 4.1 结论

**是，而且是当前最容易被检测的痕迹之一。** 它不是"只在 root 命名空间可见"的实现细节：`onekey-su` overlay 挂在 init mount namespace 上并以 shared propagation 传播，**普通 App 进程的 `/proc/self/mountinfo` 里直接可见**；`/system_ext/bin/su` 因此成为真实存在、可 stat/可读/可执行的文件（真机 `run-as` + `untrusted_app` 复核，见 §4.3）。对比之下，上游方案里普通 App 对 `/system/bin/su` 是 ENOENT —— **本地的 overlay 把"su 文件存在"这个检测点从 App 不可见变成了 App 可见**。

### 4.2 代码路径

- `OnePlus16-Jailbreak/app/assets/su_path_compat.sh`
  - L7-14：先 probe（`command -v su` + `su -c 'id -u'`），已通过则退出；
  - L17-30：挑 `/system/xbin` 或 `/system_ext/bin`（存在、非软链、无既有 su、非挂载点/子挂载）；
  - L32-43：`base=/data/adb/onekey-su-compat`，把 `su_forward` 复制成 `$base/bin/su`（chmod 755，chcon 继承目标目录 label）；
  - **L45：`mount -t overlay onekey-su -o "ro,lowerdir=$base/bin:$target" "$target"`**；
  - L49-56：probe 失败才 umount；**成功后不撤销**，注释明说"重启后自动消失"。
- 调用点：`OnePlus16-Jailbreak/app/assets/root_stage_mobile.sh` L93 `timeout 30 /system/bin/su -M -c "/system/bin/sh '$D/su_path_compat.sh'"`（`-M` = 全局/init mount namespace）。
- `su_forward.S`：直接 `execve("/system/bin/su")`，绕过 shell 对虚拟路径的 `faccessat` 预检查；失败时打印中文串 `su: 内核接口未允许执行 /system/bin/su`。

### 4.3 真机证据（本次工作区已抓取，detection-audit 提供）

`evidence/detection-audit-2.3.3/mountinfo.*.txt`（22:50，5 个视角）**全部**包含同一行：

- `mountinfo.init.txt` L213：`… /system_ext/bin ro,relatime shared:102 - overlay onekey-su ro,seclabel,lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin,redirect_dir=on`
- `mountinfo.shell.txt` L213（adb shell, uid 2000）：同上（shared:102）
- `mountinfo.gallery.txt` L73（普通 App）：`master:102`
- `mountinfo.systemui.txt` L59（系统 App）：`master:102`
- `mountinfo.manager.txt` L73（KernelSU 管理器）：`master:102`

⇒ 该挂载在 init 命名空间创建并向下传播，**所有 App 命名空间的 /proc/self/mountinfo 都能看到**，包括 source 名 `onekey-su` 和 lowerdir 里的 `/data/adb` 路径。这不是"只有 root 才能看到"的痕迹。

detection-audit 进一步用 `run-as`（uid 10360）+ `u:r:untrusted_app:s0` 复核（原始输出 `evidence/detection-audit-2.3.3/detection_audit.out`）：

- **普通 App 可 stat/读/执行 `/system_ext/bin/su`**：regular file 9056 B、755、root:root、`u:object_r:system_file:s0`、md5 `709b42b0b88e9d49cf2e8f799cf934bd`（== `app/assets/su_forward`）；执行返回 exit 127 + `su: 内核接口未允许执行 /system/bin/su`。
- **普通 App 的 `/proc/mounts`、`mount`、`df` 都把 `onekey-su` 当文件系统显示**；`statfs("/system_ext/bin")` 返回 overlay 魔数 `0x794c7630`；`/system_ext/bin` 目录 inode=2、mtime 2026-10-20 13:09（与 /system_ext 的 10-09 不同）。
- **普通 App 的 PATH 含 `/system_ext/bin`**，`command -v su` 解析到 `/system_ext/bin/su`。
- KSU 虚拟的 `/system/bin/su` 对普通 App 是 **ENOENT**（`stat`/`cat` 都是 No such file）；只有白名单 uid（shell/已授权 App）才能看到伪造的 5,522,800 B（=/data/adb/ksud 大小）`su`。即：**overlay 反而让"su 文件存在"这个检测点对普通 App 从不可见变成可见**。
- `/data/adb/onekey-su-compat`：0700 root:root，普通 App EACCES；但路径已被 mountinfo 泄露。
- **额外大泄露**：`/data/local/tmp/onekey` 目录 0755、文件 0644，普通 App 可 `ls`/读（43 个条目）：`root_stage.log`（含 uid=0、`kernelsu … Live`、管理器登记）、`manager.apk`、`su_path_compat.sh`、exploit 二进制、`ksu-op3.ko`（ELF 可读）、`bl_*.bin` 原始抓包（约 28 MB 未清理）。

### 4.4 可用的检测面（按可靠性排序，均已在普通 App uid 下实测）

1. **mountinfo/mounts/mount/df**：`grep -c onekey /proc/self/mountinfo`、`grep -E 'overlay .*onekey' /proc/self/mounts`；mount source 名 `onekey-su` 是自报家门的字符串；lowerdir 暴露 `/data/adb`（正常设备绝不能有 `/data/adb` 路径出现在系统目录的挂载参数里）。这也是反作弊/银行类 App 最常见的 root 检查项之一（"系统分区出现非官方 overlay"）。
2. **`statfs("/system_ext/bin")`**：返回 overlay（`0x794c7630`），stock 应为 erofs；零权限。
3. **`/system_ext/bin/su` 已知路径探测**：`stat`（9056 B/755/system_file）、`open` 读 ELF 魔数、`Access` 执行。**注意 App 无法 `ls` 目录**（`/system_ext/bin` 是 751），只能按已知路径命中；但对检测方来说这已足够。
4. **`command -v su` / PATH**：App 的 PATH 本身包含 `/system_ext/bin` ⇒ 解析到 `/system_ext/bin/su`（stock 设备上 `command -v su` 应为空/ENOENT）。
5. **执行指纹**：App 直接 exec `/system_ext/bin/su` 会得到 exit 127 + 中文错误串 `su: 内核接口未允许执行 /system/bin/su`；该 9056 B 二进制与 `app/assets/su_forward` 同 md5，是明显工具指纹。
6. **目录元数据**：`/system_ext/bin` 的 inode=2、mtime=2026-10-20 13:09 与 /system_ext 其他目录不一致（检测方会做 `/system_ext` 全目录元数据基线）。
7. **"su 文件存在"对比**：`/system/bin/su` 对普通 App ENOENT，而 `/system_ext/bin/su` 可命中 ⇒ 这个不匹配本身就是强信号。
8. **`/data/adb/onekey-su-compat`**：App 不可读，但 mountinfo 已泄露路径；检测方据此可判定"存在 root 工具目录"。
9. **`/data/local/tmp/onekey` 工作区**：App 可直接读全部工具与日志（见 §4.3），比 overlay 更容易取证（拿到 `root_stage.log` 可直接证明 root 环境检查通过）。

> 更正：本文早期草稿曾把"App 可 `ls /system_ext/bin` 看到 su"列为检测点——实测目录权限 751，App 只能按已知路径 stat，不能列举。

### 4.5 上游如何处理 / 是否引入同类问题

- 上游**不创建** su 路径 overlay（`run.sh` 全部走 `su -c`，没有 mount 系统目录的操作），因此**没有**这条痕迹。
- 但上游**也没有任何隐藏**：`/data/local/tmp` 固定名产物（`ksud_official` 4.99 MB、`ksu-official.ko`、`ROOTED2`、`CMD.run` 等）可被 App 按已知路径 stat/读；每 run 的 `pyb110-*` 目录 40.9 MiB 不清理；没有 mount/属性 hide、没有 `selinux_hide`。
- 所以"他有没有处理同类问题"的答案是：**躲开了 su overlay 这一条，但没有做反检测**；而它自己的 `/data/local/tmp` 残留是另一类可检测痕迹。

当前真机状态（detection-audit，2026-10-20 22:57）：SukiSU 管理器 `com.sukisu.ultra`（uid 10538）、本 App `dev.onekey.jb`（uid 10364）、`getenforce=Enforcing`、`/proc/modules` 中 `kernelsu 204800 4 - Live`（仅 shell/root 可见）、模块无 `/sys/module/kernelsu`、无 ksu 属性、`hidepid=invisible`、`ro.build.tags=release-keys`/`verifiedboot=green`。普通 App 无 `QUERY_ALL_PACKAGES` 时看不到 com.sukisu.ultra/dev.onekey.jb，但装一个带该权限的检测 App 即可枚举。

detection-audit 的检测面矩阵结论（普通 App 视角）：**零权限可稳定命中约 10 项**，其中 6 项直接来自 `onekey-su` overlay（mountinfo/mounts/mount/df/statfs/PATH/stat/exec/目录 mtime），1 项来自 `/data/local/tmp/onekey` 整目录可读；**看不到**的是 `/system/bin/su`（ENOENT）、`/data/adb`（EACCES）、`/proc/modules`（EACCES）、`/proc/kallsyms`、`dmesg`、`/sys/module/kernelsu`、`ps` 中的 su 进程、属性探测（都是 stock 值）。详细命令与逐条证据见 `docs/detection-audit.md`（task-3 产出）与 `evidence/detection-audit-2.3.3/`。

### 4.6 建议（结合用户目标：先能稳定越狱，再减少检测面）

- **方案 A（推荐，低成本）**：把 `su_path_compat.sh` 从"每次自动执行"降级为"手动/按需执行"的修复项（例如 App 的"root 诊断"里增加一个"修复默认 su 入口"按钮），默认不挂载；文档明确"MT 等终端请先用 `/system/bin/su -c id`"。若绝大多数目标应用用绝对路径或自身 root 授权即可，这条挂载可以不必要。
- **方案 B（保留自动，但可撤销）**：保留自动挂载，但 (1) 在脚本 `trap` 里登记；(2) App 退出/完成 5 分钟后由 root 命令 `umount /system_ext/bin`（需要保留一个 root 通道，或者写在脚本末尾"延迟 umount"）；(3) 增加"撤销默认 su 入口"按钮。**注意**：只要挂载存在就有痕迹，延迟撤销只缩短暴露窗口。
- **方案 C（不挂载）**：直接删掉该脚本与 su_forward，回归 KSU 原生 sucompat；风险是 MT 终端等依赖 PATH 搜索的应用仍可能失败（这正是当初引入它的原因），需先用现有"root 诊断"复测确认是否真的必需。
- **无论选哪种**，建议额外做（低风险、纯减法）：
  - `/data/local/tmp/onekey` 改为 0700、内部文件 0700/0600（loader 以 uid2000 运行，owner 可执行，不受影响；App 侧不再可读，但如果未来需要 App 读取日志则改回）；
  - 结束后删除 `/data/local/tmp/ksu-op3.ko`、`/data/local/tmp/ksud342`、`su_forward`、`bl_*.bin`（约 28 MB 原始抓包）、`manager.apk` 等固定名/大文件（或统一 bootstrap 路径并在使用后删除）；
  - 避免把 `root_stage.ok`、`root_stage.log` 长期留在可读目录（或改权限），日志里含 uid=0/Live/管理器登记信息，等于把 root 自证材料留在 App 可读路径。

---

## 5. 可移植清单（文件 / 函数 / 脚本级）

> 前提：先把本地 v2.3.3 工作树提交（`git add -A && git commit`），否则任何移植都叠在不可追溯的工作树上。建议按 P0→P1→P2 分 commit，每个 commit 都能独立构建 + 跑 `tests/`。

### P0（低风险、收益明确）

| # | 改动 | 目标文件/函数 | 上游参考 | 工作量 | 风险 | 验证 |
|---|---|---|---|---|---|---|
| P0-1 | **反 root 守卫**（可开关，默认检测到 `com.oplus.exsystemservice` 才启用；含 trap/watchdog 恢复） | `app/assets/root_stage_mobile.sh`（新增 `antiroot_guard()`，在 `ksud install` 前后调用）；`app/src/dev/onekey/jb/MainActivity.java`（开关 + PREF + 传 env；启动时自检恢复）；可加 `tests/test_root_stage.py` 用例 | `/tmp/yunni/app/app/src/main/assets/pyb110/run.sh` L98-112 / L20-30 | 0.5–1 天 | 中：`pm disable-user` 持久化，恢复失败会让系统组件长期禁用（截图等异常）；需 watchdog + 启动自检 + 文档化回滚 `pm enable --user 0 com.oplus.exsystemservice` | 真机：60–120s 不重启；`pm list packages -d` 无该项；截图正常；日志含恢复成功 |
| P0-2 | **`Prep.patchKo` slide 合法性守卫**：slide 非 0 且 2 MB 对齐才继续；patched>0 已有调用方判定 | `app/src/dev/onekey/jb/Prep.java` `patchKo`；`tests/PrepTest.java` 加用例 | `/tmp/yunni/exploit/src/prep_patch.c` L130-141 | 0.5 小时 | 极低 | 单测 + 构建 |
| P0-3 | **bootstrap ksud / ko 路径统一**：把 loader 里硬编码的 `/data/local/tmp/ksud342`、`/data/local/tmp/onekey/ksud_sukisu`、`/data/local/tmp/ksu-op3.ko` 统一为 `/data/local/tmp/onekey/ksud_bootstrap`、`/data/local/tmp/onekey/ksu-load.ko`，只投放一份 | `app/src/perf/perf_cred.c`、`perf_cred_sukisu.c`（常量）、`MainActivity.stageRootUserspace()`（去掉第二份 ksud 投放）、`runPipeline()` 的 ko 目标路径 | 上游 loader2 统一 `/data/local/tmp/ksud_official`（strings 证据），`run.sh` L20/L31 `cp -f "$KSUD" …` | 1 天（含 NDK 重编 + 设备回归） | 中：需重编两个静态 loader，路径错误会导致 su 不可用；需保留旧路径兼容一轮 | 真机：两种管理器各跑一次，su 三路通过；投放字节数下降约 5.5–7 MB |

### P1（功能增量，需真机验证）

| # | 改动 | 涉及文件 | 上游参考 | 工作量 | 风险 |
|---|---|---|---|---|---|
| P1-1 | **新增 KernelSU 官方 + BakaSU 两种管理器**：常量（包名/版本/证书 SHA-256）、UI 选择、`root_stage_mobile.sh` backend case（expected_uapi/version + 各自的加冕方式）、ksud 提取回退 | `MainActivity.java`（pkg/版本/签名常量、选择 UI、checkManager、managerActivity、restartManager）、`app/assets/root_stage_mobile.sh`、`app/assets/` 新增 2 个 ko 模板、`tests/` 扩展 | `run.sh` case 分支；`lkm/build-kernelsu.sh`、`build-bakasu.sh`；`ManagerKind` 四值 | 3–6 天 | 高：需要 2 份可加载 LKM（官方 ko 用补丁链或自编；BakaSU 必须 `CONFIG_KSU_MULTI_MANAGER_SUPPORT=y`+`CONFIG_KSU_TRACEPOINT_HOOK=y`）；加冕/su_compat 行为不同；切换管理器必须重启（rmmod 会 EAGAIN/kpanic，上游 README 已列）；管理器版本/签名常量需从官方 APK 实测取；BakaSU 管理器是否带 `libksud.so` 需确认（否则要加打包回退） |
| P1-2 | **su 路径兼容改造**（默认关闭 / 手动触发 / 可撤销；清理固定名残留） | `app/assets/su_path_compat.sh`、`root_stage_mobile.sh` L93、`MainActivity` root 诊断 UI | 上游"不做"的做法（作为对照） | 1–2 天 | 中：删掉后 MT 终端类应用可能仍失败；需要先复测确认必要性；延迟 umount 需 root 通道 |
| P1-3 | **减少可读残留**：`/data/local/tmp/onekey` 及固定名文件降权（0700/0600）+ 完成后清理 ko/ksud/`bl_*.bin`/`manager.apk`/`root_stage.log` | `ShizukuShell.stageScript()`（chmod 参数）、`root_stage_mobile.sh` 收尾、`MainActivity`（`bl_*.bin` 用完即删） | 上游无（反而更多残留）；这是本地自加的加固；真机实测普通 App 可 ls/读该目录（43 条目，含 `root_stage.log` 自证材料） | 0.5–1 天 | 低：注意 App 侧当前直接用 0755/0644 读 `bl_*.bin`/`bl_text.txt`（`ShizukuShell.readDeviceFile`），改 0700 后 App 自身（uid 非 shell）将无法直读 —— 需改为经 Stellar 读取（多一次 binder 往返）或仅对非抓包文件降权 |

### P2（可选/信息性）

- **LKM 构建文档化**：把上游 `lkm/README.md` 的环境（GKI `android17-6.18-2026-06_r9` / KDIR `gki_defconfig`+`modules_prepare`+`security/selinux/built-in.a` / DDK `ghcr.io/ylarod/ddk-min:android17-6.18-20260828`）、`scripts/config -d LTO_CLANG -e LTO_NONE`、modpost `check_exports` 放行、BakaSU 的 tracepoint hook 开关，整理进本地 docs（新增/扩展 `docs/`）。
- **构建/发布**：若未来迁 Gradle/Compose（见 task-2 的 COUI 方案），可参考上游的 AGP/Kotlin/Compose/Miuix 版本组合、`keystore.properties` release 签名、R8/资源压缩；但不要照搬"4 份 ksud 打包 + 41 MB 投放"，且本地当前 UI 功能（教程/日志分享/root 诊断/赞赏）比上游多，整体重写收益低。
- **微加固**：`ProcessRunner.drain` 的 `catch (Exception)` 对齐上游的 `catch (Throwable)`（防止 daemon 线程未捕获异常杀进程）。
- **文档**：把上游"换管理器必须重启、rmmod 会 kpanic/EAGAIN"的结论写进本地 README 的已知限制（本地目前没有明确写）。
- **上游 bug 修复记录**：commit `2511f74` 的日志线程兜底说明"close 流会让读线程抛 InterruptedIOException"，本地 `ProcessRunner` 已覆盖；无需移植。

### 明确**不建议**移植 / 反向警示

1. **上游 `prep_solve` 的判定逻辑**：只用最大票、不要求 ≥3 票、不拒绝并列、`SELINUX_STATE_LINK` 硬编码（`0xffffffc082979630`）。本地 Java 求解器更安全，保留。
2. **上游 `stageAssets` 的投放方式**：无长度校验、无原子替换、17 个文件逐个 `cat >`。保留本地 `stageScript` 的 tmp→校验→mv。
3. **上游的部署目录策略**：每秒 run 40.9 MiB 且从不清理；`/data/local/tmp` 会迅速膨胀（多次越狱后几十次 40 MB）。本地固定目录 + 覆盖式更好，若要唯一性再加"启动时清理旧目录"。
4. **打包 4 份 ksud**：APK 22 MB、投放 41 MB/次；本地"从已装管理器 APK 提取 libksud.so"更省，但需给 BakaSU 之类可能不带 libksud 的管理器准备回退。
5. **`chmod 644 /data/system/packages.list` 加冕作为默认**：改系统文件权限、瞬时、可能被 system_server 重写；本地已有 cred 补丁/manager add，保留本地方式，最多作为兜底。
6. **Next 模板的 noenforce 补丁**：除非确认必须，建议去掉，避免越狱后长期 Permissive（比 overlay 更明显的检测特征）。
7. **上游 `run.sh` 双份漂移**：`app/app/src/main/assets/pyb110/run.sh` 含 sukisu 分支，`exploit/scripts/run.sh` 没有——说明"脚本两处维护"会漂移。本地应保持单份源 + 构建时拷贝。
8. **不要把后端从 Stellar 切到 Shizuku**：本地代码注释与 README 明确记录 ColorOS 17 下 Shizuku "ADB 权限受限"；上游用 Shizuku 且未提该限制，贸然切换可能直接不可用。

---

## 6. 落地顺序与验证计划

1. **Step 0（0.5h）**：提交 v2.3.3 工作树；把 `ondevice-app/`（或至少其 build.sh 与 dist APK 的来源）纳入仓库/文档，明确 `dist/OneKeyJB-v2.3.3.apk` 的构建命令与依赖路径（`/tmp/stellar-api`、`/tmp/kotlinlib`、父目录 `bin/`/`out/`）。
2. **Step 1（P0-2，0.5h）**：`Prep.patchKo` slide 守卫 + 单测 → 构建 → `python3 -m unittest discover -s tests -v`。
3. **Step 2（P0-1，0.5–1d）**：反 root 守卫（开关 + trap + watchdog + 启动自检恢复）→ 真机一轮完整越狱验收（不重启、截图正常、`pm list packages -d` 无该项）。
4. **Step 3（P0-3，1d）**：统一 bootstrap 路径、去掉重复 ksud 投放 → 两种管理器真机回归（su 三路 + 管理器连接）。
5. **Step 4（P1-2/P1-3，1–2d）**：su 路径兼容改造 + 残留降权/清理 → 真机复测 MT 终端场景与检测面（可让 task-3 的检测脚本再跑一轮对比）。
6. **Step 5（P1-1，3–6d）**：四管理器。先做官方 KernelSU（与本地 Next 同源，风险最低），再做 BakaSU（需要自编 ko + tracepoint hook）；每加一个管理器都要过：`checkManager` → `ksud debug info` 匹配 → su 三路 → 管理器显示"工作中"；切换管理器必须重启的提示要写进 UI。
7. **每步验收清单**：`tests/` 全绿；`app/build.sh` 构建 + `apksigner verify`；真机（只读观测）`/proc/modules`=`kernelsu … Live`、`getenforce` 符合预期、`su -c 'id -u'`=0、管理器连接、无 15s 重启；不改变现有可回滚路径（重启即失 root）。

**需要先补的数据**（P1-1 之前）：
- 官方 KernelSU 管理器 APK 的证书 SHA-256 + `versionName`；其 `ksud debug info` 的 `version:` 数字（用于 `root_stage_mobile.sh` expected_version）。
- BakaSU 管理器 APK（CI `Manager-release`）的包名/版本/证书 SHA-256；APK 内是否含 `lib/arm64-v8a/libksud.so`；其 `ksud` 的 `manager add`/版本命令行为。
- 官方 KernelSU/BakaSU 的 LKM 与 ksud 的 UAPI/version 对应关系（上游 README 给 BakaSU = v4.2.0-rc3 / 35235；官方为 3.3.1，version 号需实测）。

---

## 7. 附录

### A. 证据索引

| 文件 | 内容 |
|---|---|
| `evidence/detection-audit-2.3.3/mountinfo.{init,shell,gallery,systemui,manager}.txt` | 5 个视角的 mountinfo；全部含 `overlay onekey-su … /system_ext/bin` |
| `evidence/detection-audit-2.3.3/detection_audit.out` | detection-audit 的 4 段审计原始输出（shell/App uid/untrusted_app 复核 + 检测面矩阵） |
| `docs/detection-audit.md`（待 task-3 完成后） | 真机 root 痕迹检测面完整报告（命令、逐条证据、结论） |
| `/tmp/yunni/app/app/src/main/assets/pyb110/run.sh` | 上游六步流程 + 反 root 守卫 + ksud install + 加冕 |
| `/tmp/yunni/exploit/src/{prep_solve,prep_patch,t64560_w2}.c` | 上游设备端 C 工具（prep_patch 有 slide 2MB 对齐校验） |
| `/tmp/yunni/lkm/*.sh` + `lkm/README.md` | 4 种 LKM 自编配方 |
| `/tmp/yunni/app/app/src/main/java/com/pyb110/onekey/MainActivity.kt` | 4 管理器、反 root 开关、投放与流式日志 |
| `OnePlus16-Jailbreak/app/assets/root_stage_mobile.sh` | 本地用户态安装/验收（版本、签名、UAPI、manager add、su 三路、su_path_compat） |
| `OnePlus16-Jailbreak/app/assets/su_path_compat.sh` + `app/src/su/su_forward.S` | 本地 su 路径兼容（overlay 来源） |
| `OnePlus16-Jailbreak/app/src/dev/onekey/jb/{Prep,RootDiagnostics,ProcessRunner,ShizukuShell}.java` | 本地求解/校验/进程语义 |
| `docs/158`、`docs/162`、`docs/163` | vermagic/符号/加载门/加冕/sucompat 的既有实测结论 |

### B. 关键快照

- 本地：`ksu-template.ko` 531,936 B（cred+noenforce+vermagic，v3.4.2）；`sukisu-template.ko` 406,294 B（vermagic，v4.2.1-dfae9237@main，未打 noenforce）；`dist/OneKeyJB-v2.3.3.apk` 9,287,747 B sha256 `eaded228…`。
- 上游：4 ko = 349,224 / 350,000 / 385,440 / 376,136 B；4 ksud = 4,990,848 / 7,059,584 / 5,714,560 / 5,522,800 B；assets 合计 40.9 MiB；README 声称 release APK 22,240,543 B / md5 `3528f2e036441fbe6b33c5a850dcf11d`。
- 上游 ko 版本串：Next `v3.4.0`（ksud 3.4.2）、BakaSU `v4.2.0-rc3-7c383cfe-dirty@BakaSU`、SukiSU `v4.2.1-fd2a8515@HEAD`、官方 KernelSU 无版本串（ksud 3.3.1 uapi5）。

### C. 复现命令（只读）

```bash
# 上游历史（本会话已 unshallow）
cd /tmp/yunni && git log --oneline --all

# 上游 ko 的 .modinfo / 符号态
python3 - <<'EOF'
from elftools.elf.elffile import ELFFile
for f in ["ksu-base.ko","ksu-base-next.ko","ksu-base-bakasu.ko","ksu-base-sukisu.ko"]:
    p="/tmp/yunni/app/app/src/main/assets/pyb110/"+f
    e=ELFFile(open(p,'rb')); sec=e.get_section_by_name('.modinfo')
    print(f, [x for x in sec.data().split(b'\x00') if b'ermagic=' in x])
EOF

# 本地模板补丁字节
python3 - <<'EOF'
from elftools.elf.elffile import ELFFile
for f in ["app/assets/ksu-template.ko","app/assets/sukisu-template.ko"]:
    p="/Users/xingranya/Downloads/CVE-2026-64560-fullchain/OnePlus16-Jailbreak/"+f
    e=ELFFile(open(p,'rb')); t=e.get_section_by_name('.text'); i=e.get_section_by_name('.init.text')
    raw=open(p,'rb').read()
    print(f, "text+0x5FC8=", raw[t['sh_offset']+0x5FC8:t['sh_offset']+0x5FC8+4].hex(),
             "init.text+0x148=", raw[i['sh_offset']+0x148:i['sh_offset']+0x148+4].hex())
EOF

# 真机 overlay 证据（已抓取）
grep -n "onekey" /Users/xingranya/Downloads/CVE-2026-64560-fullchain/evidence/detection-audit-2.3.3/mountinfo.*.txt
```

### D. 术语

- **slide**：KASLR 偏移（2 MB 对齐），每次重启变化；符号补丁必须按当次 slide 重打。
- **allow_shell**：KSU 模块参数，`1` 时 uid 2000(shell) 被视为允许 su/escape（`allowlist.c`）；`bundled` 只影响 GET_INFO flag。
- **GRANT_ROOT**：`KSU_IOCTL_GRANT_ROOT=0x4b01`，`allowed_for_su` 权限，内核直接把当前进程 escape 成 uid0+CAP_FULL_SET。
- **sucompat**：KSU 把 `/system/bin/su` 的 execve 重定向到 `/data/adb/ksud`；默认 true。
- **加冕（crown）**：manager 被 `track_throne` 识别并 `ksu_set_manager_appid`；管理器启动后靠 setuid hook 拿 ksu fd。
- **noenforce 补丁**：NOP 掉模块 init 里的 `setenforce(true)`，让 SELinux 停在 Permissive。
- **cred 补丁**：把 `track_throne` 里 `override_creds(ksu_cred)` 的符号换成 `init_cred`（uid0+CAP_FULL_SET），使读取 0640 的 `packages.list` 成功。

---

> 说明：本文所有"真机/当前状态"结论来自 `evidence/detection-audit-2.3.3/` 与 detection-audit 的即时反馈（只读）；完整命令与逐条证据以 task-3 的 `docs/detection-audit.md` 为准。本文未修改任何 app 源码、未操作手机。
