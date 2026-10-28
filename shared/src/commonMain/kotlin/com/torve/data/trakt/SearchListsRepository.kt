package com.torve.data.trakt

import com.torve.data.auth.*
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.profile.FamilyControls
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SearchListsRepository(private val client: TraktClient, private val api: TraktAuthorizedApi,
    private val prefs: PreferencesRepository, private val metadata: MetadataRepository,
    private val ids: UserIdProvider, private val family: FamilyControls,
    private val policy: ContentPolicyRepository, private val filter: ContentPolicyFilter,
    private val notifier: SettingsRefreshNotifier) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshing = kotlinx.coroutines.flow.MutableStateFlow(setOf<String>())
    private suspend fun checkAccess() {
        family.initialize()
        check(!family.state.value.isKids && family.allowsSource("catalogue")) { "Public lists are available in adult profiles. Switch profiles with a parent to browse lists." }
    }
    suspend fun connected(): Boolean = api.hasConnection()
    private suspend fun read(key: String): List<TraktBrowseList> = runCatching { prefs.getString(key)?.let { json.decodeFromString<List<TraktBrowseList>>(it) } }.getOrNull().orEmpty()
    suspend fun saved(): List<TraktBrowseList> { checkAccess(); return read(SAVED) }
    suspend fun pinned(): List<TraktBrowseList> { checkAccess(); return read(HOME) }
    suspend fun liked(): List<TraktBrowseList> { checkAccess(); return read(LIKED) }
    suspend fun browse(mode: String, query: String, page: Int): List<TraktBrowseList> = ids.withPersonalDataScope {
        checkAccess()
        val text = query.trim()
        val lists = when (mode) {
            "home" -> if (page == 1) read(HOME) else emptyList()
            "saved" -> if (page == 1) read(SAVED) else emptyList()
            "mine" -> if (page == 1) { check(api.hasConnection()) { "Connect Trakt in Settings to view your lists." }
                api.getPersonalLists().map { TraktBrowseList(it.name, it.description, it.ids, it.itemCount, owned = true) } } else emptyList()
            "liked" -> { check(api.hasConnection()) { "Connect Trakt in Settings to view liked lists." }
                api.getLikedBrowseLists(page).also { batch ->
                    val combined = ((if (page == 1) emptyList() else read(LIKED)) + batch).distinctBy { it.ids.trakt }
                    prefs.setString(LIKED, json.encodeToString(combined))
                }
            }
            else -> client.browsePublicLists(text, page)
        }
        if (mode == "explore" || text.isBlank()) lists else lists.filter { it.name.contains(text, true) || it.user?.username?.contains(text, true) == true }
    }
    data class ItemPage(val items: List<MediaItem>, val hasMore: Boolean)
    suspend fun items(listId: Int, page: Int, own: Boolean): ItemPage = ids.withPersonalDataScope {
        checkAccess()
        val rows = if (own) api.getOwnBrowseListItems(listId, page) else client.getPublicListItems(listId, page)
        val limiter = Semaphore(4)
        val items = coroutineScope { rows.mapNotNull { row ->
            val media = row.movie ?: row.show ?: return@mapNotNull null
            val type = if (row.movie != null) MediaType.MOVIE else MediaType.SERIES
            async { limiter.withPermit {
                try {
                    val tmdb = media.ids.tmdb
                    if (tmdb != null) metadata.getDetail(if (type == MediaType.MOVIE) "movie" else "tv", tmdb)
                    else media.ids.imdb?.let { metadata.findByImdbId(it, if (type == MediaType.MOVIE) "movie" else "tv") }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                ?: MediaItem(id = media.ids.imdb ?: media.ids.tmdb?.toString() ?: "trakt:${media.ids.trakt}",
                    tmdbId = media.ids.tmdb, imdbId = media.ids.imdb, type = type, title = media.title, year = media.year,
                    posterUrl = media.images?.poster?.firstOrNull()?.let(::imageUrl), backdropUrl = media.images?.fanart?.firstOrNull()?.let(::imageUrl))
            } }
        }.awaitAll() }.dedupeByStableKey()
        checkAccess()
        val visible = visible(items)
        if (page == 1) prefs.setString(snapshotKey(listId, own), json.encodeToString(visible))
        ItemPage(visible, rows.size >= 30)
    }
    private fun visible(items: List<MediaItem>): List<MediaItem> = filter.filterItems(policy.state.value,
        ContentAccessContext.DIRECT_SEARCH, items, ContentSourceType.TRAKT).items.filterNot { it.isContentPlaceholder || it.isStubDetail }
    suspend fun cachedItems(id: Int, own: Boolean): List<MediaItem> {
        checkAccess()
        return visible(runCatching { prefs.getString(snapshotKey(id, own))?.let { json.decodeFromString<List<MediaItem>>(it) } }.getOrNull().orEmpty())
    }
    suspend fun save(list: TraktBrowseList, value: Boolean) = ids.withPersonalDataScope {
        checkAccess(); update(SAVED, list, value)
    }
    suspend fun like(list: TraktBrowseList, value: Boolean) = ids.withPersonalDataScope {
        checkAccess(); check(api.hasConnection()) { "Connect Trakt in Settings to like lists." }
        api.followBrowseList(requireNotNull(list.ids.trakt), value)
        update(LIKED, list, value)
    }
    suspend fun pin(list: TraktBrowseList, value: Boolean, own: Boolean) = ids.withPersonalDataScope {
        checkAccess()
        val id = requireNotNull(list.ids.trakt)
        val sections = runCatching { prefs.getString("custom_sections")?.let { json.decodeFromString<List<CustomSection>>(it) } }.getOrNull().orEmpty()
        val key = "trakt-list:$id"
        val updated = sections.filterNot { it.id == key }.let { other -> if (!value) other else other + CustomSection(key, list.name, "both",
            CustomSectionFilters(traktListId = id, traktListOwned = own), (other.maxOfOrNull { it.order } ?: -1) + 1) }
        prefs.setString("custom_sections", json.encodeToString(updated)); update(HOME, list, value)
        notifier.notifyRefresh(kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
    }
    /** Home first frame reads a profile-scoped snapshot. Refresh never stalls startup. */
    suspend fun homeItems(id: Int, own: Boolean): List<MediaItem> {
        if (family.state.value.isKids || !family.allowsSource("catalogue")) return emptyList()
        val cached = cachedItems(id, own)
        val stamp = ids.currentDataUserId() + ":$id:$own"
        if (stamp !in refreshing.value) {
            refreshing.value = refreshing.value + stamp
            val owner = capturePersonalDataScope(ids)
            scope.launch(owner) {
                try {
                    val fresh = items(id, 1, own).items
                    if (fresh != cached) notifier.notifyRefresh(kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
                    delay(15 * 60_000)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                finally { refreshing.value = refreshing.value - stamp }
            }
        }
        return cached
    }
    private suspend fun update(key: String, list: TraktBrowseList, value: Boolean) {
        val others = read(key).filterNot { it.ids.trakt == list.ids.trakt }
        prefs.setString(key, json.encodeToString(if (value) listOf(list) + others else others))
    }
    private fun snapshotKey(id: Int, own: Boolean) = "search_list_snapshot_v1:$id:$own"
    private fun imageUrl(value: String) = if (value.startsWith("https://")) value else "https://" + value.removePrefix("//")
    private companion object { const val SAVED = "search_saved_lists_v1"; const val HOME = "search_home_lists_v1"; const val LIKED = "search_liked_lists_v1" }
}
