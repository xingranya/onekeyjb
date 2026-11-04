package com.pyb110.onekey

import android.os.Build

data class DeviceProfile(
    val id: String,
    val displayName: String,
    val model: String,
    val device: String?,
    val kernelRelease: String,
    val supportsStable: Boolean,
    val supportsNew: Boolean,
    val symbolsAsset: String,
    val onuceSymbolsAsset: String,
    val onuceAsset: String,
)

object DeviceProfiles {
    val PYB110 = DeviceProfile(
        id = "pyb110",
        displayName = "OnePlus 16",
        model = "PYB110",
        device = "OP64E1L1",
        kernelRelease = "6.18.21-android17-5-gf957accaf563-ab15926176-4k",
        supportsStable = true,
        supportsNew = true,
        symbolsAsset = "core/symbols.txt",
        onuceSymbolsAsset = "core/symbols.txt",
        onuceAsset = "core/onuce/onuce_111_98260",
    )

    val PMS110 = DeviceProfile(
        id = "pms110",
        displayName = "OnePlus Ace 7 Pro",
        model = "PMS110",
        device = null,
        kernelRelease = "6.12.69-android16-6-g242e1a07f878-ab15865446-4k",
        supportsStable = false,
        supportsNew = true,
        symbolsAsset = "core/onuce/device-symbols-612.txt",
        onuceSymbolsAsset = "core/onuce/device-symbols-612.txt",
        onuceAsset = "core/onuce/onuce_111_64560",
    )

    val all = listOf(PYB110, PMS110)

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
}
