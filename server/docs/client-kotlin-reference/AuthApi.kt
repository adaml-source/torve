package com.torve.data.auth

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Torve auth API — Retrofit interface.
 *
 * All endpoints return Response<T> so callers can inspect status codes
 * (especially 429 which returns HTML, not JSON).
 */
interface AuthApi {

    @POST("auth/register")
    suspend fun register(@Body body: SignupRequestDto): Response<AuthResponseDto>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequestDto): Response<AuthResponseDto>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): Response<RefreshResponseDto>

    @POST("auth/resend-verification")
    suspend fun resendVerification(@Body body: ResendVerificationRequestDto): Response<MessageResponseDto>

    @POST("auth/password-reset/request")
    suspend fun requestPasswordReset(@Body body: PasswordResetRequestDto): Response<MessageResponseDto>

    @POST("auth/password-reset/confirm")
    suspend fun confirmPasswordReset(@Body body: PasswordResetConfirmDto): Response<MessageResponseDto>
}
