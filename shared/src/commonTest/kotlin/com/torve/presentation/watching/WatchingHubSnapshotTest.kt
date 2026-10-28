package com.torve.presentation.watching

import com.torve.domain.model.*
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.*

class WatchingHubSnapshotTest {
    private val prefs = SavedPreferences()
    private var person: String? = "account:profile1"
    private var now = 1_000_000L
    private fun store() = WatchingHubSnapshotStore(prefs, { person }, { now })
    private val episode = WatchingEpisode(
        MediaItem("12", tmdbId = 12, type = MediaType.SERIES, title = "Saved show", posterUrl = "poster",
            backdropUrl = "backdrop", seasons = listOf(Season(1, 2)), cast = listOf(CastMember(1, "Actor"))),
        1, Episode(2, "Next episode"), LocalDate(2026, 10, 18), resume = true,
        positionMs = 20_000L, progressPercent = .2f,
    )

    @Test fun newStoreRestoresEpisodeArtworkAndResumeWithoutMetadataOrTrakt() = runTest {
        store().write(WatchingHubState(personalDataUserId = person, upNext = listOf(episode), calendar = listOf(episode)))
        val restored = assertNotNull(store().read())
        assertEquals(episode.copy(show = episode.show.copy(seasons = emptyList(), cast = emptyList())), restored.upNext.single())
        assertEquals("backdrop", restored.calendar.single().show.backdropUrl)
        assertTrue(restored.upNext.single().show.seasons.isEmpty())
    }

    @Test fun accountProfileAndSignedOutCannotReadAnotherPersonsCards() = runTest {
        store().write(WatchingHubState(personalDataUserId = person, upNext = listOf(episode)))
        person = "account:profile2"; assertNull(store().read())
        person = "other-account:profile1"; assertNull(store().read())
        person = null; assertNull(store().read())
        person = "account:profile1"; assertNotNull(store().read())
    }

    @Test fun oldOwnerCannotOverwriteTheCurrentPersonsCache() = runTest {
        person = "new-person"
        store().write(WatchingHubState(personalDataUserId = "old-person", upNext = listOf(episode)))
        assertTrue(prefs.values.isEmpty())
    }

    @Test fun expiredFutureAndCorruptCopiesAreCacheMisses() = runTest {
        store().write(WatchingHubState(personalDataUserId = person, upNext = listOf(episode)))
        now += WatchingHubSnapshotStore.MAX_AGE_MS + 1; assertNull(store().read())
        now = 999_999; assertNull(store().read())
        prefs.values[WatchingHubSnapshotStore.KEY] = "broken"; assertNull(store().read())
    }

    @Test fun successfulEmptyLibraryReplacesOldCards() = runTest {
        store().write(WatchingHubState(personalDataUserId = person, upNext = listOf(episode)))
        store().write(WatchingHubState(personalDataUserId = person))
        assertTrue(assertNotNull(store().read()).upNext.isEmpty())
    }

    @Test fun ownerChangeDuringReadCannotPublishPreviousCards() = runTest {
        store().write(WatchingHubState(personalDataUserId = person, upNext = listOf(episode)))
        prefs.onRead = { person = "new-person" }
        assertNull(store().read())
    }
}

private class SavedPreferences : PreferencesRepository {
    val values = mutableMapOf<String, String>()
    var onRead: () -> Unit = {}
    override suspend fun getString(key: String): String? = values[key].also { onRead() }
    override suspend fun setString(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
}
