package com.torve.data.recording

import com.torve.domain.model.Channel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgData
import com.torve.domain.model.EpgProgramme
import com.torve.domain.recording.EpgCorrection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the EPG correction application contract:
 *   - empty correction is byte-equivalent to baseline
 *   - offset shifts the rendered programme times
 *   - tvg-id remap routes a channel to a different EPG bucket
 *   - hidden categories drop matching channels from the rendered set
 *   - stale EPG can be detected from the corrected programme list
 */
class EpgCorrectionApplierTest {

    private val playlistId = "p1"

    private fun ch(name: String, tvgId: String?, group: String? = null): EnrichedChannel =
        EnrichedChannel(
            channel = Channel(
                name = name,
                url = "http://stream/$name",
                tvgId = tvgId,
                groupTitle = group,
                playlistId = playlistId,
            ),
        )

    private fun p(channelKey: String, title: String, start: Long, end: Long): EpgProgramme =
        EpgProgramme(channelId = channelKey, startTime = start, endTime = end, title = title)

    private fun epgData(
        programmes: List<EpgProgramme>,
    ): EpgData = EpgData(
        channels = emptyMap(),
        programmes = programmes,
        programmesByChannelKey = programmes.groupBy { it.channelId },
        generationId = 1L,
    )

    @Test
    fun `empty correction is identity for matched channels`() {
        val channels = listOf(ch("BBC One", "bbc.one"))
        val programmes = listOf(p("$playlistId::bbc.one", "Sherlock", 100L, 200L))
        val out = EpgCorrectionApplier.apply(
            playlistId = playlistId,
            channels = channels,
            epgData = epgData(programmes),
            correction = EpgCorrection(playlistId = playlistId),
        )
        assertEquals(1, out.matchedChannels)
        assertEquals(0, out.unmatchedChannels)
        assertEquals(programmes, out.programmesByKey["$playlistId::bbc.one"])
        assertEquals(programmes, out.correctedProgrammes)
    }

    @Test
    fun `offset shifts every programme by N minutes`() {
        val channels = listOf(ch("BBC One", "bbc.one"))
        val programmes = listOf(p("$playlistId::bbc.one", "Sherlock", 0L, 60_000L))
        val out = EpgCorrectionApplier.apply(
            playlistId = playlistId,
            channels = channels,
            epgData = epgData(programmes),
            correction = EpgCorrection(playlistId = playlistId, offsetMinutes = 60),
        )
        val rendered = out.programmesByKey["$playlistId::bbc.one"]!!.single()
        assertEquals(3_600_000L, rendered.startTime)
        assertEquals(3_660_000L, rendered.endTime)
        // The corrected list is what the stale-EPG health snapshot
        // consumes — same shifted values.
        assertEquals(3_660_000L, out.correctedProgrammes.single().endTime)
    }

    @Test
    fun `tvg-id remap routes a channel to a different EPG bucket`() {
        // Channel's own tvg-id is "playlist-bbc1"; the EPG provides
        // programmes under key "$playlistId::bbc.one.hd". Without the
        // remap the channel sees nothing; with it, the channel matches.
        val channels = listOf(ch("BBC One", "playlist-bbc1"))
        val programmes = listOf(p("$playlistId::bbc.one.hd", "Sherlock", 0L, 60_000L))
        val correction = EpgCorrection(
            playlistId = playlistId,
            tvgIdRemap = mapOf("playlist-bbc1" to "bbc.one.hd"),
        )
        val out = EpgCorrectionApplier.apply(playlistId, channels, epgData(programmes), correction)
        assertEquals(1, out.matchedChannels)
        // The lookup map uses the channel's own canonical key as the
        // outer key so the renderer's existing keying still works.
        val rendered = out.programmesByKey["$playlistId::playlist-bbc1"]
        assertNotNull(rendered)
        assertEquals(1, rendered.size)
        assertEquals("Sherlock", rendered.single().title)
    }

    @Test
    fun `tvg-id remap on the canonical-key suffix also matches`() {
        // Some installs key the channel by a non-tvg-id name; allow the
        // user to remap by the canonical key suffix too.
        val channels = listOf(ch("BBC One", tvgId = null))
        val programmes = listOf(p("$playlistId::bbc.one.hd", "Sherlock", 0L, 60_000L))
        val correction = EpgCorrection(
            playlistId = playlistId,
            tvgIdRemap = mapOf("bbcone" to "bbc.one.hd"),
        )
        val out = EpgCorrectionApplier.apply(playlistId, channels, epgData(programmes), correction)
        assertEquals(1, out.matchedChannels)
    }

    @Test
    fun `hidden categories filter is identity when empty`() {
        val channels = listOf(ch("Adult", "adult", group = "Adult"), ch("BBC", "bbc"))
        val out = EpgCorrectionApplier.filterHiddenCategories(
            channels,
            EpgCorrection(playlistId = playlistId),
        )
        assertEquals(channels, out)
    }

    @Test
    fun `hidden categories drop matching channels`() {
        val channels = listOf(
            ch("Adult Channel", "adult", group = "Adult"),
            ch("BBC One", "bbc", group = "News"),
        )
        val out = EpgCorrectionApplier.filterHiddenCategories(
            channels,
            EpgCorrection(playlistId = playlistId, hiddenCategories = setOf("adult")),
        )
        assertEquals(1, out.size)
        assertEquals("BBC One", out.single().channel.name)
    }

    @Test
    fun `unmatched channel still returns an empty list under its own key`() {
        val channels = listOf(ch("Mystery", "mystery"))
        val programmes = listOf(p("$playlistId::other", "Other", 0L, 1L))
        val out = EpgCorrectionApplier.apply(
            playlistId,
            channels,
            epgData(programmes),
            EpgCorrection(playlistId = playlistId),
        )
        assertEquals(0, out.matchedChannels)
        assertEquals(1, out.unmatchedChannels)
        assertTrue(out.programmesByKey["$playlistId::mystery"].isNullOrEmpty())
    }

    @Test
    fun `channel with neither tvg-id nor name produces no key and counts unmatched`() {
        val channels = listOf(ch("", null))
        val out = EpgCorrectionApplier.apply(
            playlistId,
            channels,
            epgData(emptyList()),
            EpgCorrection(playlistId = playlistId),
        )
        assertEquals(0, out.matchedChannels)
        assertEquals(1, out.unmatchedChannels)
        assertNull(out.programmesByKey[""])
    }
}
