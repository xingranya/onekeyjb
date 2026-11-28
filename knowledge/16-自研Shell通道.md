# 16 · 自研 Shell 通道（OneKeyShell）

> 目标：不依赖 Stellar / Shizuku 也能以特权身份跑 shell。2.25.0 起为默认通道。

## 为什么需要它

Stellar 是第三方 App，它有三个绕不开的代价：

| 问题 | 后果 |
| --- | --- |
| 需要用户额外装一个 App 并手动授权 | 装机门槛，且对方一旦改协议我们就得跟 |
| 权限授予走 binder + 弹窗 | 无人值守场景（开机自动越狱）没法自己完成 |
| 开机自启由对方控制 | 对方不开自启，我们的自动越狱就等不到通道 |

自研通道把这三条都拿回来了：服务端是我们自己的代码，身份由「谁来拉起它」决定。

## 架构

```
App 进程 (untrusted_app)
  ├─ StellarShell / ShizukuShell   ← 保留原名原签名，内部路由
  │     └─ OneKeyShell.available() ? 自研通道 : 原实现
  └─ OneKeyShell（客户端）
        ├─ socket 传输（优先）
        └─ 文件队列传输（兜底）
              ↓
服务端进程 (shell / root)
  └─ OneKeyShellServer（app_process 起的常驻 JVM）
        ├─ LocalServerSocket（抽象命名空间）
        └─ FileTransport.serveLoop
```

服务端与 App 进程完全解耦：App 被杀、被升级、被强停都不影响它；只要不被系统回收就一直可用。

## 双传输（关键设计）

### 为什么不能只用 socket

抽象 Unix socket 的 `connectto` 在 SELinux 上是**跨域检查**，不是路径权限：

```
avc: denied { connectto } for path=006F6E656B65795F7368656C6C5F...
scontext=u:r:untrusted_app:s0:c192,c256,c512,c768
tcontext=u:r:shell:s0 tclass=unix_stream_socket permissive=0
```

`untrusted_app` 域没有连 `shell` 域的权限。改 socket 路径、改文件权限位都无效 —— 拦的是域对。

### 文件队列

落点 `getExternalFilesDir(null)/onekey-shell/`，受分区存储保护（其它 App 进不来），shell 与 root 读得到。

```
q/<id>.sh  .env  .cwd  .to  请求体
q/<id>.go                   原子触发（最后写）
r/<id>.out .err .rc         结果体（.rc 出现即完成）
k/<id>                      终止请求
heartbeat                   服务端心跳（3 秒一次）
```

服务端每 40 ms 扫 `q/`，客户端每 30 ms 扫 `r/`。端到端约 70 ms —— 对「读日志行决定是否中止」完全够用。

### 选路逻辑

```
connOrNull() 成功  →  socket 传输
connOrNull() 失败  →  记 socketBlockedAt（60 秒冷却）
heartbeat 新鲜     →  文件队列
两者都不可用        →  通道不可用，回落 Stellar
```

## 鉴权

不是防 root（拦不住），是防**同设备其它 App**通过 `/proc/net/unix` 枚举到抽象 socket 名后复用我们的 root shell。

```
服务端 → HELLO  { protoVer, uid, nonce[32] }
客户端 → AUTH   HMAC-SHA256( token, nonce ‖ boot_id )
服务端 → AUTH_OK / AUTH_FAIL
```

- `token` = 64 个十六进制字符（256 bit），首次启动生成，存 `onekey-shell/token`（0644）。
- socket 名 = `onekey_shell_` + token 前 16 hex → 泄露 64 bit，剩余 192 bit 未知。
- 鉴权窗口 10 秒；失败即断开，不执行任何命令。

## 引导阶梯

| 级 | 手段 | 前提 | 得到 | 状态 |
| --- | --- | --- | --- | --- |
| L0 | 连接已存在的 socket / 文件队列 | 服务端在跑 | 原权限 | ✅ |
| L1 | `su -c "sh start.sh"` | 已有 root | **uid 0** | ✅ |
| L1b | 写 `/data/adb/modules/onekey_shell` | 已有 root | 每次开机自动起 | ✅ |
| L2 | 无线调试自配对（通知栏回码） | `WRITE_SECURE_SETTINGS` | uid 2000 | ✅ |
| L3 | UI 展示 `adb shell sh <start.sh>` | 用户手动 | uid 2000 | ✅ |

顺序刻意如此：先试最省事也最安全的（复用），再试需要 root 的，最后才麻烦用户。

### L2 无线调试配对的实际形状

完整链路在 `adb/AdbBootstrap.kt` + `adb/AdbPairingService.kt`：

1. `Settings.Global.putInt(cr, "adb_wifi_enabled", 1)` 打开无线调试（需 `WRITE_SECURE_SETTINGS`，首次由 root 一次性授予）。
2. mDNS 发现 `_adb-tls-pairing._tcp` 端口。
3. 前台服务 + 通知栏 `RemoteInput` 让用户回 6 位配对码，跑 SPAKE2（纯 Java 实现，见 `adb/Spake2.kt`）。
4. mDNS 发现 `_adb-tls-connect._tcp` 端口，用自签证书做 TLS 客户端证书连 adbd。
5. `shell:sh start.sh` 把服务端拉起来。

**配对端口只在设置页那个对话框可见期间存活**：切走应用（哪怕只是按 HOME）adbd 立刻关掉端口，`adb mdns services` 变空。所以「应用内弹输入框让用户抄码再切回来」根本走不通，必须通知栏 RemoteInput 让用户全程停在设置页。

**TLS 升级后设备会主动发 A_CNXN banner**，客户端此时不能再补一条 `A_CNXN`，否则 adbd 当成二次认证并回 `A_STLS`，shell 阶段第一帧就读到 `0x534c5453`。详见踩坑记录第 49 条。

### start.sh 必须活到服务端自证就绪

脚本末尾轮询 `/proc/net/unix` 等抽象 socket 出现再退出：

```sh
i=0
while [ $i -lt 50 ]; do
  grep -q 'onekey_shell_<16hex>' /proc/net/unix 2>/dev/null && break
  i=$((i+1)); sleep 0.1
done
echo started
```

原因：adb 的 shell 会话在脚本退出那一刻关闭，adbd 会按会话清掉整个进程组（`setsid` 挡不住）。`app_process` 起 JVM 要 300~600 ms，不等就必然被杀。详见踩坑记录第 50 条。

## 服务端启动脚本

`ShellSecrets.ensureScript()` 生成 `onekey-shell/start.sh`：

```sh
APK=$(pm path <pkg> | head -1 | cut -d: -f2)
CLASSPATH="$APK" setsid app_process /system/bin --nice-name=onekey_shell \
    com.pyb110.onekey.shell.OneKeyShellServer \
    --socket <name> --token-file <path> --log <path> </dev/null >>log 2>&1 &
echo started
```

**参数顺序是硬约束**：`app_process [vm-options] <parent-dir> [options] <class> [args]`。
`--nice-name` 放在 `/system/bin` 之前会被当成 vm-option，整条命令静默退出（退出码 0、无输出）。

## 调用点迁移方式

**不在 106 处调用点逐个替换**，而是让 `StellarShell` / `ShizukuShell` 内部路由：

```kotlin
// StellarShell.kt
private fun self(): Boolean = OneKeyShell.available()

fun run(script: String, timeoutSec: Long = 60, extraEnv: Array<String>? = null): Result {
    if (self()) return OneKeyShell.run(script, timeoutSec, extraEnv).let { Result(it.code, it.output) }
    return try { /* 原 Stellar 实现 */ }
}
```

理由：调用点分散在 11 个文件，逐个替换必然漏改，而漏改的那一处会在「用户已卸载 Stellar」时静默失败。

**但状态查询不做路由**：`available() / hasPermission() / serviceUid()` 必须只反映 Stellar 自身状态，
否则 `ShellBootstrap` 就没法判断「Stellar 能不能兜底」。需要「任意通道可用」的语义用 `shellReady()`。

## 状态语义

| 查询 | 含义 |
| --- | --- |
| `OneKeyShell.available()` | 自研通道可执行 |
| `StellarShell.available()` | Stellar 自身可用（不看自研） |
| `JailbreakController.shellReady()` | 任意通道可用 |
| `JailbreakController.shellGranted()` | 任意通道已授权（自研通道恒 true） |
| `JailbreakController.shellLabel()` | 当前通道名（自研通道 / Stellar / 未连接） |

## 已知限制

| 限制 | 影响 | 出路 |
| --- | --- | --- |
| 无 root 且无 adb 时通道起不来 | 首次装机必须手动执行一次 | 已实现 L2 无线调试自配对，可全自动 |
| 文件队列延迟约 70 ms | 高频短命令比 socket 慢 | 只在高频场景下才明显，引擎不敏感 |
| 服务端被系统回收后不自愈 | 需要 App 侧重新引导 | `ShellBootstrap.start()` 在开机服务与 UI 按钮里都会触发 |
| 从 uid 2000 升到 uid 0 | 需要 root 才能拿 uid 0 | 这是 SELinux 的必然结果，不是实现缺陷；L2 只到 2000 |
