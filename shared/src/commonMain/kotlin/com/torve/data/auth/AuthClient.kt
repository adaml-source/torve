package com.torve.data.auth

import com.torve.domain.repository.PreferencesRepository

data class AuthUser(
    val email: String,
    val displayName: String? = null,
)

data class AuthResult(
    val success: Boolean,
    val user: AuthUser? = null,
    val error: String? = null,
)

/**
 * Local-first authentication client.
 * Stores credentials in preferences. Backend integration can be added later.
 */
class AuthClient(
    private val prefsRepo: PreferencesRepository,
) {
    companion object {
        const val KEY_AUTH_EMAIL = "auth_email"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_AUTH_DISPLAY_NAME = "auth_display_name"
    }

    suspend fun isLoggedIn(): Boolean {
        return prefsRepo.getString(KEY_AUTH_TOKEN)?.isNotBlank() == true
    }

    suspend fun getCurrentUser(): AuthUser? {
        val email = prefsRepo.getString(KEY_AUTH_EMAIL) ?: return null
        if (email.isBlank()) return null
        val name = prefsRepo.getString(KEY_AUTH_DISPLAY_NAME)
        return AuthUser(email = email, displayName = name)
    }

    suspend fun login(email: String, password: String): AuthResult {
        // Local-first: accept any valid-looking credentials
        if (email.isBlank() || !email.contains("@")) {
            return AuthResult(success = false, error = "Please enter a valid email address")
        }
        if (password.length < 6) {
            return AuthResult(success = false, error = "Password must be at least 6 characters")
        }

        val user = AuthUser(email = email)
        prefsRepo.setString(KEY_AUTH_EMAIL, email)
        prefsRepo.setString(KEY_AUTH_TOKEN, "local_${email.hashCode()}")
        return AuthResult(success = true, user = user)
    }

    suspend fun register(email: String, password: String, displayName: String?): AuthResult {
        if (email.isBlank() || !email.contains("@")) {
            return AuthResult(success = false, error = "Please enter a valid email address")
        }
        if (password.length < 6) {
            return AuthResult(success = false, error = "Password must be at least 6 characters")
        }

        val user = AuthUser(email = email, displayName = displayName)
        prefsRepo.setString(KEY_AUTH_EMAIL, email)
        prefsRepo.setString(KEY_AUTH_TOKEN, "local_${email.hashCode()}")
        if (!displayName.isNullOrBlank()) {
            prefsRepo.setString(KEY_AUTH_DISPLAY_NAME, displayName)
        }
        return AuthResult(success = true, user = user)
    }

    suspend fun logout() {
        prefsRepo.remove(KEY_AUTH_EMAIL)
        prefsRepo.remove(KEY_AUTH_TOKEN)
        prefsRepo.remove(KEY_AUTH_DISPLAY_NAME)
    }

    suspend fun deleteAccount(): AuthResult {
        // Clear all auth data
        prefsRepo.remove(KEY_AUTH_EMAIL)
        prefsRepo.remove(KEY_AUTH_TOKEN)
        prefsRepo.remove(KEY_AUTH_DISPLAY_NAME)
        return AuthResult(success = true)
    }
}
