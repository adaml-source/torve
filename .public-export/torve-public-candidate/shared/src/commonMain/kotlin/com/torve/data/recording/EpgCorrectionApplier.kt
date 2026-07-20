package com.torve.data.recording

import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgData
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.domain.recording.EpgCorrection
import com.torve.domain.recording.applyEpgOffset

/**
 * Applies a persisted [EpgCorrection] to EPG data + the rendered
 * channel set so guide lookups, programme times, and hidden categories
 * all line up.
 *
 * Three corrections this helper bakes in:
 *   1. **Offset** — every programme's `startTime` / `endTime` shifts by
 *      `correction.offsetMinutes * 60_000L`.
 *   2. **tvg-id remap** — channel-side: when a channel's `tvgId` (or
 *      its canonical key suffix) is in the remap, the lookup uses the
 *      mapped EPG id. The map is `playlistChannelTvgId → epgChannelId`,
 *      matching the Settings UI's "playlist id → EPG id" form.
 *   3. **Hidden categories** — applied separately by the caller via
 *      [filterHiddenCategories] over the channel list. Kept as a
 *      separate function so the caller can apply it to channels and
 *      categories without re-running the full guide build.
 *
 * Empty correction (default fields) is identity — the produced map
 * is byte-equivalent to the input's `programmesByChannelKey` when the
 * channel set's canonical keys all resolve.
 */
object EpgCorrectionApplier {

    /**
     * Result of applying a correction. The caller consumes:
     *   * [programmesByKey] for the guide's per-channel programme list
     *     (replaces `EpgData.programmesByChannelKey` for rendering).
     *   * [matchedChannels] / [unmatchedChannels] for the EPG-state pill.
     *   * [correctedProgrammes] for the stale-EPG health snapshot.
     */
    data class Applied(
        val programmesByKey: Map<String, List<EpgProgramme>>,
        val matchedChannels: Int,
        val unmatchedChannels: Int,
        val correctedProgrammes: List<EpgProgramme>,
    )

    fun apply(
        playlistId: String,
        channels: List<EnrichedChannel>,
        epgData: EpgData,
        correction: EpgCorrection,
    ): Applied {
        // 1. Offset shifts every programme's window. Identity when 0.
        val shifted = applyEpgOffset(epgData.programmes, correction.offsetMinutes)

        // 2. Group corrected programmes by their channelId — same key
        //    shape EpgData.programmesByChannelKey uses
        //    (`<playlistId>::<channelId>`).
        val byChannelId: Map<String, List<EpgProgramme>> =
            if (correction.offsetMinutes == 0) {
                // Reuse the input's grouping when nothing shifted to keep
                // the result byte-equivalent to baseline.
                epgData.programmesByChannelKey
            } else {
                shifted.groupBy { it.channelId }
            }

        // 3. Per-channel lookup with optional channel-side remap.
        val programmesByKey = LinkedHashMap<String, List<EpgProgramme>>(channels.size)
        var matched = 0
        var unmatched = 0
        for (enriched in channels) {
            val ownKey = canonicalEpgChannelKey(playlistId, enriched.channel) ?: run {
                unmatched++
                continue
            }
            val lookupKey = resolveLookupKey(ownKey, playlistId, enriched, correction.tvgIdRemap)
            val programmes = byChannelId[lookupKey].orEmpty()
            programmesByKey[ownKey] = programmes
            if (programmes.isEmpty()) unmatched++ else matched++
        }
        return Applied(
            programmesByKey = programmesByKey,
            matchedChannels = matched,
            unmatchedChannels = unmatched,
            correctedProgrammes = shifted,
        )
    }

    /**
     * Drop channels whose primary category (group title) is in
     * [correction.hiddenCategories]. Case-insensitive match; the lookup
     * mirrors the existing `state.hiddenCategories` filter so results
     * stay aligned across the two stores.
     *
     * Identity when [EpgCorrection.hiddenCategories] is empty.
     */
    fun filterHiddenCategories(
        channels: List<EnrichedChannel>,
        correction: EpgCorrection,
    ): List<EnrichedChannel> {
        if (correction.hiddenCategories.isEmpty()) return channels
        val hiddenLower = correction.hiddenCategories.map { it.lowercase() }.toSet()
        return channels.filter { ec ->
            val group = ec.channel.groupTitle?.trim()?.lowercase()
            group == null || group !in hiddenLower
        }
    }

    private fun resolveLookupKey(
        ownKey: String,
        playlistId: String,
        enriched: EnrichedChannel,
        tvgIdRemap: Map<String, String>,
    ): String {
        if (tvgIdRemap.isEmpty()) return ownKey
        // Look up by raw tvg-id first (matches the Settings UI's hint).
        val tvgId = enriched.channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }
        val mapped = tvgId?.let { tvgIdRemap[it] }
            ?: tvgIdRemap[ownKey.substringAfter("::")]
        return if (mapped != null) "$playlistId::$mapped" else ownKey
    }
}
