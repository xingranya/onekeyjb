### OneKey v2.19.3

#### 管理器版本同步
| 管理器 | 版本 | versionCode | 变化 |
| --- | --- | --- | --- |
| KernelSU | v3.3.1 | 32680 | 已是最新 |
| KernelSU Next | v3.4.2 | 33355 | 已是最新 |
| SukiSU Ultra | v4.3.0-r2 | 41150 | 期望版本号由 41140 修正为 41150 |
| BakaSU | v4.2.0-rc3 | 35171 | 已是最新 |
| FinaSU | **v1.5.0** | **33174** | 升级（原 v1.4.5 / 33168） |

五家签名证书均已核对，与内置信任表一致，热切换判定不受影响。

#### 新增：ksud 归一化
98260 执行引擎按「管理器 APK 目录下 lib/arm64/libksud.so」定位加载器，但 SukiSU 从 r1 起把 ksud 改名成随机库（r1 `libfdbqiupsrx.so`、r2 `libtqqfthevwb.so`），该文件不存在时引擎会直接报 `KSUD_MISSING`。

现在的处理：
- 逐个把 `lib/arm64` 下的 so 拷到可执行目录跑 `-V`，**只有真 ksud 会输出 `ksud … (uapi: X)`**；
- 命中后回写成 `libksud.so`，之后首次运行、再次运行与热切换都走原路径；
- 越狱前（已有 root 时）自动归一化；安装通用加载器前也会先归一化，避免备份/替换落到不存在的路径；
- 找不到时给出明确提示，而不是笼统失败。

#### 校验
| 项 | 值 |
| --- | --- |
| 版本 | 2.19.3 / 201 |
| APK 大小 | 24,228,449 B |
| SHA-256 | `637afb35baf8dbe66571ab10967fffc78b5190da0ad0242b41d289a85f800b24` |

> 归一化逻辑需要 root 才能读写 /data/app，因此首次越狱（尚无 root）仍建议用 KernelSU / KernelSU Next / BakaSU / FinaSU 之一；成功后再切到 SukiSU 即会自动补齐。
