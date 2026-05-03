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

    /**
     * If [jumpToStep] was called for a target past TERMS while the user
     * hadn't accepted yet, we redirect them to TERMS and stash the
     * intended destination here. After they tick + advance, [nextStep]
     * (or the gating callsite) consumes this and lands them where they
     * originally meant to go instead of the next sequential step.
     */
    private var pendingPostTermsJump: SetupStep? = null

    companion object {
        const val KEY_SETUP_COMPLETED = "setup_completed"

        // Legacy boolean key — held the literal "true" string when
        // terms were accepted. Kept for migration only; new writes go
        // to KEY_TERMS_ACCEPTED_VERSION.
        const val KEY_TERMS_ACCEPTED = "setup_terms_accepted"

        // Versioned acceptance — stores the integer version of the
        // terms the user accepted. Compared against
        // [CURRENT_TERMS_VERSION]; if the persisted value is lower,
        // the user is treated as not having accepted (and the TERMS
        // step is shown again). Bump CURRENT_TERMS_VERSION whenever
        // terms / TMDB / Trakt disclosure copy materially changes —
        // returning users will then re-consent on next launch.
        const val KEY_TERMS_ACCEPTED_VERSION = "setup_terms_accepted_version"
        const val CURRENT_TERMS_VERSION = 1
    }

    init {
        // Hydrate persisted terms acceptance so a returning user who
        // already agreed never sees the disclaimer-as-blocker again.
        // Also covers process death / cold start within the same install.
        // Versioned form first; falls back to the legacy boolean key,
        // which is treated as version 1 (the version in effect when
        // that key was written). Migrating the legacy value to the
        // versioned key on the same launch keeps the prefs
        // canonical going forward.
        scope.launch {
            val versionStr = prefsRepo.getString(KEY_TERMS_ACCEPTED_VERSION)
            val acceptedVersion = versionStr?.toIntOrNull() ?: run {
                // No versioned value — check the legacy boolean.
                val legacy = prefsRepo.getString(KEY_TERMS_ACCEPTED) == "true"
                if (legacy) {
                    // Migrate to the versioned key. The legacy "true"
                    // means the user accepted whatever was version 1.
                    prefsRepo.setString(KEY_TERMS_ACCEPTED_VERSION, "1")
                    1
                } else {
                    0
                }
            }
            if (acceptedVersion >= CURRENT_TERMS_VERSION) {
                _state.update { it.copy(termsAccepted = true) }
            }
        }
    }

    fun nextStep() {
        _state.update { s ->
            // Special case: leaving TERMS while a deep-link target is
            // queued means the user originally tapped "Set up Trakt"
            // (or similar) on the hub and we deflected them to TERMS.
            // Honor that original destination instead of the default
            // TERMS → DEBRID step.
            val deepLinkTarget = pendingPostTermsJump
            val next = if (s.currentStep == SetupStep.TERMS && deepLinkTarget != null) {
                pendingPostTermsJump = null
                deepLinkTarget
            } else {
                when (s.currentStep) {
                    SetupStep.WELCOME -> SetupStep.TERMS
                    SetupStep.TERMS -> SetupStep.DEBRID
                    SetupStep.DEBRID -> SetupStep.TRAKT
                    SetupStep.TRAKT -> SetupStep.QUALITY
                    SetupStep.QUALITY -> SetupStep.CHANNELS
                    SetupStep.CHANNELS -> SetupStep.DONE
                    SetupStep.DONE -> SetupStep.DONE
                }
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

    /**
     * Jump directly to [step]. Used by the credential-first hub to deep-link
     * into the matching guided-wizard step when the user picks an intent
     * card; preserved separately from [nextStep] so the linear forward/back
     * UX still works for users who chose the guided path.
     *
     * Gated on [SetupUiState.termsAccepted]: if the user hasn't accepted
     * yet AND the target lives past TERMS, we land them on TERMS first
     * and remember the original target. After they tick the box and
     * advance, [nextStep] consumes [pendingPostTermsJump] and lands
     * them on what they originally clicked. Without this gate, every
     * "Set up X" button on the SetupIntentHub bypassed the disclaimer
     * — users only ever saw it by walking backward through the wizard,
     * and TMDB's API attribution requirement is contractual.
     */
    fun jumpToStep(step: SetupStep) {
        val termsAccepted = _state.value.termsAccepted
        val pastTerms = step != SetupStep.WELCOME && step != SetupStep.TERMS
        if (!termsAccepted && pastTerms) {
            pendingPostTermsJump = step
            _state.update { it.copy(currentStep = SetupStep.TERMS) }
        } else {
            _state.update { it.copy(currentStep = step) }
        }
    }

    /** True when the user must accept terms before doing anything else. */
    fun needsTermsAcceptance(): Boolean = !_state.value.termsAccepted

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
        // Persist so a returning user doesn't see the disclaimer again
        // on every cold start. Untick clears both the versioned key
        // and the legacy boolean. Tick writes the current version to
        // the versioned key (the legacy key is left alone — init
        // migration writes "1" there for hydration if the version
        // key is missing, but on accept we don't need to touch it).
        scope.launch {
            if (accepted) {
                prefsRepo.setString(KEY_TERMS_ACCEPTED_VERSION, CURRENT_TERMS_VERSION.toString())
                // Also set the legacy key in case some external reader
                // still queries it. Cheap to keep in sync.
                prefsRepo.setString(KEY_TERMS_ACCEPTED, "true")
            } else {
                prefsRepo.remove(KEY_TERMS_ACCEPTED_VERSION)
                prefsRepo.remove(KEY_TERMS_ACCEPTED)
            }
        }
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
