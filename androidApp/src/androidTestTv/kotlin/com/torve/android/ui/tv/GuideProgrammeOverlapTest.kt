package com.torve.android.ui.tv

import com.torve.android.tv.screens.buildWindowCells
import com.torve.android.ui.channels.mobileGuideProgrammeSlots
import com.torve.domain.model.EpgProgramme
import org.junit.Assert.*
import org.junit.Test

class GuideProgrammeOverlapTest {
    private val original = EpgProgramme("mtv", 0, 90, "Caught in the Act")
    private val shifted = original.copy(startTime = 10, endTime = 100, title = " Caught  in the Act ")
    private val repeat = original.copy(startTime = 100, endTime = 180)
    private val entries = listOf(repeat, shifted, original, original)

    @Test fun tvMergesOverlappingCopiesButKeepsLaterSameTitleBroadcast() {
        val cells = buildWindowCells(entries, 0, 200)
        assertEquals(listOf(0L, 100L, 180L), cells.map { it.startMs })
        assertEquals(listOf(100L, 180L, 200L), cells.map { it.endMs })
        assertEquals(200L, cells.sumOf { it.endMs - it.startMs })
        assertEquals(listOf(original), cells.filter { it.isCurrentAt(50) }.map { it.programme })
        assertEquals(listOf(repeat), cells.filter { it.isCurrentAt(100) }.map { it.programme })
        assertFalse(cells.last().isCurrentAt(190))
        assertEquals(90L, cells.first().programme!!.endTime)
    }

    @Test fun tvHighlightUsesVisibleSlotInsteadOfOverlappingOriginalTimes() {
        val first = original.copy(endTime = 180)
        val next = original.copy(startTime = 60, endTime = 120, title = "Next show")
        val cells = buildWindowCells(listOf(next, first), 20, 140)
        assertEquals(listOf(20L, 60L, 120L), cells.map { it.startMs })
        assertEquals(listOf(next), cells.filter { it.isCurrentAt(80) }.map { it.programme })
        assertTrue(cells.zipWithNext().all { (left, right) -> left.endMs == right.startMs })
        assertEquals(180L, cells.first().programme!!.endTime)
    }

    @Test fun tvPastAndFutureWindowsCannotMarkARepeatAsCurrent() {
        val past = buildWindowCells(entries, 0, 40)
        val future = buildWindowCells(entries, 110, 200)
        assertTrue(past.none { it.isCurrentAt(50) })
        assertTrue(future.none { it.isCurrentAt(50) })
        assertEquals(40L, past.sumOf { it.endMs - it.startMs })
        assertEquals(90L, future.sumOf { it.endMs - it.startMs })
    }

    @Test fun phoneMtvDuplicatesMergeWithoutRemovingTheNextBroadcast() {
        val slots = mobileGuideProgrammeSlots(entries, 20, 200)
        assertEquals(2, slots.size)
        assertEquals(listOf(20L, 100L), slots.map { it.startTime })
        assertEquals(listOf(100L, 180L), slots.map { it.endTime })
        assertEquals(listOf(original), slots.filter { it.isCurrentAt(50) }.map { it.programme })
        assertEquals(listOf(repeat), slots.filter { it.isCurrentAt(100) }.map { it.programme })
        assertEquals(0L, slots.first().schedule.startTime)
        assertEquals(100L, slots.first().schedule.endTime)
        assertEquals(90L, slots.first().programme.endTime)
    }

    @Test fun phoneConflictsUseOneCurrentSlotAndNonOverlappingTimeLabels() {
        val first = original.copy(endTime = 180)
        val next = original.copy(startTime = 60, endTime = 120, title = "Next show")
        val slots = mobileGuideProgrammeSlots(listOf(next, first), 20, 140)
        assertEquals(listOf(60L, 120L), slots.map { it.schedule.endTime })
        assertEquals(listOf(next), slots.filter { it.isCurrentAt(80) }.map { it.programme })
        assertTrue(slots.zipWithNext().all { (left, right) -> left.endTime <= right.startTime })
        assertEquals(180L, slots.first().programme.endTime)
    }

    @Test fun invalidEntriesAndDuplicateStartsNeverCreateExtraCells() {
        val data = listOf(original, original.copy(endTime = 30, title = "Incomplete"),
            original.copy(startTime = 200, endTime = 100, title = "Invalid"))
        assertEquals(listOf(original), buildWindowCells(data, 0, 100).mapNotNull { it.programme })
        assertEquals(listOf(original), mobileGuideProgrammeSlots(data, 0, 100).map { it.programme })
    }

    @Test fun slightlyOverlappingConsecutiveMtvEpisodesDoNotMergeIntoAnAllDayCurrentShow() {
        val feed = listOf(original.copy(startTime = 0, endTime = 45),
            original.copy(startTime = 44, endTime = 99), original.copy(startTime = 45, endTime = 95),
            original.copy(startTime = 95, endTime = 145), original.copy(startTime = 99, endTime = 150))
        val tv = buildWindowCells(feed.reversed(), 0, 200)
        val phone = mobileGuideProgrammeSlots(feed.reversed(), 0, 200)
        assertEquals(listOf(0L, 44L, 95L), tv.filter { it.programme != null }.map { it.startMs })
        assertEquals(listOf(0L, 44L, 95L), phone.map { it.startTime })
        assertEquals(listOf(44L, 95L, 150L), phone.map { it.endTime })
        assertEquals(listOf(44L), tv.filter { it.isCurrentAt(50) }.map { it.startMs })
        assertEquals(listOf(44L), phone.filter { it.isCurrentAt(50) }.map { it.startTime })
    }

    @Test fun differentEpisodeSubtitlesRemainSeparateEvenWithOverlappingFeedTimes() {
        val first = original.copy(subTitle = "Episode one")
        val second = shifted.copy(title = original.title, subTitle = "Episode two")
        val tv = buildWindowCells(listOf(first, second), 0, 100)
        val phone = mobileGuideProgrammeSlots(listOf(first, second), 0, 100)
        assertEquals(2, phone.size)
        assertEquals(listOf(second), tv.filter { it.isCurrentAt(50) }.map { it.programme })
        assertEquals(listOf(second), phone.filter { it.isCurrentAt(50) }.map { it.programme })
    }
}
