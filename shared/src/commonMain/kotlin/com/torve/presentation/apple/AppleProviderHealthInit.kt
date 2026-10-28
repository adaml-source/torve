package com.torve.presentation.apple

import com.torve.data.addon.StremioAddonClient
import com.torve.data.debrid.DebridClient
import com.torve.data.panda.readPandaDebridActivationSnapshot
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.AutomationInstanceRepository
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.integrations.MediaLifecycleService
import com.torve.data.integrations.AutomationAdminClient
import com.torve.domain.model.DebridServiceType
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.providerhealth.ProviderHealthRepository
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.panda.PandaConfigStateStore
import com.torve.presentation.providerhealth.AddonProbeTarget
import com.torve.presentation.providerhealth.AddonProviderHealthChecker
import com.torve.presentation.providerhealth.AutomationStackProviderHealthChecker
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
import com.torve.presentation.providerhealth.SeerrProviderHealthChecker
import com.torve.presentation.providerhealth.TraktProviderHealthChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.torve.domain.providerhealth.ProviderHealthCategory
import org.koin.core.Koin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares the real provider probes and cached state with Android. No startup network checks. */
class AppleProviderHealthInit(private val koin: Koin) {
    private var started = false
    private val lock = Mutex()
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun usableCredential(value: String?) = value?.trim()?.takeIf { it.isNotBlank() && !it.contains("redact", true) }
    suspend fun start() = lock.withLock {
        if (started) return@withLock
        val repository = koin.get<ProviderHealthRepository>()
        val coordinator = koin.get<ProviderHealthCoordinator>()
        val debridClient = koin.get<DebridClient>()
        val secretStore = koin.get<IntegrationSecretStore>()
        val traktTokenStore = koin.get<TraktTokenStore>()
        val prefs = koin.get<PreferencesRepository>()
        val libraryService = koin.get<LibraryOverlayService>()
        val mediaLifecycleService = koin.get<MediaLifecycleService>()
        val automationRepository = koin.get<AutomationInstanceRepository>()
        val automationAdminClient = koin.get<AutomationAdminClient>()
        val addonRepository = koin.get<AddonRepository>()
        val stremioAddonClient = koin.get<StremioAddonClient>()
        val channelsViewModel = lazy { koin.get<ChannelsViewModel>() }
        val pandaConfigStateStore = koin.get<PandaConfigStateStore>()
        val refreshOnSettings = koin.get<ProviderHealthRefreshOnSettings>()
        val traktClient = koin.get<com.torve.data.trakt.TraktClient>()
        val simklClient = koin.get<com.torve.data.simkl.SimklClient>()
        repository.load()
        val pandaDebridProviderMap = mapOf(
            "realdebrid" to DebridServiceType.REAL_DEBRID,
            "alldebrid" to DebridServiceType.ALL_DEBRID,
            "premiumize" to DebridServiceType.PREMIUMIZE,
            "torbox" to DebridServiceType.TORBOX,
        )
        DebridServiceType.entries.forEach { provider ->
            val providerId = pandaDebridProviderMap.entries.first { it.value == provider }.key
            val checker = DebridProviderHealthChecker(
                    provider = provider,
                    apiKeySource = {
                        if (providerId in prefs.readPandaDebridActivationSnapshot().disconnectedProviderIds) null
                        else usableCredential(secretStore.get(secretKey(provider)))
                            ?: run {
                                val state = pandaConfigStateStore.current
                                usableCredential(state.debridApiKeys[providerId])
                                    ?: usableCredential(
                                        if (state.selectedProvider?.id == providerId) {
                                            state.debridApiKey
                                        } else {
                                            null
                                        },
                                    )
                            }
                    },
                    debridClient = debridClient,
                )
            coordinator.register(checker)
            // Local reconciliation only: never keep an old green result for a missing key.
            repository.upsert(checker.configurationState())
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
                    if (!prefs.getString("plex_server_url").isNullOrBlank())
                        secretStore.get(IntegrationSecretKey.PLEX_ACCESS_TOKEN)
                    else secretStore.get(IntegrationSecretKey.JELLYFIN_API_KEY)
                },
                service = libraryService,
            ),
        )

        coordinator.register(
            SeerrProviderHealthChecker(
                serverUrlSource = { prefs.getString("seerr_server_url") },
                apiKeySource = { secretStore.get(IntegrationSecretKey.SEERR_API_KEY) },
                service = mediaLifecycleService,
            ),
        )
        coordinator.register(
            AutomationStackProviderHealthChecker(
                repository = automationRepository,
                adminClient = automationAdminClient,
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
            IptvProviderHealthChecker(stateSource = { channelsViewModel.value.state.value }),
        )
        coordinator.register(
            IptvEpgProviderHealthChecker(stateSource = { channelsViewModel.value.state.value }),
        )

        // Authenticated account checks are separate from history synchronization.
        coordinator.register(
            TraktProviderHealthChecker(
                tokenSource = {
                    traktTokenStore.accessToken()
                        ?: secretStore.get(IntegrationSecretKey.TRAKT_ACCESS_TOKEN)
                },
                probe = { koin.get<com.torve.data.trakt.api.TraktAuthorizedApi>().probeAccount() },
            ),
        )
        coordinator.register(
            SimklProviderHealthChecker(
                tokenSource = { secretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN) },
                probe = simklClient::probeAccount,
            ),
        )

        // ── Panda Usenet stack ─────────────────────────────────────────
        // stateSource prefers PandaConfigStateStore (live, set by the VM
        // on every wizard session). When the store hasn't been hydrated
        // yet (isEditMode = false), it falls back to prefs + secretStore
        // so checks are accurate on cold start without opening Panda.
        // panda_download_client / panda_usenet_provider / panda_indexer_subkeys
        // are written by saveConfigAndInstall and checkExistingConfig.
        val pandaStateSource: suspend () -> com.torve.presentation.panda.PandaSetupUiState = {
            val live = pandaConfigStateStore.current
            if (live.isEditMode) {
                live
            } else {
                // If no Panda token exists the user hasn't configured Panda at all.
                val hasPandaToken = secretStore.get(IntegrationSecretKey.PANDA_TOKEN) != null
                if (!hasPandaToken) {
                    live
                } else {
                    // Panda IS configured. Resolve credentials from secretStore without
                    // requiring the user to open the Panda screen first. Works correctly
                    // on fresh install, on app update, and on every cold start.
                    //
                    // Strategy: prefer the pref-stored name (written by saveConfigAndInstall
                    // / checkExistingConfig on this version), then scan known names so the
                    // fallback works even before those prefs have been written once.
                    val prefClient = runCatching { prefs.getString("panda_download_client") }.getOrNull()
                    val prefProvider = runCatching { prefs.getString("panda_usenet_provider") }.getOrNull()
                    val prefIndexerSubkeys = runCatching { prefs.getString("panda_indexer_subkeys") }.getOrNull() ?: ""

                    val knownClients = buildList {
                        if (!prefClient.isNullOrBlank() && prefClient != "none") add(prefClient)
                        addAll(listOf("torbox", "sabnzbd", "nzbget", "nzbvortex"))
                    }.distinct()
                    val torboxDebridKey = secretStore.get(IntegrationSecretKey.DEBRID_API_KEY_TORBOX)
                        ?.takeIf { it.isNotBlank() && !it.contains("redact", ignoreCase = true) }
                        .orEmpty()
                    val downloadClient = knownClients.firstOrNull { c ->
                        secretStore.get(IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY, subKey = c)?.isNotBlank() == true ||
                            secretStore.get(IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_PASSWORD, subKey = c)?.isNotBlank() == true ||
                            (c == "torbox" && torboxDebridKey.isNotBlank())
                    } ?: "none"
                    val downloadClientApiKey = if (downloadClient != "none")
                        secretStore.get(IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY, subKey = downloadClient)
                            ?: if (downloadClient == "torbox") torboxDebridKey else ""
                    else ""
                    val downloadClientPassword = if (downloadClient != "none")
                        secretStore.get(IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_PASSWORD, subKey = downloadClient) ?: ""
                    else ""

                    val knownProviders = buildList {
                        if (!prefProvider.isNullOrBlank() && prefProvider != "none") add(prefProvider)
                        addAll(listOf("easynews", "newshosting", "generic"))
                    }.distinct()
                    val usenetProvider = knownProviders.firstOrNull { p ->
                        secretStore.get(IntegrationSecretKey.PANDA_USENET_PASSWORD, subKey = p)?.isNotBlank() == true
                    } ?: "none"
                    val usenetPassword = if (usenetProvider != "none")
                        secretStore.get(IntegrationSecretKey.PANDA_USENET_PASSWORD, subKey = usenetProvider) ?: ""
                    else ""

                    // Enumerate all stored indexer subkeys directly from secretStore.
                    // Subkeys have the format "type|url" — we don't need to know them
                    // in advance because getSubKeys() reads the SharedPreferences key
                    // names (which are not encrypted) and filters by prefix. Works
                    // immediately after any app update without any user action.
                    val indexerRows: List<com.torve.data.panda.NzbIndexerRow> = secretStore
                        .getSubKeys(IntegrationSecretKey.PANDA_INDEXER_API_KEY)
                        .filter { it.contains("|") } // skip type-only entries
                        .mapNotNull { subKey ->
                            val apiKey = secretStore.get(IntegrationSecretKey.PANDA_INDEXER_API_KEY, subKey = subKey)
                                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val (type, url) = subKey.split("|", limit = 2)
                            com.torve.data.panda.NzbIndexerRow(type = type, url = url, apiKey = apiKey)
                        }

                    com.torve.presentation.panda.PandaSetupUiState(
                        isEditMode = true,
                        downloadClient = downloadClient,
                        downloadClientApiKey = downloadClientApiKey,
                        downloadClientPassword = downloadClientPassword,
                        enableUsenet = usenetProvider != "none",
                        usenetProvider = usenetProvider,
                        usenetPassword = usenetPassword,
                        nzbIndexers = indexerRows.ifEmpty { com.torve.presentation.panda.PandaSetupUiState().nzbIndexers },
                    )
                }
            }
        }
        coordinator.register(PandaUsenetProviderHealthChecker(stateSource = pandaStateSource))
        coordinator.register(PandaUsenetProviderProviderHealthChecker(stateSource = pandaStateSource))
        coordinator.register(PandaDownloadClientProviderHealthChecker(stateSource = pandaStateSource))

        // Observe settings-refresh signals (Panda save success, Debrid
        // token rotation, integration changes). One long-running
        // collector; exits with the scope on process death.
        observerScope.launch { refreshOnSettings.observe() }

        // fresh keys and every checker — including debrid — gets accurate
        // results.
        // Do not run network/provider probes automatically at app startup.
        // The TV shell must stay responsive and render cached state first;
        // explicit settings refreshes and credential-transfer completion still
        // drive runAll()/runCheck() when the user needs fresh health status.

        val seeds = listOf(
            Triple(ProviderHealthCategory.TRAKT, "trakt:account", "Trakt"),
            Triple(ProviderHealthCategory.SIMKL, "simkl:account", "SIMKL"),
            Triple(ProviderHealthCategory.IPTV, "iptv:active", "Channels"),
            Triple(ProviderHealthCategory.EPG, "iptv:epg", "Programme guide"),
            Triple(ProviderHealthCategory.PLEX_JELLYFIN, "library:plex_jellyfin", "Plex / Jellyfin"),
            Triple(ProviderHealthCategory.REQUEST_MANAGER, "seerr:request_manager", "Seerr")
        ) + DebridServiceType.entries.map { Triple(ProviderHealthCategory.DEBRID, "debrid:${it.name.lowercase()}", it.name.replace('_', ' ')) }
        seeds.forEach { (category, key, label) ->
            if (repository.entries.value.none { it.providerKey == key }) repository.upsert(com.torve.domain.providerhealth.ProviderHealthEntry(category, key, label, ProviderHealthStatus.UNKNOWN, message = "Not checked yet.", nextAction = "Connection settings"))
        }
        started = true
    }
    private fun secretKey(provider: DebridServiceType) = when (provider) {
        DebridServiceType.REAL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID
        DebridServiceType.ALL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID
        DebridServiceType.PREMIUMIZE -> IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE
        DebridServiceType.TORBOX -> IntegrationSecretKey.DEBRID_API_KEY_TORBOX
    }
}
