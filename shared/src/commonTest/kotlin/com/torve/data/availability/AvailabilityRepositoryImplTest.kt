package com.torve.data.availability

import com.torve.domain.integrations.AvailabilityProvider
import com.torve.domain.model.AvailabilityOffer
import com.torve.domain.model.AvailabilityOfferType
import com.torve.domain.model.AvailabilityResult
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AvailabilityRepositoryImplTest {

    @Test
    fun usesCacheForRepeatedRequests() = runTest {
        val provider = FakeAvailabilityProvider()
        val prefs = InMemoryPreferencesRepository()
        val repo = AvailabilityRepositoryImpl(provider, prefs, Json)

        repo.getAvailability(1, MediaType.MOVIE, "US")
        repo.getAvailability(1, MediaType.MOVIE, "US")

        assertEquals(1, provider.calls)
    }
}

private class FakeAvailabilityProvider : AvailabilityProvider {
    var calls: Int = 0

    override suspend fun getAvailability(tmdbId: Int, mediaType: MediaType, region: String): AvailabilityResult {
        calls += 1
        return AvailabilityResult(
            tmdbId = tmdbId,
            mediaType = mediaType,
            region = region,
            offers = listOf(
                AvailabilityOffer(
                    providerName = "Provider",
                    offerType = AvailabilityOfferType.SUBSCRIPTION,
                    webUrl = "https://example.com",
                ),
            ),
            fetchedAt = 1L,
        )
    }
}

private class InMemoryPreferencesRepository : PreferencesRepository {
    private val map = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = map[key]

    override suspend fun setString(key: String, value: String) {
        map[key] = value
    }

    override suspend fun remove(key: String) {
        map.remove(key)
    }
}

