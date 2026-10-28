package com.torve.android.tv.screens

import androidx.media3.ui.AspectRatioFrameLayout
import com.torve.android.player.mpvPictureFormatProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePictureFormatTest {
    @Test
    fun `every exposed format has a distinct renderer configuration`() {
        val configurations = LivePictureFormat.entries.map(LivePictureFormat::renderConfiguration)

        assertEquals(LivePictureFormat.entries.size, configurations.toSet().size)
        assertTrue(LivePictureFormat.entries.none { it.key == "original" })
    }

    @Test
    fun `source and fill map to their intended uncropped and cropped modes`() {
        val source = LivePictureFormat.SOURCE.renderConfiguration
        assertNull(source.containerAspectRatio)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, source.exoResizeMode)
        assertNull(source.mpvAspectRatioOverride)
        assertEquals(0f, source.mpvPanscan, 0f)

        val fill = LivePictureFormat.FILL.renderConfiguration
        assertNull(fill.containerAspectRatio)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, fill.exoResizeMode)
        assertNull(fill.mpvAspectRatioOverride)
        assertEquals(1f, fill.mpvPanscan, 0f)
    }

    @Test
    fun `explicit ratios force the same presentation ratio in exo and mpv`() {
        val expected = mapOf(
            LivePictureFormat.RATIO_16_9 to (16f / 9f),
            LivePictureFormat.RATIO_4_3 to (4f / 3f),
            LivePictureFormat.RATIO_21_9 to (21f / 9f),
        )

        expected.forEach { (format, ratio) ->
            val configuration = format.renderConfiguration
            assertEquals(ratio, configuration.containerAspectRatio!!, 0.0001f)
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, configuration.exoResizeMode)
            assertEquals(ratio, configuration.mpvAspectRatioOverride!!, 0.0001f)
            assertEquals(0f, configuration.mpvPanscan, 0f)
        }
    }

    @Test
    fun `switching away from 21 by 9 restores every requested renderer mode`() {
        val twentyOneByNine = LivePictureFormat.fromKey("21_9").renderConfiguration

        LivePictureFormat.entries
            .filterNot { it == LivePictureFormat.RATIO_21_9 }
            .forEach { target ->
                val restored = LivePictureFormat.fromKey(target.key)
                assertEquals(target, restored)
                assertNotEquals(twentyOneByNine, restored.renderConfiguration)
            }
    }

    @Test
    fun `OSD key round trip reflects the authoritative current mode`() {
        LivePictureFormat.entries.forEach { selected ->
            assertEquals(selected, LivePictureFormat.fromKey(selected.key))
        }
        assertEquals(LivePictureFormat.SOURCE, LivePictureFormat.fromKey("original"))
        assertEquals(LivePictureFormat.SOURCE, LivePictureFormat.fromKey("unknown"))
    }

    @Test
    fun `mpv property mapping can reset 21 by 9 back to source and fill`() {
        val ratio = mpvPictureFormatProperties(21f / 9f, panscan = 0f)
        assertEquals((21f / 9f).toString(), ratio.aspectOverride)
        assertEquals("0.0", ratio.panscan)

        val source = mpvPictureFormatProperties(null, panscan = 0f)
        assertEquals("-1", source.aspectOverride)
        assertEquals("0.0", source.panscan)

        val fill = mpvPictureFormatProperties(null, panscan = 1f)
        assertEquals("-1", fill.aspectOverride)
        assertEquals("1.0", fill.panscan)
    }
}
