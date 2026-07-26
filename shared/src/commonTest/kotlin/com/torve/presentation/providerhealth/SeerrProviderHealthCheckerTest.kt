package com.torve.presentation.providerhealth

import com.torve.domain.integrations.MediaLifecycleRequest
import com.torve.domain.integrations.MediaLifecycleService
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.integrations.MediaLifecycleStatus
import com.torve.domain.model.MediaType
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SeerrProviderHealthCheckerTest {
    @Test
    fun `missing credentials are unconfigured without a network call`() = runTest {
        val service = FakeLifecycleService(true)
        val entry = SeerrProviderHealthChecker({ null }, { null }, service).check()

        assertEquals(ProviderHealthStatus.UNCONFIGURED, entry.status)
        assertEquals(ProviderHealthCategory.REQUEST_MANAGER, entry.category)
        assertEquals(0, service.testCalls)
    }

    @Test
    fun `partial credentials are actionable but not marked connected`() = runTest {
        val entry = SeerrProviderHealthChecker(
            serverUrlSource = { "https://seerr.example.test" },
            apiKeySource = { null },
            service = FakeLifecycleService(true),
        ).check()

        assertEquals(ProviderHealthStatus.YELLOW, entry.status)
        assertEquals("Finish setup", entry.nextAction)
    }

    @Test
    fun `successful probe is connected`() = runTest {
        val entry = checker(connectionResult = true).check()
        assertEquals(ProviderHealthStatus.GREEN, entry.status)
    }

    @Test
    fun `failed or throwing probe is red and sanitized`() = runTest {
        val entry = checker(connectionResult = false).check()
        assertEquals(ProviderHealthStatus.RED, entry.status)
        assertEquals("Seerr is unreachable or rejected the API key", entry.message)
    }

    private fun checker(connectionResult: Boolean) = SeerrProviderHealthChecker(
        serverUrlSource = { "https://seerr.example.test" },
        apiKeySource = { "secret-that-must-not-appear" },
        service = FakeLifecycleService(connectionResult),
    )

    private class FakeLifecycleService(private val connectionResult: Boolean) : MediaLifecycleService {
        var testCalls: Int = 0

        override suspend fun isConfigured(): Boolean = true

        override suspend fun testConnection(serverUrl: String, apiKey: String): Boolean {
            testCalls++
            return connectionResult
        }

        override suspend fun getStatus(
            tmdbId: Int,
            mediaType: MediaType,
            seasons: List<Int>,
            is4k: Boolean,
        ) = MediaLifecycleStatus(tmdbId, mediaType, MediaLifecycleState.NOT_REQUESTED)

        override suspend fun request(request: MediaLifecycleRequest) =
            MediaLifecycleStatus(request.tmdbId, request.mediaType, MediaLifecycleState.PENDING_APPROVAL)

        override suspend fun retry(requestId: Int) =
            MediaLifecycleStatus(0, MediaType.MOVIE, MediaLifecycleState.PROCESSING, requestId)
    }
}
