package com.torve.presentation.panda

import com.torve.data.panda.NzbIndexerRow
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.providerhealth.PandaDownloadClientProviderHealthChecker
import com.torve.presentation.providerhealth.PandaUsenetProviderHealthChecker
import com.torve.presentation.providerhealth.PandaUsenetProviderProviderHealthChecker
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins:
 *   1. The default store snapshot is the unconfigured-flavored
 *      [PandaSetupUiState] — every Panda checker treats it as
 *      UNCONFIGURED with no special-casing.
 *   2. `publish()` advances the snapshot.
 *   3. The three Panda provider-health checkers (the same instances
 *      registered in `DesktopProviderHealthInit` and
 *      `AndroidProviderHealthInit`) walk the closed status enum:
 *      UNCONFIGURED → GREEN → YELLOW → RED based on store snapshots.
 *      This is the regression guard for the "factory VM mis-reports
 *      state" bug the store was introduced to fix.
 */
class PandaConfigStateStoreTest {

    @Test
    fun defaultSnapshotIsUnconfiguredFlavoredAndCheckersReportUnconfigured() = runTest {
        val store = PandaConfigStateStore()
        // Default state: isEditMode=false, no indexers configured, etc.
        // Every Panda checker short-circuits to UNCONFIGURED.
        assertEquals(false, store.current.isEditMode)
        assertEquals(false, store.current.enableUsenet)
        assertEquals("none", store.current.usenetProvider)
        assertEquals("none", store.current.downloadClient)

        val indexer = PandaUsenetProviderHealthChecker(stateSource = { store.current }).check()
        assertEquals(ProviderHealthCategory.USENET_INDEXER, indexer.category)
        assertEquals(ProviderHealthStatus.UNCONFIGURED, indexer.status)

        val provider = PandaUsenetProviderProviderHealthChecker(stateSource = { store.current }).check()
        assertEquals(ProviderHealthCategory.USENET_PROVIDER, provider.category)
        assertEquals(ProviderHealthStatus.UNCONFIGURED, provider.status)

        val downloadClient = PandaDownloadClientProviderHealthChecker(stateSource = { store.current }).check()
        assertEquals(ProviderHealthCategory.DOWNLOAD_CLIENT, downloadClient.category)
        assertEquals(ProviderHealthStatus.UNCONFIGURED, downloadClient.status)
    }

    @Test
    fun publishUpdatesCurrentAndStateFlow() = runTest {
        val store = PandaConfigStateStore()
        val initial = store.current
        store.publish(
            PandaSetupUiState(
                isEditMode = true,
                enableUsenet = true,
                usenetProvider = "newshosting",
                usenetPassword = "pw-real",
            ),
        )
        assertNotEquals(initial.isEditMode, store.current.isEditMode)
        assertEquals(true, store.current.enableUsenet)
        assertEquals("newshosting", store.current.usenetProvider)
        // StateFlow value matches.
        assertEquals(store.current, store.state.value)
    }

    @Test
    fun fullyConfiguredStateProducesGreenRowsAcrossAllThreeCheckers() = runTest {
        val store = PandaConfigStateStore()
        store.publish(
            PandaSetupUiState(
                isEditMode = true,
                nzbIndexers = listOf(
                    NzbIndexerRow(type = "nzbgeek", url = "https://nzbgeek.info", apiKey = "key1"),
                    NzbIndexerRow(type = "scenenzbs", url = "https://scenenzbs.com", apiKey = "key2"),
                ),
                enableUsenet = true,
                usenetProvider = "newshosting",
                usenetPassword = "pw",
                downloadClient = "torbox",
                downloadClientApiKey = "torbox-api-key",
            ),
        )

        val indexer = PandaUsenetProviderHealthChecker(stateSource = { store.current }).check()
        assertEquals(ProviderHealthStatus.GREEN, indexer.status)
        assertTrue(indexer.message?.contains("nzbgeek", ignoreCase = true) == true)

        val provider = PandaUsenetProviderProviderHealthChecker(stateSource = { store.current }).check()
        assertEquals(ProviderHealthStatus.GREEN, provider.status)

        val downloadClient = PandaDownloadClientProviderHealthChecker(stateSource = { store.current }).check()
        assertEquals(ProviderHealthStatus.GREEN, downloadClient.status)
    }

    @Test
    fun missingIndexerKeysProduceRed() = runTest {
        val store = PandaConfigStateStore()
        store.publish(
            PandaSetupUiState(
                isEditMode = true,
                nzbIndexers = listOf(
                    // Configured indexer but key blank — RED.
                    NzbIndexerRow(type = "nzbgeek", url = "https://nzbgeek.info", apiKey = ""),
                ),
            ),
        )
        val indexer = PandaUsenetProviderHealthChecker(stateSource = { store.current }).check()
        assertEquals(ProviderHealthStatus.RED, indexer.status)
    }

    @Test
    fun partiallyConfiguredIndexersProduceYellow() = runTest {
        val store = PandaConfigStateStore()
        store.publish(
            PandaSetupUiState(
                isEditMode = true,
                nzbIndexers = listOf(
                    NzbIndexerRow(type = "nzbgeek", url = "https://nzbgeek.info", apiKey = "good"),
                    NzbIndexerRow(type = "scenenzbs", url = "https://scenenzbs.com", apiKey = ""),
                ),
            ),
        )
        val indexer = PandaUsenetProviderHealthChecker(stateSource = { store.current }).check()
        assertEquals(ProviderHealthStatus.YELLOW, indexer.status)
        assertTrue(indexer.message?.contains("missing") == true)
    }

    @Test
    fun usenetProviderWithBlankPasswordProducesRed() = runTest {
        val store = PandaConfigStateStore()
        store.publish(
            PandaSetupUiState(
                isEditMode = true,
                enableUsenet = true,
                usenetProvider = "newshosting",
                usenetPassword = "",
            ),
        )
        val provider = PandaUsenetProviderProviderHealthChecker(stateSource = { store.current }).check()
        assertEquals(ProviderHealthStatus.RED, provider.status)
    }

    @Test
    fun redactedSecretsAreTreatedAsMissing() = runTest {
        // The wizard sometimes seeds redacted placeholder strings when
        // hydrating a Panda config. The checker treats those as missing
        // so we don't claim GREEN on placeholder data.
        val store = PandaConfigStateStore()
        store.publish(
            PandaSetupUiState(
                isEditMode = true,
                enableUsenet = true,
                usenetProvider = "newshosting",
                usenetPassword = "**REDACTED**",
            ),
        )
        val provider = PandaUsenetProviderProviderHealthChecker(stateSource = { store.current }).check()
        assertEquals(ProviderHealthStatus.RED, provider.status)
    }
}
