package com.torve.presentation.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceGovernanceUiStateTest {
    @Test
    fun accountLimitTwentyDrivesUsageTextAndWarningThreshold() {
        val state = DeviceGovernanceUiState(
            activeDeviceCount = 20,
            activeDeviceCountKnown = true,
            maxActiveDevices = 20,
            deviceLimitKnown = true,
        )

        assertEquals("20 / 20", state.deviceUsageText)
        assertTrue(state.isAtOrOverDeviceLimit)
        assertTrue(state.effectiveCapReached)
    }

    @Test
    fun accountLimitTwentyDoesNotWarnBelowThreshold() {
        val state = DeviceGovernanceUiState(
            activeDeviceCount = 19,
            activeDeviceCountKnown = true,
            maxActiveDevices = 20,
            deviceLimitKnown = true,
        )

        assertEquals("19 / 20", state.deviceUsageText)
        assertFalse(state.isAtOrOverDeviceLimit)
        assertFalse(state.effectiveCapReached)
    }
}
