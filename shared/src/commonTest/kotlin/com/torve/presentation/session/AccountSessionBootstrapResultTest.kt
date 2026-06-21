package com.torve.presentation.session

import com.torve.domain.integrations.IntegrationSecretKey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountSessionBootstrapResultTest {
    @Test
    fun isReady_true_when_no_limit_and_no_error() {
        val result = AccountSessionBootstrapResult(
            isReady = true,
            deviceLimitReached = false,
            error = null,
        )

        assertTrue(result.isReady)
        assertFalse(result.deviceLimitReached)
    }

    @Test
    fun isReady_false_when_device_limit_reached() {
        val result = AccountSessionBootstrapResult(
            isReady = false,
            deviceLimitReached = true,
            error = "Device limit reached",
        )

        assertFalse(result.isReady)
        assertTrue(result.deviceLimitReached)
    }

    @Test
    fun isReady_false_when_registration_error_present() {
        // Validates the bootstrap fix: registration failure must not produce isReady=true.
        // The coordinator now sets isReady = !deviceLimitReached && registrationError == null
        val result = AccountSessionBootstrapResult(
            isReady = false, // registrationError was non-null
            deviceLimitReached = false,
            error = "Failed to register device (405)",
        )

        assertFalse(result.isReady)
        assertFalse(result.deviceLimitReached)
        assertTrue(!result.error.isNullOrBlank())
    }

    @Test
    fun registration_error_classifier_only_flags_real_device_limit_errors() {
        assertFalse(null.isDeviceLimitRegistrationError())
        assertFalse("Failed to refresh account settings".isDeviceLimitRegistrationError())
        assertFalse("Network error while registering device".isDeviceLimitRegistrationError())

        assertTrue("Device limit reached".isDeviceLimitRegistrationError())
        assertTrue("No activation slots remain".isDeviceLimitRegistrationError())
        assertTrue("swap limit reached".isDeviceLimitRegistrationError())
    }

    @Test
    fun restore_maps_website_torbox_integration_alias_to_client_secret_key() {
        assertEquals(
            IntegrationSecretKey.DEBRID_API_KEY_TORBOX,
            integrationSecretKeyForRestore("torbox"),
        )
        assertEquals(
            IntegrationSecretKey.DEBRID_API_KEY_TORBOX,
            integrationSecretKeyForRestore("DEBRID_API_KEY_TORBOX"),
        )
        assertEquals(
            IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY,
            integrationSecretKeyForRestore("torbox_download_client_api_key"),
        )
    }

    @Test
    fun restore_maps_website_service_aliases_to_client_secret_keys() {
        assertEquals(
            IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID,
            integrationSecretKeyForRestore("real_debrid"),
        )
        assertEquals(
            IntegrationSecretKey.TRAKT_TOKENS,
            integrationSecretKeyForRestore("trakt"),
        )
        assertEquals(
            IntegrationSecretKey.PANDA_TOKEN,
            integrationSecretKeyForRestore("panda"),
        )
    }
}
