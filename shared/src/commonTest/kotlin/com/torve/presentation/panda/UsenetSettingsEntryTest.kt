package com.torve.presentation.panda

import com.torve.data.panda.NzbIndexerRow
import com.torve.data.panda.PandaProvider
import kotlin.test.*

class UsenetSettingsEntryTest {
    private val none = PandaProvider("none", "Usenet", emptyList())
    @Test fun directEntryPreservesExistingProvidersSourcesAndIndexerKeys() {
        val provider = PandaProvider("torbox", "TorBox", listOf("apikey"))
        val original = PandaSetupUiState(setupMode = PandaSetupMode.DEBRID, selectedProvider = provider,
            currentStep = PandaSetupStep.REVIEW, debridApiKeys = mapOf("torbox" to "fixture-key"),
            enabledSources = setOf("torrentio"), enableUsenet = true, usenetHost = "news.fixture.invalid",
            nzbIndexers = listOf(NzbIndexerRow("scenenzbs", "", "fixture-indexer-key")))
        val opened = original.reopenedForUsenet(none)
        assertEquals(PandaSetupStep.USENET, opened.currentStep)
        assertEquals(provider, opened.selectedProvider)
        assertEquals(original.debridApiKeys, opened.debridApiKeys)
        assertEquals(original.enabledSources, opened.enabledSources)
        assertEquals(original.nzbIndexers, opened.nzbIndexers)
        assertEquals(original.usenetHost, opened.usenetHost)
        assertEquals(PandaSetupMode.DEBRID, opened.setupMode)
    }
    @Test fun firstSetupCanReachUsenetWithoutDebridAuthentication() {
        val opened = PandaSetupUiState().reopenedForUsenet(none)
        assertEquals(PandaSetupStep.USENET, opened.currentStep)
        assertEquals(PandaSetupMode.USENET_ONLY, opened.setupMode)
        assertTrue(opened.authConnected)
        assertEquals(none, opened.selectedProvider)
    }
}
