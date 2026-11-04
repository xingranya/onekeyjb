# merged-app 交接说明（stellar-port → Lead）

> 生成时间：2026-10-20 23:05 · 作者：teammate stellar-port
> Lead 已接手 merged-app 收尾，本文件是当前工程状态 + 关键事实，避免重复踩坑。
> 工程根：`/Users/xingranya/Downloads/CVE-2026-64560-fullchain/merged-app`（由 `cp -R /tmp/yunni/app` 而来）

## 1. 当前工程状态（已写入、可编译状态未知）

已完成的文件（相对 merged-app）：
| 文件 | 状态 |
|---|---|
| settings.gradle.kts | 已改：repos = google/mavenCentral/jitpack.io + 本地 `libs/stellar-repo` |
| build.gradle.kts | 上游原样（AGP 9.4.1 + kotlin compose plugin 2.4.20） |
| gradle.properties | 已改：jvmargs 3072m、nonFinalResIds |
| keystore.properties | 新建：keystore/debug.keystore（storePassword=android, alias=androiddebugkey） |
| local.properties | 新建：sdk.dir=/Users/xingranya/Library/Android/sdk |
| keystore/debug.keystore | 从 ondevice-app/debug.keystore 复制（cert SHA-256 = a023f95cd5c4aa99d64444215c9bd605e17d90c3140fa97e1fefe1c537be487e，与设备已装 dev.onekey.jb 一致） |
| libs/stellar-repo/** | 新建：本地 maven 仓库，com.github.roro2239.Stellar-API:{aidl,api,provider,shared}:1.0.3 的 aar+pom（来自 /tmp/stellar-api/real-*.aar，104K） |
| app/build.gradle.kts | 已改：applicationId=dev.onekey.jb、versionCode=17、versionName="2.4.0"、minSdk31/targetSdk37/compileSdk37；debug+release 都签名到上面 keystore；依赖移除 Miuix/Shizuku，加入 COUI 4 模块 1.1.0 + Stellar 4 模块 1.0.3 |
| app/src/main/AndroidManifest.xml | 已改：WAKE_LOCK；queries 5 包（stellar manager + 4 管理器）；StellarProvider（authorities=\${applicationId}.stellar，exported，INTERACT_ACROSS_USERS_FULL）+ meta-data roro.stellar.permissions=stellar；activity .MainActivity |
| app/src/main/java/com/pyb110/onekey/MainActivity.kt | 最小占位（ComponentActivity + setContent{ Text("loading") }），等 Lead 覆盖 |
| app/src/main/assets/pyb110/ | 上游 17 个资产 + 新增 `su_forward`（9056B）、`su_path_compat.sh`（2236B），共 41MB；**run.sh 仍是上游未合并版** |
| app/src/main/java/.../MainActivity.kt（上游版） | 已移除，备份在 `/tmp/yunni-MainActivity.kt.bak` |

**还没写（Lead 接手部分）**：`StellarShell.kt`、`JailbreakController.kt`、合并版 `run.sh`、以及一次真实 `:app:assembleDebug` 验证。

已跑通的验证（重要基线）：
```
cd merged-app && ./gradlew :app:dependencies --configuration debugRuntimeClasspath
→ BUILD SUCCESSFUL in 11s（EXIT=0）
```
说明 COUI 1.1.0 与 Stellar API 1.0.3 坐标全部可解析（COUI 拉入 org.jetbrains.compose.*:1.11.1，最终与 compose-bom 2026.09.00 的 androidx compose 1.12.1 共存；packaging 阶段是否有 duplicate class 风险尚未验证，第一次 assembleDebug 重点看这里）。

## 2. 关键常量（供 run.sh / Controller 使用，均已核查）

管理器 4 种（kind → LKM / ksud / Android 管理器包名 / 管理器 versionName / 内核上报 version）：
| kind | LKM asset | ksud asset | 管理器包名 | 管理器 versionName | 内核 version（ksud debug info） |
|---|---|---|---|---|---|
| kernelsu | ksu-base.ko | ksud_official | me.weishu.kernelsu | 3.3.1（官方） | **32680**（LKM 内 "welcome to KernelSU version 32680"） |
| next | ksu-base-next.ko | ksud_next | com.rifsxd.ksunext | 3.4.2 | **33311**（LKM 是 v3.4.0；反汇编唯一候选 33311；APK 版本码 33355 = manager versionCode） |
| bakasu | ksu-base-bakasu.ko | ksud_bakasu | com.resukisu.resukisu | 4.2.0-rc3 | **35235**（反汇编 mov #0x89a3 确认） |
| sukisu | ksu-base-sukisu.ko | ksud_sukisu | com.sukisu.ultra | v4.2.1 | **41027**（LKM "welcome to KernelSU version 41027"；管理器/ksud 是 40994，不要混用） |

全部 uapi_version=5。建议"内核 version 校验"用族映射而不是全等：next∈{33311,33355}、sukisu∈{40994,41027}（设备当前加载的是旧 LKM 40994），kernelsu=32680、bakasu=35235；跨族（版本属于别的管理器）→ 失败安全中止"请重启后切换管理器"。

已知坑（上游资产，task-1 报告 docs/merge-upstream-diff.md 结论 6 已确认，我复核过）：
- `ksu-base.ko` 已打 PYB110 vermagic；**`ksu-base-next/bakasu/sukisu.ko` 的 .modinfo 仍是 `vermagic=6.18.21-4k ...`**，PYB110 内核未开 CONFIG_MODULE_FORCE_LOAD ⇒ 这 3 份直接 finit_module 会失败。
- 修法：`python3 /tmp/yunni/third_party/cve-2026-64560-tools/ko_vermagic.py <in.ko> <out.ko>`（pyelftools 已装），先把脚本里 VERMAGIC 常量改成本机串：
  `6.18.21-android17-5-gf957accaf563-ab15926176-4k SMP preempt mod_unload modversions aarch64`
  参考 ksu-base.ko 的字符串数量特征：打完后 short 串在 `zermagic=`/struct 各 1 处，device 串 1 处。

## 3. 真机事实（只读观测，未操作手机；serial 3B16AA00ANP00000）

- 系统：PYB110 / Android 17 / SDK 37 / 内核 `6.18.21-android17-5-gf957accaf563-ab15926176-4k`。
- 已装：dev.onekey.jb v2.3.3（versionCode 16，签名 = 我们的 debug.keystore）；com.sukisu.ultra v4.2.1（40994）；roro.stellar.manager 1.1。
- 当前内核模块**已加载且 Live**：`kernelsu 204800 4 - Live`，`ksud debug info` = version:40994 / uapi:5 / lkm:true / bundled:false / runtime_mode:late-load。→ 新 App 默认走"模块已加载→修复模式"。
- `/data/adb/ksud` 存在（sukisu ksud "4.2.1-2-gdfae9237 (uapi: 5)"）；`/data/adb/ksu/bin/{su,ksud} → /data/adb/ksud`；/data/adb 下还有 modules/zygisksu/lspd 等。
- **v2.3.3 遗留痕迹（本次清理目标）**：`/proc/mounts` 有一行
  `onekey-su /system_ext/bin overlay ro,... lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin`，
  `/data/adb/onekey-su-compat/bin` 存在；`/system_ext/bin/su` 9056B = su_forward；`/system/bin/su` 5.5MB(=ksud 拷贝，shell 直接执行报 `can't execute: Permission denied`)。
- su 行为（adb shell uid2000 实测）：`su -c 'id -u'`→0、`su -M -c`→0、`printf 'id -u\nexit\n' | su`→0（**都靠 /system_ext/bin/su 这个遗留 overlay 入口**，PATH 里 /system_ext/bin 在 /system/bin 前）；`/system/bin/su -c ...` 被 shell 的 faccessat 预检查拒绝。
  → 因此 **cleanup 掉 overlay 后 default su 可能失效**：合并版 root 入口不要只依赖 PATH 的 su。建议 root 入口顺序：`$D/su_forward`（直接 execve /system/bin/su，绕过 shell 预检查，正是 v2.3.3 su_path_compat 里那个二进制；工作目录里直接用不算全局挂载/入口）→ `/data/adb/ksu/bin/su` → `su`。su 验证四项：绝对路径（su_forward）、stdin、-M 必过；default(PATH) 在 SU_COMPAT=0 时只告警不中止（不创建 overlay），SU_COMPAT=1 时才用 su_path_compat.sh 修复并必过。
- `su_forward` 未在真机单独实测（只在 v2.3.3 overlay 路径下工作过）；Lead 若实测注意这一步。

## 4. 合并版 run.sh 设计（尚未写，以下为对接好的方案）

底：`/tmp/yunni/app/app/src/main/assets/pyb110/run.sh`（4 管理器 + 六步链 + anti-root guard，整段保序保留 [1/6]~[6/6]）。
新增环境变量：`PYB110_DIR`、`PYB110_MANAGER`、`PYB110_DISABLE_ANTIROOT`、**`PYB110_SU_COMPAT`（默认 0）**、可选 `PYB110_RUN_ID`。
输出协议（给 Controller 解析）：每阶段 `echo "PROGRESS <0-100> <label>"`；结束 `echo "RESULT:OK"` / `echo "RESULT:FAIL"` + `echo DONE`；成功另写 `$D/root_stage.ok` 内容 `ROOT_STAGE_OK:<RUN_ID>`（唯一 runId，防复用旧成功）。
建议追加/改写点：
1) 开头定义 case 表（见 §2），echo MANAGER。
2) 定义 `cleanup_compat()`：`for m in $(awk '$1=="onekey-su" && $3=="overlay"{print $2}' /proc/self/mounts); do umount "$m"; done`；`rm -rf /data/adb/onekey-su-compat`。脚本**开始**（有 root 就做，没 root 记录跳过）和**结束**（成功/失败 trap 都做）各调一次；SU_COMPAT=0 时绝不创建这些。
3) 管理器检查：`dumpsys package "$MPKG"` 取 versionName，缺包 → FAIL；版本不同 → WARN（不要硬失败，避免 versionName 假设错；硬失败放在内核 version 校验）。
4) 六步链后（或 ALREADY_LOADED 分支）进入 root stage：
   - 等模块 Live：轮询 1s 最多 180s `grep -i kernelsu /proc/modules`，行第 5 字段必须 Live，超时 FAIL。
   - init_root（见 §3），失败 FAIL。
   - 内核 version/uapi 校验：`"$KP" debug info`（KP 优先 /data/adb/ksud，缺则 $D/$KSUD），uapi 必须 5，version 族校验（§2），跨族 FAIL。
   - SukiSU：`setenforce 0`（root 下，超时 10）。
   - anti-root guard（DISABLE_ANTIROOT=1 才做）：`pm disable-user --user 0 com.oplus.exsystemservice` → `pkill -9 -f com.oplus.exsystemservice` → sleep 5 → `pm enable --user 0 ...`；用 trap + 后台 watchdog（`(sleep 20; root_cmd pm enable ...) &`）防止 5s 内被杀留永久 disabled；开关关时 echo ANTIROOT_SKIP。
   - `ksud install`（用 $D/$KSUD，rc 检查；`/data/adb/ksud` 必须存在）；`mkdir -p /data/adb/ksu/bin /data/adb/modules`；`ln -sf /data/adb/ksud /data/adb/ksu/bin/{su,ksud}`。
   - SukiSU 管理器登记（v2.3.3 root_stage_mobile.sh 逻辑）：`$KP debug get-sign` 校验 `size: N, hash: 947ae944...` → `ksud manager add <pkg> <size> <hash>` → `manager set-enabled true` → `manager list` 回读；失败 FAIL（Next/官方/BakaSU 不做这步，可留 `chmod 644 /data/system/packages.list; mv 往返` 兜底加冕，仅当管理器未加冕时）。
   - `$KP feature set su_compat 1`、`$KP debug mark refresh`（只刷新已有授权）。
   - su 四项验证（§3），按 SU_COMPAT 决定 default 是否硬失败。
   - SU_COMPAT=1 时：root 执行 `$D/su_path_compat.sh`（其中 su_forward 路径 = `$(dirname $0)/su_forward`，所以两文件都要 stage 到 $D）。
   - 重启管理器：`am force-stop $MPKG`；`am start -W -n $MPKG/<activity>`（next: com.rifsxd.ksunext/.ui.MainActivity；sukisu: com.sukisu.ultra/com.sukisu.ultra.ui.MainActivity；其他用 monkey 启动）。SukiSU 可保留 /proc/<pid>/fd 查 `[ksu_driver]` 的连接检查（建议 WARN 不 FAIL）。
   - 写 `ROOT_STAGE_OK:$RUN_ID`（tmp+mv 原子），`RESULT:OK`，`DONE`。
5) 失败路径统一 `fail()`：echo RESULT:FAIL → cleanup_compat（SU_COMPAT=0）→ 写 ROOT_STAGE_FAIL:<RUN_ID> → exit 非 0。
6) 安全：沿用上游 `timeout`/setsid/被动等待；不要新增任何 su 全局 overlay（除 SU_COMPAT=1 用户显式开启）。

## 5. Stellar API 事实（写 StellarShell.kt 需要）

- 坐标：`implementation("com.github.roro2239.Stellar-API:{aidl,api,provider,shared}:1.0.3")`（本地 repo 优先，jitpack 兜底）。
- `roro.stellar.Stellar`（Kotlin object，Java 侧 INSTANCE）关键成员：
  `pingBinder(): Boolean`、`checkSelfPermission(permission="stellar"): Boolean`、`requestPermission(permission, requestCode)`、`uid: Int`、`newProcess(cmd: Array<String?>, env: Array<String?>?, dir: String?)` → `StellarRemoteProcess : java.lang.Process`（Kotlin 调用注意 Array<String?>）。
- `StellarRemoteProcess` 额外：`alive(): Boolean`、`waitForTimeout(long, TimeUnit): Boolean`（用它们替代 Java 的 instanceof 分支；普通 Process 用 `isAlive/waitFor(timeout,unit)`）。
- 监听器：addBinderReceivedListenerSticky / addBinderDeadListener / addRequestPermissionResultListener（方法名见 /tmp/stellar-api/Stellar.kt 反编译源码，可直接对照）。requestPermission 的回调 code 由调用方传入。
- Provider：客户端 manifest 必须声明 `roro.stellar.StellarProvider`（已在 manifest 配好）+ meta-data `roro.stellar.permissions=stellar`（参考 ondevice-app/AndroidManifest.xml）。
- 可参考的现成 Stellar 移植：`/Users/.../ondevice-app/src/dev/onekey/jb/ShizukuShell.java`（198 行）+ `ProcessRunner.java`（99 行）；Kotlin 化即可，其中 `stageAsset/stageBytes` 用 `cat > "$dst.tmp.$$"` → `wc -c` 校验长度 → chmod → `mv -f` 原子替换。
- 工作目录建议 `/data/local/tmp/onekey`（沿用 v2.3.3；也可 boot_id 子目录），env PATH 加 `/data/adb/ksu/bin`、`/system_ext/bin`（su_forward 检查用）。

## 6. JailbreakController 接口（Lead 为准，我按此实现到一半）

Lead 最新要求：`managers: List<ManagerOption>`（data class(kind,label,pkg,installedVersion)）、`selectedManager="next"`、`antiRootGuard: Boolean`（**纯 var，不要 setAntiRootGuard 函数**；要能直接 `controller.antiRootGuard = it`）、`defaultSuCompat: Boolean`（默认 false，runJailbreak 传 PYB110_SU_COMPAT）、`running`、`progress: Float 0f..1f`、`stage`、`logs: List<String>`（append-only cap 2000）、`deviceInfo`、`serviceReady`、`serviceGranted`；动作 refreshState/selectManager/runJailbreak/selfCheck/repair/clearLogs/cleanupOldCompatMounts/openStellar。UI 只读状态/调动作，不直接碰 Stellar/Shell。
若要持久化 antiRootGuard/defaultSuCompat 又保持纯 var：可用 `private var _x by mutableStateOf(saved)` + `var x get/_x set`（Compose 仍可观察，赋值可直接生效）；runJailbreak 里读取即可。

## 7. 构建 & 交付

```
cd /Users/xingranya/Downloads/CVE-2026-64560-fullchain/merged-app
./gradlew :app:assembleDebug      # 先验证 COUI/Stellar 打包（duplicate class / manifest merge / R8 先不碰）
./gradlew :app:assembleRelease    # R8 + shrink，产物 app/build/outputs/apk/release/app-release.apk
```
- JDK：PATH 上 java 17 可跑 Gradle 9.7.1；若 AGP 9.4.1 要求 21，用 `/opt/homebrew/opt/openjdk@21`（已装；`org.gradle.java.home` 或 JAVA_HOME）。
- 安装验证：`adb install -r`（versionCode 17 > 16，签名相同 ⇒ 可覆盖）；设备 serial 3B16AA00ANP00000。
- 参考构建：上游 /tmp/yunni 曾产物 `/tmp/yunni/app/app/build/outputs/apk/debug/app-debug.apk`。
