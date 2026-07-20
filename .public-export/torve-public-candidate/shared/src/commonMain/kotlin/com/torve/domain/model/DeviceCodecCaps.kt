package com.torve.domain.model

/**
 * Device codec capability information.
 * On Android this queries MediaCodecList; on other platforms defaults to safe baseline.
 *
 * This is the single source of truth for "can this device decode codec X?"
 * Used by StreamSelector to filter out streams the device cannot play.
 *
 * HEVC profile/level is critical: many devices report "supports HEVC" but fail on
 * high profiles (Main10, HDR) or high levels (L120/L150 = 4K). We capture the actual
 * max supported HEVC level so we can reject streams that exceed device capabilities
 * BEFORE they reach the decoder (zero codec errors).
 */
data class DeviceCodecCaps(
    val supportsH264: Boolean = true,
    val supportsHevc: Boolean = false,
    val supportsHevcMain10: Boolean = false,
    val supportsVp9: Boolean = false,
    val supportsAv1: Boolean = false,
    /**
     * Max HEVC Main profile level the device reliably decodes.
     * Uses Android's CodecProfileLevel constants (e.g. HEVCMainTierLevel41 = 16384).
     * null = unknown (treat as weak HEVC support).
     */
    val maxHevcMainLevel: Int? = null,
    /**
     * Max HEVC Main10 profile level. null = no Main10 or unknown.
     */
    val maxHevcMain10Level: Int? = null,
    /**
     * True if the device is an emulator or known-weak HEVC device.
     * When true, HEVC is disabled entirely — only H.264/VP9 allowed.
     */
    val isWeakHevcDevice: Boolean = false,
) {
    /**
     * Returns true if the device can decode [codec] (as extracted by StreamParser).
     * [bitDepth] is optional; if "10" and codec is HEVC, requires Main10 support.
     * [title] is optional; used to detect high-profile indicators in torrent names.
     */
    fun canDecode(codec: String?, bitDepth: String? = null, title: String? = null): Boolean {
        val t = title?.uppercase().orEmpty()

        // Even when codec is unknown, reject if the title clearly indicates
        // HEVC/DV content and this is a weak HEVC device
        if (codec.isNullOrBlank()) {
            if (isWeakHevcDevice && titleImpliesHevc(t)) return false
            return true
        }

        val c = codec.uppercase()
        return when {
            c.contains("H.264") || c.contains("H264") || c.contains("X264") || c.contains("AVC") ->
                supportsH264

            c.contains("HEVC") || c.contains("H.265") || c.contains("H265") || c.contains("X265") ||
                c.contains("DV") || c.contains("DOLBY") -> {
                // Weak HEVC device (emulator, low-end) = reject all HEVC / DV
                if (isWeakHevcDevice) return false

                val is10Bit = bitDepth == "10" ||
                    c.contains("MAIN10") || c.contains("MAIN 10") ||
                    t.contains("10BIT") || t.contains("10-BIT") || t.contains("10 BIT") ||
                    t.contains("MAIN10") || t.contains("MAIN 10")

                val isHdr = t.contains("HDR") || t.contains("DOLBY VISION") || t.contains("DV")

                when {
                    isHdr -> supportsHevcMain10 // HDR requires Main10 minimum
                    is10Bit -> supportsHevcMain10
                    else -> supportsHevc
                }
            }

            c.contains("VP9") -> supportsVp9
            c.contains("AV1") || c.contains("AV01") -> supportsAv1

            else -> true // unknown codec, allow but deprioritize
        }
    }

    private fun titleImpliesHevc(upperTitle: String): Boolean =
        upperTitle.contains("HEVC") || upperTitle.contains("H.265") || upperTitle.contains("H265") ||
            upperTitle.contains("X265") || upperTitle.contains("DOLBY VISION") ||
            upperTitle.contains("DV") || upperTitle.contains("HDR")

    companion object {
        /** Safe fallback for platforms where we cannot query codecs — H.264 only. */
        val SAFE_BASELINE = DeviceCodecCaps(
            supportsH264 = true,
            supportsHevc = false,
            supportsHevcMain10 = false,
            supportsVp9 = false,
            supportsAv1 = false,
            isWeakHevcDevice = true,
        )
    }
}
