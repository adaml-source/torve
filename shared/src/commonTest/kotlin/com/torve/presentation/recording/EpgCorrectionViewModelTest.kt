package com.torve.presentation.recording

import com.torve.data.recording.EpgCorrectionRepository
import com.torve.domain.model.EpgProgramme
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpgCorrectionViewModelTest {

    private fun newVm(): Pair<EpgCorrectionViewModel, InMemoryPrefs> {
        val prefs = InMemoryPrefs()
        val repo = EpgCorrectionRepository(prefs)
        val testScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        return EpgCorrectionViewModel(repo, scope = testScope) to prefs
    }

    @Test
    fun `load hydrates the correction for a playlist`() = runTest {
        val (vm, prefs) = newVm()
        prefs.store["epg_correction_p1"] =
            """{"playlistId":"p1","offsetMinutes":60,"tvgIdRemap":{},"hiddenCategories":[]}"""
        vm.load("p1")
        val state = vm.state.first { it.isHydrated }
        assertEquals("p1", state.correction.playlistId)
        assertEquals(60, state.correction.offsetMinutes)
    }

    @Test
    fun `setOffsetMinutes persists and updates state`() = runTest {
        val (vm, prefs) = newVm()
        vm.load("p1")
        vm.state.first { it.isHydrated }
        vm.setOffsetMinutes(30)
        val updated = vm.state.first { it.correction.offsetMinutes == 30 }
        assertEquals(30, updated.correction.offsetMinutes)
        assertTrue(prefs.store["epg_correction_p1"]!!.contains("\"offsetMinutes\":30"))
    }

    @Test
    fun `setMapping adds and removes entries`() = runTest {
        val (vm, _) = newVm()
        vm.load("p1")
        vm.state.first { it.isHydrated }
        vm.setMapping("ch-1", "epg-x")
        val withMap = vm.state.first { it.correction.tvgIdRemap.isNotEmpty() }
        assertEquals("epg-x", withMap.correction.tvgIdRemap["ch-1"])

        vm.setMapping("ch-1", null)
        val cleared = vm.state.first { it.correction.tvgIdRemap.isEmpty() }
        assertTrue(cleared.correction.tvgIdRemap.isEmpty())
    }

    @Test
    fun `toggleHiddenCategory flips category membership`() = runTest {
        val (vm, _) = newVm()
        vm.load("p1")
        vm.state.first { it.isHydrated }
        vm.toggleHiddenCategory("Adult")
        val added = vm.state.first { "Adult" in it.correction.hiddenCategories }
        assertTrue("Adult" in added.correction.hiddenCategories)

        vm.toggleHiddenCategory("Adult")
        val removed = vm.state.first { "Adult" !in it.correction.hiddenCategories }
        assertFalse("Adult" in removed.correction.hiddenCategories)
    }

    @Test
    fun `updateHealth flags stale and surfaces match percent`() = runTest {
        val (vm, _) = newVm()
        vm.load("p1")
        val nowMs = 1_700_000_000_000L
        val programmes = listOf(
            EpgProgramme(channelId = "a", startTime = nowMs - 3_600_000L, endTime = nowMs - 60_000L, title = "Stale"),
        )
        vm.updateHealth(matchedChannelCount = 5, unmatchedChannelCount = 5, programmes, nowMs)
        val withHealth = vm.state.first { it.health != null }
        val health = withHealth.health!!
        assertTrue(health.isStale)
        assertEquals(50, health.matchPercent)
    }

    private class InMemoryPrefs : PreferencesRepository {
        val store: MutableMap<String, String> = mutableMapOf()
        override suspend fun getString(key: String): String? = store[key]
        override suspend fun setString(key: String, value: String) { store[key] = value }
        override suspend fun remove(key: String) { store.remove(key) }
    }
}
