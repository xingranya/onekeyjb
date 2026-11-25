pluginManagement {
    repositories {
        // 阿里云镜像优先：Google Maven 直连不稳（Connection reset），镜像命中率更高
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        // Stellar API 的 JitPack 仓库（官方发布坐标：com.github.roro2239.Stellar-API）
        maven { url = uri("https://jitpack.io") }
        // 本地离线镜像：libs/stellar-repo 内置 1.0.3 的 aidl/api/provider/shared（与 JitPack 产物一致）
        maven { url = uri("libs/stellar-repo") }
        // 本地离线镜像：libs/coui-repo 内置 COUI alpha17（Maven Central 拉不到时兜底）
        maven { url = uri("libs/coui-repo") }
    }
}
rootProject.name = "OneKeyJB"
include(":app")
