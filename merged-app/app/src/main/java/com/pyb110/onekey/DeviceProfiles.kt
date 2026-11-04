package com.pyb110.onekey

import android.os.Build

data class DeviceProfile(
    val id: String,
    val displayName: String,
    val model: String,
    val device: String?,
    val kernelRelease: String,
    val symbolsAsset: String,
    val onuceSymbolsAsset: String,
    val onuceAsset: String,
    /** 98260 原生执行器是否按当前内核构建标定过；未标定时只允许尝试，失败不算回归。 */
    val onuceVerified: Boolean = true,
)

object DeviceProfiles {
    /** 一加 16 · ColorOS 17.0.0.x（内核 commit f957acc）。 */
    val PYB110 = DeviceProfile(
        id = "pyb110",
        displayName = "OnePlus 16",
        model = "PYB110",
        device = "OP64E1L1",
        kernelRelease = "6.18.21-android17-5-gf957accaf563-ab15926176-4k",
        symbolsAsset = "core/symbols.txt",
        onuceSymbolsAsset = "core/symbols.txt",
        onuceAsset = "core/onuce/onuce_111_98260",
    )

    /** 一加 16 · ColorOS 17.0.1.201（内核 commit 7fcbffcad3e1，执行器已按新构建放行）。 */
    val PYB110_201 = DeviceProfile(
        id = "pyb110",
        displayName = "OnePlus 16",
        model = "PYB110",
        device = "OP64E1L1",
        kernelRelease = "6.18.21-android17-5-g7fcbffcad3e1-ab16353812-4k",
        symbolsAsset = "core/symbols-1701201.txt",
        onuceSymbolsAsset = "core/symbols-1701201.txt",
        onuceAsset = "core/onuce/onuce_111_98260_201",
        // 偏移仍由 symbols 文件提供；新构建尚未在真机跑通整链，失败请导出日志。
        onuceVerified = false,
    )

    val PMS110 = DeviceProfile(
        id = "pms110",
        displayName = "OnePlus Ace 7 Pro",
        model = "PMS110",
        device = null,
        kernelRelease = "6.12.69-android16-6-g242e1a07f878-ab15865446-4k",
        symbolsAsset = "core/onuce/device-symbols-612.txt",
        onuceSymbolsAsset = "core/onuce/device-symbols-612.txt",
        onuceAsset = "core/onuce/onuce_111_64560",
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
