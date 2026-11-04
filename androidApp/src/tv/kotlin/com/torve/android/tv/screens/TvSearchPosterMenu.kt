package com.torve.android.tv.screens

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import com.torve.android.R
import com.torve.android.tv.components.TvMediaContextMenu
import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.android.tv.components.TvMediaContextMenuState
import com.torve.android.tv.components.tvSettingsLabel
import com.torve.android.tv.launchTvTrailer
import com.torve.android.tv.NotificationType
import com.torve.android.tv.TvNotificationQueue
import com.torve.domain.integrations.MediaLifecycleRequest
import com.torve.domain.integrations.MediaLifecycleService
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.library.LibraryRequestMonitor
import kotlinx.coroutines.*
import org.koin.compose.koinInject

internal class TvSearchPosterMenuHandle(val open: (MediaItem, Rect?, FocusRequester) -> Unit)

/** Search reuses the app's watchlist actions; library/trailer work starts only after a choice. */
@Composable
internal fun rememberTvSearchPosterMenu(
    owner: String?,
    actionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)?,
    onContextAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)?,
    isScreenActive: Boolean = true,
): TvSearchPosterMenuHandle = key(owner, isScreenActive) {
    rememberTvSearchPosterMenuForProfile(owner, actionsForItem, onContextAction)
}

@Composable
private fun rememberTvSearchPosterMenuForProfile(
    owner: String?,
    actionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)?,
    onContextAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)?,
): TvSearchPosterMenuHandle {
    val metadata: MetadataRepository = koinInject()
    val library: MediaLifecycleService = koinInject()
    val monitor: LibraryRequestMonitor = koinInject()
    val context = LocalContext.current
    val seasonLabel = tvSettingsLabel("Add season to library")
    val trailerLabel = tvSettingsLabel("Watch trailer")
    val timeoutMessage = tvSettingsLabel("The action timed out. Please try again.")
    val failureMessage = tvSettingsLabel("The action could not be completed. Please try again.")
    val noSeasonsMessage = tvSettingsLabel("Season information is unavailable.")
    val loadingSeasonsLabel = tvSettingsLabel("Loading seasons")
    val cancelLabel = tvSettingsLabel("Cancel")
    val scope = rememberCoroutineScope()
    val window = LocalWindowInfo.current
    var menu by remember(owner) { mutableStateOf<TvMediaContextMenuState?>(null) }
    var origin by remember(owner) { mutableStateOf<FocusRequester?>(null) }
    var restore by remember(owner) { mutableStateOf<FocusRequester?>(null) }
    val busy = remember(owner) { mutableStateMapOf<String, Boolean>() }

    fun dismiss() {
        menu = null
        restore = origin
    }

    LaunchedEffect(menu, restore, window.isWindowFocused) {
        if (menu != null || !window.isWindowFocused) return@LaunchedEffect
        val target = restore ?: return@LaunchedEffect
        repeat(12) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.isSuccess) {
                restore = null
                return@LaunchedEffect
            }
        }
        restore = null
    }

    fun runAction(item: MediaItem, action: suspend () -> Unit) {
        val key = item.tvSearchStableKey()
        if (busy[key] == true) return
        busy[key] = true
        scope.launch {
            try {
                withTimeout(20_000) { action() }
            } catch (_: TimeoutCancellationException) {
                if (menu?.actions?.firstOrNull()?.id == "search_library_loading") dismiss()
                TvNotificationQueue.post(timeoutMessage, NotificationType.ERROR)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (menu?.actions?.firstOrNull()?.id == "search_library_loading") dismiss()
                // Provider errors can contain authenticated URLs. Never surface them here.
                TvNotificationQueue.post(failureMessage, NotificationType.ERROR)
            } finally {
                busy.remove(key)
            }
        }
    }

    suspend fun resolve(item: MediaItem, details: Boolean): MediaItem = withContext(Dispatchers.IO) {
        val identified = when {
            item.tmdbId != null -> item
            item.id.extractTmdbIdOrNull() != null -> item.copy(tmdbId = item.id.extractTmdbIdOrNull())
            else -> (item.imdbId ?: item.id.extractImdbIdOrNull())?.let {
                metadata.findByImdbId(it, if (item.type == MediaType.SERIES) "tv" else "movie")
            } ?: item
        }
        val id = identified.tmdbId
        if (details && id != null) {
            metadata.getDetail(if (identified.type == MediaType.SERIES) "tv" else "movie", id)
        } else identified
    }

    fun requestLibrary(item: MediaItem, season: Int?) = runAction(item) {
        val identified = resolve(item, details = false)
        val message = withContext(Dispatchers.IO) {
            requestTvSearchLibraryCopy(identified, season, library, monitor::track)
        }
        TvNotificationQueue.post(message)
    }

    menu?.let { state ->
        TvMediaContextMenu(state, onDismiss = ::dismiss, onAction = { action ->
            when {
                busy[state.item.tvSearchStableKey()] == true &&
                    (action.id == "search_library" || action.id == "trailer" || action.id.startsWith("search_library_season:")) -> Unit
                action.id == "search_library_loading" -> Unit
                action.id == "search_library_cancel" -> dismiss()
                action.id == "search_library" && state.item.type == MediaType.SERIES -> {
                    val loadingMenu = state.copy(actions = listOf(
                        TvMediaContextMenuAction("search_library_loading", loadingSeasonsLabel),
                        TvMediaContextMenuAction("search_library_cancel", cancelLabel)))
                    menu = loadingMenu
                    runAction(state.item) {
                        val detail = resolve(state.item, details = true)
                        if (menu !== loadingMenu) return@runAction
                        val seasons = detail.seasons.filter { it.seasonNumber > 0 }.distinctBy { it.seasonNumber }.sortedBy { it.seasonNumber }
                        if (seasons.isEmpty()) {
                            dismiss()
                            TvNotificationQueue.post(noSeasonsMessage, NotificationType.ERROR)
                        } else {
                            restore = null
                            menu = state.copy(item = detail, actions = seasons.map {
                                TvMediaContextMenuAction("search_library_season:${it.seasonNumber}",
                                    context.getString(R.string.detail_add_season_to_library, it.seasonNumber))
                            })
                        }
                    }
                }
                action.id == "search_library" -> {
                    dismiss()
                    requestLibrary(state.item, null)
                }
                action.id.startsWith("search_library_season:") -> {
                    dismiss()
                    action.id.substringAfter(':').toIntOrNull()?.let { requestLibrary(state.item, it) }
                }
                action.id == "trailer" -> {
                    dismiss()
                    runAction(state.item) {
                        val item = if (state.item.trailerKey.isNullOrBlank()) resolve(state.item, details = true) else state.item
                        val key = item.trailerKey
                        if (key.isNullOrBlank() || !launchTvTrailer(context, key)) {
                            TvNotificationQueue.post(context.getString(R.string.tv_notification_trailer_unavailable), NotificationType.ERROR)
                        }
                    }
                }
                else -> {
                    dismiss()
                    onContextAction?.invoke(state.item, action, state.progress)
                }
            }
        })
    }

    return TvSearchPosterMenuHandle { item, bounds, requester ->
        origin = requester
        restore = null
        val existing = actionsForItem?.invoke(item, null).orEmpty()
        menu = TvMediaContextMenuState(item, null, bounds, tvSearchPosterActions(item, existing,
            context.getString(R.string.detail_add_to_library), seasonLabel, trailerLabel))
    }
}

internal fun tvSearchPosterActions(
    item: MediaItem,
    existing: List<TvMediaContextMenuAction>,
    libraryLabel: String,
    seasonLabel: String,
    trailerLabel: String,
): List<TvMediaContextMenuAction> = buildList {
    add(TvMediaContextMenuAction("search_library", if (item.type == MediaType.SERIES) seasonLabel else libraryLabel))
    existing.firstOrNull { it.id == "toggle_watchlist" }?.let(::add)
    // Search results commonly lack trailer metadata; resolve it when the user chooses this.
    add(TvMediaContextMenuAction("trailer", trailerLabel))
    addAll(existing.filter { it.id !in setOf("toggle_watchlist", "trailer") })
}

internal suspend fun requestTvSearchLibraryCopy(
    item: MediaItem,
    season: Int?,
    service: MediaLifecycleService,
    track: suspend (com.torve.domain.integrations.MediaLifecycleStatus, String, List<Int>) -> Unit,
): String {
    val id = item.tmdbId ?: return "This title could not be identified for a library request."
    val seasons = if (item.type == MediaType.SERIES) listOf(requireNotNull(season)) else emptyList()
    val before = service.getStatus(id, item.type, seasons)
    if (before.state == MediaLifecycleState.UNCONFIGURED) return "Connect library downloads in Settings first."
    if (before.state == MediaLifecycleState.AVAILABLE) return "Already available in your library"
    if (!before.canRequest) {
        if (before.isInProgress) {
            track(before, item.title, seasons)
            return "Already in your library queue"
        }
        return "Library availability could not be checked. No request was sent."
    }
    val status = service.request(MediaLifecycleRequest(id, item.type, seasons))
    track(status, item.title, seasons)
    return when (status.state) {
        MediaLifecycleState.UNCONFIGURED -> "Connect library downloads in Settings first."
        MediaLifecycleState.PENDING_APPROVAL -> "Request sent for approval"
        MediaLifecycleState.AVAILABLE -> "Already available in your library"
        MediaLifecycleState.APPROVED, MediaLifecycleState.PROCESSING, MediaLifecycleState.PARTIALLY_AVAILABLE -> "Added to your library queue"
        else -> "The library request could not be submitted."
    }
}
