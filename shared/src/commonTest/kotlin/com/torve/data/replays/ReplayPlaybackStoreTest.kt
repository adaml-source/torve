package com.torve.data.replays

import com.torve.data.auth.UserIdProvider
import com.torve.domain.repository.DeviceLocalSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReplayPlaybackStoreTest {
    private class Fixture {
        var owner = "profile-a"
        var gate: CompletableDeferred<Unit>? = null
        val storage = mutableMapOf<String, MutableMap<String, String>>()
        val local = object : DeviceLocalSettingsRepository {
            override suspend fun getString(key: String): String? { gate?.await(); return storage[owner]?.get(key) }
            override suspend fun setString(key: String, value: String) { storage.getOrPut(owner) { mutableMapOf() }[key] = value }
            override suspend fun remove(key: String) { storage[owner]?.remove(key) }
        }
        val store = ReplayPlaybackStore(local, { owner })
    }
    @Test fun bookmarksOnlyAfterOutputAndNeverInMovieHistory() = runTest {
        val f = Fixture()
        f.store.save(f.owner, "web-replay:a", 50_000, 100_000, false)
        assertTrue(f.storage.isEmpty())
        f.store.save(f.owner, "tmdb:123", 50_000, 100_000, true)
        assertTrue(f.storage.isEmpty())
        f.store.save(f.owner, "web-replay:a", 50_000, 100_000, true)
        assertEquals(50_000, f.store.startPosition("web-replay:a"))
        assertEquals(setOf("web_replays_v1_progress"), f.storage.getValue(f.owner).keys)
    }
    @Test fun completedReplayStartsAgainAndGuestHasPrivateKey() = runTest {
        val f = Fixture(); f.owner = UserIdProvider.SIGNED_OUT_USER_ID
        f.store.save(f.owner, "web-replay:a", 96_000, 100_000, true)
        assertEquals(0, f.store.startPosition("web-replay:a"))
        assertEquals(setOf("web_replays_guest_web_replays_v1_progress"), f.storage.getValue(f.owner).keys)
    }
    @Test fun profileSwitchDuringReadCannotWriteOldBookmarkToNewProfile() = runTest {
        val f = Fixture(); val gate = CompletableDeferred<Unit>(); f.gate = gate
        val work = launch { f.store.save("profile-a", "web-replay:a", 30_000, 100_000, true) }
        runCurrent(); f.owner = "profile-b"; gate.complete(Unit); work.join()
        assertTrue(f.storage.isEmpty())
    }
    @Test fun headersRequireExactUrlAndOwnerAndAreSingleUse() = runTest {
        val f = Fixture(); val stream = ResolvedReplay("https://cdn.example/video.m3u8?token=short", mapOf("Referer" to "https://sports.example/"))
        f.store.stage(stream)
        assertNull(f.store.consumeHeaders("https://cdn.example/other.m3u8"))
        assertEquals(stream.headers, f.store.consumeHeaders(stream.url))
        assertNull(f.store.consumeHeaders(stream.url))
        f.store.stage(stream); f.owner = "profile-b"
        assertNull(f.store.consumeHeaders(stream.url))
    }
}
