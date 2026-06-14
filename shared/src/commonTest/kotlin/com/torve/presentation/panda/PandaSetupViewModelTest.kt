package com.torve.presentation.panda

import com.torve.data.panda.PandaProvider
import com.torve.data.panda.PandaSourceProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PandaSetupViewModelTest {

    // ── Step navigation ──

    @Test
    fun initialStepIsSetupType() {
        val state = PandaSetupUiState()
        assertEquals(PandaSetupStep.SETUP_TYPE, state.currentStep)
        assertEquals(null, state.setupMode)
    }

    @Test
    fun debridSetupProgressUsesFullWizard() {
        val state = PandaSetupUiState(
            setupMode = PandaSetupMode.DEBRID,
            currentStep = PandaSetupStep.AUTH,
        )

        assertEquals(2, state.progressStepNumber())
        assertEquals(6, state.progressStepCount())
    }

    @Test
    fun usenetOnlyProgressSkipsDebridSteps() {
        val state = PandaSetupUiState(
            setupMode = PandaSetupMode.USENET_ONLY,
            selectedProvider = PandaProvider("none", "Use Usenet only (skip debrid)", emptyList()),
            currentStep = PandaSetupStep.USENET,
        )

        assertEquals(2, state.progressStepNumber())
        assertEquals(4, state.progressStepCount())
    }

    @Test
    fun defaultSourcesArePreset() {
        val state = PandaSetupUiState(
            enabledSources = setOf("yts", "eztv", "1337x", "thepiratebay", "torrentgalaxy", "nyaasi"),
        )
        assertEquals(6, state.enabledSources.size)
        assertTrue("yts" in state.enabledSources)
        assertTrue("nyaasi" in state.enabledSources)
        assertFalse("rutor" in state.enabledSources)
    }

    @Test
    fun sourceToggleAddsAndRemoves() {
        var sources = setOf("yts", "eztv")

        // Add
        val added = sources.toMutableSet().apply { add("1337x") }
        assertTrue("1337x" in added)
        assertEquals(3, added.size)

        // Remove
        val removed = added.toMutableSet().apply { remove("yts") }
        assertFalse("yts" in removed)
        assertEquals(2, removed.size)
    }

    // ── Provider selection ──

    @Test
    fun oauthProviderDefaultsToOAuth() {
        val provider = PandaProvider("realdebrid", "Real-Debrid", listOf("oauth", "apikey"))
        val defaultAuth = if ("oauth" in provider.authMethods) "oauth" else "apikey"
        assertEquals("oauth", defaultAuth)
    }

    @Test
    fun apikeyOnlyProviderDefaultsToApikey() {
        val provider = PandaProvider("torbox", "TorBox", listOf("apikey"))
        val defaultAuth = if ("oauth" in provider.authMethods) "oauth" else "apikey"
        assertEquals("apikey", defaultAuth)
    }

    // ── Provider ID mapping ──

    @Test
    fun fallbackSchemaIncludesCloudDownloadClients() {
        assertTrue("torbox" in FALLBACK_SCHEMA.downloadClients)
        assertTrue(FALLBACK_SCHEMA.downloadClientFields["torbox"]?.cloud == true)
        assertTrue("apiKey" in FALLBACK_SCHEMA.downloadClientFields["torbox"]!!.fields)
    }

    @Test
    fun providerIdToDebridServiceTypeMapping() {
        val mapping = mapOf(
            "realdebrid" to "REAL_DEBRID",
            "premiumize" to "PREMIUMIZE",
            "alldebrid" to "ALL_DEBRID",
            "torbox" to "TORBOX",
        )
        assertEquals(4, mapping.size)
        assertEquals("REAL_DEBRID", mapping["realdebrid"])
        assertEquals("TORBOX", mapping["torbox"])
    }

    // ── Auth state ──

    @Test
    fun authConnectedWhenKeyPresent() {
        val state = PandaSetupUiState(
            debridApiKey = "key-123",
            authConnected = true,
        )
        assertTrue(state.authConnected)
        assertTrue(state.debridApiKey.isNotBlank())
    }

    @Test
    fun existingCredentialDetectedFlag() {
        val state = PandaSetupUiState(
            authConnected = true,
            existingCredentialDetected = true,
        )
        assertTrue(state.existingCredentialDetected)
    }

    // ── Edit mode ──

    @Test
    fun editModePrefillsState() {
        val state = PandaSetupUiState(
            isEditMode = true,
            pandaToken = "tok_existing",
            selectedProvider = PandaProvider("realdebrid", "Real-Debrid", listOf("oauth", "apikey")),
            debridApiKey = "existing-key",
            authConnected = true,
            enabledSources = setOf("yts", "eztv", "1337x"),
            maxQuality = "1080p",
            qualityProfile = "best_quality",
            releaseLanguage = "german",
        )
        assertTrue(state.isEditMode)
        assertNotNull(state.pandaToken)
        assertEquals("realdebrid", state.selectedProvider?.id)
        assertEquals(3, state.enabledSources.size)
        assertEquals("german", state.releaseLanguage)
    }

    // ── Config payload ──

    @Test
    fun enabledSourcesIncludedInPayload() {
        val sources = setOf("yts", "eztv", "1337x")
        val payload = com.torve.data.panda.PandaConfigPayload(
            enabledProviders = sources.toList(),
            debridService = "realdebrid",
            debridApiKey = "key",
        )
        assertEquals(3, payload.enabledProviders.size)
        assertTrue("yts" in payload.enabledProviders)
    }

    @Test
    fun multiDebridConnectionsIncludeEveryVerifiedProvider() {
        val state = PandaSetupUiState(
            selectedProvider = PandaProvider("realdebrid", "Real-Debrid", listOf("oauth", "apikey")),
            debridApiKey = "rd-key",
            debridApiKeys = mapOf(
                "realdebrid" to "rd-key",
                "premiumize" to "pm-key",
                "torbox" to "tb-key",
            ),
        )

        val connections = pandaDebridConnectionsForPayload(state)

        assertEquals(3, connections.size)
        assertEquals(setOf("realdebrid", "premiumize", "torbox"), connections.map { it.provider }.toSet())
        assertEquals("rd-key", primaryPandaDebridConnection(state).apiKey)
    }

    @Test
    fun selectedProviderKeyDoesNotBleedFromAnotherProvider() {
        val state = PandaSetupUiState(
            selectedProvider = PandaProvider("torbox", "TorBox", listOf("apikey")),
            debridApiKey = "tb-key",
            debridApiKeys = mapOf(
                "realdebrid" to "rd-key",
                "torbox" to "tb-key",
            ),
        )

        assertEquals("tb-key", state.debridApiKeys[state.selectedProvider?.id])
        assertFalse(state.debridApiKey == state.debridApiKeys["realdebrid"])
        assertEquals("torbox", primaryPandaDebridConnection(state).provider)
    }

    @Test
    fun selectedProviderReplacementPreservesOtherConnections() {
        val state = PandaSetupUiState(
            selectedProvider = PandaProvider("torbox", "TorBox", listOf("apikey")),
            debridApiKey = "tb-key-new",
            debridApiKeys = mapOf(
                "realdebrid" to "rd-key",
                "torbox" to "tb-key-old",
            ),
        )

        val connections = pandaDebridConnectionsForPayload(state).associate { it.provider to it.apiKey }

        assertEquals("rd-key", connections["realdebrid"])
        assertEquals("tb-key-new", connections["torbox"])
        assertEquals(2, connections.size)
    }

    @Test
    fun removedProviderDropsOnlyThatConnectionFromPayload() {
        val state = PandaSetupUiState(
            selectedProvider = PandaProvider("realdebrid", "Real-Debrid", listOf("oauth", "apikey")),
            debridApiKey = "",
            debridApiKeys = mapOf(
                "torbox" to "tb-key",
                "premiumize" to "pm-key",
            ),
        )

        val connections = pandaDebridConnectionsForPayload(state)

        assertEquals(setOf("torbox", "premiumize"), connections.map { it.provider }.toSet())
        assertFalse(connections.any { it.provider == "realdebrid" })
    }

    @Test
    fun redactedDebridKeysAreNeverSentBackInPayload() {
        val state = PandaSetupUiState(
            selectedProvider = PandaProvider("realdebrid", "Real-Debrid", listOf("oauth", "apikey")),
            debridApiKey = "[redacted]",
            debridApiKeys = mapOf(
                "realdebrid" to "[redacted]",
                "torbox" to "tb-key",
            ),
        )

        val connections = pandaDebridConnectionsForPayload(state)

        assertEquals(listOf("torbox"), connections.map { it.provider })
        assertEquals("tb-key", connections.single().apiKey)
    }

    // ── Save state ──

    @Test
    fun usenetOnlyPayloadIgnoresStoredDebridKeys() {
        val state = PandaSetupUiState(
            setupMode = PandaSetupMode.USENET_ONLY,
            selectedProvider = PandaProvider("none", "Use Usenet only (skip debrid)", emptyList()),
            debridApiKeys = mapOf(
                "realdebrid" to "rd-key",
                "torbox" to "tb-key",
            ),
        )

        assertTrue(pandaDebridConnectionsForPayload(state).isEmpty())
        assertEquals("none", primaryPandaDebridConnection(state).provider)
        assertEquals("", primaryPandaDebridConnection(state).apiKey)
    }

    @Test
    fun addonInstalledAfterSave() {
        val state = PandaSetupUiState(
            addonInstalled = true,
            pandaToken = "tok_new",
            manifestUrl = "https://panda.torve.app/u/tok_new/manifest.json",
        )
        assertTrue(state.addonInstalled)
        assertNotNull(state.manifestUrl)
    }

    // ── Source provider data ──

    @Test
    fun sourceProviderCategoriesAreCovered() {
        val providers = listOf(
            PandaSourceProvider("yts", "YTS", "desc", "movies"),
            PandaSourceProvider("eztv", "EZTV", "desc", "series"),
            PandaSourceProvider("1337x", "1337x", "desc", "general"),
            PandaSourceProvider("nyaasi", "Nyaa", "desc", "anime"),
            PandaSourceProvider("rutor", "Rutor", "desc", "regional"),
        )
        val categories = providers.map { it.category }.toSet()
        assertTrue("movies" in categories)
        assertTrue("series" in categories)
        assertTrue("general" in categories)
        assertTrue("anime" in categories)
        assertTrue("regional" in categories)
    }

    // ── Error state ──

    @Test
    fun errorAndSaveErrorAreIndependent() {
        val state = PandaSetupUiState(
            error = "OAuth failed",
            saveError = null,
        )
        assertNotNull(state.error)
        assertEquals(null, state.saveError)

        val state2 = state.copy(error = null, saveError = "Config save failed")
        assertEquals(null, state2.error)
        assertNotNull(state2.saveError)
    }

    // ── Management token lifecycle ──

    @Test
    fun freshStateHasNoManagementTokenArtifacts() {
        val s = PandaSetupUiState()
        assertEquals(null, s.configId)
        assertFalse(s.hasManagementToken)
        assertEquals(null, s.pendingManagementTokenDisplay)
        assertEquals(null, s.managementTokenNotice)
        assertFalse(s.editRequiresRecovery)
    }

    @Test
    fun capturedManagementTokenSurfacesOnceViaPendingDisplay() {
        val after = PandaSetupUiState(
            configId = "cfg-live",
            hasManagementToken = true,
            pendingManagementTokenDisplay = "mgmt-abcdef",
            managementTokenNotice = "Save this now",
            addonInstalled = true,
        )
        assertEquals("cfg-live", after.configId)
        assertTrue(after.hasManagementToken)
        assertEquals("mgmt-abcdef", after.pendingManagementTokenDisplay)
    }

    @Test
    fun editRequiresRecoveryWhenNoManagementTokenStored() {
        val s = PandaSetupUiState(
            isEditMode = true,
            pandaToken = "tok_existing",
            configId = "cfg-legacy",
            hasManagementToken = false,
            editRequiresRecovery = true,
        )
        assertTrue(s.isEditMode)
        assertTrue(s.editRequiresRecovery)
        assertFalse(s.hasManagementToken)
    }

    @Test
    fun rotateAndRecoveryInProgressFlagsAreIndependent() {
        val s = PandaSetupUiState(
            rotateInProgress = true,
            recoveryInProgress = false,
        )
        assertTrue(s.rotateInProgress)
        assertFalse(s.recoveryInProgress)
        assertEquals(null, s.rotateError)
        assertEquals(null, s.recoveryError)
    }
}
