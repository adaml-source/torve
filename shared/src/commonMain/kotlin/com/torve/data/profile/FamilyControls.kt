package com.torve.data.profile

import com.torve.data.auth.UserIdProvider
import com.torve.domain.model.*
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class FamilyAccessState(
    val loaded: Boolean = false,
    val configuration: FamilyConfiguration = FamilyConfiguration(),
    val activeProfileId: String = "default",
    val parentAuthorized: Boolean = false,
    val adultUnlocked: Boolean = false,
    val lockedOutUntil: Long = 0,
    val error: String? = null,
    val sessionVersion: Long = 0,
    val syncedAt: Long? = null,
    val syncError: String? = null,
    val profileContentRating: ContentRating? = null,
) {
    val effectiveKidsRules: KidsProfileRules? get() {
        // A child rating ceiling requires positive age evidence, including on
        // devices restoring older rules that allowed unclassified titles.
        val cloud = configuration.kids[activeProfileId].takeIf { configuration.enabled }?.copy(hideUnrated = true)
        val limit = when (profileContentRating) {
            ContentRating.G -> 0; ContentRating.PG -> 8; ContentRating.PG_13 -> 13
            ContentRating.R -> 17; ContentRating.NC_17, null -> return cloud
        }
        return (cloud ?: KidsProfileRules(allowedSources = setOf("catalogue"))).copy(
            maximumAge = cloud?.maximumAge?.let { minOf(it, limit) } ?: limit,
            ratingCountry = "US", hideUnrated = true)
    }
    val contentRestricted: Boolean get() = configuration.enabled || (profileContentRating != null && profileContentRating != ContentRating.NC_17)
    val isKids: Boolean get() = effectiveKidsRules != null
    val needsUnlock: Boolean get() = !loaded || (configuration.enabled && !isKids && !adultUnlocked)
}

@Serializable
private data class CertificateRecord(val certificates: List<AgeCertification>, val checkedAt: Long, val adult: Boolean? = null, val sources: Set<String> = emptySet(), val canonicalTmdbId: Int? = null)

/** Server-owned policy with a device cache. Parent grants never enter backups or ordinary preferences. */
class FamilyControls(
    private val settings: DeviceLocalSettingsRepository,
    private val secure: SecureStorage,
    users: UserIdProvider?,
    private val notifier: SettingsRefreshNotifier,
    private val lookup: suspend (MediaItem) -> List<AgeCertification>,
    private val remote: FamilyPolicyRemote? = null,
    private val verifyAccount: suspend (String, String) -> String? = { _, _ -> null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val accountId: () -> String = { users?.currentUserId() ?: UserIdProvider.SIGNED_OUT_USER_ID },
    private val profileId: () -> String = { users?.currentProfileId() ?: "default" },
    private val profileRatingLimit: () -> ContentRating? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val limiter = Semaphore(3)
    private val certificateLocks = List(64) { Mutex() }
    private var certificatesDirty = false
    private val certificatesState = MutableStateFlow<Map<String, CertificateRecord>>(emptyMap())
    private val canonicalIdentities = MutableStateFlow<Map<String, Int>>(emptyMap())
    internal fun rememberCanonicalIdentity(item: MediaItem, tmdbId: Int) {
        if (tmdbId > 0) canonicalIdentities.update { it + FamilyPolicy.titleKeys(item).associateWith { tmdbId } }
    }
    internal suspend fun canonicalBlockKey(item: MediaItem): String {
        certify(item)
        val id = FamilyPolicy.titleKeys(item).firstNotNullOfOrNull { certificates[it]?.canonicalTmdbId }
            ?: item.tmdbId ?: item.id.toIntOrNull()
        return if (id != null) "${item.type.name}:$id" else FamilyPolicy.titleKeys(item).first()
    }
    private var certificates: Map<String, CertificateRecord>
        get() = certificatesState.value
        set(value) { certificatesState.value = value }
    private data class PlaybackGrant(val session: String, val source: String, val item: MediaItem?, val externalKey: String? = null)
    private val playbackGrantsState = MutableStateFlow<Map<String, PlaybackGrant>>(emptyMap())
    private val playbackGrants: Map<String, PlaybackGrant> get() = playbackGrantsState.value
    private val extensionSourcesState = MutableStateFlow<Map<String, String>>(emptyMap())
    private val extensionSources: Map<String, String> get() = extensionSourcesState.value
    private val externalItemsState = MutableStateFlow<Map<String, Pair<MediaItem, String>>>(emptyMap())
    private val externalItems: Map<String, Pair<MediaItem, String>> get() = externalItemsState.value
    private val verificationJobs = mutableMapOf<String, Job>()
    private var refreshJob: Job? = null
    private var managementUntil = 0L
    private var unlockedProfile: String? = null
    private var backgroundAt: Long? = null
    private val _state = MutableStateFlow(FamilyAccessState())
    val state: StateFlow<FamilyAccessState> = _state.asStateFlow()

    private var loadedAccount: String? = null
    private var cloudRevision = 0L
    private var cloudGrant: String? = null
    private var cloudGrantExpires = 0L
    private var foregroundActive = true
    private fun cloudAccount(): Boolean = remote != null && accountId() != UserIdProvider.SIGNED_OUT_USER_ID
    fun parentGrant(): String? = cloudGrant.takeIf { loadedAccount == accountId() && now() < cloudGrantExpires }

    init {
        scope.launch { initialize() }
        if (remote != null) scope.launch {
            while (isActive) {
                delay(15_000)
                if (foregroundActive) refreshRemote()
            }
        }
    }

    suspend fun initialize() = mutex.withLock {
        val owner = accountId()
        if (_state.value.loaded && loadedAccount == owner) return@withLock
        loadedAccount = owner
        cloudGrant = null; cloudGrantExpires = 0; managementUntil = 0; unlockedProfile = null
        _state.update { it.copy(loaded = false) }
        try {
            val raw = settings.getString(CONFIG)
            val config = raw?.let { json.decodeFromString<FamilyConfiguration>(it) } ?: FamilyConfiguration()
            certificates = runCatching { settings.getString(CERTIFICATES)?.let { json.decodeFromString<Map<String, CertificateRecord>>(it) } }.getOrNull().orEmpty()
            if (cloudAccount()) {
                val cache = settings.getString("family_device_cloud_$owner")?.let { json.decodeFromString<FamilyPolicySnapshot>(it) }
                try {
                    val snapshot = withTimeout(8_000) { fetchRemoteWithMigration(owner) }
                    check(accountId() == owner) { "Account changed. Try again." }
                    applyRemote(snapshot, owner)
                    secure.remove(PIN)
                } catch (e: TimeoutCancellationException) {
                    val safe = cache?.configuration?.takeIf { it.enabled && it.accountId == owner } ?: config.takeIf { it.enabled && it.accountId == owner } ?: FamilyConfiguration(enabled = true, accountId = owner)
                    _state.value = FamilyAccessState(loaded = true, configuration = safe, syncError = "Connect to Torve to verify Family settings."); updateSession()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    val safe = cache?.configuration?.takeIf { it.enabled && it.accountId == owner }
                        ?: config.takeIf { it.enabled && it.accountId == owner } ?: FamilyConfiguration(enabled = true, accountId = owner)
                    _state.value = FamilyAccessState(loaded = true, configuration = safe, syncError = "Connect to Torve to verify Family settings. Adult profiles stay locked.")
                    updateSession()
                }
            } else {
                _state.value = FamilyAccessState(loaded = true, configuration = config)
                updateSession()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // A damaged policy must not silently turn protections off.
            _state.value = FamilyAccessState(loaded = true, configuration = FamilyConfiguration(enabled = true), error = "Family settings could not be read. Restore the saved settings before continuing.")
        }
    }

    private suspend fun fetchRemoteWithMigration(owner: String): FamilyPolicySnapshot {
        val snapshot = remote!!.fetch()
        val legacy = settings.getString(CONFIG)?.let { json.decodeFromString<FamilyConfiguration>(it) }
        if (snapshot.configuration.version == 0L && legacy?.enabled == true && legacy.accountId == owner) {
            val pin = secure.getString(PIN) ?: error("Unlock the original device to sync Family settings.")
            check(owner == accountId()) { "Account changed. Try again." }
            val result = remote.update(legacy.copy(version = 0), 0, pin, null)
            return FamilyPolicySnapshot(result.configuration, result.pinRevision)
        }
        return snapshot
    }

    suspend fun refreshRemote() {
        if (!cloudAccount()) { if (loadedAccount != accountId()) initialize(); return }
        if (loadedAccount != accountId() || !state.value.loaded) { initialize(); return }
        mutex.withLock {
            val owner = accountId()
            try {
                val snapshot = withTimeout(8_000) { fetchRemoteWithMigration(owner) }
                if (owner == accountId()) { applyRemote(snapshot, owner); secure.remove(PIN) }
            } catch (_: TimeoutCancellationException) { _state.update { it.copy(syncError = "Changes could not be checked. Saved restrictions remain active.") } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(syncError = "Changes could not be checked. Saved restrictions remain active.") } }
        }
    }

    private suspend fun applyRemote(snapshot: FamilyPolicySnapshot, owner: String) {
        check(snapshot.configuration.accountId == owner) { "Account mismatch" }
        val changed = state.value.configuration != snapshot.configuration || cloudRevision != snapshot.pinRevision
        if (changed) {
            cloudGrant = null; cloudGrantExpires = 0; managementUntil = 0; unlockedProfile = null
            playbackGrantsState.value = emptyMap(); externalItemsState.value = emptyMap(); extensionSourcesState.value = emptyMap()
        }
        cloudRevision = snapshot.pinRevision
        // Source approval carries the same explicit catalogue choice to a receiving device.
        withContext(com.torve.data.auth.PersonalDataScope(owner, profileId())) {
            snapshot.configuration.kids.forEach { (id, rules) ->
                settings.setString("profilepref_v1:$id:metadata_tmdb_catalogue_enabled", ("catalogue" in rules.allowedSources).toString())
            }
        }
        settings.setString("family_device_cloud_$owner", json.encodeToString(snapshot))
        settings.setString(CONFIG, json.encodeToString(snapshot.configuration))
        _state.update { it.copy(loaded = true, configuration = snapshot.configuration, syncedAt = now(), syncError = null,
            sessionVersion = it.sessionVersion + if (changed) 1 else 0) }
        updateSession()
        if (changed) changed()
    }

    private suspend fun acceptProof(proof: FamilyParentProof) {
        val owner = accountId()
        applyRemote(FamilyPolicySnapshot(proof.configuration, proof.pinRevision), owner)
        cloudGrant = proof.grant; cloudGrantExpires = proof.expiresAt
        managementUntil = minOf(now() + MANAGEMENT_MS, proof.expiresAt)
        unlockedProfile = profileId().takeUnless { it in state.value.configuration.kids }
        updateSession()
    }

    private suspend fun saveRemote(configuration: FamilyConfiguration, pin: String? = null) {
        val owner = accountId()
        try {
            val proof = remote!!.update(configuration, state.value.configuration.version, pin, parentGrant())
            check(owner == accountId()) { "Account changed. Try again." }
            acceptProof(proof)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            runCatching { remote!!.fetch() }.getOrNull()?.takeIf { owner == accountId() }?.let { applyRemote(it, owner) }
            throw e
        }
    }

    suspend fun signInOwner(email: String, password: String) {
        val expected = state.value.configuration.accountId
        check(expected.isNotBlank()) { "Sign in to your Torve account." }
        check(verifyAccount(email.trim(), password) == expected) { "Sign in to the account that enabled Family controls." }
        initialize(); refreshRemote()
    }
    suspend fun requestEmailRecovery(): String {
        check(cloudAccount()) { "Sign in to the account that enabled Family controls." }
        return remote!!.requestRecovery().destination
    }
    suspend fun recoverWithEmailCode(code: String, pin: String, confirmation: String) {
        require(pin.matches(Regex("[0-9]{4,6}")) && pin == confirmation) { "Enter the same 4â€“6 digit PIN twice." }
        check(cloudAccount()) { "Sign in to recover the parent PIN." }
        val owner = accountId(); val proof = remote!!.confirmRecovery(code, pin)
        check(owner == accountId()) { "Account changed. Try again." }
        acceptProof(proof); unlockedProfile = profileId().takeUnless { it in state.value.configuration.kids }; updateSession()
    }
    suspend fun connectionChoices(profile: String): List<String> {
        requireParent()
        return remote?.connections(profile, parentGrant() ?: if (!state.value.configuration.enabled) "" else error("Enter the parent PIN to copy connections.")) ?: emptyList()
    }
    suspend fun copyConnections(source: String, target: String, services: List<String>, replace: Boolean) {
        requireParent()
        remote?.copyConnections(source, target, services, replace, parentGrant() ?: if (!state.value.configuration.enabled) "" else error("Enter the parent PIN to copy connections."))
            ?: error("Sign in to copy account connections.")
        changed()
    }

    fun updateSession() {
        if (loadedAccount != null && loadedAccount != accountId()) {
            cloudGrant = null; managementUntil = 0; unlockedProfile = null
            _state.update { it.copy(loaded = false, adultUnlocked = false, parentAuthorized = false) }
            scope.launch { initialize() }
            return
        }
        val profile = profileId()
        val limit = profileRatingLimit()
        val boundaryChanged = _state.value.activeProfileId != profile || _state.value.profileContentRating != limit
        if (boundaryChanged) { playbackGrantsState.value = emptyMap(); externalItemsState.value = emptyMap() }
        _state.update { s ->
            val ownerMatches = !s.configuration.enabled || s.configuration.accountId == accountId()
            s.copy(activeProfileId = profile, profileContentRating = limit,
                sessionVersion = s.sessionVersion + if (boundaryChanged) 1 else 0, parentAuthorized = now() < managementUntil && (!cloudAccount() || parentGrant() != null),
                adultUnlocked = ownerMatches && unlockedProfile == profileId())
        }
    }

    fun canManage(): Boolean = _state.value.loaded && loadedAccount == accountId() && (!_state.value.configuration.enabled || (now() < managementUntil && (!cloudAccount() || parentGrant() != null)))

    fun requireParent() { check(canManage()) { "Enter the parent PIN to change these settings." } }

    suspend fun enable(pin: String, confirmation: String) {
        initialize()
        check(!_state.value.configuration.enabled) { "Family controls are already enabled." }
        require(pin.matches(Regex("[0-9]{4,6}")) && pin == confirmation) { "Enter the same 4â€“6 digit PIN twice." }
        if (cloudAccount()) {
            saveRemote(FamilyConfiguration(enabled = true, accountId = accountId(), version = state.value.configuration.version), pin)
            unlockedProfile = profileId(); updateSession(); return
        }
        secure.putString(PIN, pin)
        certificates = emptyMap(); canonicalIdentities.value = emptyMap(); settings.remove(CERTIFICATES); playbackGrantsState.value = emptyMap(); externalItemsState.value = emptyMap(); extensionSourcesState.value = emptyMap()
        val config = FamilyConfiguration(enabled = true, accountId = accountId(), version = maxOf(now(), _state.value.configuration.version + 1))
        settings.setString(CONFIG, json.encodeToString(config))
        managementUntil = now() + MANAGEMENT_MS
        unlockedProfile = profileId()
        _state.value = _state.value.copy(configuration = config, sessionVersion = _state.value.sessionVersion + 1)
        updateSession(); changed()
    }

    suspend fun authorize(pin: String) = mutex.withLock {
        val s = _state.value
        check(s.loaded && s.configuration.enabled) { "Family controls are not enabled." }
        if (cloudAccount()) {
            val owner = accountId(); val proof = remote!!.authorize(pin)
            check(owner == accountId()) { "Account changed. Try again." }
            acceptProof(proof); return@withLock
        }
        val locked = settings.getString(LOCK_UNTIL)?.toLongOrNull() ?: 0L
        check(now() >= locked) { "Too many attempts. Wait a minute before trying again." }
        val expected = secure.getString(PIN)
        if (expected == null || expected != pin) {
            val attempts = (settings.getString(ATTEMPTS)?.toIntOrNull() ?: 0) + 1
            if (attempts >= 5) {
                settings.setString(LOCK_UNTIL, (now() + 60_000).toString()); settings.setString(ATTEMPTS, "0")
                _state.update { it.copy(lockedOutUntil = now() + 60_000) }
            } else settings.setString(ATTEMPTS, attempts.toString())
            error("Incorrect parent PIN.")
        }
        settings.remove(ATTEMPTS); settings.remove(LOCK_UNTIL)
        managementUntil = now() + MANAGEMENT_MS
        _state.update { it.copy(parentAuthorized = true, lockedOutUntil = 0, error = null) }
    }

    suspend fun recoverPin(email: String, password: String, pin: String, confirmation: String) {
        initialize()
        require(pin.matches(Regex("[0-9]{4,6}")) && pin == confirmation) { "Enter the same 4â€“6 digit PIN twice." }
        check(!cloudAccount()) { "Use the code sent to the account owner's email to reset the PIN." }
        val expected = _state.value.configuration.accountId
        check(_state.value.configuration.enabled && expected != UserIdProvider.SIGNED_OUT_USER_ID) { "Account recovery is unavailable for this setup." }
        val verified = verifyAccount(email.trim(), password)
        check(verified == expected && accountId() == expected) { "Verify the Torve account that enabled Family controls." }
        secure.putString(PIN, pin)
        settings.remove(ATTEMPTS); settings.remove(LOCK_UNTIL)
        managementUntil = now() + MANAGEMENT_MS
        unlockedProfile = profileId().takeUnless { it in state.value.configuration.kids }
        updateSession(); changed()
    }
    fun needsAccountSignIn(): Boolean = state.value.configuration.enabled && state.value.configuration.accountId != accountId()

    suspend fun changePin(pin: String, confirmation: String) {
        requireParent()
        require(pin.matches(Regex("[0-9]{4,6}")) && pin == confirmation) { "Enter the same 4â€“6 digit PIN twice." }
        if (cloudAccount()) { saveRemote(state.value.configuration, pin); return }
        secure.putString(PIN, pin)
    }

    suspend fun disable() {
        requireParent()
        if (cloudAccount()) { saveRemote(state.value.configuration.copy(enabled = false, kids = emptyMap())); lock(); return }
        val next = FamilyConfiguration(version = maxOf(now(), _state.value.configuration.version + 1))
        settings.setString(CONFIG, json.encodeToString(next))
        secure.remove(PIN)
        playbackGrantsState.value = emptyMap(); externalItemsState.value = emptyMap(); extensionSourcesState.value = emptyMap()
        managementUntil = 0; unlockedProfile = null
        _state.value = FamilyAccessState(loaded = true, configuration = next, sessionVersion = _state.value.sessionVersion + 1)
        changed()
    }

    suspend fun saveProfile(id: String, kids: Boolean, rules: KidsProfileRules) {
        requireParent()
        require(rules.maximumAge in 0..17 && rules.ratingCountry in setOf("US", "DE", "GB", "FR", "AU")) { "Choose a supported age limit and rating country." }
        val current = _state.value.configuration
        val next = current.copy(kids = if (kids) current.kids + (id to rules) else current.kids - id, version = maxOf(now(), _state.value.configuration.version + 1))
        if (cloudAccount()) {
            saveRemote(next)
            if (kids) settings.setString("profilepref_v1:$id:metadata_tmdb_catalogue_enabled", ("catalogue" in rules.allowedSources).toString())
            return
        }
        settings.setString(CONFIG, json.encodeToString(next))
        if (kids) settings.setString("profilepref_v1:$id:metadata_tmdb_catalogue_enabled", ("catalogue" in rules.allowedSources).toString())
        _state.update { it.copy(configuration = next, sessionVersion = _state.value.sessionVersion + 1) }
        updateSession(); changed()
    }

    fun authorizeProfileSwitch(id: String) {
        val config = _state.value.configuration
        if (!config.enabled) return
        check(_state.value.loaded && config.accountId == accountId()) { "Family controls are locked." }
        if (id !in config.kids) { requireParent(); unlockedProfile = id }
        else { unlockedProfile = null; managementUntil = 0; cloudGrant = null; cloudGrantExpires = 0 }
        _state.update { it.copy(sessionVersion = _state.value.sessionVersion + 1) }
    }

    fun background() { backgroundAt = now(); foregroundActive = false }
    fun foreground() {
        if (backgroundAt?.let { now() - it >= 60_000 } == true) lock()
        backgroundAt = null; foregroundActive = true; updateSession(); scope.launch { refreshRemote() }
    }
    fun lock() {
        managementUntil = 0; unlockedProfile = null; cloudGrant = null; cloudGrantExpires = 0
        _state.update { it.copy(parentAuthorized = false, adultUnlocked = false, sessionVersion = _state.value.sessionVersion + 1) }
        scope.launch { changed() }
    }

    fun allowsSource(key: String): Boolean {
        val s = _state.value
        if (!s.loaded) return false
        if (!s.configuration.enabled) return true
        if (s.configuration.accountId != accountId()) return false
        val rules = s.effectiveKidsRules
        return if (rules != null) key in rules.allowedSources else unlockedProfile == profileId()
    }

    fun allows(item: MediaItem, source: String? = item.familySourceKey): Boolean {
        val s = _state.value
        if (!s.loaded) return false
        if (!s.contentRestricted) return true
        if (s.configuration.enabled && s.configuration.accountId != accountId()) return false
        val rules = s.effectiveKidsRules ?: return unlockedProfile == profileId()
        val cached = FamilyPolicy.titleKeys(item).firstNotNullOfOrNull { certificates[it] }
        val origin = source ?: cached?.sources?.firstOrNull { it in rules.allowedSources } ?: "catalogue"
        if (!allowsSource(origin)) return false
        val canonicalId = cached?.canonicalTmdbId ?: item.tmdbId ?: item.id.toIntOrNull()
        val trustedIdentity = item.copy(tmdbId = canonicalId, adult = item.adult ?: cached?.adult)
        if (FamilyPolicy.titleKeys(trustedIdentity).any { it in rules.blockedTitles }) return false
        if (cached != null && rules.blockedTitles.isNotEmpty() && canonicalId == null) return false
        if (cached != null && now() - cached.checkedAt < if (cached.certificates.isEmpty()) 60_000 else CERTIFICATE_TTL) return FamilyPolicy.allows(trustedIdentity, rules, cached.certificates)
        requestCertification(item)
        return !rules.hideUnrated && (rules.blockedTitles.isEmpty() || canonicalId != null) && FamilyPolicy.allows(trustedIdentity, rules, emptyList())
    }

    suspend fun prepare(items: List<MediaItem>, source: String = "catalogue"): List<MediaItem> = kotlinx.coroutines.withContext(com.torve.util.ioDispatcher) {
        initialize()
        val stamp = sessionKey()
        if (!_state.value.contentRestricted) return@withContext items
        if (!allowsSource(source)) return@withContext emptyList()
        if (!_state.value.isKids) return@withContext items
        val prepared = coroutineScope { items.map { item -> async { certifyItem(item.copy(familySourceKey = item.familySourceKey ?: source), persist = false) } }.awaitAll() }
        persistCertificateCache()
        if (stamp != sessionKey()) return@withContext emptyList()
        return@withContext prepared.filter { allows(it) }
    }

    suspend fun certify(item: MediaItem): MediaItem = certifyItem(item, persist = true)

    private suspend fun persistCertificateCache() = mutex.withLock {
        if (certificatesDirty) {
            settings.setString(CERTIFICATES, json.encodeToString(certificates))
            certificatesDirty = false
        }
    }

    private suspend fun certifyItem(item: MediaItem, persist: Boolean): MediaItem {
        val keys = FamilyPolicy.titleKeys(item)
        val record = certificateLocks[(keys.first().hashCode() and Int.MAX_VALUE) % certificateLocks.size].withLock {
            val cached = keys.firstNotNullOfOrNull { certificates[it] }
            cached?.takeIf { now() - it.checkedAt < if (it.certificates.isEmpty()) 60_000 else CERTIFICATE_TTL } ?: limiter.withPermit {
                CertificateRecord(try { withTimeoutOrNull(8_000) { lookup(item) } ?: emptyList() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }, now(), item.adult, setOfNotNull(item.familySourceKey),
                    keys.firstNotNullOfOrNull { canonicalIdentities.value[it] } ?: item.tmdbId ?: item.id.toIntOrNull()).also { result ->
                    mutex.withLock {
                        certificates = (certificates + keys.associateWith { result }).entries.takeLastEntries(6000)
                        certificatesDirty = true
                    }
                }
            }
        }
        val withOrigin = record.copy(sources = record.sources + setOfNotNull(item.familySourceKey))
        if (withOrigin != record) mutex.withLock { certificates = certificates + keys.associateWith { withOrigin }; certificatesDirty = true }
        if (persist) persistCertificateCache()
        return item.copy(ageCertifications = record.certificates, adult = item.adult ?: record.adult)
    }

    private fun requestCertification(item: MediaItem) {
        val key = FamilyPolicy.titleKeys(item).first()
        synchronizedJobs(key, item)
    }
    // Main render calls and verification completions share this small dispatcher-owned registry.
    private fun synchronizedJobs(key: String, item: MediaItem) {
        scope.launch {
            mutex.withLock {
                if (verificationJobs[key]?.isActive == true || verificationJobs.size >= 96) return@launch
                refreshJob?.cancel()
                verificationJobs[key] = scope.launch {
                    try { certify(item) } finally {
                        mutex.withLock {
                            verificationJobs.remove(key)
                            // A batch of certification completions must not repeatedly
                            // cancel the Home request that is still preparing its rows.
                            if (verificationJobs.isEmpty()) {
                                refreshJob?.cancel()
                                refreshJob = scope.launch { delay(250); changed() }
                            }
                        }
                    }
                }
            }
        }
    }
    fun rememberExtension(addon: InstalledAddon) {
        extensionSourcesState.update { it + (addon.manifestUrl.removeSuffix("/manifest.json").trimEnd('/') to "extension:${addon.manifest.id}") }
    }
    fun extensionSource(baseUrl: String): String? = extensionSources[baseUrl.removeSuffix("/manifest.json").trimEnd('/')]
    fun inheritPlayback(from: String, to: String) {
        val grant = playbackGrants[from] ?: return
        if (playbackAllowed(from)) playbackGrantsState.update { it + (to to grant) }
    }

    suspend fun rememberExternal(kind: String, externalId: String, item: MediaItem, source: String): Boolean {
        // Only trusted source adapters call this after reading official metadata.
        val rules = state.value.effectiveKidsRules
        val canonicalItem = if (rules?.blockedTitles?.isNotEmpty() == true && item.tmdbId == null) {
            certify(item)
            item.copy(tmdbId = FamilyPolicy.titleKeys(item).firstNotNullOfOrNull { certificates[it]?.canonicalTmdbId })
        } else item
        externalItemsState.update { it + ("$kind:$externalId" to (canonicalItem to source)) }
        return externalAllowed(kind, externalId)
    }
    fun externalAllowed(kind: String, id: String): Boolean {
        if (!_state.value.contentRestricted) return _state.value.loaded
        if (!state.value.isKids) return allowsSource("$kind:$id")
        val entry = externalItems["$kind:$id"] ?: return false
        val rules = state.value.effectiveKidsRules ?: return false
        if (rules.blockedTitles.isNotEmpty() && entry.first.tmdbId == null) return false
        return allowsSource(entry.second) && FamilyPolicy.allows(entry.first, rules)
    }
    fun inheritExternal(kind: String, parentId: String, childId: String) {
        externalItems["$kind:$parentId"]?.let { pair -> externalItemsState.update { it + ("$kind:$childId" to pair) } }
    }
    fun registerPlayback(url: String, source: String, item: MediaItem? = null) {
        if (!_state.value.contentRestricted || !_state.value.isKids) return
        if (!allowsSource(source) || (item != null && !allows(item, source))) return
        playbackGrantsState.update { grants -> (grants + (url to PlaybackGrant(sessionKey(), source, item))).entries.toList().takeLast(500).associate { it.toPair() } }
    }
    fun registerExternalPlayback(url: String, kind: String, id: String) {
        val pair = externalItems["$kind:$id"] ?: return
        if (externalAllowed(kind, id) && state.value.isKids) {
            playbackGrantsState.update { it + (url to PlaybackGrant(sessionKey(), pair.second, pair.first, "$kind:$id")) }
        }
    }
    fun playbackAllowed(url: String): Boolean {
        if (!_state.value.loaded) return false
        if (!_state.value.contentRestricted) return true
        if (!state.value.isKids) return allowsSource("playback")
        val grant = playbackGrants[url] ?: return false
        return grant.session == sessionKey() && allowsSource(grant.source) && (if (grant.externalKey != null) {
            externalAllowed(grant.externalKey.substringBefore(':'), grant.externalKey.substringAfter(':'))
        } else grant.item == null || allows(grant.item, grant.source))
    }
    suspend fun requireMediaAccess(type: MediaType, id: String, imdb: String? = null, source: String = "catalogue") {
        initialize()
        if (!_state.value.contentRestricted) return
        val item = MediaItem(id = id, imdbId = imdb, type = type, title = "", familySourceKey = source)
        val verified = if (state.value.isKids) certify(item) else item
        check(allows(verified, source)) { "This title is not available in this profile." }
    }

    fun contentSessionKey(): String {
        val config = _state.value.configuration
        val rules = _state.value.effectiveKidsRules
        return "${accountId()}:${profileId()}:${profileRatingLimit()?.name.orEmpty()}:${rules?.hashCode() ?: 0}"
    }
    fun sessionKey(): String = "${accountId()}:${profileId()}:${_state.value.configuration.version}:${_state.value.sessionVersion}"
    private suspend fun changed() { notifier.notifyRefresh(now()) }
    private fun Set<Map.Entry<String, CertificateRecord>>.takeLastEntries(limit: Int) = toList().takeLast(limit).associate { it.key to it.value }
    private companion object {
        const val CONFIG = "family_device_configuration_v1"
        const val PIN = "family_parent_pin_v1"
        const val CERTIFICATES = "family_device_certifications_v1"
        const val ATTEMPTS = "family_device_pin_attempts_v1"
        const val LOCK_UNTIL = "family_device_pin_lock_until_v1"
        const val MANAGEMENT_MS = 5 * 60_000L
        const val CERTIFICATE_TTL = 7 * 24 * 60 * 60_000L
    }
}
