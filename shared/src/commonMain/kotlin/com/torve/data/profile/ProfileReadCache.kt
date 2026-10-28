package com.torve.data.profile

import app.cash.sqldelight.Query
import com.torve.db.TorveDatabase
import com.torve.db.User_profile
import com.torve.domain.model.ContentRating
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** App-lifetime profile reads. SQLDelight invalidates these on every profile-table write,
 * including cloud imports and transactions. There is no time-based policy cache. */
internal class ProfileReadCache(private val database: TorveDatabase) {
    private data class Snapshot(val account: String, val revision: Long, val profiles: List<User_profile>)
    private val revision = MutableStateFlow(0L)
    private val cached = MutableStateFlow<Snapshot?>(null)
    private val changes = database.torveQueries.getAllProfiles("")
    private val listener = Query.Listener { revision.update { it + 1 } }

    init { changes.addListener(listener) }

    internal fun profiles(account: String): List<User_profile> {
        val version = revision.value
        cached.value?.takeIf { it.account == account && it.revision == version }?.let { return it.profiles }
        val rows = database.torveQueries.getAllProfiles(account).executeAsList()
        cached.value = Snapshot(account, version, rows)
        return rows
    }

    fun activeProfileId(account: String): String? = profiles(account).firstOrNull { it.is_active == 1L }?.id
    fun ratingLimit(account: String, profile: String): ContentRating? =
        ContentRating.fromString(profiles(account).firstOrNull { it.id == profile }?.max_content_rating)
}
