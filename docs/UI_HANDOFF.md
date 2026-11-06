# UI 升级交接说明

> 面向接手继续做界面的 AI / 开发者。先读本文，再看 `docs/UI_UPGRADE_PLAN.md` 与 `docs/design/`。

## 1. 项目与目标

Android 应用 **OneKey**（一键越狱工具，包名 `dev.onekey.jb`）。目标平台 OnePlus 16（PYB110 / ColorOS 17 / 内核 6.18.21）。

界面改造目标：从单文件手工排版，升级为与 ColorOS 设置页同构的**分组列表**界面，视觉对齐 `docs/design/v2-*.png` 七张设计稿。

**铁律**：只改 UI 层。`JailbreakController` / `OneKeyEngine` / `ManagerSwitchProtocol` / `ManagerDownloads` / 引擎资产一行不动，功能零回归。

## 2. 技术栈约束

| 项 | 约定 |
| --- | --- |
| UI 库 | `io.github.suqi8.coui.kmp`（COUI，ColorOS 风格），**只用它的原生控件，不自己画** |
| 入口 | `app/src/main/java/com/pyb110/onekey/MainActivity.kt`（单文件 2700+ 行，按函数分块） |
| 组件层 | `app/src/main/java/com/pyb110/onekey/ui/components/ListCard.kt` |
| 模糊 | 沿用现有 `BlurBar` + `coui-blur`，**不要引入 Haze** |
| 抽屉 | `io.github.suqi8.coui.kmp.window.WindowBottomSheet`，需用 `visible` 状态驱动（先 false 后 true）才有入场动画 |
| 图标 | `COUIIcons.<Name>`，名字见 `coui-icons` aar 里的 `extended/` 包 |
| 构建 | JDK 21，`./gradlew assembleRelease` |

## 3. 设计规范（已固化）

```
页面左右边距     PageMargin = 12.dp
卡片圆角         20.dp（Card 默认）
按钮圆角         14.dp
行高             由 BasicComponent 决定（约 56dp）
分隔线           1.dp，左内缩 52.dp（有图标列）/ CardInset（无），右内缩 CardInset
分组标题         与卡片左边缘对齐，上 20dp 下 8dp
主色             深玫瑰 #9B4A5A（浅色主题）
```

## 4. 组件层用法

```kotlin
ListCard(rows = listOf(
    ListRow(title = "标题", summary = "副标题", icon = COUIIcons.Xxx, arrow = true) { /* onClick */ },
    ListRow(title = "开关行", icon = COUIIcons.Yyy, end = { Switch(checked, onCheckedChange) }),
))
SectionTitle("分组名")
```

`ListRow` 字段：`title / summary / icon / end / arrow / enabled / onClick / iconTint`。

## 5. 已完成

| 页面 | 状态 |
| --- | --- |
| 首页 | 状态卡（服务胶囊 + 2×2 栅格）、主按钮、拓展功能 5 行、更多 2 行 ✅ |
| 拓展功能分组 | 热切换 / 目标管理器 / 越狱管理器 / 管理器下载 / 拓展开关 ✅ |
| 管理器热切换抽屉 | 按阶段收敛按钮（未安装→只给"安装"；未生效→"重启"；就绪→"切换"）✅ |
| 目标管理器抽屉 | 五家单选列表 ✅ |
| 越狱管理器抽屉 | 五家列表 + 版本/适配状态 ✅ |
| 管理器下载抽屉 | 行式布局 + 下载图标 ✅ |
| 拓展开关抽屉 | ListCard + 5 个开关 ✅ |
| 日志页 | 阶段卡 + 筛选分段 + 自动滚动 + 按级别着色 ✅ |
| 历史页 | 全部/成功/失败筛选 + 今日/昨日/更早分组 + ListCard 行 ✅ |
| 关于页 | 品牌区 + 设备卡 + 个性化卡 + 支持说明卡 + 页脚 ✅ |
| 用户手册 | 步骤卡（圆环序号 + 条目）✅ |
| 顶栏 | 四页统一「头像 + 标题 + 版本号」✅ |
| 底栏 | 保持原样（未改）✅ |

**全部改动已通过 `compileReleaseKotlin` 与 `assembleRelease`。**

## 6. 未完成 / 已知问题

1. **真机未验证**：设备长时间离线，上述改动只经过编译验证，没有任何真机截图确认。
2. **首页细节**：结果横幅（`ResultBanner`）、越狱卡片（`ActionCard`）、越狱方式说明卡（`ExploitCard`）仍是旧样式，未对齐设计稿。
3. **弹窗遮罩点击关闭**：`WindowDialog` 没有 `dismissOnClickOutside` 参数，点空白不关闭；抽屉（`WindowBottomSheet`）已具备下滑动画。
4. **手动滚动冲突**：抽屉内容用了 `verticalScroll`，可能影响下滑关闭手感，需要在真机调。
5. `LogScreen` 的 `snackbarHostState` / `scope` / `onExport` 参数已不再使用（保留签名避免改调用点）。
6. `OptionsCard` 的 `onOpenLog` 参数已不使用。

## 7. 设计稿

`docs/design/v2-*.png`（截图）与同名 `.html`（可读源码，便于逐像素对照）：

home / hotswitch / features / logs / history / about / manual

**注意**：设计稿是 AI 生成的示意图，含虚构内容（如 Magisk/APatch、英文日志、示例设备），实现时一律以真实功能为准，不要照搬虚构项。

## 8. 版本状态

- 当前 `versionCode = 197`，`versionName = "2.18.30"`
- **未发布**：本次只提交代码，未打 Release、未上传 CNB
- 已发布历史见 GitHub Releases（最新正式版 v2.18.30）

## 9. 验证清单（改完必跑）

1. `./gradlew compileReleaseKotlin`
2. `./gradlew assembleRelease` + 真机安装
3. 功能回归：服务检测 → 开始越狱 → SU 就绪 → 检测 primary → 切换 → 安装/还原加载器 → 重启后热切换 → 日志导出 → 历史筛选 → 检查更新
