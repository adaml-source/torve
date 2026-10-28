package com.torve.presentation.providerhealth

import com.torve.data.simkl.SimklClient
import com.torve.data.trakt.TraktClient
import com.torve.domain.integrations.ConnectionProbeResult
import com.torve.domain.providerhealth.ProviderHealthFailure
import com.torve.domain.providerhealth.ProviderHealthStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class TrackingAccountProbeTest {
    @Test fun accountProbesUseAuthenticatedReadOnlyEndpoints() = runTest {
        for (trakt in listOf(true, false)) {
            var calls = 0
            val client = HttpClient(MockEngine { request ->
                calls++
                assertEquals("/users/settings", request.url.encodedPath)
                assertEquals("Bearer fixture-token", request.headers["Authorization"])
                assertEquals(if (trakt) HttpMethod.Get else HttpMethod.Post, request.method)
                assertNotNull(request.headers[if (trakt) "trakt-api-key" else "simkl-api-key"])
                respond("{}", HttpStatusCode.OK)
            })
            try {
                val result = if (trakt) TraktClient(client, Json).probeAccount("fixture-token")
                    else SimklClient(client).probeAccount("fixture-token")
                assertEquals(ConnectionProbeResult.CONNECTED, result)
                assertEquals(1, calls)
            } finally { client.close() }
        }
    }

    @Test fun responsesRemainTypedWhenKtorThrowsForHttpFailures() = runTest {
        val cases = mapOf(401 to ConnectionProbeResult.AUTH_REJECTED, 403 to ConnectionProbeResult.AUTH_REJECTED,
            429 to ConnectionProbeResult.UNREACHABLE, 503 to ConnectionProbeResult.UNREACHABLE,
            404 to ConnectionProbeResult.SETUP_INCOMPLETE)
        for ((status, expected) in cases) for (trakt in listOf(true, false)) {
            val client = HttpClient(MockEngine { respond("private response", HttpStatusCode.fromValue(status)) }) {
                expectSuccess = true
            }
            try {
                assertEquals(expected, if (trakt) TraktClient(client, Json).probeAccount("fixture-token")
                    else SimklClient(client).probeAccount("fixture-token"))
            } finally { client.close() }
        }
    }

    @Test fun emptyCredentialDoesNotMakeAnAccountRequest() = runTest {
        var calls = 0
        assertEquals(ProviderHealthStatus.UNCONFIGURED, TraktProviderHealthChecker({ " " }, {
            calls++; ConnectionProbeResult.CONNECTED
        }).check().status)
        assertEquals(ProviderHealthStatus.UNCONFIGURED, SimklProviderHealthChecker({ null }, {
            calls++; ConnectionProbeResult.CONNECTED
        }).check().status)
        assertEquals(0, calls)
    }

    @Test fun savedTokenWithoutAProbeIsNotPresentedAsVerified() = runTest {
        assertEquals(ProviderHealthStatus.YELLOW, TraktProviderHealthChecker({ "fixture" }).check().status)
        assertEquals(ProviderHealthStatus.YELLOW, SimklProviderHealthChecker({ "fixture" }).check().status)
    }

    @Test fun rejectedTokenPointsToReauthorizationInsteadOfSync() = runTest {
        val entry = TraktProviderHealthChecker({ "fixture" }, { ConnectionProbeResult.AUTH_REJECTED }).check()
        assertEquals(ProviderHealthStatus.RED, entry.status)
        assertEquals(ProviderHealthFailure.AUTH_REJECTED, entry.failure)
        assertEquals("Reconnect Trakt", entry.nextAction)
    }

    @Test fun networkFailureDoesNotClaimCredentialsWereRejected() = runTest {
        val entry = SimklProviderHealthChecker({ "fixture" }, { ConnectionProbeResult.UNREACHABLE }).check()
        assertEquals(ProviderHealthFailure.UNREACHABLE, entry.failure)
        assertEquals("Retry connection", entry.nextAction)
    }

    @Test fun unexpectedErrorsAreSanitized() = runTest {
        val entry = TraktProviderHealthChecker({ "fixture" }, { error("SECRET_TOKEN https://private-server") }).check()
        assertFalse(entry.message.orEmpty().contains("SECRET_TOKEN"))
        assertFalse(entry.message.orEmpty().contains("private-server"))
    }

    @Test fun cancellationNeverBecomesAConnectionFailure() = runTest {
        assertFailsWith<CancellationException> {
            SimklProviderHealthChecker({ "fixture" }, { throw CancellationException("cancel") }).check()
        }
    }
}
