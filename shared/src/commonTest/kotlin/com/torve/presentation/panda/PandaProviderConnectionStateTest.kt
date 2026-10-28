package com.torve.presentation.panda

import com.torve.data.panda.PandaProvider
import kotlin.test.*

class PandaProviderConnectionStateTest {
    private val rd = PandaProvider("realdebrid", "Real-Debrid", listOf("oauth", "apikey"))

    @Test fun anotherProvidersKeyDoesNotHideTheSelectedProvidersConnectionForm() {
        val state = PandaSetupUiState(selectedProvider = rd, authConnected = true,
            debridApiKeys = mapOf("torbox" to "torbox-key"))
        assertFalse(state.hasDebridCredential("realdebrid"))
        assertEquals("Not connected", state.debridStatusLabel("realdebrid"))
        assertTrue(state.hasDebridCredential("torbox"))
        assertFalse(state.isDebridVerified("torbox"))
    }

    @Test fun serverOnlyCredentialsBelongToTheirNamedProvider() {
        val state = PandaSetupUiState(selectedProvider = rd, authConnected = true,
            serverHasSecrets = setOf("debrid_api_key", "debrid_api_key_torbox"))
        assertFalse(state.hasDebridCredential("realdebrid"))
        assertTrue(state.hasDebridCredential("torbox"))
        assertEquals("Saved credentials · not checked", state.debridStatusLabel("torbox"))
    }

    @Test fun verificationIsBoundToBothTheProviderAndItsCurrentKey() {
        val state = PandaSetupUiState(debridApiKeys = mapOf("realdebrid" to "rd-key", "torbox" to "tb-key"),
            verifiedDebridApiKeys = mapOf("realdebrid" to "rd-key"))
        assertTrue(state.isDebridVerified("realdebrid"))
        assertFalse(state.isDebridVerified("torbox"))
        assertFalse(state.copy(debridApiKeys = mapOf("realdebrid" to "new-key")).isDebridVerified("realdebrid"))
    }

    @Test fun selectingAnUnconfiguredProviderKeepsBothExistingConnectionsInTheSavePayload() {
        val keys = mapOf("torbox" to "tb-key", "premiumize" to "pm-key")
        val state = PandaSetupUiState(setupMode = PandaSetupMode.DEBRID, selectedProvider = rd,
            debridApiKeys = keys, verifiedDebridApiKeys = keys, authConnected = true)
        assertEquals(setOf("torbox", "premiumize"), pandaDebridConnectionsForPayload(state).map { it.provider }.toSet())
        assertFalse(state.hasDebridCredential("realdebrid"))
        assertEquals("Connected", state.debridStatusLabel("torbox"))
        assertEquals("Connected", state.debridStatusLabel("premiumize"))
    }

    @Test fun disablingOneProviderPreservesBothCredentialsAndTheOthersStatus() {
        val keys = mapOf("realdebrid" to "rd-key", "torbox" to "tb-key")
        val state = PandaSetupUiState(debridApiKeys = keys, verifiedDebridApiKeys = keys,
            debridProviderEnabled = mapOf("realdebrid" to false))
        assertEquals("Connected · disabled", state.debridStatusLabel("realdebrid"))
        assertEquals("Connected", state.debridStatusLabel("torbox"))
        assertEquals(2, state.debridApiKeys.size)
    }

    @Test fun hydrationCannotBorrowThePrimaryProvidersKeyForAnotherSelection() {
        assertEquals("", selectedPandaDebridCredential("realdebrid", mapOf("torbox" to "tb-key"), "torbox", "tb-key"))
        assertEquals("rd-key", selectedPandaDebridCredential("realdebrid", mapOf("realdebrid" to "rd-key"), "torbox", "tb-key"))
        assertEquals("tb-key", selectedPandaDebridCredential("torbox", emptyMap(), "torbox", "tb-key"))
    }

    @Test fun globalReadyOrUsenetOnlyNeverLabelsAnUnconfiguredProviderConnected() {
        val state = PandaSetupUiState(setupMode = PandaSetupMode.USENET_ONLY, authConnected = true)
        assertFalse(state.hasDebridCredential("none"))
        assertFalse(state.hasDebridCredential("realdebrid"))
        assertEquals("Not connected", state.debridStatusLabel("realdebrid"))
    }
}
