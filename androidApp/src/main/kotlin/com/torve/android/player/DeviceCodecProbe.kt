package com.torve.android.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import com.torve.domain.model.DeviceCodecCaps

/**
 * Probes the device's hardware and software decoders via MediaCodecList
 * to build a [DeviceCodecCaps] snapshot. Called once at player init.
 *
 * Critical: captures actual HEVC profile/level limits — not just "supports HEVC".
 * Many devices (emulators, low-end phones) report HEVC support but fail on
 * high profiles (Main10, HDR) or high levels (L120/L150 = 4K).
 */
object DeviceCodecProbe {

    private const val TAG = "DeviceCodecProbe"

    private const val MIME_AVC = "video/avc"
    private const val MIME_HEVC = "video/hevc"
    private const val MIME_VP9 = "video/x-vnd.on2.vp9"
    private const val MIME_AV1 = "video/av01"

    fun probe(): DeviceCodecCaps {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val infos = codecList.codecInfos

        var h264 = false
        var hevc = false
        var hevcMain10 = false
        var vp9 = false
        var av1 = false
        var maxHevcMainLevel: Int? = null
        var maxHevcMain10Level: Int? = null

        for (info in infos) {
            if (info.isEncoder) continue

            for (mime in info.supportedTypes) {
                when (mime.lowercase()) {
                    MIME_AVC -> {
                        h264 = true
                    }
                    MIME_HEVC -> {
                        hevc = true
                        try {
                            val caps = info.getCapabilitiesForType(MIME_HEVC)
                            for (pl in caps.profileLevels) {
                                // Track max Main profile level
                                if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) {
                                    if (maxHevcMainLevel == null || pl.level > maxHevcMainLevel!!) {
                                        maxHevcMainLevel = pl.level
                                    }
                                }

                                // Track Main10 support and max level
                                if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                    pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                                    (Build.VERSION.SDK_INT >= 33 &&
                                        pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
                                ) {
                                    hevcMain10 = true
                                    if (maxHevcMain10Level == null || pl.level > maxHevcMain10Level!!) {
                                        maxHevcMain10Level = pl.level
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to check HEVC profiles", e)
                        }
                    }
                    MIME_VP9 -> {
                        vp9 = true
                    }
                    MIME_AV1 -> {
                        av1 = true
                    }
                }
            }
        }

        // Detect weak HEVC devices:
        // - Emulators (goldfish/ranchu/sdk)
        // - Devices where max HEVC level is too low for typical content (< Level 4.1 = 16384)
        // Level 4.1 (16384) supports 1080p@60fps — minimum for modern streaming content
        val isEmulator = Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.FINGERPRINT.contains("generic", ignoreCase = true)

        val hevcLevelTooLow = maxHevcMainLevel != null &&
            maxHevcMainLevel!! < MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41

        val isWeakHevc = isEmulator || (hevc && hevcLevelTooLow)

        val caps = DeviceCodecCaps(
            supportsH264 = h264,
            supportsHevc = hevc && !isWeakHevc,
            supportsHevcMain10 = hevcMain10 && !isWeakHevc,
            supportsVp9 = vp9,
            supportsAv1 = av1,
            maxHevcMainLevel = maxHevcMainLevel,
            maxHevcMain10Level = maxHevcMain10Level,
            isWeakHevcDevice = isWeakHevc,
        )

        Log.d(TAG, "Device codec caps: $caps")
        if (isEmulator) Log.d(TAG, "Emulator detected — HEVC disabled for stability")
        if (hevcLevelTooLow) Log.d(TAG, "HEVC level too low (max=$maxHevcMainLevel, need>=16384) — HEVC disabled")

        return caps
    }
}
