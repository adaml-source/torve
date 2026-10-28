package com.torve.data.integrations

import com.torve.domain.integrations.ConnectionProbeResult
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

internal fun connectionResult(status: HttpStatusCode): ConnectionProbeResult = when (status.value) {
    in 200..299 -> ConnectionProbeResult.CONNECTED
    401, 403 -> ConnectionProbeResult.AUTH_REJECTED
    404 -> ConnectionProbeResult.SETUP_INCOMPLETE
    408, 429, in 500..599 -> ConnectionProbeResult.UNREACHABLE
    else -> ConnectionProbeResult.UNKNOWN_FAILURE
}

/** Preserve cancellation and HTTP evidence; never expose URLs, tokens or response bodies. */
internal suspend fun authenticatedProbe(request: suspend () -> HttpStatusCode): ConnectionProbeResult = try {
    connectionResult(request())
} catch (error: Exception) {
    if (error is CancellationException) throw error
    if (error is ResponseException) connectionResult(error.response.status) else ConnectionProbeResult.UNREACHABLE
}
