package com.torve.data.auth

/**
 * Auth repository — thin wrapper around AuthApi with safeApiCall.
 *
 * Handles:
 * - Token persistence on successful login/signup
 * - User state updates on refresh (is_verified may change)
 * - Rate limit awareness
 *
 * This is a reference implementation. Adapt to your DI / storage layer.
 */
class AuthRepository(
    private val api: AuthApi,
    private val tokenStore: TokenStore,  // your DataStore / EncryptedSharedPrefs wrapper
) {
    suspend fun register(
        email: String,
        password: String,
        displayName: String? = null,
        deviceName: String? = null,
    ): ApiResult<AuthResponseDto> {
        val result = safeApiCall {
            api.register(
                SignupRequestDto(
                    email = email,
                    password = password,
                    displayName = displayName,
                    deviceName = deviceName,
                )
            )
        }
        if (result is ApiResult.Success) {
            persistAuth(result.data)
        }
        return result
    }

    suspend fun login(
        email: String,
        password: String,
        deviceName: String? = null,
    ): ApiResult<AuthResponseDto> {
        val result = safeApiCall {
            api.login(
                LoginRequestDto(
                    email = email,
                    password = password,
                    deviceName = deviceName,
                )
            )
        }
        if (result is ApiResult.Success) {
            persistAuth(result.data)
        }
        return result
    }

    suspend fun refresh(): ApiResult<RefreshResponseDto> {
        val refreshToken = tokenStore.getRefreshToken()
            ?: return ApiResult.Error(401, "No refresh token stored")

        val result = safeApiCall {
            api.refresh(RefreshRequestDto(refreshToken = refreshToken))
        }

        when (result) {
            is ApiResult.Success -> {
                // Update access token (refresh token stays the same)
                tokenStore.saveAccessToken(result.data.tokens.accessToken)
                // Update user state — is_verified may have changed
                tokenStore.saveUser(result.data.user)
            }
            is ApiResult.Error -> {
                if (result.code == 401) {
                    // Refresh token expired or revoked — force re-login
                    tokenStore.clear()
                }
            }
            else -> { /* RateLimited or NetworkError — keep existing tokens */ }
        }
        return result
    }

    suspend fun resendVerification(email: String): ApiResult<MessageResponseDto> {
        return safeApiCall {
            api.resendVerification(ResendVerificationRequestDto(email = email))
        }
    }

    suspend fun requestPasswordReset(email: String): ApiResult<MessageResponseDto> {
        return safeApiCall {
            api.requestPasswordReset(PasswordResetRequestDto(email = email))
        }
    }

    suspend fun confirmPasswordReset(
        token: String,
        newPassword: String,
    ): ApiResult<MessageResponseDto> {
        return safeApiCall {
            api.confirmPasswordReset(PasswordResetConfirmDto(token = token, newPassword = newPassword))
        }
    }

    private fun persistAuth(auth: AuthResponseDto) {
        tokenStore.saveAccessToken(auth.tokens.accessToken)
        tokenStore.saveRefreshToken(auth.tokens.refreshToken)
        tokenStore.saveUser(auth.user)
    }
}

/**
 * Token storage interface — implement with EncryptedSharedPreferences or DataStore.
 */
interface TokenStore {
    fun saveAccessToken(token: String)
    fun saveRefreshToken(token: String)
    fun saveUser(user: UserDto)
    fun getAccessToken(): String?
    fun getRefreshToken(): String?
    fun getUser(): UserDto?
    fun clear()
}
