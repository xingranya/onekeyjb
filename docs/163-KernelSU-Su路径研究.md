# 163 · KernelSU-Next 3.4.2 载入后「用户态 → root / 管理器」路径研究

> 2026-10-19 14:15 · 结论来自 KernelSU-Next v3.4.2 源码（tag `v3.4.2`）+ 本仓库 `out/ksud-3.4.2`、`out/ksu-op3.ko` 静态分析。
> **现场已实测走通**（Lead 反馈，见 §3）：`allow_shell=1` 时 shell 可以自己装 ksu driver fd 并走 `KSU_IOCTL_GRANT_ROOT` escape 成 uid 0；不需要管理器加冕即可拿 root。管理器加冕是独立问题（§4）。

---

## 0. 结论速览

| # | 结论 | 依据 |
|---|---|---|
| 1 | `allow_shell=1` 的全部语义 = 把 **uid 2000** 视为「允许 su / 允许 escape」的 uid；**不需要管理器、不需要 bundled** | `kernel/policy/allowlist.c` L272/L360、`kernel/core/init.c` L79-84 |
| 2 | shell 拿 root 的**最短路径**：装 ksu driver fd（`reboot(0xDEADBEEF,0xCAFEBABE,0,&fd)`）→ `ioctl(fd, 0x4b01 /*GRANT_ROOT*/)` → 内核 `escape_with_root_profile()` 直接给 **uid0 + CAP_FULL_SET + u:r:ksu:s0 + 关 seccomp** | `kernel/supercall/dispatch.c` L26-40 L802-808；`kernel/policy/app_profile.c` L126-227；`kernel/supercall/supercall.c` L122-162 |
| 3 | `feature list` 全 `NOT_SUPPORTED` / `feature get su_compat` EPERM **不是**「su_compat 没开」 | `GET_FEATURE` 权限门是 `manager_or_root`；ksud 把任何错误折叠成 supported=false |
| 4 | 上一轮 `track_throne: open /data/system/packages.list failed: -13` 的根因 = **ksu_cred 没有 DAC cap**：它是 `prepare_creds()` 复制**加载模块进程**的 cred；而该进程 cap_effective 来自 `cred+0x40`（低 6 位恒 0），永远没有 CAP_DAC_READ_SEARCH/OVERRIDE | `kernel/core/init.c` L136；`kernel/selinux/selinux.c` L137-142（只改 SELinux domain）；`docs/158` §2 |
| 5 | `/sys/module/kernelsu` 不存在、SELinux 载入后回弹 Enforcing，都是**模块自己的行为**，不是加载失败 | `kernel/core/init.c` L184-187、L201-205 |
| 6 | `bundled=1` 只影响 `GET_INFO` 的一个 flag，与 allow_shell 无关；late-load 注释明说「bundled flag is meaningless in jailbreak mode」 | `kernel/supercall/dispatch.c` L46-51；`userspace/ksud/src/late_load.rs` L62-63 |
| 7 | 「重试 v42 让地址位含 CAP_DAC_OVERRIDE(1)/FOWNER(3)/DAC_READ_SEARCH(2)」**不可能**：写入值是 `cred+0x40`，cred_jar 64 字节对齐 ⇒ 低 6 位恒 0 | `docs/158` §2 + 实测 cred 地址（0x…b840/0x…f6c0 等全部 0x40 对齐） |

---

## 1. 源码事实（tag v3.4.2）

源码已 clone 到 `/tmp/KernelSU-Next`（`git clone --depth 1 --branch v3.4.2 https://github.com/KernelSU-Next/KernelSU-Next.git`）。

### 1.1 模块参数 `allow_shell` / `bundled` / `norc` / `force_feature`

`kernel/core/init.c` L79-103：

```c
#ifdef CONFIG_KSU_DEBUG
bool allow_shell = true;
#else
bool allow_shell = false;          // ← 本机 ko 是 release(non-debug) 构建，默认 false
#endif
module_param(allow_shell, bool, 0);   // perm=0：不建 sysfs 节点，但 **finit_module 参数可设**
bool ksu_no_custom_rc = false;
module_param_named(norc, ksu_no_custom_rc, bool, 0);
#ifdef MODULE
bool ksu_bundled = false;
module_param_named(bundled, ksu_bundled, bool, 0);
#endif
```

- `allow_shell` 只有两处用处：`__ksu_is_allow_uid()`（allowlist.c L272）和 `ksu_get_root_profile()`（allowlist.c L360）；两处都是「uid==2000 直接按允许处理」。
- `bundled` 只有一处用处：`do_get_info()` 里把 `KSU_GET_INFO_FLAG_BUNDLED` 塞进 flags（dispatch.c L46-51），供管理器判断「LKM 是否随管理器打包」。**与 allow_shell 完全无关**。
- `force_feature=<name|id>:<value>[,<name|id>:<value>...]`（`kernel/policy/feature.c` L49-103）：在 module init 前解析，late-load 路径末尾 `ksu_feature_apply_forced()` 应用；**被 forced 的 feature 之后拒绝 `SET_FEATURE`（-EPERM）**（feature.c L219-222）。→ 加载时可用 `force_feature=adb_root:1` 绕开「必须先 root 才能 SET_FEATURE」的先后问题。

### 1.2 模块 init 的 late-load 行为（解释上一轮所有「异常」）

`kernel/core/init.c` L117-207：

```c
ksu_late_loaded = (current->pid != 1);   // 我们 finit_module 时 pid != 1 ⇒ late-load
...
if (ksu_late_loaded) {
    apply_kernelsu_rules(); cache_sid(); setup_ksu_cred();
    escape_to_root_for_init();           // 只把「当前进程」SELinux domain 改成 u:r:ksu:s0，不改 uid/caps
    ksu_allowlist_init(); ksu_load_allow_list();
    ksu_syscall_hook_manager_init();
    ksu_throne_tracker_init(); ksu_observer_init();
    ksu_boot_completed = true;
    track_throne(false);                 // ← 读 packages.list；失败导致管理器永不加冕
    ksu_feature_apply_forced();
    if (!getenforce()) { pr_info("Permissive SELinux, enforcing"); setenforce(true); }  // ← 「回弹 Enforcing」
}
#ifdef MODULE
#ifndef CONFIG_KSU_DEBUG
    kobject_del(&THIS_MODULE->mkobj.kobj);   // ← /sys/module/kernelsu 消失（release 构建）
#endif
#endif
```

`ksu_cred` 在 L136 `prepare_creds()` 创建，复制的是**加载进程**的 cred；`setup_ksu_cred()`（selinux.c L137-142）只 `transive_to_domain()`，**不改 uid/fsuid/caps**。
⇒ 用「地址派生能力」的候选进程加载时，ksu_cred 的 fsuid 可能是 0，但 cap_effective 是 `cred+0x40` 的随机高位（bit1/bit2 恒 0），读 0640 的 `/data/system/packages.list` 必 EACCES=-13。

### 1.3 `su_compat`（`/system/bin/su` → ksud 的 execve 重定向）

`kernel/feature/sucompat.c`：

- L34：`bool ksu_su_compat_enabled = true;` —— **默认开启**，不需要管理器、不需要 SET_FEATURE。
- L163-259 `ksu_handle_execve_sucompat_common()`：仅当 `ksu_is_allow_uid_for_current(current_uid)` 才介入（allow_shell=1 ⇒ shell 通过；另受 `shell_su_adb_only` feature 限制，默认 static key = false ⇒ 不限制）。
  1. `filp_open("/data/adb/ksud", O_PATH)`（用 override_creds(ksu_cred)，要求 ksu_cred 的 fsuid 能穿过 `/data/adb 0700 root`）；
  2. **先 `escape_with_root_profile()`**（uid/euid/fsuid=0、CAP_FULL_SET、u:r:ksu:s0、关 seccomp）；
  3. 再 `execveat(fd, "", argv, envp, AT_EMPTY_PATH)` 执行 ksud，**argv[0] 保留 "su"**；
  4. 成功后 `ksu_install_su_fd()` 装 scoped su-session fd。
- ksud 侧（`userspace/ksud/src/cli.rs` L569-573）：`arg0 == "su" || arg0.ends_with("/su")` → `su::root_shell()`（内核已经给过 root profile，它只负责 exec shell）。所以 **ksud 版本必须与内核 UAPI 匹配**（uapi=5；`ksucalls.rs ensure_uapi_version_matched`）。

⇒ `su -c id` 要能用，条件只有：allow_shell=1 + `/data/adb/ksud` 存在 + SELinux 不挡 exec。**模块 init 末尾的 setenforce(true) 是上一轮 126 的最可能原因**（掩盖方法是 noenforce 补丁，或载入后再把 selinux_state 的 enforcing 写 0）。

### 1.4 `adb_root` feature（让 adbd 以 root 跑）

`kernel/feature/adb_root.c`：

- `DEFINE_STATIC_KEY_FALSE(ksu_adb_root)` —— 默认关闭；`kernel_adb_root_feature_set()` 只是开关 static key。
- 打开后，**init(pid1) exec /adbd 时**被 execve hook 捕获：
  - `is_libadbroot_ok()`：`/data/adb/ksu/lib/libadbroot.so` 必须存在，否则打印 "not exists, skip adb root. Please run `ksud install`" 并跳过；
  - 把 `LD_PRELOAD=/data/adb/ksu/lib/libadbroot.so`、`LD_LIBRARY_PATH=/data/adb/ksu/lib` 压进 envp；
  - `escape_to_root_for_adb_root()`（selinux.c L299-313）：把 adbd 的 SELinux domain 改成 ksu。
- 打开方式二选一：加载时 `force_feature=adb_root:1`（推荐，不需要先 root），或 root 后 `ksud feature set adb_root 1`（SET_FEATURE 权限 = manager_or_root）。

### 1.5 ksuctl / ioctl 权限模型（`kernel/supercall/dispatch.c` L800-967）

| 命令 | 权限检查 | 说明 |
|---|---|---|
| `KSU_IOCTL_GRANT_ROOT` = `_IOC(NONE,'K',1,0)` = **0x4b01** | `allowed_for_su` | = `is_manager() || ksu_is_allow_uid_for_current(current_uid)`；**allow_shell=1 时 uid 2000 直接通过**（不看 euid） |
| `GET_INFO` / `GET_INFO_LEGACY` / `CHECK_SAFEMODE` | `always_allow` | 无权限也能读版本/uapi/flags |
| `GET_FEATURE` / `SET_FEATURE` / `GET_ALLOW_LIST` / `NEW_GET_ALLOW_LIST` / `GET_MANAGER_APPID` / `GET/SET_APP_PROFILE` / `MANAGE_MARK` / `ADD_TRY_UMOUNT` / `GET_HOOK_MODE` / `GET_VERSION_TAG` … | `manager_or_root` | **shell(2000) 调用必 EPERM**；root(uid==0) 或已被加冕的 manager uid 才可以 |
| `REPORT_EVENT` / `SET_SEPOLICY` / `SET_INIT_PGRP` / `GET_SULOG_FD` / `DISABLE_ESCAPE_TO_ROOT` | `only_root` | 仅 current_uid()==0 |

`GRANT_ROOT` 的实际效果 = `escape_with_root_profile()`（`kernel/policy/app_profile.c` L126-227）：
`prepare_creds` → uid/suid/euid/fsuid + gid 全 0 → `cap_effective/permitted/bset = CAP_FULL_SET` → `setup_groups` → `setup_selinux("u:r:ksu:s0")` → `commit_creds` → `disable_seccomp()` → `setup_mount_ns`。
注意：**若 euid 已经是 0，或线程带 `TIF_KSU_DISABLE_ESCAPE_WITH_ROOT`，会直接放弃 escape**（返回 0 但不提权）。

### 1.6 管理器加冕（`kernel/manager/throne_tracker.c` L259-405 + `manager_identity.h`）

- `is_manager()`：`ksu_manager_appid == current_uid() % 100000`。
- `track_throne()`：`override_creds(ksu_cred)` → 读 `/data/system/packages.list`（0640 `system:package_info`）→ 扫 `/data/app` 找 `base.apk` → `is_manager_apk()` 校验 APK 签名（`kernel/manager/apk_sign.c`，只认官方 manager 签名表）→ `crown_manager()` → `ksu_set_manager_appid(uid)`。
- 加冕时机：① 模块 init 末尾 `track_throne(false)`（**要求加载前管理器 APK 已安装**）；② `pkg_observer` 检测到 packages.list 被写（安装/卸载 App 触发，含 `pm install`）。
- **加冕前置条件 = ksu_cred 能读 packages.list**。三种解法：
  a) **给 ko 打 cred 补丁**（`tools/ko_patch_cred.py`：把 track_throne 里 `adrp x8,ksu_cred; ldr x8,[x8]` 改成 `adrp x8,init_cred; add x8,x8,#0`，即 `override_creds(&init_cred)`，init_cred = uid0 + CAP_FULL_SET）→ 参考工程实测成功；**下一 boot 首选**。
  b) 拿到全能力 root（`GRANT_ROOT`）后 `chmod 644 /data/system/packages.list`，再触发一次包事件。注意 system_server 会原子重写该文件、可能把权限改回 0640，需要「chmod 后立刻触发」；root profile 的 CAP_FULL_SET 保证 chmod 本身成功（v42 的地址派生 caps **永远没有 CAP_FOWNER**，不能用它 chmod）。
  c) 不用挂 packages.list 的旁路：root 进程走 `reboot()` magic 的 `CHANGE_MANAGER_UID(10006)`（`supercall.c` L148-162，要求 `current_uid()==0`，无签名校验）直接 `ksu_set_manager_appid(<appid>)`。管理器下次启动时 zygote `setresuid(10xxx)` 会命中 setuid hook → `ksu_install_fd()` 自动拿到 driver fd（`hook/setuid_hook.c` L30-38）。

### 1.7 ksud 关键行为（`userspace/ksud/src/`）

- driver fd（`ksucalls.rs` L88-141）：先 scan `/proc/self/fd` 找 `anon_inode:[ksu_driver]`/`[ksu_driver_su]`；没有就 `reboot(0xDEADBEEF, 0xCAFEBABE, 0, &fd)`（内核 reboot kprobe 用 task_work 装 fd；若被 seccomp 拦会打印 "KernelSU driver install syscall was blocked by seccomp"）。
- `ksud install`（`utils.rs` L231-251）：ensure `/data/adb` → 把 `current_exe` 复制成 `/data/adb/ksud` + restorecon 成 KSU file context → 解 assets 到 `/data/adb/ksu/bin`（busybox/bootctl/resetprop…）→ 建 `/data/adb/ksu/bin/ksud` 符号链接；`--libadbroot <path>` 时额外复制到 `/data/adb/ksu/lib/libadbroot.so`。
- `ksud debug su`（`cli.rs` L763 → `su.rs` L23-41）：`GRANT_ROOT` ioctl 后 exec `sh`（无参数，读 stdin；`printf "id\n" | …` 可用）。**不需要管理器，只需要 allow_shell 或已被加冕**。
- `ksud debug set-manager`：3.4.2 走 `/sys/module/kernelsu/parameters/ksu_debug_manager_appid`（`debug.rs` L20-52，要求 CONFIG_KSU_DEBUG）→ 本机 non-debug 且 kobject 已删除 ⇒ **此路不通**，不要试。
- `ksud feature list`（`feature.rs` L264-321）：`ksucalls::get_feature(id).unwrap_or_default()`，任何错误（EPERM 等）都退化成 `supported=false` → 打印 `NOT_SUPPORTED`。**这是权限假象，不是 feature 状态。**

---

## 2. 本仓库二进制的静态分析结论

| 文件 | 结论 |
|---|---|
| `out/ksud-3.4.2`（7,059,592 B） | 官方 release 资产 `ksud-aarch64-linux-android`（v3.4.2），字符串含 `3.4.2 (uapi: 5)`、`anon_inode:[ksu_driver_su]`、`set-manager`、`claim inherited KernelSU driver fd` → 与内核 3.4.2/uapi5 匹配，**可用** |
| `out/ksu-op3.ko`（474,454 B） | `ksu/ksunext.ko` + vermagic(OP 6.18.21…gf957accaf563) + 228 个 SHN_ABS 符号（绑定 op3 kallsyms）。**没有 cred 补丁、没有 noenforce 补丁** ⇒ 能 finit_module，但 track_throne 必 -13、加载后 SELinux 必回弹 Enforcing。`/sys/module/kernelsu` 缺失正常 |
| `out/next-caps.ko`（531,034 B） | = 官方 `aarch64_android17-6.18_kernelsu.ko`（473,552 B）+ cred 补丁（sha256 `8879737f8cdb4ce49b922ef348c52af4d2c640f3e17881e2907488cb49208fa3`，本地重跑一致）。只是中间件，vermagic/syms 未打 |
| `out/next-caps-now.ko`（531,435 B） | cred+noenforce+vermagic+syms 的**小米 boot 产物**（vermagic `…g1d099fcb35e0-abogki540753930…`，ABS 0xffffffdc…）→ **OP 不可用** |

官方 v3.4.2 release 资产（`https://github.com/KernelSU-Next/KernelSU-Next/releases/tag/v3.4.2`）：

- 内核模块（android17-6.18 KMI）：`aarch64_android17-6.18_kernelsu.ko`（473,552 B）
- 管理器 APK：`KernelSU_Next_v3.4.2_33355-release.apk`（16,682,167 B）
  直链：`https://github.com/KernelSU-Next/KernelSU-Next/releases/download/v3.4.2/KernelSU_Next_v3.4.2_33355-release.apk`
- 用户态：`ksud-aarch64-linux-android`（= 本仓库 `out/ksud-3.4.2`）
- 注：APK 里的 `lib/arm64-v8a/libadbroot.so` 就是 adb_root feature 需要的预加载库。

### 补丁链（已在本机 dry-run 全绿；换 boot 必须重跑）

```bash
# 0) 当次 boot 的符号表（op3 那套 = slide 0x28BAA00000 / _text 0xffffffe93aa00000）
python3 tools/mk_kallsyms.py ksu/vmlinux.elf <slide_hex> /tmp/kallsyms_now.txt
# 1) cred 补丁：track_throne 用 &init_cred（修 -13 / 加冕）
python3 tools/ko_patch_cred.py /tmp/official-a17-6.18_kernelsu.ko /tmp/1-caps.ko       # 473552 -> 531034
# 2) noenforce 补丁：NOP 掉 init 里的 setenforce(true)（SELinux 保持 permissive）
python3 tools/ko_patch_noenforce.py /tmp/1-caps.ko /tmp/2-noenf.ko                       # .init.text+0x148 (file 0x37148)
# 3) vermagic -> OP 内核
python3 tools/ko_vermagic.py /tmp/2-noenf.ko /tmp/3-v.ko \
  '6.18.21-android17-5-gf957accaf563-ab15926176-4k SMP preempt mod_unload modversions aarch64'
# 4) 228 个未导出符号 SHN_ABS 化
python3 tools/ko_patchsyms.py /tmp/3-v.ko /tmp/ksu-final.ko /tmp/kallsyms_now.txt        # patched 228
```

- `ko_patch_cred.py` 本仓库已有；`ko_patch_noenforce.py` 只在 `reference/CVE-2026-64560-public/tools/`（依赖 capstone）；**/tmp/ko_patch_noenforce.py 是等价的只用 pyelftools 的版本**（已实测：official/ksunext 两个 ko 都命中 `setenforce@0x148`）。
- 顺序必须是 **cred → noenforce → vermagic → syms**（syms 必须最后、且绑定当 boot）。

---

## 3. 现场已实测走通的路径（Lead 结果，归档用）

```text
allow_shell=1 时，shell(2000) 直接:
  1) syscall reboot(0xDEADBEEF, 0xCAFEBABE, 0, &fd)   # 内核 reboot kprobe -> task_work 装 anon_inode:[ksu_driver]
  2) ioctl(fd, 0x4b01 /* KSU_IOCTL_GRANT_ROOT */)     # allowed_for_su: allow_shell+shell -> 通过
     => 当前进程 escape: uid=0, CAP_FULL_SET, SELinux u:r:ksu:s0, seccomp 关闭
  3) /data/adb/ksud install                            # 铺 /data/adb/ksud + ksu/bin + context
  4) ksud feature set adb_root 1                       # 或加载时 force_feature=adb_root:1
  5) cp <APK>/lib/arm64-v8a/libadbroot.so /data/adb/ksu/lib/   # adb_root 的硬前提
     setprop ctl.restart adbd
     => init exec adbd 被 hook: 注入 LD_PRELOAD + escape domain -> adb shell = uid=0 root
  6) 管理器 com.rifsxd.ksunext 加冕并成功启动 UI
```

判据：`id` = uid=0(root)，context `u:r:ksu:s0`（su/escape 后）或 `u:r:su:s0`/`u:r:ksu:s0`（adb root 后）；`grep kernelsu /proc/modules` = Live；`getenforce` 与预期一致；dmesg 里 `Crowning manager: com.rifsxd.ksunext(uid=…)`。

---

## 4. 权限模型 / 上一轮 §10 误判逐条纠正

| §10 的判断 | 实际 |
|---|---|
| 「feature 全 NOT_SUPPORTED，feature get EPERM → su_compat 没开」 | GET_FEATURE 需要 manager_or_root；shell 必 EPERM；ksud 把错误折叠成 NOT_SUPPORTED。su_compat 默认 true |
| 「allow_shell 很可能没生效」 | stat/faccessat 的 `su->ksud!` 重定向与 execve 用**同一个** `ksu_is_allow_uid_for_current(2000)` 判定 ⇒ 那时 allow_shell 已生效 |
| 「bundled=1 可能是 allow_shell 生效的前提」 | 不是。bundled 只加一个 GET_INFO flag |
| 「/sys/module/kernelsu 不存在是模块藏了」 | non-debug 构建主动 `kobject_del`；且 module_param perm=0 本来就不建 parameters 文件 |
| 「模块读不到 packages.list 是因为 SELinux/permissive 不够」 | 是 DAC：ksu_cred 无 CAP_DAC_READ_SEARCH，文件 0640 system:package_info |
| 「SELinux 回弹原因未查」 | init.c L184-187 模块主动 `setenforce(true)`；noenforce 补丁可禁止 |
| 「su 126 是 execve 没被重定向」 | 更可能是重定向后 `execveat("/data/adb/ksud")` 被 **enforcing** 的 LSM 拒绝，或 ksu_cred 穿不过 /data/adb；permissive 下不会 |

---

## 5. 给后续排期的三条「不要浪费时间」结论

1. **v42 拿 DAC 类 caps 不可能**：stage2 写入值是 `T = cred+0x40`（`docs/158` §2.2：`*(T)=P` 之后 `RB_CLEAR_NODE(T)` 最终 `*(T)=T`），cred_jar 64B 对齐 ⇒ cap 位 0-5（CHOWN/DAC_OVERRIDE/DAC_READ_SEARCH/FOWNER/FSETID/KILL）恒 0；能拿的只有 bit≥6（SETGID/SETUID/SETPCAP/SYS_MODULE(16)/SYS_ADMIN(21)…）。所以「chmod packages.list」不能靠 v42，但**可以靠 GRANT_ROOT 的 root profile（内核常量 CAP_FULL_SET）**。
2. **写原语值只有三种：0 / T（自身地址）/ P（8K 页地址）**，不能写任意值 ⇒ 不能伪造 cap_effective 位、不能把 ksu_cred 指向伪造 cred；kprobe 任意读（`@ADDR`）能定位 task/cred 做校验，但「读到了也改不出 DAC 位」。零写 `cred+0x8/+0x18` 可把 uid/euid 变 0（对 /data/adb 这类 root 属主路径有效），对 packages.list 无效。
3. **不能 rmmod/换 ko**（README §3.1：必崩）⇒ 换 cred/noenforce 补丁必须重启后重走整条链；每 boot 必须重算 slide + 重打 228 个符号。`/tmp/chain-4-final.ko` 只是 op3 boot 的 dry-run 产物（重启即作废）。

---

## 6. 附录

### 6.1 本 boot（op3，slide=0x28BAA00000，_text=0xffffffe93aa00000）关键地址（`out/kallsyms_op3.txt`）

```text
selinux_state   0xffffffe93d379630   (enforcing 在 +0；target = selinux_state-7)
sig_enforce     0xffffffe93d34f208   (MODULE_SIG_PROTECT 实际开关，可零写)
init_cred       0xffffffe93d0f2a50
init_user_ns    0xffffffe93d0f10d8
init_task       0xffffffe93d0df240
sys_call_table  0xffffffe93bdb0d58
modules         0xffffffe93d178638
```

### 6.2 /tmp 里可直接复用的产物（本会话生成）

| 路径 | 说明 |
|---|---|
| `/tmp/ksu_crown` + `/tmp/ksu_crown.c` | aarch64 静态小工具：root 下读 `/data/local/tmp/KSU_APPID` → reboot magic 装 fd + `CHANGE_MANAGER_UID`。可作「不重载 ko 直接加冕管理器」的旁路 |
| `/tmp/ko_patch_noenforce.py` | 只用 pyelftools 的 noenforce 补丁（等价 reference 版本，免 capstone） |
| `/tmp/official-a17-6.18_kernelsu.ko` | 官方 release ko 原件（473,552 B） |
| `/tmp/chain-4-final.ko` | cred+noenforce+vermagic+syms 的 op3-boot dry-run 产物（重启后作废，勿用） |

### 6.3 源码链接（tag v3.4.2）

- https://github.com/KernelSU-Next/KernelSU-Next/blob/v3.4.2/kernel/core/init.c
- .../kernel/policy/allowlist.c
- .../kernel/policy/feature.c
- .../kernel/policy/app_profile.c
- .../kernel/feature/sucompat.c
- .../kernel/feature/adb_root.c
- .../kernel/feature/shell_su_adb_only.c
- .../kernel/supercall/dispatch.c
- .../kernel/supercall/perm.c
- .../kernel/supercall/supercall.c
- .../kernel/manager/throne_tracker.c
- .../kernel/manager/apk_sign.c
- .../kernel/manager/manager_identity.h
- .../kernel/selinux/selinux.c
- .../uapi/feature.h · .../uapi/supercall.h
- .../userspace/ksud/src/ksucalls.rs · su.rs · cli.rs · debug.rs · feature.rs · utils.rs · late_load.rs · defs.rs
