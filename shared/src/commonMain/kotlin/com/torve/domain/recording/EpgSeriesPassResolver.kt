package com.torve.domain.recording

import com.torve.domain.model.EpgProgramme
import kotlin.random.Random

/**
 * Concrete [SeriesPassResolver] driven by EPG data. Turns a
 * [RecordingSeriesPass] into one [Recording] per future programme on
 * the pass's channel that matches the title (and the optional season
 * minimum). Wires the resolver hook the scheduler already exposes —
 * Prompt 21 enables series passes for the first time.
 *
 * Filter order:
 *   1. Drop programmes that already ended before [nowMs].
 *   2. Keep titles that contain `pass.titleMatch` (case-insensitive
 *      substring).
 *   3. If `pass.seasonMin` is set, drop programmes whose parsed
 *      season number is below it. Programmes whose title has no
 *      parseable season are kept — silently dropping them would
 *      surprise users whose IPTV guide doesn't tag seasons.
 *   4. If `pass.recordOnlyNew`, drop programmes whose
 *      `(channelId, startTime)` already exists as a Recording for
 *      this pass.
 *
 * `pass.keepCount` is NOT enforced here — that's
 * [RecordingStoragePruneSelector]'s job. The resolver only schedules.
 */
class EpgSeriesPassResolver(
    private val programmesForChannel: suspend (playlistId: String, channelId: String) -> List<EpgProgramme>,
    private val existingRecordingsForPass: suspend (passId: String) -> List<Recording>,
    private val channelNameForId: suspend (playlistId: String, channelId: String) -> String? = { _, _ -> null },
    private val streamUrlForChannelId: suspend (playlistId: String, channelId: String) -> String? = { _, _ -> null },
    private val newId: () -> String = { defaultId() },
) : SeriesPassResolver {

    override suspend fun resolve(pass: RecordingSeriesPass, nowMs: Long): List<Recording> {
        val streamUrl = streamUrlForChannelId(pass.playlistId, pass.channelId)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val channelName = channelNameForId(pass.playlistId, pass.channelId) ?: pass.channelId

        val matched = programmesForChannel(pass.playlistId, pass.channelId)
            .filter { it.endTime > nowMs }
            .filter { it.title.contains(pass.titleMatch, ignoreCase = true) }
            .filter { programme ->
                val seasonMin = pass.seasonMin ?: return@filter true
                val parsed = parseSeasonNumber(programme.title) ?: return@filter true
                parsed >= seasonMin
            }

        val deduped = if (pass.recordOnlyNew) {
            val existing = existingRecordingsForPass(pass.id)
            matched.filterNot { p ->
                existing.any { r -> r.startMs == p.startTime && r.channelId == pass.channelId }
            }
        } else {
            matched
        }

        return deduped.map { p ->
            Recording(
                id = newId(),
                playlistId = pass.playlistId,
                channelId = pass.channelId,
                channelName = channelName,
                streamUrl = streamUrl,
                programmeTitle = p.title,
                programmeDescription = p.description,
                startMs = p.startTime,
                endMs = p.endTime,
                status = RecordingStatus.SCHEDULED,
                scheduleKind = RecordingScheduleKind.SERIES,
                seriesPassId = pass.id,
                createdAtMs = nowMs,
            )
        }
    }

    private companion object {
        private fun defaultId(): String = "rec-" + Random.nextLong().toString(16).trimStart('-').take(12)
    }
}

/**
 * Parse a season number from a programme title. Recognises the common
 * IPTV-guide patterns `S01E02` (and lower-case variants with optional
 * separators) and `1x05`. Returns null when no pattern matches.
 *
 * Internal so [EpgSeriesPassResolverTest] and any future caller in the
 * same module can exercise it directly.
 */
private val SEASON_REGEX = Regex(
    """[sS](\d{1,2})[\s.\-_]?[eE]\d{1,3}|(\d{1,2})x\d{1,3}""",
)

internal fun parseSeasonNumber(title: String): Int? {
    val match = SEASON_REGEX.find(title) ?: return null
    val s = match.groupValues[1].ifBlank { match.groupValues[2] }
    return s.toIntOrNull()
}
