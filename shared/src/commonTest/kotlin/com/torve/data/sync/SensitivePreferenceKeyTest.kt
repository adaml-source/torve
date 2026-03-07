package com.torve.data.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitivePreferenceKeyTest {

    @Test
    fun detectsSensitiveKeys() {
        assertTrue(isSensitivePreferenceKey("trakt_access_token"))
        assertTrue(isSensitivePreferenceKey("my_api_key"))
        assertTrue(isSensitivePreferenceKey("service_secret"))
    }

    @Test
    fun keepsCustomizationKeysExportable() {
        assertFalse(isSensitivePreferenceKey("content_region_code"))
        assertFalse(isSensitivePreferenceKey("card_style_presets"))
        assertFalse(isSensitivePreferenceKey("home_section_configs"))
    }
}

