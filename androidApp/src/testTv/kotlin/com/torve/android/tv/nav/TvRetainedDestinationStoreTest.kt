package com.torve.android.tv.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRetainedDestinationStoreTest {
    @Test
    fun `returns the same destination state while its entry is retained`() {
        val store = TvRetainedDestinationStore<String, Any>(maxEntries = 2)

        val original = store.getOrPut("details-entry") { Any() }
        val restored = store.getOrPut("details-entry") { Any() }

        assertSame(original, restored)
    }

    @Test
    fun `evicts least recently used destination and releases it`() {
        val released = mutableListOf<String>()
        val store = TvRetainedDestinationStore<String, String>(
            maxEntries = 2,
            onRelease = released::add,
        )
        store.getOrPut("first") { "first-state" }
        store.getOrPut("second") { "second-state" }
        store.getOrPut("first") { error("already retained") }

        store.getOrPut("third") { "third-state" }

        assertTrue(store.contains("first"))
        assertFalse(store.contains("second"))
        assertTrue(store.contains("third"))
        assertEquals(listOf("second-state"), released)
    }

    @Test
    fun `clear releases every retained value exactly once`() {
        val released = mutableListOf<String>()
        val store = TvRetainedDestinationStore<String, String>(
            maxEntries = 2,
            onRelease = released::add,
        )
        store.getOrPut("one") { "one-state" }
        store.getOrPut("two") { "two-state" }

        store.clear()
        store.clear()

        assertEquals(listOf("one-state", "two-state"), released)
    }
}
