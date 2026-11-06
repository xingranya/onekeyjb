### OneKey v2.19.2

合入上游 **KSU-Universal v1.2.0**，内置通用多管理器加载器升级；界面与业务逻辑不变。

#### 上游变更（v1.2.0）
- **manager ops**：每个 profile 独立选择 `setuid` / `umount` / `module_load` / 动态管理器控制流，切换 profile 只替换 ops 表
- **BakaSU**：zygote/setuid 语义、`/data/adb/ksud` 只读保护、Baka umount、`CAP_DAC_READ_SEARCH`、`block_modules` 过滤
- **FinaSU**：`vivo_module_filter` 模块过滤；动态管理器按 `KSUM` v2 持久化
- **模块加载 hook 修复**：过滤从 tracepoint 改为 syscall 表 hook，避免在内核态读取 ELF 导致卡死
- **Baka 多管理器语义修正**：不再预注册全部已安装管理器，只保留 active primary

#### 本仓库改动
- 内置 `core/exec/ksud-universal` 替换为 v1.2.0（12,652,520 B）
- 加载器就绪检测的尺寸常量同步更新
- 第三方声明里的版本链接更新为 `v1.2.0`

#### 校验
| 项 | 值 |
| --- | --- |
| 版本 | 2.19.2 / 200 |
| APK 大小 | 24,212,065 B |
| APK SHA-256 | `db7f51f651ca6783d92a17d5992500416e24f7d3870bc94b3f800e9f1ca9ac0b` |
| 内置加载器 SHA-256 | `d14f50dfb36fd27c83cf6ef51ee5193224a5bfff99f0bc3e2e7d6667a11867fd` |
| 内嵌内核模块 | `android17-6.18_kernelsu.ko`（vermagic `6.18.21-4k`） |

> 上游标注 6.12 KMI 的运行时回归待执行；本机 6.18 链路的切换行为尚未在本版本实测。
