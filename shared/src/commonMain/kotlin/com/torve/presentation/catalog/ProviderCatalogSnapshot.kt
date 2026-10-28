package com.torve.presentation.catalog

import com.torve.domain.model.MediaItem
import com.torve.domain.repository.PreferencesRepository
import com.torve.util.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ProviderCatalogSnapshot(
    val fingerprint: String,
    val savedAtMs: Long,
    val providerIds: String,
    val items: List<MediaItem>,
    val totalPages: Int,
)

/** Small first-page snapshots live in the existing account/profile namespace. */
internal class ProviderCatalogSnapshotStore(
    private val prefs: PreferencesRepository,
    private val mediaType: String,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val writes = Mutex()
    private fun key(providerId: Int) = "provider_catalog_snapshot_v1_${mediaType}_$providerId"

    suspend fun fingerprint(state: CatalogUiState, region: String): String = withContext(ioDispatcher) {
        val filter = state.filter
        listOf(mediaType, state.providerId, region, prefs.getString("app_language").orEmpty(),
            state.selectedCategory.name, state.selectedGenreId, filter.sortBy.name, filter.minRating,
            filter.ratingSource.name, filter.year, filter.yearTo, filter.runtimeFilter?.name).joinToString("|")
    }

    suspend fun read(providerId: Int, fingerprint: String): ProviderCatalogSnapshot? = withContext(ioDispatcher) {
        try {
            prefs.getString(key(providerId))?.let { json.decodeFromString<List<ProviderCatalogSnapshot>>(it) }
                ?.firstOrNull { it.fingerprint == fingerprint && now() - it.savedAtMs in 0..7L * 86_400_000 }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun write(providerId: Int, fingerprint: String, providerIds: String, state: CatalogUiState) = withContext(ioDispatcher) {
        if (state.currentPage != 1 || state.providerId != providerId || state.searchQuery.isNotBlank()) return@withContext
        writes.withLock { try {
            val previous = runCatching { prefs.getString(key(providerId))?.let { json.decodeFromString<List<ProviderCatalogSnapshot>>(it) } }
                .getOrNull().orEmpty().filter { it.fingerprint != fingerprint && now() - it.savedAtMs in 0..7L * 86_400_000 }
            val snapshot = ProviderCatalogSnapshot(fingerprint, now(), providerIds,
                state.items.take(40).map { it.copy(cast = emptyList(), seasons = emptyList()) }, state.totalPages)
            prefs.setString(key(providerId), json.encodeToString((previous + snapshot).takeLast(6)))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Cache writes must not block a displayed catalogue. */ } }
    }
}
