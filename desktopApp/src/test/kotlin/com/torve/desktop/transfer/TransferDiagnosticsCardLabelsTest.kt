package com.torve.desktop.transfer

import com.torve.data.transfer.CreateTransferSessionRequest
import com.torve.data.transfer.TransferRelayApi
import com.torve.data.transfer.TransferRelayResult
import com.torve.data.transfer.TransferSessionDto
import com.torve.domain.telemetry.TransferTelemetryErrorCategory
import com.torve.domain.transfer.SealedSecretsEnvelope
import com.torve.presentation.transfer.AttemptOutcome
import com.torve.presentation.transfer.AttemptRole
import com.torve.presentation.transfer.RelayReachability
import com.torve.presentation.transfer.TransferAttemptTracker
import com.torve.presentation.transfer.TransferDiagnosticsCollector
import com.torve.presentation.transfer.TransferDiagnosticsSnapshot
import com.torve.desktop.security.JvmTransferCryptoEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the desktop diagnostics card's label helpers and exercises
 * the shared collector + tracker through a relay-unavailable +
 * recovered cycle.
 *
 * Two invariants the tests pin:
 *   1. Every label string is one of the closed enum tokens shipped in
 *      shared — no stringly-typed leakage from arbitrary enum names.
 *   2. The diagnostics snapshot's `toString()` is free of any backend
 *      body / session string / envelope / token shape after a sequence
 *      of recorded attempts.
 */
class TransferDiagnosticsCardLabelsTest {

    @Test
    fun relayLabelCoversEveryReachabilityValue() {
        val expected = mapOf(
            RelayReachability.UNKNOWN to "unknown",
            RelayReachability.REACHABLE to "reachable",
            RelayReachability.UNAVAILABLE to "unavailable",
            RelayReachability.UNAUTHORIZED to "unauthorized",
            RelayReachability.NETWORK_ERROR to "network error",
            RelayReachability.NOT_SIGNED_IN to "not signed in",
            RelayReachability.NO_CRYPTO_ENGINE to "no crypto engine",
        )
        for (r in RelayReachability.entries) {
            assertEquals(expected[r], relayLabel(r), "missing label for $r")
        }
    }

    @Test
    fun roleLabelAndOutcomeLabelCoverEveryEnumValue() {
        for (r in AttemptRole.entries) {
            // Just asserting non-blank — exact tokens are checked in the
            // map test where strict pinning matters.
            assertTrue(roleLabel(r).isNotBlank(), "missing role label for $r")
        }
        for (o in AttemptOutcome.entries) {
            assertTrue(outcomeLabel(o).isNotBlank(), "missing outcome label for $o")
        }
        // Strict pinning for the canonical 5 outcomes.
        val pinned = mapOf(
            AttemptOutcome.REGISTERED to "registered",
            AttemptOutcome.DELIVERED to "delivered",
            AttemptOutcome.IMPORTED to "imported",
            AttemptOutcome.FAILED to "failed",
            AttemptOutcome.RELAY_UNAVAILABLE to "relay unavailable",
        )
        for ((k, v) in pinned) {
            assertEquals(v, outcomeLabel(k))
        }
    }

    @Test
    fun collectorReflectsRelayUnavailableThenRecoveredAttempt() = runBlocking {
        val tracker = TransferAttemptTracker(nowMs = { 1_700_000_000_000L })
        val engine = JvmTransferCryptoEngine()
        val api = SwitchableRelayApi()
        val collector = TransferDiagnosticsCollector(
            cryptoEngine = engine,
            authClient = null, // signed-out: probe is gated on auth
            relayApi = api,
            tracker = tracker,
            nowMs = { 1_700_000_000_000L },
        )

        // Initial: nothing recorded → relay UNKNOWN (we're signed-out, so
        // collector short-circuits to NOT_SIGNED_IN).
        val initial = collector.collect(probeRelay = false)
        assertEquals(RelayReachability.NOT_SIGNED_IN, initial.relayReachable)
        assertEquals(null, initial.lastAttempt)

        // Simulate a real "relay unavailable" event from the receiver VM.
        tracker.record(
            AttemptRole.RECEIVER,
            AttemptOutcome.RELAY_UNAVAILABLE,
            errorCategory = TransferTelemetryErrorCategory.RELAY_NOT_DEPLOYED,
        )
        val unavailable = collector.collect(probeRelay = false)
        // Still NOT_SIGNED_IN because we have no AuthClient — the
        // collector won't override that with history. But the lastAttempt
        // is now populated with closed-enum values only.
        assertEquals(RelayReachability.NOT_SIGNED_IN, unavailable.relayReachable)
        val ua = assertNotNull(unavailable.lastAttempt)
        assertEquals(AttemptRole.RECEIVER, ua.role)
        assertEquals(AttemptOutcome.RELAY_UNAVAILABLE, ua.outcome)
        assertEquals(TransferTelemetryErrorCategory.RELAY_NOT_DEPLOYED, ua.errorCategory)

        // Now simulate a successful import on the same device.
        tracker.record(AttemptRole.RECEIVER, AttemptOutcome.IMPORTED)
        val recovered = collector.collect(probeRelay = false)
        val rec = assertNotNull(recovered.lastAttempt)
        assertEquals(AttemptOutcome.IMPORTED, rec.outcome)
        assertEquals(null, rec.errorCategory)

        // Closed-shape invariant: snapshot.toString() must not contain
        // any backend/session/envelope shape at any point in the cycle.
        for (snapshot in listOf(initial, unavailable, recovered)) {
            assertNoLeakShapesIn(snapshot)
        }
    }

    @Test
    fun probeRelayDoesNotConsumeQuotaEvenWhenAuthIsAbsent() = runBlocking {
        val tracker = TransferAttemptTracker(nowMs = { 1L })
        val api = SwitchableRelayApi()
        val collector = TransferDiagnosticsCollector(
            cryptoEngine = JvmTransferCryptoEngine(),
            authClient = null,
            relayApi = api,
            tracker = tracker,
            nowMs = { 1L },
        )
        collector.collect(probeRelay = true)
        // Without auth the collector short-circuits; never hit the API.
        assertEquals(0, api.getCalls)
    }

    private fun assertNoLeakShapesIn(snap: TransferDiagnosticsSnapshot) {
        val text = snap.toString()
        for (forbidden in listOf(
            "Bearer ",
            "torve://transfer/receive/",
            "\"ciphertext\"",
            "\"senderEphemeralPublicKey\"",
            "https://",
            "http://",
        )) {
            assertTrue(
                !text.contains(forbidden),
                "Diagnostics leak: snapshot.toString() contains '$forbidden': $text",
            )
        }
    }
}

/** Test stub — flips relay reachability between unavailable and OK. */
private class SwitchableRelayApi : TransferRelayApi {
    var getCalls: Int = 0
    var unavailable: Boolean = true

    override suspend fun createSession(
        accessToken: String,
        request: CreateTransferSessionRequest,
    ): TransferRelayResult<TransferSessionDto> = error("not used")

    override suspend fun getSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<TransferSessionDto> {
        getCalls += 1
        return if (unavailable) TransferRelayResult.Unavailable
        else TransferRelayResult.NotFound
    }

    override suspend fun postEnvelope(
        accessToken: String,
        sessionId: String,
        envelope: SealedSecretsEnvelope,
    ): TransferRelayResult<Unit> = error("not used")

    override suspend fun consumeSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<Unit> = error("not used")
}
