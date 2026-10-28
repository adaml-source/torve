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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class OmdbClient(
    private val httpClient: HttpClient,
    private val prefsRepo: PreferencesRepository,
    private val secretStore: IntegrationSecretStore,
) {
    companion object {
        const val KEY_OMDB_API_KEY = "omdb_api_key"
    }

    private var loggedOmdbDisabled = false
    private val plotLock = Mutex()
    private var plotCredential: String? = null
    private val plots = linkedMapOf<String, Pair<Long, String?>>()

    /** Exact-ID, on-demand full plots. Episode results must confirm the series and episode. */
    suspend fun fetchFullPlot(imdbId: String, type: String, season: Int? = null, episode: Int? = null): String? {
        if (!imdbId.matches(Regex("tt[0-9]+")) || type !in setOf("movie", "series", "episode")) return null
        if (type == "episode" && (season == null || season < 0 || episode == null || episode < 1)) return null
        val key = configuredApiKey()?.takeIf { it.isNotBlank() } ?: return null
        return plotLock.withLock {
            if (plotCredential != key) { plots.clear(); plotCredential = key }
            val identity = "$imdbId:$type:$season:$episode"
            val now = Clock.System.now().toEpochMilliseconds()
            plots[identity]?.takeIf { now - it.first < (if (it.second == null) 60_000L else 43_200_000L) }?.let { return@withLock it.second }
            val plot = try {
                val result: OmdbResponse = httpClient.get("https://www.omdbapi.com/") {
                    parameter("i", imdbId); parameter("apikey", key); parameter("plot", "full")
                    parameter("type", type)
                    if (type == "episode") { parameter("Season", season); parameter("Episode", episode) }
                }.body()
                val matches = result.type == type && if (type == "episode") {
                    result.seriesId == imdbId && result.season?.toIntOrNull() == season && result.episode?.toIntOrNull() == episode
                } else result.imdbId == imdbId
                result.plot?.trim()?.takeIf { result.response == "True" && matches && it.isNotBlank() && !it.equals("N/A", true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            plots[identity] = Clock.System.now().toEpochMilliseconds() to plot
            while (plots.size > 48) plots.remove(plots.keys.first())
            plot
        }
    }

    /**
     * Fetches ratings from OMDB for a given IMDb ID.
     * Returns a partial MediaRatings with IMDb, Rotten Tomatoes, and Metacritic.
     * Returns null if OMDB key not configured or request fails.
     */
    suspend fun fetchRatings(imdbId: String): MediaRatings? {
        val apiKey = configuredApiKey()
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

    suspend fun isConfigured(): Boolean = !configuredApiKey().isNullOrBlank()

    private suspend fun configuredApiKey(): String? =
        secretStore.get(IntegrationSecretKey.OMDB_API_KEY)
            ?: prefsRepo.getString(KEY_OMDB_API_KEY)

    private fun logOmdbDisabled() {
        if (loggedOmdbDisabled) return
        loggedOmdbDisabled = true
        println("TORVE_RATINGS: OMDb disabled; configure OMDB_API_KEY to enable IMDb votes, Rotten Tomatoes critics, and Metacritic ratings from OMDb.")
    }
}

@Serializable
internal data class OmdbResponse(
    @SerialName("Response") val response: String = "False",
    @SerialName("Plot") val plot: String? = null,
    @SerialName("imdbID") val imdbId: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("seriesID") val seriesId: String? = null,
    @SerialName("Season") val season: String? = null,
    @SerialName("Episode") val episode: String? = null,
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
