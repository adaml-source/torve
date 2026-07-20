package com.torve.model

import com.torve.domain.model.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamQualityTest {

    @Test
    fun fromString_4k() {
        assertEquals(StreamQuality.UHD_4K, StreamQuality.fromString("2160p"))
        assertEquals(StreamQuality.UHD_4K, StreamQuality.fromString("4K UHD"))
        assertEquals(StreamQuality.UHD_4K, StreamQuality.fromString("UHD BluRay"))
    }

    @Test
    fun fromString_remux4k() {
        assertEquals(StreamQuality.REMUX_4K, StreamQuality.fromString("Remux 2160p"))
        assertEquals(StreamQuality.REMUX_4K, StreamQuality.fromString("4K REMUX"))
    }

    @Test
    fun fromString_1080p() {
        assertEquals(StreamQuality.FHD_1080P, StreamQuality.fromString("1080p"))
        assertEquals(StreamQuality.FHD_1080P, StreamQuality.fromString("1080p BluRay"))
    }

    @Test
    fun fromString_720p() {
        assertEquals(StreamQuality.HD_720P, StreamQuality.fromString("720p"))
        assertEquals(StreamQuality.HD_720P, StreamQuality.fromString("720p WEB-DL"))
    }

    @Test
    fun fromString_480p() {
        assertEquals(StreamQuality.SD_480P, StreamQuality.fromString("480p"))
    }

    @Test
    fun fromString_defaultsTo1080p() {
        assertEquals(StreamQuality.FHD_1080P, StreamQuality.fromString("unknown"))
        assertEquals(StreamQuality.FHD_1080P, StreamQuality.fromString(""))
    }
}
