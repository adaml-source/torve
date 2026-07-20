package com.torve.data.panda

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PandaApiContractTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // ── Providers ──

    @Test
    fun providersResponseDecodesCorrectly() {
        val dto = json.decodeFromString<PandaProvidersResponse>(
            """
            {
              "providers": [
                {
                  "id": "realdebrid",
                  "name": "Real-Debrid",
                  "auth_methods": ["oauth", "apikey"],
                  "logo_url": "https://example.com/logo.svg",
                  "help_url": "https://example.com/help"
                },
                {
                  "id": "torbox",
                  "name": "TorBox",
                  "auth_methods": ["apikey"]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, dto.providers.size)
        assertEquals("realdebrid", dto.providers[0].id)
        assertEquals(listOf("oauth", "apikey"), dto.providers[0].authMethods)
        assertEquals("https://example.com/logo.svg", dto.providers[0].logoUrl)
        assertEquals("torbox", dto.providers[1].id)
        assertEquals(listOf("apikey"), dto.providers[1].authMethods)
        assertNull(dto.providers[1].logoUrl)
    }

    // ── Device Code ──

    @Test
    fun deviceCodeResponseDecodesCorrectly() {
        val dto = json.decodeFromString<PandaDeviceCodeResponse>(
            """
            {
              "device_code": "abc123",
              "user_code": "XYZW",
              "verification_url": "https://real-debrid.com/device",
              "expires_in": 900,
              "interval": 5
            }
            """.trimIndent(),
        )

        assertEquals("abc123", dto.deviceCode)
        assertEquals("XYZW", dto.userCode)
        assertEquals("https://real-debrid.com/device", dto.verificationUrl)
        assertEquals(900, dto.expiresIn)
        assertEquals(5, dto.interval)
    }

    // ── Auth Poll ──

    @Test
    fun authPollPendingDecodes() {
        val dto = json.decodeFromString<PandaAuthPollResponse>("""{"status":"pending"}""")
        assertEquals("pending", dto.status)
        assertNull(dto.apiKey)
    }

    @Test
    fun authPollApprovedDecodes() {
        val dto = json.decodeFromString<PandaAuthPollResponse>(
            """{"status":"approved","api_key":"secret-key-123"}""",
        )
        assertEquals("approved", dto.status)
        assertEquals("secret-key-123", dto.apiKey)
    }

    @Test
    fun authPollExpiredDecodes() {
        val dto = json.decodeFromString<PandaAuthPollResponse>("""{"status":"expired"}""")
        assertEquals("expired", dto.status)
    }

    // ── Config ──

    @Test
    fun configResponseDecodes() {
        val dto = json.decodeFromString<PandaConfigResponse>(
            """
            {
              "panda_token": "tok_abc",
              "manifest_url": "https://panda.torve.app/u/tok_abc/manifest.json",
              "config_id": "cfg123",
              "expires_at": null
            }
            """.trimIndent(),
        )

        assertEquals("tok_abc", dto.pandaToken)
        assertEquals("https://panda.torve.app/u/tok_abc/manifest.json", dto.manifestUrl)
        assertEquals("cfg123", dto.configId)
        assertNull(dto.expiresAt)
        assertNull(dto.managementToken)
    }

    @Test
    fun configResponseDecodesWithManagementToken() {
        val dto = json.decodeFromString<PandaConfigResponse>(
            """
            {
              "config_id": "cfg123",
              "panda_token": "eyJ2ZXJz.abc.xyz",
              "manifest_url": "https://panda.torve.app/u/eyJ2ZXJz.abc.xyz/manifest.json",
              "management_token": "544c8b52aa10a8e89fab28cbb33dc4a0f7d812c9e8b3c64d5e1b2a0fdc981234",
              "management_token_notice": "Save this token now — shown only once."
            }
            """.trimIndent(),
        )

        assertEquals("cfg123", dto.configId)
        assertEquals(
            "544c8b52aa10a8e89fab28cbb33dc4a0f7d812c9e8b3c64d5e1b2a0fdc981234",
            dto.managementToken,
        )
        assertEquals(
            "Save this token now — shown only once.",
            dto.managementTokenNotice,
        )
    }

    @Test
    fun configResponseToStringRedactsTokens() {
        val dto = PandaConfigResponse(
            pandaToken = "tok_super_secret",
            manifestUrl = "https://example/u/tok_super_secret/manifest.json",
            configId = "cfg-xyz",
            managementToken = "mgmt-abcdef-0123-secret",
            managementTokenNotice = "shown once",
        )
        val rendered = dto.toString()
        assertTrue("cfg-xyz" in rendered, "config_id is non-sensitive; must stay visible")
        assertFalse("tok_super_secret" in rendered, "pandaToken leaked into toString")
        assertFalse("mgmt-abcdef-0123-secret" in rendered, "managementToken leaked into toString")
        assertTrue("hasPandaToken=true" in rendered)
        assertTrue("hasManagementToken=true" in rendered)
    }

    @Test
    fun rotateManifestResponseDecodes() {
        val dto = json.decodeFromString<PandaRotateManifestResponse>(
            """
            {
              "panda_token": "new_tok_xyz",
              "manifest_url": "https://panda.torve.app/u/new_tok_xyz/manifest.json"
            }
            """.trimIndent(),
        )
        assertEquals("new_tok_xyz", dto.pandaToken)
        assertEquals(
            "https://panda.torve.app/u/new_tok_xyz/manifest.json",
            dto.manifestUrl,
        )
        assertFalse("new_tok_xyz" in dto.toString(), "rotate manifest token leaked into toString")
    }

    @Test
    fun rotateManagementResponseDecodes() {
        val dto = json.decodeFromString<PandaRotateManagementResponse>(
            """
            {
              "management_token": "freshly_minted_64_hex_token_1234567890abcdef",
              "management_token_notice": "New token — save it now."
            }
            """.trimIndent(),
        )
        assertEquals("freshly_minted_64_hex_token_1234567890abcdef", dto.managementToken)
        assertEquals("New token — save it now.", dto.managementTokenNotice)
        assertFalse(
            "freshly_minted_64_hex_token" in dto.toString(),
            "rotate management token leaked into toString",
        )
    }

    @Test
    fun configRecordDecodes() {
        val dto = json.decodeFromString<PandaConfigRecord>(
            """
            {
              "config_id": "cfg123",
              "config": {
                "version": 2,
                "enabledProviders": ["yts", "eztv"],
                "qualityProfile": "balanced",
                "maxQuality": "1080p",
                "releaseLanguage": "german",
                "debridService": "realdebrid",
                "debridApiKey": "[redacted]"
              },
              "updated_at": "2026-04-19T12:00:00Z"
            }
            """.trimIndent(),
        )

        assertEquals("cfg123", dto.configId)
        assertEquals(listOf("yts", "eztv"), dto.config?.enabledProviders)
        assertEquals("balanced", dto.config?.qualityProfile)
        assertEquals("1080p", dto.config?.maxQuality)
        assertEquals("german", dto.config?.releaseLanguage)
        assertEquals("realdebrid", dto.config?.debridService)
    }

    // ── Error ──

    @Test
    fun errorResponseDecodes() {
        val dto = json.decodeFromString<PandaErrorResponse>(
            """{"code":"invalid_api_key","message":"Invalid Real-Debrid API key"}""",
        )
        assertEquals("invalid_api_key", dto.code)
        assertEquals("Invalid Real-Debrid API key", dto.message)
    }

    @Test
    fun errorResponseHandlesEmpty() {
        val dto = json.decodeFromString<PandaErrorResponse>("""{}""")
        assertEquals("", dto.code)
        assertEquals("", dto.message)
    }

    // ── Config Payload ──

    @Test
    fun configPayloadSerializesCorrectly() {
        val payload = PandaConfigPayload(
            enabledProviders = listOf("yts", "eztv"),
            debridService = "realdebrid",
            debridApiKey = "key123",
            maxQuality = "1080p",
            qualityProfile = "best_quality",
            releaseLanguage = "german",
        )
        val serialized = json.encodeToString(PandaConfigPayload.serializer(), payload)
        assertTrue(serialized.contains("\"debridService\":\"realdebrid\""))
        assertTrue(serialized.contains("\"maxQuality\":\"1080p\""))
        assertTrue(serialized.contains("\"releaseLanguage\":\"german\""))
        assertTrue(serialized.contains("\"yts\""))
    }

    @Test
    fun configPatchSerializesOnlyNonNull() {
        val patch = PandaConfigPatch(
            maxQuality = "720p",
            releaseLanguage = "german",
        )
        val serialized = json.encodeToString(PandaConfigPatch.serializer(), patch)
        assertTrue(serialized.contains("\"maxQuality\":\"720p\""))
        assertTrue(serialized.contains("\"releaseLanguage\":\"german\""))
    }

    @Test
    fun configPayloadSerializesMultiDebridConnections() {
        val payload = PandaConfigPayload(
            debridService = "realdebrid",
            debridApiKey = "rd-key",
            debridConnections = listOf(
                PandaDebridConnection(provider = "realdebrid", apiKey = "rd-key"),
                PandaDebridConnection(provider = "torbox", apiKey = "tb-key"),
            ),
        )
        val serialized = json.encodeToString(PandaConfigPayload.serializer(), payload)
        assertTrue(serialized.contains("\"debridConnections\""))
        assertTrue(serialized.contains("\"provider\":\"realdebrid\""))
        assertTrue(serialized.contains("\"apiKey\":\"tb-key\""))
    }

    @Test
    fun configSecretsDecodeMultiDebridConnections() {
        val dto = json.decodeFromString<PandaConfigSecrets>(
            """
            {
              "config_id": "cfg123",
              "debrid_api_key": "legacy-key",
              "debrid_connections": [
                {"provider": "realdebrid", "api_key": "rd-key", "enabled": true},
                {"provider": "torbox", "api_key": "tb-key", "enabled": true}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("cfg123", dto.configId)
        assertEquals(2, dto.debridConnections.size)
        assertEquals("realdebrid", dto.debridConnections[0].provider)
        assertEquals("tb-key", dto.debridConnections[1].apiKey)
    }

    // ── API Key Request ──

    @Test
    fun apiKeyRequestSerializes() {
        val req = PandaApiKeyRequest(apiKey = "my-key")
        val serialized = json.encodeToString(PandaApiKeyRequest.serializer(), req)
        assertTrue(serialized.contains("\"api_key\":\"my-key\""))
    }

    // ── Device Code Request ──

    @Test
    fun deviceCodeRequestSerializes() {
        val req = PandaDeviceCodeRequest(deviceCode = "dev-code-123")
        val serialized = json.encodeToString(PandaDeviceCodeRequest.serializer(), req)
        assertTrue(serialized.contains("\"device_code\":\"dev-code-123\""))
    }
}
