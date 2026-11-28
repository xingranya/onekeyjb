import java.io.FileInputStream
import java.util.Properties

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pyb110.onekey"
    compileSdk {
        // COUI alpha17 与 Compose 1.13.0 的 aar-metadata 要求 minCompileSdk=37、minCompileMinorSdk=1，
        // 只到 37.0 会在 checkReleaseAarMetadata 阶段直接失败，所以钉到 37.2。
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        // 与 v2.3.3 相同的 applicationId，便于覆盖安装；versionCode 必须更大
        applicationId = "dev.onekey.jb"
        minSdk = 33
        targetSdk = 37
        versionCode = 224
        versionName = "2.25.0"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    signingConfigs {
        if (keystorePropsFile.exists()) create("release") {
            run {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }
    buildTypes {
        debug {
            // 本地有 keystore 时用同一把密钥，便于覆盖安装；CI 无密钥时构建未签名包
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // ManagerDownloads 依赖 org.json，JVM 单测里让 android.jar 的桩方法返回默认值
        unitTests.isReturnDefaultValues = true
    }
}


dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.core:core-ktx:1.17.0")

    // COUI —— ColorOS 17 原生观感组件库（io.github.itoseo:coui，与 Shark / LuckyTool 同源）
    implementation("io.github.itoseo:coui:0.0.1-alpha17")
    // 图标：COUI alpha11 起不再自带图标包，改用 Google 官方 Material 图标
    implementation("androidx.compose.material:material-icons-extended")

    // Stellar API（Shizuku 深度分支，ColorOS 17 上可用；替换 Shizuku）
    implementation("com.github.roro2239.Stellar-API:aidl:1.0.3")
    implementation("com.github.roro2239.Stellar-API:api:1.0.3")
    implementation("com.github.roro2239.Stellar-API:provider:1.0.3")
    implementation("com.github.roro2239.Stellar-API:shared:1.0.3")

    testImplementation("junit:junit:4.13.2")
}
