package com.streamvault.data.channels

import com.streamvault.domain.model.EpgProgramme
import com.streamvault.domain.model.Channel
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
                // Xtream Codes catchup: replace /live/ with /timeshift/ and append duration/start
                val base = channel.url
                    .replace("/live/", "/timeshift/")
                    .replace(".ts", "")
                    .replace(".m3u8", "")
                "$base/$durationSec/$startSec"
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
}
