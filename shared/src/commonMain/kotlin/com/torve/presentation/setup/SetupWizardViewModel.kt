package com.torve.presentation.setup

import com.torve.data.debrid.DebridClient
import com.torve.data.trakt.TraktClient
import com.torve.data.trakt.TraktDeviceCode
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.data.trakt.repo.TraktSyncRepository
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.StreamQuality
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.repository.WatchlistRepository
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

enum class SetupStep { WELCOME, TERMS, DEBRID, TRAKT, QUALITY, CHANNELS, DONE }

data class SetupUiState(
    val currentStep: SetupStep = SetupStep.WELCOME,
    // Debrid
    val debridProvider: DebridServiceType = DebridServiceType.REAL_DEBRID,
    val debridApiKey: String = "",
    val debridConnected: Boolean = false,
    val debridLoading: Boolean = false,
    val debridError: String? = null,
    // Trakt
    val traktClientId: String = "",
    val traktClientSecret: String = "",
    val traktConnected: Boolean = false,
    val traktDeviceCode: TraktDeviceCode? = null,
    val traktLoading: Boolean = false,
    val traktError: String? = null,
    val traktUsername: String? = null,
    // Quality
    val maxQuality: StreamQuality = StreamQuality.FHD_1080P,
    val cachedOnly: Boolean = true,
    // Channels
    val channelPlaylistUrl: String = "",
    val channelPlaylistName: String = "",
    val channelPlaylistType: String = "m3u",
    val channelXtreamServer: String = "",
    val channelXtreamUsername: String = "",
    val channelXtreamPassword: String = "",
    // Terms acceptance
    val termsAccepted: Boolean = false,
)

class SetupWizardViewModel(
    private val debridClient: DebridClient,
    private val prefsRepo: PreferencesRepository,
    private val traktClient: TraktClient,
    private val tokenStore: TraktTokenStore,
    private val integrationSecretStore: IntegrationSecretStore,
    private val watchlistRepo: WatchlistRepository,
    private val watchProgressRepo: WatchProgressRepository,
    private val watchHistoryRepo: WatchHistoryRepository,
    private val traktSyncRepo: TraktSyncRepository,
    private val settingsRefreshNotifier: SettingsRefreshNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    companion object {
        const val KEY_SETUP_COMPLETED = "setup_completed"
    }

    fun nextStep() {
        _state.update { s ->
            val next = when (s.currentStep) {
                SetupStep.WELCOME -> SetupStep.TERMS
                SetupStep.TERMS -> SetupStep.DEBRID
                SetupStep.DEBRID -> SetupStep.TRAKT
                SetupStep.TRAKT -> SetupStep.QUALITY
                SetupStep.QUALITY -> SetupStep.CHANNELS
                SetupStep.CHANNELS -> SetupStep.DONE
                SetupStep.DONE -> SetupStep.DONE
            }
            s.copy(currentStep = next)
        }
    }

    fun previousStep() {
        _state.update { s ->
            val prev = when (s.currentStep) {
                SetupStep.WELCOME -> SetupStep.WELCOME
                SetupStep.TERMS -> SetupStep.WELCOME
                SetupStep.DEBRID -> SetupStep.TERMS
                SetupStep.TRAKT -> SetupStep.DEBRID
                SetupStep.QUALITY -> SetupStep.TRAKT
                SetupStep.CHANNELS -> SetupStep.QUALITY
                SetupStep.DONE -> SetupStep.CHANNELS
            }
            s.copy(currentStep = prev)
        }
    }

    fun skipStep() {
        nextStep()
    }

    // Debrid
    fun setDebridProvider(provider: DebridServiceType) {
        _state.update { it.copy(debridProvider = provider) }
    }

    fun setDebridApiKey(key: String) {
        _state.update { it.copy(debridApiKey = key) }
    }

    fun connectDebrid() {
        val apiKey = _state.value.debridApiKey
        if (apiKey.isBlank()) return

        scope.launch {
            _state.update { it.copy(debridLoading = true, debridError = null) }
            val result = debridClient.verifyApiKey(_state.value.debridProvider, apiKey)
            if (result.success) {
                prefsRepo.setString("debrid_provider", _state.value.debridProvider.name)
                integrationSecretStore.put(IntegrationSecretKey.DEBRID_API_KEY, apiKey)
                prefsRepo.remove("debrid_api_key")
                _state.update { it.copy(debridConnected = true, debridLoading = false) }
            } else {
                _state.update {
                    it.copy(debridLoading = false, debridError = result.error ?: "Connection failed")
                }
            }
        }
    }

    // Trakt — OAuth Device Code Flow
    fun startTraktAuth() {
        scope.launch {
            _state.update { it.copy(traktLoading = true, traktError = null) }
            try {
                val code = traktClient.getDeviceCode()
                _state.update { it.copy(traktDeviceCode = code, traktLoading = false) }
                pollTraktDevice(code)
            } catch (e: Exception) {
                _state.update { it.copy(traktLoading = false, traktError = e.message) }
            }
        }
    }

    private fun pollTraktDevice(code: TraktDeviceCode) {
        scope.launch {
            var interval = code.interval.toLong()
            val maxAttempts = code.expiresIn / code.interval
            for (i in 0 until maxAttempts) {
                delay(interval * 1000L)
                when (val result = traktClient.pollDeviceToken(code.deviceCode)) {
                    is com.torve.data.trakt.TraktPollResult.Success -> {
                        tokenStore.write(result.tokens)
                        val username = try {
                            traktClient.getUser(result.tokens.accessToken).username
                        } catch (_: Exception) { null }
                        _state.update {
                            it.copy(
                                traktConnected = true,
                                traktDeviceCode = null,
                                traktUsername = username,
                            )
                        }
                        // Kick off initial Trakt import so watchlist / continue
                        // watching rails populate without requiring an app restart.
                        scope.launch { initialTraktImport() }
                        return@launch
                    }
                    is com.torve.data.trakt.TraktPollResult.Pending -> { /* Keep polling */ }
                    is com.torve.data.trakt.TraktPollResult.SlowDown -> { interval += 1 }
                    is com.torve.data.trakt.TraktPollResult.Expired,
                    is com.torve.data.trakt.TraktPollResult.Denied,
                    is com.torve.data.trakt.TraktPollResult.AlreadyUsed -> {
                        _state.update { it.copy(traktDeviceCode = null, traktError = "Authorization failed. Try again.") }
                        return@launch
                    }
                    is com.torve.data.trakt.TraktPollResult.TransientError -> {
                        // DNS / timeout / 5xx — keep polling, don't abandon
                        // the auth window on a single hiccup.
                        interval = (interval + 1).coerceAtMost(15L)
                    }
                    is com.torve.data.trakt.TraktPollResult.Error -> {
                        _state.update { it.copy(traktDeviceCode = null, traktError = result.message) }
                        return@launch
                    }
                }
            }
            _state.update { it.copy(traktDeviceCode = null, traktError = "Authorization timed out") }
        }
    }

    private suspend fun initialTraktImport() {
        runCatching { watchlistRepo.syncFromTrakt() }
        runCatching { watchProgressRepo.syncFromTrakt() }
        runCatching { watchHistoryRepo.syncFromTrakt() }
        runCatching { traktSyncRepo.syncRatingsFromTrakt() }
        runCatching { traktSyncRepo.flushPendingWrites() }
        prefsRepo.setString("trakt_last_sync_time", Clock.System.now().toEpochMilliseconds().toString())
        // Wake up Home / other screens that observe this notifier so the
        // freshly imported watchlist + continue-watching rails appear.
        settingsRefreshNotifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
    }

    @Suppress("unused")
    fun setTraktClientId(id: String) {
        _state.update { it.copy(traktClientId = id) }
    }

    @Suppress("unused")
    fun setTraktClientSecret(secret: String) {
        _state.update { it.copy(traktClientSecret = secret) }
    }

    // Quality
    fun setMaxQuality(quality: StreamQuality) {
        _state.update { it.copy(maxQuality = quality) }
    }

    fun setCachedOnly(enabled: Boolean) {
        _state.update { it.copy(cachedOnly = enabled) }
    }

    // Terms
    fun setTermsAccepted(accepted: Boolean) {
        _state.update { it.copy(termsAccepted = accepted) }
    }

    // Channels
    fun setChannelPlaylistUrl(url: String) {
        _state.update { it.copy(channelPlaylistUrl = url) }
    }

    fun setChannelPlaylistName(name: String) {
        _state.update { it.copy(channelPlaylistName = name) }
    }

    fun setChannelPlaylistType(type: String) {
        _state.update { it.copy(channelPlaylistType = type) }
    }

    fun setChannelXtreamServer(server: String) {
        _state.update { it.copy(channelXtreamServer = server) }
    }

    fun setChannelXtreamUsername(username: String) {
        _state.update { it.copy(channelXtreamUsername = username) }
    }

    fun setChannelXtreamPassword(password: String) {
        _state.update { it.copy(channelXtreamPassword = password) }
    }

    fun completeSetup() {
        scope.launch {
            completeSetupNow()
        }
    }

    suspend fun completeSetupNow() {
        val s = _state.value
        // Save quality preferences
        prefsRepo.setString("stream_max_quality", s.maxQuality.name)
        prefsRepo.setString("stream_cached_only", s.cachedOnly.toString())

        // Save Trakt credentials if provided
        if (s.traktClientId.isNotBlank()) {
            prefsRepo.setString("trakt_client_id", s.traktClientId)
            if (s.traktClientSecret.isNotBlank()) {
                integrationSecretStore.put(IntegrationSecretKey.TRAKT_CLIENT_SECRET, s.traktClientSecret)
                prefsRepo.remove("trakt_client_secret")
            }
        }

        // Mark setup as complete
        prefsRepo.setString(KEY_SETUP_COMPLETED, "true")
    }

    suspend fun isSetupCompleted(): Boolean {
        return prefsRepo.getString(KEY_SETUP_COMPLETED) == "true"
    }
}
