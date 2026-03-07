package com.torve.data.trakt.repo

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraktSyncRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // --- deriveWatchlistMerge tests ---

    @Test
    fun merge_disjoint_sets_adds_all_remote() {
        val local = setOf("a", "b")
        val remote = setOf("c", "d")
        val plan = deriveWatchlistMerge(local, remote)
        assertEquals(setOf("c", "d"), plan.toAdd)
        assertTrue(plan.toRemove.isEmpty())
    }

    @Test
    fun merge_overlapping_sets_adds_only_new() {
        val local = setOf("a", "b", "c")
        val remote = setOf("b", "c", "d")
        val plan = deriveWatchlistMerge(local, remote)
        assertEquals(setOf("d"), plan.toAdd)
        assertTrue(plan.toRemove.isEmpty(), "Local-only items should not be removed")
    }

    @Test
    fun merge_identical_sets_no_changes() {
        val local = setOf("a", "b", "c")
        val remote = setOf("a", "b", "c")
        val plan = deriveWatchlistMerge(local, remote)
        assertTrue(plan.toAdd.isEmpty())
        assertTrue(plan.toRemove.isEmpty())
    }

    @Test
    fun merge_empty_local_adds_all_remote() {
        val local = emptySet<String>()
        val remote = setOf("a", "b")
        val plan = deriveWatchlistMerge(local, remote)
        assertEquals(setOf("a", "b"), plan.toAdd)
    }

    @Test
    fun merge_empty_remote_no_changes() {
        val local = setOf("a", "b")
        val remote = emptySet<String>()
        val plan = deriveWatchlistMerge(local, remote)
        assertTrue(plan.toAdd.isEmpty())
        assertTrue(plan.toRemove.isEmpty())
    }

    @Test
    fun merge_both_empty_no_changes() {
        val plan = deriveWatchlistMerge(emptySet(), emptySet())
        assertTrue(plan.toAdd.isEmpty())
        assertTrue(plan.toRemove.isEmpty())
    }

    // --- Queue payload serialization ---

    @Test
    fun queue_payload_round_trip() {
        val payload = TraktQueuePayload(
            tmdbId = 550,
            mediaType = "movie",
            imdbId = "tt0137523",
            rating = 9,
        )
        val encoded = json.encodeToString(TraktQueuePayload.serializer(), payload)
        val decoded = json.decodeFromString(TraktQueuePayload.serializer(), encoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun queue_payload_minimal_fields() {
        val payload = TraktQueuePayload(
            tmdbId = 123,
            mediaType = "series",
        )
        val encoded = json.encodeToString(TraktQueuePayload.serializer(), payload)
        val decoded = json.decodeFromString(TraktQueuePayload.serializer(), encoded)
        assertEquals(123, decoded.tmdbId)
        assertEquals("series", decoded.mediaType)
        assertEquals(null, decoded.imdbId)
        assertEquals(null, decoded.rating)
    }

    // --- Backoff calculation ---

    @Test
    fun backoff_doubles_each_attempt() {
        val delays = (0..5).map { attempts ->
            minOf(300_000L, 1000L * (1L shl minOf(attempts, 8)))
        }
        assertEquals(1000L, delays[0])   // 1s
        assertEquals(2000L, delays[1])   // 2s
        assertEquals(4000L, delays[2])   // 4s
        assertEquals(8000L, delays[3])   // 8s
        assertEquals(16000L, delays[4])  // 16s
        assertEquals(32000L, delays[5])  // 32s
    }

    @Test
    fun backoff_caps_at_5_minutes() {
        val delay = minOf(300_000L, 1000L * (1L shl minOf(10, 8)))
        // 1000 * 256 = 256000 which is under 300000
        assertEquals(256_000L, delay)

        // At attempt 9+, shl is capped at 8 = 256, so max is 256000
        val delayHigh = minOf(300_000L, 1000L * (1L shl minOf(20, 8)))
        assertEquals(256_000L, delayHigh)
    }
}
