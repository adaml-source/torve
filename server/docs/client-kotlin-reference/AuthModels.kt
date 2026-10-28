package com.torve.data.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Response DTOs ────────────────────────────────────────────────────────────

@Serializable
data class AuthResponseDto(
    val tokens: TokensDto,
    val user: UserDto,
)

@Serializable
data class TokensDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
)

@Serializable
data class RefreshResponseDto(
    val tokens: RefreshTokensDto,
    val user: UserDto,
)

@Serializable
data class RefreshTokensDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    // NOTE: refresh_token is intentionally absent — reuse the original
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("is_verified") val isVerified: Boolean,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class MessageResponseDto(
    val message: String,
)

// ── Request DTOs ─────────────────────────────────────────────────────────────

@Serializable
data class SignupRequestDto(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    val platform: String? = "android",
)

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
    @SerialName("device_name") val deviceName: String? = null,
    val platform: String? = "android",
)

@Serializable
data class RefreshRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class ResendVerificationRequestDto(
    val email: String,
)

@Serializable
data class PasswordResetRequestDto(
    val email: String,
)

@Serializable
data class PasswordResetConfirmDto(
    val token: String,
    @SerialName("new_password") val newPassword: String,
)

// ── Error DTOs ───────────────────────────────────────────────────────────────

// Backend errors: {"detail": "string"} or {"detail": [...]}
// 429 errors: HTML body from Nginx (not JSON!)

@Serializable
data class ApiErrorDto(
    val detail: kotlinx.serialization.json.JsonElement? = null,
) {
    /**
     * Extract a human-readable error message.
     * Handles both string detail and array-of-validation-errors detail.
     */
    fun message(): String {
        val d = detail ?: return "An unexpected error occurred."
        // String case: {"detail": "Invalid email or password"}
        if (d is kotlinx.serialization.json.JsonPrimitive && d.isString) {
            return d.content
        }
        // Array case: {"detail": [{"msg": "...", "loc": [...]}]}
        if (d is kotlinx.serialization.json.JsonArray && d.isNotEmpty()) {
            return d.mapNotNull { elem ->
                (elem as? kotlinx.serialization.json.JsonObject)
                    ?.get("msg")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }.joinToString(". ")
                .ifEmpty { "Validation failed." }
        }
        return "An unexpected error occurred."
    }
}
