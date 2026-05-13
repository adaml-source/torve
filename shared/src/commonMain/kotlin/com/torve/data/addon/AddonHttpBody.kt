package com.torve.data.addon

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable

private const val DEFAULT_ADDON_MAX_BODY_BYTES = 2 * 1024 * 1024

internal class AddonBodyTooLargeException(
    val limitBytes: Int,
    val contentLength: Long? = null,
) : IllegalStateException(
    if (contentLength != null) {
        "Addon response is too large ($contentLength bytes, limit $limitBytes bytes)."
    } else {
        "Addon response exceeded $limitBytes bytes."
    },
)

internal suspend fun HttpResponse.safeAddonBodyAsText(
    maxBytes: Int = DEFAULT_ADDON_MAX_BODY_BYTES,
): String {
    val declaredLength = headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declaredLength != null && declaredLength > maxBytes) {
        throw AddonBodyTooLargeException(maxBytes, declaredLength)
    }

    val channel = bodyAsChannel()
    val bytes = ByteArray(maxBytes + 1)
    var total = 0
    while (total < bytes.size) {
        val read = channel.readAvailable(bytes, total, bytes.size - total)
        if (read <= 0) break
        total += read
        if (total > maxBytes) {
            throw AddonBodyTooLargeException(maxBytes)
        }
    }
    return bytes.decodeToString(0, total)
}
