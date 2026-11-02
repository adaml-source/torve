package com.torve.data.channels

import com.torve.domain.model.Channel
import kotlinx.coroutines.CancellationException

internal class ChannelBatchWriteException(cause: Exception) : IllegalStateException("Could not save the staged channel batch", cause)

/** Retain one database batch, rather than every decoded channel in a provider catalogue. */
internal class BoundedChannelBatchWriter(
    private val batchSize: Int,
    private val write: (List<Channel>, Int) -> Unit,
) {
    private val seen = HashSet<String>()
    private val pending = ArrayList<Channel>(batchSize)
    var count: Int = 0
        private set
    var last: Channel? = null
        private set

    init { require(batchSize > 0) }

    fun add(identity: String, channel: Channel) {
        if (!seen.add(identity)) return
        pending.add(channel)
        last = channel
        count++
        if (pending.size == batchSize) flush()
    }

    fun flush() {
        if (pending.isEmpty()) return
        try {
            write(pending, count - pending.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            throw ChannelBatchWriteException(failed)
        }
        pending.clear()
    }

    /** A failed all-categories request is discarded before the category fallback starts. */
    fun reset() {
        pending.clear()
        seen.clear()
        count = 0
        last = null
    }
}
