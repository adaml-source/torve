package com.torve.android.di

import org.junit.Assert.*
import org.junit.Test

class ActiveProfileIdCacheTest {
    @Test fun repeatedReadsDoNotReopenDatabaseCursors() {
        var reads = 0
        val cache = ActiveProfileIdCache { reads++; "primary" }
        repeat(10_000) { assertEquals("primary", cache.current("account")) }
        assertEquals(1, reads)
    }

    @Test fun profileWritesInvalidateImmediatelyAndAccountsRemainSeparate() {
        val values = mutableMapOf("a" to "adult", "b" to "other")
        val cache = ActiveProfileIdCache(values::get)
        assertEquals("adult", cache.current("a"))
        assertEquals("other", cache.current("b"))
        values["a"] = "child"
        cache.invalidate()
        assertEquals("child", cache.current("a"))
        assertEquals("other", cache.current("b"))
    }

    @Test fun missingProfilesAreCachedButNewProfilesBecomeVisibleAfterInsert() {
        var reads = 0
        var value: String? = null
        val cache = ActiveProfileIdCache { reads++; value }
        repeat(100) { assertNull(cache.current("a")) }
        assertEquals(1, reads)
        value = "new"
        cache.invalidate()
        assertEquals("new", cache.current("a"))
    }

    @Test fun aWriteDuringAReadCannotLeaveAnOutdatedCachedProfile() {
        var reads = 0
        lateinit var cache: ActiveProfileIdCache
        cache = ActiveProfileIdCache {
            reads++
            if (reads == 1) { cache.invalidate(); "old" } else "new"
        }
        assertEquals("new", cache.current("a"))
        assertEquals("new", cache.current("a"))
        assertEquals(2, reads)
    }
}
