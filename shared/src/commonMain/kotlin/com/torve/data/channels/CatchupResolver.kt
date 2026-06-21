package com.torve.data.channels

import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.Channel
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Resolves catchup/timeshift URLs for replaying past EPG programmes.
 *
 * M3U playlists define catchup support via EXTINF attributes:
 * - catchup: type ("default", "append", "shift", "flussonic", "xc")
 * - catchup-days: how many days of archive are available
 * - catchup-source: URL template with placeholders
 *
 * Placeholders in catchup-source:
 * - {start} / ${start} — UTC epoch seconds
 * - {end} / ${end} — UTC epoch seconds
 * - {duration} — duration in seconds
 * - {utc} — UTC epoch seconds (alias for start)
 * - {Y}, {m}, {d}, {H}, {M}, {S} — date/time components (UTC)
 * - {timestamp} — UTC epoch seconds
 */
class CatchupResolver {

    fun canCatchup(channel: Channel): Boolean {
        return !channel.catchupType.isNullOrBlank() &&
            (channel.catchupDays ?: 0) > 0
    }

    fun resolve(
        channel: Channel,
        programme: EpgProgramme,
    ): String? {
        val type = channel.catchupType?.lowercase() ?: return null
        val startSec = programme.startTime / 1000
        val endSec = programme.endTime / 1000
        val durationSec = endSec - startSec

        return when (type) {
            "default", "flussonic", "fs" -> {
                val template = channel.catchupSource
                if (template.isNullOrBlank()) {
                    // Default: append ?utc={start}&lutc={end} to stream URL
                    "${channel.url}?utc=$startSec&lutc=$endSec"
                } else {
                    replacePlaceholders(template, startSec, endSec, durationSec)
                }
            }
            "append" -> {
                val suffix = channel.catchupSource ?: "?utc={utc}&lutc={end}"
                channel.url + replacePlaceholders(suffix, startSec, endSec, durationSec)
            }
            "shift" -> {
                val shiftSec = Clock.System.now().epochSeconds - startSec
                val template = channel.catchupSource
                if (template.isNullOrBlank()) {
                    "${channel.url}?timeshift=$shiftSec"
                } else {
                    replacePlaceholders(template, startSec, endSec, durationSec)
                        .replace("{offset}", shiftSec.toString())
                }
            }
            "xc" -> {
                resolveXtreamCatchup(channel, startSec, durationSec)
            }
            else -> {
                // Try catchup-source as full template
                val template = channel.catchupSource ?: return null
                replacePlaceholders(template, startSec, endSec, durationSec)
            }
        }
    }

    private fun replacePlaceholders(
        template: String,
        startSec: Long,
        endSec: Long,
        durationSec: Long,
    ): String {
        // Parse UTC date/time components from start
        val instant = Instant.fromEpochSeconds(startSec)
        val utc = instant.toLocalDateTime(TimeZone.UTC)

        return template
            .replace("\${start}", startSec.toString())
            .replace("{start}", startSec.toString())
            .replace("\${end}", endSec.toString())
            .replace("{end}", endSec.toString())
            .replace("{duration}", durationSec.toString())
            .replace("{utc}", startSec.toString())
            .replace("{timestamp}", startSec.toString())
            .replace("{Y}", utc.year.toString())
            .replace("{m}", utc.monthNumber.toString().padStart(2, '0'))
            .replace("{d}", utc.dayOfMonth.toString().padStart(2, '0'))
            .replace("{H}", utc.hour.toString().padStart(2, '0'))
            .replace("{M}", utc.minute.toString().padStart(2, '0'))
            .replace("{S}", utc.second.toString().padStart(2, '0'))
    }

    private fun resolveXtreamCatchup(
        channel: Channel,
        startSec: Long,
        durationSec: Long,
    ): String {
        val liveUrl = channel.url
        val parsed = XtreamLiveUrlParts.parse(liveUrl)
        if (parsed != null) {
            val instant = Instant.fromEpochSeconds(startSec)
            val utc = instant.toLocalDateTime(TimeZone.UTC)
            val startLabel = buildString {
                append(utc.year.toString().padStart(4, '0'))
                append('-')
                append(utc.monthNumber.toString().padStart(2, '0'))
                append('-')
                append(utc.dayOfMonth.toString().padStart(2, '0'))
                append(':')
                append(utc.hour.toString().padStart(2, '0'))
                append('-')
                append(utc.minute.toString().padStart(2, '0'))
            }
            val durationMinutes = ((durationSec + 59) / 60).coerceAtLeast(1)
            return "${parsed.serverBase}/timeshift/${parsed.username}/${parsed.password}/$durationMinutes/$startLabel/${parsed.streamId}.${parsed.extension}"
        }

        // Preserve the legacy fallback for non-standard provider URLs.
        val base = liveUrl
            .replace("/live/", "/timeshift/")
            .replace(".ts", "")
            .replace(".m3u8", "")
        return "$base/$durationSec/$startSec"
    }
}

private data class XtreamLiveUrlParts(
    val serverBase: String,
    val username: String,
    val password: String,
    val streamId: String,
    val extension: String,
) {
    companion object {
        private val LIVE_URL_PATTERN = Regex("""^(https?://.+?)/live/([^/]+)/([^/]+)/([^/.]+)\.([A-Za-z0-9]+)(?:\?.*)?$""")

        fun parse(url: String): XtreamLiveUrlParts? {
            val match = LIVE_URL_PATTERN.matchEntire(url) ?: return null
            return XtreamLiveUrlParts(
                serverBase = match.groupValues[1],
                username = match.groupValues[2],
                password = match.groupValues[3],
                streamId = match.groupValues[4],
                extension = match.groupValues[5],
            )
        }
    }
}
