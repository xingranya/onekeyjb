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
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        // 与 v2.3.3 相同的 applicationId，便于覆盖安装；versionCode 必须更大
        applicationId = "dev.onekey.jb"
        minSdk = 33
        targetSdk = 37
        versionCode = 204
        versionName = "2.19.6"
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

    // COUI —— ColorOS 风格 Compose Multiplatform 组件库（替换 Miuix）
    implementation("io.github.suqi8.coui.kmp:coui-ui:1.1.0")
    implementation("io.github.suqi8.coui.kmp:coui-preference:1.1.0")
    implementation("io.github.suqi8.coui.kmp:coui-icons:1.1.0")
    implementation("io.github.suqi8.coui.kmp:coui-blur:1.1.0")

    // Stellar API（Shizuku 深度分支，ColorOS 17 上可用；替换 Shizuku）
    implementation("com.github.roro2239.Stellar-API:aidl:1.0.3")
    implementation("com.github.roro2239.Stellar-API:api:1.0.3")
    implementation("com.github.roro2239.Stellar-API:provider:1.0.3")
    implementation("com.github.roro2239.Stellar-API:shared:1.0.3")
}
