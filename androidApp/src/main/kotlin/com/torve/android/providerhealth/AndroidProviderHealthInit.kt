package com.torve.android.providerhealth

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.torve.data.addon.StremioAddonClient
import com.torve.data.debrid.DebridClient
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.model.DebridServiceType
import com.torve.domain.providerhealth.ProviderHealthStatus
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
    private val context: android.content.Context,
) {
    @Volatile
    private var started: Boolean = false

    companion object {
        private const val HEALTH_CHANNEL_ID = "provider_health"
        private const val HEALTH_NOTIF_ID = 2001
    }

    /** Long-running scope for the settings-driven re-run observer. */
    private val observerScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun ensureHealthChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                HEALTH_CHANNEL_ID,
                "Service alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun postHealthNotification(label: String, message: String) {
        ensureHealthChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(context, HEALTH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Connection problem")
            .setContentText("$label: $message")
            .build()
        NotificationManagerCompat.from(context).notify(HEALTH_NOTIF_ID, notification)
    }

    private fun usableCredential(value: String?): String? =
        value
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("redact", ignoreCase = true) }

    suspend fun start() {
        if (started) return
        started = true

        repository.load()

        // Refresh the RD OAuth access token eagerly so an expired token
        // doesn't surface as a bad_token 401 on the first play attempt.
        // Only runs when a refresh token is stored (i.e. device-auth flow
        // was used). API-key users have no refresh token so this is a no-op.
        if (secretStore.get(IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN) != null) {
            runCatching { debridClient.rdTokenRefresher?.refresh() }
        }

        // ── Debrid: one checker per provider. UNCONFIGURED when no key. ─
        // apiKeySource checks secretStore first (persisted after any
        // save or checkExistingConfig), then falls back to
        // PandaConfigStateStore (populated while the Panda VM is live).
        // This means the checker always has a key if Panda has been
        // opened in this session, even before the user re-saves.
        val pandaDebridProviderMap = mapOf(
            "realdebrid" to DebridServiceType.REAL_DEBRID,
            "alldebrid" to DebridServiceType.ALL_DEBRID,
            "premiumize" to DebridServiceType.PREMIUMIZE,
            "torbox" to DebridServiceType.TORBOX,
        )
        DebridServiceType.entries.forEach { provider ->
            coordinator.register(
                DebridProviderHealthChecker(
                    provider = provider,
                    apiKeySource = {
                        usableCredential(secretStore.get(secretKey(provider)))
                            ?: run {
                                val state = pandaConfigStateStore.current
                                val providerId = pandaDebridProviderMap.entries
                                    .firstOrNull { it.value == provider }
                                    ?.key
                                usableCredential(providerId?.let { state.debridApiKeys[it] })
                                    ?: usableCredential(
                                        if (providerId != null && state.selectedProvider?.id == providerId) {
                                            state.debridApiKey
                                        } else {
                                            null
                                        },
                                    )
                            }
                    },
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

        // Collect status transitions and notify on GREEN→RED
        observerScope.launch {
            var prev = emptyMap<String, ProviderHealthStatus>()
            coordinator.entries.collect { entries ->
                entries.forEach { entry ->
                    val wasOk = prev[entry.providerKey] == ProviderHealthStatus.GREEN
                    val nowBad = entry.status == ProviderHealthStatus.RED
                    if (wasOk && nowBad) {
                        postHealthNotification(entry.label, entry.message ?: "Connection lost")
                    }
                }
                prev = entries.associate { it.providerKey to it.status }
            }
        }
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
