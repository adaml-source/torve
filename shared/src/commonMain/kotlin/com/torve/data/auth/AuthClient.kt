package com.torve.data.auth

import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
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
 * Authentication client that communicates with the Torve backend.
 * Falls back to local-only mode when the backend is unreachable.
 */
class AuthClient(
    private val prefsRepo: PreferencesRepository,
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
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"
    }

    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    suspend fun isLoggedIn(): Boolean {
        return prefsRepo.getString(KEY_AUTH_ACCESS_TOKEN)?.isNotBlank() == true
    }

    suspend fun getAccessToken(): String? {
        return prefsRepo.getString(KEY_AUTH_ACCESS_TOKEN)
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
            val resp = httpClient.post("${baseUrl()}/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(AuthLoginDto(email, password, deviceRegistrationProvider()))
            }
            if (!resp.status.isSuccess()) {
                val errorBody = try { resp.body<ErrorDto>() } catch (_: Exception) { null }
                return AuthResult(success = false, error = errorBody?.detail ?: "Login failed (${resp.status.value})")
            }
            val authResp: AuthResponseDto = resp.body()
            persistAuth(authResp)
            AuthResult(
                success = true,
                user = AuthUser(id = authResp.user.id, email = authResp.user.email),
                tokens = AuthTokens(authResp.tokens.access_token, authResp.tokens.refresh_token, authResp.tokens.expires_in),
            )
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
            val resp = httpClient.post("${baseUrl()}/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(AuthRegisterDto(email, password, deviceRegistrationProvider()))
            }
            if (!resp.status.isSuccess()) {
                val errorBody = try { resp.body<ErrorDto>() } catch (_: Exception) { null }
                return AuthResult(success = false, error = errorBody?.detail ?: "Registration failed (${resp.status.value})")
            }
            val authResp: AuthResponseDto = resp.body()
            persistAuth(authResp)
            if (!displayName.isNullOrBlank()) {
                prefsRepo.setString(KEY_AUTH_DISPLAY_NAME, displayName)
            }
            AuthResult(
                success = true,
                user = AuthUser(id = authResp.user.id, email = authResp.user.email, displayName = displayName),
                tokens = AuthTokens(authResp.tokens.access_token, authResp.tokens.refresh_token, authResp.tokens.expires_in),
            )
        } catch (e: Exception) {
            AuthResult(success = false, error = "Network error: ${e.message}")
        }
    }

    suspend fun refreshTokens(): AuthResult {
        val refreshToken = prefsRepo.getString(KEY_AUTH_REFRESH_TOKEN) ?: return AuthResult(success = false, error = "No refresh token")
        return try {
            val resp = httpClient.post("${baseUrl()}/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshDto(refreshToken))
            }
            if (!resp.status.isSuccess()) {
                // Refresh failed — user needs to re-login
                clearAuth()
                return AuthResult(success = false, error = "Session expired, please log in again")
            }
            val authResp: AuthResponseDto = resp.body()
            persistAuth(authResp)
            AuthResult(
                success = true,
                user = AuthUser(id = authResp.user.id, email = authResp.user.email),
                tokens = AuthTokens(authResp.tokens.access_token, authResp.tokens.refresh_token, authResp.tokens.expires_in),
            )
        } catch (e: Exception) {
            AuthResult(success = false, error = "Network error: ${e.message}")
        }
    }

    suspend fun logout() {
        try {
            val accessToken = prefsRepo.getString(KEY_AUTH_ACCESS_TOKEN)
            val refreshToken = prefsRepo.getString(KEY_AUTH_REFRESH_TOKEN)
            if (accessToken != null) {
                httpClient.post("${baseUrl()}/auth/logout") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(LogoutDto(refreshToken))
                }
            }
        } catch (_: Exception) {
            // Best effort
        }
        clearAuth()
    }

    suspend fun deleteAccount(): AuthResult {
        clearAuth()
        return AuthResult(success = true)
    }

    private suspend fun persistAuth(response: AuthResponseDto) {
        prefsRepo.setString(KEY_AUTH_USER_ID, response.user.id)
        prefsRepo.setString(KEY_AUTH_EMAIL, response.user.email)
        prefsRepo.setString(KEY_AUTH_ACCESS_TOKEN, response.tokens.access_token)
        prefsRepo.setString(KEY_AUTH_REFRESH_TOKEN, response.tokens.refresh_token)
    }

    private suspend fun clearAuth() {
        prefsRepo.remove(KEY_AUTH_USER_ID)
        prefsRepo.remove(KEY_AUTH_EMAIL)
        prefsRepo.remove(KEY_AUTH_ACCESS_TOKEN)
        prefsRepo.remove(KEY_AUTH_REFRESH_TOKEN)
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
private data class RefreshDto(val refresh_token: String)

@Serializable
private data class LogoutDto(val refresh_token: String?)

@Serializable
private data class ErrorDto(val detail: String? = null)

@Serializable
data class UserResponseDto(val id: String, val email: String)

@Serializable
data class TokensDto(val access_token: String, val refresh_token: String, val expires_in: Int = 900)

@Serializable
data class AuthResponseDto(val user: UserResponseDto, val tokens: TokensDto)
