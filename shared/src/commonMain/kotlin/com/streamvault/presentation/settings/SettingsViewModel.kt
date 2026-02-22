package com.streamvault.presentation.settings

import com.streamvault.data.debrid.DebridClient
import com.streamvault.data.trakt.TraktClient
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val debridClient: DebridClient,
    private val traktClient: TraktClient,
    private val prefsRepo: PreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var debridPollJob: Job? = null
    private var traktPollJob: Job? = null

    companion object {
        const val KEY_DEBRID_PROVIDER = "debrid_provider"
        const val KEY_DEBRID_API_KEY = "debrid_api_key"
        const val KEY_DEBRID_RD_REFRESH = "debrid_rd_refresh_token"
        const val KEY_DEBRID_RD_CLIENT_ID = "debrid_rd_client_id"
        const val KEY_DEBRID_RD_CLIENT_SECRET = "debrid_rd_client_secret"
        const val KEY_DEBRID_RD_EXPIRES_AT = "debrid_rd_expires_at"
        const val KEY_TRAKT_CLIENT_ID = "trakt_client_id"
        const val KEY_TRAKT_CLIENT_SECRET = "trakt_client_secret"
        const val KEY_TRAKT_ACCESS_TOKEN = "trakt_access_token"
        const val KEY_TRAKT_REFRESH_TOKEN = "trakt_refresh_token"
    }

    init {
        loadSavedSettings()
    }

    private fun loadSavedSettings() {
        scope.launch {
            val provider = prefsRepo.getString(KEY_DEBRID_PROVIDER)?.let {
                try { DebridServiceType.valueOf(it) } catch (_: Exception) { null }
            } ?: DebridServiceType.REAL_DEBRID

            val apiKey = prefsRepo.getString(KEY_DEBRID_API_KEY) ?: ""
            val traktClientId = prefsRepo.getString(KEY_TRAKT_CLIENT_ID) ?: ""
            val traktClientSecret = prefsRepo.getString(KEY_TRAKT_CLIENT_SECRET) ?: ""
            val traktAccessToken = prefsRepo.getString(KEY_TRAKT_ACCESS_TOKEN) ?: ""
            val traktRefreshToken = prefsRepo.getString(KEY_TRAKT_REFRESH_TOKEN) ?: ""

            // Set Trakt credentials
            if (traktClientId.isNotBlank()) {
                traktClient.setCredentials(traktClientId, traktClientSecret)
            }

            _state.update {
                it.copy(
                    debridProvider = provider,
                    debridApiKey = apiKey,
                    debridConnected = apiKey.isNotBlank(),
                    traktClientId = traktClientId,
                    traktClientSecret = traktClientSecret,
                    traktAccessToken = traktAccessToken,
                    traktRefreshToken = traktRefreshToken,
                    traktConnected = traktAccessToken.isNotBlank(),
                )
            }

            // Verify stored credentials
            if (apiKey.isNotBlank()) {
                verifyDebridConnection()
            }
            if (traktAccessToken.isNotBlank()) {
                verifyTraktConnection()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Debrid
    // -------------------------------------------------------------------------

    fun setDebridProvider(provider: DebridServiceType) {
        _state.update { it.copy(debridProvider = provider) }
        scope.launch { prefsRepo.setString(KEY_DEBRID_PROVIDER, provider.name) }
    }

    fun setDebridApiKey(apiKey: String) {
        _state.update { it.copy(debridApiKey = apiKey) }
    }

    fun connectDebridWithApiKey() {
        val apiKey = _state.value.debridApiKey
        if (apiKey.isBlank()) return

        scope.launch {
            _state.update { it.copy(debridLoading = true, debridError = null) }
            val result = debridClient.verifyApiKey(_state.value.debridProvider, apiKey)
            if (result.success) {
                prefsRepo.setString(KEY_DEBRID_API_KEY, apiKey)
                _state.update {
                    it.copy(
                        debridConnected = true,
                        debridUser = result.user,
                        debridLoading = false,
                    )
                }
            } else {
                _state.update {
                    it.copy(debridLoading = false, debridError = result.error)
                }
            }
        }
    }

    fun startDebridDeviceAuth() {
        val provider = _state.value.debridProvider
        if (!debridClient.supportsDeviceAuth(provider)) return

        scope.launch {
            _state.update { it.copy(debridLoading = true, debridError = null) }
            try {
                val code = debridClient.getDeviceCode(provider)
                _state.update { it.copy(debridDeviceCode = code, debridLoading = false) }
                if (code != null) pollDebridDevice(code)
            } catch (e: Exception) {
                _state.update {
                    it.copy(debridLoading = false, debridError = e.message)
                }
            }
        }
    }

    private fun pollDebridDevice(code: com.streamvault.data.debrid.DeviceCodeInfo) {
        debridPollJob?.cancel()
        debridPollJob = scope.launch {
            _state.update { it.copy(isPollingDebrid = true) }
            val maxAttempts = code.expiresIn / code.interval
            for (i in 0 until maxAttempts) {
                delay(code.interval * 1000L)
                val result = debridClient.pollDeviceAuth(
                    _state.value.debridProvider,
                    code.deviceCode,
                    code.userCode,
                )
                if (result.done && result.apiKey != null) {
                    prefsRepo.setString(KEY_DEBRID_API_KEY, result.apiKey)
                    result.oauthTokens?.let { tokens ->
                        prefsRepo.setString(KEY_DEBRID_RD_REFRESH, tokens.refreshToken)
                        prefsRepo.setString(KEY_DEBRID_RD_CLIENT_ID, tokens.clientId)
                        prefsRepo.setString(KEY_DEBRID_RD_CLIENT_SECRET, tokens.clientSecret)
                        prefsRepo.setString(KEY_DEBRID_RD_EXPIRES_AT, tokens.expiresAt.toString())
                    }
                    _state.update {
                        it.copy(
                            debridApiKey = result.apiKey,
                            debridConnected = true,
                            debridDeviceCode = null,
                            isPollingDebrid = false,
                        )
                    }
                    verifyDebridConnection()
                    return@launch
                }
            }
            _state.update {
                it.copy(isPollingDebrid = false, debridError = "Device auth timed out")
            }
        }
    }

    private suspend fun verifyDebridConnection() {
        val result = debridClient.verifyApiKey(
            _state.value.debridProvider,
            _state.value.debridApiKey,
        )
        _state.update {
            it.copy(
                debridUser = result.user,
                debridConnected = result.success,
                debridError = if (!result.success) result.error else null,
            )
        }
    }

    fun disconnectDebrid() {
        debridPollJob?.cancel()
        scope.launch {
            prefsRepo.remove(KEY_DEBRID_API_KEY)
            prefsRepo.remove(KEY_DEBRID_RD_REFRESH)
            prefsRepo.remove(KEY_DEBRID_RD_CLIENT_ID)
            prefsRepo.remove(KEY_DEBRID_RD_CLIENT_SECRET)
            prefsRepo.remove(KEY_DEBRID_RD_EXPIRES_AT)
        }
        _state.update {
            it.copy(
                debridApiKey = "",
                debridConnected = false,
                debridUser = null,
                debridDeviceCode = null,
                isPollingDebrid = false,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Trakt
    // -------------------------------------------------------------------------

    fun setTraktCredentials(clientId: String, clientSecret: String) {
        traktClient.setCredentials(clientId, clientSecret)
        _state.update { it.copy(traktClientId = clientId, traktClientSecret = clientSecret) }
        scope.launch {
            prefsRepo.setString(KEY_TRAKT_CLIENT_ID, clientId)
            prefsRepo.setString(KEY_TRAKT_CLIENT_SECRET, clientSecret)
        }
    }

    fun startTraktDeviceAuth() {
        if (_state.value.traktClientId.isBlank()) {
            _state.update { it.copy(traktError = "Set Trakt Client ID first") }
            return
        }

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

    private fun pollTraktDevice(code: com.streamvault.data.trakt.TraktDeviceCode) {
        traktPollJob?.cancel()
        traktPollJob = scope.launch {
            _state.update { it.copy(isPollingTrakt = true) }
            val maxAttempts = code.expiresIn / code.interval
            for (i in 0 until maxAttempts) {
                delay(code.interval * 1000L)
                val tokens = traktClient.pollDeviceToken(code.deviceCode)
                if (tokens != null) {
                    prefsRepo.setString(KEY_TRAKT_ACCESS_TOKEN, tokens.accessToken)
                    prefsRepo.setString(KEY_TRAKT_REFRESH_TOKEN, tokens.refreshToken)
                    _state.update {
                        it.copy(
                            traktAccessToken = tokens.accessToken,
                            traktRefreshToken = tokens.refreshToken,
                            traktConnected = true,
                            traktDeviceCode = null,
                            isPollingTrakt = false,
                        )
                    }
                    verifyTraktConnection()
                    return@launch
                }
            }
            _state.update {
                it.copy(isPollingTrakt = false, traktError = "Device auth timed out")
            }
        }
    }

    private suspend fun verifyTraktConnection() {
        try {
            val user = traktClient.getUser(_state.value.traktAccessToken)
            _state.update { it.copy(traktUser = user, traktConnected = true) }
        } catch (e: Exception) {
            _state.update {
                it.copy(traktConnected = false, traktError = e.message)
            }
        }
    }

    fun disconnectTrakt() {
        traktPollJob?.cancel()
        val token = _state.value.traktAccessToken
        scope.launch {
            if (token.isNotBlank()) traktClient.revokeToken(token)
            prefsRepo.remove(KEY_TRAKT_ACCESS_TOKEN)
            prefsRepo.remove(KEY_TRAKT_REFRESH_TOKEN)
        }
        _state.update {
            it.copy(
                traktAccessToken = "",
                traktRefreshToken = "",
                traktConnected = false,
                traktUser = null,
                traktDeviceCode = null,
                isPollingTrakt = false,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Getters for other ViewModels
    // -------------------------------------------------------------------------

    fun getDebridProvider(): DebridServiceType = _state.value.debridProvider
    fun getDebridApiKey(): String = _state.value.debridApiKey
    fun isDebridConnected(): Boolean = _state.value.debridConnected
    fun getTraktAccessToken(): String = _state.value.traktAccessToken
    fun isTraktConnected(): Boolean = _state.value.traktConnected
}
