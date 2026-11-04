# 自研 98260 执行器 · 进度

## 已完成

### M1 泄漏与 slide（真机验证）
- `native/executor/leak.c`：trigger 开 kmem_cache_alloc → 自造已知调用点 →
  扫 trace_pipe_raw 8 字节窗口 → 2MB 对齐 + 多调用点共识。
- 真机连续多次：`SLIDE_OK=0x1f1ca00000 seeds=3 votes=70万~180万`，与 onuce 自算值一致。
- 关键参数：本机 slide ≈ 133GB，任何上界筛选必须 ≥1TB。

### 执行器骨架（真机验证）
- `native/executor/{main.c,leak.c,exec.h}` → `onekey-exec`；
- 协议：VER/STAGE/PROGRESS/SLIDE_OK/WARN/ERROR/DONE；
- `run <ws> <backend>` 已跑到 `STAGE=plant` 并在 plant 处显式报未实现。

### plant 基础（已定位，未适配）
- 仓库内自带完整 98260 实现：`tools/ic98260/`（`timer_lpe.c` 1542 行 +
  `ic_shellcode_arm64.c`），参数在 `ic_timer_target.h`。
- 现有参数是**旧内核** f957acc 标定：k_itimer size 0x180 / node 0xe0 / handling 0xf0 /
  task cred 0x900 / real_cred 0x908 / timer head 0x8a0 / cred size 0xb8。
- `tools/ic98260/build.sh` 可用 NDK 29 重新构建成功（1.9MB 静态）；
  二进制已 push 到设备，直接运行暂无输出（需要按新内核重标后才能判定）。

## 待做（按顺序）

1. **重标偏移**：新内核无 BTF（扫描确认只有 DWARF 字符串表），只能从
   `vmlinux.elf` 反汇编推导：
   - `__arm64_sys_getuid` / `commit_creds` → task->cred / real_cred；
   - `posix_cpu_timer_set` → task posix_cputimers 的 CLOCK_THREAD_CPUTIME_ID 头；
   - `posix_cpu_timer_del` → k_itimer node/handling/head 偏移；
   - `prepare_creds` → cred 各字段与 size。
2. 用新偏移重建 ic98260，真机跑 dry（不带 IC_ROOT_CMD）确认命中判定。
3. 通过后接入 `onekey-exec` 的 `STAGE=plant`，再走 `load`（ksud late-load）。
4. 集成 App（NativeExecutor）并出包。

## 第 2 轮：偏移核对 + 现有 plant 真机跑通到 stage1

### 新内核布局（vmlinux.elf 反汇编实测）

| 项 | 旧参数 | 新内核实测 | 结论 |
| --- | --- | --- | --- |
| task->cred | 0x900/0x908 | getuid: ldr x8,[x8,#0x908] → cred=0x908 | 相邻字段，与旧值一致 |
| cred->uid | 0x4/0x8 | ldr w8,[x8,#8] → 0x8 | |
| cred->gid | — | 0xc | |
| cred->euid / egid | — | 0x18 / 0x1c | getresuid 交叉确认 suid 0x10 |
| cred->cap_effective | 0x50 | cap_capable: ldr x9,[x0,#0x40] → 0x40 | |
| cred->user_ns | 0x90 | cap_capable: [x0,#0x90] | 一致 |
| k_itimer 大小 | 0x180 | /proc/slabinfo posix_timers_cache objsize 384=0x180 | 一致 |
| k_itimer node / pid | 0xe0 / 0x108 | del/set 均见 [x0,#0xe0]、[x19,#0x108] | 一致 |

结论：结构布局在新内核上没变，旧参数可用；差异在代码路径（官方修复改写了
posix_cpu_timer_del 的 retry 结构），影响竞态窗口而不是偏移。

### 真机运行现状

用仓库内源码重建的 ic98260 在新内核完整跑到：

    ATTEMPT_STAGE aio-spray -> stage1 -> ATTEMPT_OUT errno=2 (ENOENT) -> 重试
    TARGET / KILLER / EXEC 线程均正常起来，execve 已执行

errno=ENOENT 来自 ic_run_stage1() 末尾的 !object 分支：oracle 批次跑完也没在
AIO 喷发页里看到内核指针，即 UAF 没落地（不是崩溃，也不是权限问题）。

### 下一轮

调参收窄竞态：IC_ORACLE_BATCH(3)、IC_ORACLE_RUN_NS(80ms)、IC_AIO_SPRAY_PAGES(64000)、
以及 stage1 扫描步长/阈值；每轮记录命中率，直到 stage1 能拿到 object。


## 第 4 轮：参数化改造 + 计时 + Shark 协议定位

- ic98260 增加环境变量调参：IC_ORACLE_RUN_NS / IC_ORACLE_BATCH / IC_MAX_ATTEMPTS / IC_SPRAY_PAGES，
  默认值调快（20ms / 16 / 200 / 2048），仍可回落原值；已重建装机。
- 实测单次尝试约 10 秒，三组参数（20ms/32/8192、80ms/3/64000、10ms/64/2048）全部 ATTEMPT_OUT errno=2，
  即 UAF 仍未落地：不是崩溃、不是权限，是回收没命中。
- 发现 Shark 的 App 侧协议（从其 dex 得出）：
  - 工作目录 /data/local/tmp/pyb110-*，环境变量 PYB110_DIR / PYB110_MANAGER / PYB110_LATE_LOAD=1
    / PYB110_SUSFS / PYB110_VFS / PYB110_UNLOAD_GUARD / PYB110_DISABLE_ANTIROOT；
  - 直接跑 t98260 会报 DIR_MISSING，需要按上面变量铺好目录。

### 下一轮
1. 按 Shark 协议铺目录跑其 t98260，读出它打印的 timers/victims/frag/settle 与命中流程；
2. 用这组参数替换 ic98260 的形状参数，观察 stage1 是否能命中；
3. 命中后接 late-load（它已证明可用）并接进 onekey-exec。


## 第 5 轮：Shark 运行环境尝试 + 关键认知修正

- 按 dex 里的协议铺了 /data/local/tmp/pyb110-shark（t98260 + manager-apk + 两个 ko），
  设了 PYB110_DIR / PYB110_MANAGER_PKG / PYB110_MANAGER_APK / PYB110_KMI / PYB110_LATE_LOAD，
  仍然固定回落到 me.weishu.kernelsu 并报 KSUD_MISSING —— 说明它的管理器选择不只看这几个环境变量，
  很可能由它自家 App 写入的配置决定（其 App 未安装）。
- 直接在其二进制里搜 timers=/victims=/settle= 等格式串未命中（strings 里的长行是拼接显示），
  想靠静态常量反推参数这条路暂时走不通。

### 重要修正

tools/ic98260/TARGET.md 明确写着 "Runtime success is not yet verified"：
**这份移植从未在真机验证过，旧内核也没验证过**。所以现在不是\u201c新内核回归\u201d，而是\u201c未验证实现 + 未知参数\u201d。
这也解释了为什么调参毫无起色：参数空间没有基准点。

### 下一轮
1. 先用最小实验确认我们这条链的\u201c命中判据\u201d本身是否成立（AIO 回收后页里应出现的内核指针形态）；
2. 若判据不成立，说明移植的形状构造与 6.18 实际分配路径不符，需要按 onuce 的 plant 阶段（同一族的实现，
   它对 layout 的判断更贴近本机）重构，而不是继续调 ic98260 的常数；
3. 同时继续尝试让 Shark 的 t98260 跑起来（补它的配置文件），拿到可对照的时序与参数。


## 第 8 轮：时序机制定位 + killer 提速

读 timer_lpe.c 搞清了整条竞态的真实时序：

1. EXEC 线程在 CLOCK_THREAD_CPUTIME_ID 上 arm 一个 timer，发布 tid；
2. LOCKER 线程在碎片化 VMA 上不断 mprotect，拖慢 exec 的 mmap 路径；
3. EXEC 自旋 5,000,000 次后 execve(/proc/self/exe)；
4. KILLER 轮询 tgkill(tgid, tid, 0) 直到 ESRCH（= de_thread 已换 tid），
   然后向 **tgid** 发 SIGKILL，让 exec 在 de_thread 之后失败，
   此时 exit_itimers/posix_cpu_timers_exit 释放 timer，而 timerqueue 仍引用该节点 → UAF。

新内核把 posix_cpu_timers_exit 放在 begin_new_exec 里、exec_task_namespaces 之后、siglock 保护内，
从 de_thread 到清理的窗口变短 → 我们的 killer 必须更快。

本轮改动：
- killer 的 ESRCH 轮询去掉 sched_yield，改紧循环 + 2e8 上限；
- EXEC 自旋次数改为可调 IC_EXEC_SPIN（默认仍 5000000）。

### 下一轮（设备恢复后）
扫 IC_EXEC_SPIN（1e6/5e6/2e7）× IC_ORACLE_RUN_NS × IC_SPRAY_PAGES，
看 stage1 是否能拿到 object；命中即接着做 fake cred 与 late-load。


## 第 8/9 轮：单次尝试的耗时剖面（真机实测，单位 ms）

给 IC_STAGE 加了时间戳后，一次完整尝试（IC_SPRAY_PAGES=512, run_ns=20ms, batch=32）：

```
[835510] helper        [835511] oracle      [835531] padding     [835533] fork
[835597] TARGET prepared  (+63)
[835853] exec thread created (+256)
[836294] EXEC execve run (+441)
[836361] KILLER tid-gone  (+67)   ← de_thread 探测成功
[839390] oracle-candidates (+3029)  ← 3 秒空等
[839423] prewarm
[846616] release-barn (+7193)      ← 最大头 7.2 秒
[849618] release-main (+3002)
[855626] delete-oracle (+6008)     ← 6 秒
[856647] prime-pcp (+1021)
[857040] aio-spray (+393)
[857084] stage1 → [860179] ENOENT (+3095)
```

单次约 27 秒，10 秒目标差在三块：prewarm(7.2s)、delete-oracle(6s)、release 系列(3s+3s)。
这说明瓶颈不是竞态本身，而是**准备/回收工序太重**；要提速必须换更轻的形状工艺，
而不是继续调 oracle 参数（oracle 只占几百毫秒）。

### 下一轮
1. 砍/并行化 prewarm 与 delete-oracle（它们是 slab/RCU 形态准备，可重叠到 stage1 等待期）；
2. 检查 3 秒 oracle-candidates 空等是否为固定 sleep，可改为事件驱动；
3. 目标：单次 <8 秒，再看命中率。


## 第 9 轮：UI 对齐 Shark（使用手册 + 5 个拓展功能，删除修复/兼容项）

按用户要求改造，真机截图确认：

- 新增「使用手册」：关于页入口 → 弹窗内含 一、准备 / 二、一键越狱 / 三、拓展功能（5 项）/ 四、异常处理；
- 维护卡片改成「拓展功能」5 个开关（真机可见）：
  1. 越狱后禁用安全弹窗（antiRootGuard）
  2. 越狱后软重启（softRebootFix）
  3. 越狱后卸载内核安全防护（unloadKernelGuard，新增，默认开）
  4. SUSFS 支持（新增，默认关）
  5. VFS 支持（新增，默认关，仅 6.18）
- 删除：修复 SU 通道、终端 su 兼容层、恢复系统组件、立即软重启、默认 SU 兼容文案；
  「环境诊断与修复」改为「环境诊断」，诊断弹窗里不再有修复 SU 入口。

验证：assembleRelease 通过；真机安装后首页/关于页截图确认上述改动生效。

### 待办
- 5 个开关目前只落盘与日志，尚未接到执行器（SUSFS/VFS 需要模块）；
- 98260 链路仍卡在 plant，需继续按 Shark 的形状参数推进。


## 第 10 轮：尝试用 Shark 参考实现拿参数（受阻）

- 已把 Shark APK 装到设备并完成 Stellar 授权（其首页显示 已授权、管理器自动识别 FinaSU、漏洞方式 CVE-2026-98260-6.18）。
- 但 `adb shell input tap` 在其界面里**完全不生效**：连续点击「一键越狱」与底部「日志」tab，屏幕内容不变
  （状态栏时间在走，说明屏幕是活的），怀疑其 Compose 手势层过滤了合成触摸事件。
- 静态找参数：在其 t98260 里搜 `timers=%d`/`victims=%d` 均未命中（`strings` 输出的长行是相邻字面量拼接），
  只找到 `settle`/`--slide`/`--settle` 这几个片段，说明参数是运行期拼进 argv 的。

### 结论
这一轮没能从 Shark 侧拿到可用的时序参数；不编造数据。

### 下一轮
1. 用 `uiautomator dump` 取 Shark 首页的真实控件与可点击节点，优先用 `am start -n ... --es` 或无障碍点击代替坐标点；
2. 同时继续我们自己的 plant：把 stage1 的命中判据做成独立最小实验（只验证 AIO 页回收是否可能），
   避免在未验证的整链上盲目调参。


## 第 11 轮：拿到 Shark 的成功日志（决定性参考）

用户提供了 Shark 在 17.0.1.201 上的完整成功日志：
- 总耗时约 3 秒（22→25），一次 tile 命中（558ms）；
- slide 一次得出：SLIDE_DERIVED=0x293f000000 site=0xffffffe9bf3da37c；
- tile 写：off=0x860，RECLAIM_WRITES=4096，HIT=1；
- cred 写：off=0x9e0，首次 uid 不对（CRED_NOT_ROOT），再补一次 tile（off=0xfe0）后 uid=0；
- 模块加载：libksud.so late-load --kmi android17-6.18 --allow-shell；
- 收尾：rmmod oplus_secure_guard_new、ksud install、antiroot stop、SUSFS/VFS 跳过。

完整规格已写入 analysis/shark/SUCCESS-SPEC.md。

### 下一轮
按该规格实现 tile 写原语（selinux_state → permissive）与 cred 写（0x9e0），
再接 late-load；每个阶段都用真机 su -c id 验收。


### 补充核对（本轮）
- 确认 ic98260 源码里**没有** tile/cred 那套原语（grep 'UAF mode' 为 0）：之前看到的 `UAF mode=tile` 来自 onuce 的输出，
  不是我们的移植。也就是说我们现有的 ic98260 是另一套 shaping（elist 扫描 + 0x30/0x68 写），与 Shark 的 tile(0x860)/cred(0x9e0) 不是同一实现。
- 结论：要复刻 Shark，需要在我们的执行器里**新写** tile/cred 两个原语；现有 ic98260 只能当脚手架（timer/exec/killer/spray 已具备）。

### 下一轮（实现 tile 写原语）
1. 复用 ic98260 的 timer/UAF/reclaim 脚手架，新增 `tile_write(target, off=0x860, writes=4096)`；
2. 首个目标：selinux_state → permissive（可用 `getenforce` 立即验证，最安全的里程碑）；
3. 通过后再做 cred(0x9e0) 与 late-load。


## 第 12 轮：我方泄漏与 Shark 逐字节对齐（关键验证）

给自研 leak 增加 `site` 输出后，真机结果：

```
SLIDE_OK=0x293f000000 seeds=3 votes=1297446 site=0xffffffe9bf157968
```

- slide **0x293f000000 与 Shark 日志里的完全一致**（同一 boot）；
- site 静态地址 = 0xffffffc080157968 = getname_flags+0x44（我们自己造的已知调用点）；
- 用同一 slide 计算 `selinux_state - 7`：
  `0xffffffc082999630 + 0x293f000000 - 7 = 0xffffffe9c1999629`，
  与 Shark 日志里的 `tile target=ffffffe9c1999629` **逐字节相同**。

结论：泄漏环节已经和 Shark 等价，目标地址计算也已对齐；
剩下唯一没实现的就是**写原语**（tile: off=0x860 / 4096 写；cred: off=0x9e0）。

### 下一轮
在 ic98260 脚手架上实现 tile 写原语，目标 selinux_state-7 → 用 getenforce 验证 permissive。

