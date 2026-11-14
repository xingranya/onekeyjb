# 第三方组件与许可声明

OneKey（下称"本应用"）在运行过程中会调用、加载或以二进制形式分发以下第三方组件。
各组件的著作权归其原作者所有，本应用不主张任何权利，亦不代表上述项目对本应用的认可。

## 一、内核模块（GPL）

本应用分发的内核模块由上游项目编译而来，遵循 GPL 许可，其完整对应源码可从下列上游仓库获取：

| 组件 | 文件 | 作者 | 许可证 | 上游 |
| --- | --- | --- | --- | --- |
| SUSFS Guard LKM v2.3.0-gki | susfs_guard_lkm.ko | susfs4ksu 项目 | GPL-2.0 | https://github.com/simonpunk/susfs4ksu |
| Hybrid Mount | hybridmount-6.18.21.ko | maxsteeel | GPL-2.0 | 随 SUSFS / KernelSU 生态发布（见模块 author 字段） |

如需上述模块的对应源码，可在本仓库 Issues 中索取，或直接取用上游仓库同版本源码；
本应用未对模块逻辑做修改，仅按设备内核版本重新编译并对齐 vermagic。

## 二、Root 管理器

应用不内置管理器，运行时读取用户已安装的管理器 APK 与其 libksud：

| 组件 | 作者 / 组织 | 许可证 | 上游 |
| --- | --- | --- | --- |
| KernelSU | tiann 及贡献者 | GPL-3.0（kernel 目录 GPL-2.0） | https://github.com/tiann/KernelSU |
| KernelSU-Next | KernelSU-Next 组织 | GPL-3.0（kernel 目录 GPL-2.0） | https://github.com/KernelSU-Next/KernelSU-Next |
| SukiSU Ultra | SukiSU 组织 | GPL-3.0 | https://github.com/SukiSU-Ultra/SukiSU-Ultra |
| BakaSU | BakaSU 项目 | GPL-3.0 | 见其官方仓库 |
| FinaSU | FinaSU 项目 | GPL-3.0 | 见其官方仓库 |

## 三、界面与运行时

| 组件 | 作者 | 许可证 | 上游 |
| --- | --- | --- | --- |
| KSU-Universal | Yunnijian | GPL-3.0（内核源码文件标注 GPL-2.0） | 多管理器运行时 profile 切换架构与 /proc/kernelsu_manager 协议（https://github.com/Yunnijian/KSU-Universal） |
| ksud-universal（内置） | Yunnijian / KSU-Universal 项目 | GPL-3.0 | 内置的通用多管理器加载器，内嵌 6.18 通用内核模块；未修改，按原样分发（https://github.com/Yunnijian/KSU-Universal/releases/tag/v1.2.0） |
| 98260 执行器 · 6.18.21（内置） | Yunnijian / Shark 项目 | GPL-3.0 | `assets/core/exec/onekey-98260`，取自上游 `libk61821.so`，未修改按原样分发（https://github.com/Yunnijian/Shark） |
| 98260 执行器 · 6.12.69（内置） | Yunnijian / Shark 项目 | GPL-3.0 | `assets/core/exec/onekey-98260-61269`，取自上游 `libk61269.so`，未修改按原样分发（https://github.com/Yunnijian/Shark） |
| SUSFS Guard LKM · 6.12（内置） | susfs4ksu / Shark 项目 | GPL-2.0 | `assets/core/exec/susfs_guard_lkm_612.ko`，取自上游 `libsusfs_guard_lkm_612.so`（https://github.com/Yunnijian/Shark） |
| COUI（Compose 组件库、模糊与偏好组件） | suqi8 | Apache-2.0 | https://github.com/suqi8/coui |
| Stellar（特权命令服务通道） | roro2239 | 见其仓库 | https://github.com/roro2239/Stellar |
| AndroidX / Jetpack Compose / Kotlin | Google、JetBrains | Apache-2.0 | https://developer.android.com/jetpack |

## 四、漏洞利用相关实现与参考

本应用的越狱链路基于公开研究与公开实现整理，特此致谢：

- **Shark**（Yunnijian）：98260 执行引擎，GPL-3.0；本应用内置的两个执行器与 6.12 SUSFS 模块均来自该项目，
  基线为上游 `main` 提交 `49c3b0e`（v2.3.2，2026-11-13），按原样分发，未做修改
- **InfinityCore**：98260 的公开参考实现，仅作研究参考，未分发
- **onuce**：早期 98260 链路的实现与调试经验，仅作研究参考；其载荷已自本版本起全部移除，不再随应用分发
- 相关内核漏洞的公开分析与复现资料

上述参考项目本应用不转售、不再分发其源代码；实际分发的组件以第三节的内置清单为准。

## 五、pc98260 临时 root 通道（su / rootd）· MIT · 已全部移除

本应用早期版本的 98260 临时 root 通道改编自第三方 MIT 项目。**相关源码与全部编译产物已自本版本起
从仓库与 APK 中移除**，当前版本不再分发该部分代码；但历史提交与已发布的历史版本 APK 中仍包含它们，
故在此保留完整署名与许可声明。

- 原项目：https://github.com/quyicheng03-boop/CVE-2026-98260-popsicle-android
- 原作者：**Copyright (c) 2026 quyicheng03-boop**
- 许可证：**MIT**，完整文本见 [`LICENSES/MIT-pc98260.txt`](LICENSES/MIT-pc98260.txt)（与上游 LICENSE 逐字节一致）
- 上游基线提交：`8525ad4`（2026-10-23），对应文件 `src/pc98260_su.c`、`src/pc98260_rootd.c`

### 5.1 曾分发的源码与产物

| 曾存在的路径 | 说明 |
| --- | --- |
| `merged-app/app/src/main/cpp/ghostkey/pc98260_su.c` | 改编自上游 `src/pc98260_su.c`；本地增加连接重试与 socket uid/gid/mode 诊断 |
| `merged-app/app/src/main/cpp/ghostkey/pc98260_rootd.c` | 改编自上游 `src/pc98260_rootd.c`；本地增加优雅停机控制命令 |
| `assets/ghostkey/ghostkey98260` | 上述源码的编译产物（ARM64 可执行文件） |
| `assets/core/onuce/onuce_111_98260` | 内嵌改编自上游 rootd 的实现：`/data/local/tmp/pc98260-root.sock`、`PATH=/system/bin:/vendor/bin:/data/local/tmp`、日志格式串 `[rootd] startup failed errno=%d (%s)` |
| `assets/core/onuce/onuce_111_98260_201` | 同上，另内嵌源文件名 `pc98260_rootd.c` 与符号 `pc98260_run_rootd` / `rootd_serve` |
| `assets/core/onuce/onuce_111_64560` | 内嵌同一 socket 路径 `/data/local/tmp/pc98260-root.sock` |

**来源链**：上游 `8525ad4`（2026-10-23）→ 本仓库 `bd72c36`（v2.17.16 / versionCode 164）引入并适配
→ 自 v2.19.6（versionCode 204）起全部移除。

### 5.2 移除方式与替代

- 原生后端源码目录 `cpp/ghostkey/` 与其 Kotlin 驱动、profile 资产一并删除；
- 四个 onuce 载荷全部删除，改为按内核拆分的 Shark 执行器（见第三节），
  6.18.21 与 6.12.69 各一份，**不含该 MIT 部分**；
- 全部机型统一走 Shark 执行器，功能路径与支持的机型不变。

### 5.3 权利声明

上述源码及其编译产物的著作权归原作者所有，本应用不主张任何权利。历史版本的分发适用该 MIT
许可与版权声明；如需该部分对应源码，可从上述原项目或本仓库历史提交取得。
若署名有遗漏或不准确，请按第七节联系方式指出，我们会及时更正。

## 六、商标与免责

- "OnePlus""ColorOS"等为其各自权利人的商标，本应用与上述公司无任何隶属或合作关系。
- 本应用按"现状"提供，不对因使用本应用造成的任何直接或间接损失承担责任；刷机、提权存在设备损坏与数据丢失风险，请在充分理解后自行决定是否使用。

## 七、如何反馈署名问题

若你是上述项目的作者，认为署名方式需要调整，请通过应用内"关于 → 使用手册"中提供的反馈渠道联系，我们会在下一个版本修正。

_最后更新：2026-11-14_