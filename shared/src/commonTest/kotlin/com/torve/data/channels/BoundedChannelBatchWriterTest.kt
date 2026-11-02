package com.torve.data.channels

import com.torve.domain.model.Channel
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class BoundedChannelBatchWriterTest {
    @Test fun largeCatalogIsSavedBeforeItsEndWithOnlyOneBatchRetained() {
        var written = 0
        var maximumBatch = 0
        val writer = BoundedChannelBatchWriter(40) { batch, offset ->
            assertEquals(written, offset)
            assertEquals("Channel $offset", batch.first().name)
            assertEquals("Channel ${offset + batch.size - 1}", batch.last().name)
            maximumBatch = maxOf(maximumBatch, batch.size)
            written += batch.size
        }
        repeat(100_003) { index ->
            writer.add(index.toString(), channel(index))
            assertTrue(index + 1 - written < 40, "Decoded channels must be flushed while the response is still arriving")
        }
        writer.add("0", channel(-1))
        writer.flush()
        assertEquals(40, maximumBatch)
        assertEquals(100_003, written)
        assertEquals(written, writer.count)
        assertEquals("Channel 100002", writer.last?.name)
    }

    @Test fun failedAggregateCanBeResetBeforeCategoryFallback() {
        val rows = mutableListOf<String>()
        val writer = BoundedChannelBatchWriter(2) { batch, offset ->
            assertEquals(rows.size, offset)
            rows += batch.map { it.name }
        }
        writer.add("0", channel(0)); writer.add("1", channel(1)); writer.add("2", channel(2))
        rows.clear(); writer.reset()
        writer.add("0", channel(10)); writer.flush()
        assertEquals(listOf("Channel 10"), rows)
        assertEquals(1, writer.count)
    }

    @Test fun databaseErrorsAndCancellationDoNotBecomePartialCatalogSuccess() {
        val failed = BoundedChannelBatchWriter(1) { _, _ -> error("Disk failure") }
        assertFailsWith<ChannelBatchWriteException> { failed.add("0", channel(0)) }
        val cancelled = BoundedChannelBatchWriter(1) { _, _ -> throw CancellationException("Cancelled") }
        assertFailsWith<CancellationException> { cancelled.add("0", channel(0)) }
    }

    private fun channel(index: Int) = Channel(name = "Channel $index", url = "https://example.test/$index")
}
