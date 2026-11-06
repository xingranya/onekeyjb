# OneKey UI 升级计划（COUI 原生 + OPlusKey 分组层）

## 0. 目标与硬约束

**目标**：把现有 2217 行单文件界面，升级为与 ColorOS 设置页同构的分组列表界面，视觉对齐 `analysis/design/*.png` 七张设计稿。

**硬约束**（不可违反）：
1. **零功能回归**：只改 UI 层，`JailbreakController` / `OneKeyEngine` / 引擎调用 / su 逻辑一行不动。
2. **COUI 原生控件优先**：`Text / Surface / Card / Switch / Button / Icon / Dialog / Scaffold` 全部来自 `io.github.suqi8.coui.kmp.basic`；不新写自绘控件。
3. **只抄 OPlusKey 的分组层**：`CardList / CardRow / SectionTitle` 三个文件（含 `CardListPosition`），不引入其页面框架、导航、Haze 页面栈。
4. **头像保留**：四个主页面（首页 / 日志 / 历史 / 关于）顶栏统一带头像。
5. **底栏不变**：沿用现有 `AppBottomBar`（四项 + 选中态），不做悬浮胶囊改造。

## 0.1 设计稿（v2，已按修正清单重生成）

产物：`analysis/design/v2-*.png`（截图）与 `v2-*.html`（可复刻 HTML），共 7 屏：home / hotswitch / features / logs / history / about / manual。
所有主页面均含头像；底栏沿用现有样式；热切换页只列真实五家。

## 1. 设计稿修正清单（已全部落地）

| 设计稿问题 | 修正 |
| --- | --- |
| 热切换页出现 Magisk Alpha / APatch | 只保留真实五家：KernelSU / KernelSU Next / SukiSU Ultra / BakaSU / FinaSU |
| 底栏被改成悬浮胶囊 | 回退为现有 `AppBottomBar` |
| 关于页 / 日志页顶栏样式不一致 | 统一为「头像 + 标题 + 右侧版本号」 |
| 日志页示例为英文虚构设备 | 改用真实中文日志与 PYB110 |
| 首页 2×2 信息栅格字段 | 固定为：核心架构 / 安全状态 / 内核版本 / 模块版本 |

## 2. 组件层（新增 3 个文件，直接移植 OPlusKey）

```
ui/components/CardList.kt      // List<CardRow> → 卡片；自动首/中/末圆角、分隔线内缩 16dp
ui/components/CardRow.kt       // 行规格：title / summary / icon / end / arrow / enabled / onClick
ui/components/SectionTitle.kt  // 分组标题：左对齐卡片边缘，上 24dp 下 8dp
```

关键常量（照抄，不自定义）：
- `PAGE_MARGIN = 12.dp`
- 卡片圆角：首行 `20.dp` 上、末行 `20.dp` 下、中间 `0.dp`
- 行高 `56.dp`；行内左右 `16.dp`
- 分隔线：`1.dp`，`outlineVariant` 30%，左右各内缩 `16.dp`

## 3. 依赖对齐

| 项 | 现状 | 计划 |
| --- | --- | --- |
| `coui-ui` | 1.1.0 | 先验证 `1.1.0-subtitle11` 可解析；不可用则保持 1.1.0（subtitle 用 `summary` 字段实现） |
| `coui-preference` | 已引入但 0 使用 | **确定不启用**：与 CardRow 职责重叠，行内自定义空间更小 |
| `coui-blur` | 用于顶栏 | 保留现状（无页面转场，不存在 OPlusKey 那个坐标失效问题），**不引入 Haze**，减少风险 |
| `coui-icons` | 已用 | 继续用 `COUIIcons` |

> 说明：OPlusKey 弃用 coui-blur 是因为它有页面栈转场；我们是单 Activity 无转场，coui-blur 不会踩到那个坑，因此不为此引入新依赖。

## 4. 页面改造清单

| 页面 | 现有函数 | 改造 |
| --- | --- | --- |
| 首页 | `HomeScreen` / `HeroCard` / `StatusCard` / `ExploitCard` / `ManagerCard` / `OptionsCard` / `ActionCard` | 合并为 4 个分组：状态、主操作、拓展功能、更多；状态卡加 2×2 栅格；新增「用户手册」「关于」行 |
| 热切换 | `OptionsCard` 内嵌面板 | 抽出独立二级页 `HotSwitchScreen`：primary 卡 + 选择列表 + 六按钮网格 |
| 拓展功能弹窗 | `OptionsCard` 内 5 个开关 | 改为 `WindowDialog` 模态，5 行 `CardRow` + `Switch` |
| 日志 | `LogScreen` | 阶段条改五段；日志流等宽 + 分类着色；保留复制/导出/清空 |
| 历史 | `HistoryScreen` | 按日期分组；加全部/成功/失败筛选；空态 |
| 关于 | `AboutScreen` | 信息卡 + 操作卡（检查更新/用户手册/开源许可/第三方声明） |
| 用户手册 | 无 | 新增 `ManualScreen`，三步卡片 |
| 顶栏 | `TopBar` | 统一为「头像 + 标题 + 版本号」，四页共用 |

## 5. 功能零回归保障

1. **不改的代码**：`JailbreakController.kt`、`OneKeyEngine.kt`、`ManagerSwitchProtocol.kt`、`ManagerDownloads.kt`、引擎资产 —— 全部不动。
2. **回调保持**：每个按钮的 `onClick` 仍调用原有 controller 方法，签名不变。
3. **真机回归清单**（每次提交后跑）：
   - 启动 → 服务状态检测 → 显示「服务在线/离线」正确；
   - 开始越狱 → 5 秒左右成功 → 状态变「SU 就绪」；
   - 检测 → primary 行显示正确；
   - 切换 → primary 变化 → 目标管理器被拉起；
   - 安装/还原加载器 → 文件大小变化 → 可还原；
   - 重启手机 → 重启后热切换仍可用；
   - 日志页复制/导出/清空可用；
   - 历史页筛选可用；
   - 关于页检查更新可用。

## 6. 分阶段实施

| 阶段 | 内容 | 预估 | 回滚点 |
| --- | --- | --- | --- |
| P0 | 新增 3 个组件文件 + 常量；不改任何现有界面 | 30 min | 删文件即可 |
| P1 | 顶栏统一（头像四页共用）+ 底栏保持 | 30 min | 单函数回滚 |
| P2 | 首页重排（4 分组 + 状态栅格 + 更多分组） | 1 h | `HomeScreen` 回滚 |
| P3 | 热切换二级页 + 拓展功能弹窗 | 1 h | 新页面可下线 |
| P4 | 日志 / 历史 / 关于 / 用户手册 | 1.5 h | 逐页回滚 |

每阶段独立编译 + 真机截图对比，通过后再进入下一阶段。

## 7. 验收标准

- 首页相邻卡片间距完全一致，无手工 `Spacer`；
- 任一卡片 ≥2 行时自动出现分隔线，首末行圆角正确；
- 圆角只剩 `20dp` 一档（按钮 `16dp`）；
- 四个主页面顶栏均带头像；
- 底栏与现在完全一致（无视觉变化）；
- 所有功能回归清单全绿。

## 8. 风险

| 风险 | 应对 |
| --- | --- |
| `CardList` 移植后行内自定义内容（开关、箭头）布局错位 | 先只移植 `CardRow` 的四种 end 形态，逐页替换 |
| 深色主题下分隔线不可见 | 用 `outlineVariant` 30% 并在深色下提到 40% |
| 大改导致功能按钮丢失 | 每次替换后跑回归清单，未通过不回滚下一步 |