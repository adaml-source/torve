package com.torve.android.ui.channels

import com.torve.domain.model.EpgProgramme
import com.torve.presentation.channels.GuideProgrammeSlot
import com.torve.presentation.channels.guideProgrammeSlots

internal data class MobileGuideProgrammeSlot(
    val schedule: GuideProgrammeSlot,
    val startTime: Long,
    val endTime: Long,
) {
    val programme: EpgProgramme get() = schedule.programme
    fun isCurrentAt(now: Long): Boolean = now >= startTime && now < endTime
}

/** Clip display coordinates to the viewport; retain original times for playback. */
internal fun mobileGuideProgrammeSlots(programmes: List<EpgProgramme>, start: Long, end: Long): List<MobileGuideProgrammeSlot> {
    return guideProgrammeSlots(programmes).mapNotNull { slot ->
        val left = maxOf(start, slot.startTime)
        val right = minOf(end, slot.endTime)
        if (right <= left) null else MobileGuideProgrammeSlot(slot, left, right)
    }
}
