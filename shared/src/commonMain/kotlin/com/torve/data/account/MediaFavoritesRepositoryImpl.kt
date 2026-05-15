package com.torve.data.account

import com.torve.data.auth.AuthClient
import com.torve.domain.model.MediaFavorite
import com.torve.domain.model.MediaItem
import com.torve.domain.model.favoriteMediaKey
import com.torve.domain.model.toMediaFavorite
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.MediaFavoritesRepository
import com.torve.domain.repository.MediaFavoritesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MediaFavoritesRepositoryImpl(
    private val authClient: AuthClient,
    private val api: MediaFavoritesApi,
    private val localSettingsRepository: DeviceLocalSettingsRepository,
    private val json: Json,
) : MediaFavoritesRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(MediaFavoritesState())
    override val state: StateFlow<MediaFavoritesState> = _state.asStateFlow()

    private var eventsJob: Job? = null
    private var eventsUserId: String? = null
    private var activeUserId: String? = null

    init {
        scope.launch {
            authClient.authUserFlow.collectLatest { user ->
                val userId = user?.id?.takeIf { it.isNotBlank() }
                if (userId == null) {
                    activeUserId = null
                    stopEventsLoop()
                    _state.value = MediaFavoritesState()
                } else {
                    if (activeUserId != userId) {
                        activeUserId = userId
                        stopEventsLoop()
                        _state.value = MediaFavoritesState()
                    }
                    hydrateFromCache(userId)
                    refreshInternal(userId)
                    ensureEventsLoop(userId)
                }
            }
        }
        scope.launch {
            val userId = authClient.getCurrentUser()?.id?.takeIf { it.isNotBlank() }
            if (userId != null) {
                activeUserId = userId
                hydrateFromCache(userId)
                refreshInternal(userId)
                ensureEventsLoop(userId)
            }
        }
    }

    override fun refresh(force: Boolean) {
        val userId = currentUserIdOrNull() ?: return
        scope.launch {
            refreshInternal(userId)
        }
    }

    override fun toggleFavorite(item: MediaItem) {
        val key = item.favoriteMediaKey()
        if (key in _state.value.favoriteKeys) {
            removeFavorite(key)
        } else {
            addFavorite(item)
        }
    }

    override fun addFavorite(item: MediaItem) {
        val userId = currentUserIdOrNull()
        if (userId == null) {
            _state.update { it.copy(lastError = "Sign in to sync favorites") }
            return
        }
        val favorite = item.toMediaFavorite()
        val previous = _state.value
        if (favorite.mediaKey in previous.favoriteKeys) return
        val optimistic = listOf(favorite) + previous.items.filterNot { it.mediaKey == favorite.mediaKey }
        applyItems(optimistic, isLoading = false, lastError = null)

        scope.launch {
            cacheItems(optimistic, userId)
            val token = authClient.getValidAccessToken()
            if (token.isNullOrBlank()) {
                applySnapshot(previous, "Sign in to sync favorites", userId)
                return@launch
            }
            if (!isCurrentUser(userId)) {
                return@launch
            }
            runCatching {
                api.upsertFavorite(
                    accessToken = token,
                    favorite = favorite,
                    sourceDeviceId = authClient.getServerDeviceId(),
                ).toDomain()
            }.onSuccess { saved ->
                if (!isCurrentUser(userId)) return@onSuccess
                val confirmed = listOf(saved) + _state.value.items.filterNot { it.mediaKey == saved.mediaKey }
                applyItems(confirmed, isLoading = false, lastError = null)
                cacheItems(confirmed, userId)
            }.onFailure { error ->
                applySnapshot(previous, error.message ?: "Failed to save favorite", userId)
            }
        }
    }

    override fun removeFavorite(mediaKey: String) {
        val userId = currentUserIdOrNull()
        if (userId == null) {
            _state.update { it.copy(lastError = "Sign in to sync favorites") }
            return
        }
        val previous = _state.value
        if (mediaKey !in previous.favoriteKeys) return
        val optimistic = previous.items.filterNot { it.mediaKey == mediaKey }
        applyItems(optimistic, isLoading = false, lastError = null)

        scope.launch {
            cacheItems(optimistic, userId)
            val token = authClient.getValidAccessToken()
            if (token.isNullOrBlank()) {
                applySnapshot(previous, "Sign in to sync favorites", userId)
                return@launch
            }
            if (!isCurrentUser(userId)) {
                return@launch
            }
            runCatching {
                api.deleteFavorite(token, mediaKey)
            }.onFailure { error ->
                applySnapshot(previous, error.message ?: "Failed to remove favorite", userId)
            }
        }
    }

    override suspend fun clearSessionState() {
        stopEventsLoop()
        localSettingsRepository.remove(KEY_CACHE)
        _state.value = MediaFavoritesState()
        activeUserId = null
    }

    private suspend fun refreshInternal(userId: String) {
        if (!isCurrentUser(userId)) return
        val token = authClient.getValidAccessToken() ?: return
        if (!isCurrentUser(userId)) return
        _state.update { it.copy(isLoading = true, lastError = null) }
        runCatching {
            api.listFavorites(token).items.map { it.toDomain() }
        }.onSuccess { items ->
            if (!isCurrentUser(userId)) return@onSuccess
            applyItems(items, isLoading = false, lastError = null)
            cacheItems(items, userId)
        }.onFailure { error ->
            if (!isCurrentUser(userId)) return@onFailure
            _state.update {
                it.copy(isLoading = false, lastError = error.message ?: "Failed to load favorites")
            }
        }
    }

    private fun ensureEventsLoop(userId: String) {
        if (eventsJob?.isActive == true && eventsUserId == userId) return
        stopEventsLoop()
        eventsUserId = userId
        eventsJob = scope.launch {
            var backoffMs = 1_000L
            while (isActive && isCurrentUser(userId)) {
                val token = authClient.getValidAccessToken()
                if (token.isNullOrBlank()) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                    continue
                }
                runCatching {
                    api.collectFavoriteInvalidations(token) {
                        refreshInternal(userId)
                    }
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun stopEventsLoop() {
        eventsJob?.cancel()
        eventsJob = null
        eventsUserId = null
    }

    private suspend fun hydrateFromCache(userId: String) {
        if (!isCurrentUser(userId)) return
        val raw = localSettingsRepository.getString(KEY_CACHE) ?: return
        val cached = runCatching {
            json.decodeFromString<List<MediaFavorite>>(raw)
        }.getOrDefault(emptyList())
        if (cached.isNotEmpty() && isCurrentUser(userId)) {
            applyItems(cached, isLoading = false, lastError = null)
        }
    }

    private suspend fun cacheItems(items: List<MediaFavorite>, userId: String?) {
        if (!isCurrentUser(userId)) return
        localSettingsRepository.setString(KEY_CACHE, json.encodeToString(normalizeItems(items)))
    }

    private fun applySnapshot(snapshot: MediaFavoritesState, error: String, userId: String?) {
        if (!isCurrentUser(userId)) return
        _state.value = snapshot.copy(lastError = error, isLoading = false)
        scope.launch { cacheItems(snapshot.items, userId) }
    }

    private fun applyItems(
        items: List<MediaFavorite>,
        isLoading: Boolean,
        lastError: String?,
    ) {
        val normalized = normalizeItems(items)
        _state.value = MediaFavoritesState(
            items = normalized,
            favoriteKeys = normalized.map { it.mediaKey }.toSet(),
            isLoading = isLoading,
            lastError = lastError,
        )
    }

    private fun normalizeItems(items: List<MediaFavorite>): List<MediaFavorite> {
        return items
            .filter { it.mediaKey.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.mediaKey }
    }

    private fun currentUserIdOrNull(): String? {
        return authClient.authUserFlow.value?.id?.takeIf { it.isNotBlank() }
            ?: activeUserId
    }

    private fun isCurrentUser(userId: String?): Boolean {
        if (userId.isNullOrBlank()) return false
        val current = currentUserIdOrNull()
        return current == userId
    }

    private companion object {
        const val KEY_CACHE = "media_favorites_cache"
    }
}
