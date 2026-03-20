package com.torve.data.auth

import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
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
        private const val TOKEN_LIFETIME_MS = 14L * 60 * 1000 // 14 min (1 min buffer on 15 min server TTL)
        private const val REFRESH_BUFFER_MS = 60_000L
    }

    private val refreshMutex = Mutex()

    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun isLoggedIn(): Boolean {
        if (secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)?.isNotBlank() == true) return true
        // Check legacy plaintext storage and migrate if found
        migrateTokensIfNeeded()
        return secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)?.isNotBlank() == true
    }

    suspend fun getAccessToken(): String? {
        secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)?.let { return it }
        migrateTokensIfNeeded()
        return secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)
    }

    /**
     * Returns a valid access token, proactively refreshing if the token is near expiry.
     * Uses a Mutex to prevent concurrent refresh calls from racing.
     * Returns null if not logged in or refresh fails.
     */
    suspend fun getValidAccessToken(): String? {
        getAccessToken() ?: return null
        refreshMutex.withLock {
            val expiresAt = secureStorage.getString(KEY_TOKEN_EXPIRES_AT)?.toLongOrNull() ?: 0L
            val now = Clock.System.now().toEpochMilliseconds()
            if (expiresAt > 0 && now >= expiresAt - REFRESH_BUFFER_MS) {
                refreshTokens()
            }
        }
        return getAccessToken()
    }

    suspend fun getCurrentUser(): AuthUser? {
        val email = localSettingsRepository.getString(KEY_AUTH_EMAIL) ?: return null
        if (email.isBlank()) return null
        val id = localSettingsRepository.getString(KEY_AUTH_USER_ID) ?: ""
        val name = localSettingsRepository.getString(KEY_AUTH_DISPLAY_NAME)
        val verified = localSettingsRepository.getString(KEY_AUTH_IS_VERIFIED)?.toBoolean() ?: false
        return AuthUser(id = id, email = email, displayName = name, isVerified = verified)
    }

    suspend fun getAuthenticatedUser(): AuthUser? {
        val accessToken = getValidAccessToken()
        if (accessToken.isNullOrBlank()) {
            if (getCurrentUser() != null) {
                clearAuth()
            }
            return null
        }
        return getCurrentUser()
    }

    fun currentDeviceRegistration(): DeviceRegistrationDto = deviceRegistrationProvider()

    /** Server-assigned device ID, persisted from the login/register response. */
    suspend fun getServerDeviceId(): String? = localSettingsRepository.getString(KEY_AUTH_DEVICE_ID)

    suspend fun login(email: String, password: String): AuthResult {
        if (email.isBlank() || !email.contains("@")) {
            return AuthResult(success = false, error = "Please enter a valid email address")
        }
        if (password.length < 8) {
            return AuthResult(success = false, error = "Password must be at least 8 characters")
        }

        return try {
            val device = deviceRegistrationProvider()
            val resp = httpClient.post("${baseUrl()}/auth/login") {
                contentType(ContentType.Application.Json)
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
                val errorBody = try { resp.body<ErrorDto>() } catch (_: Exception) { null }
                return AuthResult(success = false, error = errorBody?.detail ?: "Login failed (${resp.status.value})")
            }
            val authResp: AuthResponseDto = resp.body()
            persistAuth(authResp)
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
            val resp = httpClient.post("${baseUrl()}/auth/register") {
                contentType(ContentType.Application.Json)
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
                val errorBody = try { resp.body<ErrorDto>() } catch (_: Exception) { null }
                return AuthResult(success = false, error = errorBody?.detail ?: "Registration failed (${resp.status.value})")
            }
            val authResp: AuthResponseDto = resp.body()
            persistAuth(authResp, fallbackDisplayName = displayName)
            authResp.toAuthResult()
        } catch (e: Exception) {
            AuthResult(success = false, error = "Network error: ${e.message}")
        }
    }

    suspend fun refreshTokens(): AuthResult {
        val refreshToken = secureStorage.getString(KEY_AUTH_REFRESH_TOKEN)
            ?: return AuthResult(success = false, error = "No refresh token")
        return try {
            val device = deviceRegistrationProvider()
            val resp = httpClient.post("${baseUrl()}/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshDto(refresh_token = refreshToken, device = device))
            }
            if (!resp.status.isSuccess()) {
                if (resp.status.value == 401) {
                    clearAuth()
                }
                return AuthResult(success = false, error = "Session expired, please log in again")
            }
            val authResp: AuthResponseDto = resp.body()
            persistAuth(authResp)
            authResp.toAuthResult()
        } catch (e: Exception) {
            AuthResult(success = false, error = "Network error: ${e.message}")
        }
    }

    /**
     * Request a password reset email for [email].
     * The backend always returns success to avoid leaking email existence.
     */
    suspend fun requestPasswordReset(email: String): AuthResult {
        if (email.isBlank() || !email.contains("@")) {
            return AuthResult(success = false, error = "Please enter a valid email address")
        }
        return try {
            val resp = httpClient.post("${baseUrl()}/auth/password-reset/request") {
                contentType(ContentType.Application.Json)
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

    /**
     * Request a new verification email. Rate limited to 1/min on the backend.
     * Always returns 200 regardless of whether the email is registered.
     */
    suspend fun resendVerification(email: String): AuthResult {
        return try {
            val resp = httpClient.post("${baseUrl()}/auth/resend-verification") {
                contentType(ContentType.Application.Json)
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
        try {
            val accessToken = secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)
            val refreshToken = secureStorage.getString(KEY_AUTH_REFRESH_TOKEN)
            if (accessToken != null) {
                httpClient.post("${baseUrl()}/auth/logout") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(LogoutDto(refreshToken))
                }
            }
        } catch (_: Exception) {
            // Best effort — server may not support /auth/logout yet
        }
        clearAuth()
    }

    suspend fun deleteAccount(): AuthResult {
        try {
            val accessToken = secureStorage.getString(KEY_AUTH_ACCESS_TOKEN)
            if (accessToken != null) {
                val response = httpClient.delete("${baseUrl()}/auth/account") {
                    bearerAuth(accessToken)
                }
                if (response.status.value !in 200..299) {
                    return AuthResult(success = false, error = "Could not delete account (HTTP ${response.status.value})")
                }
            }
        } catch (e: Exception) {
            return AuthResult(success = false, error = e.message ?: "Could not delete account")
        }
        clearAuth()
        return AuthResult(success = true)
    }

    /**
     * Attempt to restore a valid session on app startup.
     * Migrates legacy tokens to secure storage and silently refreshes.
     */
    suspend fun restoreSession(): Boolean {
        migrateTokensIfNeeded()
        if (!isLoggedIn()) return false
        return refreshTokens().success
    }

    /**
     * One-time migration of tokens from plaintext device-local settings
     * to encrypted SecureStorage.
     */
    private suspend fun migrateTokensIfNeeded() {
        if (secureStorage.getString(KEY_AUTH_ACCESS_TOKEN) != null) return
        val oldAccessToken = localSettingsRepository.getString(KEY_AUTH_ACCESS_TOKEN) ?: return
        val oldRefreshToken = localSettingsRepository.getString(KEY_AUTH_REFRESH_TOKEN)
        secureStorage.putString(KEY_AUTH_ACCESS_TOKEN, oldAccessToken)
        if (oldRefreshToken != null) {
            secureStorage.putString(KEY_AUTH_REFRESH_TOKEN, oldRefreshToken)
        }
        localSettingsRepository.remove(KEY_AUTH_ACCESS_TOKEN)
        localSettingsRepository.remove(KEY_AUTH_REFRESH_TOKEN)
    }

    private suspend fun persistAuth(
        response: AuthResponseDto,
        fallbackDisplayName: String? = null,
    ) {
        val expiresAt = Clock.System.now().toEpochMilliseconds() + TOKEN_LIFETIME_MS
        secureStorage.putString(KEY_AUTH_ACCESS_TOKEN, response.tokens.access_token)
        // Refresh responses omit refresh_token — keep the original
        response.tokens.refresh_token?.let {
            secureStorage.putString(KEY_AUTH_REFRESH_TOKEN, it)
        }
        secureStorage.putString(KEY_TOKEN_EXPIRES_AT, expiresAt.toString())
        localSettingsRepository.setString(KEY_AUTH_USER_ID, response.user.id)
        localSettingsRepository.setString(KEY_AUTH_EMAIL, response.user.email)
        (response.user.display_name ?: fallbackDisplayName?.takeIf { it.isNotBlank() })
            ?.let { localSettingsRepository.setString(KEY_AUTH_DISPLAY_NAME, it) }
        localSettingsRepository.setString(KEY_AUTH_IS_VERIFIED, response.user.is_verified.toString())
        response.device?.id?.takeIf { it.isNotBlank() }?.let {
            localSettingsRepository.setString(KEY_AUTH_DEVICE_ID, it)
        }
    }

    private suspend fun clearAuth() {
        secureStorage.remove(KEY_AUTH_ACCESS_TOKEN)
        secureStorage.remove(KEY_AUTH_REFRESH_TOKEN)
        secureStorage.remove(KEY_TOKEN_EXPIRES_AT)
        localSettingsRepository.remove(KEY_AUTH_USER_ID)
        localSettingsRepository.remove(KEY_AUTH_EMAIL)
        localSettingsRepository.remove(KEY_AUTH_DISPLAY_NAME)
        localSettingsRepository.remove(KEY_AUTH_IS_VERIFIED)
        localSettingsRepository.remove(KEY_AUTH_DEVICE_ID)
    }
}

// ── Backend DTOs ──

@Serializable
data class DeviceRegistrationDto(
    val device_id: String? = null,
    val installation_id: String,
    val device_name: String,
    val device_type: String,
    val platform: String,
    val app_version: String? = null,
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

/**
 * Handles polymorphic `detail` field from the backend:
 * - Structured errors: `{"detail": "Human-readable message"}`
 * - Validation errors (422): `{"detail": [{"msg": "...", ...}, ...]}`
 */
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
            else -> element.toString()
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
