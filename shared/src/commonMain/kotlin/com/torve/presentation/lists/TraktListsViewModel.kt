package com.torve.presentation.lists

import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.PersonalDataScope
import com.torve.data.auth.checkPersonalScope
import com.torve.data.trakt.*
import com.torve.data.trakt.api.*
import com.torve.domain.model.*
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TraktListsState(val loading: Boolean = false, val connected: Boolean = true,
    val lists: List<TraktPersonalList> = emptyList(), val selected: TraktPersonalList? = null,
    val items: List<MediaItem> = emptyList(), val membership: Map<Int, Boolean> = emptyMap(),
    val busy: Boolean = false, val error: String? = null, val message: String? = null)

class TraktListsViewModel(private val api: TraktAuthorizedApi, private val metadata: MetadataRepository,
    private val userIds: UserIdProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)) {
    private val _state = MutableStateFlow(TraktListsState())
    val state = _state.asStateFlow()
    private var target: MediaItem? = null
    private var loadedPerson: String? = null
    private var job: Job? = null
    private val membershipRevisions = mutableMapOf<Int, Long>()
    fun clear() = scope.cancel()
    fun load(forTitle: MediaItem? = null) {
        if (target?.id != forTitle?.id || target?.type != forTitle?.type) {
            membershipRevisions.clear()
            _state.update { it.copy(membership = emptyMap()) }
        }
        target = forTitle
        job?.cancel()
        job = scope.launch(PersonalDataScope(userIds.currentUserId(), userIds.currentProfileId())) {
            val owner = userIds.currentDataUserId()
            if (loadedPerson != null && loadedPerson != owner) {
                membershipRevisions.clear()
                _state.value = TraktListsState()
            }
            loadedPerson = owner
            _state.update { it.copy(loading = true, error = null) }
            try {
                if (!api.hasConnection()) { _state.value = TraktListsState(connected = false); return@launch }
                val lists = api.getPersonalLists().filter { it.ids.trakt != null }
                if (owner != userIds.currentDataUserId()) return@launch
                _state.update { it.copy(loading = false, connected = true, lists = lists) }
                if (forTitle != null) for (list in lists) {
                    val id = list.ids.trakt!!
                    val revision = membershipRevisions[id] ?: 0L
                    val contains = try { api.getPersonalListItems(id).any { matchesTitle(it, forTitle) } }
                        catch (error: Exception) { if (error is CancellationException) throw error; continue }
                    if (owner != userIds.currentDataUserId()) return@launch
                    if ((membershipRevisions[id] ?: 0L) == revision)
                        _state.update { it.copy(membership = it.membership + (id to contains)) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (owner == userIds.currentDataUserId()) _state.update { it.copy(loading = false, connected = e !is TraktAuthorizationRequiredException,
                    error = "Could not load Trakt lists. Check your connection or reconnect Trakt.") }
            }
        }
    }
    fun browse(list: TraktPersonalList) = action {
        val entries = api.getPersonalListItems(list.ids.trakt!!)
        val items = entries.mapNotNull { item ->
            val info = item.movie ?: item.show ?: return@mapNotNull null
            val type = if (item.movie != null) MediaType.MOVIE else MediaType.SERIES
            val tmdb = info.ids?.tmdb
            if (tmdb != null) MediaItem(tmdb.toString(), tmdbId = tmdb, imdbId = info.ids?.imdb,
                type = type, title = info.title, year = info.year)
            else info.ids?.imdb?.let { metadata.findByImdbId(it, if (type == MediaType.MOVIE) "movie" else "tv") }
        }
        userIds.checkPersonalScope()
        _state.update { it.copy(selected = list, items = items) }
        val person = userIds.currentDataUserId()
        val limit = kotlinx.coroutines.sync.Semaphore(3)
        // Display the list immediately, then enrich artwork without removing its focusable rows.
        scope.launch {
            items.take(60).map { item -> async {
                val detail = try { limit.acquire(); try {
                    item.tmdbId?.let { metadata.getDetail(if (item.type == MediaType.MOVIE) "movie" else "tv", it) }
                } finally { limit.release() } }
                    catch (error: Exception) { if (error is CancellationException) throw error; null }
                if (detail != null && userIds.currentDataUserId() == person && _state.value.selected?.ids?.trakt == list.ids.trakt)
                    _state.update { s -> s.copy(items = s.items.map { if (it.id == item.id && it.type == item.type) detail.copy(id = item.id) else it }) }
            } }.awaitAll()
        }
    }
    fun closeList() { _state.update { it.copy(selected = null, items = emptyList(), message = null, error = null) } }
    fun create(name: String, onSaved: () -> Unit = {}) = action {
        val created = api.createPersonalList(name)
        _state.update { it.copy(lists = it.lists + created, message = "List created") }; onSaved()
    }
    fun rename(list: TraktPersonalList, name: String, onSaved: () -> Unit = {}) = action {
        val renamed = api.renamePersonalList(list.ids.trakt!!, name)
        _state.update { it.copy(lists = it.lists.map { old -> if (old.ids.trakt == renamed.ids.trakt) renamed else old }, message = "List renamed") }; onSaved()
    }
    fun delete(list: TraktPersonalList, onSaved: () -> Unit = {}) = action {
        api.deletePersonalList(list.ids.trakt!!)
        _state.update { it.copy(lists = it.lists.filterNot { old -> old.ids.trakt == list.ids.trakt }, message = "List deleted") }; onSaved()
    }
    fun toggleMembership(list: TraktPersonalList) = action {
        val title = target ?: return@action
        val id = list.ids.trakt!!
        val contains = _state.value.membership[id] ?: api.getPersonalListItems(id).any { matchesTitle(it, title) }
        val value = TraktHistoryMovie(TraktIds(tmdb = title.tmdbId, imdb = title.imdbId))
        check(value.ids.tmdb != null || value.ids.imdb != null) { "This title needs a TMDB or IMDb match" }
        val body = if (title.type == MediaType.MOVIE) TraktListItemsWrite(movies = listOf(value)) else TraktListItemsWrite(shows = listOf(value))
        api.mutatePersonalListItems(id, body, remove = contains)
        membershipRevisions[id] = (membershipRevisions[id] ?: 0L) + 1L
        _state.update { it.copy(membership = it.membership + (id to !contains),
            message = if (contains) "Removed from ${list.name}" else "Added to ${list.name}") }
    }
    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        val owner = userIds.currentDataUserId()
        _state.update { it.copy(busy = true, error = null, message = null) }
        scope.launch(PersonalDataScope(userIds.currentUserId(), userIds.currentProfileId())) {
            try { block() }
            catch (e: Exception) { if (e is CancellationException) throw e
                if (owner == userIds.currentDataUserId()) _state.update { it.copy(error = "Trakt could not save the change. Check your connection and try again.") } }
            finally { if (owner == userIds.currentDataUserId()) _state.update { it.copy(busy = false) } }
        }
    }
}

internal fun matchesTitle(entry: TraktWatchlistItemResponse, item: MediaItem): Boolean {
    if ((entry.movie != null) != (item.type == MediaType.MOVIE)) return false
    val ids = (entry.movie ?: entry.show)?.ids ?: return false
    return item.tmdbId?.let { it == ids.tmdb } == true || item.imdbId?.let { it == ids.imdb } == true
}
