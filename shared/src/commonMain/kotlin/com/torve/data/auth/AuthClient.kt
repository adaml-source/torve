package com.torve.data.auth

import com.torve.data.error.deviceLimitReachedMessage
import com.torve.data.error.parseBackendError
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.ClientTrustHeaders
import com.torve.domain.security.SecureStorage
import com.torve.util.ioDispatcher
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class AuthUser(
    val id: String = "",
    val email: String,
    val displayName: String? = null,
    val isVerified: Boolean = false,
)

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Int = 900,
)

data class AuthResult(
    val success: Boolean,
    val user: AuthUser? = null,
    val tokens: AuthTokens? = null,
    val error: String? = null,
)

sealed interface AuthEvent {
    data class SessionExpired(val message: String) : AuthEvent
    /**
     * Fired exactly once after a successful [AuthClient.register]. Used
     * by the navigation layer to mark the user as needing onboarding —
     * a fresh register is the only signal we have that "this is a new
     * account that should land in the setup choice flow," because plain
     * sign-in could be a returning user with everything already
     * configured. Carries the user id for callsites that key per-user
     * onboarding flags.
     */
    data class Registered(val userId: String) : AuthEvent
}

/**
 * Authentication client for the Torve production backend at [DEFAULT_BASE_URL].
 *
 * Tokens are stored in [SecureStorage] (AES-256 via Android Keystore on Android).
 * Non-sensitive user metadata (email, display name) stays in [DeviceLocalSettingsRepository].
 */
class AuthClient(
    private val localSettingsRepository: DeviceLocalSettingsRepository,
    private val secureStorage: SecureStorage,
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val deviceRegistrationProvider: () -> DeviceRegistrationDto,
) {
    companion object {
        const val KEY_AUTH_EMAIL = "auth_email"
        const val KEY_AUTH_USER_ID = "auth_user_id"
        const val KEY_AUTH_ACCESS_TOKEN = "auth_access_token"
        const val KEY_AUTH_REFRESH_TOKEN = "auth_refresh_token"
        const val KEY_AUTH_DISPLAY_NAME = "auth_display_name"
        const val KEY_AUTH_IS_VERIFIED = "auth_is_verified"
        const val KEY_AUTH_DEVICE_ID = "auth_device_id"
        private const val KEY_TOKEN_EXPIRES_AT = "auth_token_expires_at"
        const val DEFAULT_BASE_URL = "https://api.torve.app"
        private const val TOKEN_LIFETIME_MS = 14L * 60 * 1000
        private const val REFRESH_BUFFER_MS = 60_000L
    }

    private val authStateMutex = Mutex()

    /**
     * Observable auth user state. Emitted on login, register, logout, token refresh,
     * and verification status check.
     */
    private val _authUser = MutableStateFlow<AuthUser?>(null)
    val authUserFlow: StateFlow<AuthUser?> = _authUser.asStateFlow()

    private val _authEvents = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 4)
    val authEvents = _authEvents.asSharedFlow()

    private suspend fun emitCurrentUser() {
        _authUser.value = getCurrentUser()
    }

    private val sseScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var sseJob: Job? = null

    fun startVerificationEvents() {
        if (sseJob?.isActive == true) return
        sseJob = sseScope.launch {
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    val token = getValidAccessToken() ?: break
                    val trustHeaders = clientTrustHeaders()
                    httpClient.prepareGet("${baseUrl()}/me/events") {
                        bearerAuth(token)
                        appendInstallationHeader()
                        trustHeaders?.appendTo(this)
                        headers.append("Accept", "text/event-stream")
                    }.execute { response ->
                        if (response.status == HttpStatusCode.Unauthorized) {
                            val refreshed = refreshTokens()
                            if (!refreshed.success) return@execute
                            return@execute
                        }
                        if (!response.status.isSuccess()) return@execute

                        backoffMs = 1_000L
                        val channel = response.bodyAsChannel()
                        var currentEvent = ""
                        while (isActive && !channel.isClosedForRead) {
                            val line = channel.readUTF8Line() ?: break
                            when {
                                line.startsWith("event:") -> {
                                    currentEvent = line.removePrefix("event:").trim()
                                }

                                line.startsWith("data:") -> {
                                    if (currentEvent == "EMAIL_VERIFIED") {
                                        checkVerificationStatus()
                                        return@execute
                                    }
                                    currentEvent = ""
                                }

                                line.isBlank() -> {
                                    currentEvent = ""
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Transient failure, reconnect with backoff.
                }

                val user = getCurrentUser()
                if (user == null || user.isVerified) break

                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stopVerificationEvents() {
        sseJob?.cancel()
        sseJob = null
    }

    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun isLoggedIn(): Boolean {
        if (secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)?.isNotBlank() == true) return true
        authStateMutex.withLock {
            migrateTokensIfNeededLocked()
        }
        return secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)?.isNotBlank() == true
    }

    suspend fun getAccessToken(): String? {
        secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)?.let { return it }
        return authStateMutex.withLock {
            migrateTokensIfNeededLocked()
            secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)
        }
    }

    /**
     * Returns a valid access token, proactively refreshing if the token is near expiry.
     * Refresh is serialized so only one refresh request can run at a time.
     */
    suspend fun getValidAccessToken(): String? {
        return authStateMutex.withLock {
            migrateTokensIfNeededLocked()
            val currentAccessToken = secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)
                ?: return@withLock if (!secureStorage.getString(KEY_AUTH_REFRESH_TOKEN).isNullOrBlank()) {
                    val refreshResult = refreshTokensLocked()
                    if (refreshResult.success) secureStorage.getString(KEY_AUTH_ACCESS_TOKEN) else null
                } else {
                    null
                }
            val expiresAt = secureStorage.getString(KEY_TOKEN_EXPIRES_AT)?.toLongOrNull() ?: 0L
            val now = Clock.System.now().toEpochMilliseconds()
            if (expiresAt > 0 && now >= expiresAt - REFRESH_BUFFER_MS) {
                val refreshResult = refreshTokensLocked()
                return@withLock if (refreshResult.success) {
                    secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)
                } else {
                    null
                }
            }
            currentAccessToken
        }
    }

    suspend fun getCurrentUser(): AuthUser? {
        val email = localSettingsRepository.getString(KEY_AUTH_EMAIL) ?: return null
        if (email.isBlank()) return null
        val id = localSettingsRepository.getString(KEY_AUTH_USER_ID) ?: ""
        val name = localSettingsRepository.getString(KEY_AUTH_DISPLAY_NAME)
        val verified = localSettingsRepository.getString(KEY_AUTH_IS_VERIFIED)?.toBoolean() ?: false
        return AuthUser(id = id, email = email, displayName = name, isVerified = verified)
    }

    /**
     * Returns locally cached user information as long as a local session exists.
     * Transient refresh/network failures must not wipe the session.
     */
    suspend fun getAuthenticatedUser(): AuthUser? {
        val accessToken = getValidAccessToken()
        val user = if (accessToken.isNullOrBlank()) {
            if (hasStoredSession()) getCurrentUser() else null
        } else {
            getCurrentUser()
        }
        _authUser.value = user
        return user
    }

    suspend fun checkVerificationStatus(): Boolean {
        val accessToken = getValidAccessToken() ?: return false
        return try {
            val trustHeaders = clientTrustHeaders()
            val resp = httpClient.get("${baseUrl()}/me") {
                bearerAuth(accessToken)
                appendInstallationHeader()
                trustHeaders?.appendTo(this)
            }
            if (!resp.status.isSuccess()) return false
            val user: UserResponseDto = resp.body()
            localSettingsRepository.setString(KEY_AUTH_USER_ID, user.id)
            localSettingsRepository.setString(KEY_AUTH_EMAIL, user.email)
            user.display_name?.let { localSettingsRepository.setString(KEY_AUTH_DISPLAY_NAME, it) }
            localSettingsRepository.setString(KEY_AUTH_IS_VERIFIED, user.is_verified.toString())
            emitCurrentUser()
            if (user.is_verified) stopVerificationEvents()
            user.is_verified
        } catch (_: Exception) {
            false
        }
    }

    fun currentDeviceRegistration(): DeviceRegistrationDto = deviceRegistrationProvider()

    suspend fun getServerDeviceId(): String? = localSettingsRepository.getString(KEY_AUTH_DEVICE_ID)

    /**
     * Persist tokens received from a successful [PairingApi.pollPairingStatus]
     * claim. Used by the QR-sign-in flow: the TV (signed-out) generated a
     * code, the phone (signed-in) scanned and claimed it, and the TV's next
     * poll returned auth tokens for the user. We feed those into the same
     * persistence path as a normal login so all observers (auth flow,
     * subscription VM, content-policy repo, account-session coordinator)
     * see the user appear exactly as if they'd signed in by typing their
     * email + password.
     */
    suspend fun signInViaPairing(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Int,
        userId: String,
        email: String,
        displayName: String?,
        isVerified: Boolean,
        deviceId: String?,
    ): AuthResult {
        val response = AuthResponseDto(
            user = UserResponseDto(
                id = userId,
                email = email,
                display_name = displayName,
                is_verified = isVerified,
            ),
            tokens = TokensResponseDto(
                access_token = accessToken,
                refresh_token = refreshToken,
                expires_in = expiresInSeconds,
            ),
            device = deviceId?.takeIf { it.isNotBlank() }?.let { AuthDeviceResponseDto(id = it) },
        )
        validateAuthResponse(response, requireRefreshToken = true)?.let { return it }
        authStateMutex.withLock {
            persistAuthLocked(response)
        }
        return response.toAuthResult()
    }

    suspend fun login(email: String, password: String): AuthResult {
        if (email.isBlank() || !email.contains("@")) {
            return AuthResult(success = false, error = "Please enter a valid email address")
        }
        if (password.length < 8) {
            return AuthResult(success = false, error = "Password must be at least 8 characters")
        }

        return try {
            val device = deviceRegistrationProvider()
            val trustHeaders = clientTrustHeaders()
            val resp = httpClient.post("${baseUrl()}/auth/login") {
                contentType(ContentType.Application.Json)
                appendInstallationHeader(device.installation_id)
                trustHeaders?.appendTo(this)
                setBody(
                    AuthLoginDto(
                        email = email,
                        password = password,
                        device = device,
                    ),
                )
            }
            if (!resp.status.isSuccess()) {
                if (resp.status.value == 429) {
                    return AuthResult(success = false, error = "Too many attempts. Please wait a minute and try again.")
                }
                val raw = runCatching { resp.bodyAsText() }.getOrDefault("")
                val parsed = parseBackendError(raw)
                return AuthResult(
                    success = false,
                    error = parsed.deviceLimitReachedMessage()
                        ?: parsed.message
                        ?: "Login failed (${resp.status.value})",
                )
            }
            val authResp: AuthResponseDto = resp.body()
            validateAuthResponse(authResp, requireRefreshToken = true)?.let { return it }
            authStateMutex.withLock {
                persistAuthLocked(authResp)
            }
            authResp.toAuthResult()
        } catch (e: Exception) {
            AuthResult(success = false, error = "Network error: ${e.message}")
        }
    }

    suspend fun register(email: String, password: String, displayName: String?): AuthResult {
        if (email.isBlank() || !email.contains("@")) {
            return AuthResult(success = false, error = "Please enter a valid email address")
        }
        if (password.length < 8) {
            return AuthResult(success = false, error = "Password must be at least 8 characters")
        }

        return try {
            val device = deviceRegistrationProvider()
            val trustHeaders = clientTrustHeaders()
            val resp = httpClient.post("${baseUrl()}/auth/register") {
                contentType(ContentType.Application.Json)
                appendInstallationHeader(device.installation_id)
                trustHeaders?.appendTo(this)
                setBody(
                    AuthRegisterDto(
                        email = email,
                        password = password,
                        device = device,
                    ),
                )
            }
            if (!resp.status.isSuccess()) {
                if (resp.status.value == 429) {
                    return AuthResult(success = false, error = "Too many attempts. Please wait a minute and try again.")
                }
                val raw = runCatching { resp.bodyAsText() }.getOrDefault("")
                val parsed = parseBackendError(raw)
                return AuthResult(
                    success = false,
                    error = parsed.deviceLimitReachedMessage()
                        ?: parsed.message
                        ?: "Registration failed (${resp.status.value})",
                )
            }
            val authResp: AuthResponseDto = resp.body()
            validateAuthResponse(authResp, requireRefreshToken = true)?.let { return it }
            authStateMutex.withLock {
                persistAuthLocked(authResp, fallbackDisplayName = displayName)
            }
            // Fire AuthEvent.Registered exactly once on successful
            // register so the navigation layer can mark the user as
            // needing onboarding. This is the ONLY place that emits it
            // — plain sign-in (login + signInViaPairing) doesn't, since
            // a returning user shouldn't be force-routed into setup.
            _authEvents.emit(AuthEvent.Registered(userId = authResp.user.id))
            authResp.toAuthResult()
        } catch (e: Exception) {
            AuthResult(success = false, error = "Network error: ${e.message}")
        }
    }

    suspend fun refreshTokens(): AuthResult {
        return authStateMutex.withLock {
            refreshTokensLocked()
        }
    }

    suspend fun requestPasswordReset(email: String): AuthResult {
        if (email.isBlank() || !email.contains("@")) {
            return AuthResult(success = false, error = "Please enter a valid email address")
        }
        return try {
            val trustHeaders = clientTrustHeaders()
            val resp = httpClient.post("${baseUrl()}/auth/password-reset/request") {
                contentType(ContentType.Application.Json)
                trustHeaders?.appendTo(this)
                setBody(PasswordResetRequestDto(email.trim()))
            }
            if (!resp.status.isSuccess()) {
                if (resp.status.value == 429) {
                    return AuthResult(success = false, error = "Please wait a minute before requesting another reset email.")
                }
                val errorBody = try { resp.body<ErrorDto>() } catch (_: Exception) { null }
                return AuthResult(
                    success = false,
                    error = errorBody?.detail ?: "Request failed (${resp.status.value})",
                )
            }
            AuthResult(success = true)
        } catch (e: Exception) {
            AuthResult(success = false, error = "Network error: ${e.message}")
        }
    }

    suspend fun resendVerification(email: String): AuthResult {
        return try {
            val accessToken = getValidAccessToken()
            val trustHeaders = clientTrustHeaders()
            val resp = httpClient.post("${baseUrl()}/auth/resend-verification") {
                accessToken?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                appendInstallationHeader()
                trustHeaders?.appendTo(this)
                setBody(ResendVerificationDto(email.trim()))
            }
            if (resp.status.value == 429) {
                return AuthResult(success = false, error = "Please wait a minute before requesting another verification email.")
            }
            if (!resp.status.isSuccess()) {
                return AuthResult(success = false, error = "Failed to send verification email")
            }
            AuthResult(success = true)
        } catch (e: Exception) {
            AuthResult(success = false, error = "Network error: ${e.message}")
        }
    }

    suspend fun logout() {
        authStateMutex.withLock {
            try {
                val accessToken = secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)
                val refreshToken = secureStorage.getString(KEY_AUTH_REFRESH_TOKEN)
                if (accessToken != null) {
                    val trustHeaders = clientTrustHeaders()
                    httpClient.post("${baseUrl()}/auth/logout") {
                        bearerAuth(accessToken)
                        contentType(ContentType.Application.Json)
                        appendInstallationHeader()
                        trustHeaders?.appendTo(this)
                        setBody(LogoutDto(refreshToken))
                    }
                }
            } catch (_: Exception) {
                // Best effort - server may not support /auth/logout yet.
            }
            clearAuthLocked()
        }
    }

    suspend fun deleteAccount(): AuthResult {
        val accessToken = getValidAccessToken()
            ?: return AuthResult(success = false, error = "Not signed in")
        return try {
            val trustHeaders = clientTrustHeaders()
            val response = httpClient.delete("${baseUrl()}/auth/account") {
                bearerAuth(accessToken)
                appendInstallationHeader()
                trustHeaders?.appendTo(this)
            }
            if (response.status.value !in 200..299) {
                AuthResult(success = false, error = "Could not delete account (HTTP ${response.status.value})")
            } else {
                authStateMutex.withLock { clearAuthLocked() }
                AuthResult(success = true)
            }
        } catch (e: Exception) {
            AuthResult(success = false, error = e.message ?: "Could not delete account")
        }
    }

    /**
     * Attempt to restore a valid session on app startup.
     */
    suspend fun restoreSession(): Boolean {
        authStateMutex.withLock {
            migrateTokensIfNeededLocked()
        }
        if (!isLoggedIn()) return false
        return refreshTokens().success
    }

    private suspend fun migrateTokensIfNeededLocked() {
        if (secureStorage.getString(KEY_AUTH_ACCESS_TOKEN) != null) return
        val oldAccessToken = localSettingsRepository.getString(KEY_AUTH_ACCESS_TOKEN) ?: return
        val oldRefreshToken = localSettingsRepository.getString(KEY_AUTH_REFRESH_TOKEN)
        secureStorage.updateStrings(
            mapOf(
                KEY_AUTH_ACCESS_TOKEN to oldAccessToken,
                KEY_AUTH_REFRESH_TOKEN to oldRefreshToken,
            ),
        )
        localSettingsRepository.remove(KEY_AUTH_ACCESS_TOKEN)
        localSettingsRepository.remove(KEY_AUTH_REFRESH_TOKEN)
    }

    private suspend fun persistAuthLocked(
        response: AuthResponseDto,
        fallbackDisplayName: String? = null,
    ) {
        val expiresAt = Clock.System.now().toEpochMilliseconds() + TOKEN_LIFETIME_MS
        val refreshToken = response.tokens.refresh_token
            ?.takeIf { it.isNotBlank() }
            ?: secureStorage.getString(KEY_AUTH_REFRESH_TOKEN)
        secureStorage.updateStrings(
            mapOf(
                KEY_AUTH_ACCESS_TOKEN to response.tokens.access_token,
                KEY_AUTH_REFRESH_TOKEN to refreshToken,
                KEY_TOKEN_EXPIRES_AT to expiresAt.toString(),
            ),
        )
        localSettingsRepository.setString(KEY_AUTH_USER_ID, response.user.id)
        localSettingsRepository.setString(KEY_AUTH_EMAIL, response.user.email)
        (response.user.display_name ?: fallbackDisplayName?.takeIf { it.isNotBlank() })
            ?.let { localSettingsRepository.setString(KEY_AUTH_DISPLAY_NAME, it) }
        localSettingsRepository.setString(KEY_AUTH_IS_VERIFIED, response.user.is_verified.toString())
        response.device?.id?.takeIf { it.isNotBlank() }?.let {
            localSettingsRepository.setString(KEY_AUTH_DEVICE_ID, it)
        }
        emitCurrentUser()
        if (!response.user.is_verified) {
            startVerificationEvents()
        } else {
            stopVerificationEvents()
        }
    }

    private suspend fun clearAuthLocked() {
        stopVerificationEvents()
        secureStorage.updateStrings(
            mapOf(
                KEY_AUTH_ACCESS_TOKEN to null,
                KEY_AUTH_REFRESH_TOKEN to null,
                KEY_TOKEN_EXPIRES_AT to null,
            ),
        )
        localSettingsRepository.remove(KEY_AUTH_USER_ID)
        localSettingsRepository.remove(KEY_AUTH_EMAIL)
        localSettingsRepository.remove(KEY_AUTH_DISPLAY_NAME)
        localSettingsRepository.remove(KEY_AUTH_IS_VERIFIED)
        localSettingsRepository.remove(KEY_AUTH_DEVICE_ID)
        _authUser.value = null
    }

    private suspend fun refreshTokensLocked(): AuthResult {
        val refreshToken = secureStorage.getString(KEY_AUTH_REFRESH_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return AuthResult(success = false, error = "No refresh token")

        return try {
            val device = deviceRegistrationProvider()
            val trustHeaders = clientTrustHeaders()
            val resp = httpClient.post("${baseUrl()}/auth/refresh") {
                contentType(ContentType.Application.Json)
                appendInstallationHeader(device.installation_id)
                trustHeaders?.appendTo(this)
                setBody(RefreshDto(refresh_token = refreshToken, device = device))
            }
            if (!resp.status.isSuccess()) {
                val errorBody = runCatching { resp.body<ErrorDto>() }.getOrNull()
                val message = errorBody?.detail?.takeIf { it.isNotBlank() }
                    ?: "Session expired, please log in again"
                if (shouldInvalidateSession(resp.status)) {
                    invalidateSessionLocked(message)
                }
                return AuthResult(success = false, error = message)
            }

            val authResp: AuthResponseDto = resp.body()
            validateAuthResponse(authResp, requireRefreshToken = true)?.let { validationError ->
                invalidateSessionLocked(validationError.error ?: "Session expired, please log in again")
                return validationError
            }
            persistAuthLocked(authResp)
            authResp.toAuthResult()
        } catch (e: Exception) {
            AuthResult(success = false, error = "Network error: ${e.message}")
        }
    }

    private fun validateAuthResponse(
        response: AuthResponseDto,
        requireRefreshToken: Boolean,
    ): AuthResult? {
        if (response.tokens.access_token.isBlank()) {
            return AuthResult(success = false, error = "Server returned an invalid access token")
        }
        if (requireRefreshToken && response.tokens.refresh_token.isNullOrBlank()) {
            return AuthResult(success = false, error = "Server returned an invalid refresh token")
        }
        return null
    }

    private suspend fun invalidateSessionLocked(message: String) {
        val hadSession = hasStoredSessionLocked() || getCurrentUser() != null
        clearAuthLocked()
        if (hadSession) {
            _authEvents.emit(AuthEvent.SessionExpired(message))
        }
    }

    private fun shouldInvalidateSession(status: HttpStatusCode): Boolean {
        return status == HttpStatusCode.BadRequest ||
            status == HttpStatusCode.Unauthorized ||
            status == HttpStatusCode.Forbidden
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendInstallationHeader(
        installationId: String = deviceRegistrationProvider().installation_id,
    ) {
        installationId.takeIf { it.isNotBlank() }?.let {
            header("X-Torve-Installation-Id", it)
        }
    }

    private suspend fun clientTrustHeaders(
        includeIntegrityToken: Boolean = false,
    ) = ClientTrustHeaders.capture(includeIntegrityToken)

    private suspend fun hasStoredSession(): Boolean {
        return authStateMutex.withLock { hasStoredSessionLocked() }
    }

    private suspend fun hasStoredSessionLocked(): Boolean {
        return secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)?.isNotBlank() == true ||
            secureStorage.getString(KEY_AUTH_REFRESH_TOKEN)?.isNotBlank() == true
    }
}

@Serializable
data class DeviceRegistrationDto(
    val device_id: String? = null,
    val installation_id: String,
    val device_name: String,
    val device_type: String,
    val platform: String,
    val app_version: String? = null,
    val stable_device_id: String? = null,
)

@Serializable
private data class AuthLoginDto(
    val email: String,
    val password: String,
    val device: DeviceRegistrationDto,
)

@Serializable
private data class AuthRegisterDto(
    val email: String,
    val password: String,
    val device: DeviceRegistrationDto,
)

@Serializable
private data class PasswordResetRequestDto(val email: String)

@Serializable
private data class ResendVerificationDto(val email: String)

@Serializable
private data class RefreshDto(
    val refresh_token: String,
    val device: DeviceRegistrationDto? = null,
)

@Serializable
private data class LogoutDto(val refresh_token: String?)

private object DetailSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Detail", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.mapNotNull { item ->
                (item as? JsonObject)?.get("msg")?.jsonPrimitive?.contentOrNull
            }.joinToString("; ").ifEmpty { "Validation error" }

            is JsonObject -> {
                // Structured error: {"code": "...", "message": "..."} — extract human message,
                // never return raw JSON object string to prevent [object Object]-style leakage.
                element["message"]?.jsonPrimitive?.contentOrNull
                    ?: element["msg"]?.jsonPrimitive?.contentOrNull
                    ?: element["code"]?.jsonPrimitive?.contentOrNull
                    ?: "Request failed"
            }
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value != null) encoder.encodeString(value)
    }
}

@Serializable
private data class ErrorDto(
    @Serializable(with = DetailSerializer::class)
    val detail: String? = null,
)

@Serializable
data class UserResponseDto(
    val id: String,
    val email: String,
    val display_name: String? = null,
    val is_active: Boolean = true,
    val is_verified: Boolean = false,
    val created_at: String? = null,
)

@Serializable
data class TokensResponseDto(
    val access_token: String,
    val refresh_token: String? = null,
    val token_type: String = "bearer",
    val expires_in: Int = 900,
)

@Serializable
data class AuthDeviceResponseDto(
    val id: String = "",
)

@Serializable
data class AuthResponseDto(
    val user: UserResponseDto,
    val tokens: TokensResponseDto,
    val device: AuthDeviceResponseDto? = null,
) {
    fun toAuthResult(): AuthResult = AuthResult(
        success = true,
        user = AuthUser(
            id = user.id,
            email = user.email,
            displayName = user.display_name,
            isVerified = user.is_verified,
        ),
        tokens = AuthTokens(tokens.access_token, tokens.refresh_token, tokens.expires_in),
    )
}
