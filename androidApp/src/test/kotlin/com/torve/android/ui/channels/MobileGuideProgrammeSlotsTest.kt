package com.torve.android.ui.channels

import com.torve.domain.model.EpgProgramme
import org.junit.Test
import org.junit.Assert.*

class MobileGuideProgrammeSlotsTest {
    @Test fun clipsPastAndFutureWithoutChangingPlaybackTimes() {
        val current = EpgProgramme("key", 0, 200, "Current")
        val next = EpgProgramme("key", 200, 400, "Next")
        val slots = mobileGuideProgrammeSlots(listOf(next, current,
            EpgProgramme("key", -100, 0, "Old"), EpgProgramme("key", 400, 500, "Later")), 100, 300)
        assertEquals(listOf(100L, 200L), slots.map { it.startTime })
        assertEquals(listOf(200L, 300L), slots.map { it.endTime })
        assertEquals(0L, slots.first().programme.startTime)
    }
    @Test fun duplicateInvalidAndOverlappingEntriesDoNotPileUp() {
        val slots = mobileGuideProgrammeSlots(listOf(EpgProgramme("key", 0, 300, "First"),
            EpgProgramme("key", 0, 100, "Duplicate"), EpgProgramme("key", 200, 400, "Second"),
            EpgProgramme("key", 500, 400, "Invalid")), 0, 600)
        assertEquals(listOf("First", "Second"), slots.map { it.programme.title })
        assertEquals(200L, slots.first().endTime)
    }
}
