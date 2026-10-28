package com.torve.data.addon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StreamResultSortingTest {
    private fun stream(name: String, size: String?, score: Int = 0) =
        ParsedStream(addonName = "test", quality = "1080p", title = name, size = size, score = score)

    private val streams = listOf(
        stream("unknown", null, 100),
        stream("small", "800 MiB", 90),
        stream("large", "0.01 TB", 10),
        stream("medium", "2 GB", 70),
        stream("same size", "2048 MB", 60),
        stream("unreadable", "unknown"),
    )

    @Test
    fun largestFirstIgnoresScoreAndKeepsUnknownSizesLast() {
        assertEquals(listOf("large", "medium", "same size", "small", "unknown", "unreadable"),
            sortStreamResults(streams, StreamSortMode.SIZE_DESC).map { it.title })
    }

    @Test
    fun smallestFirstKeepsEqualSizesStableAndUnknownSizesLast() {
        assertEquals(listOf("small", "medium", "same size", "large", "unknown", "unreadable"),
            sortStreamResults(streams, StreamSortMode.SIZE_ASC).map { it.title })
    }

    @Test
    fun recommendedRetainsOriginalOrdering() {
        assertSame(streams, sortStreamResults(streams, StreamSortMode.DEFAULT))
    }
}
