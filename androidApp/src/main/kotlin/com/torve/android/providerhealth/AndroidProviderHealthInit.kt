package com.torve.android.providerhealth

import com.torve.data.addon.StremioAddonClient
import com.torve.data.debrid.DebridClient
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.model.DebridServiceType
import com.torve.domain.providerhealth.ProviderHealthRepository
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.panda.PandaConfigStateStore
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
import com.torve.presentation.providerhealth.ProviderHealthRefreshOnSettings
import com.torve.presentation.providerhealth.SimklProviderHealthChecker
import com.torve.presentation.providerhealth.TraktProviderHealthChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hydrates [ProviderHealthRepository] and registers every shared
 * [com.torve.presentation.providerhealth.ProviderHealthChecker] whose
 * dependencies are bound on Android. Mirrors `DesktopProviderHealthInit`
 * for cross-platform parity, with two intentional differences:
 *
 *   * **No PlaybackHealthBridge.** The desktop variant bridges
 *     `DesktopPlayerController` phases into the repo. Android uses
 *     ExoPlayer / MPV through a different controller surface, and
 *     bridging that is a separate slice — not part of this wiring.
 *
 * Idempotent: a second [start] call is a no-op. [refreshAll] is the
 * cheap re-run hook used by the credential-transfer completion notifier.
 */
class AndroidProviderHealthInit(
    private val repository: ProviderHealthRepository,
    private val coordinator: ProviderHealthCoordinator,
    private val debridClient: DebridClient,
    private val secretStore: IntegrationSecretStore,
    private val traktTokenStore: TraktTokenStore,
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

    /** Long-running scope for the settings-driven re-run observer. */
    private val observerScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

        // ── IPTV (state projection — no extra fetches) ────────────────
        // ChannelsViewModel is bound as a single in SharedModule, so the
        // state we read here matches the user-facing one.
        coordinator.register(
            IptvProviderHealthChecker(stateSource = { channelsViewModel.state.value }),
        )
        coordinator.register(
            IptvEpgProviderHealthChecker(stateSource = { channelsViewModel.state.value }),
        )

        // ── Trakt / SIMKL (token presence) ────────────────────────────
        coordinator.register(
            TraktProviderHealthChecker(
                tokenSource = {
                    traktTokenStore.accessToken()
                        ?: secretStore.get(IntegrationSecretKey.TRAKT_ACCESS_TOKEN)
                },
            ),
        )
        coordinator.register(
            SimklProviderHealthChecker(
                tokenSource = { secretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN) },
            ),
        )

        // ── Panda Usenet stack (state projection from PandaConfigStateStore) ──
        // The store is a singleton mirror that the wizard's VM publishes
        // its `_state` into; reading `current` here gives a faithful view
        // even when no VM is constructed (returns the default
        // `PandaSetupUiState()` whose `isEditMode = false` → checkers
        // report UNCONFIGURED — exactly what we want on a fresh device).
        coordinator.register(
            PandaUsenetProviderHealthChecker(stateSource = { pandaConfigStateStore.current }),
        )
        coordinator.register(
            PandaUsenetProviderProviderHealthChecker(stateSource = { pandaConfigStateStore.current }),
        )
        coordinator.register(
            PandaDownloadClientProviderHealthChecker(stateSource = { pandaConfigStateStore.current }),
        )

        // Observe settings-refresh signals (Panda save success, Debrid
        // token rotation, integration changes). One long-running
        // collector; exits with the scope on process death.
        observerScope.launch { refreshOnSettings.observe() }
    }

    /**
     * Re-runs every registered checker. Cheap when no checkers are
     * registered yet (returns immediately). Used by the credential-
     * transfer completion notifier so freshly imported credentials
     * flip the rows green / yellow / red on next composition.
     */
    fun refreshAll() {
        coordinator.runAll()
    }

    private fun secretKey(provider: DebridServiceType): IntegrationSecretKey = when (provider) {
        DebridServiceType.REAL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID
        DebridServiceType.ALL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID
        DebridServiceType.PREMIUMIZE -> IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE
        DebridServiceType.TORBOX -> IntegrationSecretKey.DEBRID_API_KEY_TORBOX
    }
}
