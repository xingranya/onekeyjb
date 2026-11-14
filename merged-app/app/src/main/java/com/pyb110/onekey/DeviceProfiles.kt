package com.pyb110.onekey

import android.os.Build

data class DeviceProfile(
    val id: String,
    val displayName: String,
    val model: String,
    val device: String?,
    val kernelRelease: String,
    /** 内核模块 KMI 标识，传给执行器的 PYB110_KMI。 */
    val kmi: String,
    /** 该机型使用的 98260 执行器资产（按内核拆分的 runner）。 */
    val runnerAsset: String,
    /** SUSFS 模块资产；不同 KMI 需要各自编译的模块。 */
    val susfsAsset: String,
    /** 是否支持 hybridmount VFS 重定向（仅 6.18 内核有对应模块）。 */
    val vfsSupported: Boolean = false,
)

object DeviceProfiles {
    /** 一加 16 · ColorOS 17.0.0.x（内核 commit f957acc）。 */
    val PYB110 = DeviceProfile(
        id = "pyb110",
        displayName = "OnePlus 16",
        model = "PYB110",
        device = "OP64E1L1",
        kernelRelease = "6.18.21-android17-5-gf957accaf563-ab15926176-4k",
        kmi = "android17-6.18",
        runnerAsset = "core/exec/onekey-98260",
        susfsAsset = "core/exec/susfs_guard_lkm.ko",
        vfsSupported = true,
    )

    /** 一加 16 · ColorOS 17.0.1.201（内核 commit 7fcbffcad3e1）。 */
    val PYB110_201 = DeviceProfile(
        id = "pyb110",
        displayName = "OnePlus 16",
        model = "PYB110",
        device = "OP64E1L1",
        kernelRelease = "6.18.21-android17-5-g7fcbffcad3e1-ab16353812-4k",
        kmi = "android17-6.18",
        runnerAsset = "core/exec/onekey-98260",
        susfsAsset = "core/exec/susfs_guard_lkm.ko",
        vfsSupported = true,
    )

    /** 一加 Ace 7 Pro · 6.12.69。 */
    val PMS110 = DeviceProfile(
        id = "pms110",
        displayName = "OnePlus Ace 7 Pro",
        model = "PMS110",
        device = null,
        kernelRelease = "6.12.69-android16-6-g242e1a07f878-ab15865446-4k",
        kmi = "android16-6.12",
        runnerAsset = "core/exec/onekey-98260-61269",
        susfsAsset = "core/exec/susfs_guard_lkm_612.ko",
    )

    val all = listOf(PYB110, PYB110_201, PMS110)

    fun current(): DeviceProfile? {
        val model = Build.MODEL.trim().uppercase()
        val device = Build.DEVICE.trim().uppercase()
        val kernel = (System.getProperty("os.version") ?: "").trim()
        return all.firstOrNull { p ->
            p.model.equals(model, ignoreCase = true) &&
                (p.device == null || p.device.equals(device, ignoreCase = true)) &&
                p.kernelRelease == kernel
        }
    }

    fun forDevice(): DeviceProfile? {
        val model = Build.MODEL.trim().uppercase()
        val device = Build.DEVICE.trim().uppercase()
        return all.firstOrNull { p ->
            p.model.equals(model, ignoreCase = true) &&
                (p.device == null || p.device.equals(device, ignoreCase = true))
        }
    }

    /** 机型命中但内核串不认识时，用它提示支持哪些 release。 */
    fun knownReleasesFor(profile: DeviceProfile?): List<String> =
        profile?.let { p -> all.filter { it.id == p.id }.map { it.kernelRelease } } ?: emptyList()
}
