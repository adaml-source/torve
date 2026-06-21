package com.torve.data.account

import com.torve.data.auth.AuthClient
import com.torve.data.auth.AuthUser
import com.torve.domain.model.MediaFavorite
import com.torve.domain.model.MediaItem
import com.torve.domain.model.canonicalMediaKey
import com.torve.domain.model.extractTmdbIdFromMediaId
import com.torve.domain.model.matchesMediaItemFavorite
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MediaFavoritesRepositoryImpl(
    private val authClient: AuthClient,
    private val api: MediaFavoritesRemoteDataSource,
    private val localSettingsRepository: DeviceLocalSettingsRepository,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val eventsEnabled: Boolean = true,
) : MediaFavoritesRepository {
    private val _state = MutableStateFlow(MediaFavoritesState())
    override val state: StateFlow<MediaFavoritesState> = _state.asStateFlow()

    private var eventsJob: Job? = null
    private var eventsUserId: String? = null
    private var activeUserId: String? = null
    private val sessionMutex = Mutex()
    private val pendingAdds = LinkedHashMap<String, MediaFavorite>()
    private val pendingRemoves = LinkedHashSet<String>()

    init {
        scope.launch {
            authClient.authUserFlow.collectLatest(::handleAuthUser)
        }
        scope.launch {
            handleAuthUser(authClient.getCurrentUser())
        }
    }

    private suspend fun handleAuthUser(user: AuthUser?) {
        val userId = user?.id?.takeIf { it.isNotBlank() }
            ?: authClient.getCurrentUser()?.id?.takeIf { it.isNotBlank() }
        sessionMutex.withLock {
            if (userId == null) {
                activeUserId = null
                clearPending()
                stopEventsLoop()
                _state.value = MediaFavoritesState()
                return
            }
            if (activeUserId == userId) {
                ensureEventsLoop(userId)
                return
            }
            activeUserId = userId
            clearPending()
            stopEventsLoop()
            _state.value = MediaFavoritesState()
            hydrateFromCache(userId)
            refreshInternal(userId)
            ensureEventsLoop(userId)
        }
    }

    override fun refresh(force: Boolean) {
        val userId = currentUserIdOrNull() ?: return
        scope.launch {
            refreshInternal(userId)
        }
    }

    override fun toggleFavorite(item: MediaItem) {
        val existingKeys = _state.value.items
            .filter { it.matchesMediaItemFavorite(item) }
            .map { it.mediaKey }
            .distinct()
        if (existingKeys.isNotEmpty()) {
            removeFavoriteKeys(existingKeys)
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
        pendingAdds[favorite.canonicalMediaKey()] = favorite
        pendingRemoves.removeAll(mediaKeyAliases(favorite.mediaKey))
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
                )
            }.onSuccess { result ->
                if (!isCurrentUser(userId)) return@onSuccess
                val saved = result.favorite.toDomain()
                pendingAdds.remove(saved.canonicalMediaKey())
                val confirmed = listOf(saved) + _state.value.items.filterNot { it.mediaKey == saved.mediaKey }
                applyItems(
                    confirmed,
                    isLoading = false,
                    lastError = null,
                    version = result.version ?: _state.value.version,
                    updatedAt = result.updatedAt ?: saved.updatedAt ?: _state.value.updatedAt,
                )
                cacheItems(confirmed, userId)
            }.onFailure { error ->
                pendingAdds.remove(favorite.canonicalMediaKey())
                applySnapshot(previous, favoriteUpdateErrorMessage(error), userId)
            }
        }
    }

    override fun removeFavorite(mediaKey: String) {
        removeFavoriteKeys(listOf(mediaKey))
    }

    private fun removeFavoriteKeys(mediaKeys: List<String>) {
        val userId = currentUserIdOrNull()
        if (userId == null) {
            _state.update { it.copy(lastError = "Sign in to sync favorites") }
            return
        }
        val previous = _state.value
        val keysToDelete = mediaKeys.flatMap { mediaKeyAliases(it) }.toSet()
        if (keysToDelete.none { it in previous.favoriteKeys }) return
        pendingRemoves.addAll(keysToDelete)
        keysToDelete.forEach { key -> pendingAdds.remove(key) }
        val optimistic = previous.items.filterNot { favorite ->
            favorite.mediaKey in keysToDelete || favorite.canonicalMediaKey() in keysToDelete
        }
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
                keysToDelete.forEach { key ->
                    api.deleteFavorite(token, key)
                }
            }.onSuccess {
                pendingRemoves.removeAll(keysToDelete)
            }.onFailure { error ->
                pendingRemoves.removeAll(keysToDelete)
                applySnapshot(previous, favoriteUpdateErrorMessage(error), userId)
            }
        }
    }

    override suspend fun clearSessionState() {
        stopEventsLoop()
        localSettingsRepository.remove(KEY_CACHE)
        clearPending()
        _state.value = MediaFavoritesState()
        activeUserId = null
    }

    private suspend fun refreshInternal(userId: String) {
        if (!isCurrentUser(userId)) return
        val token = authClient.getValidAccessToken() ?: return
        if (!isCurrentUser(userId)) return
        _state.update { it.copy(isLoading = true, lastError = null) }
        runCatching {
            api.listFavorites(token)
        }.onSuccess { dto ->
            if (!isCurrentUser(userId)) return@onSuccess
            val items = dto.favoriteRows.map { it.toDomain() }
            val merged = mergePending(items)
            applyItems(
                merged,
                isLoading = false,
                lastError = null,
                version = dto.version,
                updatedAt = dto.updatedAt,
            )
            cacheItems(merged, userId)
        }.onFailure { error ->
            if (!isCurrentUser(userId)) return@onFailure
            _state.update {
                it.copy(isLoading = false, lastError = favoriteLoadErrorMessage(error))
            }
        }
    }

    private fun ensureEventsLoop(userId: String) {
        if (!eventsEnabled) return
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
        version: String? = _state.value.version,
        updatedAt: String? = _state.value.updatedAt,
    ) {
        val normalized = normalizeItems(items)
        _state.value = MediaFavoritesState(
            items = normalized,
            favoriteKeys = normalized
                .flatMap { favorite -> mediaKeyAliases(favorite.mediaKey) + favorite.canonicalMediaKey() }
                .toSet(),
            isLoading = isLoading,
            lastError = lastError,
            version = version,
            updatedAt = updatedAt,
        )
    }

    private fun normalizeItems(items: List<MediaFavorite>): List<MediaFavorite> {
        return items
            .filter { it.mediaKey.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.canonicalMediaKey() }
    }

    private fun mergePending(items: List<MediaFavorite>): List<MediaFavorite> {
        val filtered = items.filterNot { favorite ->
            favorite.mediaKey in pendingRemoves || favorite.canonicalMediaKey() in pendingRemoves
        }
        val pending = pendingAdds.values.toList()
        if (pending.isEmpty()) return filtered
        val pendingKeys = pending.map { it.canonicalMediaKey() }.toSet()
        return pending + filtered.filterNot { it.canonicalMediaKey() in pendingKeys }
    }

    private fun clearPending() {
        pendingAdds.clear()
        pendingRemoves.clear()
    }

    private fun favoriteUpdateErrorMessage(error: Throwable): String {
        return when {
            error.message?.contains("sign in", ignoreCase = true) == true -> "Sign in to sync favorites"
            else -> "Could not update favorites. Please try again."
        }
    }

    private fun favoriteLoadErrorMessage(error: Throwable): String {
        return when {
            error.message?.contains("sign in", ignoreCase = true) == true -> "Sign in to sync favorites"
            else -> "Could not load favorites. Please try again."
        }
    }

    private fun mediaKeyAliases(mediaKey: String): Set<String> {
        mediaKey.extractTmdbIdFromMediaId()?.let { tmdbId ->
            val type = when {
                mediaKey.contains(":movie:", ignoreCase = true) -> "movie"
                mediaKey.contains(":tv:", ignoreCase = true) ||
                    mediaKey.contains(":series:", ignoreCase = true) -> "series"
                else -> null
            }
            if (type != null) {
                return setOf(mediaKey, "$type:$tmdbId", "${type.uppercase()}:$tmdbId")
            }
        }
        val parts = mediaKey.split(":", limit = 2)
        if (parts.size != 2) return setOf(mediaKey)
        val idPart = parts[1].takeIf { it.isNotBlank() } ?: return setOf(mediaKey)
        val canonicalType = when (parts[0].trim().lowercase()) {
            "movie" -> "movie"
            "series", "tv" -> "series"
            else -> return setOf(mediaKey)
        }
        return setOf("$canonicalType:$idPart", "${canonicalType.uppercase()}:$idPart")
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
