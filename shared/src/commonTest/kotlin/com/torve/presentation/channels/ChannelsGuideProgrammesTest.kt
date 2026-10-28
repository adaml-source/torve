package com.torve.presentation.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.domain.model.stableChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelsGuideProgrammesTest {
    @Test
    fun shifted_duplicate_zdf_programmes_render_once_and_keep_original_details() {
        val original = EpgProgramme("zdfinfo", 900, 955, "Die Inka - Aufstieg und Untergang")
        val duplicate = original.copy(startTime = 915, title = " Die Inka -  Aufstieg und Untergang ")
        val next = original.copy(startTime = 950, endTime = 1035, title = "Next episode")
        val slots = guideProgrammeSlots(listOf(next, duplicate, original, original))
        assertEquals(2, slots.size)
        assertEquals(GuideProgrammeSlot(original, 900, 950), slots[0])
        assertEquals(original.endTime, slots[0].programme.endTime)
        assertEquals(GuideProgrammeSlot(next, 950, 1035), slots[1])
    }

    @Test
    fun conflicting_nested_and_duplicate_start_entries_never_overlap() {
        val entries = listOf(
            EpgProgramme("zdf", 100, 400, "Long entry"),
            EpgProgramme("zdf", 110, 150, "Short entry"),
            EpgProgramme("zdf", 110, 200, "Complete entry"),
            EpgProgramme("zdf", 180, 250, "Later entry"),
            EpgProgramme("zdf", 260, 300, "Later entry"),
            EpgProgramme("zdf", 300, 300, "Invalid"),
        )
        val slots = guideProgrammeSlots(entries.reversed())
        assertEquals(slots, guideProgrammeSlots(entries))
        assertEquals(listOf(100L, 110L, 180L, 260L), slots.map { it.startTime })
        assertEquals(listOf(110L, 180L, 250L, 300L), slots.map { it.endTime })
        assertTrue(slots.zipWithNext().all { (left, right) -> left.endTime <= right.startTime })
        assertEquals(4, slots.size, "Non-overlapping repeat titles are separate broadcasts")
    }

    @Test
    fun guide_uses_playlist_scoped_schedule_and_retains_past_and_future_programmes() {
        val channel = Channel("Local News", "https://fixture.invalid/live/1", tvgId = "news", playlistId = "current")
        val previous = EpgProgramme("news", 100, 200, "Previous")
        val next = EpgProgramme("news", 300, 400, "Next")
        val invalid = EpgProgramme("news", 500, 400, "Invalid")
        val state = ChannelsUiState(selectedPlaylistId = "other", guideProgrammes = mapOf(
            canonicalEpgChannelKey(channel)!! to listOf(next, previous, previous, invalid),
            "other::news" to listOf(EpgProgramme("news", 100, 200, "Other playlist")),
        ))
        assertEquals(listOf(previous, next), programmesForGuideChannel(state, channel))
        assertTrue(programmesForGuideChannel(state, channel.copy(playlistId = "absent")).isEmpty())
    }

    @Test
    fun legacy_url_keys_are_used_only_when_the_canonical_schedule_is_absent() {
        val channel = Channel("News", "https://fixture.invalid/live/1", playlistId = "playlist")
        val canonical = EpgProgramme("news", 100, 200, "Canonical")
        val legacy = canonical.copy(title = "Legacy")
        val state = ChannelsUiState(guideProgrammes = mapOf(
            canonicalEpgChannelKey(channel)!! to listOf(canonical), stableChannelId(channel) to listOf(legacy),
        ))
        assertEquals(listOf(canonical), programmesForGuideChannel(state, channel))
        assertEquals(listOf(legacy), programmesForGuideChannel(state.copy(guideProgrammes = mapOf(stableChannelId(channel) to listOf(legacy))), channel))
    }

    @Test
    fun consecutive_same_title_broadcasts_with_small_boundary_overlaps_stay_separate() {
        val first = EpgProgramme("mtv", 0, 45, "Caught in the Act")
        val entries = listOf(first, first.copy(startTime = 44, endTime = 99),
            first.copy(startTime = 45, endTime = 95), first.copy(startTime = 95, endTime = 145),
            first.copy(startTime = 99, endTime = 150))
        val slots = guideProgrammeSlots(entries.reversed())
        assertEquals(listOf(0L, 44L, 95L), slots.map { it.startTime })
        assertEquals(listOf(44L, 95L, 150L), slots.map { it.endTime })
        assertEquals(slots, guideProgrammeSlots(entries))
    }

    @Test
    fun distinct_episode_subtitles_are_not_merged_as_duplicate_broadcasts() {
        val first = EpgProgramme("mtv", 0, 90, "Caught in the Act", subTitle = "Episode one")
        val next = first.copy(startTime = 10, endTime = 100, subTitle = "Episode two")
        val slots = guideProgrammeSlots(listOf(first, next))
        assertEquals(listOf(0L, 10L), slots.map { it.startTime })
        assertEquals(listOf(10L, 100L), slots.map { it.endTime })
        assertEquals(listOf(first, next), slots.map { it.programme })
    }
}
