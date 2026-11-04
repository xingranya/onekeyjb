# CVE-2026-98260 一加16可行性评估与实现记录

日期：2026-10-26
目标：OnePlus 16 PYB110，ColorOS 17 / Android 17，内核
6.18.21-android17-5-gf957accaf563-ab15926176-4k。

## 1. 漏洞存在性

- CVE 记录：exec 非 leader 线程时，de_thread() 之后 POSIX CPU timer 清理
  位置过晚；bprm->point_of_no_return 之后的失败路径会先进入 exit_itimers()，
  造成 timerqueue UAF。
- 官方修复版本：Linux 6.18.54 起不受影响；6.18.21 < 6.18.54。
- 本机 ksu/vmlinux.elf 的 begin_new_exec 反汇编顺序：
  unshare_files -> exec_mmap -> exec_task_namespaces -> posix_cpu_timers_exit，
  与漏洞版本的“清理在可失败操作之后”一致，未见厂商提前回移修复。

结论：**漏洞在本机内核上存在**。

## 2. 两个仓库的可用性

| 仓库 | 目标 | 结论 |
| --- | --- | --- |
| InfinityCore-Android-CVE-2026-98260 | 7.0 系布局，单文件 | k_itimer 布局与本机 6.18 接近（0x180 / node 0xe0 / handling 0xf0），可作为移植基础；task/cred 偏移需改 |
| CVE-2026-98260-popsicle-android | 小米 6.12.69 精确构建 | 三阶段二进制、硬编码小米偏移/符号/vmemmap，不能直接用于一加 |

## 3. 本机关键布局（来自 vmlinux 反汇编）

- posix_timers kmem_cache 的 size = 0x180
- posix_cpu_timer_del：k_itimer node 0xe0、handling 0xf0、head 0x100、pid 0x108
- posix_cpu_timer_set：task + 0x868 + 0x18 * clock + 8 为 timerqueue head；
  CLOCK_THREAD_CPUTIME_ID 使用 clock index 2，因此 head = task + 0x8a0
- commit_creds：current real_cred = task + 0x900，cred = task + 0x908
- prepare_creds：cred size = 0xb8；getresuid 显示 uid 0x8、suid 0x10、
  euid 0x18；caps 0x30/0x38/0x40/0x48/0x50；security 0x80；user_ns 0x90

## 4. 移植改动

- tools/ic98260/ic_timer_target.h：
  - OFF_TASK_CRED 0xd00 -> 0x900
  - OFF_TASK_CPU_TIMER_HEAD 0xca0 -> 0x8a0
  - OFF_TASK_REAL_CRED 0xd08 -> 0x908
- timer_lpe.c：增加 IC_ROOT_CMD，成功子进程执行指定 root 证明脚本；
  父进程收到成功信号后退出，便于 App 收集结果。
- 生成 arm64 静态二进制 merged-app/app/src/main/assets/core/ic98260。

## 5. 尚未验证/高风险

- 6.18.21 的 user_namespace、selinux label/security 结构布局未从 BTF 实测；
  InfinityCore 的 fake userns/security 常量可能仍需调整。
- SLUB/RCU/AIO 堆布局与 7.0 目标不同，需要真机观测目标 slab 和页重用。
- 当前代码在临时 root 成功后继续尝试 root 阶段：resolve/patch LKM、insmod、ksud install、post.sh 管理器登记。该全链尚未真机验证；如果 SELinux/模块签名或 heap 布局不匹配，会停在对应阶段并保留日志。
- 真机运行时必须以 root.ok、root.id、root.enforce 三个结果为准，不能仅凭进程退出码。

## 6. App 集成

首页新增“越狱实现”切换：

- 64560 稳定链：现有自适应管理器 + StableEngine，默认。
- 98260 实验链：Stellar 投放 core/ic98260，root 子进程继续 resolve/patch、加载 LKM、安装 ksud、登记管理器；
  与稳定链共用运行锁，互斥，失败不自动回滚。

代码位置：

- InfinityEngine.kt
- JailbreakController.kt（exploitKind、runInfinity98260）
- MainActivity.kt（ExploitCard、条件显示管理器卡片）
- tools/ic98260/

## 7. 结论

**静态可行性成立**：漏洞存在，k_itimer 布局与移植源匹配，所需 offset 已按本机
vmlinux 修正，二进制已编译进 App。**运行时可行性尚未证明**：缺少本机 BTF 与
真机调试，fake userns/SELinux 和堆布局可能失败。当前状态应标注为“实验链”，
真机通过后再决定是否把 98260 作为正式实现。
