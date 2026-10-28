package com.torve.domain.integrations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JellyfinAccountConnectionTest {
    @Test
    fun accountPayloadContainsEveryValueNeededToReconnect() {
        val payload = requireNotNull(
            buildJellyfinAccountPayload(
                serverUrl = "http://jellyfin.local:8096",
                apiKey = "secret-key",
                selectedUserId = "user-42",
            ),
        )
        assertEquals("secret-key", payload.credentials.get("api_key"))
        assertEquals("http://jellyfin.local:8096", payload.credentials.get("server_url"))
        assertEquals("user-42", payload.credentials.get("selected_user_id"))
        assertEquals("http://jellyfin.local:8096", payload.config.get("server_url"))
        assertEquals("user-42", payload.config.get("selected_user_id"))
    }

    @Test
    fun restoreAcceptsLegacyConfigServerUrl() {
        val restored = requireNotNull(
            restoreJellyfinAccountConnection(
                credentials = mapOf("api_key" to "secret-key"),
                config = mapOf("server_url" to "https://media.example/"),
            ),
        )

        assertEquals("https://media.example", restored.serverUrl)
        assertEquals("secret-key", restored.apiKey)
        assertNull(restored.selectedUserId)
    }

    @Test
    fun restorePrefersEncryptedCompanionValues() {
        val restored = requireNotNull(
            restoreJellyfinAccountConnection(
                credentials = mapOf(
                    "api_key" to "secret-key",
                    "server_url" to "http://current.local:8096/",
                    "selected_user_id" to "current-user",
                ),
                config = mapOf(
                    "server_url" to "http://stale.local:8096",
                    "selected_user_id" to "stale-user",
                ),
            ),
        )

        assertEquals("http://current.local:8096", restored.serverUrl)
        assertEquals("current-user", restored.selectedUserId)
    }

    @Test
    fun partialOrInvalidConnectionIsRejected() {
        assertNull(buildJellyfinAccountPayload("jellyfin.local", "key", null))
        assertNull(restoreJellyfinAccountConnection(mapOf("api_key" to "key"), emptyMap()))
        assertNull(restoreJellyfinAccountConnection(emptyMap(), mapOf("server_url" to "http://host")))
    }
}
