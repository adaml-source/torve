package com.torve.domain.security

import com.torve.platform.torveVerboseLog
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ClientTrustSignal(
    val platform: String,
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("build_number")
    val buildNumber: String? = null,
    val flavor: String? = null,
    @SerialName("distribution_channel")
    val distributionChannel: String? = null,
    @SerialName("package_name")
    val packageName: String? = null,
    @SerialName("installer_package")
    val installerPackage: String? = null,
    @SerialName("signing_certificate_sha256")
    val signingCertificateSha256: String? = null,
    @SerialName("is_debuggable")
    val isDebuggable: Boolean? = null,
    @SerialName("is_emulator")
    val isEmulator: Boolean? = null,
    @SerialName("has_known_hooking_indicators")
    val hasKnownHookingIndicators: Boolean? = null,
    @SerialName("has_known_root_indicators")
    val hasKnownRootIndicators: Boolean? = null,
    @SerialName("integrity_provider")
    val integrityProvider: String = "none",
    @SerialName("generated_at_epoch_millis")
    val generatedAtEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
)

@Serializable
data class ClientIntegrityAttestation(
    @SerialName("integrity_provider")
    val integrityProvider: String,
    @SerialName("integrity_token")
    val integrityToken: String,
    val nonce: String? = null,
    @SerialName("generated_at_epoch_millis")
    val generatedAtEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
)

interface ClientTrustSignalProvider {
    suspend fun currentSignal(includeIntegrityToken: Boolean = false): ClientTrustSignal

    suspend fun currentIntegrityAttestation(): ClientIntegrityAttestation? = null
}

object NoOpClientTrustSignalProvider : ClientTrustSignalProvider {
    override suspend fun currentSignal(includeIntegrityToken: Boolean): ClientTrustSignal =
        ClientTrustSignal(
            platform = "unknown",
            integrityProvider = "none",
        )
}

object ClientTrustSignalRegistry {
    var provider: ClientTrustSignalProvider = NoOpClientTrustSignalProvider
        private set

    fun setProvider(provider: ClientTrustSignalProvider) {
        this.provider = provider
    }

    fun clearProvider() {
        provider = NoOpClientTrustSignalProvider
    }
}

data class ClientTrustHeaderValues(
    val encodedSignal: String,
) {
    fun appendTo(builder: HttpRequestBuilder) {
        builder.header(ClientTrustHeaders.TRUST_SIGNAL_HEADER, encodedSignal)
    }
}

object ClientTrustHeaders {
    const val TRUST_SIGNAL_HEADER = "X-Torve-Client-Trust"
    private const val MAX_HEADER_CHARS = 1024

    private val headerJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun capture(includeIntegrityToken: Boolean = false): ClientTrustHeaderValues? {
        // Keep the parameter for source compatibility with earlier call
        // sites, but never put full integrity tokens into a generic HTTP
        // header. Tokens are requested through captureIntegrityAttestation()
        // and attached only to explicit verification payloads.
        val signal = runCatching {
            ClientTrustSignalRegistry.provider.currentSignal(includeIntegrityToken = false)
        }.getOrNull() ?: return null
        val encoded = encodeForHeader(signal) ?: return null
        return ClientTrustHeaderValues(
            encodedSignal = encoded,
        )
    }

    suspend fun captureIntegrityAttestation(): ClientIntegrityAttestation? {
        return runCatching {
            ClientTrustSignalRegistry.provider.currentIntegrityAttestation()
                ?.takeIf { it.integrityToken.isNotBlank() }
        }.getOrNull()
    }

    private fun encodeForHeader(signal: ClientTrustSignal): String? {
        val full = headerJson.encodeToString(signal)
        if (full.length <= MAX_HEADER_CHARS) return full

        torveVerboseLog {
            "CLIENT_TRUST header_compact reason=size fullChars=${full.length} maxChars=$MAX_HEADER_CHARS"
        }
        val compact = signal.copy(
            installerPackage = null,
            signingCertificateSha256 = null,
            hasKnownHookingIndicators = null,
            hasKnownRootIndicators = null,
        )
        val compactEncoded = headerJson.encodeToString(compact)
        if (compactEncoded.length <= MAX_HEADER_CHARS) return compactEncoded

        val minimal = ClientTrustSignal(
            platform = signal.platform,
            appVersion = signal.appVersion,
            buildNumber = signal.buildNumber,
            flavor = signal.flavor,
            distributionChannel = signal.distributionChannel,
            packageName = signal.packageName,
            isDebuggable = signal.isDebuggable,
            isEmulator = signal.isEmulator,
            integrityProvider = signal.integrityProvider,
            generatedAtEpochMillis = signal.generatedAtEpochMillis,
        )
        val minimalEncoded = headerJson.encodeToString(minimal)
        if (minimalEncoded.length <= MAX_HEADER_CHARS) return minimalEncoded

        torveVerboseLog {
            "CLIENT_TRUST header_omitted reason=size minimalChars=${minimalEncoded.length} maxChars=$MAX_HEADER_CHARS"
        }
        return null
    }
}
