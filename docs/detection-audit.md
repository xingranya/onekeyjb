# 真机 root 痕迹检测面审计（2.3.3 + SukiSU Ultra）

- **设备**：OnePlus PYB110（OP64E1L1），Android 17 / SDK 37，内核 `6.18.21-android17-5-gf957accaf563-ab15926176-4k`（aarch64）
- **状态**：SukiSU Ultra 管理器 `v4.2.1`（versionCode 40994，`com.sukisu.ultra` uid 10538）+ onekey 越狱 App `dev.onekey.jb` 2.3.3（uid 10364）；`kernelsu` 模块 **Live**；SELinux **Enforcing**；`ro.boot.verifiedbootstate=green`、`ro.boot.flash.locked=1`
- **审计时间**：2026-10-20 22:44–22:57 CST（同一 boot；`onekey-su` overlay 于当日 13:09 挂载，`root_stage` 15:31 重跑，`/data/adb/ksud` 22:08 更新）
- **方式**：**只读** adb 命令。四个视角：①`adb shell`(uid 2000)；②`run-as` 真实 App uid（`bin.mt.termex` uid 10360，debuggable）；③shell 读真实 App 进程 `/proc/<pid>/mountinfo`；④可选 `su` + `busybox setuidgid` + `setcon` 构造 `uid=App + u:r:untrusted_app:s0` 交叉复核
- **未做**：未 mount/umount、未写设备、未重启、未改 root；不安装任何 App
- **复现脚本**：[tools/detection_audit.sh](../tools/detection_audit.sh)；原始输出与证据：[evidence/detection-audit-2.3.3/](../evidence/detection-audit-2.3.3/)

---

## 0. 结论速览

当前越狱状态对「普通 App（非 root、非 shell）」的检测面**不干净**：一个零权限的 App 通过解析自己的 `/proc/self/mountinfo` 就能 100% 命中本次越狱特有的 `onekey-su` overlay；此外整个越狱工作区 `/data/local/tmp/onekey/` 是 0755/0644，普通 App 可列举可读取（含 root 证据日志、脚本、管理器 APK、exploit 二进制）。

| # | 检测点 | App 可见 | 风险 | 关键证据 |
|---|---|---|---|---|
| 1 | `/proc/self/mountinfo` / `/proc/mounts` 中 `source=onekey-su` 的 overlay 挂在 `/system_ext/bin` | **YES**（零权限） | **P0** | `onekey-su /system_ext/bin overlay ro,...lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin` |
| 2 | `mount` / `df` 输出含 `onekey-su` | **YES** | **P0** | `df` 直接把 `onekey-su` 当文件系统名显示 |
| 3 | `statfs("/system_ext/bin")` = overlay magic `0x794c7630`；目录 inode=2、mtime 11 vs stock | **YES** | P1 | `stat -c %a`：/system_ext/bin=751、/system_ext=755；mtime 2026-10-20 13:09 vs 2026-10-09 |
| 4 | `/system_ext/bin/su` 真实存在（9056B，`system_file`，755） | **YES** | P1 | 已知路径可 `stat`/读/执行；目录本身 751 不可列 |
| 5 | 执行 `/system_ext/bin/su` 得到非标准报错 `su: 内核接口未允许执行 /system/bin/su`（exit 127） | **YES** | P1 | `run-as` 实测 |
| 6 | App `PATH` 含 `/system_ext/bin`，`command -v su` 可命中 | **YES** | P2 | 真实 App `/proc/<pid>/environ` 的 PATH 同样含 `/system_ext/bin` |
| 7 | `/data/local/tmp/onekey/` 整目录可读（0755 / 文件 644）：`root_stage.log`、`su_path_compat.sh`、`manager.apk`、exploit 二进制、`suverify.txt` | **YES** | **P0** | App uid `ls` 成功，43 条目；`root_stage.log` 首行 `=== root 环境检查 ===` |
| 8 | `/data/local/tmp/ksu-op3.ko`、`/data/local/tmp/root_stage.sh`、`ksud342` 等已知名可读 | **YES** | P1 | ELF 魔数可读，ko 406294B |
| 9 | 包名 `com.sukisu.ultra` / `dev.onekey.jb` | 部分 | P2 | 无 `QUERY_ALL_PACKAGES` 的 App 只看得到 7 个包、看不到这两个；声明该权限的检测 App 可枚举（本机已装 `icu.nullptr.applistdetector` 2.4 且已授予） |
| 10 | `/system/bin/su`（KSU sucompat 虚拟文件） | **NO** | — | App 视角 ENOENT；仅白名单 uid（shell、已授权 App）可见 |
| 11 | `/data/adb`、`/data/adb/onekey-su-compat` | **NO** | — | App/shell 均 EACCES；但路径已由 mountinfo 的 `lowerdir=` 泄漏 |
| 12 | `/proc/modules` 里 `kernelsu ... Live` | **NO**（App 域 EACCES，stock 策略同样禁止）；shell/root 可见 | — | App 读 `/proc/modules` → `Permission denied` |
| 13 | `/sys/module/kernelsu` | **NO** | — | 不存在（模块 release 构建主动 `kobject_del`） |
| 14 | `/proc/kallsyms`、`dmesg`、`ps` 看 su/ksu 进程 | **NO** | — | 均被拒；`/proc` 以 `hidepid=invisible` 挂载 |
| 15 | 属性探测（test-keys / debuggable / verifiedboot / SELinux） | **NO**（干净） | — | `release-keys`、`ro.debuggable=0`、`green`、`locked=1`、`Enforcing`；无任何 ksu/onekey 属性 |

**一句话**：`/system/bin/su` 的 sucompat 隐藏做得不错（普通 App 看不到），但 `su_path_compat.sh` 额外制造的 **全局 overlay + 真实 `/system_ext/bin/su`** 把这件事全部抵消，并且 `/data/local/tmp/onekey` 工作区是比 overlay 更严重的持久泄露面。

---

## 1. 给 upstream-diff（task-1）的 4 个事实

> 以下是 2026-10-20 22:44 之后在同一 boot 上的只读实测。

**(1) `adb shell`(uid2000) 看 `/proc/self/mounts`：有，挂载点是 `/system_ext/bin`（`/system/xbin` 不存在）。**

```text
onekey-su /system_ext/bin overlay ro,seclabel,relatime,lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin,redirect_dir=on 0 0
```

对应 `/proc/self/mountinfo`（shell 的 ns 与 init 相同，`shared:102`）：

```text
20621 30 0:474 / /system_ext/bin ro,relatime shared:102 - overlay onekey-su ro,seclabel,lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin,redirect_dir=on
```

- 来源：`OnePlus16-Jailbreak/app/assets/su_path_compat.sh` 第 45 行 `mount -t overlay onekey-su -o "ro,lowerdir=$base/bin:$target" "$target"`，由 `root_stage_mobile.sh` 用 `su -M`（全局 mount namespace）执行 → 在 init ns 以 `shared:102` 挂载，**通过 shared→slave 传播到所有 App 挂载命名空间**（实测 App 进程的 mountinfo 为 `master:102` 的克隆，见 §3.1）。

**(2) shell 视角下两个 su 路径的真实性（重点：`/system/bin/su` 是假的）**

```text
$ ls -la /system_ext/bin/su /system/bin/su /system/xbin/su
-rwxr-xr-x 1 root root 5522800 2026-10-20 22:08 /system/bin/su
-rwxr-xr-x 1 root root    9056 2026-10-20 15:31 /system_ext/bin/su
ls: /system/xbin/su: No such file or directory

$ stat -c "%n|%F|%s|%a|%u:%g|%C" /system_ext/bin/su /system/bin/su
/system_ext/bin/su|regular file|9056|755|0:0|u:object_r:system_file:s0
/system/bin/su|regular file|5522800|755|0:0|?

$ ls /system/bin | grep -x su ; echo rc=$?     # getdents 里没有 su
rc=1
$ head -c 16 /system/bin/su                    # 真 open 直接 ENOENT
head: /system/bin/su: No such file or directory
$ /system/bin/su --version
4.2.1-2-gdfae9237:KernelSU
```

- `/system_ext/bin/su`：**真实文件**（overlay 的 upper 层），9056B，`755 root:root`，context 伪装成 `u:object_r:system_file:s0`，md5 = `709b42b0b88e9d49cf2e8f799cf934bd` = `app/assets/su_forward`（statically linked ELF，直接 `execve("/system/bin/su")` 的 asm 小工具）。
- `/system/bin/su`：**KSU sucompat 的 stat/execve 重定向**（只对白名单 uid 生效，shell=uid2000 在白名单里）。`ls`/`stat` 被重定向成 `/data/adb/ksud` 的 stat（所以大小 5522800=ksud、mtime 22:08），但 `getdents`、`open` 都不会重定向 → 目录列表没有它、`cat` ENOENT、`stat -c %C` 拿不到 context（显示 `?`）。
- **结论**：以 shell 视角审计 `/system/bin/su` 会被 sucompat 骗到，必须用普通 App uid 复核。

**(3) SELinux / `/data/adb` 可读性**

```text
$ getenforce
Enforcing
$ cat /sys/fs/selinux/enforce
1          # shell 可读；App uid 读被拒（Permission denied）
$ ls -la /data/adb/onekey-su-compat
ls: /data/adb/onekey-su-compat: Permission denied      # /data/adb 是 700 root:root，shell 也进不去
```

root 视角（仅取证，不改）：`/data/adb/onekey-su-compat` = `700 root:root`，`bin/` = `751 root:shell`，`bin/su` = `9056B 755 root:root`，md5 同上，mtime 2026-10-20 15:31。

**(4) 普通 App uid 视角：能看到 overlay 和 `/system_ext/bin/su`；看不到 `/system/bin/su`。**

`run-as bin.mt.termex`（uid 10360，`u:r:runas_app:s0`）与 `uid=10360 + u:r:untrusted_app:s0` 两种构造结果一致：

```text
# mountinfo / mounts（App 自身）
20621 30 0:474 / /system_ext/bin ro,relatime shared:102 - overlay onekey-su ro,seclabel,lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin,redirect_dir=on

# 真实 App 进程命名空间（shell 读 /proc/<pid>/mountinfo，3 个 App 进程一致）
73:21364 21354 0:474 / /system_ext/bin ro,relatime master:102 - overlay onekey-su ro,seclabel,lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin,redirect_dir=on

# App uid 文件操作
stat  /system_ext/bin/su  -> regular file 9056 755 0:0
head -c4 /system_ext/bin/su -> \177 E L F
/system_ext/bin/su -c id -u -> "su: 内核接口未允许执行 /system/bin/su"  (exit 127)
stat  /system/bin/su      -> No such file or directory        # 看不到
stat  /data/adb           -> Permission denied                 # 看不到
grep -c kernelsu /proc/modules -> Permission denied            # 看不到
command -v su             -> /system_ext/bin/su                # PATH 能命中
```

→ **App uid 能看到 onekey-su overlay、能看到并执行 `/system_ext/bin/su`；看不到 `/system/bin/su`、`/data/adb`、`/proc/modules`。**

---

## 2. 视角与方法（为什么不能只用 adb shell 下结论）

| 视角 | 构造 | 可信度 | 用途 |
|---|---|---|---|
| `shell` | `adb shell`（uid 2000，`u:r:shell:s0`） | **会误导**：shell 被 KSU 视为 allow uid，`/system/bin/su` 被 sucompat 伪造成存在（见 §3.3） | 取证、看全局挂载、root 面记录 |
| `app-uid` | `run-as <debuggable-app>` → 真实 App uid + `u:r:runas_app:s0`（本机 `bin.mt.termex` uid 10360） | DAC 结论真实；SELinux 域为 `runas_app` | 零 root 的 App 视角主手段 |
| `app-ns` | shell 读 `/proc/<pid>/mountinfo`（App 进程，uid 10xxx） | 真实 App 挂载命名空间 | 证明 overlay 传播到了 App ns |
| `untrusted` | `su -c` 起 root shell → `busybox setuidgid <uid> sh` → `echo u:r:untrusted_app:s0 > /proc/self/attr/current` → 之后命令都是 App uid + `untrusted_app` | 最接近普通 App（`CapEff=0`、uid 正确；无 per-app MLS category，但本审计判定点与 category 无关） | 交叉验证（脚本 §3 自动尝试，失败即跳过） |

注意：

- 普通 App 的 mount namespace 是 zygote fork 后从 init ns 继承的 slave（`master:102`），而本次 overlay 是 init ns 的 `shared:102`，所以**传播**过去；因此 App **不需要任何权限**，读自己的 `/proc/self/mountinfo` 就能看到。
- App 的 `PATH`（从 `/proc/<pid>/environ` 读真实 App 进程）：`/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/apex/com.android.virt/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin` —— **包含 `/system_ext/bin`**，所以任何做 PATH 查找 `su` 的检测都会命中 overlay 里的 `su`。
- 全流程无写操作；`/data/adb` 的 root 只读内容只用于记录机制，不参与「App 可见性」结论。

---

## 3. 详细发现

### 3.1 `onekey-su` overlay（P0，最严重）

**事实**

```text
# /proc/self/mounts（shell uid2000）
onekey-su /system_ext/bin overlay ro,seclabel,relatime,lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin,redirect_dir=on 0 0

# /proc/self/mountinfo（shell/init ns）
20621 30 0:474 / /system_ext/bin ro,relatime shared:102 - overlay onekey-su ro,seclabel,lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin,redirect_dir=on

# App 进程（systemui uid 10264 / gallery uid 10168 / launcher uid 10257）各自 ns 内都有克隆
73:21364 21354 0:474 / /system_ext/bin ro,relatime master:102 - overlay onekey-su ro,seclabel,lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin,redirect_dir=on
```

**为什么它能被任何 App 检测**

1. `mountinfo`/`mounts` 是 App 自己的 proc 文件，**普通 App 读自己的 `/proc/self/mountinfo` 不需要任何权限**（实测 `run-as` 与 `untrusted_app` 域都可读）。
2. overlay 的 `source` 字段就是字符串 `onekey-su`，任何 `line.contains("onekey-su")` 的检测直接命中。
3. `lowerdir=` 把 `/data/adb/onekey-su-compat/bin` 这个绝对路径也暴露给所有 App（即使 App 进不去 `/data/adb`）。
4. `mount`（toybox 读 mounts）、`df`（读 mountinfo）在 App 域同样可执行/可读：`df /system_ext/bin` 直接输出 `onekey-su 474901564 ... 80% /system_ext/bin`。
5. `statfs("/system_ext/bin")` 返回 `f_type=0x794c7630`（OVERLAYFS），且 `/system_ext/bin` 的 inode=2（overlay root inode）、mtime=`2026-10-20 13:09`，与 `/system_ext` 的 `2026-10-09 21:25`（系统镜像时间）不一致——即使用「不信 mountinfo」的检测也能通过 stat/statfs 命中。
6. 该 overlay 是**全局挂载**（`su -M`）且为 `shared:102` 传播，App 命名空间不是独立私有的 → 无法通过「只影响 root 进程」的方式避免暴露。

**来源（本地源码）**

- `OnePlus16-Jailbreak/app/assets/su_path_compat.sh`：
  - `base=/data/adb/onekey-su-compat`（L32）
  - `mount -t overlay onekey-su -o "ro,lowerdir=$base/bin:$target" "$target"`（L45），`target` 取 `/system/xbin`（本机不存在）后的 `/system_ext/bin`
  - 注释「只挂载只读叠加层；原目录作为 lower 保留，重启后自动消失」
- `OnePlus16-Jailbreak/app/assets/root_stage_mobile.sh` L93：`timeout 30 /system/bin/su -M -c "/system/bin/sh '$D/su_path_compat.sh'"` —— `-M` 即全局 mount namespace，导致传播到所有 App。

**补充**：`/system_ext/bin` 原属 erofs 只读镜像，overlay 是「目录级」替换（merged 143 个条目 + 1 个 su），所以 shell 看 `ls /system_ext/bin` 完全正常（除了多一个 su），而 App 因目录权限 751 无法列出、只能按已知路径命中 `su`。

### 3.2 `/system_ext/bin/su`（P0/P1）

| 属性 | 值 |
|---|---|
| 路径 | `/system_ext/bin/su`（overlay 内，真实文件） |
| 大小/inode | 9056 B / inode 1071617 |
| 权限/属主 | `755 root:root` |
| SELinux | `u:object_r:system_file:s0`（伪装成普通系统文件） |
| mtime | 2026-10-20 15:31:04（当日写入；mount 时间 13:09） |
| md5 | `709b42b0b88e9d49cf2e8f799cf934bd` = `OnePlus16-Jailbreak/app/assets/su_forward` |
| 内容 | 静态链接 aarch64 ELF（未 strip），汇编实现，直接 `execve("/system/bin/su")`（syscall 221）以绕开 shell 的 `faccessat` 预检查；失败时写 stderr `su: 内核接口未允许执行 /system/bin/su`，exit 126/127 |

**App 视角可做的操作（全部实测）**

```text
run-as uid10360:
  stat /system_ext/bin/su        -> 9056, 755, root:root      # Java File.exists()/length() 均命中
  head -c 4 /system_ext/bin/su   -> "\x7fELF"                 # 内容可读，可做 md5/特征匹配
  /system_ext/bin/su -c id -u    -> stderr "su: 内核接口未允许执行 /system/bin/su", exit 127
  command -v su                  -> /system_ext/bin/su
  ls /system_ext/bin             -> Permission denied          # 目录 751，仅 o+x，不可列
```

→ 对检测方来说：`new File("/system_ext/bin/su").exists()` 为 true、`length()==9056`、执行后得到非标准输出/退出码——三连命中。**注意 `su_forward` 的报错文案含「内核接口」字样，等于主动告诉检测方「这里有内核 SU 接口」。**

### 3.3 `/system/bin/su`：sucompat 虚拟文件（App 不可见，shell 可见）

| 证据 | shell(uid2000) | App uid(10360) |
|---|---|---|
| `stat`/`ls` | 显示 `regular file 5522800 755 0:0`，context `?` | `No such file or directory` |
| `getdents`（`ls /system/bin` 再 `grep -x su`） | **不出现**（rc=1） | 不出现 |
| `open`（`cat`/`file`） | `No such file or directory` | ENOENT |
| 执行 `--version` | `4.2.1-2-gdfae9237:KernelSU` | 若经 `/system_ext/bin/su` 转发 → exit 127 + 中文报错 |
| 执行 `-c 'id -u'` | `0`（uid=0，context `u:r:ksu:s0`） | 不可用 |

- 机制：KSU `sucompat` 对**白名单 uid** 拦截 `stat/access/execve` 的 `/system/bin/su`，重定向到 `/data/adb/ksud`（所以假 stat 的大小=ksud 大小 5,522,800）；`getdents`/`open` 不拦截，故目录列表与真实打开仍 ENOENT。
- 结论：**`/system/bin/su` 本身对普通 App 是安全的（不可见）**；shell 视角看到的「存在」是假象，审计时不能拿它给 App 视角下结论。
- 对上游 `su_path_compat.sh` 的意义：它要解决的正是 shell 对 `/system/bin/su` 的 `faccessat` 预检查（`su_forward` 注释），但代价是把「虚拟 su」升级成了「全局可见的真实 su 文件 + overlay」。

### 3.4 `/data/adb/onekey-su-compat`（App 不可读，但路径已泄漏）

```text
# root 只读取证
/data/adb/onekey-su-compat          700 root:root   2026-10-20 13:09
/data/adb/onekey-su-compat/bin      751 root:shell  2026-10-20 13:09
/data/adb/onekey-su-compat/bin/su   9056 755 root:root  u:object_r:system_file:s0  2026-10-20 15:31
md5 = 709b42b0b88e9d49cf2e8f799cf934bd
```

- shell(uid2000)：`/data/adb` 本身 700 root:root → `Permission denied`，不可列（`/data/adb/onekey-su-compat` 同理）。
- App：`stat /data/adb` 也 `Permission denied`（SELinux + DAC 双重），无法确认存在性（`File.exists()` 返回 false）。
- **但**：`/proc/self/mountinfo` 的 `lowerdir=/data/adb/onekey-su-compat/bin:/system_ext/bin` 已把这个路径字符串公开给所有 App——检测方不需要读 `/data/adb`，只需要 grep mountinfo 里的 `data/adb`。

### 3.5 内核面（App 基本不可见）

| 检测点 | shell(uid2000) | App 域 | 说明 |
|---|---|---|---|
| `/proc/modules` | `kernelsu 204800 4 - Live 0x0000000000000000 (O)` | **Permission denied** | App 域被 SELinux（`proc_modules`）拒绝；这是 stock Android 同样行为，不是本次越狱特有信号 |
| `/sys/module/kernelsu` | 不存在 | 不存在 | KernelSU release 构建在 init 末尾 `kobject_del`，无 sysfs 痕迹 |
| `/proc/kallsyms` | Permission denied（shell 也读不到） | Permission denied | — |
| `dmesg` | root 可读（含 KSU 日志，如 `Crowning manager`） | `klogctl: Operation not permitted` | — |
| `ps` 里 su/ksu 进程 | 可见 | 不可见（`/proc` 以 `hidepid=invisible` 挂载，仅见自己） | — |

> 结论：**内核面针对「App 检测」是干净的**；但针对 ADB/PC 侧扫描（能拿 shell/root）`/proc/modules` 的 `kernelsu ... Live` 是硬痕迹（模块名无法轻易改）。

### 3.6 属性 / props

App uid 可读的值（`getprop`）：

```text
ro.build.tags              = release-keys
ro.debuggable              = (空 —— 该 prop 对 App 不可读，stock 同样)
ro.secure                  = (空 —— 同上)
ro.boot.verifiedbootstate  = green
ro.boot.flash.locked       = 1
ro.build.type              = user
ro.boot.selinux            = enforcing
```

- 未发现任何 `ksu`/`kernelsu`/`sukisu`/`magisk`/`lsposed`/`zygisk` 相关属性；`/system_ext/bin` 与 `/system/bin` 都没有新增 `ro.*` 属性。
- 唯一名字含 `onekey` 的属性是 `sys.data.oplus.onekeycooling.support=false`，疑似 OnePlus「一键降温」运行时 prop，与越狱无关（未在 build.prop 中找到，属运行时属性）——但**如果检测方做简单的 `grep -i onekey` 会误报**，建议忽略或改由 O+ 系统属性来源确认。

### 3.7 包名 / 管理器 / 白名单

```text
com.sukisu.ultra   v4.2.1  (versionCode 40994)  uid 10538
dev.onekey.jb      2.3.3   (versionCode 16)     uid 10364
```

- **无 `QUERY_ALL_PACKAGES` 的普通 App**：`pm list packages` 只返回 **7 个** 可见包（自身 + 系统默认可见），`grep sukisu|onekey` 为 0 → 普通 App 默认**看不到**管理器/越狱 App 包名。
- **有 `QUERY_ALL_PACKAGES` 的检测 App 可枚举**：本机已安装 `icu.nullptr.applistdetector` v2.4（android.permission.QUERY_ALL_PACKAGES granted=true）等检测类 App；这类 App 会直接看到 `com.sukisu.ultra`、`dev.onekey.jb`，以及 `bin.mt.plus`、`com.tsng.hidemyapplist`、`github.tornaco.android.thanos` 等「root 圈」应用 → 包名/应用列表检测有效。
- KSU `.allowlist` 实际包含（root 只读）：`dev.onekey.jb`、`make.more.r2d2.play.cellular_pro`、`com.web.czero`、`com.xayah.databackup.foss`、`com.tsng.hidemyapplist`、`com.rosan.installer.x.revived`、`roro.stellar.manager`、`com.omarea.vtools`、`com.ktls.fileinfo`、`bin.mt.plus`、`com.nvmex.networkhelper`、`com.byyoung.setting`、`cn.myflv.monitor`、`com.luckyzyx.luckytool`、`com.trae.launchericonsize` 等；这些被授权 App 能看到 `/system/bin/su` 并直接拿 root——检测 App 不在其中，因此看不到 `/system/bin/su`。

### 3.8 `/data/local/tmp/onekey` 工作区泄漏（P0，持久）

`ShizukuShell.java` 中 `WORK = "/data/local/tmp/onekey"`，目录以 **0755** 创建、结果文件 **0644**（注释原文：`App-side read of a world-readable file under /data/local/tmp (dirs are 0755)`），用于 App（app uid）读回 shell 侧产生的结果。这导致**任何 App** 只要知道路径即可列举和读取：

```text
run-as uid10360:
  ls /data/local/tmp/onekey      -> 43 个条目（成功）
  head -1 .../root_stage.log     -> "=== root 环境检查 ==="（成功）
  head -c4 /data/local/tmp/ksu-op3.ko -> "\x7fELF"（成功，406294 B）
  ls /data/local/tmp             -> Permission denied（父目录 0771，仅 x，不可列）
```

工作区内容（`ls -la /data/local/tmp/onekey`，43 项，节选）：

| 文件 | 大小/权限 | 泄露内容 |
|---|---|---|
| `root_stage.log` | 1066 B / 644 | **root 证据日志**：`uid=0(root) ... context=u:r:ksu:s0`、ksud `version: 40994`、`uapi_version: 5`、`lkm: true`、`late_load: true`、管理器登记、「默认 su 入口通过：/system_ext/bin/su」等 |
| `root_run` / `root_backend` / `root_stage.ok` | 644 | `ROOT_STAGE_OK:<uuid>`、backend=`sukisu` |
| `su_path_compat.sh` | 2236 B / 755 | **完整的 overlay 安装逻辑**（`onekey-su`、`/data/adb/onekey-su-compat`、target 选择） |
| `su_forward` | 9056 B / 755 | 与 `/system_ext/bin/su` 相同的 su 转发 ELF |
| `root_stage_mobile.sh` | 在 `/data/local/tmp/root_stage.sh` | 完整 root staging 脚本（含 ksud install / manager add / `su -M` 调 compat） |
| `manager.apk` | 16,682,167 B / 644 | SukiSU Ultra 管理器 APK（PK 头可读） |
| `ksud_manager` / `ksud_sukisu` / `ksud342` | 5,522,800 / 7,059,584 B | ksud 二进制 |
| `t64560_v36` / `t64560_v42` / `forker` / `ksu_grant` / `perf_cred` / `runas` / `sysprobe` | 130 KB–1 MB / 755 | CVE-2026-64560 exploit / 提权辅助二进制 |
| `bl_*.bin` / `bl_text*.txt` / `c_bl.txt` | 最大 8 MB | boot.img 组件 / 分析中间产物 |
| `diag*.txt` / `diag2/` / `diag3/` / `dmesg_now.log` / `suverify.txt` / `uidtest*.txt` / `root_id.txt` | 644 | 历史诊断：`suverify.txt` 内含 `kernelsu ... Live`、`/data/adb/ksud` 软链、`uid=0(root) context=u:r:ksu:s0` 等 |

同类持久残留还有：`/data/local/tmp/ksud342`、`/data/local/tmp/ksud_next`、`/data/local/tmp/ksu-op.ko`、`/data/local/tmp/ksu-op2.ko`、`/data/local/tmp/ksu-op3.ko`（644，可读 ELF）、`/data/local/tmp/root_stage.sh`、`/data/local/tmp/adbroot_stage.log`、`/data/local/tmp/forker`、`/data/local/tmp/ksu_grant` 等（父目录 0771 不可列，但文件名高度可猜：`onekey`/`ksu`/`ksud`/`root_stage`）。

**重要**：overlay 重启即消失（只读、不落盘），但 `/data/local/tmp/onekey` 在磁盘上**重启后仍在**——这是比 overlay 更持久的检测面。

### 3.9 负向结果（对越狱有利的部分）

- `/system/bin/su` 对 App 不可见（sucompat 生效）。
- `/data/adb` 对 App/shell 均不可读（700 root:root + SELinux）。
- `/proc/modules`、`/proc/kallsyms`、`dmesg` 对 App 不可读；`ps` 因 `hidepid=invisible` 看不到其他 uid。
- `/sys/module/kernelsu` 不存在（`kobject_del`）。
- SELinux `Enforcing`，`verifiedbootstate=green`、`flash.locked=1`（没有解锁警告）、`release-keys`、`ro.debuggable=0` —— 传统「属性/AVB」类检测干净。
- 已装的隐藏模块 `YH_YC`（月虹一键隐藏模块 v5.5.4，`yhyc` 守护进程 pid 2120 正在运行）**并没有**隐藏上述 overlay：实测 3 个 App 进程的 mount ns 都仍带 `onekey-su`（可能该模块只处理它自己的隐藏范围，或未针对挂载面）。

---

## 4. 检测 App 会怎么写（可直接验证的样例）

```java
// 1) mountinfo —— 零权限，最致命
for (String l : Files.readAllLines(Paths.get("/proc/self/mountinfo")))
    if (l.contains("onekey-su") || (l.contains("/system_ext/bin") && l.contains("overlay")))
        return "ROOT: onekey-su overlay";

// 2) statfs —— 零权限（需少量 JNI 或 Os.statfs）
//    f_type == 0x794c7630 (OVERLAYFS_SUPER_MAGIC) 且路径为 /system_ext/bin

// 3) 真实 su 文件（已知路径）
File su = new File("/system_ext/bin/su");
if (su.exists() && su.length() == 9056) return "ROOT: onekey su_forward";

// 4) 执行探测
Process p = Runtime.getRuntime().exec(new String[]{"/system_ext/bin/su", "-c", "id -u"});
int rc = p.waitFor();   // rc == 127，stderr 含 "内核接口"

// 5) 工作区（已知/可猜路径）
if (new File("/data/local/tmp/onekey/root_stage.log").canRead()) return "ROOT: onekey workspace";
if (new File("/data/local/tmp/ksu-op3.ko").canRead())        return "ROOT: ksu module";

// 6) 包名（需 QUERY_ALL_PACKAGES）
if (pm.getPackageInfo("com.sukisu.ultra", 0) != null) return "ROOT: SukiSU manager";
if (pm.getPackageInfo("dev.onekey.jb", 0) != null)    return "ROOT: onekey app";
```

shell/命令行等价（本审计脚本已封装）：

```bash
adb shell 'grep -i onekey /proc/self/mountinfo; df /system_ext/bin | tail -1; stat -c %s /system_ext/bin/su'
```

---

## 5. 风险分级与整改建议

### P0-1：`onekey-su` 全局 overlay 暴露给所有 App

- **成因**：`su_path_compat.sh` 用 `su -M` 在 init ns 挂 `shared:102` 的 overlay → 传播到所有 App 命名空间。
- **建议（按优先级）**：
  1. **不再需要这个 overlay 时就不要挂**：普通 App 本来不可见 `/system/bin/su`，sucompat 已覆盖白名单调用方；compat overlay 只对「shell 里 PATH 搜 su / 绕过 faccessat 预检查」有意义，收益小、暴露大。若 Stellar shell 可以直接绝对路径调用，评估删除 overlay 后整条链是否仍通过（`root_stage_mobile.sh` 的 probe 需要改）。
  2. **私有命名空间挂载**：若必须保留，改为在「需要它的进程自己的 mount namespace」里挂（先 `unshare -m` + `mount --make-rprivate /`，再 mount），不要 `-M` 全局挂载；这样 App 命名空间不会收到克隆。
  3. **降低字符串特征**：`source` 名改中性（如 `none`）、`lowerdir` 移出 `/data/adb`（例如放到 0700 的 shell 私有目录），避免 `onekey-su`/`data/adb` 关键字直出。这只是降低 grep 命中率，不能根除 mountinfo 中「多出来的 overlay 条目」。
  4. 可用 KSU 的 `ADD_TRY_UMOUNT`/mount-hide 类能力（KernelSU-Next 有 try-umount ioctl）把该 mount 在非 root App 的 ns 里隐藏——需实测该 ioctl 对本 overlay 是否生效。

### P0-2：`/data/local/tmp/onekey` 持久工作区

- **成因**：`ShizukuShell.WORK=/data/local/tmp/onekey`，目录 0755、结果文件 0644（为让 app uid 读回结果）；`root_stage.log`/`root_stage.ok` 还显式 `chmod 644`。
- **建议**：
  1. **短期**：清理历史残留（需人工确认后执行，本次审计不执行）：`/data/local/tmp/onekey/`、`/data/local/tmp/ksu-op*.ko`、`/data/local/tmp/ksud342`、`/data/local/tmp/ksud_next`、`/data/local/tmp/root_stage.sh`、`/data/local/tmp/adbroot_stage.log`、`/data/local/tmp/forker`、`/data/local/tmp/ksu_grant`、`/data/local/tmp/bl_*.bin`、`/data/local/tmp/manager.apk`。
  2. **改设计（2.3.4+）**：结果不要经过世界可读文件——直接读 `Stellar.newProcess(...)` 的 stdout（`ShizukuShell.run/start` 已有 line callback），或由 shell 侧创建文件后 `chown <app_uid>` 并放到 app 私有目录（`/data/data/<pkg>/...`，其他 App 不可读）；工作目录改 0700，运行结束 `rm -rf`；目录/文件名去 `onekey`/`ksu`/`su` 特征。
  3. 保留 `/data/local/tmp` 只放「短命、0700 目录内、跑完即删」的中间文件。

### P1：`/system_ext/bin/su`、statfs/mtime 等「文件级」痕迹

- 与 P0-1 同源：overlay 私有化后自然消失。
- 若保留全局挂载：无法消除 `stat(/system_ext/bin)` 的 overlay inode/mtime 差异；只能接受或改为「不新增文件、不新增挂载」的方案。
- 另：`su_forward` 的报错文案「内核接口未允许执行」属于主动暴露特征，若保留该二进制，建议改成通用文案（如 `su: permission denied`）。

### P2：包名/应用列表

- 普通 App 默认看不到，但带 `QUERY_ALL_PACKAGES` 的检测 App 可枚举 `com.sukisu.ultra`/`dev.onekey.jb`。
- 可用已装 HMA（`com.tsng.hidemyapplist`，LSPosed/zygisksu 已装）+ 对检测 App 隐藏；或考虑管理器随机包名（SukiSU 是否支持需确认）。
- 本机已装 `icu.nullptr.applistdetector`（v2.4，已授予 QUERY_ALL_PACKAGES）：建议把它作为回归验收工具（打开后看是否报 `com.sukisu.ultra`/`dev.onekey.jb`）。

### 自检流程

- 每次越狱/每次发版：`SAVE=1 tools/detection_audit.sh`，确认 §0 表中 P0 项全部为 `NO`；本轮实测为 3 个 YES（overlay、statfs、workspace）。
- 需要 App 视角回归时，用 `run-as`（无需 root）或在 App 内新增一个调试页跑同样命令。

---

## 6. 复现

```bash
# 完整审计（只读；自动用 run-as 取 App uid 视角，可选 untrusted_app 域交叉验证）
SAVE=1 tools/detection_audit.sh

# 输出：屏幕 + evidence/detection-audit-2.3.3/detection_audit.out

# 手工最小复现
S=3B16AA00ANP00000
adb -s $S shell 'grep -i onekey /proc/self/mountinfo'
adb -s $S shell 'df /system_ext/bin | tail -1'
adb -s $S shell 'stat -c "%n|%F|%s|%a|%u:%g|%C" /system_ext/bin/su /system/bin/su'
adb -s $S shell 'run-as bin.mt.termex ls /data/local/tmp/onekey | wc -l'
adb -s $S shell 'run-as bin.mt.termex head -1 /data/local/tmp/onekey/root_stage.log'
```

证据文件（`evidence/detection-audit-2.3.3/`）：

- `detection_audit.out` —— 脚本完整原始输出（125 行）
- `mountinfo.shell.txt` / `mountinfo.init.txt` / `mountinfo.manager.txt` / `mountinfo.systemui.txt` / `mountinfo.gallery.txt` —— 各命名空间 mountinfo 原始快照
- `onekey_workspace_listing.txt` / `data_local_tmp_listing.txt` / `data_adb_root_listing.txt` / `packages_thirdparty.txt` / `getprop_shell.txt`

---

## 7. 限制与未覆盖

1. **代理视角精度**：`runas_app`（run-as）与 `untrusted_app:s0`（setcon 构造）都不是 100% 真实 App 域（真实 App 为 `untrusted_app_35:s0:c...`，带 MLS category、seccomp 过滤器）。但本审计涉及的所有判定点（`system_file`、`shell_data_file`、`proc_*`、mountinfo 读取）与 category/seccomp 无关，且两套视角结论一致；`CapEff=0`、uid 正确。
2. **未安装测试 APK**：未向设备安装任何检测 App，避免修改系统；`QUERY_ALL_PACKAGES` 场景用「已装检测 App + shell 侧行为」推断（`icu.nullptr.applistdetector` 已授予该权限，但本审计未启动它）。
3. **单 boot 快照**：审计期间设备状态可能被其他流程改动（如 22:08 有 ksud/管理器更新）。overlay 与 `/data/local/tmp` 内容以 22:44–22:57 快照为准；重启后 overlay 应消失、`/data/local/tmp` 残留仍在。
4. **未测试 KSU mount-hide / try-umount 是否能隐藏本 overlay**（属于整改验证项，需要 root 下调用 ioctl，超出「只读审计」范围）。
5. **未测试真实恶意检测 App 的完整规则集**（如银行/风控 SDK 会同时查 `su` 关键字、mount 列表、ptrace、包名、系统属性、/proc 扫描等）；本报告只列「已确认 App 可见」的硬指标，按 P0→P2 修复可将 App 视角的可见痕迹降到「只剩包名/应用列表」（可再用 HMA 处理）。

---

*审计人：detection-audit（Team）· 2026-10-20 22:57 CST · 全程只读，未修改设备。*
