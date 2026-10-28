package com.torve.data.profile

import com.torve.data.sync.safeBackupIdentity
import com.torve.data.sync.safeBackupPreference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class LibraryBackup(val schemaVersion: Int = 1, val exportedAt: Long,
    val records: List<HouseholdRecord>, val preferences: List<LibraryBackupPreference> = emptyList())

@Serializable
internal data class LibraryBackupPreference(val profile: String, val key: String, val value: String)

internal val backupRecordFields = mapOf(
    "profile" to setOf("name", "avatar", "createdAt", "protected", "rating"),
    "progress" to setOf("mediaType", "title", "position", "duration", "updatedAt", "season", "episode", "show"),
    "history" to setOf("mediaId", "mediaType", "title", "watchedAt", "duration", "season", "episode", "show"),
    "watchlist" to setOf("mediaType", "title", "tmdb", "imdb", "rating", "year", "genres", "addedAt", "order"),
)

private const val MAX_BACKUP_NUMBER = 9_000_000_000_000_000L
private val backupIntegerFields = setOf("position", "duration", "updatedAt", "watchedAt", "createdAt",
    "addedAt", "avatar", "tmdb", "year", "season", "episode", "order")
private fun safeBackupValue(key: String, value: JsonElement): Boolean {
    if (value !is JsonPrimitive || value is JsonNull) return false
    if (value.isString && (value.content.length > 4096 || Regex("://|%[0-9a-fA-F]{2}").containsMatchIn(value.content))) return false
    if (key in backupIntegerFields) return !value.isString && value.longOrNull?.let { it in 0..MAX_BACKUP_NUMBER } == true
    if (key == "protected") return !value.isString && value.booleanOrNull != null
    return true
}

internal fun HouseholdRecord.forBackup(): HouseholdRecord? {
    if (deleted || !validRecord(this) || !safeBackupIdentity(id) || changedAt !in 0..MAX_BACKUP_NUMBER) return null
    val fields = backupRecordFields[kind] ?: return null
    val safeData = JsonObject(data.filter { (key, value) ->
        key in fields && safeBackupValue(key, value)
    })
    if (kind == "history" && safeData["mediaId"]?.jsonPrimitive?.content?.let(::safeBackupIdentity) != true) return null
    return copy(data = safeData)
}

internal fun LibraryBackup.validate(): LibraryBackup {
    require(schemaVersion == 1 && exportedAt in 1..MAX_BACKUP_NUMBER && records.size <= 20_000 && preferences.size <= 2_500) { "Unsupported or oversized backup." }
    val profiles = records.filter { it.kind == "profile" }.map { it.profile }.toSet()
    require(profiles.isNotEmpty() && profiles.size <= 8 && records.map { it.key }.distinct().size == records.size) { "Invalid backup profiles." }
    records.forEach { record ->
        require(record.profile in profiles && record.forBackup() == record &&
            (record.kind != "profile" || record.profile == record.id)) { "Backup contains unsupported data." }
    }
    preferences.forEach { require(it.profile in profiles && safeBackupPreference(it.key, it.value)) { "Backup contains unsupported preferences." } }
    require(preferences.map { it.profile to it.key }.distinct().size == preferences.size)
    return this
}
