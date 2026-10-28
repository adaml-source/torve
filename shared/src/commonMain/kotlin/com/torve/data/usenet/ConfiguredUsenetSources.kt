package com.torve.data.usenet

import com.torve.data.addon.*
import com.torve.data.auth.PersonalDataScope
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.data.panda.*
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.domain.model.*
import com.torve.domain.repository.StreamFetchResult
import com.torve.domain.usenet.UsenetIndexerUrlResolver
import com.torve.presentation.session.AccountSessionCoordinator
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.TimeSource

/** A saved connection is a source in its own right; it never installs an extension. */
class UsenetSourceConfiguration(
    val owner: String,
    val enabled: Boolean,
    val indexers: List<NzbIndexerRow>,
    val downloadClient: String,
    val downloadClientApiKey: String,
) {
    override fun toString() = "UsenetSourceConfiguration(enabled=$enabled, indexers=${indexers.size})"
}

/** No credentials: a selected result must still belong to the active profile and connection. */
data class ConfiguredNzbSource(val owner: String, val indexerUrl: String, val season: Int?, val episode: Int?)

class AccountUsenetSourceSettings(
    private val session: () -> AccountSessionCoordinator,
    private val api: PandaApiClient,
    private val ids: UserIdProvider,
) {
    suspend fun read(): UsenetSourceConfiguration? = ids.withPersonalDataScope {
        val owner = PersonalDataScope(ids.currentUserId(), ids.currentProfileId())
        val reference = session().getUsenetAccountConfig() ?: return@withPersonalDataScope null
        val token = session().getTorveAccessToken()
        val record = api.readUsenetConfig(reference, token)
        val config = record.config ?: error("Saved Usenet settings are unavailable. Open Usenet settings and try again.")
        if (!config.enableUsenet) return@withPersonalDataScope null
        val rows = config.nzbIndexers.ifEmpty {
            if (config.nzbIndexer == "none") emptyList() else
                listOf(NzbIndexerRow(config.nzbIndexer, config.nzbIndexerUrl, config.nzbIndexerApiKey))
        }.filter { it.type != "none" }
        if (rows.isEmpty()) return@withPersonalDataScope null
        val secrets = if (rows.any { !usableKey(it.apiKey) } ||
            (config.downloadClient != "none" && !usableKey(config.downloadClientApiKey))) {
            val id = record.configId ?: reference.configId ?: error("Saved Usenet connection has no identifier.")
            check(!token.isNullOrBlank()) { "Sign in to use your saved Usenet connections." }
            api.getConfigSecrets(id, token)
        } else null
        val indexers = rows.map { row ->
            val key = row.apiKey.takeIf(::usableKey) ?: secrets?.nzbIndexers
                ?.firstOrNull { it.type == row.type && it.url.trimEnd('/') == row.url.trimEnd('/') }?.apiKey
                ?: secrets?.nzbIndexerApiKey?.takeIf { rows.size == 1 }
                ?: error("The API key for a saved NZB indexer is unavailable. Reconnect it in Usenet settings.")
            check(usableKey(key)) { "A saved NZB indexer needs its API key. Open Usenet settings." }
            row.copy(apiKey = key.trim(), url = UsenetIndexerUrlResolver.resolve(row.type, row.url))
        }
        owner.check(ids)
        UsenetSourceConfiguration(ids.currentDataUserId(), true, indexers, config.downloadClient,
            config.downloadClientApiKey.takeIf(::usableKey) ?: secrets?.downloadClientApiKey.orEmpty())
    }
}

private fun usableKey(value: String) = value.isNotBlank() && !value.contains("redact", true)

/** Shared by mobile, TV, Apple and desktop. Failure of one indexer cannot cancel another. */
class ConfiguredUsenetSources(
    private val newznab: NewznabClient,
    private val torbox: TorBoxUsenetClient,
    private val readSettings: suspend () -> UsenetSourceConfiguration?,
    private val currentOwner: () -> String,
) {
    suspend fun hasSources(): Boolean = readSettings()?.let { it.enabled && it.indexers.isNotEmpty() } == true

    suspend fun search(type: MediaType, imdbId: String, season: Int?, episode: Int?,
        preferences: StreamPreferences): StreamFetchResult {
        val owner = currentOwner()
        val config = try { readSettings() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return StreamFetchResult(emptyList(), sourceErrors = listOf(
            "Could not load saved Usenet connections. Open Usenet settings and try again.")) }
        if (config == null || !config.enabled) return StreamFetchResult(emptyList())
        checkOwner(owner, config)
        if (type !in setOf(MediaType.MOVIE, MediaType.SERIES) || !Regex("tt\\d+").matches(imdbId))
            return StreamFetchResult(emptyList())
        if (type == MediaType.SERIES && (season == null || episode == null)) return StreamFetchResult(emptyList())
        val permits = Semaphore(3)
        val batches = coroutineScope {
            config.indexers.distinctBy { it.url.trimEnd('/') }.map { row -> async {
                permits.withPermit {
                    val started = TimeSource.Monotonic.markNow()
                    val name = runCatching { Url(row.url).host }.getOrDefault("NZB indexer")
                    try {
                        val items = withTimeout(12_000) {
                            newznab.searchTitle(row.url, row.apiKey, imdbId, season, episode)
                        }
                        checkOwner(owner, config)
                        val streams = items.filter { item ->
                            item.imdbId?.removePrefix("tt")?.trimStart('0')?.let {
                                it == imdbId.removePrefix("tt").trimStart('0')
                            } != false && (season == null || episode == null || matchesEpisode(item.title, season, episode))
                        }.map { item ->
                            StreamParser.parse(StremioStream(name = "$name · Usenet", title = item.title,
                                url = item.nzbUrl, behaviorHints = StemioBehaviorHints(videoSize = item.sizeBytes,
                                    indexer = name, filename = item.title, service = config.downloadClient)), name)
                                .copy(configuredNzb = ConfiguredNzbSource(owner, row.url, season, episode),
                                    isCached = false, downloadService = config.downloadClient)
                        }.filter { stream ->
                            val quality = StreamQuality.fromString(stream.quality)
                            (!preferences.excludeSeasonPacks || !stream.isSeasonPack()) &&
                                (quality == StreamQuality.UNKNOWN || quality.rank in preferences.maxQuality.rank..preferences.minQuality.rank) &&
                                (preferences.maxFileSizeBytes == null || stream.size?.let(StreamAggregator::parseSizeToBytes)
                                    ?.let { it <= preferences.maxFileSizeBytes } != false)
                        }
                        StreamFetchResult(StreamScorer().scoreAll(streams, preferences), providerStats = listOf(
                            ProviderSourceStat(name, streams.size, 0, started.elapsedNow().inWholeMilliseconds)))
                    } catch (timeout: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        failed(name, "Request timed out. Try refreshing sources.", started.elapsedNow().inWholeMilliseconds)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        failed(name, DiagnosticsRedactor.redact(error.message.orEmpty().replace(row.apiKey, "[redacted]")),
                            started.elapsedNow().inWholeMilliseconds)
                    }
                }
            } }.awaitAll()
        }
        checkOwner(owner, config)
        return StreamFetchResult(batches.flatMap { it.streams }.distinctBy { it.directUrl },
            providerStats = batches.flatMap { it.providerStats }, sourceErrors = batches.flatMap { it.sourceErrors })
    }

    suspend fun resolve(stream: ParsedStream): ResolvedStream {
        val reference = stream.configuredNzb ?: error("This source is not a configured NZB result.")
        check(currentOwner() == reference.owner) { "Your profile changed. Refresh sources before playing." }
        val config = readSettings() ?: error("This Usenet connection was removed. Refresh sources.")
        checkOwner(reference.owner, config)
        check(config.enabled && config.indexers.any { it.url.trimEnd('/') == reference.indexerUrl.trimEnd('/') }) {
            "This NZB indexer was removed. Refresh sources."
        }
        check(config.downloadClient == "torbox" && usableKey(config.downloadClientApiKey)) {
            "Select TorBox as the download client in Usenet settings and enter its API key."
        }
        val ready = torbox.resolveForEpisode(stream.directUrl ?: error("NZB URL is missing"),
            config.downloadClientApiKey, reference.season, reference.episode) {
                checkOwner(reference.owner, config)
            }.getOrThrow()
        checkOwner(reference.owner, config)
        return ResolvedStream(ready.streamUrl, DebridServiceType.TORBOX, ready.fileName, ready.sizeBytes)
    }

    private fun checkOwner(owner: String, config: UsenetSourceConfiguration) {
        if (currentOwner() != owner || config.owner != owner) throw CancellationException("The active profile changed")
    }
    private fun matchesEpisode(title: String, season: Int, episode: Int): Boolean {
        val tag = Regex("(?i)(?:s(\\d+)e(\\d+)|(\\d+)x(\\d+))").find(title) ?: return true
        val numbers = tag.groupValues.drop(1).filter(String::isNotBlank).mapNotNull(String::toIntOrNull)
        return numbers == listOf(season, episode)
    }
    private fun failed(name: String, message: String, elapsed: Long) = StreamFetchResult(emptyList(),
        providerStats = listOf(ProviderSourceStat(name, 0, 0, elapsed, failed = true)),
        sourceErrors = listOf("$name: ${message.take(320)}"))
}
