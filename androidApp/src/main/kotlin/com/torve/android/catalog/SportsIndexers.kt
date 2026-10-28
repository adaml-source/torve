package com.torve.android.catalog

import com.torve.data.panda.NzbIndexerRow
import com.torve.data.usenet.NewznabItem
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.usenet.UsenetIndexerUrlResolver
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

internal data class SportsIndexer(val type: String, val url: String, val apiKey: String) {
    val endpoint: String get() = sportsEndpoint(url)
    val label: String get() = when (type.lowercase()) {
        "scenenzbs" -> "Treasure Maps"
        "nzbgeek" -> "NZBgeek"
        "dognzb" -> "DOGnzb"
        "nzbplanet" -> "NZBPlanet"
        else -> runCatching { URI(url).host }.getOrNull() ?: "Custom indexer"
    }
    override fun toString() = "SportsIndexer($label)"
}

internal fun String?.isUsableSportsSecret(): Boolean {
    val value = this?.trim().orEmpty()
    return value.isNotBlank() && !value.contains("redact", ignoreCase = true) &&
        !value.all { it == '*' || it.code == 8226 }
}

private fun sportsEndpoint(url: String): String = runCatching {
    val uri = URI(url.trim())
    require(uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank())
    val path = uri.path.orEmpty().trimEnd('/').let {
        if (it.substringAfterLast('/').lowercase() in setOf("api", "api.php")) it else "$it/api"
    }
    URI(uri.scheme.lowercase(), uri.userInfo, uri.host.lowercase(), uri.port, path, uri.query, null).toString()
}.getOrDefault("")

/** A configured row can recover only its own saved credential, never another provider's key. */
internal fun resolveSportsIndexers(configured: List<NzbIndexerRow>?, saved: List<NzbIndexerRow>): List<SportsIndexer> {
    val selected = (configured ?: saved).filter { it.type != "none" }
    return selected.mapNotNull { row ->
        val url = UsenetIndexerUrlResolver.resolve(row.type, row.url)
        val endpoint = sportsEndpoint(url).takeIf(String::isNotBlank) ?: return@mapNotNull null
        val key = row.apiKey.takeIf { it.isUsableSportsSecret() } ?: saved.firstOrNull {
            sportsEndpoint(UsenetIndexerUrlResolver.resolve(it.type, it.url)) == endpoint && it.apiKey.isUsableSportsSecret()
        }?.apiKey ?: return@mapNotNull null
        SportsIndexer(row.type, url, key.trim())
    }.distinctBy { it.endpoint }
}

internal suspend fun readSavedSportsIndexers(
    secretStore: IntegrationSecretStore, prefs: PreferencesRepository,
): List<NzbIndexerRow> {
    val saved = secretStore.getSubKeys(IntegrationSecretKey.PANDA_INDEXER_API_KEY)
        .filter { '|' in it }.mapNotNull { subKey ->
            val key = secretStore.get(IntegrationSecretKey.PANDA_INDEXER_API_KEY, subKey)
                ?.takeIf { it.isUsableSportsSecret() } ?: return@mapNotNull null
            val (type, url) = subKey.split('|', limit = 2)
            NzbIndexerRow(type, url, key)
        }
    if (saved.isNotEmpty()) return saved
    val type = prefs.getString("panda_nzb_indexer") ?: prefs.getString("nzb_indexer") ?: return emptyList()
    val key = prefs.getString("panda_nzb_indexer_api_key") ?: prefs.getString("nzb_indexer_api_key") ?: return emptyList()
    val url = prefs.getString("panda_nzb_indexer_url") ?: prefs.getString("nzb_indexer_url").orEmpty()
    return listOf(NzbIndexerRow(type, url, key))
}

internal fun sportsDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun sportsIndexersKey(indexers: List<SportsIndexer>): String = sportsDigest(
    indexers.map { sportsDigest(it.endpoint + "\u0000" + it.apiKey) }.sorted().joinToString("|"),
)

internal fun sportsPageKey(indexers: List<SportsIndexer>) = "tv_sports_v2:${sportsIndexersKey(indexers)}"

// GUIDs are local to an indexer. Two providers may use the same GUID for different NZBs.
internal fun NewznabItem.sportsSourceId() = "nzb_${sportsDigest(nzbUrl)}"

internal data class SportsIndexerResult(val indexer: SportsIndexer, val items: List<NewznabItem>, val error: String? = null)

internal data class SportsIndexerResults(val results: List<SportsIndexerResult>) {
    val items get() = results.flatMap { it.items }.distinctBy { it.sportsSourceId() }
    val anySucceeded get() = results.any { it.error == null }
    val warning: String? get() = results.filter { it.error != null }.takeIf { it.isNotEmpty() }?.let { failed ->
        val summary = failed.joinToString(" · ") { "${it.indexer.label}: ${it.error}" }
        if (anySucceeded) "Some indexers unavailable. $summary" else summary
    }
}

/** One failing or slow indexer cannot cancel the other configured providers. */
internal suspend fun fetchSportsIndexers(
    indexers: List<SportsIndexer>,
    timeoutMs: Long = 45_000,
    onResult: (SportsIndexerResult) -> Unit = {},
    fetch: suspend (SportsIndexer) -> List<NewznabItem>,
): SportsIndexerResults = coroutineScope {
    val permits = Semaphore(3)
    SportsIndexerResults(indexers.map { indexer -> async {
        permits.withPermit {
            val result = try {
                SportsIndexerResult(indexer, withTimeout(timeoutMs) { fetch(indexer) })
            } catch (_: TimeoutCancellationException) {
                SportsIndexerResult(indexer, emptyList(), "Request timed out. Try refreshing.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SportsIndexerResult(indexer, emptyList(), DiagnosticsRedactor.redact(error.message ?: "Could not reach the indexer."))
            }
            onResult(result)
            result
        }
    } }.awaitAll())
}
