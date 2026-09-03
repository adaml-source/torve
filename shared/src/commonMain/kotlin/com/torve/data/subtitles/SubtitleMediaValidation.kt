package com.torve.data.subtitles

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeoutOrNull

data class OpenSubtitlesMovieHash(
    val hash: String,
    val fileSize: Long,
)

/** Standard OpenSubtitles OSHash: file size plus little-endian 64-bit words from each 64 KiB edge. */
fun calculateOpenSubtitlesHash(
    fileSize: Long,
    first64KiB: ByteArray,
    last64KiB: ByteArray,
): String? {
    if (fileSize < HASH_WINDOW_BYTES * 2L) return null
    if (first64KiB.size != HASH_WINDOW_BYTES || last64KiB.size != HASH_WINDOW_BYTES) return null
    var hash = fileSize.toULong()
    fun addWindow(window: ByteArray) {
        var offset = 0
        while (offset + 7 < window.size) {
            var word = 0UL
            for (byteIndex in 0 until 8) {
                word = word or ((window[offset + byteIndex].toUByte().toULong()) shl (byteIndex * 8))
            }
            hash += word
            offset += 8
        }
    }
    addWindow(first64KiB)
    addWindow(last64KiB)
    return hash.toString(16).padStart(16, '0').takeLast(16)
}

/** Range-only hash probe. It refuses servers that ignore Range, so it can never fetch the movie. */
class OpenSubtitlesHashService(private val httpClient: HttpClient) {
    suspend fun calculateForHttp(url: String, knownFileSize: Long?): OpenSubtitlesMovieHash? {
        val size = knownFileSize?.takeIf { it >= HASH_WINDOW_BYTES * 2L } ?: return null
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        return withTimeoutOrNull(HASH_TOTAL_TIMEOUT_MS) {
            // Keep the two requests sequential. Apart from avoiding duplicate-URL request
            // coalescing in some engines, this guarantees the second edge is never requested
            // after the first edge proves that the server ignores byte ranges.
            val firstBytes = fetchExactRange(url, 0L, HASH_WINDOW_BYTES - 1L) ?: return@withTimeoutOrNull null
            val lastStart = size - HASH_WINDOW_BYTES
            val lastBytes = fetchExactRange(url, lastStart, size - 1L) ?: return@withTimeoutOrNull null
            calculateOpenSubtitlesHash(size, firstBytes, lastBytes)?.let {
                OpenSubtitlesMovieHash(it, size)
            }
        }
    }

    private suspend fun fetchExactRange(url: String, start: Long, endInclusive: Long): ByteArray? {
        val response = runCatching {
            httpClient.get(url) {
                header(HttpHeaders.Range, "bytes=$start-$endInclusive")
                timeout {
                    requestTimeoutMillis = HASH_REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = HASH_REQUEST_TIMEOUT_MS
                }
            }
        }.getOrNull() ?: return null
        // A 200 response means the server ignored Range. Never consume that body.
        if (response.status.value != 206) return null
        val declared = response.contentLength()
        if (declared != null && declared != HASH_WINDOW_BYTES.toLong()) return null
        val bytes = readBounded(response.bodyAsChannel(), HASH_WINDOW_BYTES)
        return bytes.takeIf { it.size == HASH_WINDOW_BYTES }
    }

    private companion object {
        const val HASH_TOTAL_TIMEOUT_MS = 7_000L
        const val HASH_REQUEST_TIMEOUT_MS = 5_000L
    }
}

sealed class SubtitleValidationResult {
    data class Valid(
        val cueCount: Int,
        val lastCueMs: Long?,
        val runtimeSuspicious: Boolean,
    ) : SubtitleValidationResult()

    data class Invalid(val reason: String) : SubtitleValidationResult()
}

/** Validates a bounded subtitle body before ExoPlayer is asked to attach it. */
class SubtitleDownloadValidator(private val httpClient: HttpClient) {
    suspend fun validate(url: String, videoDurationMs: Long? = null, forced: Boolean? = null): SubtitleValidationResult {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return SubtitleValidationResult.Invalid("Unsupported subtitle URL")
        }
        return withTimeoutOrNull(VALIDATION_TIMEOUT_MS) {
            val response = runCatching {
                httpClient.get(url) {
                    timeout {
                        requestTimeoutMillis = VALIDATION_TIMEOUT_MS
                        socketTimeoutMillis = VALIDATION_TIMEOUT_MS
                    }
                }
            }.getOrNull() ?: return@withTimeoutOrNull SubtitleValidationResult.Invalid("Subtitle download failed")
            if (!response.status.isSuccess()) {
                return@withTimeoutOrNull SubtitleValidationResult.Invalid("Subtitle HTTP ${response.status.value}")
            }
            val declared = response.contentLength()
            if (declared != null && declared > MAX_SUBTITLE_BYTES) {
                return@withTimeoutOrNull SubtitleValidationResult.Invalid("Subtitle file is too large")
            }
            val bytes = readBounded(response.bodyAsChannel(), MAX_SUBTITLE_BYTES.toInt())
            if (bytes.isEmpty()) return@withTimeoutOrNull SubtitleValidationResult.Invalid("Subtitle file is empty")
            val text = runCatching { bytes.decodeToString() }.getOrNull()
                ?: return@withTimeoutOrNull SubtitleValidationResult.Invalid("Subtitle text is not readable")
            validateSubtitleText(text, videoDurationMs, forced)
        } ?: SubtitleValidationResult.Invalid("Subtitle validation timed out")
    }

    private companion object {
        const val VALIDATION_TIMEOUT_MS = 8_000L
        const val MAX_SUBTITLE_BYTES = 5L * 1024L * 1024L
    }
}

fun validateSubtitleText(
    text: String,
    videoDurationMs: Long? = null,
    forced: Boolean? = null,
): SubtitleValidationResult {
    if (text.isBlank()) return SubtitleValidationResult.Invalid("Subtitle file is empty")
    val arrowTimes = Regex(
        "(?m)(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[,.](\\d{1,3})",
    ).findAll(text).toList()
    val assTimes = Regex(
        "(?im)^Dialogue:[^,]*,(\\d{1,2}):(\\d{2}):(\\d{2})[.](\\d{2}),(\\d{1,2}):(\\d{2}):(\\d{2})[.](\\d{2}),",
    ).findAll(text).toList()
    val cueCount = arrowTimes.size + assTimes.size
    if (cueCount == 0) return SubtitleValidationResult.Invalid("No subtitle cues found")
    val lastArrow = arrowTimes.maxOfOrNull { match ->
        timestampMs(match.groupValues, 5, millisecondDigits = match.groupValues[8].length)
    }
    val lastAss = assTimes.maxOfOrNull { match ->
        timestampMs(match.groupValues, 5, millisecondDigits = 2)
    }
    val lastCue = listOfNotNull(lastArrow, lastAss).maxOrNull()
    val duration = videoDurationMs?.takeIf { it > 0L }
    val suspicious = duration != null && lastCue != null && (
        lastCue > duration * 3 / 2 ||
            (forced != true && cueCount >= 20 && lastCue < duration / 3)
        )
    return SubtitleValidationResult.Valid(cueCount, lastCue, suspicious)
}

private suspend fun readBounded(channel: io.ktor.utils.io.ByteReadChannel, maximumBytes: Int): ByteArray {
    val chunk = ByteArray(8 * 1024)
    var result = ByteArray(minOf(maximumBytes, 32 * 1024))
    var total = 0
    while (true) {
        val remaining = maximumBytes + 1 - total
        if (remaining <= 0) return ByteArray(0)
        val read = channel.readAvailable(chunk, 0, minOf(chunk.size, remaining))
        if (read < 0) break
        if (read == 0) {
            kotlinx.coroutines.yield()
            continue
        }
        val nextTotal = total + read
        if (nextTotal > maximumBytes) return ByteArray(0)
        if (nextTotal > result.size) result = result.copyOf(minOf(maximumBytes, maxOf(result.size * 2, nextTotal)))
        chunk.copyInto(result, destinationOffset = total, endIndex = read)
        total = nextTotal
    }
    return result.copyOf(total)
}

private fun timestampMs(groups: List<String>, startIndex: Int, millisecondDigits: Int): Long {
    val hours = groups[startIndex].toLongOrNull() ?: 0L
    val minutes = groups[startIndex + 1].toLongOrNull() ?: 0L
    val seconds = groups[startIndex + 2].toLongOrNull() ?: 0L
    val rawFraction = groups[startIndex + 3].toLongOrNull() ?: 0L
    val milliseconds = when (millisecondDigits) {
        2 -> rawFraction * 10L
        1 -> rawFraction * 100L
        else -> rawFraction
    }
    return ((hours * 60L + minutes) * 60L + seconds) * 1_000L + milliseconds
}

private const val HASH_WINDOW_BYTES = 64 * 1024
