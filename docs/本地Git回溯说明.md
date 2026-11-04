# 本地 Git 回溯说明

本项目根目录已建立本地 Git 仓库（不上传 GitHub，仅本机回溯用）。

## 基本操作
```bash
cd /Users/xingranya/Downloads/CVE-2026-64560-fullchain

git log --oneline --decorate -20     # 查看历史
git tag                              # 查看所有版本标签
git show v2.4.0 --stat               # 查看 v2.4.0 改了什么

# 回退到某个版本（只读查看）
git checkout v2.4.0

# 从某个版本拉修复分支
git checkout -b fix/v2.4.1 v2.4.0

# 撤销全部未提交改动
git restore .
```

## 当前已打标签
- `v2.4.0`：COUI 新界面 + 4 管理器 + 默认 SU 自动化 + 包体/图标优化（当前最新）

## 离线备份
- `dist/onekey-v2.4.0.bundle`：完整仓库备份（含全部历史与标签）
- 恢复方式：`git clone dist/onekey-v2.4.0.bundle restored-repo`

## 重新构建出当前 APK
```bash
cd merged-app
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
# 签名：keystore/debug.keystore（keystore.properties 已入库）
```

## APK 校验
- v2.4.0 sha256：`8939dc46607118e1976c04a2240ac1339c6bc3a4d5bc6ca273863bea22ff3741`
- 文件：`dist/OneKeyJB-v2.4.0.apk`

> 说明：`merged-app/app/build`、`.gradle`、`out/`、`reference/` 等派生/大文件目录已在 .gitignore 排除；源码、资源、签名与文档均已入库。
