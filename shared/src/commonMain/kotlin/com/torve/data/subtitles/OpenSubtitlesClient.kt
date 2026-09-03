package com.torve.data.subtitles

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

class OpenSubtitlesClient(
    private val httpClient: HttpClient,
    private val secretStore: IntegrationSecretStore,
) {
    companion object {
        const val BASE_URL = "https://api.opensubtitles.com/api/v1"
        const val USER_AGENT = "Torve v1.0"
    }

    private suspend fun apiKey(): String? =
        secretStore.get(IntegrationSecretKey.OPENSUBTITLES_API_KEY)
            ?.takeIf { it.isNotBlank() }

    fun isConfigured(): Boolean = runCatching {
        kotlinx.coroutines.runBlocking { apiKey() != null }
    }.getOrDefault(false)

    suspend fun isConfiguredAsync(): Boolean = apiKey() != null

    suspend fun searchSubtitles(
        imdbId: String?,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        languages: String? = null,
        releaseQuery: String? = null,
        movieHash: String? = null,
        movieByteSize: Long? = null,
        page: Int = 1,
    ): List<OsSubtitleResult> = searchSubtitlesPage(
        imdbId = imdbId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        languages = languages,
        releaseQuery = releaseQuery,
        movieHash = movieHash,
        movieByteSize = movieByteSize,
        page = page,
    ).subtitles

    suspend fun searchSubtitlesPage(
        imdbId: String?,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        languages: String? = null,
        releaseQuery: String? = null,
        movieHash: String? = null,
        movieByteSize: Long? = null,
        page: Int = 1,
    ): OpenSubtitlesSearchPage {
        val requestedPage = page.coerceAtLeast(1)
        val key = apiKey() ?: return OpenSubtitlesSearchPage(
            subtitles = emptyList(),
            page = requestedPage,
            totalCount = null,
            totalPages = null,
            failure = "Not configured",
        )
        // OpenSubtitles expects the numeric IMDb ID without the "tt" prefix.
        val numericId = imdbId
            ?.removePrefix("tt")
            ?.trimStart('0')
            ?.takeIf(String::isNotBlank)
        if (numericId == null && releaseQuery.isNullOrBlank() && movieHash.isNullOrBlank()) {
            return OpenSubtitlesSearchPage(emptyList(), requestedPage, null, null, "No usable search identity")
        }
        val httpResponse = try {
            httpClient.get("$BASE_URL/subtitles") {
                header("Api-Key", key)
                header("User-Agent", USER_AGENT)
                numericId?.let { parameter("imdb_id", it) }
                seasonNumber?.let { parameter("season_number", it) }
                episodeNumber?.let { parameter("episode_number", it) }
                languages?.let { parameter("languages", it) }
                releaseQuery?.takeIf { it.isNotBlank() }?.let { parameter("query", it) }
                movieHash?.takeIf { it.isNotBlank() }?.let { parameter("moviehash", it) }
                movieByteSize?.takeIf { it > 0L }?.let { parameter("moviebytesize", it) }
                parameter("page", requestedPage)
                // Provider ordering is only a transport concern. Torve re-ranks match-first.
                parameter("order_by", "new_download_count")
                parameter("order_direction", "desc")
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return OpenSubtitlesSearchPage(
                emptyList(), requestedPage, null, null,
                "Request failed (${error::class.simpleName ?: "network"})",
            )
        }
        if (!httpResponse.status.isSuccess()) {
            return OpenSubtitlesSearchPage(
                emptyList(), requestedPage, null, null,
                "HTTP ${httpResponse.status.value}",
            )
        }
        val response = try {
            httpResponse.body<OsSearchResponse>()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return OpenSubtitlesSearchPage(
                emptyList(), requestedPage, null, null,
                "Invalid response (${error::class.simpleName ?: "decode"})",
            )
        }

        val subtitles = response.data.flatMap { item ->
            val attrs = item.attributes
            val (flag, name) = languageInfo(attrs.language)
            attrs.files.filter { it.fileId != 0 }.map { file ->
                OsSubtitleResult(
                    subtitleId = attrs.subtitleId ?: item.id.takeIf(String::isNotBlank),
                    fileId = file.fileId,
                    fileName = file.fileName.ifBlank { attrs.release.ifBlank { "subtitle" } },
                    language = attrs.language,
                    flagEmoji = flag,
                    languageName = name,
                    downloadCount = attrs.downloadCount,
                    recentDownloadCount = attrs.newDownloadCount,
                    fromTrusted = attrs.fromTrusted,
                    hearingImpaired = attrs.hearingImpaired,
                    release = attrs.release,
                    aiTranslated = attrs.aiTranslated,
                    machineTranslated = attrs.machineTranslated,
                    ratings = attrs.ratings,
                    voteCount = attrs.votes,
                    fps = attrs.fps?.takeIf { it > 0.0 },
                    hd = attrs.hd,
                    forced = attrs.foreignPartsOnly,
                    uploadDate = attrs.uploadDate.takeIf(String::isNotBlank),
                    uploaderName = attrs.uploader?.name,
                    uploaderRank = attrs.uploader?.rank,
                    comments = attrs.comments,
                    numberOfCds = attrs.numberOfCds,
                    movieHashMatch = attrs.movieHashMatch,
                    mediaTitle = attrs.featureDetails?.parentTitle ?: attrs.featureDetails?.movieName ?: attrs.featureDetails?.title,
                    mediaYear = attrs.featureDetails?.year,
                    seasonNumber = attrs.featureDetails?.seasonNumber,
                    episodeNumber = attrs.featureDetails?.episodeNumber,
                    mediaImdbId = attrs.featureDetails?.imdbId,
                    parentImdbId = attrs.featureDetails?.parentImdbId,
                )
            }
        }
        return OpenSubtitlesSearchPage(
            subtitles = subtitles,
            page = response.page ?: requestedPage,
            totalCount = response.totalCount,
            totalPages = response.totalPages,
        )
    }

    suspend fun getDownloadUrl(fileId: Int): String? {
        val key = apiKey() ?: return null
        return runCatching {
            httpClient.post("$BASE_URL/download") {
                header("Api-Key", key)
                header("User-Agent", USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody(OsDownloadRequest(fileId = fileId))
            }.body<OsDownloadResponse>().link.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
