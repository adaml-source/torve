package com.torve.data.security

import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.security.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.*
import kotlin.test.*

class PlayIntegrityMonitorTest {
    private val signal = ClientTrustSignal(platform = "android", packageName = "com.torve.app",
        installerPackage = "com.android.vending", integrityProvider = "google_play_integrity", isDebuggable = false)
    private class Prefs : PreferencesRepository {
        val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String) = values[key]
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    private fun TestScope.client(handler: MockRequestHandler) = HttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler(handler)
    })) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
    }
    private val challenge = """{"enabled":true,"challenge":"server-challenge","request_hash":"server-hash","cloud_project_number":588484757557}"""
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    @AfterTest fun cleanUp() { ClientTrustSignalRegistry.clearProvider() }

    @Test fun sendsBoundTokenOnlyToVerdictAndPersistsDailyCooldown() = runTest {
        var calls = 0
        var providerCalls = 0
        ClientTrustSignalRegistry.setProvider(object : ClientTrustSignalProvider {
            override suspend fun currentSignal(includeIntegrityToken: Boolean) = signal
            override suspend fun currentIntegrityAttestation(challenge: ClientIntegrityChallenge): ClientIntegrityAttestation {
                providerCalls++
                assertEquals("server-hash", challenge.requestHash)
                assertEquals(588484757557, challenge.cloudProjectNumber)
                return ClientIntegrityAttestation("google_play_integrity", "secret-token")
            }
        })
        val http = client { request ->
            calls++
            assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
            assertEquals("install", request.headers["X-Torve-Installation-Id"])
            assertFalse(request.headers.toString().contains("secret-token"))
            if (request.url.encodedPath.endsWith("/challenge")) respond(challenge, headers = headers)
            else {
                val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                assertEquals("server-challenge", body["challenge"]?.jsonPrimitive?.content)
                assertEquals("secret-token", body["integrity_token"]?.jsonPrimitive?.content)
                respond("{}", headers = headers)
            }
        }
        val prefs = Prefs()
        var now = 1000L
        fun monitor() = PlayIntegrityMonitor(http, { "https://api.torve.app" }, prefs, { now })
        monitor().observe("access", "login", "install", signal)
        monitor().observe("access", "foreground", "install", signal)
        assertEquals(2, calls)
        assertEquals(1, providerCalls)
        now += PlayIntegrityMonitor.DAY_MS
        monitor().observe("access", "foreground", "install", signal)
        assertEquals(4, calls)
        http.close()
    }

    @Test fun skipsSideloadsAmazonDebugBuildsAndUnrelatedActions() = runTest {
        val http = client { error("Must not contact backend") }
        val monitor = PlayIntegrityMonitor(http, { "https://api.torve.app" }, Prefs())
        for (s in listOf(signal.copy(installerPackage = null), signal.copy(integrityProvider = "none"),
            signal.copy(isDebuggable = true), signal.copy(packageName = "other"))) {
            monitor.observe("access", "login", "install", s)
        }
        monitor.observe("access", "playback", "install", signal)
        http.close()
    }

    @Test fun disabledChallengeAndNetworkFailureDoNotRetryOrFailCaller() = runTest {
        for (fails in listOf(false, true)) {
            var calls = 0
            val http = client {
                calls++
                if (fails) throw IllegalStateException("network unavailable")
                respond("{\"enabled\":false}", headers = headers)
            }
            val monitor = PlayIntegrityMonitor(http, { "https://api.torve.app" }, Prefs(), { 1000L })
            monitor.observe("access", "login", "install", signal)
            monitor.observe("access", "foreground", "install", signal)
            assertEquals(1, calls)
            http.close()
        }
    }

    @Test fun unavailableTokenIsReportedWithoutBlockingAndConcurrentAttemptsAreSkipped() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        ClientTrustSignalRegistry.setProvider(object : ClientTrustSignalProvider {
            override suspend fun currentSignal(includeIntegrityToken: Boolean) = signal
            override suspend fun currentIntegrityAttestation(challenge: ClientIntegrityChallenge): ClientIntegrityAttestation? {
                entered.complete(Unit)
                release.await()
                return null
            }
        })
        val http = client {
            calls++
            respond(if (calls == 1) challenge else "{}", headers = headers)
        }
        val monitor = PlayIntegrityMonitor(http, { "https://api.torve.app" }, Prefs(), { 1000L })
        val first = launch { monitor.observe("access", "login", "install", signal) }
        entered.await()
        monitor.observe("access", "foreground", "install", signal)
        assertEquals(1, calls)
        release.complete(Unit)
        first.join()
        assertEquals(2, calls)
        http.close()
    }

    @Test fun slowGoogleCallTimesOutAndCancellationPropagates() = runTest {
        for (cancel in listOf(false, true)) {
            var calls = 0
            ClientTrustSignalRegistry.setProvider(object : ClientTrustSignalProvider {
                override suspend fun currentSignal(includeIntegrityToken: Boolean) = signal
                override suspend fun currentIntegrityAttestation(challenge: ClientIntegrityChallenge): ClientIntegrityAttestation? {
                    if (cancel) throw CancellationException("cancelled")
                    delay(90_000)
                    return null
                }
            })
            val http = client { calls++; respond(challenge, headers = headers) }
            val monitor = PlayIntegrityMonitor(http, { "https://api.torve.app" }, Prefs(), { 1000L })
            if (cancel) assertFailsWith<CancellationException> { monitor.observe("access", "login", "install", signal) }
            else monitor.observe("access", "login", "install", signal)
            assertEquals(1, calls)
            http.close()
        }
    }
}
