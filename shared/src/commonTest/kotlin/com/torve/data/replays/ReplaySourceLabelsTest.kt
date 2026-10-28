package com.torve.data.replays

import kotlin.test.*

class ReplaySourceLabelsTest {
    @Test fun duplicateHostsHaveDistinctStableNumbersAndKeepSuppliedDetails() {
        val first = ReplaySourceOption("one", "F1TV 1080p - OK", "https://ok.ru/videoembed/1", "https://sports.example/race")
        val second = first.copy(id = "two", title = "Sky Sports English 720p - OK", url = "https://ok.ru/videoembed/2")
        val options = listOf(first, second)
        assertEquals("Source 1 · OK.ru", ReplaySourceLabels.title(first, options))
        assertEquals("Source 2 · OK.ru", ReplaySourceLabels.title(second, options))
        assertEquals(first.title, ReplaySourceLabels.detail(first))
        assertEquals(second.title, ReplaySourceLabels.detail(second))
    }
    @Test fun unknownHostsDoNotInventQualityLanguageOrSeekSupport() {
        val option = ReplaySourceOption("one", "unknown.example", "https://unknown.example/watch", "https://sports.example/race")
        assertEquals("Source 1 · unknown.example", ReplaySourceLabels.title(option, listOf(option)))
        assertEquals("Full replay · Alternative video host", ReplaySourceLabels.detail(option))
    }
    @Test fun numberingRestartsWithinEachGameAndFriendlyNamesSurviveIntermediateHosts() {
        val first = ReplaySourceOption("one", "Server #1 (FM)", "https://intermediate.example/1", "https://sports.example", "A vs B")
        val second = first.copy(id = "two", title = "Vidara", url = "https://gasobni.example/2", gameTitle = "C vs D")
        assertEquals("Source 1 · Filemoon", ReplaySourceLabels.title(first, listOf(first, second)))
        assertEquals("Source 1 · Vidara", ReplaySourceLabels.title(second, listOf(first, second)))
    }
}
