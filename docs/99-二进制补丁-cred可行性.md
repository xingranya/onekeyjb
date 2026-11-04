# 二进制补丁可行性分析：让 track_throne 用 `&init_cred` 读 packages.list

> 目的：解决「管理器不被加冕 → UI 一直显示未安装」的根因。
> 结论：**可行**，改动是 `1 条指令 + 2 条重定位 + 1 个新符号`，已离线验证；
> 但**单独打这个补丁不一定够**，还要配合 `setenforce 0`（见 §5）。

---

## 1. 问题复述

`track_throne()` 在 `current->cred` 临时替换成 `ksu_cred` 之后 `filp_open("/data/system/packages.list")`：

```
KernelSU: packages.list detected: 128
KernelSU: track_throne: open /data/system/packages.list failed: -13
```

`ksu_cred` 来自模块 init 里的 `ksu_cred = prepare_creds()`，而 `prepare_creds()` **复制的是
加载模块那个进程的 cred**。本链的加载者是「地址派生能力」的候选进程（`cap_effective = 地址自身`），
地址 8 字节对齐 ⇒ **bit1（CAP_DAC_OVERRIDE）恒为 0** ⇒ 读不了 `0640 system:package_info` 的文件。

userspace 的绕法（chmod / bind mount）都已实测失败：

* `chmod 644` 会被 `system_server` 在 `pm install` 期间原子重写回 0640（mtime 每次都变）；
* `bind mount` 一个 0644 副本能顶住重写，但**观察器不再触发** `track_throne`
  （`packages.list detected` 消失）—— 两者互斥。

所以只能改内核。

---

## 2. 原始代码（反汇编证据）

`track_throne`（`.text` 偏移 `0x5f7c`，大小 2044）开头：

```
05fb8  bl   mutex_lock
05fbc  adrp x8, ksu_cred            ; R275
05fc0  mrs  x10, sp_el0             ; x10 = current
05fc4  str  x10, [sp, #0x10]        ; 保存 current
05fc8  ldr  x8, [x8]                ; R286 → x8 = *ksu_cred（取指针）
05fcc  add  x9, x10, #0x908         ; &current->cred
05fd0  ldr  x28, [x10, #0x908]      ; old_cred = current->cred
05fd4  stlr x8, [x9]                ; current->cred = ksu_cred   ← 内联版 override_creds
05fd8  adrp x0, <path>
05fe8  bl   filp_open
...
062bc  add  x8, x8, #0x908
062c0  stlr x28, [x8]               ; current->cred = old_cred   ← 用完恢复
```

注意它**不是**调用 `override_creds()`，而是直接把 `current->cred` 写掉（`sp_el0` 在 arm64 上就是
`current`）。好在函数尾部有恢复，所以改动只在函数内部生效。

---

## 3. 被否掉的方案：`prepare_kernel_cred(NULL)`

最初的想法是把 init 里那条 `bl prepare_creds` 换成 `prepare_kernel_cred(NULL)`。
**不可行**，因为本机 6.18 的实现是：

```
ffffffc080133b30  paciasp
...
ffffffc080133b48  cbz  x0, 0xffffffc080133cd8     ; x0 == NULL 就跳走
...
ffffffc080133cd8  mov  x19, xzr                   ; 返回 NULL
ffffffc080133cdc  brk  #0x800                     ; WARN_ON_ONCE 的 BUG 指令
```

即 `prepare_kernel_cred(NULL)` 会 **WARN 并返回 NULL**（新内核加了 `WARN_ON_ONCE(!daemon)`），
`ksu_cred = NULL` 之后必然崩。要传只能传 `&init_task`，但那就需要 3 条指令（adrp/add/bl），
还得吃掉 `printk` 那几条——不如直接改 `track_throne`。

---

## 4. 采纳方案：把读取处的 `ksu_cred` 换成 `&init_cred`

`init_cred` 是 `struct cred` **本体**（不是指针），地址 `0xffffffc082692a50`（link），
uid 0 + `CAP_FULL_SET`，天然带 `CAP_DAC_OVERRIDE`。

只要把 track_throne 里那两行改掉即可：

| 偏移 | 原始 | 改成 |
|---|---|---|
| `0x5fbc` | `adrp x8, ksu_cred`（R275） | `adrp x8, init_cred`（R275，换符号） |
| `0x5fc8` | `ldr  x8, [x8]`（R286） | `add  x8, x8, #0`（R277 ADD_ABS_LO12_NC，换符号） |

改完之后：`x8 = &init_cred`（adrp 取页 + add 补低 12 位，本机低 12 位是 `0xa50`），
`stlr x8, [x9]` 写进 `current->cred`，函数尾部照原样恢复 `old_cred`。

### 为什么只改这一处

`ksu_cred` 在模块里还有别的用途（`setup_ksu_cred` 里做 SELinux 域转换、若干恢复点），
它们保持原样。改动只在「读包列表」这一条路径上，影响面最小。

### 工具

`tools/ko_patch_cred.py`：

```bash
python tools/ko_patch_cred.py <in.ko> out/next-caps.ko
python tools/ko_vermagic.py  out/next-caps.ko out/next-caps-v.ko
python tools/ko_patchsyms.py out/next-caps-v.ko out/next-caps-now.ko out/kallsyms_now.txt
```

最后一步会把 `init_cred` 也按当次 boot 的 kallsyms 变成 `SHN_ABS + 绝对地址`（该符号未被导出），
所以**每次重启都要重跑**——这一点和现有 227 个符号的处理完全一致。

---

## 5. 离线验证结果

```
原始: 0x5fbc=0x90000008  0x5fc8=0xf9400108          ; adrp / ldr
新符号 init_cred index=1738（原 1738 个）
  重定位 0x5fbc: type=275 sym=1277(ksu_cred)  ->  type=275 sym=1738(init_cred)
  重定位 0x5fc8: type=286 sym=1277(ksu_cred)  ->  type=277 sym=1738(init_cred)

kallsyms: patched 228 undefined symbols -> SHN_ABS   ; 227 + 新增的 init_cred

符号 init_cred: shndx=SHN_ABS value=0xffffffdcafe92a50
05fbc adrp x8, ...        ; R275 init_cred
05fc8 add  x8, x8, #0     ; R277 init_cred（imm12 由加载器填 0xa50）
05fcc add  x9, x10, #0x908
05fd0 ldr  x28, [x10, #0x908]
05fd4 stlr x8, [x9]       ; current->cred = &init_cred
```

`init_cred` 的 `SHN_ABS` 值与 kallsyms 完全一致，低 12 位 `0xa50` 与 `add` 的 imm12 对得上。

---

## 6. 风险与不确定项

| 风险 | 评估 / 缓解 |
|---|---|
| SELinux 是否也拦 | **未证实**。`su -c 'dmesg | grep -i "avc: denied"'` 为空（Enforcing 期间也没有 AVC 记录），所以更可能一直是 DAC 在挡；`0644` 那次 `-13` 未必是干净对照（bind mount 与触发时序重叠）。按 DAC 解释，本补丁应当足够；若仍失败，再 `setenforce 0` 兜底 |
| 加载失败 | 重定位写错会 `finit_module` 返回 `ENOENT`/`EINVAL`（和现有符号补丁失败的表现一样），不会静默 |
| cred 没恢复 | 已反汇编确认函数尾部 `stlr x28, [x8]` 恢复旧 cred |
| 越界/CFI | 只改直接 `adrp/add`，不涉及间接调用，kCFI 不检查；`adrp` ±4 GB 足够 |
| 符号地址绑当次 boot | 与现有 227 个符号同处理，`--boots` 时每 boot 重打 |
| 回滚 | 原 ko 一直在，`insmod` 回原版即可（或重启） |

---

## 7. 建议的落地验证顺序

1. 用 `tools/ko_patch_cred.py` 出的 ko，在下一次 `./reproduce.sh` 里加载（在 `build_ko()` 里
   先 `ko_patch_cred` 再 `ko_vermagic`/`ko_patchsyms`），观察 dmesg 是否出现
   `Searching manager... / Found manager base.apk ... is_manager: 1 / Crowning manager: uid=…`。
2. 若仍 `-13`，立刻 `su -c 'echo 0 > /sys/fs/selinux/enforce'` 再触发一次（排除 LSM）。
3. 若加冕成功但 UI 仍不对，再查 `is_manager_apk` 的 size/hash 常量是否与当前 APK 匹配。

## 8. 结论

* **技术可行性：高。** 改动 1 条指令 + 2 条重定位，已有工具、已离线验证、可回滚。
* **是否足够：大概率够。** `dmesg` 里没有任何 AVC 拒绝记录，所以阻碍更可能是
  `ksu_cred` 缺少 `CAP_DAC_OVERRIDE`（DAC），而这正是本补丁解决的东西；
  SELinux 那一半留了 `setenforce 0` 兜底。
* **成本：低。** 不需要 GKI 源码、不需要 clang，纯 ELF 手术，和现有 `ko_vermagic + ko_patchsyms`
  是同一套流水线。
