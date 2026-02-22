package com.streamvault.presentation.setup

import com.streamvault.data.debrid.DebridClient
import com.streamvault.data.trakt.TraktClient
import com.streamvault.data.trakt.TraktDeviceCode
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.StreamQuality
import com.streamvault.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStep { WELCOME, DEBRID, TRAKT, QUALITY, IPTV, DONE }

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
    // IPTV
    val iptvPlaylistUrl: String = "",
    val iptvPlaylistName: String = "",
    val iptvPlaylistType: String = "m3u",
    val iptvXtreamServer: String = "",
    val iptvXtreamUsername: String = "",
    val iptvXtreamPassword: String = "",
)

class SetupWizardViewModel(
    private val debridClient: DebridClient,
    private val prefsRepo: PreferencesRepository,
    private val traktClient: TraktClient,
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
                SetupStep.WELCOME -> SetupStep.DEBRID
                SetupStep.DEBRID -> SetupStep.TRAKT
                SetupStep.TRAKT -> SetupStep.QUALITY
                SetupStep.QUALITY -> SetupStep.IPTV
                SetupStep.IPTV -> SetupStep.DONE
                SetupStep.DONE -> SetupStep.DONE
            }
            s.copy(currentStep = next)
        }
    }

    fun previousStep() {
        _state.update { s ->
            val prev = when (s.currentStep) {
                SetupStep.WELCOME -> SetupStep.WELCOME
                SetupStep.DEBRID -> SetupStep.WELCOME
                SetupStep.TRAKT -> SetupStep.DEBRID
                SetupStep.QUALITY -> SetupStep.TRAKT
                SetupStep.IPTV -> SetupStep.QUALITY
                SetupStep.DONE -> SetupStep.IPTV
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
                prefsRepo.setString("debrid_api_key", apiKey)
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
            val maxAttempts = code.expiresIn / code.interval
            for (i in 0 until maxAttempts) {
                delay(code.interval * 1000L)
                val tokens = traktClient.pollDeviceToken(code.deviceCode)
                if (tokens != null) {
                    prefsRepo.setString("trakt_access_token", tokens.accessToken)
                    prefsRepo.setString("trakt_refresh_token", tokens.refreshToken)
                    // Try to get username
                    val username = try {
                        traktClient.getUser(tokens.accessToken).username
                    } catch (_: Exception) { null }
                    _state.update {
                        it.copy(
                            traktConnected = true,
                            traktDeviceCode = null,
                            traktUsername = username,
                        )
                    }
                    return@launch
                }
            }
            _state.update { it.copy(traktDeviceCode = null, traktError = "Authorization timed out") }
        }
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

    // IPTV
    fun setIptvPlaylistUrl(url: String) {
        _state.update { it.copy(iptvPlaylistUrl = url) }
    }

    fun setIptvPlaylistName(name: String) {
        _state.update { it.copy(iptvPlaylistName = name) }
    }

    fun setIptvPlaylistType(type: String) {
        _state.update { it.copy(iptvPlaylistType = type) }
    }

    fun setIptvXtreamServer(server: String) {
        _state.update { it.copy(iptvXtreamServer = server) }
    }

    fun setIptvXtreamUsername(username: String) {
        _state.update { it.copy(iptvXtreamUsername = username) }
    }

    fun setIptvXtreamPassword(password: String) {
        _state.update { it.copy(iptvXtreamPassword = password) }
    }

    fun completeSetup() {
        scope.launch {
            val s = _state.value
            // Save quality preferences
            prefsRepo.setString("stream_max_quality", s.maxQuality.name)
            prefsRepo.setString("stream_cached_only", s.cachedOnly.toString())

            // Save Trakt credentials if provided
            if (s.traktClientId.isNotBlank()) {
                prefsRepo.setString("trakt_client_id", s.traktClientId)
                prefsRepo.setString("trakt_client_secret", s.traktClientSecret)
            }

            // Mark setup as complete
            prefsRepo.setString(KEY_SETUP_COMPLETED, "true")
        }
    }

    suspend fun isSetupCompleted(): Boolean {
        return prefsRepo.getString(KEY_SETUP_COMPLETED) == "true"
    }
}
