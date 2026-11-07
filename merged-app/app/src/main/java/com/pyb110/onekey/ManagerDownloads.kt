package com.pyb110.onekey

/** 五家管理器的官方发布入口；版本号在发布新版本时同步更新。 */
data class ManagerDownload(
    val kind: String,
    val label: String,
    val version: String,
    val versionCode: Int,
    val apkUrl: String,
    val pageUrl: String,
)

object ManagerDownloads {
    val all = listOf(
        ManagerDownload(
            kind = "kernelsu",
            label = "KernelSU",
            version = "v3.3.1",
            versionCode = 32680,
            apkUrl = "https://github.com/tiann/KernelSU/releases/download/v3.3.1/KernelSU_v3.3.1_32680-release.apk",
            pageUrl = "https://github.com/tiann/KernelSU/releases/tag/v3.3.1",
        ),
        ManagerDownload(
            kind = "next",
            label = "KernelSU Next",
            version = "v3.4.2",
            versionCode = 33355,
            apkUrl = "https://github.com/KernelSU-Next/KernelSU-Next/releases/download/v3.4.2/KernelSU_Next_v3.4.2_33355-release.apk",
            pageUrl = "https://github.com/KernelSU-Next/KernelSU-Next/releases/tag/v3.4.2",
        ),
        ManagerDownload(
            kind = "sukisu",
            label = "SukiSU Ultra",
            version = "v4.3.0-r2",
            versionCode = 41150,
            apkUrl = "https://github.com/SukiSU-Ultra/SukiSU-Ultra/releases/download/v4.3.0-r2/SukiSU_v4.3.0-r2_41150-release.apk",
            pageUrl = "https://github.com/SukiSU-Ultra/SukiSU-Ultra/releases/tag/v4.3.0-r2",
        ),
        ManagerDownload(
            kind = "bakasu",
            label = "BakaSU",
            version = "v4.2.0-rc3",
            versionCode = 35171,
            apkUrl = "https://github.com/Baka-SU/BakaSU/releases/download/v4.2.0-rc3/ReSukiSU_v4.2.0-rc3_35171-arm64-v8a-release.apk",
            pageUrl = "https://github.com/Baka-SU/BakaSU/releases/tag/v4.2.0-rc3",
        ),
        ManagerDownload(
            kind = "finasu",
            label = "FinaSU",
            version = "v1.5.0",
            versionCode = 33174,
            apkUrl = "https://github.com/LyraVoid/FinaSU/releases/download/v1.5.0/FinaSU_v1.5.0_33174-release.apk",
            pageUrl = "https://github.com/LyraVoid/FinaSU/releases/tag/v1.5.0",
        ),
    )
}
