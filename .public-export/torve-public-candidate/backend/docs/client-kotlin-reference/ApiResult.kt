package com.torve.data.auth

import kotlinx.serialization.json.Json
import retrofit2.Response

/**
 * Sealed result type for API calls.
 * Handles JSON errors, HTML 429 errors, and network failures uniformly.
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: Int, val message: String) : ApiResult<Nothing>()
    data class RateLimited(val retryAfterSeconds: Int = 60) : ApiResult<Nothing>()
    data class NetworkError(val exception: Throwable) : ApiResult<Nothing>()
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Wraps a Retrofit call into an ApiResult.
 *
 * Usage:
 *   val result = safeApiCall { api.login(body) }
 *   when (result) {
 *       is ApiResult.Success -> handleTokens(result.data)
 *       is ApiResult.RateLimited -> showRateLimitMessage(result.retryAfterSeconds)
 *       is ApiResult.Error -> showError(result.message)
 *       is ApiResult.NetworkError -> showOfflineMessage()
 *   }
 */
suspend fun <T> safeApiCall(call: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = call()
        when {
            response.isSuccessful -> {
                val body = response.body()
                if (body != null) {
                    ApiResult.Success(body)
                } else {
                    ApiResult.Error(response.code(), "Empty response body")
                }
            }
            response.code() == 429 -> {
                // 429 comes from Nginx as HTML — do NOT parse as JSON
                val retryAfter = response.headers()["Retry-After"]?.toIntOrNull() ?: 60
                ApiResult.RateLimited(retryAfterSeconds = retryAfter)
            }
            else -> {
                val errorBody = response.errorBody()?.string().orEmpty()
                val message = try {
                    lenientJson.decodeFromString<ApiErrorDto>(errorBody).message()
                } catch (_: Exception) {
                    // Could not parse as JSON (e.g., HTML error page)
                    httpStatusMessage(response.code())
                }
                ApiResult.Error(response.code(), message)
            }
        }
    } catch (e: java.io.IOException) {
        ApiResult.NetworkError(e)
    } catch (e: Exception) {
        ApiResult.Error(0, e.message ?: "Unknown error")
    }
}

private fun httpStatusMessage(code: Int): String = when (code) {
    400 -> "Invalid request."
    401 -> "Invalid credentials."
    403 -> "Account is disabled."
    409 -> "Email already registered."
    422 -> "Please check your input."
    500 -> "Server error. Please try again later."
    else -> "Something went wrong (HTTP $code)."
}
