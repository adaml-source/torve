package com.torve.presentation.profile

import com.torve.data.profile.FamilyControls
import com.torve.data.profile.FamilyAccessState
import com.torve.data.integrations.JellyfinLibraryOverlayService
import com.torve.domain.model.*
import com.torve.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class FamilySourceChoice(val key: String, val title: String, val group: String)
data class FamilyControlsUiState(
    val access: FamilyAccessState = FamilyAccessState(),
    val profiles: List<UserProfile> = emptyList(),
    val sources: List<FamilySourceChoice> = emptyList(),
    val editingProfileId: String? = null,
    val draftKids: Boolean = false,
    val draftRules: KidsProfileRules = KidsProfileRules(),
    val previewItems: List<MediaItem> = emptyList(),
    val previewReady: Boolean = false,
    val titleResults: List<MediaItem> = emptyList(),
    val blockedTitleLabels: Map<String, String> = emptyMap(),
    val pendingProfileId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val recoveryDestination: String? = null,
    val copySourceId: String? = null,
    val copyTargetId: String? = null,
    val copyServices: List<String> = emptyList(),
    val selectedCopyServices: Set<String> = emptySet(),
    val copyReplace: Boolean = false,
    val copySync: Boolean = true,
    val copyingConnections: Boolean = false,
)

class FamilyControlsViewModel(
    val controls: FamilyControls,
    private val profiles: ProfileRepository,
    private val metadata: () -> MetadataRepository,
    private val addons: () -> AddonRepository,
    private val channels: () -> ChannelRepository,
    private val jellyfin: () -> JellyfinLibraryOverlayService,
    private val provisionSources: suspend (String, KidsProfileRules) -> Unit = { _, _ -> },
    private val copier: com.torve.data.profile.ProfileConnectionCopier? = null,
    private val syncProfiles: suspend () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val metadataRepo by lazy(metadata)
    private val _state = MutableStateFlow(FamilyControlsUiState())
    val state: StateFlow<FamilyControlsUiState> = _state.asStateFlow()
    init {
        scope.launch { controls.state.collect { access -> _state.update { it.copy(access = access) } } }
        refresh()
    }
    private fun work(action: suspend () -> Unit) {
        if (_state.value.busy) return
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Could not update Family controls.") } }
            finally { controls.updateSession(); _state.update { it.copy(busy = false) } }
        }
    }
    fun refresh() = work {
        controls.initialize(); controls.refreshRemote(); syncProfiles(); controls.updateSession()
        if (profiles.getAllProfiles().isEmpty() && !controls.state.value.configuration.enabled) {
            profiles.createProfile(UserProfile(id = "default", name = "Default", isActive = true))
        }
        _state.update { it.copy(profiles = profiles.getAllProfiles()) }
    }
    fun signInOwner(email: String, password: String) = work { controls.signInOwner(email, password); syncProfiles(); _state.update { it.copy(profiles = profiles.getAllProfiles()) } }
    fun requestEmailRecovery() = work {
        val destination = controls.requestEmailRecovery()
        _state.update { it.copy(recoveryDestination = destination, notice = "A recovery code was sent to $destination.") }
    }
    fun confirmEmailRecovery(code: String, pin: String, confirmation: String) = work {
        controls.recoverWithEmailCode(code, pin, confirmation)
        _state.update { it.copy(recoveryDestination = null, notice = "Parent PIN reset. Other devices must unlock again.") }
    }
    fun connectionName(key: String): String = com.torve.data.profile.connectionLabel(key)
    fun beginCopyConnections() = work {
        controls.requireParent()
        val all = profiles.getAllProfiles()
        val source = controls.state.value.activeProfileId
        _state.update { it.copy(copyingConnections = true, profiles = all, copySourceId = source,
            copyTargetId = all.firstOrNull { p -> p.id != source }?.id, copyServices = emptyList(), selectedCopyServices = emptySet()) }
        loadCopyChoices(source)
    }
    private suspend fun loadCopyChoices(source: String) {
        val choices = copier?.choices(source).orEmpty()
        _state.update { it.copy(copyServices = choices, selectedCopyServices = emptySet()) }
    }
    fun setCopySource(id: String) = work {
        _state.update { it.copy(copySourceId = id, selectedCopyServices = emptySet()) }
        loadCopyChoices(id)
    }
    fun setCopyTarget(id: String) { _state.update { it.copy(copyTargetId = id) } }
    fun toggleCopyService(key: String) { _state.update { it.copy(selectedCopyServices = if (key in it.selectedCopyServices) it.selectedCopyServices - key else it.selectedCopyServices + key) } }
    fun setCopyReplace(value: Boolean) { _state.update { it.copy(copyReplace = value) } }
    fun setCopySync(value: Boolean) { _state.update { it.copy(copySync = value) } }
    fun cancelCopyConnections() { _state.update { it.copy(copyingConnections = false) } }
    fun copyConnections() = work {
        val s = state.value
        copier?.copy(s.copySourceId ?: error("Choose a source profile."), s.copyTargetId ?: error("Choose a destination profile."), s.selectedCopyServices.toList(), s.copyReplace, s.copySync)
            ?: error("Connection copying is unavailable.")
        _state.update { it.copy(copyingConnections = false, notice = if (s.copySync) "Connections copied and saved to your account. They restore when you open the destination profile." else "Connections copied on this device.") }
    }
    fun enable(pin: String, confirmation: String) = work { controls.enable(pin, confirmation); loadSources() }
    fun authorize(pin: String) = work {
        controls.authorize(pin)
        val pending = _state.value.pendingProfileId ?: controls.state.value.activeProfileId.takeIf { controls.state.value.needsUnlock }
        if (pending != null) { profiles.setActiveProfile(pending); controls.updateSession() }
        _state.update { it.copy(pendingProfileId = null, profiles = profiles.getAllProfiles()) }
        loadSources()
    }
    fun selectProfile(id: String) = work {
        if (controls.state.value.configuration.enabled && id !in controls.state.value.configuration.kids && !controls.canManage()) {
            _state.update { it.copy(pendingProfileId = id) }; return@work
        }
        profiles.setActiveProfile(id); controls.updateSession()
        _state.update { it.copy(profiles = profiles.getAllProfiles(), editingProfileId = null) }
    }
    fun cancelPin() { _state.update { it.copy(pendingProfileId = null, error = null) } }
    fun lock() { controls.lock() }
    fun disable() = work { controls.disable(); _state.update { it.copy(editingProfileId = null, notice = "Family controls are off.") } }
    fun recoverPin(email: String, password: String, pin: String, confirmation: String) = work {
        controls.recoverPin(email, password, pin, confirmation)
        _state.update { it.copy(profiles = profiles.getAllProfiles(), notice = "Parent PIN reset.") }
    }
    fun changePin(pin: String, confirmation: String) = work { controls.changePin(pin, confirmation); _state.update { it.copy(notice = "Parent PIN changed.") } }
    fun createProfile(name: String) = work {
        controls.requireParent()
        require(name.trim().isNotEmpty()) { "Enter a profile name." }
        require(profiles.getAllProfiles().size < 8) { "You can create up to 8 profiles." }
        val id = kotlinx.datetime.Clock.System.now().toEpochMilliseconds().toString(36) + kotlin.random.Random.nextInt(100000).toString(36)
        profiles.createProfile(UserProfile(id = id, name = name.trim().take(40)))
        // A profile created from Family controls starts restricted before appearing in the picker.
        controls.saveProfile(id, true, KidsProfileRules())
        _state.update { it.copy(profiles = profiles.getAllProfiles()) }
        beginEdit(id)
    }
    fun beginEdit(id: String) {
        if (!controls.canManage()) { _state.update { it.copy(error = "Enter the parent PIN to edit profiles.") }; return }
        val rules = controls.state.value.configuration.kids[id]
        _state.update { it.copy(editingProfileId = id, draftKids = rules != null,
            draftRules = rules ?: KidsProfileRules(), previewItems = emptyList(), previewReady = false, titleResults = emptyList(), blockedTitleLabels = emptyMap()) }
        scope.launch {
            loadSources()
            val keys = rules?.blockedTitles.orEmpty()
            val labels = mutableMapOf<String, String>()
            for (key in keys) {
                if (!controls.canManage() || _state.value.editingProfileId != id) return@launch
                val type = if (key.startsWith("SERIES:")) "tv" else "movie"
                val value = key.substringAfter(':')
                val title = try { value.toIntOrNull()?.let { metadataRepo.getDetail(type, it).title }
                    ?: value.takeIf { it.startsWith("tt") }?.let { metadataRepo.findByImdbId(it, type)?.title } }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                labels[key] = title ?: if (type == "tv") "Blocked TV show" else "Blocked movie"
            }
            if (controls.canManage() && _state.value.editingProfileId == id) _state.update { it.copy(blockedTitleLabels = labels) }
        }
    }
    fun cancelEdit() { _state.update { it.copy(editingProfileId = null, previewItems = emptyList(), previewReady = false, titleResults = emptyList()) } }
    fun setKids(value: Boolean) { _state.update { it.copy(draftKids = value, previewReady = false) } }
    fun setMaximumAge(age: Int) { _state.update { it.copy(draftRules = it.draftRules.copy(maximumAge = age), previewReady = false) } }
    fun setCountry(country: String) { _state.update { it.copy(draftRules = it.draftRules.copy(ratingCountry = country), previewReady = false) } }
    fun setHideUnrated(hide: Boolean) { _state.update { it.copy(draftRules = it.draftRules.copy(hideUnrated = hide), previewReady = false) } }
    fun toggleSource(key: String) { _state.update { it.copy(draftRules = it.draftRules.copy(allowedSources = if (key in it.draftRules.allowedSources) it.draftRules.allowedSources - key else it.draftRules.allowedSources + key), previewReady = false) } }
    fun toggleBlocked(item: MediaItem) = work {
        controls.requireParent()
        val key = controls.canonicalBlockKey(item)
        _state.update { it.copy(draftRules = it.draftRules.copy(blockedTitles = if (key in it.draftRules.blockedTitles) it.draftRules.blockedTitles - key else it.draftRules.blockedTitles + key), blockedTitleLabels = it.blockedTitleLabels + (key to item.title), previewReady = false) }
    }
    fun removeBlocked(key: String) { _state.update { it.copy(draftRules = it.draftRules.copy(blockedTitles = it.draftRules.blockedTitles - key)) } }
    fun saveEditor() = work {
        val current = _state.value
        val id = current.editingProfileId ?: return@work
        if (current.draftKids) provisionSources(id, current.draftRules)
        controls.saveProfile(id, current.draftKids, current.draftRules)
        _state.update { it.copy(editingProfileId = null, profiles = profiles.getAllProfiles(), notice = "Profile rules saved.") }
    }
    fun searchTitles(query: String) = work {
        controls.requireParent()
        _state.update { it.copy(titleResults = if (query.trim().length < 2) emptyList() else metadataRepo.searchMulti(query.trim()).take(20)) }
    }
    fun preview() = work {
        controls.requireParent()
        val draft = _state.value.draftRules.copy(hideUnrated = true)
        val candidates = metadataRepo.getTrending("movie") + metadataRepo.getTrending("tv")
        val verified = coroutineScope { candidates.take(40).map { async { controls.certify(it) } }.awaitAll() }
        val visible = verified.filter { "catalogue" in draft.allowedSources && FamilyPolicy.allows(it, draft) }
        _state.update { it.copy(previewItems = visible, previewReady = true) }
    }
    private suspend fun loadSources() {
        if (!controls.canManage()) return
        val available = mutableListOf(FamilySourceChoice("catalogue", "Movies & TV catalogue", "Browse"), FamilySourceChoice("downloads", "Offline downloads", "Library"))
        runCatching { addons().getEnabledAddons() }.getOrDefault(emptyList()).forEach { addon ->
            available += FamilySourceChoice("extension:${addon.manifest.id}", addon.manifest.name, "Extensions")
        }
        runCatching { jellyfin().getLibrarySectionsOrThrow(forFamilySetup = true) }.getOrDefault(emptyList()).forEach { section ->
            available += FamilySourceChoice("jellyfin:${section.id}", section.name, "Jellyfin")
        }
        val channelSource = channels()
        if (channelSource is com.torve.data.profile.FamilyChannelRepository) {
            runCatching { channelSource.setupChoices() }.getOrDefault(emptyList()).forEach { (key, title, group) ->
                available += FamilySourceChoice(key, title, group)
            }
        }
        _state.update { it.copy(sources = available.distinctBy { it.key }) }
    }
}
