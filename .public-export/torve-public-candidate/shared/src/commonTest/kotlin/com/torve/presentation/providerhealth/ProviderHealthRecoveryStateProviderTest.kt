package com.torve.presentation.providerhealth

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.transfer.SecretCategory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderHealthRecoveryStateProviderTest {

    @Test
    fun freshDeviceWithEmptyStoreShowsRecoveryCard() = runTest {
        val provider = ProviderHealthRecoveryStateProvider(secretStore = RecoveryFakeStore())
        val snap = provider.snapshot()
        assertTrue(snap.shouldShowRecoveryCard)
        // Every catalog category with non-empty key list counts as missing.
        assertTrue(snap.missingTransferableCategoryCount >= 4)
        assertTrue(SecretCategory.DEBRID in snap.missingCategories)
        assertTrue(SecretCategory.PLEX_JELLYFIN in snap.missingCategories)
    }

    @Test
    fun fullySetUpDeviceDoesNotShowCard() = runTest {
        val store = RecoveryFakeStore().apply {
            seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "rd")
            seed(IntegrationSecretKey.PLEX_ACCESS_TOKEN, "plex")
            seed(IntegrationSecretKey.TRAKT_ACCESS_TOKEN, "trakt")
            seed(IntegrationSecretKey.CHATGPT_API_KEY, "ai")
            seed(IntegrationSecretKey.PANDA_TOKEN, "panda")
        }
        val provider = ProviderHealthRecoveryStateProvider(secretStore = store)
        val snap = provider.snapshot()
        assertFalse(snap.shouldShowRecoveryCard)
        assertEquals(0, snap.missingTransferableCategoryCount)
    }

    @Test
    fun partialSetupDoesNotShowCardEvenWhenManyCategoriesAreMissing() = runTest {
        val store = RecoveryFakeStore().apply {
            seed(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, "simkl")
        }
        val provider = ProviderHealthRecoveryStateProvider(secretStore = store)
        val snap = provider.snapshot()
        assertFalse(snap.shouldShowRecoveryCard, "partial setup should not trigger; got $snap")
        assertTrue(snap.missingTransferableCategoryCount >= 2)
    }

    @Test
    fun greenProviderHealthRowsSuppressRecoveryCardEvenWhenSecretStoreIsEmpty() = runTest {
        val provider = ProviderHealthRecoveryStateProvider(secretStore = RecoveryFakeStore())
        val snap = provider.snapshot(
            healthEntries = listOf(
                entry(ProviderHealthCategory.SIMKL, ProviderHealthStatus.GREEN),
            ),
        )

        assertFalse(
            snap.shouldShowRecoveryCard,
            "a green provider row means this device is already partly configured; got $snap",
        )
    }

    @Test
    fun singleMissingCategoryDoesNotTriggerCard() = runTest {
        // Only Trakt missing — that's normal partial setup, not the
        // "fresh device" signal. Threshold is 2.
        val store = RecoveryFakeStore().apply {
            seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "rd")
            seed(IntegrationSecretKey.PLEX_ACCESS_TOKEN, "plex")
            seed(IntegrationSecretKey.CHATGPT_API_KEY, "ai")
            seed(IntegrationSecretKey.PANDA_TOKEN, "panda")
        }
        val provider = ProviderHealthRecoveryStateProvider(secretStore = store)
        val snap = provider.snapshot()
        assertFalse(snap.shouldShowRecoveryCard, "1 missing category should not trigger; got $snap")
        assertEquals(1, snap.missingTransferableCategoryCount)
        assertEquals(listOf(SecretCategory.TRAKT_SIMKL), snap.missingCategories)
    }

    @Test
    fun providerHealthUnconfiguredRowsCountAsMissingEvenIfStoreLooksOk() = runTest {
        // Defense in depth: if the store reports a value but the
        // ProviderHealth coordinator says UNCONFIGURED for a transferable
        // category, treat as missing.
        val store = RecoveryFakeStore().apply {
            seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "rd-stale-but-present")
            seed(IntegrationSecretKey.PLEX_ACCESS_TOKEN, "plex")
            seed(IntegrationSecretKey.TRAKT_ACCESS_TOKEN, "trakt")
            seed(IntegrationSecretKey.CHATGPT_API_KEY, "ai")
            seed(IntegrationSecretKey.PANDA_TOKEN, "panda")
        }
        val provider = ProviderHealthRecoveryStateProvider(secretStore = store)

        val healthEntries = listOf(
            entry(ProviderHealthCategory.DEBRID, ProviderHealthStatus.UNCONFIGURED),
            entry(ProviderHealthCategory.SIMKL, ProviderHealthStatus.UNCONFIGURED),
        )
        val snap = provider.snapshot(healthEntries = healthEntries)
        // DEBRID forced missing by health-row signal even though store had a value.
        assertFalse(snap.shouldShowRecoveryCard)
        assertTrue(SecretCategory.DEBRID in snap.missingCategories)
    }

    @Test
    fun nonTransferableHealthRowsAreIgnored() = runTest {
        // ADDON, EPG, IPTV, PLAYBACK aren't in the transfer catalog —
        // their UNCONFIGURED rows must not count toward the recovery
        // signal.
        val store = RecoveryFakeStore().apply {
            seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "rd")
            seed(IntegrationSecretKey.PLEX_ACCESS_TOKEN, "plex")
            seed(IntegrationSecretKey.TRAKT_ACCESS_TOKEN, "trakt")
            seed(IntegrationSecretKey.CHATGPT_API_KEY, "ai")
            seed(IntegrationSecretKey.PANDA_TOKEN, "panda")
        }
        val provider = ProviderHealthRecoveryStateProvider(secretStore = store)
        val healthEntries = listOf(
            entry(ProviderHealthCategory.ADDON, ProviderHealthStatus.UNCONFIGURED),
            entry(ProviderHealthCategory.EPG, ProviderHealthStatus.UNCONFIGURED),
            entry(ProviderHealthCategory.IPTV, ProviderHealthStatus.UNCONFIGURED),
            entry(ProviderHealthCategory.PLAYBACK, ProviderHealthStatus.UNCONFIGURED),
        )
        val snap = provider.snapshot(healthEntries = healthEntries)
        assertFalse(snap.shouldShowRecoveryCard)
        assertEquals(0, snap.missingTransferableCategoryCount)
    }

    private fun entry(
        category: ProviderHealthCategory,
        status: ProviderHealthStatus,
    ): ProviderHealthEntry = ProviderHealthEntry(
        category = category,
        providerKey = "rec-test:${category.name.lowercase()}",
        label = category.name,
        status = status,
    )
}

/**
 * Local fake — duplicated rather than reused so test-class private
 * collisions don't bite (other tests in the package declare similar
 * fakes file-privately).
 */
private class RecoveryFakeStore : IntegrationSecretStore {
    private val backing = mutableMapOf<String, String>()
    private fun addr(key: IntegrationSecretKey, subKey: String?) =
        "${key.name}|${subKey.orEmpty()}"

    fun seed(key: IntegrationSecretKey, value: String) {
        backing[addr(key, null)] = value
    }

    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {
        backing[addr(key, subKey)] = value
    }

    override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
        backing[addr(key, subKey)]

    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {
        backing.remove(addr(key, subKey))
    }

    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
    override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode =
        IntegrationStorageMode.DEVICE_ONLY
    override suspend fun clearAllSecrets() { backing.clear() }
}
