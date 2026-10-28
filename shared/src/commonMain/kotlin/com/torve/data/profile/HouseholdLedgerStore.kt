package com.torve.data.profile

import com.torve.db.TorveDatabase

/** Keeps the durable outbox intact without exceeding Android's per-row read limit. */
internal class HouseholdLedgerStore(private val database: TorveDatabase) {
    fun read(account: String): String? = database.torveQueries.getHouseholdLedgerChunks(account)
        .executeAsList().takeIf { it.isNotEmpty() }?.joinToString("")

    fun write(account: String, value: String) {
        database.transaction {
            database.torveQueries.deleteHouseholdLedgerChunks(account)
            var start = 0
            var index = 0
            while (start < value.length) {
                var end = (start + CHUNK_CHARACTERS).coerceAtMost(value.length)
                // SQLite TEXT bindings must never receive half a surrogate pair.
                if (end < value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
                database.torveQueries.setPreference(account, PREFIX + index.toString().padStart(8, '0'), value.substring(start, end))
                start = end
                index++
            }
        }
    }

    companion object {
        const val PREFIX = "household_sync_ledger_v2:"
        const val CHUNK_CHARACTERS = 16_384
    }
}
