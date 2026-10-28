package com.torve.data.ratings

import com.torve.domain.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

data class CinemetaImdbRating(val score: Float, val votes: Int?)

/** One public metadata lookup on Detail, never a per-card browsing fan-out. */
class CinemetaImdbRatingsApi(
    private val httpClient: HttpClient,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private data class Cached(val value: CinemetaImdbRating?, val expires: Long)
    private val cache = mutableMapOf<String, Cached>()
    private val mutex = Mutex()

    suspend fun fetch(imdbId: String, type: MediaType): CinemetaImdbRating? = withContext(Dispatchers.Default) {
        if (!Regex("tt[0-9]+").matches(imdbId)) return@withContext null
        val kind = if (type == MediaType.SERIES) "series" else "movie"
        val key = "$kind:$imdbId"
        mutex.withLock {
            cache[key]?.takeIf { it.expires > now() }?.let { return@withLock it.value }
            val rating = withTimeoutOrNull(4_000) {
                try {
                    val response = httpClient.get("https://v3-cinemeta.strem.io/meta/$kind/$imdbId.json")
                    if (!response.status.isSuccess()) return@withTimeoutOrNull null
                    val meta = response.body<CinemetaRatingResponse>().meta ?: return@withTimeoutOrNull null
                    if (meta.id != imdbId) return@withTimeoutOrNull null
                    val score = meta.imdbRating?.content?.toFloatOrNull()
                        ?.takeIf { it.isFinite() && it > 0f && it <= 10f } ?: return@withTimeoutOrNull null
                    val votes = meta.imdbVotes?.content?.replace(",", "")?.toIntOrNull()?.takeIf { it > 0 }
                    CinemetaImdbRating(score, votes)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            if (cache.size >= 256) cache.minByOrNull { it.value.expires }?.key?.let(cache::remove)
            cache[key] = Cached(rating, now() + if (rating == null) 60_000 else 3_600_000)
            rating
        }
    }
}

@Serializable private data class CinemetaRatingResponse(val meta: CinemetaRatingMeta? = null)
@Serializable private data class CinemetaRatingMeta(
    val id: String,
    val imdbRating: JsonPrimitive? = null,
    val imdbVotes: JsonPrimitive? = null,
)
