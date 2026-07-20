package com.torve.presentation.providerhealth

import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.EpgState
import com.torve.presentation.transfer.AttemptOutcome
import com.torve.presentation.transfer.AttemptRole
import com.torve.presentation.transfer.RelayReachability
import com.torve.presentation.transfer.TransferAttemptRecord
import com.torve.presentation.transfer.TransferDiagnosticsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks in the false-positive guards Prompt 17 specifies. Each test
 * names the warning it suppresses (or the wording it pins) so a
 * regression message points at the exact rule that was reintroduced.
 */
class ProviderEvidenceBuildersTest {

    private fun playlist() = ChannelPlaylist(
        id = "p1",
        name = "Test playlist",
        url = "http://example.invalid/m3u",
        channelCount = 100,
    )

    private fun rawIptvEntry(status: ProviderHealthStatus = ProviderHealthStatus.GREEN) = ProviderHealthEntry(
        category = ProviderHealthCategory.IPTV,
        providerKey = "iptv:active",
        label = "IPTV",
        status = status,
    )

    @Test
    fun `EPG loaded but zero matches with usable channels surfaces as informational GREEN`() {
        // Prompt 17 acceptance: "EPG loaded but no channels matched"
        // is not shown as a blocking error if channels are usable.
        // ChannelsViewModel sets EpgState.Loaded with all-zero counts
        // on cache-first startup; the runtime player still matches
        // channels by fuzzy name. Treating that placeholder as a
        // warning was the false-positive the user reported.
        val state = ChannelsUiState(
            playlists = listOf(playlist()),
            selectedPlaylistId = "p1",
            channels = emptyList(),  // not yet rendered
            epgState = EpgState.Loaded(
                sourceUrl = "http://example.invalid/epg",
                sourceChannelCount = 0,
                sourceProgrammeCount = 0,
                matchedChannelCount = 0,
                unmatchedChannelCount = 0,
            ),
        )
        val facets = iptvFacetsFrom(state)
        val evidence = iptvEvidenceFrom(rawIptvEntry(), facets)

        assertEquals(
            ProviderHealthStatus.GREEN,
            evidence.status,
            "EPG zero-match with playable channels must NOT be RED/YELLOW; runtime falls back to name match",
        )
        assertTrue(
            evidence.evidenceSummary.contains("name match", ignoreCase = true),
            "evidence summary must explain the name-fallback so the user understands why this is informational, " +
                "not a real warning. Got: ${evidence.evidenceSummary}",
        )
    }

    @Test
    fun `cached IPTV catalog counts as playlist loaded evidence`() {
        val state = ChannelsUiState(
            playlists = emptyList(),
            selectedPlaylistId = "cached-p1",
            categories = listOf(com.torve.domain.model.ChannelCategory("General", 12)),
        )

        val facets = iptvFacetsFrom(state)
        val evidence = iptvEvidenceFrom(rawIptvEntry(), facets)

        assertTrue(facets.playlistLoaded)
        assertTrue(facets.channelsLoaded)
        assertEquals(ProviderHealthStatus.GREEN, evidence.status)
        assertFalse(
            evidence.evidenceSummary.contains("No IPTV playlist added", ignoreCase = true),
            "cached channel/catalog state must not produce the contradictory no-playlist copy",
        )
    }

    @Test
    fun `EPG zero-match without usable channels surfaces YELLOW with action`() {
        // The rare real case: playlist has no channels AND EPG matched
        // nothing. Then warn — there's actually a problem to fix.
        val state = ChannelsUiState(
            playlists = listOf(playlist().copy(channelCount = 0)),
            selectedPlaylistId = "p1",
            channels = emptyList(),
            epgState = EpgState.Loaded(
                sourceUrl = "http://example.invalid/epg",
                sourceChannelCount = 0,
                sourceProgrammeCount = 0,
                matchedChannelCount = 0,
                unmatchedChannelCount = 50,
            ),
        )
        val facets = iptvFacetsFrom(state)
        val evidence = iptvEvidenceFrom(rawIptvEntry(), facets)

        // Without usable channels we expect either YELLOW or
        // UNCONFIGURED depending on the playlist state — both should
        // give the user something to do. The key invariant is
        // "not silently green when nothing actually works".
        assertTrue(
            evidence.status == ProviderHealthStatus.YELLOW ||
                evidence.status == ProviderHealthStatus.UNCONFIGURED,
            "with no usable channels we must surface something, got status=${evidence.status}",
        )
    }

    @Test
    fun `IPTV facets correctly distinguish the five sub-states the prompt lists`() {
        // Prompt 17: IPTV health must distinguish playlist loaded /
        // channels loaded / EPG loaded / EPG matched / EPG unmatched
        // but usable. Smoke-check that the structured facets carry
        // the distinction so downstream UI can render them.
        val state = ChannelsUiState(
            playlists = listOf(playlist()),
            selectedPlaylistId = "p1",
            channels = emptyList(),
            categories = listOf(),
            groupedChannels = emptyMap(),
            epgState = EpgState.Loaded(
                sourceUrl = "u",
                sourceChannelCount = 100,
                sourceProgrammeCount = 1000,
                matchedChannelCount = 70,
                unmatchedChannelCount = 30,
            ),
        )
        val facets = iptvFacetsFrom(state)
        // Five distinct facets are observable separately:
        assertTrue(facets.playlistLoaded)
        assertTrue(facets.channelsLoaded, "100 channelCount on a stored playlist counts as loaded")
        assertTrue(facets.epgLoaded)
        assertEquals(70, facets.epgMatchedCount)
        assertEquals(30, facets.epgUnmatchedCount)
        assertFalse(facets.epgFullyUnmatched, "70 matched is not fully unmatched")
    }

    private fun snapshotWithReachability(
        reach: RelayReachability,
        attempt: TransferAttemptRecord? = null,
    ) = TransferDiagnosticsSnapshot(
        cryptoEngineAvailable = true,
        signedIn = reach != RelayReachability.NOT_SIGNED_IN,
        relayReachable = reach,
        lastAttempt = attempt,
        collectedAtEpochMs = 1_000L,
    )

    @Test
    fun `transfer evidence with no attempt explains credential transfer explicitly`() {
        // Prompt 20 acceptance: "attempt" by itself is not enough.
        // The wording must name credential transfer and must not read as "transfer
        // failed" — the user has done nothing yet and the row
        // shouldn't sound like an error.
        val facet = transferFacetFrom(snapshotWithReachability(RelayReachability.UNKNOWN))
        assertEquals(TransferHealthFacet.NoAttemptYet, facet)
        val evidence = transferEvidenceFrom(facet)
        assertEquals(ProviderHealthStatus.UNCONFIGURED, evidence.status)
        assertTrue(
            evidence.evidenceSummary.contains("No credential transfer has been started", ignoreCase = true),
            "summary must explain what has not happened, got: ${evidence.evidenceSummary}",
        )
        assertFalse(evidence.canRefresh, "refresh on a never-attempted state would do nothing")
    }

    @Test
    fun `network error explains backend relay vs manual paste fallback`() {
        // Prompt 17 acceptance: network errors explain backend relay
        // vs local/manual transfer fallback. The user should know
        // the network failure only blocks the auto-import path; they
        // can still paste credentials manually from another device.
        val facet = transferFacetFrom(snapshotWithReachability(RelayReachability.NETWORK_ERROR))
        assertEquals(TransferHealthFacet.BackendUnavailable, facet)
        val evidence = transferEvidenceFrom(facet)
        assertTrue(
            evidence.evidenceSummary.contains("manual paste", ignoreCase = true),
            "network-error evidence must point at the manual paste fallback. Got: ${evidence.evidenceSummary}",
        )
    }

    @Test
    fun `unauthenticated facet surfaces sign-in action without panicking`() {
        val facet = transferFacetFrom(snapshotWithReachability(RelayReachability.UNAUTHORIZED))
        assertEquals(TransferHealthFacet.Unauthenticated, facet)
        val evidence = transferEvidenceFrom(facet)
        assertEquals("Sign in", evidence.recommendedAction)
        assertTrue(
            evidence.evidenceSummary.contains("manual paste", ignoreCase = true),
            "unauthenticated evidence must still mention manual paste fallback",
        )
    }

    @Test
    fun relayUnsupported404MapsToRelayUnsupportedFacetNotBackendUnavailable() {
        // RelayReachability.UNAVAILABLE means the relay route family
        // returned 404 — distinct from a network failure. The facet
        // must reflect that distinction so the copy can name "the
        // backend doesn't expose the relay" rather than blaming the
        // network.
        val facet = transferFacetFrom(snapshotWithReachability(RelayReachability.UNAVAILABLE))
        assertEquals(TransferHealthFacet.RelayUnsupported, facet)
    }

    @Test
    fun `last send failure facet renders with retry action`() {
        val facet = transferFacetFrom(
            snapshotWithReachability(
                RelayReachability.REACHABLE,
                attempt = TransferAttemptRecord(
                    role = AttemptRole.SENDER,
                    outcome = AttemptOutcome.FAILED,
                    recordedAtEpochMs = 5_000L,
                ),
            ),
        )
        assertTrue(facet is TransferHealthFacet.LastSendFailed)
        val evidence = transferEvidenceFrom(facet)
        assertEquals("Retry send", evidence.recommendedAction)
        assertEquals(5_000L, evidence.lastCheckedAt)
    }

    @Test
    fun `last receive failure facet renders with retry receive action`() {
        val facet = transferFacetFrom(
            snapshotWithReachability(
                RelayReachability.REACHABLE,
                attempt = TransferAttemptRecord(
                    role = AttemptRole.RECEIVER,
                    outcome = AttemptOutcome.FAILED,
                    recordedAtEpochMs = 8_000L,
                ),
            ),
        )
        assertTrue(facet is TransferHealthFacet.LastReceiveFailed)
        val evidence = transferEvidenceFrom(facet)
        assertEquals("Retry receive", evidence.recommendedAction)
    }
}
