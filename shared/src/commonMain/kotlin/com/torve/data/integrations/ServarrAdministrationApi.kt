package com.torve.data.integrations

import com.torve.domain.integrations.AutomationAddMediaRequest
import com.torve.domain.integrations.AutomationAdminErrorCode
import com.torve.domain.integrations.AutomationAdminResult
import com.torve.domain.integrations.AutomationCapability
import com.torve.domain.integrations.AutomationIndexer
import com.torve.domain.integrations.AutomationIndexerCreateRequest
import com.torve.domain.integrations.AutomationIndexerProtocol
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationLibraryItem
import com.torve.domain.integrations.AutomationMediaKind
import com.torve.domain.integrations.AutomationQualityProfile
import com.torve.domain.integrations.AutomationQueueItem
import com.torve.domain.integrations.AutomationQueueRemoval
import com.torve.domain.integrations.AutomationRelease
import com.torve.domain.integrations.AutomationReleaseQuery
import com.torve.domain.integrations.AutomationRootFolder
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.AutomationSubtitleCandidate
import com.torve.domain.integrations.AutomationSubtitleDeleteRequest
import com.torve.domain.integrations.AutomationSubtitleDownloadRequest
import com.torve.domain.integrations.AutomationSubtitleKind
import com.torve.domain.integrations.AutomationSubtitleTarget
import com.torve.domain.integrations.TdarrAutomation
import com.torve.domain.integrations.TdarrJob
import com.torve.domain.integrations.TdarrJobAction
import com.torve.domain.integrations.TdarrJobActionRequest
import com.torve.domain.integrations.TdarrLibrary
import com.torve.domain.integrations.TdarrNode
import com.torve.domain.integrations.TdarrOverview
import com.torve.domain.integrations.TdarrRunAutomationRequest
import com.torve.domain.integrations.TdarrScanRequest
import com.torve.domain.integrations.TdarrWorker
import com.torve.domain.integrations.TdarrWorkerAction
import com.torve.domain.integrations.TdarrWorkerLimitChange
import com.torve.domain.integrations.TdarrWorkerLimitRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Version-aware administration calls for the five supported automation apps.
 * This class never logs response bodies and converts all failures to neutral,
 * typed results before they reach presentation code.
 */
internal class ServarrAdministrationApi(private val httpClient: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    fun capabilities(instance: AutomationInstance): Set<AutomationCapability> = when (instance.serviceType) {
        AutomationServiceType.SONARR,
        AutomationServiceType.RADARR -> setOf(
            AutomationCapability.LIBRARY_LOOKUP,
            AutomationCapability.LIBRARY_ADD,
            AutomationCapability.RELEASE_SEARCH,
            AutomationCapability.RELEASE_GRAB,
            AutomationCapability.QUEUE_READ,
            AutomationCapability.QUEUE_CONTROL,
            AutomationCapability.INDEXER_READ,
            AutomationCapability.INDEXER_CONTROL,
            AutomationCapability.INDEXER_CREATE,
        )
        AutomationServiceType.PROWLARR -> setOf(
            AutomationCapability.INDEXER_READ,
            AutomationCapability.INDEXER_CONTROL,
            AutomationCapability.INDEXER_CREATE,
        )
        AutomationServiceType.BAZARR -> setOf(
            AutomationCapability.SUBTITLE_WANTED,
            AutomationCapability.SUBTITLE_SEARCH,
            AutomationCapability.SUBTITLE_CONTROL,
        )
        AutomationServiceType.TDARR -> setOf(
            AutomationCapability.TDARR_LIBRARIES,
            AutomationCapability.TDARR_NODES,
            AutomationCapability.TDARR_JOBS,
            AutomationCapability.TDARR_CONTROL,
        )
    }

    suspend fun lookupMedia(
        instance: AutomationInstance,
        apiKey: String,
        query: String,
    ): AutomationAdminResult<List<AutomationLibraryItem>> {
        if (query.trim().length < 2) return invalid("Enter at least two search characters")
        val path = when (instance.serviceType) {
            AutomationServiceType.SONARR -> "/api/v3/series/lookup"
            AutomationServiceType.RADARR -> "/api/v3/movie/lookup"
            else -> return unsupported()
        }
        return requestJson(instance, apiKey, RequestKind.GET, path, mapOf("term" to query.trim()))
            .mapValue { element -> element.rows().mapNotNull { parseLibraryItem(it, instance.serviceType, lookup = true) } }
    }

    suspend fun listLibrary(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationAdminResult<List<AutomationLibraryItem>> {
        val path = when (instance.serviceType) {
            AutomationServiceType.SONARR -> "/api/v3/series"
            AutomationServiceType.RADARR -> "/api/v3/movie"
            else -> return unsupported()
        }
        return requestJson(instance, apiKey, RequestKind.GET, path)
            .mapValue { it.rows().mapNotNull { row -> parseLibraryItem(row, instance.serviceType, lookup = false) } }
    }

    suspend fun qualityProfiles(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationAdminResult<List<AutomationQualityProfile>> {
        if (!instance.serviceType.isSonarrOrRadarr()) return unsupported()
        return requestJson(instance, apiKey, RequestKind.GET, "/api/v3/qualityprofile")
            .mapValue { element ->
                element.rows().mapNotNull { row ->
                    val id = row.int("id") ?: return@mapNotNull null
                    AutomationQualityProfile(id, row.string("name") ?: "Profile $id")
                }
            }
    }

    suspend fun rootFolders(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationAdminResult<List<AutomationRootFolder>> {
        if (!instance.serviceType.isSonarrOrRadarr()) return unsupported()
        return requestJson(instance, apiKey, RequestKind.GET, "/api/v3/rootfolder")
            .mapValue { element ->
                element.rows().mapNotNull { row ->
                    val id = row.int("id") ?: return@mapNotNull null
                    val path = row.string("path") ?: return@mapNotNull null
                    AutomationRootFolder(id, path, row.long("freeSpace"))
                }
            }
    }

    suspend fun addMedia(
        instance: AutomationInstance,
        apiKey: String,
        request: AutomationAddMediaRequest,
    ): AutomationAdminResult<AutomationLibraryItem> {
        if (!instance.serviceType.isSonarrOrRadarr()) return unsupported()
        if (request.qualityProfileId <= 0 || request.rootFolderPath.isBlank()) {
            return invalid("Select a quality profile and root folder")
        }
        val externalId = request.item.externalId ?: return invalid("The selected item has no provider identifier")
        val body = buildJsonObject {
            put("title", request.item.title)
            request.item.year?.let { put("year", it) }
            put("qualityProfileId", request.qualityProfileId)
            put("rootFolderPath", request.rootFolderPath)
            put("monitored", request.monitor)
            if (instance.serviceType == AutomationServiceType.SONARR) {
                put("tvdbId", externalId)
                put("seriesType", "standard")
                put("seasonFolder", true)
                putJsonObject("addOptions") {
                    put("monitor", if (request.monitor) "all" else "none")
                    put("searchForMissingEpisodes", request.searchOnAdd)
                    put("searchForCutoffUnmetEpisodes", false)
                }
            } else {
                put("tmdbId", externalId)
                putJsonObject("addOptions") { put("searchForMovie", request.searchOnAdd) }
            }
        }
        val path = if (instance.serviceType == AutomationServiceType.SONARR) "/api/v3/series" else "/api/v3/movie"
        return requestJson(instance, apiKey, RequestKind.POST, path, body = body)
            .mapValue { element ->
                parseLibraryItem(element.jsonObjectOrEmpty(), instance.serviceType, lookup = false)
                    ?: request.item
            }
    }

    suspend fun interactiveSearch(
        instance: AutomationInstance,
        apiKey: String,
        query: AutomationReleaseQuery,
    ): AutomationAdminResult<List<AutomationRelease>> {
        if (!instance.serviceType.isSonarrOrRadarr()) return unsupported()
        if (query.mediaId <= 0) return invalid("Select a library item first")
        val params = if (instance.serviceType == AutomationServiceType.SONARR) {
            val episodeId = query.episodeId
                ?: return invalid("Choose a specific Sonarr episode before finding releases")
            mapOf("episodeId" to episodeId.toString())
        } else {
            mapOf("movieId" to query.mediaId.toString())
        }
        return requestJson(instance, apiKey, RequestKind.GET, "/api/v3/release", params)
            .mapValue { element -> element.rows().mapNotNull(::parseRelease) }
    }

    /**
     * Starts Sonarr's automatic search for the series' monitored missing episodes.
     * A series-only GET /release is not equivalent: Sonarr treats that request as
     * the general RSS feed unless a season or episode is supplied.
     */
    suspend fun searchMissingEpisodes(
        instance: AutomationInstance,
        apiKey: String,
        seriesId: Int,
        monitorRegularSeasons: Boolean = false,
    ): AutomationAdminResult<Unit> {
        if (instance.serviceType != AutomationServiceType.SONARR) return unsupported()
        if (seriesId <= 0) return invalid("Select a managed series first")
        if (monitorRegularSeasons) {
            when (val monitorResult = enableRegularSeasons(instance, apiKey, seriesId)) {
                is AutomationAdminResult.Failure -> return monitorResult
                is AutomationAdminResult.Success -> Unit
            }
        }
        val body = buildJsonObject {
            put("name", "SeriesSearch")
            put("seriesId", seriesId)
        }
        return requestJson(instance, apiKey, RequestKind.POST, "/api/v3/command", body = body).toUnit()
    }

    private suspend fun enableRegularSeasons(
        instance: AutomationInstance,
        apiKey: String,
        seriesId: Int,
    ): AutomationAdminResult<Unit> {
        val currentResult = requestJson(instance, apiKey, RequestKind.GET, "/api/v3/series/$seriesId")
        val current = when (currentResult) {
            is AutomationAdminResult.Failure -> return currentResult
            is AutomationAdminResult.Success -> currentResult.value as? JsonObject
                ?: return invalid("Sonarr returned an invalid series record")
        }
        val updatedSeasons = current.array("seasons")?.map { element ->
            val season = element as? JsonObject ?: return@map element
            if ((season.int("seasonNumber") ?: 0) <= 0) return@map element
            JsonObject(season.toMutableMap().apply { put("monitored", JsonPrimitive(true)) })
        }
        val updated = JsonObject(current.toMutableMap().apply {
            put("monitored", JsonPrimitive(true))
            put("monitorNewItems", JsonPrimitive("all"))
            updatedSeasons?.let { put("seasons", JsonArray(it)) }
        })
        return requestJson(
            instance,
            apiKey,
            RequestKind.PUT,
            "/api/v3/series/$seriesId",
            body = updated,
        ).toUnit()
    }

    suspend fun grabRelease(
        instance: AutomationInstance,
        apiKey: String,
        release: AutomationRelease,
    ): AutomationAdminResult<Unit> {
        if (!instance.serviceType.isSonarrOrRadarr()) return unsupported()
        if (release.guid.isBlank() || release.indexerId <= 0) return invalid("This release cannot be grabbed")
        val body = buildJsonObject {
            put("guid", release.guid)
            put("indexerId", release.indexerId)
        }
        return requestJson(instance, apiKey, RequestKind.POST, "/api/v3/release", body = body).toUnit()
    }

    suspend fun queue(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationAdminResult<List<AutomationQueueItem>> {
        if (!instance.serviceType.isSonarrOrRadarr()) return unsupported()
        val params = mapOf("page" to "1", "pageSize" to "200", "includeUnknownSeriesItems" to "true")
        return requestJson(instance, apiKey, RequestKind.GET, "/api/v3/queue", params)
            .mapValue { element -> element.rows().mapNotNull(::parseQueueItem) }
    }

    suspend fun removeQueueItem(
        instance: AutomationInstance,
        apiKey: String,
        queueId: Long,
        removal: AutomationQueueRemoval,
    ): AutomationAdminResult<Unit> {
        if (!instance.serviceType.isSonarrOrRadarr()) return unsupported()
        if (queueId <= 0) return invalid("Invalid queue item")
        val params = mapOf(
            "removeFromClient" to removal.removeFromDownloadClient.toString(),
            "blocklist" to removal.blocklistRelease.toString(),
            "skipRedownload" to (!removal.searchAgain).toString(),
        )
        return requestJson(instance, apiKey, RequestKind.DELETE, "/api/v3/queue/$queueId", params).toUnit()
    }

    suspend fun retryQueueItem(
        instance: AutomationInstance,
        apiKey: String,
        queueId: Long,
    ): AutomationAdminResult<Unit> {
        if (!instance.serviceType.isSonarrOrRadarr()) return unsupported()
        if (queueId <= 0) return invalid("Invalid queue item")
        return requestJson(instance, apiKey, RequestKind.POST, "/api/v3/queue/grab/$queueId").toUnit()
    }

    suspend fun indexers(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationAdminResult<List<AutomationIndexer>> {
        val base = instance.serviceType.indexerApiBase() ?: return unsupported()
        return requestJson(instance, apiKey, RequestKind.GET, "$base/indexer")
            .mapValue { element -> element.rows().mapNotNull(::parseIndexer) }
    }

    suspend fun createIndexer(
        instance: AutomationInstance,
        apiKey: String,
        request: AutomationIndexerCreateRequest,
    ): AutomationAdminResult<AutomationIndexer> {
        val base = instance.serviceType.indexerApiBase() ?: return unsupported()
        if (request.name.isBlank() || request.baseUrl.isBlank() ||
            !(request.baseUrl.startsWith("http://") || request.baseUrl.startsWith("https://"))
        ) {
            return invalid("Enter an indexer name and http(s) URL")
        }
        val schemaResult = requestJson(instance, apiKey, RequestKind.GET, "$base/indexer/schema")
        val schemas = (schemaResult as? AutomationAdminResult.Success)?.value?.rows()
            ?: return schemaResult.failureOrInvalid()
        val implementation = if (request.protocol == AutomationIndexerProtocol.TORRENT) "Torznab" else "Newznab"
        val preferredName = if (request.protocol == AutomationIndexerProtocol.TORRENT) "Generic Torznab" else "Generic Newznab"
        val schema = schemas.firstOrNull { it.string("name") == preferredName }
            ?: schemas.firstOrNull { it.string("implementation") == implementation }
            ?: return invalid("This service does not expose a compatible generic indexer")
        val configuredFields = schema.array("fields")?.map { element ->
            val field = element as? JsonObject ?: return@map element
            val value = when (field.string("name")) {
                "baseUrl" -> JsonPrimitive(request.baseUrl.trimEnd('/'))
                "apiPath" -> JsonPrimitive(request.apiPath.ifBlank { "/api" })
                "apiKey" -> JsonPrimitive(request.apiKey.trim())
                "additionalParameters" -> JsonPrimitive(request.additionalParameters.trim())
                "torrentBaseSettings.appMinimumSeeders" -> JsonPrimitive(request.minimumSeeders.coerceAtLeast(0))
                else -> null
            }
            if (value == null) field else JsonObject(field + ("value" to value))
        }.orEmpty()
        val payload = JsonObject(
            schema + mapOf(
                "name" to JsonPrimitive(request.name.trim()),
                "enableRss" to JsonPrimitive(true),
                "enableAutomaticSearch" to JsonPrimitive(true),
                "enableInteractiveSearch" to JsonPrimitive(true),
                "priority" to JsonPrimitive(request.priority.coerceIn(1, 50)),
                "fields" to JsonArray(configuredFields),
            ),
        )
        return requestJson(instance, apiKey, RequestKind.POST, "$base/indexer", body = payload)
            .mapValue { element -> parseIndexer(element.jsonObjectOrEmpty()) ?: parseIndexer(payload)!! }
    }

    suspend fun testIndexer(
        instance: AutomationInstance,
        apiKey: String,
        indexerId: Int,
    ): AutomationAdminResult<Unit> {
        val base = instance.serviceType.indexerApiBase() ?: return unsupported()
        val current = requestJson(instance, apiKey, RequestKind.GET, "$base/indexer/$indexerId")
        val body = (current as? AutomationAdminResult.Success)?.value?.jsonObjectOrEmpty()
            ?: return current.failureOrInvalid()
        return requestJson(instance, apiKey, RequestKind.POST, "$base/indexer/test", body = body).toUnit()
    }

    suspend fun setIndexerEnabled(
        instance: AutomationInstance,
        apiKey: String,
        indexerId: Int,
        enabled: Boolean,
    ): AutomationAdminResult<AutomationIndexer> {
        val base = instance.serviceType.indexerApiBase() ?: return unsupported()
        val current = requestJson(instance, apiKey, RequestKind.GET, "$base/indexer/$indexerId")
        val raw = (current as? AutomationAdminResult.Success)?.value?.jsonObjectOrEmpty()
            ?: return current.failureOrInvalid()
        val updates = mutableMapOf<String, JsonElement>()
        if ("enable" in raw) updates["enable"] = JsonPrimitive(enabled)
        listOf("enableRss", "enableAutomaticSearch", "enableInteractiveSearch").forEach { key ->
            if (key in raw) updates[key] = JsonPrimitive(enabled)
        }
        if (updates.isEmpty()) return invalid("This indexer does not expose an enable switch")
        val updated = JsonObject(raw + updates)
        return requestJson(instance, apiKey, RequestKind.PUT, "$base/indexer/$indexerId", body = updated)
            .mapValue { element -> parseIndexer(element.jsonObjectOrEmpty()) ?: parseIndexer(updated)!! }
    }

    suspend fun deleteIndexer(
        instance: AutomationInstance,
        apiKey: String,
        indexerId: Int,
    ): AutomationAdminResult<Unit> {
        val base = instance.serviceType.indexerApiBase() ?: return unsupported()
        return requestJson(instance, apiKey, RequestKind.DELETE, "$base/indexer/$indexerId").toUnit()
    }

    suspend fun wantedSubtitles(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationAdminResult<List<AutomationSubtitleTarget>> {
        if (instance.serviceType != AutomationServiceType.BAZARR) return unsupported()
        val episodes = requestJson(instance, apiKey, RequestKind.GET, "/api/episodes/wanted", mapOf("start" to "0", "length" to "200"))
        val movies = requestJson(instance, apiKey, RequestKind.GET, "/api/movies/wanted", mapOf("start" to "0", "length" to "200"))
        if (episodes is AutomationAdminResult.Failure) return episodes
        if (movies is AutomationAdminResult.Failure) return movies
        val episodeRows = (episodes as AutomationAdminResult.Success).value.rows().mapNotNull { row ->
            val episodeId = row.intAny("sonarrEpisodeId", "episodeId") ?: return@mapNotNull null
            AutomationSubtitleTarget(
                kind = AutomationSubtitleKind.EPISODE,
                mediaId = episodeId,
                seriesId = row.intAny("sonarrSeriesId", "seriesId"),
                title = row.stringAny("title", "episodeTitle", "seriesTitle") ?: "Episode $episodeId",
                missingLanguages = row.languageList("missing_subtitles", "missingSubtitles"),
            )
        }
        val movieRows = (movies as AutomationAdminResult.Success).value.rows().mapNotNull { row ->
            val movieId = row.intAny("radarrId", "radarrid") ?: return@mapNotNull null
            AutomationSubtitleTarget(
                kind = AutomationSubtitleKind.MOVIE,
                mediaId = movieId,
                title = row.stringAny("title", "movieTitle") ?: "Movie $movieId",
                missingLanguages = row.languageList("missing_subtitles", "missingSubtitles"),
            )
        }
        return AutomationAdminResult.Success(episodeRows + movieRows)
    }

    suspend fun searchSubtitles(
        instance: AutomationInstance,
        apiKey: String,
        target: AutomationSubtitleTarget,
    ): AutomationAdminResult<List<AutomationSubtitleCandidate>> {
        if (instance.serviceType != AutomationServiceType.BAZARR) return unsupported()
        val path: String
        val params: Map<String, String>
        if (target.kind == AutomationSubtitleKind.EPISODE) {
            path = "/api/providers/episodes"
            params = mapOf("episodeid" to target.mediaId.toString())
        } else {
            path = "/api/providers/movies"
            params = mapOf("radarrid" to target.mediaId.toString())
        }
        return requestJson(instance, apiKey, RequestKind.GET, path, params)
            .mapValue { element -> element.rows().mapNotNull(::parseSubtitleCandidate) }
    }

    suspend fun downloadSubtitle(
        instance: AutomationInstance,
        apiKey: String,
        request: AutomationSubtitleDownloadRequest,
    ): AutomationAdminResult<Unit> {
        if (instance.serviceType != AutomationServiceType.BAZARR) return unsupported()
        val candidate = request.candidate
        if (candidate.selectionToken.isBlank() || candidate.provider.isBlank()) return invalid("Invalid subtitle selection")
        val common = mutableMapOf(
            "hi" to candidate.hearingImpaired.toString(),
            "forced" to candidate.forced.toString(),
            "original_format" to candidate.originalFormat.toString(),
            "provider" to candidate.provider,
            "subtitle" to candidate.selectionToken,
        )
        val path = if (request.target.kind == AutomationSubtitleKind.EPISODE) {
            val seriesId = request.target.seriesId ?: return invalid("Series identifier is missing")
            common["seriesid"] = seriesId.toString()
            common["episodeid"] = request.target.mediaId.toString()
            "/api/providers/episodes"
        } else {
            common["radarrid"] = request.target.mediaId.toString()
            "/api/providers/movies"
        }
        return requestJson(instance, apiKey, RequestKind.POST, path, common).toUnit()
    }

    suspend fun searchMissingSubtitle(
        instance: AutomationInstance,
        apiKey: String,
        target: AutomationSubtitleTarget,
        language: String,
        forced: Boolean,
        hearingImpaired: Boolean,
    ): AutomationAdminResult<Unit> {
        if (instance.serviceType != AutomationServiceType.BAZARR) return unsupported()
        if (language.isBlank()) return invalid("Select a subtitle language")
        val params = mutableMapOf(
            "language" to language,
            "forced" to forced.toString(),
            "hi" to hearingImpaired.toString(),
        )
        val path = if (target.kind == AutomationSubtitleKind.EPISODE) {
            params["seriesid"] = (target.seriesId ?: return invalid("Series identifier is missing")).toString()
            params["episodeid"] = target.mediaId.toString()
            "/api/episodes/subtitles"
        } else {
            params["radarrid"] = target.mediaId.toString()
            "/api/movies/subtitles"
        }
        return requestJson(instance, apiKey, RequestKind.PATCH, path, params).toUnit()
    }

    suspend fun deleteSubtitle(
        instance: AutomationInstance,
        apiKey: String,
        request: AutomationSubtitleDeleteRequest,
    ): AutomationAdminResult<Unit> {
        if (instance.serviceType != AutomationServiceType.BAZARR) return unsupported()
        if (request.path.isBlank() || request.language.isBlank()) return invalid("Select a subtitle to delete")
        val params = mutableMapOf(
            "language" to request.language,
            "path" to request.path,
            "forced" to request.forced.toString(),
            "hi" to request.hearingImpaired.toString(),
        )
        val path = if (request.target.kind == AutomationSubtitleKind.EPISODE) {
            params["seriesid"] = (request.target.seriesId ?: return invalid("Series identifier is missing")).toString()
            params["episodeid"] = request.target.mediaId.toString()
            "/api/episodes/subtitles"
        } else {
            params["radarrid"] = request.target.mediaId.toString()
            "/api/movies/subtitles"
        }
        return requestJson(instance, apiKey, RequestKind.DELETE, path, params).toUnit()
    }

    suspend fun tdarrOverview(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationAdminResult<TdarrOverview> {
        if (instance.serviceType != AutomationServiceType.TDARR) return unsupported()
        val libraries = tdarrCollection(instance, apiKey, "LibrarySettingsJSONDB")
        val jobs = tdarrStaged(instance, apiKey)
        val automations = tdarrCollection(instance, apiKey, "AutomationsJSONDB")
        val nodes = requestJson(instance, apiKey, RequestKind.GET, "/api/v2/get-nodes")
        listOf(libraries, jobs, automations, nodes).firstOrNull { it is AutomationAdminResult.Failure }
            ?.let { return it.failureOrInvalid() }
        return AutomationAdminResult.Success(
            TdarrOverview(
                libraries = (libraries as AutomationAdminResult.Success).value.rows().mapNotNull(::parseTdarrLibrary),
                nodes = parseTdarrNodes((nodes as AutomationAdminResult.Success).value),
                jobs = (jobs as AutomationAdminResult.Success).value.rows().mapNotNull(::parseTdarrJob),
                automations = (automations as AutomationAdminResult.Success).value.rows().mapNotNull(::parseTdarrAutomation),
            ),
        )
    }

    suspend fun actOnTdarrJob(
        instance: AutomationInstance,
        apiKey: String,
        request: TdarrJobActionRequest,
    ): AutomationAdminResult<Unit> {
        if (instance.serviceType != AutomationServiceType.TDARR) return unsupported()
        if (request.jobId.isBlank()) return invalid("Select a Tdarr job")
        val staged = when (val result = tdarrStaged(instance, apiKey)) {
            is AutomationAdminResult.Failure -> return result
            is AutomationAdminResult.Success -> result.value.rows()
        }
        val row = staged.firstOrNull { tdarrJobId(it) == request.jobId }
            ?: return invalid("The Tdarr job is no longer staged; refresh and try again")
        val verdict = when (request.action) {
            TdarrJobAction.ACCEPT -> "accept"
            TdarrJobAction.RETRY -> "retry"
            TdarrJobAction.REQUEUE -> "reset"
            TdarrJobAction.SKIP -> "skip"
            TdarrJobAction.REVIEWED -> "reviewed"
        }
        val body = buildJsonObject {
            putJsonObject("data") {
                put("obj", row)
                put("verdict", verdict)
            }
        }
        return requestJson(instance, apiKey, RequestKind.POST, "/api/v2/transcode-user-verdict", body = body).toUnit()
    }

    suspend fun scanTdarrLibrary(
        instance: AutomationInstance,
        apiKey: String,
        request: TdarrScanRequest,
    ): AutomationAdminResult<Unit> {
        if (instance.serviceType != AutomationServiceType.TDARR) return unsupported()
        if (request.libraryId.isBlank()) return invalid("Select a Tdarr library")
        val sourcePath = request.path?.takeIf { it.isNotBlank() } ?: run {
            when (val libraries = tdarrCollection(instance, apiKey, "LibrarySettingsJSONDB")) {
                is AutomationAdminResult.Failure -> return libraries
                is AutomationAdminResult.Success -> libraries.value.rows()
                    .firstOrNull { it.stringAny("_id", "id", "DB") == request.libraryId }
                    ?.stringAny("folder", "source")
                    ?.takeIf { it.isNotBlank() }
                    ?: return invalid("The Tdarr library source folder is unavailable")
            }
        }
        val body = buildJsonObject {
            putJsonObject("data") {
                putJsonObject("scanConfig") {
                    put("dbID", request.libraryId)
                    put("mode", request.mode)
                    put("arrayOrPath", sourcePath)
                }
            }
        }
        return requestJson(instance, apiKey, RequestKind.POST, "/api/v2/scan-files", body = body).toUnit()
    }

    suspend fun runTdarrAutomation(
        instance: AutomationInstance,
        apiKey: String,
        request: TdarrRunAutomationRequest,
    ): AutomationAdminResult<Unit> {
        if (instance.serviceType != AutomationServiceType.TDARR) return unsupported()
        if (request.automationId.isBlank()) return invalid("Select a Tdarr automation")
        val body = buildJsonObject {
            putJsonObject("data") {
                put("configId", request.automationId)
                putJsonObject("payload") { }
                if (request.libraryIds.isNotEmpty()) {
                    putJsonArray("libraryIds") { request.libraryIds.forEach { add(JsonPrimitive(it)) } }
                }
                if (request.nodeIds.isNotEmpty()) {
                    putJsonArray("targetNodeIds") { request.nodeIds.forEach { add(JsonPrimitive(it)) } }
                }
                put("executeImmediately", request.executeImmediately)
                put("bypassWorkerLimits", false)
                put("bypassStagedFileLimit", false)
            }
        }
        return requestJson(instance, apiKey, RequestKind.POST, "/api/v2/run-automation", body = body).toUnit()
    }

    suspend fun cancelTdarrWorker(
        instance: AutomationInstance,
        apiKey: String,
        action: TdarrWorkerAction,
    ): AutomationAdminResult<Unit> {
        if (instance.serviceType != AutomationServiceType.TDARR) return unsupported()
        if (action.nodeId.isBlank() || action.workerId.isBlank()) return invalid("Select a running worker")
        val body = buildJsonObject {
            putJsonObject("data") {
                put("nodeID", action.nodeId)
                put("workerID", action.workerId)
                put("cause", action.cause.take(160))
            }
        }
        return requestJson(instance, apiKey, RequestKind.POST, "/api/v2/cancel-worker-item", body = body).toUnit()
    }

    suspend fun changeTdarrWorkerLimit(
        instance: AutomationInstance,
        apiKey: String,
        request: TdarrWorkerLimitRequest,
    ): AutomationAdminResult<Unit> {
        if (instance.serviceType != AutomationServiceType.TDARR) return unsupported()
        val allowed = setOf("healthcheckcpu", "healthcheckgpu", "transcodecpu", "transcodegpu")
        if (request.nodeId.isBlank() || request.workerType !in allowed) return invalid("Invalid worker limit selection")
        val body = buildJsonObject {
            putJsonObject("data") {
                put("nodeID", request.nodeId)
                put("process", if (request.change == TdarrWorkerLimitChange.INCREASE) "increase" else "decrease")
                put("workerType", request.workerType)
            }
        }
        return requestJson(instance, apiKey, RequestKind.POST, "/api/v2/alter-worker-limit", body = body).toUnit()
    }

    private suspend fun tdarrCollection(
        instance: AutomationInstance,
        apiKey: String,
        collection: String,
    ): AutomationAdminResult<JsonElement> {
        val body = buildJsonObject {
            putJsonObject("data") {
                put("collection", collection)
                put("mode", "getAll")
            }
        }
        return requestJson(instance, apiKey, RequestKind.POST, "/api/v2/cruddb", body = body)
    }

    private suspend fun tdarrStaged(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationAdminResult<JsonElement> {
        val body = buildJsonObject {
            putJsonObject("data") {
                put("start", 0)
                put("pageSize", 500)
                putJsonArray("filters") { }
                putJsonArray("sorts") { }
                putJsonObject("opts") { }
            }
        }
        return requestJson(instance, apiKey, RequestKind.POST, "/api/v2/client/staged", body = body)
    }

    private suspend fun requestJson(
        instance: AutomationInstance,
        apiKey: String,
        kind: RequestKind,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
    ): AutomationAdminResult<JsonElement> = runCatching {
        val url = "${instance.serverUrl.trimEnd('/')}$path"
        val response = when (kind) {
            RequestKind.GET -> httpClient.get(url) { configure(instance, apiKey, query) }
            RequestKind.POST -> httpClient.post(url) { configure(instance, apiKey, query, body) }
            RequestKind.PUT -> httpClient.put(url) { configure(instance, apiKey, query, body) }
            RequestKind.PATCH -> httpClient.post(url) {
                method = io.ktor.http.HttpMethod.Patch
                configure(instance, apiKey, query, body)
            }
            RequestKind.DELETE -> httpClient.delete(url) { configure(instance, apiKey, query, body) }
        }
        response.toAdminResult()
    }.getOrElse {
        AutomationAdminResult.Failure(
            AutomationAdminErrorCode.UNREACHABLE,
            "The automation service could not be reached",
            retryable = true,
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.configure(
        instance: AutomationInstance,
        apiKey: String,
        query: Map<String, String>,
        body: JsonElement? = null,
    ) {
        val headerName = automationProbeSpec(instance.serviceType).apiKeyHeader
            ?: if (instance.serviceType == AutomationServiceType.TDARR && apiKey.isNotBlank()) "x-api-key" else null
        headerName?.let { header(it, apiKey.trim()) }
        accept(ContentType.Application.Json)
        query.forEach { (name, value) -> parameter(name, value) }
        body?.let {
            contentType(ContentType.Application.Json)
            setBody(it)
        }
    }

    private suspend fun HttpResponse.toAdminResult(): AutomationAdminResult<JsonElement> {
        if (!status.isSuccess()) return statusFailure(status)
        if (status == HttpStatusCode.NoContent) return AutomationAdminResult.Success(JsonNull)
        val text = bodyAsText()
        if (text.isBlank()) return AutomationAdminResult.Success(JsonNull)
        return runCatching { AutomationAdminResult.Success(json.parseToJsonElement(text)) }
            .getOrElse {
                AutomationAdminResult.Failure(
                    AutomationAdminErrorCode.INVALID_RESPONSE,
                    "The automation service returned an unreadable response",
                    retryable = true,
                )
            }
    }

    private fun statusFailure(status: HttpStatusCode): AutomationAdminResult.Failure = when (status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
            AutomationAdminResult.Failure(AutomationAdminErrorCode.UNAUTHORIZED, "The API key was rejected", false)
        HttpStatusCode.NotFound ->
            AutomationAdminResult.Failure(AutomationAdminErrorCode.NOT_FOUND, "The requested automation item was not found", false)
        HttpStatusCode.Conflict ->
            AutomationAdminResult.Failure(AutomationAdminErrorCode.CONFLICT, "The automation service rejected this conflicting action", false)
        HttpStatusCode.TooManyRequests ->
            AutomationAdminResult.Failure(AutomationAdminErrorCode.RATE_LIMITED, "The automation service is rate limited", true)
        in HttpStatusCode.InternalServerError..HttpStatusCode.GatewayTimeout ->
            AutomationAdminResult.Failure(AutomationAdminErrorCode.SERVER_ERROR, "The automation service could not complete the action", true)
        else -> AutomationAdminResult.Failure(AutomationAdminErrorCode.INVALID_REQUEST, "The automation service rejected this action", false)
    }

    private fun parseLibraryItem(
        row: JsonObject,
        type: AutomationServiceType,
        lookup: Boolean,
    ): AutomationLibraryItem? {
        val title = row.string("title") ?: return null
        val kind = if (type == AutomationServiceType.SONARR) AutomationMediaKind.SERIES else AutomationMediaKind.MOVIE
        val statistics = row.obj("statistics")
        val regularSeasons = row.array("seasons")
            ?.mapNotNull { it as? JsonObject }
            ?.filter { (it.int("seasonNumber") ?: 0) > 0 }
        return AutomationLibraryItem(
            id = row.int("id") ?: 0,
            kind = kind,
            title = title,
            year = row.int("year"),
            overview = row.string("overview"),
            posterUrl = row.array("images")?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.string("coverType") == "poster" }
                ?.stringAny("remoteUrl", "url"),
            externalId = if (type == AutomationServiceType.SONARR) row.int("tvdbId") else row.int("tmdbId"),
            monitored = row.boolean("monitored") ?: false,
            hasFile = if (lookup) false else {
                row.boolean("hasFile") ?: ((statistics?.int("episodeFileCount") ?: 0) > 0)
            },
            episodeCount = statistics?.int("episodeCount"),
            episodeFileCount = statistics?.int("episodeFileCount"),
            seasonCount = regularSeasons?.size,
            monitoredSeasonCount = regularSeasons?.count { it.boolean("monitored") == true },
        )
    }

    private fun parseRelease(row: JsonObject): AutomationRelease? {
        val guid = row.string("guid") ?: return null
        val indexerId = row.int("indexerId") ?: return null
        val quality = row.obj("quality")?.obj("quality")?.string("name")
            ?: row.obj("quality")?.string("name")
        return AutomationRelease(
            guid = guid,
            indexerId = indexerId,
            title = row.string("title") ?: guid,
            indexer = row.string("indexer"),
            protocol = row.string("protocol"),
            quality = quality,
            sizeBytes = row.long("size"),
            ageHours = row.double("ageHours"),
            seeders = row.int("seeders"),
            peers = row.int("leechers") ?: row.int("peers"),
            approved = row.boolean("approved") ?: false,
            rejections = row.array("rejections")?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull } ?: emptyList(),
        )
    }

    private fun parseQueueItem(row: JsonObject): AutomationQueueItem? {
        val id = row.long("id") ?: return null
        val size = row.long("size")
        val remaining = row.long("sizeleft") ?: row.long("sizeLeft")
        val progress = if (size != null && remaining != null && size > 0) {
            ((size - remaining).coerceAtLeast(0).toDouble() / size.toDouble() * 100.0).coerceIn(0.0, 100.0)
        } else null
        val error = row.array("statusMessages")?.mapNotNull { it as? JsonObject }
            ?.firstOrNull()?.let { it.stringAny("title", "message") }
        val movieId = row.int("movieId")
        val seriesId = row.int("seriesId")
        return AutomationQueueItem(
            id = id,
            title = row.string("title") ?: "Queue item $id",
            status = row.string("status") ?: "unknown",
            mediaId = movieId ?: seriesId,
            mediaKind = when {
                movieId != null -> AutomationMediaKind.MOVIE
                seriesId != null -> AutomationMediaKind.SERIES
                else -> null
            },
            trackedStatus = row.stringAny("trackedDownloadStatus", "trackedDownloadState"),
            progressPercent = progress,
            sizeBytes = size,
            remainingBytes = remaining,
            timeLeft = row.string("timeleft") ?: row.string("timeLeft"),
            errorMessage = error,
        )
    }

    private fun parseIndexer(row: JsonObject): AutomationIndexer? {
        val id = row.int("id") ?: return null
        val rss = row.boolean("enableRss")
        val automatic = row.boolean("enableAutomaticSearch")
        val interactive = row.boolean("enableInteractiveSearch")
        val enabled = row.boolean("enable") ?: listOfNotNull(rss, automatic, interactive).any { it }
        return AutomationIndexer(
            id = id,
            name = row.string("name") ?: "Indexer $id",
            implementation = row.string("implementationName") ?: row.string("implementation"),
            protocol = row.string("protocol"),
            enabled = enabled,
            interactiveSearchEnabled = interactive,
            automaticSearchEnabled = automatic,
            rssEnabled = rss,
            priority = row.int("priority"),
            status = row.string("status"),
        )
    }

    private fun parseSubtitleCandidate(row: JsonObject): AutomationSubtitleCandidate? {
        val token = row.stringAny("subtitle", "url", "id") ?: return null
        val languageObject = row.obj("language")
        return AutomationSubtitleCandidate(
            selectionToken = token,
            provider = row.string("provider") ?: return null,
            language = languageObject?.stringAny("name", "code2", "code3")
                ?: row.stringAny("language", "language_code") ?: "Unknown",
            score = row.double("score"),
            hearingImpaired = row.booleanAny("hearing_impaired", "hearingImpaired") ?: false,
            forced = row.boolean("forced") ?: false,
            release = row.stringAny("release_info", "release"),
            originalFormat = row.booleanAny("original_format", "originalFormat") ?: false,
        )
    }

    private fun parseTdarrLibrary(row: JsonObject): TdarrLibrary? {
        val id = row.stringAny("_id", "id", "DB") ?: return null
        return TdarrLibrary(
            id = id,
            name = row.stringAny("name", "folder") ?: id,
            sourcePath = row.stringAny("folder", "source"),
            transcodeEnabled = row.booleanAny("transcode_enabled", "transcodeEnabled") ?: false,
            healthCheckEnabled = row.booleanAny("health_check", "healthCheckEnabled") ?: false,
        )
    }

    private fun parseTdarrNodes(element: JsonElement): List<TdarrNode> {
        val root = element as? JsonObject ?: return emptyList()
        return root.mapNotNull { (nodeId, value) ->
            val row = value as? JsonObject ?: return@mapNotNull null
            val workersObject = row.obj("workers")
            val workers = workersObject?.mapNotNull { (workerId, workerValue) ->
                val worker = workerValue as? JsonObject ?: return@mapNotNull null
                TdarrWorker(
                    nodeId = nodeId,
                    nodeName = row.stringAny("nodeName", "name") ?: nodeId,
                    workerId = workerId,
                    workerType = worker.stringAny("workerType", "type") ?: "unknown",
                    status = worker.stringAny("status", "stage") ?: "unknown",
                    file = worker.stringAny("file", "filePath"),
                    progressPercent = worker.doubleAny("percentage", "progress"),
                )
            } ?: emptyList()
            TdarrNode(
                id = nodeId,
                name = row.stringAny("nodeName", "name") ?: nodeId,
                online = row.boolean("online") ?: true,
                workers = workers,
            )
        }
    }

    private fun parseTdarrJob(row: JsonObject): TdarrJob? {
        val id = tdarrJobId(row) ?: return null
        val original = row.obj("originalLibraryFile")
        val job = row.obj("job")
        return TdarrJob(
            id = id,
            file = original?.stringAny("file", "_id")
                ?: row.stringAny("file", "filePath", "originalPath", "_id") ?: id,
            status = row.stringAny("status", "stage") ?: "unknown",
            libraryId = original?.stringAny("DB", "libraryId") ?: row.stringAny("DB", "libraryId"),
            nodeId = row.stringAny("nodeID", "nodeId"),
            progressPercent = row.doubleAny("percentage", "progress"),
            updatedAtMs = row.long("updatedAt") ?: job?.long("start"),
        )
    }

    private fun tdarrJobId(row: JsonObject): String? =
        row.obj("job")?.stringAny("jobId", "id") ?: row.stringAny("jobId", "id", "_id")

    private fun parseTdarrAutomation(row: JsonObject): TdarrAutomation? {
        val id = row.stringAny("_id", "id", "configId") ?: return null
        return TdarrAutomation(
            id = id,
            name = row.string("name") ?: id,
            enabled = row.booleanAny("enabled", "scheduleEnabled") ?: false,
        )
    }

    private enum class RequestKind { GET, POST, PUT, PATCH, DELETE }
}

private fun AutomationServiceType.isSonarrOrRadarr(): Boolean =
    this == AutomationServiceType.SONARR || this == AutomationServiceType.RADARR

private fun AutomationServiceType.indexerApiBase(): String? = when (this) {
    AutomationServiceType.SONARR, AutomationServiceType.RADARR -> "/api/v3"
    AutomationServiceType.PROWLARR -> "/api/v1"
    else -> null
}

private fun unsupported(): AutomationAdminResult.Failure = AutomationAdminResult.Failure(
    AutomationAdminErrorCode.UNSUPPORTED,
    "This operation is not supported by this service",
    retryable = false,
)

private fun invalid(message: String): AutomationAdminResult.Failure = AutomationAdminResult.Failure(
    AutomationAdminErrorCode.INVALID_REQUEST,
    message,
    retryable = false,
)

private inline fun <T, R> AutomationAdminResult<T>.mapValue(transform: (T) -> R): AutomationAdminResult<R> = when (this) {
    is AutomationAdminResult.Success -> runCatching { AutomationAdminResult.Success(transform(value)) }
        .getOrElse {
            AutomationAdminResult.Failure(
                AutomationAdminErrorCode.INVALID_RESPONSE,
                "The automation service returned an unreadable response",
                retryable = true,
            )
        }
    is AutomationAdminResult.Failure -> this
}

private fun AutomationAdminResult<JsonElement>.toUnit(): AutomationAdminResult<Unit> = when (this) {
    is AutomationAdminResult.Success -> AutomationAdminResult.Success(Unit)
    is AutomationAdminResult.Failure -> this
}

private fun <T> AutomationAdminResult<T>.failureOrInvalid(): AutomationAdminResult.Failure =
    this as? AutomationAdminResult.Failure ?: AutomationAdminResult.Failure(
        AutomationAdminErrorCode.INVALID_RESPONSE,
        "The automation service returned an unreadable response",
        retryable = true,
    )

private fun JsonElement.rows(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> {
        val direct = listOf("records", "data", "results", "items", "array")
            .firstNotNullOfOrNull { key -> this[key] as? JsonArray }
        if (direct != null) direct.mapNotNull { it as? JsonObject }
        else {
            val nested = this["data"] as? JsonObject
            val nestedRows = nested?.let { data ->
                listOf("records", "data", "results", "items", "array")
                    .firstNotNullOfOrNull { key -> data[key] as? JsonArray }
            }
            nestedRows?.mapNotNull { it as? JsonObject }
                ?: values.mapNotNull { it as? JsonObject }
        }
    }
    else -> emptyList()
}

private fun JsonElement.jsonObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitiveOrNull()?.contentOrNull
private fun JsonObject.stringAny(vararg keys: String): String? = keys.firstNotNullOfOrNull(::string)
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitiveOrNull()?.intOrNull
private fun JsonObject.intAny(vararg keys: String): Int? = keys.firstNotNullOfOrNull(::int)
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitiveOrNull()?.longOrNull
private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitiveOrNull()?.doubleOrNull
private fun JsonObject.doubleAny(vararg keys: String): Double? = keys.firstNotNullOfOrNull(::double)
private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitiveOrNull()?.booleanOrNull
private fun JsonObject.booleanAny(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull(::boolean)
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.languageList(vararg keys: String): List<String> {
    val array = keys.firstNotNullOfOrNull(::array) ?: return emptyList()
    return array.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull
            is JsonObject -> item.stringAny("name", "code2", "code3", "language")
            else -> null
        }
    }
}
