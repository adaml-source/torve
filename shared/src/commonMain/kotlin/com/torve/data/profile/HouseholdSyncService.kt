package com.torve.data.profile

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.torve.data.account.AccountSettingsApi
import com.torve.data.auth.AuthClient
import com.torve.data.auth.UserIdProvider
import com.torve.data.progress.readPreferenceSafely
import com.torve.data.auth.profileDataUserId
import com.torve.db.TorveDatabase
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.util.ioDispatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal data class HouseholdRecord(val profile: String, val kind: String, val id: String,
    val changedAt: Long, val deleted: Boolean = false, val data: JsonObject = JsonObject(emptyMap())) {
    val key get() = "household_v1:$profile:$kind:$id"
}

/** Deterministic ties make concurrent/offline edits converge even on equal clock ticks. */
internal fun compareHouseholdRecords(first: HouseholdRecord, second: HouseholdRecord): Int {
    require(first.key == second.key)
    val byTime = first.changedAt.compareTo(second.changedAt)
    if (byTime != 0) return byTime
    val byRemoval = first.deleted.compareTo(second.deleted)
    if (byRemoval != 0) return byRemoval
    return canonicalHouseholdJson(first.data).compareTo(canonicalHouseholdJson(second.data))
}

private fun canonicalHouseholdJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
        "${JsonPrimitive(it.key)}:${canonicalHouseholdJson(it.value)}"
    }
    is JsonArray -> value.joinToString(",", "[", "]") { canonicalHouseholdJson(it) }
    else -> value.toString()
}

@Serializable
private data class HouseholdLedger(val records: Map<String, HouseholdRecord> = emptyMap(),
    val pending: Set<String> = emptySet())

data class HouseholdSyncState(val syncing: Boolean = false, val lastSyncedAt: Long? = null,
    val pendingChanges: Int = 0, val error: String? = null, val personalDataUserId: String? = null)

/** Per-item records make independent devices' changes merge without replacing a whole library. */
@OptIn(FlowPreview::class)
class HouseholdSyncService(private val database: TorveDatabase, private val auth: AuthClient,
    private val api: AccountSettingsApi, private val userIds: UserIdProvider,
    private val notifier: SettingsRefreshNotifier,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    automatic: Boolean = true) {
    private val queries get() = database.torveQueries
    private val mutex = Mutex()
    private val requestedRefresh = Mutex()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val ledgerStore = HouseholdLedgerStore(database)
    private val _state = MutableStateFlow(HouseholdSyncState())
    val state = _state.asStateFlow()
    private var lastFetchScope: String? = null
    private var lastFetchAt = 0L

    init {
        if (automatic) scope.launch {
            auth.authUserFlow.map { it?.id }.distinctUntilChanged().collectLatest { account ->
                if (account == null) { _state.value = HouseholdSyncState(); return@collectLatest }
                // Active selection belongs to the device, while profile definitions belong to the household.
                queries.getAllProfiles(account).asFlow().mapToList(ioDispatcher)
                    .map { rows -> rows.firstOrNull { it.is_active == 1L }?.id ?: "default" }
                    .distinctUntilChanged().collectLatest { profile ->
                        val person = profileDataUserId(account, profile)
                        if (_state.value.personalDataUserId != person) _state.value = HouseholdSyncState(personalDataUserId = person)
                        refresh(force = true)
                        merge(
                            flow { while (currentCoroutineContext().isActive) { delay(15_000); emit(Unit) } },
                            queries.getAllProgress(person).asFlow().mapToList(ioDispatcher).map { Unit },
                            queries.getAllHistory(person).asFlow().mapToList(ioDispatcher).map { Unit },
                            queries.getAllWatchlist(person).asFlow().mapToList(ioDispatcher).map { Unit },
                            queries.getAllProfiles(account).asFlow().mapToList(ioDispatcher).map { Unit },
                            // Observe invalidations without materializing every preference/cache value.
                            queries.getPreferenceKeys(person).asFlow().debounce(250L).map {
                                NAVIGATION_KEYS.associateWith { key -> queries.readPreferenceSafely(person, key) }
                            }.distinctUntilChanged().map { Unit },
                        ).debounce(1_000L).collect { refresh() }
                    }
            }
        }
    }

    fun requestRefresh(force: Boolean = false) {
        if (!requestedRefresh.tryLock()) return
        scope.launch { refresh(force) }.invokeOnCompletion { requestedRefresh.unlock() }
    }
    fun clear() = scope.cancel()

    /** A local account reset must never be captured as household deletions. */
    suspend fun resetLocalAccountData(reset: suspend () -> Unit) = withContext(ioDispatcher) {
        mutex.withLock {
            reset()
            lastFetchScope = null
            lastFetchAt = 0L
            _state.value = HouseholdSyncState()
        }
    }

    /** Copy only public replay website configuration, retaining target websites and its own history. */
    suspend fun copyReplayWebsites(targetProfile: String, source: com.torve.data.replays.ReplayConfiguration) = withContext(ioDispatcher) {
        mutex.withLock {
            val account = userIds.currentUserIdOrNull() ?: error("Sign in to copy websites.")
            check(queries.getProfile(account, targetProfile).executeAsOneOrNull() != null) { "Choose a profile on this account." }
            val key = "web_replays_v1_settings"
            val person = profileDataUserId(account, targetProfile)
            val previous = runCatching { json.decodeFromString<com.torve.data.replays.ReplayConfiguration>(queries.readPreferenceSafely(person, key).orEmpty()) }
                .getOrDefault(com.torve.data.replays.ReplayConfiguration())
            val websites = (previous.websites + source.websites).associateBy { it.id }.values.toList()
            check(websites.size <= 12) { "The target profile can have up to 12 replay websites." }
            val encoded = json.encodeToString(previous.copy(enabled = previous.enabled || source.enabled, websites = websites))
            val ledger = readLedger(account)
            val recordKey = "household_v1:$targetProfile:navigation:$key"
            val time = maxOf(Clock.System.now().toEpochMilliseconds(), (ledger.records[recordKey]?.changedAt ?: 0) + 1)
            val record = HouseholdRecord(targetProfile, "navigation", key, time, data = buildJsonObject { put("value", encoded) })
            database.transaction {
                queries.setPreference(person, key, encoded)
                persistLedger(account, HouseholdLedger(ledger.records + (record.key to record), ledger.pending + record.key))
            }
        }
        requestRefresh(force = true)
    }

    suspend fun queuePinUpdate(profileId: String) = withContext(ioDispatcher) {
        val account = userIds.currentUserIdOrNull() ?: return@withContext
        val queued = queries.getPreference(account, PIN_QUEUE).executeAsOneOrNull().orEmpty()
            .split('|').filter { it.isNotBlank() }.toSet() + profileId
        queries.setPreference(account, PIN_QUEUE, queued.joinToString("|"))
        val revisionKey = "household_profile_pin_revision_$profileId"
        val priorRevision = queries.getPreference(account, revisionKey).executeAsOneOrNull()?.toLongOrNull() ?: 0L
        queries.setPreference(account, revisionKey, maxOf(Clock.System.now().toEpochMilliseconds(), priorRevision + 1).toString())
        requestRefresh(force = true)
    }

    suspend fun refresh(force: Boolean = false): Boolean = withContext(ioDispatcher) { mutex.withLock {
        val account = userIds.currentUserIdOrNull() ?: return@withLock false
        val person = userIds.currentDataUserId()
        if (_state.value.personalDataUserId != person) _state.value = HouseholdSyncState(personalDataUserId = person)
        _state.value = _state.value.copy(syncing = true, error = null)
        try {
            var ledger = readLedger(account)
            val knownKeys = ledger.records.keys
            ledger = captureLocal(account, ledger)
            persistLedger(account, ledger) // The outbox survives failed requests and process death.
            val now = Clock.System.now().toEpochMilliseconds()
            if (!force && ledger.pending.isEmpty() && lastFetchScope == person && now - lastFetchAt < 30_000L)
                return@withLock true
            _state.value = _state.value.copy(pendingChanges = ledger.pending.size)
            val token = auth.getValidAccessToken() ?: return@withLock false
            if (account != userIds.currentUserIdOrNull()) return@withLock false
            flushPins(account, token)
            val remote = api.getAccountSettings(token).settings.filterKeys { it.startsWith("household_v1:") }
                .mapNotNull { (key, value) -> runCatching { json.decodeFromString<HouseholdRecord>(value) }.getOrNull()?.takeIf { it.key == key } }
                .filter(::validRecord)
            if (account != userIds.currentUserIdOrNull()) return@withLock false
            val records = ledger.records.toMutableMap()
            val pending = ledger.pending.toMutableSet()
            var applied = false
            for (entry in remote) {
                val local = records[entry.key]
                val initialDefault = entry.kind == "profile" && entry.profile == "default" && entry.key !in knownKeys &&
                    queries.getProfile(account, "default").executeAsOneOrNull()?.let { it.name == "Default" && it.pin.isNullOrBlank() } == true
                val order = local?.let { compareHouseholdRecords(entry, it) } ?: 1
                if (local == null || order > 0 || initialDefault) {
                    applyRemote(account, entry, token)
                    records[entry.key] = entry
                    pending.remove(entry.key)
                    applied = true
                } else if (order == 0) {
                    pending.remove(entry.key)
                    if (force && entry.kind == "profile" && queries.getProfile(account, entry.profile).executeAsOneOrNull()?.pin == REMOTE_PIN_REQUIRED) {
                        applyRemote(account, entry, token); applied = true
                    }
                }
                else if (order < 0) pending.add(entry.key)
            }
            ledger = HouseholdLedger(records, pending)
            persistLedger(account, ledger)
            // Send only changed items. Retain tombstones so stale/offline clients cannot revive removed items.
            for (batch in pending.toList().chunked(40)) {
                if (account != userIds.currentUserIdOrNull()) return@withLock false
                api.patchAccountSettings(token, batch.associateWith { json.encodeToString(records.getValue(it)) })
                pending.removeAll(batch.toSet())
                persistLedger(account, HouseholdLedger(records, pending))
            }
            lastFetchAt = now; lastFetchScope = person
            if (person == userIds.currentDataUserId()) _state.value = HouseholdSyncState(lastSyncedAt = now, personalDataUserId = person)
            if (applied) notifier.notifyRefresh(now)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (person == userIds.currentDataUserId()) _state.value = _state.value.copy(syncing = false, error = "Profile sync will retry. Check your connection.")
            false
        } finally {
            if (_state.value.personalDataUserId == person) {
                _state.value = if (person == userIds.currentDataUserId()) _state.value.copy(syncing = false)
                    else HouseholdSyncState(personalDataUserId = userIds.currentDataUserId())
            }
        }
    } }

    private fun readLedger(account: String) = ledgerStore.read(account)
        ?.let { json.decodeFromString<HouseholdLedger>(it) } ?: HouseholdLedger()
    private fun persistLedger(account: String, ledger: HouseholdLedger) = ledgerStore.write(account, json.encodeToString(ledger))

    private fun captureLocal(account: String, ledger: HouseholdLedger, active: String = userIds.currentProfileId()): HouseholdLedger {
        val records = ledger.records.toMutableMap(); val pending = ledger.pending.toMutableSet()
        val now = Clock.System.now().toEpochMilliseconds()
        val profiles = queries.getAllProfiles(account).executeAsList()
        val profileIds = profiles.map { it.id }.toSet()
        val live = mutableMapOf<String, HouseholdRecord>()
        profiles.forEach { p ->
            val data = buildJsonObject {
                put("name", p.name); put("avatar", p.avatar_index); put("createdAt", p.created_at)
                put("protected", !p.pin.isNullOrBlank())
                put("pinRevision", queries.getPreference(account, "household_profile_pin_revision_${p.id}").executeAsOneOrNull()?.toLongOrNull() ?: 0L)
                p.max_content_rating?.let { put("rating", it) }
            }
            val entry = HouseholdRecord(p.id, "profile", p.id, p.created_at, data = data)
            live[entry.key] = entry
        }
        val person = profileDataUserId(account, active)
        queries.getAllProgress(person).executeAsList().forEach { p ->
            val data = buildJsonObject {
                put("mediaType", p.media_type); put("title", p.title)
                p.poster_url?.let { put("poster", it) }; p.backdrop_url?.let { put("backdrop", it) }
                put("position", p.position_ms); put("duration", p.duration_ms); put("updatedAt", p.updated_at)
                p.season_number?.let { put("season", it) }; p.episode_number?.let { put("episode", it) }
                p.show_title?.let { put("show", it) }
            }
            val entry = HouseholdRecord(active, "progress", p.media_id, p.updated_at, data = data)
            live[entry.key] = entry
        }
        queries.getAllHistory(person).executeAsList().forEach { h ->
            val data = buildJsonObject {
                put("mediaId", h.media_id); put("mediaType", h.media_type); put("title", h.title)
                h.poster_url?.let { put("poster", it) }; h.backdrop_url?.let { put("backdrop", it) }
                put("watchedAt", h.watched_at); put("duration", h.duration_watched_ms)
                h.season_number?.let { put("season", it) }; h.episode_number?.let { put("episode", it) }
                h.show_title?.let { put("show", it) }
            }
            val entry = HouseholdRecord(active, "history", h.id, h.watched_at, data = data)
            live[entry.key] = entry
        }
        queries.getAllWatchlist(person).executeAsList().forEach { w ->
            val data = buildJsonObject {
                put("mediaType", w.media_type); put("title", w.title); put("tmdb", w.tmdb_id)
                w.imdb_id?.let { put("imdb", it) }; w.poster_url?.let { put("poster", it) }
                w.backdrop_url?.let { put("backdrop", it) }; w.rating?.let { put("rating", it) }
                w.year?.let { put("year", it) }; w.genres?.let { put("genres", it) }
                put("addedAt", w.added_at); put("order", w.sort_order)
            }
            val entry = HouseholdRecord(active, "watchlist", w.media_id, w.added_at, data = data)
            live[entry.key] = entry
        }
        NAVIGATION_KEYS.forEach { key ->
            val value = queries.readPreferenceSafely(person, key)
            if (value != null) {
                val entry = HouseholdRecord(active, "navigation", key, now, data = buildJsonObject { put("value", value) })
                live[entry.key] = entry
            }
        }
        for ((key, entry) in live) {
            val old = records[key]
            if (old == null || old.deleted || old.data != entry.data) {
                records[key] = entry.copy(changedAt = if (old == null) entry.changedAt
                    else maxOf(entry.changedAt, now, old.changedAt + 1))
                pending += key
            }
        }
        for ((key, old) in records.toMap()) {
            val inCapturedScope = old.kind == "profile" || old.profile == active
            if (inCapturedScope && !old.deleted && key !in live && (old.kind != "profile" || old.profile !in profileIds)) {
                records[key] = old.copy(changedAt = maxOf(now, old.changedAt + 1), deleted = true, data = JsonObject(emptyMap()))
                pending += key
            }
        }
        return HouseholdLedger(records, pending)
    }

    suspend fun exportLibraryBackup(expectedAccount: String): String = withContext(ioDispatcher) { mutex.withLock {
        val account = userIds.currentUserIdOrNull() ?: error("Sign in before backing up.")
        check(account.equals(expectedAccount, ignoreCase = true)) { "Account changed. Try again." }
        var captured = HouseholdLedger()
        val profiles = queries.getAllProfiles(account).executeAsList()
        profiles.forEach { captured = captureLocal(account, captured, it.id) }
        val preferences = profiles.flatMap { profile ->
            queries.getPreferenceKeys(profileDataUserId(account, profile.id)).executeAsList()
                .filter { it in com.torve.data.sync.BACKUP_PREFERENCE_KEYS }
                .mapNotNull { key ->
                    val value = queries.readPreferenceSafely(profileDataUserId(account, profile.id), key)
                    value?.takeIf { com.torve.data.sync.safeBackupPreference(key, it) }
                        ?.let { LibraryBackupPreference(profile.id, key, it) }
                }
        }
        check(account == userIds.currentUserIdOrNull()) { "Account changed. Try again." }
        json.encodeToString(LibraryBackup(exportedAt = Clock.System.now().toEpochMilliseconds(),
            records = captured.records.values.mapNotNull { it.forBackup() }, preferences = preferences).validate())
    } }

    /** Explicit restore supersedes old sync records, and keeps credentials, PINs and sources local. */
    suspend fun restoreLibraryBackup(raw: String, expectedAccount: String): Unit = withContext(ioDispatcher) { mutex.withLock {
        require(raw.length <= 2_000_000) { "Backup is too large." }
        val backup = Json { ignoreUnknownKeys = false }.decodeFromString<LibraryBackup>(raw).validate()
        val account = userIds.currentUserIdOrNull() ?: error("Sign in before restoring.")
        check(account.equals(expectedAccount, ignoreCase = true)) { "Account changed. Try again." }
        val token = auth.getValidAccessTokenForUser(account) ?: error("Sign in again before restoring.")
        check(account == userIds.currentUserIdOrNull()) { "Account changed. Try again." }
        // A backup never carries PINs. A missing protected profile must recover its
        // current account PIN before any mutation, rather than becoming inaccessible.
        val recoveredPins = mutableMapOf<String, String>()
        for (entry in backup.records.filter { it.kind == "profile" }) {
            if (entry.data["protected"]?.jsonPrimitive?.booleanOrNull == true &&
                queries.getProfile(account, entry.profile).executeAsOneOrNull() == null) {
                recoveredPins[entry.profile] = api.getIntegrationCredentials(token, "HOUSEHOLD_PIN:${entry.profile}")
                    ?.get("pin")?.takeIf { it.isNotBlank() && it != REMOTE_PIN_REQUIRED }
                    ?: error("BACKUP_PROFILE_PIN_UNAVAILABLE")
            }
        }
        check(account == userIds.currentUserIdOrNull()) { "Account changed. Try again." }
        val remotePrefs = backup.preferences.filter { com.torve.data.account.AccountSettingsSyncPolicy.isSharedKey(it.key) }
            .flatMap { pref -> com.torve.data.account.AccountSettingsSyncPolicy.outgoingRemoteSettings(
                if (pref.profile == "default") pref.key else "profilepref_v1:${pref.profile}:${pref.key}", pref.value).entries }
            .associate { it.key to it.value }
        if (remotePrefs.isNotEmpty()) api.patchAccountSettings(token, remotePrefs)
        check(account == userIds.currentUserIdOrNull()) { "Account changed. Nothing was restored on this device." }
        var ledger = readLedger(account)
        queries.getAllProfiles(account).executeAsList().forEach { ledger = captureLocal(account, ledger, it.id) }
        val records = ledger.records.toMutableMap()
        val pending = ledger.pending.toMutableSet()
        val profiles = backup.records.filter { it.kind == "profile" }.map { it.profile }.toSet()
        val savedKeys = backup.records.map { it.key }.toSet()
        val now = Clock.System.now().toEpochMilliseconds()
        val restoreRecords = backup.records.map { entry ->
            entry.copy(changedAt = maxOf(now, (records[entry.key]?.changedAt ?: 0) + 1))
        } + records.values.filter { it.profile in profiles && it.kind != "profile" && !it.deleted && it.key !in savedKeys }
            .map { it.copy(changedAt = maxOf(now, it.changedAt + 1), deleted = true, data = JsonObject(emptyMap())) }
        database.transaction {
            // Preserve other profiles added since the snapshot and every existing connection.
            restoreRecords.sortedBy { if (it.kind == "profile") 0 else 1 }.forEach { entry ->
                applyBackupRecord(account, entry, recoveredPins)
                records[entry.key] = entry
                pending += entry.key
            }
            for (pref in backup.preferences) queries.setPreference(profileDataUserId(account, pref.profile), pref.key, pref.value)
            persistLedger(account, HouseholdLedger(records, pending))
        }
        notifier.notifyRefresh(now)
    } }

    private fun applyBackupRecord(account: String, entry: HouseholdRecord, recoveredPins: Map<String, String>) {
        val person = profileDataUserId(account, entry.profile)
        val d = entry.data
        fun s(key: String) = d[key]?.jsonPrimitive?.contentOrNull
        fun n(key: String) = d[key]?.jsonPrimitive?.longOrNull
        when (entry.kind) {
            "profile" -> {
                val existing = queries.getProfile(account, entry.profile).executeAsOneOrNull()
                val protected = d["protected"]?.jsonPrimitive?.booleanOrNull == true
                val pin = if (existing != null) existing.pin else recoveredPins[entry.profile].takeIf { protected }
                queries.insertProfile(account, entry.profile, s("name") ?: "Profile", n("avatar") ?: 0,
                    existing?.is_active ?: if (entry.profile == "default") 1 else 0, pin,
                    if (!existing?.pin.isNullOrBlank()) existing?.max_content_rating else s("rating"), n("createdAt") ?: 0)
            }
            "progress" -> if (entry.deleted) queries.deleteProgressByMediaId(person, entry.id) else
                queries.upsertProgress(person, entry.id, s("mediaType") ?: "movie", s("title") ?: "", null, null,
                    n("position") ?: 0, n("duration") ?: 0, n("season"), n("episode"), s("show"), n("updatedAt") ?: 0)
            "history" -> if (entry.deleted) queries.deleteHistory(person, entry.id) else
                queries.insertHistory(person, entry.id, s("mediaId") ?: error("Missing media ID"), s("mediaType") ?: "movie",
                    s("title") ?: "", null, null, n("watchedAt") ?: 0, n("duration") ?: 0, n("season"), n("episode"), s("show"))
            "watchlist" -> if (entry.deleted) queries.removeFromWatchlist(person, entry.id) else
                queries.insertWatchlistItem(person, entry.id, s("mediaType") ?: "movie", n("tmdb") ?: 0, s("imdb"), s("title") ?: "",
                    null, null, d["rating"]?.jsonPrimitive?.doubleOrNull, n("year"), s("genres"), n("addedAt") ?: 0, n("order") ?: 0)
        }
    }

    private suspend fun flushPins(account: String, token: String) {
        val queued = queries.getPreference(account, PIN_QUEUE).executeAsOneOrNull().orEmpty().split('|').filter { it.isNotBlank() }
        val sentRevisions = mutableMapOf<String, String?>()
        for (profile in queued) {
            val revision = queries.getPreference(account, "household_profile_pin_revision_$profile").executeAsOneOrNull()
            val pin = queries.getProfile(account, profile).executeAsOneOrNull()?.pin
            val type = "HOUSEHOLD_PIN:$profile"
            if (pin.isNullOrBlank()) check(api.deleteIntegration(token, type)) { "PIN removal sync failed" }
            else check(api.saveIntegration(token, type, com.torve.data.account.SaveIntegrationRequest(
                integrationType = type, storageMode = "account", credentials = mapOf("pin" to pin),
                displayIdentifier = "Profile PIN", config = mapOf("torve_profile_id" to profile),
            ))) { "PIN sync failed" }
            sentRevisions[profile] = revision
        }
        val remaining = queries.getPreference(account, PIN_QUEUE).executeAsOneOrNull().orEmpty().split('|').filter { it.isNotBlank() }
            .filter { profile -> profile !in sentRevisions || sentRevisions[profile] != queries.getPreference(account, "household_profile_pin_revision_$profile").executeAsOneOrNull() }
        if (remaining.isEmpty()) queries.deletePreference(account, PIN_QUEUE)
        else queries.setPreference(account, PIN_QUEUE, remaining.joinToString("|"))
    }

    private suspend fun applyRemote(account: String, entry: HouseholdRecord, token: String) {
        val person = profileDataUserId(account, entry.profile)
        val d = entry.data
        fun s(key: String) = d[key]?.jsonPrimitive?.contentOrNull
        fun n(key: String) = d[key]?.jsonPrimitive?.longOrNull
        when (entry.kind) {
            "navigation" -> if (entry.id in NAVIGATION_KEYS) {
                if (entry.deleted) queries.deletePreference(person, entry.id)
                else s("value")?.let { queries.setPreference(person, entry.id, it) }
            }
            "profile" -> {
                if (entry.deleted) {
                    if (entry.profile != "default") queries.deleteProfile(account, entry.profile)
                    return
                }
                val existing = queries.getProfile(account, entry.profile).executeAsOneOrNull()
                val protected = d["protected"]?.jsonPrimitive?.booleanOrNull == true
                val pin = if (protected) runCatching { api.getIntegrationCredentials(token, "HOUSEHOLD_PIN:${entry.profile}")?.get("pin") }.getOrNull()
                    ?: REMOTE_PIN_REQUIRED else null
                if (account != userIds.currentUserIdOrNull()) return
                queries.insertProfile(account, entry.profile, s("name") ?: "Profile", n("avatar") ?: 0,
                    existing?.is_active ?: if (entry.profile == "default") 1L else 0L, pin, s("rating"), n("createdAt") ?: 0)
                queries.setPreference(account, "household_profile_pin_revision_${entry.profile}", (n("pinRevision") ?: 0L).toString())
            }
            "progress" -> if (entry.deleted) queries.deleteProgressByMediaId(person, entry.id) else
                queries.upsertProgress(person, entry.id, s("mediaType") ?: "movie", s("title") ?: "", s("poster"), s("backdrop"),
                    n("position") ?: 0, n("duration") ?: 0, n("season"), n("episode"), s("show"), n("updatedAt") ?: entry.changedAt)
            "history" -> if (entry.deleted) queries.deleteHistory(person, entry.id) else
                queries.insertHistory(person, entry.id, s("mediaId") ?: return, s("mediaType") ?: "movie", s("title") ?: "",
                    s("poster"), s("backdrop"), n("watchedAt") ?: 0, n("duration") ?: 0, n("season"), n("episode"), s("show"))
            "watchlist" -> if (entry.deleted) queries.removeFromWatchlist(person, entry.id) else
                queries.insertWatchlistItem(person, entry.id, s("mediaType") ?: "movie", n("tmdb") ?: return, s("imdb"),
                    s("title") ?: "", s("poster"), s("backdrop"), d["rating"]?.jsonPrimitive?.doubleOrNull, n("year"),
                    s("genres"), n("addedAt") ?: 0, n("order") ?: 0)
        }
    }

    companion object {
        val NAVIGATION_KEYS = setOf("custom_sections", "search_saved_lists_v1", "search_home_lists_v1", "web_replays_v1_settings")
        const val LEDGER = "household_sync_ledger_v1"
        private const val PIN_QUEUE = "household_sync_pin_queue_v1"
        const val REMOTE_PIN_REQUIRED = "__remote_pin_required__"
    }
}

internal fun validRecord(record: HouseholdRecord): Boolean =
    record.profile.matches(Regex("[A-Za-z0-9_-]{1,64}")) && record.kind in setOf("profile", "progress", "history", "watchlist", "navigation") &&
        record.id.isNotBlank() && record.id.length <= 300 && record.changedAt >= 0
