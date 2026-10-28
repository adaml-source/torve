package com.torve.android.download

import java.io.IOException

/** HTTP byte ranges describe the representation, not merely the next chunk. */
internal data class DownloadResponsePlan(val append: Boolean, val totalBytes: Long?, val alreadyComplete: Boolean = false)

internal object DownloadResponsePolicy {
    fun plan(status: Int, existing: Long, length: Long?, range: String?): DownloadResponsePlan {
        require(existing >= 0)
        if (status == 416) {
            val total = Regex("bytes \\*/(\\d+)").matchEntire(range.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            if (total != null && total > 0 && total == existing) return DownloadResponsePlan(true, total, true)
            throw IOException("The source changed; restart the download")
        }
        if (status == 200) return DownloadResponsePlan(false, length?.takeIf { it >= 0 })
        if (status != 206) throw DownloadHttpException(status)
        val values = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range.orEmpty())?.groupValues
            ?: throw IOException("Invalid partial response")
        val start = values[1].toLongOrNull() ?: throw IOException("Invalid byte range")
        val end = values[2].toLongOrNull() ?: throw IOException("Invalid byte range")
        val total = values[3].toLongOrNull() ?: throw IOException("Invalid byte range")
        if (start != existing || end < start || end >= total || total <= 0 ||
            (length != null && length >= 0 && length != end - start + 1)) throw IOException("Mismatched byte range")
        return DownloadResponsePlan(existing > 0, total)
    }

    fun verifyComplete(bytes: Long, expected: Long?) {
        if (bytes <= 0 || (expected != null && bytes != expected)) throw IOException("The download was interrupted")
    }
}

internal class DownloadHttpException(val status: Int) : IOException("Download source returned HTTP $status") {
    val retryable get() = status == 408 || status == 429 || status >= 500
}
