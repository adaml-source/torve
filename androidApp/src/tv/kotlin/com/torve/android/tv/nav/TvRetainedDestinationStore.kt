package com.torve.android.tv.nav

/**
 * Keeps destination-owned state alive while Navigation Compose temporarily
 * removes that destination's UI from composition behind a child route.
 *
 * The bounded access-order map prevents an unbounded history of closed
 * destinations. Values are released only when evicted or when the owning
 * NavHost is disposed, never merely because a child destination covers them.
 */
internal class TvRetainedDestinationStore<K : Any, V : Any>(
    private val maxEntries: Int,
    private val onRelease: (V) -> Unit = {},
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val values = LinkedHashMap<K, V>(maxEntries, 0.75f, true)

    fun getOrPut(key: K, factory: () -> V): V {
        values[key]?.let { return it }
        return factory().also { value ->
            values[key] = value
            trimToCapacity()
        }
    }

    fun remove(key: K) {
        values.remove(key)?.let(onRelease)
    }

    fun clear() {
        values.values.toList().forEach(onRelease)
        values.clear()
    }

    internal fun contains(key: K): Boolean = values.containsKey(key)

    private fun trimToCapacity() {
        while (values.size > maxEntries) {
            val eldestKey = values.entries.first().key
            remove(eldestKey)
        }
    }
}
