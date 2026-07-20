package com.torve.presentation.usenet

import com.torve.data.usenet.NewznabItem
import com.torve.util.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-scoped state cache for the NZB-driven catalog pages.
 *
 * Stores query, results, and scroll position so pages rehydrate on return
 * without re-fetching. Also owns a persistent coroutine scope so background
 * fetches survive navigation — when the user leaves the screen the fetch
 * continues and results are ready on return.
 */
object NzbBrowseStateHolder {
    data class State(
        val query: String = "",
        val items: List<NewznabItem> = emptyList(),
        val errorText: String? = null,
        val loading: Boolean = false,
        val progress: String? = null,   // e.g. "Loading... 400 / 1000"
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
        val selectedCategories: Set<String> = emptySet(),
        val selectedSportBucket: String? = null,
        val selectedLanguages: Set<String> = emptySet(),
    )

    // Persistent scope — survives composable disposal so fetches complete in background
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _flows = mutableMapOf<String, MutableStateFlow<State>>()
    private val _jobs  = mutableMapOf<String, Job>()

    fun flow(pageKey: String): StateFlow<State> =
        _flows.getOrPut(pageKey) { MutableStateFlow(State()) }.asStateFlow()

    fun get(pageKey: String): State = _flows[pageKey]?.value ?: State()

    fun put(pageKey: String, state: State) {
        _flows.getOrPut(pageKey) { MutableStateFlow(State()) }.update { state }
    }

    fun update(pageKey: String, block: (State) -> State) {
        _flows.getOrPut(pageKey) { MutableStateFlow(State()) }.update(block)
    }

    fun clear(pageKey: String) {
        _jobs[pageKey]?.cancel()
        _jobs.remove(pageKey)
        _flows[pageKey]?.update { State() }
    }

    /** Cancel any running fetch for [pageKey] and start a new one. */
    fun startFetch(pageKey: String, block: suspend () -> Unit) {
        _jobs[pageKey]?.cancel()
        _jobs[pageKey] = scope.launch {
            try {
                block()
            } catch (_: Exception) {
                update(pageKey) { it.copy(loading = false, progress = null) }
            }
        }
    }

    fun isFetching(pageKey: String): Boolean = _jobs[pageKey]?.isActive == true
}
