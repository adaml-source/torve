package com.torve.desktop.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class VlcUiLifecycleTest {

    @Test
    fun `engine creates fresh ui hosts`() {
        val engine = VlcDesktopPlaybackEngine()

        val chromeHostOne = engine.obtainChromeHost()
        val chromeHostTwo = engine.obtainChromeHost()
        val surfaceHostOne = engine.obtainSurfaceHost(VlcSurfaceCallbacks(), chromeHostOne)
        val surfaceHostTwo = engine.obtainSurfaceHost(VlcSurfaceCallbacks(), chromeHostTwo)

        try {
            assertNotSame(chromeHostOne, chromeHostTwo)
            assertNotSame(surfaceHostOne, surfaceHostTwo)
        } finally {
            surfaceHostOne.dispose()
            surfaceHostTwo.dispose()
            chromeHostOne.dispose()
            chromeHostTwo.dispose()
            engine.dispose()
        }
    }

    @Test
    fun `surface dispose removes installed listeners`() {
        val engine = VlcDesktopPlaybackEngine()
        val chromeHost = engine.obtainChromeHost()
        val surfaceHost = engine.obtainSurfaceHost(VlcSurfaceCallbacks(), chromeHost)

        try {
            // Listeners are installed on the host panel itself
            assertEquals(1, surfaceHost.componentListeners.size)
            assertEquals(1, surfaceHost.mouseListeners.size)
            assertEquals(1, surfaceHost.mouseMotionListeners.size)
            assertEquals(1, surfaceHost.keyListeners.size)

            surfaceHost.dispose()

            assertEquals(0, surfaceHost.componentListeners.size)
            assertEquals(0, surfaceHost.mouseListeners.size)
            assertEquals(0, surfaceHost.mouseMotionListeners.size)
            assertEquals(0, surfaceHost.keyListeners.size)
        } finally {
            chromeHost.dispose()
            engine.dispose()
        }
    }
}
