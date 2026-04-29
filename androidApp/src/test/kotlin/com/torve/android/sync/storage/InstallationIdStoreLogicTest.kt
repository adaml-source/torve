package com.torve.android.sync.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationIdStoreLogicTest {
    @Test
    fun `legacy prefixed ANDROID_ID is migrated`() {
        assertTrue(isLegacyHardwareBoundInstallationId(existing = "a_abc123def", androidId = "abc123def"))
    }

    @Test
    fun `legacy raw ANDROID_ID is migrated`() {
        assertTrue(isLegacyHardwareBoundInstallationId(existing = "abc123def", androidId = "abc123def"))
    }

    @Test
    fun `uuid installation id is preserved`() {
        assertFalse(
            isLegacyHardwareBoundInstallationId(
                existing = "2d0e8c5a-3db7-4f73-8d91-b9dd97d5066d",
                androidId = "abc123def",
            ),
        )
    }

    @Test
    fun `new installation ids are random`() {
        val first = newInstallationId()
        val second = newInstallationId()

        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertNotEquals(first, second)
    }
}
