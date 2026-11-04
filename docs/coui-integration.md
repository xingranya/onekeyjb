# COUI（suqi8/coui）集成方案研究

> 任务：task-2 · 研究 `https://github.com/suqi8/coui`，为「一加16提权越狱」App（当前 Java Views + aapt2 手工构建）给出可落地的 UI 改造方案。
> 结论定位：**COUI 可用、Android 端可接、但会把构建基线抬到 compileSdk 37 / JDK 21 / Gradle**；对越狱工具本体而言，UI 不是核心产品，建议「先补齐视觉（Java 手搓）」或「一次性上 Gradle+Compose（同时替换 Miuix）」，不建议在 aapt2 里硬塞 Compose。
> 研究方式：源码通读 + Maven Central 制品核验 + **本机真实 Gradle 构建验证**（临时探针工程 `/tmp/coui-probe`，未改本仓库 app 源码）。
> 日期：2026-10-20。

---

## 0. TL;DR（先看这 12 条）

1. **COUI 是 Miuix 的 ColorOS 16 风格 fork**，Compose Multiplatform UI 库，Apache-2.0，API 面与 Miuix 基本 1:1，迁移主要是「换坐标 + 换包名 + 少量改名」。
2. **发布坐标**：Maven Central `io.github.suqi8.coui.kmp:coui-ui:1.1.0`（及 core/squircle/preference/icons/blur/shader/nav/navigation3-ui 共 9 个模块），**不是 JitPack**。
3. 最新正式版 **1.1.0（2026-08-04 发布）**；上一版 1.0.0（2026-07-26）。仓库 2025-11 创建，49 star、单维护者，README 明确「实验性，API 可能随时变」。
4. **Android 端可用**，Android-only 工程用 `-android` 后缀制品（如 `coui-ui-android`）；AAR 内是普通 androidx Compose 字节码，不依赖 Miuix。
5. **硬约束 1：compileSdk 必须 ≥ 37**。COUI 1.1.0 的 AAR metadata `minCompileSdk=37`（传递依赖 material-color-utilities 5.0.0 也要求 37）。本机实测 compileSdk 35 直接构建失败。
6. **硬约束 2：coui-blur 要求 minSdk ≥ 33**。AAR manifest 声明 minSdkVersion=33，minSdk 29 的 App 加它会 manifest merger 失败（文档说的「低 minSdk 可在运行时 gate」与 AAR 实际不符，需 `tools:overrideLibrary` 才能过，不推荐）。只上 coui-ui 时 minSdk 29 实测 OK。
7. **构建基线**：库自身用 Kotlin 2.4.10 / Compose Multiplatform 1.11.1 / AGP 9.3.1 / JDK 21 / compileSdk 37。消费端实测可用：**AGP 9.4.1 + Gradle 9.7.1 + Kotlin Compose 插件 2.4.20 + JDK 21 + compileSdk 37 + androidx Compose BOM 2026.09.00**（AGP 9 内置 Kotlin，无需 kotlin-android 插件）。
8. **本机实测**：`assembleDebug`/R8 `assembleRelease` 全绿。release APK：全量 COUI（ui+preference+icons+blur）**2.26MB**，仅 coui-ui **2.11MB**，纯 Compose 最小基线 0.22MB ⇒ COUI 外观栈实际增量约 **+1.8~1.9MB（R8 后）**。
9. 对现有 9.29MB 的越狱 APK：接入后约 **11.3~11.5MB（+20~24%）**；体积主要是 assets（ksud/syms.txt 等），UI 增量占比不算致命。
10. **组件量很足**：Button/Card/TopAppBar/Scaffold/LazyColumn/TextField/Switch/Slider/Dialog/BottomSheet/Snackbar/NavigationBar/TabRow + 20 余个 Preference + 模糊（coui-blur）+ squircle 连续圆角 + 图标 5 字重 + 震动触感（OPLUS 302/308 波形）+ 按压反馈（Tint/Sink/Tilt）。
11. **Java 工程有两条可落地路径**：A) 上 Gradle+Compose，UI 重写（推荐，可与上游 Yunnijian 的 Compose 分支合并；预计 3~5 人日）；A2) 保留 Java Activity，用 ComposeView 桥只换 UI（探针已验证 Java↔Compose 互操作可编译）；B) 继续手搓 Java，仅「抄」COUI 视觉令牌（0.5~1 人日，零构建风险，但拿不到真组件与动效）。
12. **与 task-1 联动**：上游 Yunnijian 已用 Kotlin/Compose/Miuix；如果长期跟上游，越早切 Compose（且直接用 COUI 替代 Miuix）越省后续移植成本；如果本项目定位是「不可变的最小越狱工具」，维持 aapt2 更稳。

---

## 1. COUI 是什么

- 仓库：<https://github.com/suqi8/coui>；文档：<https://suqi8.github.io/coui/>；Maven Central group：`io.github.suqi8.coui.kmp`。
- 定位：**ColorOS 16（OPPO/一加）设计语言**的 Compose Multiplatform UI 库，上游 fork 自 [Miuix](https://github.com/compose-miuix-ui/miuix)（小米 HyperOS 风格），保留 API 面、重做配色/圆角/动效/按压反馈。
- 平台：Android / iOS / macOS / Desktop(JVM) / WasmJs / Js。
- 许可：**Apache-2.0**（fork 自同样 Apache-2.0 的 Miuix，需保留 LICENSE/NOTICE）。
- 活跃度（2026-10-20 抓取）：created 2025-11-07，last push 2026-08-04，49 stars / 0 forks；release v1.0.0（2026-07-26）、v1.1.0（2026-08-04）。**单维护者、发布节奏早期**——生产级关键工具引入需自行 vendor 或锁版本。
- README 原文警告：`This library is experimental. APIs may change without notice.`

### 1.1 模块划分（9 个）

| 模块 | 作用 | 依赖关系 |
| --- | --- | --- |
| `coui-core` | 基础工具、`COUIIcons` 根对象 | 无 |
| `coui-ui` | 核心组件库（自动带 core+squircle） | core, squircle |
| `coui-squircle` | 连续圆角（squircle）图形 | 无（shader 后备） |
| `coui-preference` | 设置项组件（SwitchPreference 等） | 依赖 coui-ui |
| `coui-icons` | 扩展图标（5 字重） | 依赖 core |
| `coui-blur` | 背景模糊/RuntimeShader 特效 | 依赖 coui-shader + foundation |
| `coui-shader` | RenderEffect/RuntimeShader 抽象与能力探测 | 无 |
| `coui-nav` | 导航封装 | — |
| `coui-navigation3-ui` | androidx Navigation3 集成 | — |

### 1.2 版本与制品核验（Maven Central，2026-10-20）

| 模块（Android 制品） | 版本 | AAR 大小 | AAR minSdk | minCompileSdk |
| --- | --- | ---: | ---: | ---: |
| `coui-ui-android` | 1.1.0 | 2,177,868 B | 23 | 37 |
| `coui-icons-android` | 1.1.0 | 1,879,817 B | 23 | 37 |
| `coui-preference-android` | 1.1.0 | 339,102 B | 23 | 37 |
| `coui-nav-android` | 1.1.0 | 206,540 B | 23 | 37 |
| `coui-navigation3-ui-android` | 1.1.0 | 136,784 B | 23 | 37 |
| `coui-blur-android` | 1.1.0 | 186,829 B | **33** | 37 |
| `coui-squircle-android` | 1.1.0 | 79,720 B | 23 | 37 |
| `coui-core-android` | 1.1.0 | 13,234 B | 23 | 37 |
| `coui-shader-android` | 1.1.0 | 6,053 B | 23 | 37 |

> 数据来源：`repo1.maven.org/maven2/io/github/suqi8/coui/kmp/` 目录列表 + 各 AAR 内 `META-INF/com/android/build/gradle/aar-metadata.properties` / `AndroidManifest.xml`（本机下载解包核对）。

关键传递依赖（`coui-ui-android-1.1.0.pom`）：

- `org.jetbrains.compose.foundation:foundation:1.11.1`（Gradle metadata 在 Android 上重定向为 `androidx.compose.foundation:foundation:1.11.2`）
- `org.jetbrains.kotlin:kotlin-stdlib:2.4.10`
- `androidx.navigationevent:navigationevent-compose-android:1.1.2`
- `org.jetbrains.compose.material3:material3-window-size-class:1.9.0`
- `com.materialkolor:material-color-utilities-android:5.0.0`
- 无 `androidx.compose.material3` 主体依赖 ⇒ 比 Material3 App 更轻。

---

## 2. Gradle 接入方式（本机实测通过）

### 2.1 推荐配置（Android-only App，与现有工程一致的单 module 结构）

根 `settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()   // COUI 只在 Maven Central
    }
}
rootProject.name = "OneKeyJB"
include(":app")
```

根 `build.gradle.kts`（AGP 9.4.1 内置 Kotlin，只需再挂 Compose 编译插件）：

```kotlin
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
```

`app/build.gradle.kts`：

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.onekey.jb"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }   // 必须 >= 37
    }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.onekey.jb"
        minSdk = 33          // 含 coui-blur 必须 >=33；不用 blur 可保持 29
        targetSdk = 37
        versionCode = 16
        versionName = "2.3.3"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 原 build.sh 的 assets 暂存逻辑改成 sourceSets 或 Copy task 挂到 assets 目录
    sourceSets.getByName("main").assets.srcDirs("assets", "../bin-assets")
    signingConfigs { /* 沿用 debug.keystore 或 release keystore */ }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // COUI（Android-only 用 -android 制品；KMP 工程用根坐标）
    implementation("io.github.suqi8.coui.kmp:coui-ui-android:1.1.0")
    implementation("io.github.suqi8.coui.kmp:coui-preference-android:1.1.0")
    implementation("io.github.suqi8.coui.kmp:coui-icons-android:1.1.0")   // 可选
    implementation("io.github.suqi8.coui.kmp:coui-blur-android:1.1.0")     // 可选，需 minSdk 33
}
```

> 坐标选择：**Android-only 工程建议直接用 `-android` 后缀制品**（官方 getting-started 的 Android 段就这么写，本机实测可用）。KMP 多平台工程用根坐标 `io.github.suqi8.coui.kmp:coui-ui:1.1.0`，其 Gradle module metadata 会把 `androidApiElements-published` 重定向到 `coui-ui-android`。

`gradle.properties` 至少：

```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
```

Gradle wrapper：`gradle-9.7.1-bin`（AGP 9.4.1 对应 Gradle 9.x；本机 `~/.gradle/wrapper/dists` 已缓存）。JDK 用 21（`/opt/homebrew/opt/openjdk@21`），本机 SDK 已有 `platforms/android-37.0`、`build-tools/37.0.0`。

### 2.2 实测记录（可复现）

探针工程：`/tmp/coui-probe`（单 module，包含：Compose Activity、Java Activity + ComposeView 桥、Java 业务类被 Compose 调用、COUI 主题/Scaffold/TopAppBar/Card/TextButton/SwitchPreference/WindowDropdownPreference/图标/blur）。

| 实验 | 命令 | 结果 |
| --- | --- | --- |
| 冷启动 Debug 构建 | `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL in 1m50s**（含依赖下载） |
| Release + R8 | `./gradlew :app:assembleRelease` | **BUILD SUCCESSFUL in 43s**，R8/资源压缩/lintVitalRelease 全过 |
| Debug APK | — | 64,188,364 B（未压缩、含全量 material-icons-extended，不代表真实体积） |
| Release APK（全量 COUI） | — | **2,257,635 B**；`classes.dex` 2,089,756 B |
| Release APK（仅 coui-ui，minSdk 29） | — | **2,109,467 B**；`classes.dex` 1,942,108 B |
| Release APK（纯 Compose 最小基线，无 COUI） | — | 222,290 B |
| compileSdk 35 | `./gradlew :app:assembleDebug` | **失败**：`com.materialkolor:material-color-utilities-android:5.0.0 requires ... compile against version 37`（其余 COUI AAR 的 minCompileSdk=37 同样会报） |
| minSdk 29 + coui-blur | `./gradlew :app:assembleDebug` | **失败**：`uses-sdk:minSdkVersion 29 cannot be smaller than version 33 declared in library [coui-ui...coui-blur-android:1.1.0]`，提示可 `tools:overrideLibrary="io.github.suqi8.coui.kmp.blur"`（能过但不建议） |
| minSdk 29 + 仅 coui-ui | `./gradlew :app:assembleRelease` | **成功** |
| BOM 兼容性 | 探针用 BOM 2026.09.00（androidx Compose 1.12.1），COUI 编译基线是 CMP 1.11.1→androidx 1.11.2 | 编译/R8 全过；**未做真机运行验证**（见 §8 风险） |

**结论**：接入路径本身没有坑，坑都集中在版本约束（compileSdk 37、minSdk 33、JDK 21）和构建系统切换成本上。

---

## 3. 组件清单（COUI 1.1.0）

### 3.1 基础/布局

`Scaffold`（弹窗宿主，Overlay* 组件必须包在里面）、`Surface`、`TopAppBar`/`SmallTopAppBar`、`NavigationBar`、`NavigationRail`、`TabRow`、`BreadcrumbBar`、`Card`（`CardDefaults`，12dp 圆角、按压缩放/Tint）、`BasicComponent`、`Button`、`TextButton`、`LoadingButton`、`IconButton`、`Chip`、`Text`、`SmallTitle`、`TextField`/`InputView`/`CodeTextField`、`Switch`、`Checkbox`、`RadioButton`、`Slider`、`NumberPicker`、`DatePicker`、`TimePicker`、`Stepper`、`ProgressIndicator`、`Snackbar`、`TopTips`、`Tooltip`、`Badge`/`BadgeBox`、`Icon`、`FloatingActionButton`、`FloatingToolbar`、`Divider`、`PullToRefresh`、`SearchBar`、`FullPageStatement`、`ColorPalette`/`ColorPicker`/`ColorSwatchPicker`。

### 3.2 弹窗/浮层

`OverlayDialog`、`WindowDialog`、`OverlayBottomSheet`、`WindowBottomSheet`、`OverlayListPopup`、`WindowListPopup`、`OverlayCascadingListPopup`、`OverlayLoadingDialog`、`OverlaySecurityDialog`、`DialogButtonBar`。

- Overlay* 依赖 `Scaffold` 提供的 `COUIPopupHost`；Window* 不需要 Scaffold。
- 对话框内容**不再自带 padding**（ColorOS 风格按钮条/分割线要通铺），迁移时需自己加 `24.dp` 左右内边距。

### 3.3 Preference（设置项，需 `coui-preference`）

`SwitchPreference`、`SwitchLoadingPreference`、`CheckboxPreference`、`RadioButtonPreference`、`ArrowPreference`、`MarkPreference`、`ButtonPreference`、`ListPreference`、`MultiSelectListPreference`、`SliderPreference`、`RangeSliderPreference`、`RecommendedPreference`、`OverlayDropdownPreference`/`WindowDropdownPreference`、`OverlaySpinnerPreference`/`WindowSpinnerPreference`、6 个 DropdownMenu 变体。统一支持 `startAction`/`endActions`/`bottomAction`/`cardListPosition`。

### 3.4 图标（`coui-icons`）

`COUIIcons` + `io.github.suqi8.coui.kmp.icon.extended.*` 扩展属性，5 个字重：`COUIIcons.Light/Normal/Regular/Medium/Demibold`。`coui-ui` 自带 7 个基础图标（ArrowRight/ArrowUpDown/Check/Close/Search/SearchCleanup/Sidebar，均挂在 `COUIIcons.Basic` 上）。

### 3.5 模糊（`coui-blur`，需 API 33+）

`rememberLayerBackdrop()` + `Modifier.layerBackdrop(backdrop)` 录背景层；`Modifier.textureBlur(backdrop, shape, blurRadius, colors, highlight, contentBlendMode, enabled)` 画模糊面；进阶 `progressiveTextureBlur`（边缘渐变模糊，适合导航栏/边缘淡出）、`drawBackdrop` 低层管线（blur/colorControls/colorFilter/runtimeShaderEffect）、`Highlight.*`（玻璃描边高光，6 个内置 token + 倾斜传感器视差 `rememberTiltLight`）、`BlurColors`/`BlurBlendMode`（40+ 混合模式）。能力探测：`isRenderEffectSupported()`（API 32+）、`isRuntimeShaderSupported()`（API 33+）。

### 3.6 形状/动效/触感

- `coui-squircle`：`squircleBackground/squircleSurface/squircleClip/squircleBorder`，API 33+ 走 shader，低版本自动回退圆角矩形；`LocalSquircleEnabled` 全局开关。
- 动效令牌：`COUIEasing`（folmeSpring 等）、`SinkFeedback`/`TiltFeedback`/`PressFeedbackType.Tint|Sink|Tilt|None`、`Modifier.pressable()`、`Modifier.overScrollVertical()`、`Modifier.scrollEndHaptic()`。
- 触感：`CouiHapticEffect.Switch / Strength` + `rememberCouiHaptic()`。Android 实现直接调 `View.performHapticFeedback(302/308)`（ColorOS 线性马达波形），非 OPLUS 设备回退 AOSP `CONTEXT_CLICK(6)`/`CLOCK_TICK(4)`。

---

## 4. API 示例（均可编译，已随探针工程验证）

### 4.1 主题 + 页面骨架

```kotlin
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 跟随系统深浅色；Monet 动态取色用 ColorSchemeMode.MonetSystem
            COUITheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                AppScreen()
            }
        }
    }
}

@Composable
fun AppScreen() {
    val scrollBehavior = COUIScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "一加16提权越狱",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { /* 打开日志 */ }) {
                        Icon(imageVector = COUIIcons.Notes, contentDescription = "日志")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(inner)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Card { Text("越狱状态卡", modifier = Modifier.padding(16.dp)) } }
        }
    }
}
```

### 4.2 卡片 / 按钮

```kotlin
// 普通信息卡（12dp 圆角）
Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
        Text("内核版本", fontSize = 16.sp)
        Text("6.1.xx-android16", fontSize = 14.sp)
    }
}

// 可点击卡片，按压反馈 Tilt（ColorOS 卡片倾斜）
Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.surfaceContainer),
    pressFeedbackType = PressFeedbackType.Tilt,
    onClick = { /* ... */ },
) {
    Text("管理器版本", Modifier.padding(16.dp))
}

// 主按钮（胶囊、按压缩放+按压 Tint）
Button(
    onClick = { /* 一键越狱 */ },
    colors = ButtonDefaults.buttonColorsPrimary(),
) {
    Text("一键越狱")
}

// 文字按钮（整行）
TextButton(
    text = "校验 / 再次越狱",
    onClick = { },
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.textButtonColorsPrimary(),
)
```

### 4.3 设置项：开关 + 下拉选择（对应现有「越狱后禁用安全弹窗」「管理器版本」两行）

```kotlin
Card(modifier = Modifier.fillMaxWidth()) {
    SwitchPreference(
        title = "越狱后禁用安全弹窗",
        summary = "临时屏蔽系统安全弹窗",
        checked = antiRootGuard,
        onCheckedChange = { antiRootGuard = it },
        startAction = {
            Icon(imageVector = Icons.Filled.Security, contentDescription = null)
        },
    )
}

Card(modifier = Modifier.fillMaxWidth()) {
    WindowDropdownPreference(
        title = "管理器版本",
        summary = managerVersion,
        items = listOf("KernelSU", "KernelSU Next", "BakaSU", "SukiSU Ultra"),
        selectedIndex = selectedManager,
        startAction = { Icon(imageVector = Icons.Filled.Tag, contentDescription = null) },
        onSelectedIndexChange = { selectedManager = it },
    )
}
```

### 4.4 对话框（需外层 Scaffold）

```kotlin
var showDialog by remember { mutableStateOf(false) }

Scaffold(topBar = { /* ... */ }) { inner ->
    // 页面内容
}

OverlayDialog(
    show = showDialog,
    title = "确认越狱？",
    summary = "将部署并加载 KernelSU 模块",
    onDismissRequest = { showDialog = false },
) {
    // 注意：COUI 对话框内容不带 padding，自己包
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        DialogButtonBar(
            DialogButtonBarAction("取消") { showDialog = false },
            DialogButtonBarAction("继续") { showDialog = false; runFlow() },
        )
    }
}
```

### 4.5 模糊（ColorOS 毛玻璃卡片）

```kotlin
import io.github.suqi8.coui.kmp.blur.*
import io.github.suqi8.coui.kmp.shader.isRuntimeShaderSupported

@Composable
fun GlassCard() {
    val backdrop = rememberLayerBackdrop()
    val blurOk = remember { isRuntimeShaderSupported() }   // API 33+ 为 true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop),   // 1) 录制背景层
    ) {
        BackgroundContent()
        Box(
            modifier = Modifier
                .size(320.dp, 140.dp)
                .then(
                    if (blurOk) {
                        Modifier.textureBlur(               // 2) 画模糊面
                            backdrop = backdrop,
                            shape = RoundedCornerShape(24.dp),
                            blurRadius = 20f,
                            colors = BlurDefaults.blurColors(saturation = 1.2f),
                            highlight = Highlight.GlassStrokeMiddleLight,  // 玻璃描边
                        )
                    } else Modifier
                ),
        ) {
            Text("模糊卡片", Modifier.padding(16.dp))
        }
    }
}
```

> 导航栏边缘渐变可用 `Modifier.progressiveTextureBlur(backdrop, shape, blurRadius = 20f, gradient = ProgressiveBlur.Top)`。
> 注意：`coui-blur` 的 AAR minSdk=33，App minSdk<33 时**无法直接依赖**（manifest merger 会挡）。

### 4.6 震动 / 触感

```kotlin
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
import io.github.suqi8.coui.kmp.utils.rememberCouiHaptic
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic

@Composable
fun ToggleRow() {
    val haptic = rememberCouiHaptic()   // (CouiHapticEffect) -> Unit

    SwitchPreference(
        title = "越狱后禁用安全弹窗",
        checked = true,
        onCheckedChange = {
            haptic(CouiHapticEffect.Switch)    // OPLUS 302 / AOSP CONTEXT_CLICK
            // 数值步进类交互用 CouiHapticEffect.Strength（OPLUS 308 / AOSP CLOCK_TICK）
        },
    )

    LazyColumn(modifier = Modifier.scrollEndHaptic(HapticFeedbackType.TextHandleMove)) {
        // 滑到边界时触发触感
    }
}
```

- 纯 Java/View 工程也可直接复刻同一效果：`view.performHapticFeedback(302)`，失败时回退 `6`；步进用 `308`，回退 `4`。
- 对应 `androidx.compose.ui.hapticfeedback.HapticFeedbackType` 的常规用法同样有效。

### 4.7 图标

```kotlin
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Notes    // 扩展图标是 COUIIcons 的扩展属性
import io.github.suqi8.coui.kmp.icon.basic.ArrowRight // 基础图标挂在 COUIIcons.Basic 上

Icon(COUIIcons.Notes, contentDescription = "日志")
Icon(COUIIcons.Basic.ArrowRight, contentDescription = null)
Icon(COUIIcons.Light.Settings, contentDescription = null)   // 指定字重
```

---

## 5. Miuix → COUI 迁移对照（含 Yunnijian 上游逐行清单）

### 5.1 坐标对照（官方 1:1）

| Miuix | COUI |
| --- | --- |
| `top.yukonga.miuix.kmp:miuix-ui` | `io.github.suqi8.coui.kmp:coui-ui` |
| `...:miuix-preference` | `...:coui-preference` |
| `...:miuix-icons` | `...:coui-icons` |
| `...:miuix-blur` | `...:coui-blur` |
| `...:miuix-squircle` | `...:coui-squircle` |
| `...:miuix-nav` | `...:coui-nav` |
| `...:miuix-shader` | `...:coui-shader` |
| `...:miuix-core` | `...:coui-core` |

（COUI 额外有 `coui-navigation3-ui`，Miuix 无对应。）

### 5.2 包名与符号

- 包前缀整体替换：`top.yukonga.miuix.kmp` → `io.github.suqi8.coui.kmp`（子包 `basic/theme/utils/icon/preference/blur/nav` 结构不变）：

```bash
grep -rl "top.yukonga.miuix.kmp" src | xargs sed -i '' 's/top\.yukonga\.miuix\.kmp/io.github.suqi8.coui.kmp/g'
```

- `Miuix*` 前缀符号 1:1 改名：

| Miuix | COUI |
| --- | --- |
| `MiuixTheme`（composable + object） | `COUITheme` |
| `MiuixIndication` | `COUIIndication` |
| `MiuixPopupUtils` | `COUIPopupUtils` |
| `MiuixPopupHost` | `COUIPopupHost` |
| `MiuixScrollBehavior` | `COUIScrollBehavior` |
| `MiuixIcons` | `COUIIcons` |
| `MiuixOverscrollEffect/Factory` | `COUIOverscrollEffect/Factory` |
| `NavTransitions.MiuixDefault` | `NavTransitions.COUIDefault` |
| `BadgedBox` | `BadgeBox`（另加 `overhang` 参数，`Badge` 改为 count 驱动） |

### 5.3 语义有变化的 API（迁移时要手改的点）

1. `PressFeedbackType` 新增 `Tint`：对枚举做穷尽 `when` 的代码会编译失败，补分支即可。
2. `TextField`：新增 `backgroundMode: TextFieldMode`（Rectangle/Line/None，默认 Line）；`useLabelAsPlaceholder` 默认改 `true`；新增 `justShowFocusLine/isError/maxCount/showClearButton/showPasswordToggle`；`insideMargin/textStyle` 默认值随 mode 变化。
3. `OverlayDialog/WindowDialog`：content **无内边距**，需要自己包 padding（ColorOS 按钮条通铺）。
4. `TopAppBar`：navigationIcon 自带 16dp 内边距，不要再叠 start padding。
5. `TabRow`：`itemSpacing` 默认 0dp；普通 TabRow 透明背景 + 新增 `indication`；`TabRowWithContour` 新增 `contourPadding`。
6. `Switch` 新增 `isLoading`。
7. 旧 `extra.*`（`SuperDialog/SuperSwitch/SuperArrow/SuperDropdown/SuperSpinner/...`）→ `overlay/window` + `preference` 组件（`SuperDialog` 的 `show` 从 `MutableState<Boolean>` 变为普通 `Boolean`）。
8. 自定义 `Colors` 构造参数顺序未变（位置参数可编译），但建议直接删掉自绘 palette，用 `lightColorScheme()/darkColorScheme()` 的 ColorOS 16 原生配色。
9. 视觉差异（不改代码也看得见）：配色/圆角/间距、动效曲线、按压反馈、1.1.0 文本行高变大（headline1 17→16sp）、对话框按钮条超宽时竖排、下拉菜单改为「按下位置弹出、拖动高亮、逐行触感」、菜单不再默认压暗背景。

### 5.4 Yunnijian 上游 `MainActivity.kt` 逐行迁移清单（678 行，Miuix 0.9.4）

`/tmp/yunni/app/app/src/main/java/com/pyb110/onekey/MainActivity.kt` 用到的 Miuix API 及替换：

| 现文件位置 | Miuix 用法 | COUI 替换 | 备注 |
| --- | --- | --- | --- |
| build.gradle.kts deps | `top.yukonga.miuix.kmp:miuix-ui-android:0.9.4` 等 3 个 | `io.github.suqi8.coui.kmp:coui-ui-android:1.1.0` / `-preference-android` / `-icons-android` | 坐标换掉即可 |
| imports L54-69 | `top.yukonga.miuix.kmp.basic.*` `theme.*` `icon.*` `preference.*` `utils.PressFeedbackType` | `io.github.suqi8.coui.kmp.` 同子包 | 批量 sed |
| L64-65 | `MiuixIcons` + `icon.extended.Notes` | `COUIIcons` + `icon.extended.Notes` | 图标名一致 |
| L115-116 | `MiuixTheme {` | `COUITheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {` | 想跟随系统深浅色 |
| L120 | `MiuixScrollBehavior()` | `COUIScrollBehavior()` | |
| L129-131 | `Icon(imageVector = MiuixIcons.Notes, tint = MiuixTheme.colorScheme.onBackground)` | `Icon(imageVector = COUIIcons.Notes, tint = COUITheme.colorScheme.onBackground)` | `onBackground` 属性存在 |
| L210/213 | `CardDefaults.defaultColors(color = green)` | 同名同签名 | |
| L214-215 | `showIndication/pressFeedbackType = PressFeedbackType.Tilt` | 同名 | `Tint` 是新默认，Tilt 仍可用 |
| L171 | `ButtonDefaults.textButtonColorsPrimary()` | 同名 | |
| L262-278 | `WindowDropdownPreference(title/summary/items/selectedIndex/startAction/onSelectedIndexChange)` | 同名同参 | 菜单弹出行为变化（点按位置） |
| L283 | `MiuixTheme.colorScheme.onSurfaceVariantSummary` | `COUITheme.colorScheme.onSurfaceVariantSummary` | Colors 属性面基本一致 |
| L292-304 | `SwitchPreference(title/checked/onCheckedChange/startAction)` | 同名同参 | |
| L332/338 | `MiuixTheme.textStyles.headline1/body2` | `COUITheme.textStyles.headline1/body2` | 文本行高变大、headline1 16sp |
| L366-373 | `Scaffold(topBar=..., popupHost = { })` | 同名，popupHost 可省略/用默认 COUIPopupHost | |
| L370 | `TextButton(text=..., onClick=...)` | 同名 | |

迁移后**预期视觉变化**：状态卡圆角/配色/按压缩放不同、文字更松、下拉菜单弹出位置改为手指处、对话框按钮条在窄屏可能竖排。功能代码（Shizuku/process/资产部署）零改动。

预计迁移工时：**0.5~1 人日**（sed 批替 + 编译修复 + 真机视觉走查）。

---

## 6. 本项目（ondevice-app）现状盘点

| 项 | 现状 |
| --- | --- |
| UI 实现 | 纯 Java 手写 View：`MainActivity.java` 1819 行，29 个 `TextView`、24 个 `LinearLayout`、4 个 `ScrollView`、4 个 `Button`、`GradientDrawable` 手绘卡片/按钮（`surface()/card()/filledButton()/flatButton()/outlinedButton()/mdChip()/linkRow()/avatarView()`） |
| 页面结构 | 3 个 Tab（HOME/LOG/ABOUT）+ 底部导航；状态卡、设备信息卡、管理器选择、安全弹窗开关、进度/日志、赞赏/关于/外链 |
| 构建 | `build.sh`：`aapt2 compile/link` + `javac` + `d8` + `zipalign` + `apksigner`；**无 Gradle/AndroidX/Kotlin**；依赖 `android.jar(35)` + Stellar API jars + Kotlin stdlib（仅运行期） |
| 签名 | `debug.keystore`（安卓调试签名），versionCode 16 / versionName 2.3.3 |
| APK | 9,287,747 B；`classes.dex` 2,236,564 B；assets 未压缩约 19MB（ksud342 7.06MB、syms.txt 6.25MB、t64560_* / forker / ksu-template.ko 等） |
| min/target | minSdk 29 / targetSdk 35 / compileSdk 35（aapt2 `--target-sdk-version 35`） |
| 本机工具链 | JDK 21.0.12.1 + 17；Android SDK platforms 34/35/36.1/**37.0**、build-tools 37.0.0；Gradle 9.7.1 wrapper 已缓存；~/.gradle 缓存 ~2GB |

**与上游对比**：Yunnijian 上游同一功能的产品是 Kotlin/Compose/Miuix（678 行 MainActivity，AGP 9.4.1 + Compose BOM 2026.09.00 + Kotlin 2.4.20 + compileSdk 37 + minSdk 31）；本机也已成功构建其 debug APK（~88MB，debug 未压缩）。**上游已经在 Compose 生态里**，这是决策的关键背景。

---

## 7. 「继续手搓 Java」 vs 「引入 Gradle+Compose」——现实取舍

### 7.1 三条路线对比

| 维度 | A. 引入 Gradle+Compose，UI 重写（推荐给长期） | B. 保持 aapt2/Java，只借鉴 COUI 视觉 | C. 在 aapt2 里硬塞 Kotlin/Compose（不推荐） |
| --- | --- | --- | --- |
| 能否用 COUI 真组件 | ✅ 全量（含 Preference/Blur/Squircle/动效/触感） | ❌ 只能手抄配色/圆角/触感/模糊 | ⚠️ 理论可以，实际不可维护 |
| 构建改造 | 补 settings/build.gradle + wrapper + JDK21 + SDK37（约 0.5~1 天） | 无 | 手写 Compose 编译器插件、AAR 资源合并、R8、baseline profile ⇒ 相当于自研半个 AGP |
| UI 工作量 | 重写 700~900 行 Java View 为 ~400-600 行 Compose（1.5~3 天）+ 真机联调（1~2 天） | 改 10 个绘制/交互 helper + 触感/模糊（0.5~1 天） | 同 A 的 UI 量 + 数倍构建调试 |
| 合计（1 人） | **3~5 人日**（含风险缓冲 4~6） | **0.5~1 人日** | 5~10 人日且持续维护成本 |
| APK 体积 | +1.8~1.9MB（9.29MB → 约 11.3~11.5MB） | +0（略增图片资源） | 同 A |
| 运行风险 | Compose 生态成熟；AGP9/Kotlin2.4 属新版本组合（本机已实测构建通过） | 最低 | 最高 |
| 与上游 Yunnijian 合并 | ✅ 同一套 Compose 代码，diff 可移植 | ❌ 永远分叉，上游改动要手工翻译 | ⚠️ 构建体系仍不同 |
| 后续维护 | Kotlin/Compose 主流，AI/协作者门槛低 | Java View 手搓，UI 改动继续低效 | 两头不靠 |
| 依赖供应链 | 引入 Maven 依赖（COUI/Miuix/Compose 生态） | 无新增依赖 | 同 A 且更混乱 |

### 7.2 推荐

- **如果目标是「长期跟 Yunnijian 上游 + 想要 ColorOS 观感」→ 走 A，并且一步到位直接用 COUI 替代 Miuix**（迁移文档逐条 1:1，0.5~1 天即可完成上游代码的坐标/包名替换），不要先落 Miuix 再换 COUI。
- **如果目标是「尽快交付、UI 不是卖点、不打算跟上游」→ 走 B**。COUI 的视觉令牌可手工复刻到 Java：
  - 配色：从 `Colors.kt`/ColorOS 16 palette 取 `surfaceContainer/surfaceContainerHigh/onSurface/primary` 等（可参考 COUI 的 `lightColorScheme()`）；
  - 卡片：12dp 圆角 + `GradientDrawable` + 按压 `Color(0x1F000000)` Tint（亮）/ `0x33FFFFFF`（暗），按下缩放 0.92~0.98；
  - 触感：`View.performHapticFeedback(302)`（开关）/`308`（步进），失败回退 `6`/`4`；
  - 模糊：API 31+ `View.setRenderEffect(RenderEffect.createBlurEffect(...))` 做毛玻璃（API 33+ 可用 AGSL RuntimeShader 做高光/降噪）；仅 OnePlus 16（Android 16）部署时可放心用；
  - 动效曲线：spring(stiffness≈438.65, damping≈1) / cubic-bezier(0,0,0.1,1)。
- **不建议 C**：Compose UI 依赖 AGP 的资源合并、Compose 编译器插件、R8/baseline profile 这一整套；在 `build.sh` 里复刻等价物是把构建系统重写一遍，且与上游/社区完全脱节。

### 7.3 若走 A：落地步骤（建议顺序）

1. **搭 Gradle 骨架**：`settings.gradle.kts`/`build.gradle.kts` + wrapper 9.7.1 + JDK21 + compileSdk 37，先让现有 Java 源码原样编译通过（Stellar API jars 用 `implementation(files("libs/..."))` 本地化，manifest 保留 `StellarProvider`/`queries`/`WAKE_LOCK`，assets 用 `sourceSets.main.assets.srcDirs` 挂接，签名沿用 `debug.keystore`）。
2. **加 Compose 依赖**（本仓已证明的版本组合）并新建 `MainActivity.kt`：`COUITheme + Scaffold + TopAppBar + LazyColumn` 复刻 3 个 Tab。
3. **业务逻辑不重写**：`Prep/ShizukuShell/ProcessRunner/RootDiagnostics` 直接 `import dev.onekey.jb.*` 调用；回调/状态用 `mutableStateOf` 包一层（或先 `ComposeView` 桥，见 7.4）。
4. **逐步替换**：状态卡→Card、管理器选择→WindowDropdownPreference、安全弹窗开关→SwitchPreference、日志页→LazyColumn + `scrollEndHaptic`、关于页→Card/ButtonPreference/ArrowPreference、免费声明弹窗→OverlayDialog、赞赏弹窗→WindowDialog。
5. **收尾**：R8 规则、release 签名、体积核对（目标 ≤12MB）、真机验证（OnePlus 16，Android 16 → blur/squircle/haptic 全部可用）。

### 7.4 折中路径 A2：保留 Java `MainActivity`，只把 UI 换成 ComposeView（已实测可编译）

适合「不想一次性重写 Activity/生命周期」的过渡期：

```kotlin
// CouiHost.kt —— Kotlin 侧桥接，暴露普通 Java 可调用的入口
object CouiHost {
    @JvmStatic
    fun install(view: ComposeView, title: String, onPrimaryAction: Runnable) {
        view.setContent {
            COUITheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                AppScreen(title = title, onPrimaryAction = { onPrimaryAction.run() })
            }
        }
    }
}
```

```java
// JavaHostActivity.java —— 保持 Java Activity，只挂 ComposeView
public class JavaHostActivity extends ComponentActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ComposeView composeView = new ComposeView(this);
        CouiHost.install(composeView, "COUI from Java", () -> LegacyJavaLogic.versionLine());
        setContentView(composeView);
    }
}
```

代价：状态/回调需要一层 Java↔Kotlin 桥（Runnable/接口），生命周期混用两套；不如 A 干净，但迁移可分批。

---

## 8. 风险与注意事项

1. **实验性库**：README 明示 API 可能变；1.1.0 是第二个正式版。锁死 `1.1.0` 并记录校验和；建议开启 Gradle dependency verification（`verification-metadata.xml`）。
2. **供应链**：单维护者 + 传递依赖 material-kolor / material-color-utilities / navigationevent 等；对 root/越狱工具这类高敏感 App，引入 Maven 依赖前应至少做一次源码/二进制审查或 vendor 进来自己构建（COUI 是 Apache-2.0，可自行编译）。
3. **compileSdk 强制 37**：会改变 App 的编译目标（当前 35）；AGP 9.x + SDK 37 属新组合，CI/协作者环境需同步升级（本机已装 37.0，其他机器要补）。
4. **minSdk 强制 33（若用 blur）**：当前 minSdk 29；越狱目标机是一加16（Android 16），实际不损失用户，但 manifest 要改，且理论上降低兼容性。
5. **Compose 版本对齐**：COUI 1.1.0 编译基线 androidx Compose 1.11.2，探针用 BOM 2026.09.00（1.12.1）编译/R8 通过；**未做真机运行验证**（避免干扰同批次真机 root 痕迹审计），首次落地务必真机跑一遍（尤其 blur/Highlight/RuntimeShader）。
6. **APK 增大**：+1.8~1.9MB（R8 后）；若在意体积可不上 `coui-icons`（1.9MB AAR，R8 后按用量裁剪）或改用 androidx material-icons（已在用）。
7. **首次构建耗时/缓存**：探针冷构建 ~1m50s（依赖下载），缓存后增量 <1min；CI 需预热 ~2GB Gradle 缓存。
8. **许可合规**：Apache-2.0，保留 LICENSE/NOTICE；发行包内加第三方许可声明（COUI + Miuix 上游）。
9. **不要在计划中假设 COUI 提供 Miuix 没有的「越狱专用」能力**：它只是 UI 库；Blur 是视觉特效，不涉及权限/系统能力。

---

## 9. 附录

### 9.1 实测环境

- macOS（Darwin），Apple Silicon；JDK 21.0.12.1（Homebrew `/opt/homebrew/opt/openjdk@21`）。
- Android SDK：platforms android-34/35/36.1/**37.0**；build-tools 34/35/36.0/36.1/**37.0**。
- Gradle 9.7.1（wrapper，`~/.gradle/wrapper/dists` 已缓存）；AGP 9.4.1；Compose 编译插件 2.4.20（AGP 9 内置 Kotlin，无需 kotlin-android 插件）。
- androidx Compose BOM 2026.09.00（= androidx.compose 1.12.1）；COUI 1.1.0（编译基线 CMP 1.11.1→androidx 1.11.2）。
- 探针工程 `/tmp/coui-probe`（未提交、未改本仓库源码）；`/tmp/coui-src` 为 COUI 源码浅克隆（v1.1.0，commit `e4e915c`）。

### 9.2 复现命令

```bash
# 1) 拉源码
git clone --depth 1 https://github.com/suqi8/coui.git /tmp/coui-src
# 2) 探针工程构建（见 §2.1 配置）
cd /tmp/coui-probe
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleRelease
# 3) 体积测量
ls -l app/build/outputs/apk/release/app-release-unsigned.apk
unzip -l app/build/outputs/apk/release/app-release-unsigned.apk | grep classes.dex
```

### 9.3 参考链接

- 仓库：<https://github.com/suqi8/coui>（Apache-2.0，v1.1.0 tag `e4e915c`）
- 文档站：<https://suqi8.github.io/coui/>（含中文 `docs/zh_CN`）；关键页：`docs/guide/getting-started.md`、`migration.md`、`blur.md`、`theme.md`、`utils.md`、`squircle.md`、`upgrading.md`
- Maven Central：<https://central.sonatype.com/search?q=g:io.github.suqi8.coui.kmp>
- Miuix 上游：<https://github.com/compose-miuix-ui/miuix>
- 本地上游参考工程：`/tmp/yunni`（Yunnijian fork，Kotlin/Compose/Miuix 0.9.4）
- 本仓库相关：`ondevice-app/build.sh`（aapt2 构建）、`ondevice-app/src/dev/onekey/jb/MainActivity.java`（1819 行 Java UI）、`docs/merge-upstream-diff.md`（task-1 上游差异，另见）、`docs/detection-audit.md`（task-3 真机痕迹，另见）

---

## 10. 给决策者的一页纸

- **想省事**：继续 Java，抄 COUI 的配色/圆角/Tint/触感/模糊，0.5~1 天，外观立刻接近 ColorOS 16。
- **想长期**：上 Gradle+Compose+COUI（一步替掉 Miuix），3~5 人日；换来与上游同构的 UI 代码、完整 ColorOS 组件与动效、后续移植成本最低。
- **想折中**：先 A2（Java Activity + ComposeView 桥）用 1~2 天跑通一个页面，验证真机观感后再决定全面重写。
- **千万别做**：在 `build.sh` 里手工串 Kotlin/Compose 编译器 + AAR 资源合并（C 路线）。
