package com.torve.android.tv.screens

internal data class TvMultiviewDeviceProfile(
    val apiLevel: Int,
    val isLowRamDevice: Boolean,
    val memoryClassMb: Int,
)

/**
 * Conservative two-decoder gate. Older and low-memory Fire TV hardware is
 * more valuable running one stable stream than exposing a control that can
 * trigger decoder or process death.
 */
internal object TvMultiviewCapability {
    private const val MIN_API_LEVEL = 26
    private const val MIN_MEMORY_CLASS_MB = 256

    fun isSupported(profile: TvMultiviewDeviceProfile): Boolean =
        profile.apiLevel >= MIN_API_LEVEL &&
            !profile.isLowRamDevice &&
            profile.memoryClassMb >= MIN_MEMORY_CLASS_MB
}
