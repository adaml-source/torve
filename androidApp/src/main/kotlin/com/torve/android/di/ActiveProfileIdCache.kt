package com.torve.android.di

import app.cash.sqldelight.Query
import com.torve.db.TorveDatabase
import java.util.concurrent.atomic.AtomicInteger

/** Profile-table notifications invalidate reads synchronously, including remote restores. */
internal class ActiveProfileIdCache(private val load: (String) -> String?) {
    private data class Entry(val revision: Int, val profileId: String?)
    private val revision = AtomicInteger()
    private val entries = mutableMapOf<String, Entry>()

    fun invalidate() { revision.incrementAndGet() }

    @Synchronized
    fun current(accountId: String): String? {
        while (true) {
            val version = revision.get()
            entries[accountId]?.takeIf { it.revision == version }?.let { return it.profileId }
            val value = load(accountId)
            if (revision.get() == version) {
                entries[accountId] = Entry(version, value)
                return value
            }
        }
    }

    companion object {
        fun observing(database: TorveDatabase): ActiveProfileIdCache {
            val queries = database.torveQueries
            val cache = ActiveProfileIdCache { accountId -> queries.getActiveProfile(accountId).executeAsOneOrNull()?.id }
            // SQLDelight notifies table listeners after writes/transactions. The
            // listener takes no cache lock, avoiding inversion with a database lock.
            queries.getAllProfiles("").addListener(object : Query.Listener {
                override fun queryResultsChanged() = cache.invalidate()
            })
            return cache
        }
    }
}
