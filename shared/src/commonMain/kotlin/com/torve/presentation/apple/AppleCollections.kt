package com.torve.presentation.apple

import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.MdbListRepository
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.metadata.TmdbMappers
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaItem
import com.torve.domain.model.toMediaItem
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.Koin

@Serializable
data class AppleCollectionBookmark(val source: String, val sourceId: Int, val name: String) {
    val id: String get() = "$source:$sourceId"
}
data class AppleCollectionPage(val name: String, val items: List<MediaItem>, val hasMore: Boolean)
data class AppleCollectionChoice(val source: String, val sourceId: Int, val name: String,
    val summary: String = "", val itemCount: Int = 0) { val id: String get() = "$source:$sourceId" }
data class AppleCollectionChoices(val options: List<AppleCollectionChoice>, val hasMore: Boolean,
    val mdbListConnected: Boolean, val warning: String? = null)

object CollectionBookmarkPolicy {
    fun save(current: List<AppleCollectionBookmark>, value: AppleCollectionBookmark): List<AppleCollectionBookmark> {
        require(value.source in setOf("tmdb", "tmdb_list", "mdblist") && value.sourceId > 0 && value.name.isNotBlank())
        return current.filterNot { it.id == value.id } + value
    }
}

/** Store collection identities, never expand them into watchlist mutations or a truncated snapshot. */
class AppleCollections(private val koin: Koin) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val ids get() = koin.get<UserIdProvider>()
    private val prefs get() = koin.get<PreferencesRepository>()
    private suspend fun local(): List<AppleCollectionBookmark> =
        prefs.getString("saved_media_collections_v1")?.let { json.decodeFromString<List<AppleCollectionBookmark>>(it) }.orEmpty()

    @Throws(Exception::class)
    suspend fun bookmarks(): List<AppleCollectionBookmark> = ids.withPersonalDataScope {
        (local() + koin.get<MdbListRepository>().getSavedLists().map { AppleCollectionBookmark("mdblist", it.listId, it.name) })
            .distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    @Throws(Exception::class)
    suspend fun saveCollection(id: Int) = save("tmdb", id)

    @Throws(Exception::class)
    suspend fun save(source: String, id: Int) = ids.withPersonalDataScope { mutex.withLock {
        require(id > 0)
        val name = when (source) {
            "tmdb" -> koin.get<TmdbApiClient>().getCollection(id).also { require(it.id == id) }.name
            "tmdb_list" -> koin.get<TmdbApiClient>().getCuratedList(id, 1).also { require(it.id.intOrNull == id) }.name
            "mdblist" -> koin.get<MdbListApi>().getListInfo(id, mdbKey()).name
            else -> error("Unsupported collection")
        }
        val next = CollectionBookmarkPolicy.save(local(), AppleCollectionBookmark(source, id, name))
        prefs.setString("saved_media_collections_v1", json.encodeToString(next))
    } }

    @Throws(Exception::class)
    suspend fun remove(source: String, id: Int) = ids.withPersonalDataScope { mutex.withLock {
        if (source == "mdblist") koin.get<MdbListRepository>().removeList(id)
        prefs.setString("saved_media_collections_v1", json.encodeToString(local().filterNot { it.source == source && it.sourceId == id }))
    } }

    @Throws(Exception::class)
    suspend fun contents(source: String, id: Int, page: Int): AppleCollectionPage = ids.withPersonalDataScope {
        require(id > 0 && page > 0)
        when (source) {
            "tmdb_list" -> {
                val list = koin.get<TmdbApiClient>().getCuratedList(id, page)
                require(list.id.intOrNull == id)
                AppleCollectionPage(list.name, list.items.map { TmdbMappers.movieToMediaItem(it) }.distinctBy { it.id },
                    if (list.totalPages > 0) page < list.totalPages else list.items.isNotEmpty() && list.items.size < list.itemCount && page * 20 < list.itemCount)
            }
            "tmdb" -> {
                val collection = koin.get<TmdbApiClient>().getCollection(id)
                AppleCollectionPage(collection.name, collection.parts.map { TmdbMappers.movieToMediaItem(it) }
                    .distinctBy { it.id }.sortedWith(compareBy<MediaItem> { it.releaseDate?.takeIf(String::isNotBlank) ?: "9999" }.thenBy { it.title }), false)
            }
            "mdblist" -> {
                val key = koin.get<IntegrationSecretStore>().get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefs.getString(SettingsViewModel.KEY_MDBLIST_API_KEY).orEmpty()
                require(key.isNotBlank()) { "Connect MDBList to browse this collection." }
                val api = koin.get<MdbListApi>()
                val info = api.getListInfo(id, key)
                val result = api.getListPage(id, key, limit = 100, page = page)
                AppleCollectionPage(info.name, result.items.map { it.toMediaItem() }, result.hasMore)
            }
            else -> error("Unsupported collection")
        }
    }

    private suspend fun mdbKey(): String = (koin.get<IntegrationSecretStore>().get(IntegrationSecretKey.MDBLIST_API_KEY)
        ?: prefs.getString(SettingsViewModel.KEY_MDBLIST_API_KEY).orEmpty()).also {
        require(it.isNotBlank()) { "Connect MDBList to browse its lists." }
    }

    @Throws(Exception::class)
    suspend fun enrich(items: List<MediaItem>): List<MediaItem> = ids.withPersonalDataScope {
        require(items.size <= 12)
        val key = koin.get<IntegrationSecretStore>().get(IntegrationSecretKey.MDBLIST_API_KEY)
            ?: prefs.getString(SettingsViewModel.KEY_MDBLIST_API_KEY) ?: MdbListApi.DEFAULT_API_KEY
        koin.get<com.torve.data.mdblist.RatingsEnricher>().enrichList(items, key)
    }

    /** Real source choices; no hard-coded universe membership or expansion into the watchlist. */
    @Throws(Exception::class)
    suspend fun choices(movieId: Int?, query: String, page: Int): AppleCollectionChoices = ids.withPersonalDataScope {
        require(page > 0)
        val options = mutableListOf<AppleCollectionChoice>(); var more = false; var failed = false
        val api = koin.get<TmdbApiClient>()
        suspend fun attempt(block: suspend () -> Unit) {
            try { kotlinx.coroutines.withTimeout(8_000) { block() } }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { failed = true }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
        }
        if (movieId != null && movieId > 0) attempt {
            val result = api.getMovieLists(movieId, page); more = page < result.totalPages
            options += result.results.mapNotNull { list -> list.id.intOrNull?.takeIf { it > 0 }?.let {
                AppleCollectionChoice("tmdb_list", it, list.name, list.description, list.itemCount)
            } }
        }
        val key = try { mdbKey() } catch (_: IllegalArgumentException) { "" }
        if (query.trim().length >= 2 && page == 1) {
            attempt { options += api.searchCollections(query.trim()).results.map { AppleCollectionChoice("tmdb", it.id, it.name, it.overview) } }
            if (key.isNotBlank()) attempt {
                options += koin.get<MdbListApi>().searchLists(query.trim(), key).map { AppleCollectionChoice("mdblist", it.id, it.name) }
            }
        }
        AppleCollectionChoices(options.filter { it.name.isNotBlank() }.distinctBy { it.id }, more, key.isNotBlank(),
            if (failed) "Some collection sources could not be loaded. You can retry or preview the available results." else null)
    }
}
