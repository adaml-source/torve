package com.torve.android.tv

/** Screen-owned features stay dormant until first opened. Refresh signals may
 * update an existing screen but cannot bootstrap an unopened catalogue. */
internal class TvDeferredFeature<T : Any>(private val create: () -> T) {
    @Volatile private var instance: T? = null
    fun get(): T = instance ?: synchronized(this) { instance ?: create().also { instance = it } }
    fun ifOpened(action: (T) -> Unit) { instance?.let(action) }
}
