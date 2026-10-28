package com.torve.presentation.apple

import com.torve.data.account.AccountSettingsSyncPolicy
import com.torve.domain.providerhealth.*
import kotlin.test.*

class AppleConnectionAlertPreferencesTest {
    private fun entry(category: ProviderHealthCategory, status: ProviderHealthStatus) =
        ProviderHealthEntry(category, "test", "Test connection", status)

    @Test fun optionalUnconfiguredAndPendingConnectionsNeverAlert() {
        val prefs = AppleConnectionAlertPreferences()
        for (category in ProviderHealthCategory.entries) {
            for (status in listOf(ProviderHealthStatus.UNCONFIGURED, ProviderHealthStatus.GREEN, ProviderHealthStatus.UNKNOWN)) {
                assertFalse(prefs.shouldAlert(entry(category, status)))
            }
        }
    }
    @Test fun mutedCategoriesStayQuietWhileUsedConnectionsCanWarn() {
        val prefs = decodeConnectionAlertPreferences("true", """["USENET_INDEXER","IPTV","invalid"]""")
        assertEquals(listOf("USENET_INDEXER", "IPTV"), prefs.mutedCategories)
        assertFalse(prefs.shouldAlert(entry(ProviderHealthCategory.USENET_INDEXER, ProviderHealthStatus.RED)))
        assertFalse(prefs.shouldAlert(entry(ProviderHealthCategory.IPTV, ProviderHealthStatus.YELLOW)))
        assertTrue(prefs.shouldAlert(entry(ProviderHealthCategory.TRAKT, ProviderHealthStatus.RED)))
        assertFalse(decodeConnectionAlertPreferences("false", null).shouldAlert(entry(ProviderHealthCategory.TRAKT, ProviderHealthStatus.RED)))
    }
    @Test fun preferencesAreSafeSharedKeysAndSupportProfileNamespaces() {
        for (key in listOf(KEY_CONNECTION_ALERTS_ENABLED, KEY_CONNECTION_ALERT_MUTED_CATEGORIES)) {
            assertTrue(AccountSettingsSyncPolicy.isSharedKey(key))
            assertTrue(AccountSettingsSyncPolicy.isSharedKey("profilepref_v1:child:$key"))
        }
        assertTrue(decodeConnectionAlertPreferences(null, "broken").mutedCategories.isEmpty())
    }
}
