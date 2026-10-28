package com.torve.presentation.search

import com.torve.data.auth.*
import com.torve.data.trakt.*
import com.torve.data.addon.*
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.domain.model.*
import com.torve.domain.repository.AddonRepository
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SearchSourceChoice(val id: String, val title: String, val subtitle: String)
data class SearchCollectionsState(val mode: String = "explore", val lists: List<TraktBrowseList> = emptyList(),
    val activeList: TraktBrowseList? = null, val items: List<MediaItem> = emptyList(), val sources: List<SearchSourceChoice> = emptyList(),
    val sourceId: String? = null, val sourceTitle: String? = null, val connected: Boolean = false,
    val savedIds: Set<Int> = emptySet(), val pinnedIds: Set<Int> = emptySet(), val likedIds: Set<Int> = emptySet(),
    val busy: Boolean = false, val page: Int = 1, val hasMore: Boolean = false, val error: String? = null)

class SearchCollectionsViewModel(private val lists: SearchListsRepository, private val addons: AddonRepository,
    private val aggregator: CatalogAggregator, private val client: StremioAddonClient, private val ids: UserIdProvider,
    private val policy: ContentPolicyRepository, private val filter: ContentPolicyFilter) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = filter.profileState(SearchCollectionsState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var query = ""
    private var sourceMode = false
    private var activeOwn = false
    private val sourceRows = mutableMapOf<String, Pair<InstalledAddon, AddonCatalog>>()
    private fun run(debounce: Boolean = false, block: suspend () -> Unit) {
        job?.cancel(); val current = ++generation
        val owner = PersonalDataScope(ids.currentUserId(), ids.currentProfileId())
        job = scope.launch(owner) {
            if (debounce) delay(300)
            _state.update { it.copy(busy = true, error = null) }
            try { block(); ids.checkPersonalScope() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) _state.update { it.copy(error = e.message?.takeIf { text -> text.startsWith("Connect Trakt in Settings") || text.startsWith("Public lists are available") || text == "Paste a public Trakt list URL." } ?: "Could not load results. Check your connection and try again.") } }
            finally { if (current == generation) _state.update { it.copy(busy = false) } }
        }
    }
    private suspend fun badges() {
        val connected = lists.connected()
        val saved = lists.saved().mapNotNull { it.ids.trakt }.toSet()
        val pinned = lists.pinned().mapNotNull { it.ids.trakt }.toSet()
        val liked = lists.liked().mapNotNull { it.ids.trakt }.toSet()
        _state.update { it.copy(connected = connected, savedIds = saved, pinnedIds = pinned, likedIds = liked) }
    }
    fun search(value: String, sources: Boolean) {
        query = value; sourceMode = sources
        if (sources) { searchSources(); return }
        run(debounce = true) {
            val snapshot = _state.value
            if (snapshot.activeList != null) return@run
            val data = lists.browse(snapshot.mode, value, 1)
            badges(); _state.update { it.copy(lists = data, page = 1, hasMore = data.size >= 20 && it.mode in setOf("explore", "liked") && !value.contains("://")) }
        }
    }
    fun selectMode(mode: String) {
        require(mode in setOf("explore", "home", "mine", "saved", "liked"))
        _state.update { it.copy(mode = mode, lists = emptyList(), activeList = null, items = emptyList(), hasMore = false) }
        search(query, false)
    }
    fun open(list: TraktBrowseList) {
        activeOwn = list.owned || _state.value.mode == "mine"
        _state.update { it.copy(activeList = list, items = emptyList(), error = null, hasMore = false) }
        run {
            val id = requireNotNull(list.ids.trakt)
            val cached = lists.cachedItems(id, activeOwn)
            _state.update { it.copy(items = cached) }
            val page = lists.items(id, 1, activeOwn)
            badges(); _state.update { it.copy(items = page.items, page = 1, hasMore = page.hasMore) }
        }
    }
    fun closeList() { job?.cancel(); generation++; _state.update { it.copy(activeList = null, items = emptyList(), busy = false) }; search(query, false) }
    fun loadMore() {
        val s = _state.value
        if (s.busy || !s.hasMore) return
        run {
            if (s.activeList != null) {
                val data = lists.items(requireNotNull(s.activeList.ids.trakt), s.page + 1, activeOwn)
                _state.update { it.copy(items = (s.items + data.items).dedupeByStableKey(), page = s.page + 1, hasMore = data.hasMore) }
            } else {
                val data = lists.browse(s.mode, query, s.page + 1)
                _state.update { it.copy(lists = (s.lists + data).distinctBy { row -> row.ids.trakt }, page = s.page + 1, hasMore = data.size >= 20) }
            }
        }
    }
    fun toggleSaved() = action { list, id -> lists.save(list, id !in _state.value.savedIds) }
    fun togglePinned() = action { list, id -> lists.pin(list, id !in _state.value.pinnedIds, activeOwn) }
    fun toggleLiked() = action { list, id -> lists.like(list, id !in _state.value.likedIds) }
    private fun action(block: suspend (TraktBrowseList, Int) -> Unit) {
        val list = _state.value.activeList ?: return
        run { block(list, requireNotNull(list.ids.trakt)); badges() }
    }
    fun selectSource(id: String) {
        _state.update { it.copy(sourceId = id, sourceTitle = sourceRows[id]?.second?.name, items = emptyList()) }
        searchSources()
    }
    private fun searchSources() = run(debounce = true) {
        sourceRows.clear()
        addons.getEnabledAddons().filter { filter.familyAllowsSource("extension:${it.manifest.id}") }.forEach { addon -> addon.manifest.catalogs.filter { cat -> cat.extra.any { it.name == "search" } && !AiRecommendationCatalogPolicy.isRemoved(cat.id, cat.name.orEmpty()) }.forEach { cat ->
            sourceRows["${addon.manifest.id}:${addon.manifestUrl.hashCode()}:${cat.type}:${cat.id}"] = addon to cat
        } }
        val choices = sourceRows.map { (id, pair) -> SearchSourceChoice(id, pair.second.name ?: pair.first.manifest.name,
            pair.first.manifest.name + " · " + if (pair.second.type == "series") "TV shows" else pair.second.type) }
        val source = _state.value.sourceId?.takeIf { it in sourceRows } ?: sourceRows.keys.firstOrNull()
        _state.update { it.copy(sources = choices, sourceId = source, sourceTitle = choices.firstOrNull { row -> row.id == source }?.title, hasMore = false) }
        val pair = sourceRows[source]
        if (query.trim().length < 2 || pair == null) { _state.update { it.copy(items = emptyList()) }; return@run }
        val raw = client.fetchCatalog(pair.first.manifestUrl.removeSuffix("/manifest.json").trimEnd('/'), pair.second.type, pair.second.id, search = query.trim())
        val items = raw.metas.map { it.copy(type = it.type.ifBlank { pair.second.type }).toMediaItem().copy(familySourceKey = "extension:${pair.first.manifest.id}") }
        val safe = filter.filterItems(policy.state.value, ContentAccessContext.DIRECT_SEARCH, items, ContentSourceType.ADDON).items.filterNot { it.isContentPlaceholder || it.isStubDetail }
        _state.update { it.copy(items = safe.dedupeByStableKey()) }
    }
}
