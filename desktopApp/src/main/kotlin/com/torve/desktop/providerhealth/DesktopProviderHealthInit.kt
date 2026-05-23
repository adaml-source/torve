package com.torve.desktop.providerhealth

import com.torve.data.addon.StremioAddonClient
import com.torve.data.debrid.DebridClient
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.model.DebridServiceType
import com.torve.domain.providerhealth.ProviderHealthRepository
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.panda.PandaConfigStateStore
import com.torve.presentation.providerhealth.ProviderHealthRefreshOnSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.torve.presentation.providerhealth.AddonProbeTarget
import com.torve.presentation.providerhealth.AddonProviderHealthChecker
import com.torve.presentation.providerhealth.DebridProviderHealthChecker
import com.torve.presentation.providerhealth.IptvEpgProviderHealthChecker
import com.torve.presentation.providerhealth.IptvProviderHealthChecker
import com.torve.presentation.providerhealth.PandaDownloadClientProviderHealthChecker
import com.torve.presentation.providerhealth.PandaUsenetProviderHealthChecker
import com.torve.presentation.providerhealth.PandaUsenetProviderProviderHealthChecker
import com.torve.presentation.providerhealth.PlexJellyfinProviderHealthChecker
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import com.torve.presentation.providerhealth.SimklProviderHealthChecker
import com.torve.presentation.providerhealth.TraktProviderHealthChecker

/**
 * Hydrates the repository and registers desktop's set of
 * [com.torve.presentation.providerhealth.ProviderHealthChecker]s with the
 * shared [ProviderHealthCoordinator]. Called once at desktop startup.
 *
 * Keeping this in the desktop module (rather than commonMain) avoids
 * pulling shared code into a dependency on the desktop-only Newznab
 * client / addon repository wiring. Other platforms ship their own
 * equivalent.
 */
class DesktopProviderHealthInit(
    private val repository: ProviderHealthRepository,
    private val coordinator: ProviderHealthCoordinator,
    private val debridClient: DebridClient,
    private val secretStore: IntegrationSecretStore,
    private val prefs: PreferencesRepository,
    private val libraryService: LibraryOverlayService,
    private val addonRepository: AddonRepository,
    private val stremioAddonClient: StremioAddonClient,
    private val channelsViewModel: ChannelsViewModel,
    private val pandaConfigStateStore: PandaConfigStateStore,
    private val refreshOnSettings: ProviderHealthRefreshOnSettings,
) {
    @Volatile
    private var started: Boolean = false

    /**
     * Long-running scope for the settings-driven re-run observer. Owned
     * by this init so callers don't need to thread a scope through DI.
     * The collector inside [ProviderHealthRefreshOnSettings.observe] is
     * cooperative and exits when the scope cancels (process death).
     */
    private val observerScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Bridges live [com.torve.desktop.playback.DesktopPlayerController]
     * phase transitions into the health repo. Started by V2App once it
     * has the controller reference (the controller isn't in Koin -
     * it's constructed at desktop runtime). Idempotent.
     */
    fun startPlaybackBridge(controller: com.torve.desktop.playback.DesktopPlayerController) {
        PlaybackHealthBridge(controller = controller, repository = repository).start()
    }

    suspend fun start() {
        if (started) return
        started = true

        repository.load()

        // ── Debrid: one checker per provider. UNCONFIGURED when no key. ─
        DebridServiceType.entries.forEach { provider ->
            coordinator.register(
                DebridProviderHealthChecker(
                    provider = provider,
                    apiKeySource = { secretStore.get(secretKey(provider)) },
                    debridClient = debridClient,
                ),
            )
        }

        // ── Plex / Jellyfin (composite probe) ─────────────────────────
        coordinator.register(
            PlexJellyfinProviderHealthChecker(
                serverUrlSource = {
                    val plex = runCatching { prefs.getString("plex_server_url") }.getOrNull()
                    if (!plex.isNullOrBlank()) plex
                    else runCatching { prefs.getString("jellyfin_server_url") }.getOrNull()
                },
                tokenSource = {
                    val plexToken = runCatching {
                        secretStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN)
                    }.getOrNull()
                    if (!plexToken.isNullOrBlank()) plexToken
                    else runCatching {
                        secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY)
                    }.getOrNull()
                },
                service = libraryService,
            ),
        )

        // ── Stremio addons (per-manifest probe) ───────────────────────
        coordinator.register(
            AddonProviderHealthChecker(
                addonsSource = {
                    runCatching { addonRepository.getEnabledAddons() }
                        .getOrDefault(emptyList())
                        .map { addon ->
                            AddonProbeTarget(
                                label = addon.manifest.name.takeIf { it.isNotBlank() }
                                    ?: addon.manifest.id,
                                baseUrl = addon.manifestUrl.removeSuffix("manifest.json")
                                    .removeSuffix("/"),
                            )
                        }
                },
                addonClient = stremioAddonClient,
            ),
        )

        // ── IPTV (state projection - no extra fetches) ────────────────
        coordinator.register(
            IptvProviderHealthChecker(stateSource = { channelsViewModel.state.value }),
        )
        coordinator.register(
            IptvEpgProviderHealthChecker(stateSource = { channelsViewModel.state.value }),
        )

        // ── Panda Usenet stack (state projection from PandaSetupVM) ──
        observerScope.launch {
            channelsViewModel.state
                .map { state ->
                    IptvHealthSignature(
                        playlistCount = state.playlists.size,
                        selectedPlaylistId = state.selectedPlaylistId,
                        channelCount = state.channels.size,
                        categoryChannelCount = state.categoryChannels.size,
                        categoryCount = state.categories.size,
                        groupedChannelCount = state.groupedChannels.values.sumOf { it.size },
                        epgStateClass = state.epgState::class.simpleName.orEmpty(),
                    )
                }
                .distinctUntilChanged()
                .debounce(500L)
                .collect {
                    coordinator.runCheck("iptv:active")?.join()
                    coordinator.runCheck("iptv:epg")?.join()
                }
        }

        coordinator.register(
            PandaUsenetProviderHealthChecker(stateSource = { pandaConfigStateStore.current }),
        )
        coordinator.register(
            PandaUsenetProviderProviderHealthChecker(stateSource = { pandaConfigStateStore.current }),
        )
        coordinator.register(
            PandaDownloadClientProviderHealthChecker(stateSource = { pandaConfigStateStore.current }),
        )

        // ── Trakt / SIMKL (token presence) ────────────────────────────
        coordinator.register(
            TraktProviderHealthChecker(
                tokenSource = { secretStore.get(IntegrationSecretKey.TRAKT_ACCESS_TOKEN) },
            ),
        )
        coordinator.register(
            SimklProviderHealthChecker(
                tokenSource = { secretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN) },
            ),
        )

        // Observe settings-refresh signals (Panda save success, Debrid
        // token rotation, integration changes). One long-running
        // collector; exits with the scope on process death.
        observerScope.launch { refreshOnSettings.observe() }
    }

    private fun secretKey(provider: DebridServiceType): IntegrationSecretKey = when (provider) {
        DebridServiceType.REAL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID
        DebridServiceType.ALL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID
        DebridServiceType.PREMIUMIZE -> IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE
        DebridServiceType.TORBOX -> IntegrationSecretKey.DEBRID_API_KEY_TORBOX
    }

    private data class IptvHealthSignature(
        val playlistCount: Int,
        val selectedPlaylistId: String?,
        val channelCount: Int,
        val categoryChannelCount: Int,
        val categoryCount: Int,
        val groupedChannelCount: Int,
        val epgStateClass: String,
    )
}
