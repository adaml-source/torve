package com.torve.domain.security

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientTrustSignalTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun trustSignalSerializesCompactIntegrityMetadataOnly() {
        val encoded = json.encodeToString(
            ClientTrustSignal(
                platform = "android_tv",
                appVersion = "1.0.0",
                buildNumber = "20074",
                flavor = "googleTv",
                distributionChannel = "google_play",
                packageName = "com.torve.app",
                installerPackage = "com.android.vending",
                signingCertificateSha256 = "abc123",
                isDebuggable = false,
                isEmulator = false,
                hasKnownHookingIndicators = false,
                hasKnownRootIndicators = false,
                integrityProvider = "google_play_integrity",
                generatedAtEpochMillis = 1234L,
            ),
        )
        val obj = json.parseToJsonElement(encoded).jsonObject

        assertEquals("android_tv", obj["platform"]?.jsonPrimitive?.content)
        assertEquals("google_play_integrity", obj["integrity_provider"]?.jsonPrimitive?.content)
        assertNull(obj["integrity_token"])
    }

    @Test
    fun noopProviderDoesNotEmitIntegrityToken() = runTest {
        ClientTrustSignalRegistry.clearProvider()

        val header = ClientTrustHeaders.capture(includeIntegrityToken = true)
            ?: error("missing header")
        val obj = json.parseToJsonElement(header.encodedSignal).jsonObject

        assertEquals("unknown", obj["platform"]?.jsonPrimitive?.content)
        assertEquals("none", obj["integrity_provider"]?.jsonPrimitive?.content)
        assertNull(obj["integrity_token"])
        assertNull(ClientTrustHeaders.captureIntegrityAttestation())
    }

    @Test
    fun trustHeaderDoesNotIncludeFullIntegrityAttestationToken() = runTest {
        ClientTrustSignalRegistry.setProvider(
            object : ClientTrustSignalProvider {
                override suspend fun currentSignal(includeIntegrityToken: Boolean): ClientTrustSignal =
                    ClientTrustSignal(
                        platform = "android",
                        integrityProvider = "google_play_integrity",
                        generatedAtEpochMillis = 42L,
                    )

                override suspend fun currentIntegrityAttestation(): ClientIntegrityAttestation =
                    ClientIntegrityAttestation(
                        integrityProvider = "google_play_integrity",
                        integrityToken = "full-play-integrity-token-that-must-not-be-in-a-header",
                        nonce = "nonce-1",
                        generatedAtEpochMillis = 43L,
                    )
            },
        )

        try {
            val header = ClientTrustHeaders.capture(includeIntegrityToken = true)
                ?: error("missing header")
            val obj = json.parseToJsonElement(header.encodedSignal).jsonObject

            assertFalse(header.encodedSignal.contains("full-play-integrity-token"))
            assertEquals("google_play_integrity", obj["integrity_provider"]?.jsonPrimitive?.content)
            assertNull(obj["integrity_token"])

            val attestation = ClientTrustHeaders.captureIntegrityAttestation()
            assertEquals("full-play-integrity-token-that-must-not-be-in-a-header", attestation?.integrityToken)
        } finally {
            ClientTrustSignalRegistry.clearProvider()
        }
    }

    @Test
    fun oversizedTrustHeaderCompactsOptionalFields() = runTest {
        val longValue = "x".repeat(2_000)
        ClientTrustSignalRegistry.setProvider(
            object : ClientTrustSignalProvider {
                override suspend fun currentSignal(includeIntegrityToken: Boolean): ClientTrustSignal =
                    ClientTrustSignal(
                        platform = "android_tv",
                        appVersion = "1.0.0",
                        buildNumber = "200",
                        flavor = "googleTv",
                        distributionChannel = "google_play",
                        packageName = "com.torve.app",
                        installerPackage = longValue,
                        signingCertificateSha256 = longValue,
                        hasKnownHookingIndicators = false,
                        hasKnownRootIndicators = false,
                        integrityProvider = "google_play_integrity",
                        generatedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                    )
            },
        )

        try {
            val header = ClientTrustHeaders.capture() ?: error("missing header")

            assertTrue(header.encodedSignal.length <= 1024)
            assertFalse(header.encodedSignal.contains(longValue))
        } finally {
            ClientTrustSignalRegistry.clearProvider()
        }
    }
}
