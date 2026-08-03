package com.torve.android.tv.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMultiviewCapabilityTest {
    @Test
    fun supportedProfileAllowsSecondDecoder() {
        assertTrue(
            TvMultiviewCapability.isSupported(
                TvMultiviewDeviceProfile(apiLevel = 33, isLowRamDevice = false, memoryClassMb = 512),
            ),
        )
    }

    @Test
    fun lowRamAndLegacyProfilesAreRejected() {
        assertFalse(
            TvMultiviewCapability.isSupported(
                TvMultiviewDeviceProfile(apiLevel = 33, isLowRamDevice = true, memoryClassMb = 512),
            ),
        )
        assertFalse(
            TvMultiviewCapability.isSupported(
                TvMultiviewDeviceProfile(apiLevel = 25, isLowRamDevice = false, memoryClassMb = 512),
            ),
        )
        assertFalse(
            TvMultiviewCapability.isSupported(
                TvMultiviewDeviceProfile(apiLevel = 33, isLowRamDevice = false, memoryClassMb = 128),
            ),
        )
    }
}
