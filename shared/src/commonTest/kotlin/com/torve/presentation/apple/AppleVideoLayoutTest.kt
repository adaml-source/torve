package com.torve.presentation.apple

import kotlin.test.*

class AppleVideoLayoutTest {
    @Test fun widescreenPortraitKeepsBothPictureEdgesInsideThePhoneViewport() {
        val rect = AppleVideoLayout.fit(390.0, 844.0, 16.0 / 9)
        assertEquals(390.0, rect.width, 0.001)
        assertEquals(219.375, rect.height, 0.001)
        assertEquals(0.0, rect.x, 0.001)
        assertEquals(312.3125, rect.y, 0.001)
    }

    @Test fun rotatingToLandscapeFitsTheSamePictureWithoutChangingItsAspect() {
        val rect = AppleVideoLayout.fit(844.0, 390.0, 16.0 / 9)
        assertEquals(390.0, rect.height, 0.001)
        assertEquals(16.0 / 9, rect.width / rect.height, 0.001)
        assertTrue(rect.x > 0)
        assertEquals(844.0, rect.x * 2 + rect.width, 0.001)
    }

    @Test fun everyOverrideFitsWithinPortraitLandscapeAndTelevisionBounds() {
        for ((width, height) in listOf(390.0 to 844.0, 844.0 to 390.0, 1920.0 to 1080.0)) {
            for (ratio in listOf(4.0 / 3, 16.0 / 9, 21.0 / 9)) {
                val rect = AppleVideoLayout.fit(width, height, ratio)
                assertTrue(rect.x >= 0 && rect.y >= 0)
                assertTrue(rect.width <= width + 0.001 && rect.height <= height + 0.001)
                assertEquals(ratio, rect.width / rect.height, 0.001)
            }
        }
    }

    @Test fun OriginalAndInvalidSizesCannotCreateOffscreenOrNonfiniteRendererFrames() {
        assertEquals(AppleVideoRect(0.0, 0.0, 390.0, 844.0), AppleVideoLayout.fit(390.0, 844.0, 0.0))
        assertEquals(AppleVideoRect(0.0, 0.0, 0.0, 0.0), AppleVideoLayout.fit(Double.NaN, -1.0, 16.0 / 9))
    }
}
