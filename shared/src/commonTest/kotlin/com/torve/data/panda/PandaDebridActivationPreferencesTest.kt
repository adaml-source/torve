package com.torve.data.panda

import com.torve.domain.model.DebridServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PandaDebridActivationPreferencesTest {
    @Test
    fun legacyProviderDefaultsEnabledOnce() {
        val legacy = PandaConfigPayload(
            debridService = "realdebrid",
            debridConnections = emptyList(),
        )

        assertEquals(mapOf("realdebrid" to true), legacy.debridActivationState())
        assertTrue(PandaDebridActivationSnapshot().isEnabled("realdebrid"))
    }

    @Test
    fun pendingOfflineDisableWinsOverOlderServerEnabledState() {
        val local = PandaDebridActivationSnapshot(
            enabledByProvider = mapOf("realdebrid" to false),
            pendingProviderIds = setOf("realdebrid"),
        )

        val merged = local.mergeServerState(
            serverState = mapOf("realdebrid" to true, "torbox" to false),
            updatedAt = "2026-08-13T12:00:00Z",
        )

        assertFalse(merged.isEnabled("realdebrid"))
        assertFalse(merged.isEnabled("torbox"))
        assertEquals(setOf("realdebrid"), merged.pendingProviderIds)
    }

    @Test
    fun synchronizedServerStateBecomesAuthoritative() {
        val pending = PandaDebridActivationSnapshot()
            .withExplicitMutation("realdebrid", enabled = false)
        val synchronized = pending.markSynchronized(setOf("realdebrid"))
        val enabledElsewhere = synchronized.mergeServerState(
            serverState = mapOf("realdebrid" to true),
            updatedAt = "2026-08-13T13:00:00Z",
        )

        assertTrue(enabledElsewhere.isEnabled("realdebrid"))
        assertTrue(enabledElsewhere.pendingProviderIds.isEmpty())
    }

    @Test
    fun removedServerProviderDoesNotLeaveAStaleLocalDisableBehind() {
        val cached = PandaDebridActivationSnapshot(
            enabledByProvider = mapOf("realdebrid" to false),
        )

        val merged = cached.mergeServerState(emptyMap(), updatedAt = "2026-08-13T14:00:00Z")

        assertTrue(merged.enabledByProvider.isEmpty())
        assertTrue(merged.isEnabled("realdebrid"))
    }

    @Test
    fun explicitDisconnectBlocksStaleServerAndCredentialUse() {
        val disconnected = PandaDebridActivationSnapshot(
            enabledByProvider = mapOf("realdebrid" to true),
            serverUpdatedAt = "2026-08-13T13:00:00Z",
        ).withExplicitDisconnect("realdebrid")

        val stale = disconnected.mergeServerState(
            serverState = mapOf("realdebrid" to true),
            updatedAt = "2026-08-13T13:00:00Z",
        )

        assertFalse(stale.isEnabled("realdebrid"))
        assertEquals(setOf("realdebrid"), stale.disconnectedProviderIds)
        assertTrue(stale.enabledCredentials(mapOf(DebridServiceType.REAL_DEBRID to "rd-key")).isEmpty())
    }

    @Test
    fun newerExplicitReconnectClearsDisconnectTombstone() {
        val disconnected = PandaDebridActivationSnapshot(
            disconnectedProviderIds = setOf("realdebrid"),
            serverUpdatedAt = "2026-08-13T13:00:00Z",
        )

        val reconnected = disconnected.mergeServerState(
            serverState = mapOf("realdebrid" to true),
            updatedAt = "2026-08-13T14:00:00Z",
        )

        assertTrue(reconnected.isEnabled("realdebrid"))
        assertTrue(reconnected.disconnectedProviderIds.isEmpty())
    }

    @Test
    fun localReconnectClearsDisconnectBeforeAccountCredentialIsSaved() {
        val reconnected = PandaDebridActivationSnapshot(
            disconnectedProviderIds = setOf("realdebrid"),
        ).withExplicitMutation("realdebrid", enabled = true)

        assertTrue(reconnected.isEnabled("realdebrid"))
        assertTrue(reconnected.disconnectedProviderIds.isEmpty())
        assertEquals(setOf("realdebrid"), reconnected.pendingProviderIds)
    }

    @Test
    fun newerServerRemovalPropagatesDisconnectToAnotherDevice() {
        val deviceB = PandaDebridActivationSnapshot(
            enabledByProvider = mapOf("realdebrid" to true, "torbox" to true),
            serverUpdatedAt = "2026-08-13T13:00:00Z",
        )

        val synced = deviceB.mergeServerState(
            serverState = mapOf("torbox" to true),
            updatedAt = "2026-08-13T14:00:00Z",
        )

        assertEquals(setOf("realdebrid"), synced.disconnectedProviderIds)
        assertFalse(synced.isEnabled("realdebrid"))
        assertTrue(synced.isEnabled("torbox"))
    }

    @Test
    fun disabledConfiguredCredentialIsExcludedFromResolutionAccounts() {
        val snapshot = PandaDebridActivationSnapshot(
            enabledByProvider = mapOf(
                "realdebrid" to false,
                "torbox" to true,
            ),
        )

        assertEquals(
            mapOf(DebridServiceType.TORBOX to "tb-key"),
            snapshot.enabledCredentials(
                mapOf(
                    DebridServiceType.REAL_DEBRID to "rd-key",
                    DebridServiceType.TORBOX to "tb-key",
                ),
            ),
        )
    }
}
