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
| ksud-universal（内置） | Yunnijian / KSU-Universal 项目 | GPL-3.0 | 内置的通用多管理器加载器，内嵌 6.18 通用内核模块；未修改，按原样分发（https://github.com/Yunnijian/KSU-Universal/releases/tag/v1.1.0） |
| COUI（Compose 组件库、模糊与偏好组件） | suqi8 | Apache-2.0 | https://github.com/suqi8/coui |
| Stellar（特权命令服务通道） | roro2239 | 见其仓库 | https://github.com/roro2239/Stellar |
| AndroidX / Jetpack Compose / Kotlin | Google、JetBrains | Apache-2.0 | https://developer.android.com/jetpack |

## 四、漏洞利用相关实现与参考

本应用的越狱链路基于公开研究与公开实现整理，特此致谢：

- **onuce**：98260 链路的本地实现与调试经验
- **Shark**：98260 执行引擎，GPL 许可；本应用内置的 98260 执行器即来自该项目，其源码可从上游项目获取
- **InfinityCore**：98260 的公开参考实现
- 相关内核漏洞的公开分析与复现资料

上述项目仅作为研究参考或在本地调用，本应用不转售、不再分发其源代码。

## 五、商标与免责

- "OnePlus""ColorOS"等为其各自权利人的商标，本应用与上述公司无任何隶属或合作关系。
- 本应用按"现状"提供，不对因使用本应用造成的任何直接或间接损失承担责任；刷机、提权存在设备损坏与数据丢失风险，请在充分理解后自行决定是否使用。

## 六、如何反馈署名问题

若你是上述项目的作者，认为署名方式需要调整，请通过应用内"关于 → 使用手册"中提供的反馈渠道联系，我们会在下一个版本修正。

_最后更新：2026-11-05_