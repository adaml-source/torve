package com.torve.model

import com.torve.domain.model.DeviceCodecCaps
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for DeviceCodecCaps.canDecode() logic.
 */
class DeviceCodecCapsTest {

    private val fullDevice = DeviceCodecCaps(
        supportsH264 = true,
        supportsHevc = true,
        supportsHevcMain10 = true,
        supportsVp9 = true,
        supportsAv1 = true,
    )

    private val h264Only = DeviceCodecCaps(
        supportsH264 = true,
        supportsHevc = false,
        supportsHevcMain10 = false,
        supportsVp9 = false,
        supportsAv1 = false,
    )

    private val noMain10 = DeviceCodecCaps(
        supportsH264 = true,
        supportsHevc = true,
        supportsHevcMain10 = false,
        supportsVp9 = true,
        supportsAv1 = false,
    )

    @Test
    fun h264_alwaysSupported() {
        assertTrue(fullDevice.canDecode("H.264"))
        assertTrue(fullDevice.canDecode("x264"))
        assertTrue(fullDevice.canDecode("AVC"))
        assertTrue(h264Only.canDecode("H.264"))
    }

    @Test
    fun hevc_onlyWhenSupported() {
        assertTrue(fullDevice.canDecode("HEVC"))
        assertTrue(fullDevice.canDecode("x265"))
        assertTrue(fullDevice.canDecode("H.265"))
        assertFalse(h264Only.canDecode("HEVC"))
        assertFalse(h264Only.canDecode("x265"))
    }

    @Test
    fun hevc_main10_requiresExplicitSupport() {
        assertTrue(fullDevice.canDecode("HEVC", bitDepth = "10"))
        assertFalse(noMain10.canDecode("HEVC", bitDepth = "10"))
        // 8-bit HEVC still allowed on noMain10
        assertTrue(noMain10.canDecode("HEVC"))
    }

    @Test
    fun hevc_main10InCodecString() {
        assertFalse(noMain10.canDecode("HEVC MAIN10"))
        assertTrue(fullDevice.canDecode("HEVC MAIN10"))
    }

    @Test
    fun vp9_onlyWhenSupported() {
        assertTrue(fullDevice.canDecode("VP9"))
        assertFalse(h264Only.canDecode("VP9"))
    }

    @Test
    fun av1_onlyWhenSupported() {
        assertTrue(fullDevice.canDecode("AV1"))
        assertTrue(fullDevice.canDecode("AV01"))
        assertFalse(h264Only.canDecode("AV1"))
        assertFalse(noMain10.canDecode("AV1"))
    }

    @Test
    fun unknownCodec_alwaysAllowed() {
        assertTrue(fullDevice.canDecode(null))
        assertTrue(fullDevice.canDecode(""))
        assertTrue(h264Only.canDecode(null))
        assertTrue(h264Only.canDecode(""))
    }

    @Test
    fun safeBaseline_onlyH264() {
        val baseline = DeviceCodecCaps.SAFE_BASELINE
        assertTrue(baseline.canDecode("H.264"))
        assertFalse(baseline.canDecode("HEVC"))
        assertFalse(baseline.canDecode("AV1"))
        assertFalse(baseline.canDecode("VP9"))
    }

    @Test
    fun weakHevcDevice_rejectsAllHevc() {
        val weakDevice = DeviceCodecCaps(
            supportsH264 = true,
            supportsHevc = true, // device "claims" HEVC but is weak
            supportsHevcMain10 = false,
            supportsVp9 = true,
            supportsAv1 = false,
            isWeakHevcDevice = true,
        )
        assertTrue(weakDevice.canDecode("H.264"))
        assertFalse(weakDevice.canDecode("HEVC"))
        assertFalse(weakDevice.canDecode("x265"))
        assertTrue(weakDevice.canDecode("VP9")) // VP9 still allowed
    }

    @Test
    fun hdr_inTitle_requiresMain10() {
        assertFalse(noMain10.canDecode("HEVC", title = "Movie.2024.HDR.2160p"))
        assertTrue(fullDevice.canDecode("HEVC", title = "Movie.2024.HDR.2160p"))
    }

    @Test
    fun tenBit_inTitle_requiresMain10() {
        assertFalse(noMain10.canDecode("HEVC", title = "Movie.2024.10bit.1080p"))
        assertTrue(fullDevice.canDecode("HEVC", title = "Movie.2024.10bit.1080p"))
    }
}
