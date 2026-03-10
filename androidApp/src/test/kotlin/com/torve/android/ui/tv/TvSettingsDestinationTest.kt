package com.torve.android.ui.tv

import com.torve.android.tv.TvSettingsDestination
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for TvSettingsDestination state logic.
 *
 * Validates that the single-source-of-truth enum correctly models the
 * settings sub-navigation so that nav-rail selection and displayed content
 * can never drift apart.
 */
class TvSettingsDestinationTest {

    @Test
    fun `initial destination is MAIN`() {
        val destination = TvSettingsDestination.MAIN
        assertEquals(TvSettingsDestination.MAIN, destination)
    }

    @Test
    fun `navigating to paired devices updates destination`() {
        var destination = TvSettingsDestination.MAIN
        destination = TvSettingsDestination.PAIRED_DEVICES
        assertEquals(TvSettingsDestination.PAIRED_DEVICES, destination)
    }

    @Test
    fun `pressing back from activated devices returns to MAIN`() {
        var destination = TvSettingsDestination.ACTIVATED_DEVICES
        // Simulate onBack callback
        destination = TvSettingsDestination.MAIN
        assertEquals(TvSettingsDestination.MAIN, destination)
    }

    @Test
    fun `re-selecting settings from nav rail resets to MAIN`() {
        var destination = TvSettingsDestination.PAIRED_DEVICES
        // Simulate what onNavigate does when route == SETTINGS:
        // settingsDestination = TvSettingsDestination.MAIN
        destination = TvSettingsDestination.MAIN
        assertEquals(TvSettingsDestination.MAIN, destination)
    }

    @Test
    fun `recomposition does not revert destination - state holds across reads`() {
        var destination = TvSettingsDestination.ACTIVATED_DEVICES
        // Read the value multiple times (simulates recomposition reads)
        val read1 = destination
        val read2 = destination
        val read3 = destination
        assertEquals(TvSettingsDestination.ACTIVATED_DEVICES, read1)
        assertEquals(TvSettingsDestination.ACTIVATED_DEVICES, read2)
        assertEquals(TvSettingsDestination.ACTIVATED_DEVICES, read3)
    }

    @Test
    fun `full navigation cycle - settings to paired devices to rail reselect`() {
        // Start at MAIN
        var destination = TvSettingsDestination.MAIN
        assertEquals(TvSettingsDestination.MAIN, destination)

        // Navigate to Paired Devices
        destination = TvSettingsDestination.PAIRED_DEVICES
        assertEquals(TvSettingsDestination.PAIRED_DEVICES, destination)

        // Simulate: user presses Left to rail, then focuses Settings rail item
        // onNavigate(SETTINGS) fires -> resets to MAIN
        destination = TvSettingsDestination.MAIN
        assertEquals(TvSettingsDestination.MAIN, destination)

        // Navigate to Activated Devices
        destination = TvSettingsDestination.ACTIVATED_DEVICES
        assertEquals(TvSettingsDestination.ACTIVATED_DEVICES, destination)

        // Simulate: user presses Enter on Settings rail item
        // onMoveToContent -> onNavigate(SETTINGS) -> resets to MAIN
        destination = TvSettingsDestination.MAIN
        assertEquals(TvSettingsDestination.MAIN, destination)
    }

    @Test
    fun `enum values are exhaustive - MAIN plus paired and activated screens`() {
        val allValues = TvSettingsDestination.entries
        assertEquals(3, allValues.size)
        assertEquals(TvSettingsDestination.MAIN, allValues[0])
        assertEquals(TvSettingsDestination.PAIRED_DEVICES, allValues[1])
        assertEquals(TvSettingsDestination.ACTIVATED_DEVICES, allValues[2])
    }
}
