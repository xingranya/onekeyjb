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
        mavenCentral()
        // Stellar API 的 JitPack 仓库（官方发布坐标：com.github.roro2239.Stellar-API）
        maven { url = uri("https://jitpack.io") }
        // 本地离线镜像：libs/stellar-repo 内置 1.0.3 的 aidl/api/provider/shared（与 JitPack 产物一致）
        maven { url = uri("libs/stellar-repo") }
    }
}
rootProject.name = "OneKeyJB"
include(":app")
