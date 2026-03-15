package com.torve.data.auth

import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

data class AuthUser(
    val id: String = "",
    val email: String,
    val displayName: String? = null,
)

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
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
 * Non-sensitive user metadata (email, display name) stays in [PreferencesRepository].
 */
class AuthClient(
    private val prefsRepo: PreferencesRepository,
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
        val email = prefsRepo.getString(KEY_AUTH_EMAIL) ?: return null
        if (email.isBlank()) return null
        val id = prefsRepo.getString(KEY_AUTH_USER_ID) ?: ""
        val name = prefsRepo.getString(KEY_AUTH_DISPLAY_NAME)
        return AuthUser(id = id, email = email, displayName = name)
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
            val resp = httpClient.post("${baseUrl()}/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(AuthLoginDto(
                    email = email,
                    password = password,
                    device = device,
                ))
            }
            if (!resp.status.isSuccess()) {
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
                setBody(AuthRegisterDto(
                    email = email,
                    password = password,
                    device = device,
                ))
            }
            if (!resp.status.isSuccess()) {
                val errorBody = try { resp.body<ErrorDto>() } catch (_: Exception) { null }
                return AuthResult(success = false, error = errorBody?.detail ?: "Registration failed (${resp.status.value})")
            }
            val authResp: AuthResponseDto = resp.body()
            persistAuth(authResp)
            authResp.toAuthResult(displayNameOverride = displayName)
        } catch (e: Exception) {
            AuthResult(success = false, error = "Network error: ${e.message}")
        }
    }

    suspend fun refreshTokens(): AuthResult {
        val refreshToken = secureStorage.getString(KEY_AUTH_REFRESH_TOKEN)
            ?: return AuthResult(success = false, error = "No refresh token")
        return try {
            val resp = httpClient.post("${baseUrl()}/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshDto(refreshToken))
            }
            if (!resp.status.isSuccess()) {
                clearAuth()
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
     * One-time migration of tokens from plaintext PreferencesRepository
     * to encrypted SecureStorage.
     */
    private suspend fun migrateTokensIfNeeded() {
        if (secureStorage.getString(KEY_AUTH_ACCESS_TOKEN) != null) return
        val oldAccessToken = prefsRepo.getString(KEY_AUTH_ACCESS_TOKEN) ?: return
        val oldRefreshToken = prefsRepo.getString(KEY_AUTH_REFRESH_TOKEN)
        secureStorage.putString(KEY_AUTH_ACCESS_TOKEN, oldAccessToken)
        if (oldRefreshToken != null) {
            secureStorage.putString(KEY_AUTH_REFRESH_TOKEN, oldRefreshToken)
        }
        prefsRepo.remove(KEY_AUTH_ACCESS_TOKEN)
        prefsRepo.remove(KEY_AUTH_REFRESH_TOKEN)
    }

    private suspend fun persistAuth(response: AuthResponseDto) {
        val expiresAt = Clock.System.now().toEpochMilliseconds() + TOKEN_LIFETIME_MS
        secureStorage.putString(KEY_AUTH_ACCESS_TOKEN, response.tokens.access_token)
        secureStorage.putString(KEY_AUTH_REFRESH_TOKEN, response.tokens.refresh_token)
        secureStorage.putString(KEY_TOKEN_EXPIRES_AT, expiresAt.toString())
        prefsRepo.setString(KEY_AUTH_USER_ID, response.user.id)
        prefsRepo.setString(KEY_AUTH_EMAIL, response.user.email)
    }

    private suspend fun clearAuth() {
        secureStorage.remove(KEY_AUTH_ACCESS_TOKEN)
        secureStorage.remove(KEY_AUTH_REFRESH_TOKEN)
        secureStorage.remove(KEY_TOKEN_EXPIRES_AT)
        prefsRepo.remove(KEY_AUTH_USER_ID)
        prefsRepo.remove(KEY_AUTH_EMAIL)
        prefsRepo.remove(KEY_AUTH_DISPLAY_NAME)
    }
}

// ── Backend DTOs ──

@Serializable
data class DeviceRegistrationDto(
    val installation_id: String,
    val device_name: String,
    val device_type: String,
    val platform: String,
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
private data class RefreshDto(val refresh_token: String)

@Serializable
private data class LogoutDto(val refresh_token: String?)

@Serializable
private data class ErrorDto(val detail: String? = null)

@Serializable
data class UserResponseDto(
    val id: String,
    val email: String,
    val created_at: String? = null,
)

@Serializable
data class TokensResponseDto(
    val access_token: String,
    val refresh_token: String,
    val token_type: String = "bearer",
    val expires_in: Int = 900,
)

@Serializable
data class AuthResponseDto(
    val user: UserResponseDto,
    val tokens: TokensResponseDto,
) {
    fun toAuthResult(displayNameOverride: String? = null): AuthResult = AuthResult(
        success = true,
        user = AuthUser(
            id = user.id,
            email = user.email,
            displayName = displayNameOverride,
        ),
        tokens = AuthTokens(tokens.access_token, tokens.refresh_token, tokens.expires_in),
    )
}
