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

    suspend fun searchSubtitles(
        imdbId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        languages: String? = null,
    ): List<OsSubtitleResult> {
        val key = apiKey() ?: return emptyList()
        // OpenSubtitles expects numeric IMDB ID without "tt" prefix
        val numericId = imdbId.removePrefix("tt").trimStart('0').ifEmpty { imdbId }
        val response = runCatching {
            httpClient.get("$BASE_URL/subtitles") {
                header("Api-Key", key)
                header("User-Agent", USER_AGENT)
                parameter("imdb_id", numericId)
                seasonNumber?.let { parameter("season_number", it) }
                episodeNumber?.let { parameter("episode_number", it) }
                languages?.let { parameter("languages", it) }
                parameter("order_by", "download_count")
                parameter("order_direction", "desc")
            }.body<OsSearchResponse>()
        }.getOrNull() ?: return emptyList()

        return response.data.mapNotNull { item ->
            val attrs = item.attributes
            val file = attrs.files.firstOrNull() ?: return@mapNotNull null
            if (file.fileId == 0) return@mapNotNull null
            val (flag, name) = languageInfo(attrs.language)
            OsSubtitleResult(
                fileId = file.fileId,
                fileName = file.fileName.ifBlank { attrs.release.ifBlank { "subtitle" } },
                language = attrs.language,
                flagEmoji = flag,
                languageName = name,
                downloadCount = attrs.downloadCount,
                fromTrusted = attrs.fromTrusted,
                hearingImpaired = attrs.hearingImpaired,
                release = attrs.release,
                aiTranslated = attrs.aiTranslated || attrs.machineTranslated,
                ratings = attrs.ratings,
            )
        }
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
