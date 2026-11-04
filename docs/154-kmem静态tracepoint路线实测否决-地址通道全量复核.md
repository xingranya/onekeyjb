# 154 — kmem 静态 tracepoint 路线实测否决 · 地址泄漏通道全量复核

> 2026-10-18 02:40
> 目的：验证「静态 `kmem_cache_alloc` tracepoint → 直接拿 `task_struct`/`cred` 地址」
> 这条建议在本机是否可行；若不可行，复盘既有能力里还有哪些可用于拿内核地址。

## 0. 一句话结论

**`kmem_cache_alloc` 这条路在"使能"环节就被堵死，且堵点不在我们这一侧。**
本机能建探针、能读 raw 环形缓冲、能读 `/proc/vmallocinfo`，但**没有任何一个我们可驱动的
特权域愿意使能 `kmem/*` / `workqueue/*` / `timer/*` 这些带指针载荷的事件**。
而真正缺的仍然是**一个内核地址**（文本地址给 SELinux permissive；task/cred 地址给直接提权）。

---

## 1. 事件确实存在，但 `format` 两域都读不到

```
$ ls /sys/kernel/tracing/events/kmem/
dmabuf_rss_stat  kfree  kmalloc  kmem_cache_alloc  kmem_cache_free  mm_page_alloc ...
```

但 `kmem_cache_alloc/format` 对 **shell 与 hypsys(root) 都是 `Permission denied`**
（该文件属 `debugfs_tracing_debug` 类型）。这一条直接决定了后面 simpleperf 无法识别它。

## 2. perfetto 只使能"它认识的类型"（关键否决）

同一套配置只换 `ftrace_events`，2.5s 窗口内 `trace` 里的实际条数：

| 事件 | 结果 |
|---|---|
| `sched/sched_switch` | **301–837 条** ✅ |
| `irq/irq_handler_entry` | **60 条** ✅ |
| `binder/binder_transaction` | **14 条** ✅ |
| `power/cpu_frequency` | 3 条 ✅ |
| **`kmem/kmem_cache_alloc`** | **0** ❌ |
| **`kmem/mm_page_alloc`** | **0** ❌ |
| **`kmem/kmalloc`** | **0** ❌ |
| **`workqueue/workqueue_execute_start`** | **0** ❌ |
| **`workqueue/workqueue_queue_work`** | **0** ❌ |
| **`timer/hrtimer_expire_entry`** | **0** ❌ |

**通配符也不行**（同样 0 条）：`kmem/*`、`workqueue/*`、甚至 `sched/*` 和 `*` 全部 0，
说明 perfetto v54 只接受它内置类型清单里的**精确事件名**。

空闲态 1.2 秒只采 `kmem_cache_alloc`：产物 **1524 字节、0 条记录**（系统每秒上千次分配，
若使能成功不可能为 0）。

> 顺带否掉了"用 workqueue_execute_start 的 `function` 字段拿 KASLR"的想法：
> 这个事件载荷恰好是 `[work 指针][function 文本指针]`，本来是理想目标，但它根本使能不了。

## 3. 三个"本可以"的旁路，逐个被堵

### 3.1 raw 环形缓冲区：**我们能读**（新发现，但没内容）

```
-r--r----- 1 root readtracefs  /sys/kernel/tracing/per_cpu/cpu0/trace_pipe_raw
```
shell 属于 `readtracefs`(3012) 组 ⇒ **可以读原始二进制**。实测抓到 1.1–2.5 MB，
但内容**全**是 MIUI 自己 atrace 的 `tracing_mark_write`（`B|2493|sensorDataCallback` 之类），
因为 `kmem` 从未被使能，缓冲区里当然没有它。

### 3.2 文本通路：指针被哈希，不可用

`/sys/kernel/tracing/trace_options` 里 **`hash-ptr` 处于开启状态**（`... copy_trace_marker nopause-on-trace hash-ptr ...`）
⇒ 文本里的 `%p` 是哈希值（每启动一个密钥，事后不可逆）。所以"看 trace 文本里的 ptr"
这条路从一开始就不成立 —— 这一点与你给的复盘意见一致，实测确认。

### 3.3 simpleperf：识别不了、还要 root

```
$ simpleperf list | grep kmem:
kmem:dmabuf_rss_stat / kmem:mm_calculate_totalreserve_pages / kmem:mm_page_alloc / kmem:rss_stat
```
152 个 tracepoint 里只有这 4 个 `kmem`，**恰好是"没有 `__string` 字段"的那几个**；
`kmalloc` / `kmem_cache_alloc` / `kmem_cache_free` 都被它跳过（因为 `format` 读不到、无法解析 `__data_loc`）。

```
$ simpleperf kmem record ...
E cmd_kmem.cpp:366] simpleperf kmem record command needs root privilege
$ simpleperf monitor -a -e ...:u
E cmd_monitor.cpp:437] System wide profiling needs root privilege
$ simpleperf record -a -e 'kprobes:...:u'
E ProbeEvents.cpp:200] failed to write 'p:...:u' to kprobe_events: Invalid argument
```
⇒ 系统级采集要 root；内核采样被拒（提示只能 `:u`）；它自己去建 kprobe 也失败。

## 4. 本机 tracefs 实际权限地图（一手 `ls -lZ`）

| 文件 | 标签 | 模式 | 我们可写 |
|---|---|---|---|
| `kprobe_events` | `debugfs_tracing` | 0777 | ✅ 实测建探针成功 |
| `uprobe_events` | `debugfs_tracing` | 0777 | ✅ |
| `synthetic_events` | `debugfs_tracing` | 0666 | ✅ |
| `trace` | `debugfs_tracing` | 0666 | ✅（写=清空） |
| `tracing_on` / `trace_clock` / `buffer_size_kb` | `debugfs_tracing` | 0666 | ✅ |
| `trace_marker_raw` | `debugfs_trace_marker` | 0220(组) | ✅ |
| **`events/`（含每事件 `enable`）** | `debugfs_tracing_debug` | 目录 | ❌ `avc denied {append}` |
| **`options/`、`per_cpu/`** | `debugfs_tracing_debug` | 目录 | ❌ |
| `set_event`、`current_tracer`、`available_events` | `debugfs_tracing_debug` | — | ❌ |

**结论：能建探针（`kprobe_events` 可写），不能开探针（`enable` 不可写）。**
参考实现的 `--probe-task` 卡在同一处，不是设计问题，是 SELinux 类型划分问题。

## 5. "崩溃日志拿 slide"这条老路也已封死

三次不同启动的内核 panic 里 `Kernel Offset` 分别：

```
0x150d400000   0x1790200000   0x23cf400000
```
⇒ **每次启动都重新随机，旧日志里的 slide 不能跨启动复用。**

那 WARN（不重启）会不会打印？现场复现了一次 `posix-cpu-timers.c:494` WARN
（`t64560_v15b 4000 400 8 200 1`，9 秒完成），然后取 live dmesg：

```
WARNING: CPU: 5 PID: 17268 at kernel/time/posix-cpu-timers.c:494 posix_cpu_timer_del+0x104/0x160
（全 4341 行里 "Kernel Offset" 出现 0 次）
```
⇒ **WARN 不打印 offset，只有 oops/panic 路径才打印**；而 oops 在这里必然重启。

## 6. 全量地址通道复核（hypsys root 身份逐个试）

| 通道 | 结果 |
|---|---|
| `/proc/kallsyms` | ❌ Permission denied（SELinux，非 kptr_restrict） |
| `/proc/modules` | ❌ |
| `/proc/iomem` | ❌ |
| `/proc/timer_list` | ❌ |
| `/proc/sched_debug` | ❌ |
| `/proc/sys/kernel/kptr_restrict` | ❌ |
| `/proc/sys/kernel/panic_on_oops` | ❌（顺带否决"关 panic_on_oops 让 oops 不留重启"） |
| `/sys/kernel/tracing/printk_formats` | ❌ |
| `/sys/module/msm_kgsl/sections/.text` | ❌ |
| `/sys/kernel/notes` | ❌ |
| `/sys/kernel/vmcoreinfo` | ❌ 不存在（无 kexec/crash 支持） |
| **`/proc/vmallocinfo`** | ✅ **可读**（只有 vmalloc 区地址，无文本地址） |
| `/proc/slabinfo` | ✅（无地址） |
| `/sys/kernel/tracing/trace`、`per_cpu/*/trace_pipe_raw` | ✅（无可用事件） |

`dmesg` 里能搜到的 `ffffffc0…` 只有 **VMAP 栈指针**（`sp : ffffffc0e74b3db0`），不是文本地址。

## 7. 复盘：旧能力对新目标（拿一个内核地址）的可用性

| 旧能力 | 能否给地址 | 说明 |
|---|---|---|
| **64560 的堆地址泄漏**（`RB_CLEAR_NODE` 把节点地址写回我们页） | ✅ 堆页地址 | 已实测，`0xffffff89…`，低 12 位与页内偏移严格对应 |
| **64560 的 8 字节零写**（`rb_erase` → `parent->rb_right = NULL`） | — | 写原语已有，缺目标地址 |
| 64560 的 `collect` 路径（`ctmr->task = current`） | ✅ **task_struct 地址** | 唯一已知的"文本无关"地址来源，但未落地时会崩（v29 实测） |
| 98258 list-debug 读 | ❌ | 命中率不可靠，且只能读我们自己给的指针 |
| `mi_game` 按 pid 找 task | ❌ | 内核内部取 task，但不回传 |
| `/proc/slabinfo` + grooming | 部分 | 只能"造已知页"，不能给 cred 地址 |
| uinput 崩溃寄存器 | ❌ | 每次依赖 panic，地址不能跨启动 |
| perfobserve / perfetto trace | ❌ | trace 目录受 root 限制；且 perfetto 只认固定事件类型 |
| kgsl PM4 | ❌ | GPU SMMU 不映射内核线性区 |
| **本轮新增**：`raw` 环形缓冲可读、`kprobe_events`/`uprobe_events`/`synthetic_events` 可写 | 待组合 | 一旦有了能写 `enable` 的域，这条链立刻可用 |

**唯一还活着的地址来源：`collect_posix_cputimers` 把 `current` 写进我们的页。**

## 8. 因此下一步应该做什么（明确方案，可执行）

要把 `collect` 用起来，必须先解决"不落地就崩"（v29 的根因）。办法是把**插入侧**当 oracle：

1. 伪造节点改成 **BLACK + parent=NULL**（`__rb_parent_color = 1`）。
   - 插入时 `rb_is_black(parent)` 立即 break ⇒ 不会去解引用 `gparent`，安全；
   - 删除时 `rebalance = (black ? parent : NULL) = NULL` ⇒ 依然不崩。
2. 计时器仍 **arm 3600s**（不产生到期）。风暴 + 排空 + 喷 8KB frag 照旧。
3. **落地检测（不销毁节点）**：父进程对 victim 的 CPU clock 再 `timer_create` 一个**远期**计时器。
   `timerqueue_add` 会沿树下行并在父节点上写 `parent->rb_left/right = new_node`
   ⇒ 若已落地，**一个新 k_itimer 的真实地址会出现在我们页的 `node+8/+0x10`**，
   用 `MSG_PEEK` 读回即可判"这轮是不是我们"（且不 exit、不 cleanup）。
4. 确认落地后，再 `settime(50ms)` 让 cputimer 到点 ⇒ `collect` 走我们的 fake node
   ⇒ `current`(=task_struct*) 落到 `node+0x48`，同时 `expires=0` 使处理必然发生。
5. 拿到 task 地址后，`task+0x900/0x908` 是 `real_cred/cred`（BTF 实测）；
   再用"写指针值"形态的 `rb_erase`（`child != NULL` 分支，值必须指向我们自己的可写页）
   把 `task->cred` 指向我们页里的 fake cred。

> 这套的关键判据全部可观测：第 3 步读回的是**真实内核堆指针**；
> 第 4 步读回的是**task_struct 指针**（与 `/proc/<pid>/stat` 无关但可通过 comm 交叉验证）。

## 9. 产物

- `analysis/probe_addr_channels.py` — 地址通道批量探测器（root 身份）
- `analysis/rootwrite.py` — root 任意文件写（`log -t` + `logcat -f`），本轮的配套工具
- `poc/kprobe/{forker.c, rawgrab.sh, wqgrab*.sh, evtest.sh, wctest.sh}` — 本轮实验脚本
- `analysis/out/{rawkmem/,wq/,kmem*.pb,fork.pb,alloc2.pb}` — 采集样本
