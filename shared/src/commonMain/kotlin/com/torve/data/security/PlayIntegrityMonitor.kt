package com.torve.data.security

import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.security.ClientIntegrityChallenge
import com.torve.domain.security.ClientTrustSignal
import com.torve.domain.security.ClientTrustSignalRegistry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Best-effort telemetry only. Called from the existing background account task. */
class PlayIntegrityMonitor(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val preferences: PreferencesRepository,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()

    suspend fun observe(token: String, event: String, installationId: String?, signal: ClientTrustSignal) {
        if (event !in setOf("login", "foreground", "credential_transfer") ||
            signal.integrityProvider != "google_play_integrity" ||
            signal.installerPackage != "com.android.vending" || signal.isDebuggable == true ||
            signal.packageName != "com.torve.app" || !mutex.tryLock()) return
        try {
            val now = nowMillis()
            val previous = preferences.getString(LAST_ATTEMPT)?.toLongOrNull()
            if (previous != null && now >= previous && now - previous < DAY_MS) return
            // Persist before requesting, so failures, restarts and repeated foreground events cannot loop.
            preferences.setString(LAST_ATTEMPT, now.toString())
            withTimeoutOrNull(75_000) {
                val base = baseUrlProvider().trimEnd('/')
                val challengeResponse = httpClient.post("$base/me/integrity-monitor/challenge") {
                    bearerAuth(token)
                    installationId?.let { header("X-Torve-Installation-Id", it) }
                    contentType(ContentType.Application.Json)
                    setBody(IntegrityMonitorStart(event))
                }
                if (!challengeResponse.status.isSuccess()) return@withTimeoutOrNull
                val challenge = challengeResponse.body<ClientIntegrityChallenge>()
                if (!challenge.enabled || challenge.challenge.isBlank() || challenge.requestHash.isBlank() ||
                    challenge.cloudProjectNumber <= 0) return@withTimeoutOrNull
                val attestation = ClientTrustSignalRegistry.provider.currentIntegrityAttestation(challenge)
                httpClient.post("$base/me/integrity-monitor/verdict") {
                    bearerAuth(token)
                    installationId?.let { header("X-Torve-Installation-Id", it) }
                    contentType(ContentType.Application.Json)
                    setBody(IntegrityMonitorSubmit(challenge.challenge, attestation?.integrityToken))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Integrity is observation-only, including network and Google service errors.
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        internal const val LAST_ATTEMPT = "internal_play_integrity_last_attempt_ms"
        internal const val DAY_MS = 86_400_000L
    }
}

@Serializable
internal data class IntegrityMonitorStart(@SerialName("event_type") val eventType: String)

@Serializable
internal data class IntegrityMonitorSubmit(
    val challenge: String,
    @SerialName("integrity_token") val integrityToken: String? = null,
)
