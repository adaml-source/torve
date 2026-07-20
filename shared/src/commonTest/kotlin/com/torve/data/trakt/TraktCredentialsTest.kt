package com.torve.data.trakt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktCredentialsTest {
    @Test
    fun incompleteConfiguredCredentialsFallBackAsOnePair() {
        val client = client()

        client.setCredentials("configured-client", "")

        assertFalse(client.usesCustomCredentials)
        assertEquals(TraktClient.DEFAULT_PUBLIC_CLIENT_ID, client.clientId)
        assertEquals(TraktClient.DEFAULT_PUBLIC_CLIENT_SECRET, client.clientSecret)
    }

    @Test
    fun completeConfiguredCredentialsAreApplied() {
        val client = client()

        client.setCredentials(" configured-client ", " configured-secret ")

        assertTrue(client.usesCustomCredentials)
        assertEquals("configured-client", client.clientId)
        assertEquals("configured-secret", client.clientSecret)
    }

    @Test
    fun packagedCredentialsReplaceTheLegacyFallback() {
        val client = client()

        client.setPackagedCredentials("packaged-client", "packaged-secret")
        client.setCredentials("", "")

        assertTrue(client.usesCustomCredentials)
        assertEquals("packaged-client", client.clientId)
        assertEquals("packaged-secret", client.clientSecret)
    }

    private fun client() = TraktClient(
        httpClient = HttpClient(MockEngine { error("No request expected") }),
        json = Json { ignoreUnknownKeys = true },
    )
}
