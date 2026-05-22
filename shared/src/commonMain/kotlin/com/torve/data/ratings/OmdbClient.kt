package com.torve.data.ratings

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaRatings
import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class OmdbClient(
    private val httpClient: HttpClient,
    private val prefsRepo: PreferencesRepository,
    private val secretStore: IntegrationSecretStore,
) {
    companion object {
        const val KEY_OMDB_API_KEY = "omdb_api_key"
    }

    private var loggedOmdbDisabled = false

    /**
     * Fetches ratings from OMDB for a given IMDb ID.
     * Returns a partial MediaRatings with IMDb, Rotten Tomatoes, and Metacritic.
     * Returns null if OMDB key not configured or request fails.
     */
    suspend fun fetchRatings(imdbId: String): MediaRatings? {
        val apiKey = secretStore.get(IntegrationSecretKey.OMDB_API_KEY)
            ?: prefsRepo.getString(KEY_OMDB_API_KEY)
        if (apiKey.isNullOrBlank()) {
            logOmdbDisabled()
            return null
        }
        if (!imdbId.startsWith("tt")) return null

        return runCatching {
            val response: OmdbResponse = httpClient.get("https://www.omdbapi.com/") {
                parameter("i", imdbId)
                parameter("apikey", apiKey)
            }.body()

            if (response.response == "False") return null

            val imdbScore = response.imdbRating?.toFloatOrNull()?.takeIf { it > 0f }
            val imdbVotes = response.imdbVotes
                ?.replace(",", "")
                ?.toIntOrNull()
                ?.takeIf { it > 0 }

            val rtScore = response.ratings
                ?.find { it.source == "Rotten Tomatoes" }
                ?.value
                ?.replace("%", "")
                ?.toIntOrNull()
                ?.takeIf { it > 0 }

            val metacriticScore = response.metascore
                ?.toIntOrNull()
                ?.takeIf { it > 0 }

            // Only return if we got at least one valid rating
            if (imdbScore == null && rtScore == null && metacriticScore == null) return null

            MediaRatings(
                imdbScore = imdbScore,
                imdbVotes = imdbVotes,
                rottenTomatoesScore = rtScore,
                metacriticScore = metacriticScore,
            )
        }.getOrNull()
    }

    private fun logOmdbDisabled() {
        if (loggedOmdbDisabled) return
        loggedOmdbDisabled = true
        println("TORVE_RATINGS: OMDb disabled; configure OMDB_API_KEY to enable IMDb votes, Rotten Tomatoes critics, and Metacritic ratings from OMDb.")
    }
}

@Serializable
internal data class OmdbResponse(
    @SerialName("Response") val response: String = "False",
    @SerialName("imdbRating") val imdbRating: String? = null,
    @SerialName("imdbVotes") val imdbVotes: String? = null,
    @SerialName("Metascore") val metascore: String? = null,
    @SerialName("Ratings") val ratings: List<OmdbResponseRating>? = null,
)

@Serializable
internal data class OmdbResponseRating(
    @SerialName("Source") val source: String,
    @SerialName("Value") val value: String,
)
