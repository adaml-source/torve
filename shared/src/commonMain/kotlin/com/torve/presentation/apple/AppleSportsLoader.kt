package com.torve.presentation.apple

import com.torve.data.panda.NzbIndexerRow
import com.torve.data.usenet.NewznabClient
import com.torve.data.usenet.NewznabItem
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.domain.usenet.UsenetIndexerCategoryMap
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

internal data class AppleSportsResult(val items: List<NewznabItem>, val failure: String? = null)

internal data class AppleSportsResults(val providers: List<AppleSportsResult>) {
    // GUIDs belong to an indexer; equal GUIDs from different providers are distinct sources.
    val items get() = providers.flatMap { it.items }.distinctBy { it.nzbUrl }
    val allFailed get() = providers.isNotEmpty() && providers.all { it.failure != null }
    val warning get() = providers.mapNotNull { it.failure }.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** Keep independent indexer failures and timeouts from cancelling successful Sports searches. */
internal suspend fun loadAppleSports(
    client: NewznabClient,
    indexers: List<NzbIndexerRow>,
    queries: List<String>,
    days: Int,
    timeoutMs: Long = 45_000,
): AppleSportsResults = coroutineScope {
    val permits = Semaphore(3)
    AppleSportsResults(indexers.map { row -> async {
        permits.withPermit {
            val items = linkedMapOf<String, NewznabItem>()
            // Commit each page before requesting the next, so a late quota error keeps earlier results.
            suspend fun pages(limit: Int, fetch: suspend (Int, Int) -> List<NewznabItem>) {
                val seen = mutableSetOf<String>()
                var offset = 0
                while (seen.size < limit) {
                    val page = fetch(offset, minOf(100, limit - seen.size))
                    if (page.isEmpty()) break
                    val previous = seen.size
                    page.forEach { item -> seen += item.nzbUrl; items[item.nzbUrl] = item }
                    if (seen.size == previous) break
                    offset += page.size
                }
            }
            val failure = try {
                withTimeout(timeoutMs) {
                    pages(300) { offset, limit -> client.browse(row.url, row.apiKey,
                        UsenetIndexerCategoryMap.sportsCategoriesFor(row.type), offset, limit, days.takeIf { it > 0 }) }
                    for (query in queries) pages(150) { offset, limit -> client.search(row.url, row.apiKey,
                        UsenetIndexerCategoryMap.sportsSearchCategoriesFor(row.type), query, offset, limit, days.takeIf { it > 0 }) }
                }
                null
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                "Request timed out. Try refreshing."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: "Could not reach the indexer. Try refreshing."
                DiagnosticsRedactor.redact(if (row.apiKey.isBlank()) message else message.replace(row.apiKey, "[redacted]")).take(320)
            }
            val name = when (row.type.lowercase()) {
                "scenenzbs" -> "Treasure Maps"
                "nzbgeek" -> "NZBgeek"
                "dognzb" -> "DOGnzb"
                "nzbplanet" -> "NZBPlanet"
                else -> runCatching { Url(row.url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Custom indexer"
            }
            AppleSportsResult(items.values.toList(), failure?.let { "$name: $it" })
        }
    } }.awaitAll())
}
