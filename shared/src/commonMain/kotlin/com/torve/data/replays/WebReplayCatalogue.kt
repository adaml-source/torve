package com.torve.data.replays

import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.domain.model.*
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.Koin

/** Retained catalogue, canonical configuration and profile-scoped metadata cache. */
class WebReplayCatalogue internal constructor(
    private val prefs: PreferencesRepository, private val repository: WebReplayRepository,
    private val owner: () -> String, changes: Flow<Unit>,
    private val filter: ContentPolicyFilter = ContentPolicyFilter(),
    private val policy: () -> ContentPolicyState = { ContentPolicyState.unrestricted() },
    private val personalScope: suspend (suspend () -> Unit) -> Unit = { it() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val local: DeviceLocalSettingsRepository? = null,
    private val profiles: suspend () -> List<UserProfile> = { emptyList() },
    private val copyConfiguration: suspend (String, ReplayConfiguration) -> Unit = { _, _ -> error("Sign in to copy websites between profiles.") },
    private val savedConfiguration: () -> Unit = {},
) {
    companion object {
        internal const val SETTINGS = "web_replays_v1_settings"
        internal const val CACHE = "web_replays_v1_cache"
        internal const val HISTORY = "web_replays_v1_history"
        internal fun create(koin: Koin): WebReplayCatalogue {
            val ids = koin.get<UserIdProvider>(); val filter = koin.get<ContentPolicyFilter>()
            val policy = koin.get<ContentPolicyRepository>()
            val household = koin.get<com.torve.data.profile.HouseholdSyncService>()
            val profileRepository = koin.get<com.torve.domain.repository.ProfileRepository>()
            val notifier = koin.get<com.torve.presentation.settings.SettingsRefreshNotifier>()
            val family = koin.get<com.torve.data.profile.FamilyControls>()
            return WebReplayCatalogue(koin.get(), WebReplayRepository(koin.get()), { ids.currentDataUserId() },
                merge(filter.profileOwners().map { Unit }, policy.state.map { Unit }), filter, { policy.state.value },
                personalScope = { block -> ids.withPersonalDataScope { block() } }, local = koin.get(),
                profiles = { if (ids.isSignedIn() && family.canManage()) profileRepository.getAllProfiles().filter { it.id != ids.currentProfileId() } else emptyList() },
                copyConfiguration = { target, config ->
                    family.requireParent()
                    check(ids.isSignedIn() && profileRepository.getAllProfiles().any { it.id == target }) { "Choose a profile on this account." }
                    check(household.refresh(force = true)) { "Connect to your account to copy websites." }
                    household.copyReplayWebsites(target, config)
                }, savedConfiguration = { household.requestRefresh() }).also { catalogue ->
                    catalogue.scope.launch { notifier.events.collect { catalogue.refresh() } }
                }
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = filter.profileState(WebReplayState())
    val state: StateFlow<WebReplayState> = _state.asStateFlow()
    private val writes = Mutex()
    private var configuration = ReplayConfiguration()
    private var loadedOwner: String? = null
    private var events = emptyList<ReplayEvent>()
    private var nextPages = mutableMapOf<String, String>()
    private var savedAt = 0L
    private var ticket = 0L
    private var job: Job? = null
    private var pendingRefreshTicket: Long? = null
    private var activeSignature: Triple<String, ContentPolicyState, String>? = null
    private fun localKey(key: String) = if (owner() == UserIdProvider.SIGNED_OUT_USER_ID) "web_replays_guest_$key" else key
    private suspend fun readLocal(key: String) = if (local != null) local.getString(localKey(key)) else prefs.getString(key)
    private suspend fun writeLocal(key: String, value: String) {
        if (local != null) local.setString(localKey(key), value) else prefs.setString(key, value)
    }
    init { scope.launch { changes.onStart { emit(Unit) }.collect {
        val signature = Triple(owner(), policy(), filter.profileOwnerKey())
        if (job?.isActive != true || activeSignature != signature) refresh()
    } } }

    private suspend fun load(expected: String) {
        filter.awaitProfileBoundary()
        if (owner() != expected) throw CancellationException()
        if (loadedOwner == expected) {
            val incoming = readConfiguration()
            if (owner() != expected) throw CancellationException()
            if (incoming != configuration) { configuration = incoming; savedAt = 0; publish() }
            return
        }
        configuration = ReplayConfiguration(); events = emptyList(); nextPages.clear(); savedAt = 0
        _state.value = WebReplayState(owner = expected)
        configuration = readConfiguration()
        val cache = runCatching { json.decodeFromString<ReplayCache>(readLocal(CACHE).orEmpty()) }.getOrDefault(ReplayCache())
        if (owner() != expected) throw CancellationException()
        events = cache.events.take(400); savedAt = cache.savedAt
        nextPages = cache.nextPages.filter { (id, url) -> configuration.websites.any { it.id == id && WebReplayUrls.sameOrigin(it.url, url) } }.toMutableMap()
        loadedOwner = expected; publish()
    }
    private suspend fun readConfiguration() = runCatching { json.decodeFromString<ReplayConfiguration>(prefs.getString(SETTINGS).orEmpty()) }
            .getOrDefault(ReplayConfiguration()).let { cfg -> cfg.copy(websites = cfg.websites.filter {
                WebReplayUrls.normalize(it.url) != null }.distinctBy { it.id }.take(12)) }
    private fun allowed(event: ReplayEvent): Boolean {
        val current = policy()
        // An age-verified adult's explicitly saved websites are personal library
        // sources, rather than unverified discovery. Replay cards have no movie
        // certification metadata. Family checks and sensitive-title classification
        // still run through the same filter; unverified/minor accounts stay strict.
        val personalSource = current.isSignedIn && current.adultEligible && current.ageBand == ContentAgeBand.ADULT
        return filter.filterItems(current, ContentAccessContext.ADDON_SHELF, listOf(event.mediaItem()),
            if (personalSource) ContentSourceType.LOCAL_LIBRARY else ContentSourceType.ADDON,
            strictUnknown = current.enforcementEnabled && !personalSource).items.isNotEmpty()
    }
    private fun publish(loading: Boolean = false, error: String? = null) {
        val activeIds = configuration.websites.filter { it.enabled }.map { it.id }.toSet()
        val visible = events.filter { it.websiteId in activeIds && allowed(it) }
            .distinctBy { it.id }.sortedByDescending { it.date }.take(400)
        _state.value = WebReplayState(owner(), true, configuration.enabled, configuration.websites,
            visible, loading, nextPages.keys.any { it in activeIds } && visible.size < 400, error)
    }
    fun refresh() {
        val running = job
        val signature = Triple(owner(), policy(), filter.profileOwnerKey())
        if (running?.isActive == true && activeSignature == signature) {
            // Account/settings startup notifications must not repeatedly cancel
            // the same website parse. Re-read configuration once it completes,
            // so a backend website edit still takes effect promptly.
            if (pendingRefreshTicket == ticket) return
            val waitingFor = ticket
            pendingRefreshTicket = waitingFor
            scope.launch {
                running.join()
                if (ticket == waitingFor) {
                    pendingRefreshTicket = null
                    request(force = false)
                }
            }
        } else request(force = false)
    }
    fun reload() = request(force = true)
    fun loadMore() { if (!_state.value.loading && _state.value.hasMore) request(more = true) }
    private fun request(force: Boolean = false, more: Boolean = false) {
        pendingRefreshTicket = null
        job?.cancel(); val current = ++ticket; val expected = owner()
        activeSignature = Triple(expected, policy(), filter.profileOwnerKey())
        job = scope.launch {
            try { personalScope {
                load(expected)
                fun active() = current == ticket && owner() == expected
                if (!active()) return@personalScope
                publish()
                if (!configuration.enabled) return@personalScope
                if (savedAt > 0 && !force && !more && now() - savedAt in 0..300_000) return@personalScope
                val sites = configuration.websites.filter { it.enabled && (!more || nextPages.containsKey(it.id)) }
                if (sites.isEmpty()) return@personalScope
                publish(loading = true)
                val errors = mutableListOf<String>(); val permits = Semaphore(3)
                coroutineScope {
                    sites.map { site -> async { permits.withPermit {
                        try {
                            val page = repository.catalogue(site, if (more) nextPages.getValue(site.id) else site.url)
                            ensureActive()
                            if (!active()) return@withPermit
                            val repeatsPage = more && page.events.all { event -> events.any { it.id == event.id } }
                            if (page.events.isNotEmpty()) events = ((if (more) events else events.filter { it.websiteId != site.id }) + page.events)
                                .distinctBy { it.id }.takeLast(400)
                            if (page.next == null || repeatsPage || more && page.next == site.url) nextPages.remove(site.id) else nextPages[site.id] = page.next
                            if (page.events.isEmpty()) errors += "${site.name}: no replay cards were found on this page."
                        } catch (timeout: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive(); errors += "${site.name}: the website timed out."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { errors += "${site.name}: ${friendly(error)}" }
                        if (active()) publish(loading = true, error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"))
                    } } }.awaitAll()
                }
                if (!active()) return@personalScope
                savedAt = now()
                writeLocal(CACHE, json.encodeToString(ReplayCache(events, savedAt, nextPages)))
                if (active()) publish(error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"))
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (owner() == expected && current == ticket) publish(error = friendly(error)) }
        }
    }
    @Throws(Exception::class) suspend fun configure(enabled: Boolean) = mutate { it.copy(enabled = enabled) }
    @Throws(Exception::class) suspend fun addWebsite(url: String, name: String) {
        val normalized = WebReplayUrls.normalize(url) ?: error("Enter a public HTTP or HTTPS website address.")
        mutate { cfg ->
            check(cfg.websites.size < 12 || cfg.websites.any { it.url == normalized }) { "You can add up to 12 replay websites." }
            val id = WebReplayUrls.identity(normalized)
            cfg.copy(websites = cfg.websites.filterNot { it.id == id } + ReplayWebsite(id,
                name.trim().ifBlank { io.ktor.http.Url(normalized).host }.take(80), normalized))
        }
    }
    @Throws(Exception::class) suspend fun setWebsiteEnabled(id: String, enabled: Boolean) = mutate {
        it.copy(websites = it.websites.map { site -> if (site.id == id) site.copy(enabled = enabled) else site })
    }
    @Throws(Exception::class) suspend fun removeWebsite(id: String) = mutate { it.copy(websites = it.websites.filterNot { site -> site.id == id }) }
    private suspend fun mutate(change: (ReplayConfiguration) -> ReplayConfiguration) = writes.withLock { personalScope {
        val expected = owner(); load(expected)
        val updated = change(configuration)
        job?.cancel(); ++ticket
        prefs.setString(SETTINGS, json.encodeToString(updated))
        savedConfiguration()
        if (owner() != expected) throw CancellationException()
        configuration = updated
        val ids = updated.websites.map { it.id }.toSet()
        events = events.filter { it.websiteId in ids }; nextPages.keys.retainAll(ids)
        savedAt = 0; publish(); reload()
    } }
    @Throws(Exception::class) suspend fun event(id: String): ReplayEvent? {
        var result: ReplayEvent? = null
        personalScope {
            val expected = owner(); load(expected)
            result = events.firstOrNull { it.id == id } ?: runCatching {
                json.decodeFromString<List<ReplayEvent>>(readLocal(HISTORY).orEmpty()).firstOrNull { it.id == id }
            }.getOrNull()
            result = result?.takeIf { e -> configuration.enabled && configuration.websites.any { it.id == e.websiteId && it.enabled } && allowed(e) }
            if (owner() != expected) throw CancellationException()
        }
        return result
    }
    @Throws(Exception::class) suspend fun sources(event: ReplayEvent): List<ReplaySourceOption> {
        check(this.event(event.id) == event) { "This replay is no longer available in the active profile." }
        val expected = owner(); val options = repository.sources(event)
        if (owner() != expected || this.event(event.id) != event) throw CancellationException()
        return options
    }
    @Throws(Exception::class) suspend fun highlights(event: ReplayEvent, game: String?): List<ReplayHighlight> {
        check(this.event(event.id) == event) { "This replay is no longer available in the active profile." }
        val expected = owner()
        val result = repository.highlights(event, game)
        if (owner() != expected || this.event(event.id) != event) throw CancellationException()
        return result
    }
    @Throws(Exception::class) suspend fun resolve(event: ReplayEvent, option: ReplaySourceOption): ResolvedReplay {
        check(this.event(event.id) == event) { "This replay is no longer available in the active profile." }
        val expected = owner(); val result = repository.resolve(option)
        if (owner() != expected || this.event(event.id) != event) throw CancellationException()
        personalScope {
            val history = runCatching { json.decodeFromString<List<ReplayEvent>>(readLocal(HISTORY).orEmpty()) }.getOrDefault(emptyList())
            writeLocal(HISTORY, json.encodeToString((listOf(event) + history.filter { it.id != event.id }).take(100)))
        }
        return result
    }
    /** Try the selected host first, then alternatives, within one bounded wait. */
    @Throws(Exception::class)
    suspend fun resolveAvailable(event: ReplayEvent, preferred: ReplaySourceOption, options: List<ReplaySourceOption>): ResolvedReplay? =
        withTimeoutOrNull(30_000) {
            for (option in (listOf(preferred) + options.filter { it.gameTitle == preferred.gameTitle }).distinctBy { it.id }) {
                try { return@withTimeoutOrNull resolve(event, option) }
                catch (timeout: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A single incompatible host does not block a replay. */ }
            }
            null
        }
    @Throws(Exception::class) suspend fun copyProfiles(): List<UserProfile> = profiles()
    @Throws(Exception::class) suspend fun copyToProfile(profileId: String) = personalScope {
        val expected = owner(); load(expected)
        check(profiles().any { it.id == profileId }) { "Choose a profile on this account." }
        if (owner() != expected) throw CancellationException()
        copyConfiguration(profileId, configuration)
    }
    private fun friendly(error: Exception): String = if (error is IllegalStateException || error is IllegalArgumentException)
        error.message.orEmpty().take(240).takeUnless { it.contains("http", true) && !it.startsWith("Enter a public") }
            ?: "The website could not be read. Try again or choose another source."
        else "The website could not be read. Try again or choose another source."
    fun close() { scope.cancel(); repository.close() }
}
