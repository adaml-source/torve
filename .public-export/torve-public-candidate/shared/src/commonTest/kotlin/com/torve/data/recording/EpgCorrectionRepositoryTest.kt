package com.torve.data.recording

import com.torve.domain.recording.EpgCorrection
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the persistence contract for EPG corrections:
 *   - default get() returns an empty correction, never null.
 *   - save() round-trips offset + remap + hidden categories.
 *   - save() with an empty correction removes the key.
 *   - per-playlist isolation: editing playlist A doesn't touch B.
 */
class EpgCorrectionRepositoryTest {

    @Test
    fun `get returns empty correction when nothing persisted`() = runTest {
        val repo = EpgCorrectionRepository(InMemoryPrefs())
        val out = repo.get("p1")
        assertEquals("p1", out.playlistId)
        assertTrue(out.isEmpty)
    }

    @Test
    fun `save round-trips offset and remap and hidden categories`() = runTest {
        val prefs = InMemoryPrefs()
        val repo = EpgCorrectionRepository(prefs)
        val corrected = EpgCorrection(
            playlistId = "p1",
            offsetMinutes = 60,
            tvgIdRemap = mapOf("playlist-bbc1" to "bbc.one"),
            hiddenCategories = setOf("Adult", "Religious"),
        )
        repo.save(corrected)
        val out = repo.get("p1")
        assertEquals(60, out.offsetMinutes)
        assertEquals("bbc.one", out.tvgIdRemap["playlist-bbc1"])
        assertTrue("Adult" in out.hiddenCategories)
    }

    @Test
    fun `save with empty correction drops the persisted key`() = runTest {
        val prefs = InMemoryPrefs()
        val repo = EpgCorrectionRepository(prefs)
        repo.save(EpgCorrection(playlistId = "p1", offsetMinutes = 60))
        // Now revert.
        repo.save(EpgCorrection(playlistId = "p1"))
        assertNull(prefs.store["epg_correction_p1"])
    }

    @Test
    fun `setOffsetMinutes preserves remap and hidden categories`() = runTest {
        val repo = EpgCorrectionRepository(InMemoryPrefs())
        repo.save(
            EpgCorrection(
                playlistId = "p1",
                tvgIdRemap = mapOf("a" to "b"),
                hiddenCategories = setOf("Adult"),
            ),
        )
        repo.setOffsetMinutes("p1", 30)
        val out = repo.get("p1")
        assertEquals(30, out.offsetMinutes)
        assertEquals("b", out.tvgIdRemap["a"])
        assertTrue("Adult" in out.hiddenCategories)
    }

    @Test
    fun `setTvgIdMapping with null target removes the entry`() = runTest {
        val repo = EpgCorrectionRepository(InMemoryPrefs())
        repo.setTvgIdMapping("p1", channelId = "a", epgChannelId = "b")
        repo.setTvgIdMapping("p1", channelId = "a", epgChannelId = null)
        assertTrue(repo.get("p1").tvgIdRemap.isEmpty())
    }

    @Test
    fun `setTvgIdMapping with self target removes the entry`() = runTest {
        val repo = EpgCorrectionRepository(InMemoryPrefs())
        repo.setTvgIdMapping("p1", channelId = "a", epgChannelId = "b")
        // Mapping a→a is meaningless — drop instead of storing.
        repo.setTvgIdMapping("p1", channelId = "a", epgChannelId = "a")
        assertTrue(repo.get("p1").tvgIdRemap.isEmpty())
    }

    @Test
    fun `playlists are isolated`() = runTest {
        val repo = EpgCorrectionRepository(InMemoryPrefs())
        repo.setOffsetMinutes("p1", 60)
        repo.setOffsetMinutes("p2", -30)
        assertEquals(60, repo.get("p1").offsetMinutes)
        assertEquals(-30, repo.get("p2").offsetMinutes)
    }

    private class InMemoryPrefs : PreferencesRepository {
        val store: MutableMap<String, String> = mutableMapOf()
        override suspend fun getString(key: String): String? = store[key]
        override suspend fun setString(key: String, value: String) { store[key] = value }
        override suspend fun remove(key: String) { store.remove(key) }
    }
}
