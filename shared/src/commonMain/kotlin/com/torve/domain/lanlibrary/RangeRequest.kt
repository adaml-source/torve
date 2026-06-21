package com.torve.domain.lanlibrary

/**
 * Pure logic for HTTP `Range:` request parsing and response shaping.
 * Framework-agnostic so the same code can run behind Ktor, JDK
 * `HttpServer`, or a test fixture.
 *
 * Supports the single-range form per RFC 7233 §2.1:
 *   `bytes=<first>-<last>`     — explicit range, inclusive.
 *   `bytes=<first>-`           — open-ended, server fills last.
 *   `bytes=-<suffixLength>`    — last N bytes.
 *
 * Multi-range (`bytes=0-99,200-299`) is intentionally unsupported in v1
 * — players don't need it for media streaming. Multi-range arrives as
 * [RangeParseResult.Unsupported].
 */
sealed interface RangeParseResult {
    /** A satisfiable single range. */
    data class Satisfiable(val start: Long, val endInclusive: Long) : RangeParseResult
    /** Header was absent or empty — caller should serve 200 with full body. */
    data object NoRange : RangeParseResult
    /** Header was present but malformed. Caller should return 400. */
    data class Malformed(val reason: String) : RangeParseResult
    /** Multi-range or `bytes=` followed by something weird. Return 416. */
    data class Unsupported(val reason: String) : RangeParseResult
    /** Range fell entirely outside the resource. Return 416 with Content-Range. */
    data object NotSatisfiable : RangeParseResult
}

object RangeRequest {

    /**
     * @param header the raw `Range:` header value, or null.
     * @param totalSize size of the resource in bytes.
     */
    fun parse(header: String?, totalSize: Long): RangeParseResult {
        if (totalSize < 0) return RangeParseResult.Malformed("negative totalSize")
        if (header.isNullOrBlank()) return RangeParseResult.NoRange
        val trimmed = header.trim()
        val unitSep = trimmed.indexOf('=')
        if (unitSep < 0) return RangeParseResult.Malformed("missing unit separator")
        val unit = trimmed.substring(0, unitSep).trim().lowercase()
        if (unit != "bytes") return RangeParseResult.Unsupported("unit '$unit' not supported")
        val ranges = trimmed.substring(unitSep + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ranges.isEmpty()) return RangeParseResult.Malformed("no ranges supplied")
        if (ranges.size > 1) return RangeParseResult.Unsupported("multi-range not supported")
        val spec = ranges.single()
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeParseResult.Malformed("missing '-' in range '$spec'")
        val firstStr = spec.substring(0, dash).trim()
        val lastStr = spec.substring(dash + 1).trim()

        if (firstStr.isEmpty()) {
            // Suffix form: bytes=-N
            val suffix = lastStr.toLongOrNull()
                ?: return RangeParseResult.Malformed("invalid suffix '$lastStr'")
            if (suffix <= 0) return RangeParseResult.Malformed("suffix must be > 0")
            if (totalSize == 0L) return RangeParseResult.NotSatisfiable
            val start = (totalSize - suffix).coerceAtLeast(0)
            return RangeParseResult.Satisfiable(start = start, endInclusive = totalSize - 1)
        }
        val start = firstStr.toLongOrNull()
            ?: return RangeParseResult.Malformed("invalid start '$firstStr'")
        if (start < 0) return RangeParseResult.Malformed("start must be ≥ 0")
        if (totalSize == 0L) return RangeParseResult.NotSatisfiable
        if (start >= totalSize) return RangeParseResult.NotSatisfiable

        val endInclusive: Long = if (lastStr.isEmpty()) {
            totalSize - 1
        } else {
            val candidate = lastStr.toLongOrNull()
                ?: return RangeParseResult.Malformed("invalid end '$lastStr'")
            if (candidate < start) return RangeParseResult.Malformed("end < start")
            candidate.coerceAtMost(totalSize - 1)
        }
        return RangeParseResult.Satisfiable(start = start, endInclusive = endInclusive)
    }

    /**
     * Build the response shape for a parsed range. Caller maps these
     * fields onto whatever HTTP framework it's using.
     */
    fun buildResponse(parsed: RangeParseResult, totalSize: Long, contentType: String): RangeResponse =
        when (parsed) {
            RangeParseResult.NoRange -> RangeResponse(
                status = 200,
                contentType = contentType,
                contentLength = totalSize,
                rangeStart = 0,
                rangeEndInclusive = totalSize - 1,
                contentRangeHeader = null,
                acceptRangesHeader = "bytes",
            )
            is RangeParseResult.Satisfiable -> RangeResponse(
                status = 206,
                contentType = contentType,
                contentLength = parsed.endInclusive - parsed.start + 1,
                rangeStart = parsed.start,
                rangeEndInclusive = parsed.endInclusive,
                contentRangeHeader = "bytes ${parsed.start}-${parsed.endInclusive}/$totalSize",
                acceptRangesHeader = "bytes",
            )
            RangeParseResult.NotSatisfiable -> RangeResponse(
                status = 416,
                contentType = "text/plain",
                contentLength = 0,
                rangeStart = 0,
                rangeEndInclusive = -1,
                contentRangeHeader = "bytes */$totalSize",
                acceptRangesHeader = "bytes",
            )
            is RangeParseResult.Malformed -> RangeResponse(
                status = 400,
                contentType = "text/plain",
                contentLength = 0,
                rangeStart = 0,
                rangeEndInclusive = -1,
                contentRangeHeader = null,
                acceptRangesHeader = "bytes",
            )
            is RangeParseResult.Unsupported -> RangeResponse(
                status = 416,
                contentType = "text/plain",
                contentLength = 0,
                rangeStart = 0,
                rangeEndInclusive = -1,
                contentRangeHeader = "bytes */$totalSize",
                acceptRangesHeader = "bytes",
            )
        }
}

data class RangeResponse(
    val status: Int,
    val contentType: String,
    val contentLength: Long,
    val rangeStart: Long,
    val rangeEndInclusive: Long,
    val contentRangeHeader: String?,
    val acceptRangesHeader: String,
)
