package com.torve.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.torve.data.auth.AuthClient
import com.torve.data.addon.SubtitleAggregator
import com.torve.data.download.BulkDownloadManager
import com.torve.desktop.admission.DesktopShellAdmissionController
import com.torve.desktop.admission.DesktopShellState
import com.torve.desktop.auth.DesktopAuthController
import com.torve.desktop.auth.DesktopAuthPhase
import com.torve.desktop.auth.DesktopAuthUiState
import com.torve.desktop.download.DesktopDownloadManager
import com.torve.desktop.di.desktopAppModule
import com.torve.desktop.playback.DesktopPlayerController
import com.torve.desktop.search.DesktopSearchController
import com.torve.desktop.ui.onboarding.DesktopOnboardingShell
import com.torve.desktop.ui.theme.TorveDesktopTheme
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.di.sharedModule
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.repository.MediaFavoritesRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.StreamRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.security.SecureStorage
import com.torve.domain.security.SyncPayloadEncryptor
import com.torve.domain.security.ClientTrustSignalProvider
import com.torve.domain.security.ClientTrustSignalRegistry
import com.torve.platform.DatabaseDriverFactory
import com.torve.platform.NetworkMonitor
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.presentation.search.SearchViewModel
import com.torve.presentation.seeall.SeeAllViewModel
import com.torve.presentation.player.TraktScrobbler
import com.torve.presentation.download.DownloadCatalogueViewModel
import com.torve.presentation.download.DownloadViewModel
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.settings.ThemeMode
import com.torve.presentation.panda.PandaSetupViewModel
import com.torve.presentation.setup.SetupWizardViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.dsl.koinApplication

private data class DesktopRuntime(
    val authClient: AuthClient,
    val authController: DesktopAuthController,
    val admissionController: DesktopShellAdmissionController,
    val playerController: DesktopPlayerController,
    val libraryDetailController: DesktopSearchController,
    val searchController: DesktopSearchController,
    val channelsViewModel: ChannelsViewModel,
    val channelRepository: ChannelRepository,
    val addonRepository: AddonRepository,
    val homeViewModel: HomeViewModel,
    val searchViewModel: SearchViewModel,
    val settingsViewModel: SettingsViewModel,
    val setupWizardViewModel: SetupWizardViewModel,
    val pandaSetupViewModel: PandaSetupViewModel,
    val watchlistViewModel: WatchlistViewModel,
    val mediaFavoritesRepository: MediaFavoritesRepository,
    val accountSessionCoordinator: AccountSessionCoordinator,
    val downloadViewModel: DownloadViewModel,
    val downloadCatalogueViewModel: DownloadCatalogueViewModel,
    val downloadManager: DesktopDownloadManager,
    val moviesCatalogViewModel: com.torve.presentation.catalog.CatalogViewModel,
    val tvShowsCatalogViewModel: com.torve.presentation.catalog.CatalogViewModel,
    val metadataRepository: MetadataRepository,
    val seeAllViewModel: SeeAllViewModel,
    val deviceGovernanceViewModel: com.torve.presentation.device.DeviceGovernanceViewModel,
    val castController: com.torve.desktop.cast.DesktopCastController?,
    val statsViewModel: com.torve.presentation.stats.StatsViewModel,
    val setupIntentsViewModel: com.torve.presentation.setup.SetupIntentsViewModel,
    val setupWizardCoordinator: com.torve.presentation.setup.SetupWizardCoordinator,
    val onboardingDeepLink: com.torve.desktop.ui.onboarding.DesktopOnboardingDeepLink,
    val providerHealthInit: com.torve.desktop.providerhealth.DesktopProviderHealthInit,
)

private sealed interface BootstrapState {
    data object Starting : BootstrapState

    data class Ready(
        val runtime: DesktopRuntime,
    ) : BootstrapState

    data class Failure(
        val message: String,
        val details: String,
    ) : BootstrapState
}

internal val isMac: Boolean = System.getProperty("os.name", "")
    .startsWith("Mac", ignoreCase = true)

/** Cmd on macOS, Ctrl on Windows/Linux - the platform "primary" modifier. */
internal fun primaryAccel(key: Key, shift: Boolean = false): KeyShortcut =
    KeyShortcut(key, meta = isMac, ctrl = !isMac, shift = shift)

/** macOS Fullscreen convention is Cmd+Ctrl+F; everywhere else Ctrl+F. */
internal fun fullscreenAccel(): KeyShortcut =
    if (isMac) KeyShortcut(Key.F, meta = true, ctrl = true)
    else KeyShortcut(Key.F, ctrl = true)

/**
 * Use for shortcuts that conflict with macOS reserved combos (Cmd+W = Close
 * Window, Cmd+M = Minimize). On macOS returns null so the menu item ships
 * without an accelerator; on Windows/Linux returns the Ctrl-keyed combo as
 * usual. Same muscle memory on the platforms that have it, no collision on
 * the platform where the OS owns those keystrokes.
 */
internal fun nonMacAccel(key: Key): KeyShortcut? =
    if (isMac) null else KeyShortcut(key, ctrl = true)

/**
 * Process-wide handle to the active system tray, populated once the tray
 * installs in the application root. Surfaces (e.g. the EPG reminder
 * scheduler) call this without needing the tray threaded through every
 * Compose layer. No-ops before the tray is installed or on platforms where
 * SystemTray is unsupported.
 */
@Volatile
internal var globalTray: com.torve.desktop.tray.DesktopSystemTray? = null

/**
 * In-app alert state. Backed by a [MutableStateFlow] so any Composable
 * in the V2App tree can observe it and render an AlertDialog.
 *
 * Tray balloons are unreliable while the main window is fullscreen on
 * Windows -- DWM frequently suppresses them and the user never sees the
 * notification. We therefore *also* push the message into an in-app
 * dialog so it always becomes visible.
 */
data class DesktopAlert(val title: String, val body: String)

internal val desktopAlertFlow: kotlinx.coroutines.flow.MutableStateFlow<DesktopAlert?> =
    kotlinx.coroutines.flow.MutableStateFlow(null)

fun desktopAlertDismiss() {
    desktopAlertFlow.value = null
}

fun desktopNotify(title: String, body: String) {
    // Tray balloon (best-effort -- may be hidden by DWM under fullscreen).
    globalTray?.notify(title, body)
    // Reliable, always-visible in-app dialog.
    desktopAlertFlow.value = DesktopAlert(title, body)
}

/**
 * Process-wide UpdateChecker singleton. Created once at startup with the
 * release version; surfaces (banner, File menu) read the same instance.
 * Disabled (no-op) when neither `TORVE_UPDATE_REPO` nor `TORVE_UPDATE_FEED`
 * is set in the environment.
 */
@Volatile
internal var globalUpdateChecker: com.torve.desktop.updates.UpdateChecker? = null

/**
 * Process-wide reference to the catalog top-cache worker. The Movies
 * and TV Shows pages subscribe to its progress flow to render a
 * pre-cache banner ("Pre-caching catalog: 7/35 genres...") while the
 * background pass is running. Null until bootstrap completes.
 */
@Volatile
internal var globalCatalogTopWorker: com.torve.data.catalog.CatalogTopCacheWorker? = null

/**
 * Process-wide LocalLibraryRepository singleton. The library page reads
 * this when rendering the "Local Files" tab. One instance per process so
 * folder additions in one place show up everywhere.
 */
internal val globalLocalLibrary: com.torve.desktop.library.LocalLibraryRepository by lazy {
    com.torve.desktop.library.LocalLibraryRepository()
}

/**
 * Process-wide EpgReminderStore singleton. The Live page's EPG reminders
 * and the Settings reminder list page share this - toggle in one, see
 * the change in the other.
 */
internal val globalReminderStore: com.torve.desktop.reminders.EpgReminderStore by lazy {
    com.torve.desktop.reminders.EpgReminderStore(rootDir = com.torve.desktop.platform.desktopDataDir())
}

// Desktop launches fullscreen by default. Windowed launch remains opt-in
// for dev/smoke-test scenarios.
private val launchFullscreen: Boolean =
    System.getProperty("torve.desktop.launchFullscreen")?.toBooleanStrictOrNull()
        ?: System.getProperty("torve.desktop.loginFullscreenPreview", "true").toBoolean()

fun main() {
    // Splash BEFORE application { ... } starts composing. The Compose
    // Window won't become visible for ~350ms, and even then the
    // iconify dance briefly minimizes/restores it -- showing the
    // splash up front means the user sees the Torve loading
    // surface for the entire launch race instead of a black window.
    // The launch guard disposes this splash after iconify completes.
    if (System.getProperty("os.name", "").lowercase().contains("win")) {
        com.torve.desktop.launch.globalLaunchSplash =
            com.torve.desktop.launch.showTorveLaunchSplash()
    }
    application {
    com.torve.platform.TorveRuntimeDebug.verboseLoggingEnabled =
        System.getProperty("torve.verboseLogs")?.toBooleanStrictOrNull()
            ?: (System.getenv("TORVE_VERBOSE_LOGS")?.toBooleanStrictOrNull() == true)
    val releaseInfo = DesktopReleaseInfo.current()
    // Launch-guard observability. Logged once at the top of the
    // application lifecycle so cold-launch traces can correlate
    // strategy + renderer + the rest of the guard timeline.
    com.torve.desktop.launch.launchGuardLog(
        "start",
        "os" to System.getProperty("os.name", "?"),
        "renderer" to (
            System.getProperty("skiko.renderApi")
                ?: System.getenv("SKIKO_RENDER_API")
                ?: "default"
        ),
        "strategy" to com.torve.desktop.launch.resolveLaunchGuardStrategy().token,
        "version" to releaseInfo.version,
    )
    // Sentry init must happen before the uncaught handler installs so the
    // first crash of the session has a place to go. DSN is read from
    // TORVE_SENTRY_DSN - no DSN means the SDK is a no-op (zero network
    // traffic, no events captured) which is what we want in dev / sideloads.
    com.torve.desktop.diagnostics.SentryBootstrap.install(
        releaseInfo = releaseInfo,
        dsn = System.getenv("TORVE_SENTRY_DSN"),
        environment = System.getenv("TORVE_SENTRY_ENV") ?: "production",
    )
    // Pass the raw `version` ("1.0.6") here, NOT `versionLabel` ("Version
    // 1.0.6"). UpdateChecker.isStrictlyNewer splits on `.`/`-`/`+` then
    // compares parts numerically; a "Version " prefix forces a string
    // compare that always returns false, silently breaking the in-app
    // updater for every installed build. Caught by B4 smoke 2026-05-03.
    globalUpdateChecker = com.torve.desktop.updates.UpdateChecker(
        currentVersion = releaseInfo.version,
    )
    // Process-wide reminder scheduler - fires regardless of which page
    // is mounted. Pulls from globalReminderStore, dispatches via tray.
    com.torve.desktop.reminders.ReminderScheduler(
        store = globalReminderStore,
        notifier = ::desktopNotify,
    ).start()
    // Filesystem watcher for the local library - picks up files added
    // outside Torve so the user doesn't need to hit Rescan manually.
    com.torve.desktop.library.LibraryWatcher(repository = globalLocalLibrary).start()
    // Without a global handler, an uncaught exception in any UI/coroutine
    // thread (including Compose composition or AWT) tears the JVM down with
    // no stack trace landing in our logs. Print first, ship to Sentry second,
    // then let the JVM decide what to do.
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        System.err.println("TORVE UNCAUGHT | thread=${thread.name}")
        throwable.printStackTrace(System.err)
        val stackTrace = java.io.StringWriter().also { writer ->
            throwable.printStackTrace(java.io.PrintWriter(writer))
        }.toString()
        com.torve.desktop.launch.launchGuardLog(
            "uncaught_exception",
            "thread" to thread.name,
            "type" to throwable::class.qualifiedName,
            "message" to (throwable.message ?: ""),
        )
        stackTrace.lineSequence()
            .take(80)
            .forEachIndexed { index, line ->
                com.torve.desktop.launch.launchGuardLog(
                    "uncaught_exception_frame",
                    "index" to index,
                    "text" to line.take(500),
                )
            }
        com.torve.desktop.diagnostics.SentryBootstrap.captureUncaught(thread, throwable)
    }
    val windowState = remember {
        com.torve.desktop.platform.DesktopWindowStateStore.restore().also { restored ->
            if (launchFullscreen) {
                restored.placement = WindowPlacement.Fullscreen
            }
        }
    }
    // Start INVISIBLE so the OS never paints the empty JFrame.
    // We flip to true ~350ms later (after Compose composes its first
    // frame and Skiko uploads its GPU surface). Without this the
    // user saw a black window until alt-tab forced a WM_PAINT --
    // a known Compose Desktop / Skiko-on-Windows race. Trade-off:
    // the launch *feels* slower because no window appears for the
    // first ~350ms, but when it appears it's already content-ready,
    // matching how well-behaved macOS / Windows apps launch.
    var windowVisible by remember { mutableStateOf(false) }
    val bootstrapStateHolder = remember { mutableStateOf<BootstrapState>(BootstrapState.Starting) }
    val trayHolder = remember { mutableStateOf<com.torve.desktop.tray.DesktopSystemTray?>(null) }

    DisposableEffect(Unit) {
        val tray = com.torve.desktop.tray.DesktopSystemTray(
            onShowWindow = { windowVisible = true },
            onTogglePlayback = {
                val player = (bootstrapStateHolder.value as? BootstrapState.Ready)?.runtime?.playerController ?: return@DesktopSystemTray
                val phase = player.state.value.phase
                if (phase == com.torve.desktop.playback.DesktopPlayerPhase.PLAYING) player.pause()
                else player.play()
            },
            onQuit = ::exitApplication,
        )
        tray.install()
        trayHolder.value = tray
        globalTray = tray
        onDispose {
            if (windowState.placement != WindowPlacement.Fullscreen) {
                runCatching { com.torve.desktop.platform.DesktopWindowStateStore.save(windowState) }
            }
            tray.release()
            trayHolder.value = null
            globalTray = null
            runCatching { stopKoin() }
        }
    }

    Window(
        onCloseRequest = {
            // Close-to-tray, but tear playback down first - otherwise libvlc
            // keeps the audio output thread alive and the user hears phantom
            // sound from a window they can no longer see.
            (bootstrapStateHolder.value as? BootstrapState.Ready)
                ?.runtime?.playerController
                ?.close()
            windowVisible = false
        },
        title = "${releaseInfo.appName} ${releaseInfo.versionLabel}",
        state = windowState,
        undecorated = false,
        resizable = windowState.placement != WindowPlacement.Fullscreen,
        visible = windowVisible,
    ) {
        // Paint the JFrame's native background dark BEFORE Compose's
        // first frame lands. Without this, Windows flashes its
        // default-white window background until Skiko uploads the
        // first GPU surface; with this set the flash is dark and
        // matches the loading splash that follows, but the surface
        // still doesn't render content until something forces a
        // repaint. (See LaunchedEffect below.)
        SideEffect {
            val darkBg = java.awt.Color(0x09, 0x0A, 0x10)
            if (window.background != darkBg) {
                window.background = darkBg
                window.contentPane.background = darkBg
            }
            val shouldBeResizable = windowState.placement != WindowPlacement.Fullscreen
            if (window.isResizable != shouldBeResizable) {
                window.isResizable = shouldBeResizable
            }
        }

        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(event: java.awt.event.WindowEvent?) = Unit

                override fun windowLostFocus(event: java.awt.event.WindowEvent?) {
                    val opposite = event?.oppositeWindow
                    val sameAppWindow = opposite === window || opposite?.owner === window
                    if (!sameAppWindow && windowState.placement == WindowPlacement.Fullscreen && window.isVisible) {
                        window.extendedState = window.extendedState or java.awt.Frame.ICONIFIED
                    }
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        // Show the window only after the first frame has been
        // composed. The visibility flag starts false at the parent
        // composable; flip to true here once Compose has had a
        // moment to compose + Skiko to upload its first GPU surface.
        // No flicker, no flash, no alt-tab required -- the OS just
        // paints an already-content-ready window the first time.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(350)
            windowVisible = true
        }

        // First-composition signal. LaunchedEffect(Unit) fires when
        // composition completes its first pass for this scope, so this
        // is approximately when Compose has populated its scene graph
        // (NOT when the GPU has presented the frame -- Skiko's render
        // pipeline is asynchronous from composition).
        LaunchedEffect(Unit) {
            com.torve.desktop.launch.launchGuardLog("first_composition_started")
        }

        // Once the window is actually realized on screen, run the
        // configured launch guard. The guard works around a Skiko
        // first-surface init race on Windows where the JFrame becomes
        // visible before DWM has attached its composition surface, so
        // the window sits black until something forces a full repaint.
        // Strategy is selected via TORVE_LAUNCH_GUARD env var; default
        // is "native" (JNA SetForegroundWindow + RedrawWindow), which
        // does not blink the taskbar.
        LaunchedEffect(windowVisible) {
            if (!windowVisible) return@LaunchedEffect
            com.torve.desktop.launch.launchGuardLog("main_visible")
            // Wait for the JFrame to be fully realized and for
            // Compose to schedule its first composition. Too short
            // (<100ms) and the guard runs before the back buffer
            // holds anything worth showing.
            kotlinx.coroutines.delay(200)
            com.torve.desktop.launch.runLaunchGuard(
                mainFrame = window,
                strategy = com.torve.desktop.launch.resolveLaunchGuardStrategy(),
            )
            // Dismiss the launch splash AFTER the iconify dance
            // completes, plus a tiny buffer for the restored window
            // to actually present a frame. The splash was shown in
            // main() before application{} started, so this is the
            // first chance the guard has had to dispose it.
            kotlinx.coroutines.delay(100)
            com.torve.desktop.launch.dismissTorveLaunchSplash()
        }

        // Install AWT-level subtitle drop target on the JFrame backing this
        // Compose Window. Fires SubtitleDropBus, which the active player
        // surface subscribes to. Must run inside the Window scope so
        // `window` is non-null.
        DisposableEffect(Unit) {
            com.torve.desktop.dnd.installSubtitleDropTarget(window)
            // Refresh account access state whenever the user brings the
            // window back to focus.
            val focusListener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                    com.torve.desktop.premium.DesktopPremiumStateHolder.refreshNow()
                }
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) {}
            }
            window.addWindowFocusListener(focusListener)
            onDispose {
                window.removeWindowFocusListener(focusListener)
                /* root-pane DropTarget is GC'd with the window */
            }
        }
        // Run bootstrap on Dispatchers.IO so the EDT stays free during
        // cold launch. produceState's producer block by default runs on
        // the composition coroutine context (EDT on Compose Desktop),
        // which means a synchronous Koin DI startup (1-3s) blocks
        // composition + first paint. That EDT block was making the
        // launch guard fire AFTER the user had already alt-tabbed,
        // and was leaving Compose's first frame unrendered when the
        // window appeared, producing the persistent black-window race.
        val bootstrapState by produceState<BootstrapState>(initialValue = BootstrapState.Starting) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                bootstrapDesktop()
            }
            bootstrapStateHolder.value = value
        }

        // Window-attached MenuBar - Compose Desktop maps this to native NSMenu
        // on macOS automatically. Keyboard accelerators MUST be declared
        // per-item via `shortcut = KeyShortcut(...)`. They are NOT inferred
        // from menu structure.
        val runtime = (bootstrapState as? BootstrapState.Ready)?.runtime
        val playPauseShortcut = if (isMac) KeyShortcut(Key.Spacebar) else primaryAccel(Key.K)
        val isFullscreen = windowState.placement == WindowPlacement.Fullscreen
        MenuBar {
            Menu("File") {
                Item("Check for Updates...", onClick = {
                    @OptIn(DelicateCoroutinesApi::class)
                    globalUpdateChecker?.let { checker ->
                        GlobalScope.launch {
                            val result = checker.check()
                            if (result is com.torve.desktop.updates.UpdateChecker.Result.UpToDate) {
                                desktopNotify("Torve", "You're on the latest version.")
                            }
                        }
                    } ?: desktopNotify("Torve", "Update channel isn't configured for this build.")
                })
                Item("Quit Torve", shortcut = primaryAccel(Key.Q), onClick = ::exitApplication)
            }
            Menu("Playback") {
                Item(
                    "Play / Pause",
                    shortcut = playPauseShortcut,
                    onClick = {
                        val player = runtime?.playerController ?: return@Item
                        val phase = player.state.value.phase
                        if (phase == com.torve.desktop.playback.DesktopPlayerPhase.PLAYING) player.pause()
                        else player.play()
                    },
                )
                Item(
                    "Stop",
                    onClick = { runtime?.playerController?.stop() },
                )
            }
            Menu("Window") {
                Item("Hide to Tray", shortcut = nonMacAccel(Key.W), onClick = { windowVisible = false })
                Item("Maximize", shortcut = nonMacAccel(Key.M), onClick = {
                    windowState.placement = WindowPlacement.Maximized
                })
                Item(if (isFullscreen) "Exit Fullscreen" else "Fullscreen", shortcut = fullscreenAccel(), onClick = {
                    windowState.placement = if (isFullscreen) {
                        WindowPlacement.Floating
                    } else {
                        WindowPlacement.Fullscreen
                    }
                })
            }
            Menu("Help") {
                Item("About Torve", onClick = {
                    runCatching {
                        java.awt.Desktop.getDesktop().browse(java.net.URI("https://torve.app"))
                    }
                })
            }
        }

        TorveDesktopPreview(
            bootstrapState = bootstrapState,
            releaseInfo = releaseInfo,
            windowState = windowState,
            tray = trayHolder.value,
            onExit = ::exitApplication,
        )
    }
    }
}

private fun bootstrapDesktop(): BootstrapState {
    return runCatching {
        runCatching { stopKoin() }

        val app = koinApplication {
            printLogger(Level.INFO)
            modules(sharedModule, desktopAppModule)
        }

        startKoin(app)
        val koin = app.koin
        ClientTrustSignalRegistry.setProvider(koin.get<ClientTrustSignalProvider>())

        koin.get<DatabaseDriverFactory>()
        koin.get<NetworkMonitor>()
        koin.get<DeviceIdProvider>()
        koin.get<SecureStorage>()
        koin.get<IntegrationSecretStore>()
        koin.get<SyncPayloadEncryptor>()
        koin.get<com.torve.db.TorveDatabase>()
        val authClient = koin.get<AuthClient>()
        val accountSessionCoordinator = koin.get<AccountSessionCoordinator>()
        val subscriptionViewModel = koin.get<SubscriptionViewModel>()
        // Desktop access-state holder retained for compatibility. Product
        // features are free by default; polling only keeps account/device
        // state fresh.
        val premiumState = com.torve.desktop.premium.DesktopPremiumState(
            deviceApi = koin.get<com.torve.data.device.DeviceApi>(),
            authClient = authClient,
        )
        com.torve.desktop.premium.DesktopPremiumStateHolder.bind(premiumState)
        premiumState.startPolling()
        val channelsViewModel = koin.get<ChannelsViewModel>()
        val homeViewModel = koin.get<HomeViewModel>()
        val searchViewModel = koin.get<SearchViewModel>()
        val settingsViewModel = koin.get<SettingsViewModel>()
        val setupWizardViewModel = koin.get<SetupWizardViewModel>()
        val pandaSetupViewModel = koin.get<PandaSetupViewModel>()
        val watchlistViewModel = koin.get<WatchlistViewModel>()
        val mediaFavoritesRepository = koin.get<MediaFavoritesRepository>()
        val downloadViewModel = koin.get<DownloadViewModel>()
        val downloadCatalogueViewModel = koin.get<DownloadCatalogueViewModel>()
        val metadataRepository = koin.get<MetadataRepository>()
        val streamRepository = koin.get<StreamRepository>()
        val addonRepository = koin.get<AddonRepository>()
        val downloadRepository = koin.get<DownloadRepository>()
        val bulkDownloadManager = koin.get<BulkDownloadManager>()
        val subtitleAggregator = koin.get<SubtitleAggregator>()
        val watchProgressRepository = koin.get<WatchProgressRepository>()
        val watchHistoryRepository = koin.get<WatchHistoryRepository>()
        val traktScrobbler = koin.get<TraktScrobbler>()
        val prefsRepo = koin.get<PreferencesRepository>()
        val channelRepository = koin.get<ChannelRepository>()
        val downloadManager = DesktopDownloadManager(
            downloadRepo = downloadRepository,
            bulkDownloadManager = bulkDownloadManager,
            streamRepository = streamRepository,
            settingsViewModel = settingsViewModel,
        )
        downloadManager.onDownloadsChanged = {
            downloadViewModel.loadDownloads()
            downloadCatalogueViewModel.loadCatalogue()
        }
        downloadViewModel.onFileDelete = downloadManager::deleteManagedFile
        downloadCatalogueViewModel.onFileDelete = downloadManager::deleteManagedFile

        val authController = DesktopAuthController(
            authClient = authClient,
            accountSessionCoordinator = accountSessionCoordinator,
            subscriptionViewModel = subscriptionViewModel,
        )

        val playbackEngineAndMode = run {
            val preferredMode = runCatching { com.torve.desktop.playback.PlayerModePreferences.read() }
                .getOrDefault(com.torve.desktop.playback.DesktopPlayerMode.VLC)
            val (engine, actualMode) = com.torve.desktop.playback.createPlaybackEngineWithFallback(preferredMode)
            println("Torve desktop player mode: requested=$preferredMode, actual=$actualMode")
            if (preferredMode != actualMode && actualMode == com.torve.desktop.playback.DesktopPlayerMode.VLC) {
                // MPV is an advanced/lab engine. If it is not available, normalize the
                // persisted preference back to the release-safe embedded VLC path so users
                // are not shown a warning on every launch.
                runCatching {
                    com.torve.desktop.playback.PlayerModePreferences.write(actualMode)
                }
            }
            com.torve.desktop.playback.PlayerEngineStartup.record(
                requested = actualMode,
                actual = actualMode,
            )
            engine to actualMode
        }
        val playbackEngine = playbackEngineAndMode.first
        val castController = (playbackEngine as? com.torve.desktop.vlc.VlcPlaybackEngine)?.let {
            com.torve.desktop.cast.DesktopCastController(it)
        }

        val runtime = DesktopRuntime(
            authClient = authClient,
            authController = authController,
            admissionController = DesktopShellAdmissionController(
                authController = authController,
                settingsViewModel = settingsViewModel,
                channelsViewModel = channelsViewModel,
                setupWizardViewModel = setupWizardViewModel,
                prefsRepo = prefsRepo,
                channelRepository = channelRepository,
                accountSessionCoordinator = accountSessionCoordinator,
            ),
            playerController = DesktopPlayerController(
                metadataRepository = metadataRepository,
                streamRepository = streamRepository,
                addonRepository = addonRepository,
                subtitleAggregator = subtitleAggregator,
                watchProgressRepository = watchProgressRepository,
                watchHistoryRepository = watchHistoryRepository,
                traktScrobbler = traktScrobbler,
                settingsViewModel = settingsViewModel,
                playbackEngine = playbackEngine,
                localFirstPlaybackRouter = koin.get<com.torve.desktop.lanlibrary.LocalFirstPlaybackRouter>(),
                telemetry = koin.get<com.torve.domain.telemetry.TelemetryEmitter>(),
            ),
            libraryDetailController = DesktopSearchController(
                metadataRepository = metadataRepository,
                ratingsEnricher = koin.get<com.torve.data.mdblist.RatingsEnricher>(),
                integrationSecretStore = koin.get<com.torve.domain.integrations.IntegrationSecretStore>(),
            ),
            searchController = DesktopSearchController(
                metadataRepository = metadataRepository,
                ratingsEnricher = koin.get<com.torve.data.mdblist.RatingsEnricher>(),
                integrationSecretStore = koin.get<com.torve.domain.integrations.IntegrationSecretStore>(),
            ),
            channelRepository = channelRepository,
            channelsViewModel = channelsViewModel,
            addonRepository = addonRepository,
            homeViewModel = homeViewModel,
            searchViewModel = searchViewModel,
            settingsViewModel = settingsViewModel,
            setupWizardViewModel = setupWizardViewModel,
            pandaSetupViewModel = pandaSetupViewModel,
            watchlistViewModel = watchlistViewModel,
            mediaFavoritesRepository = mediaFavoritesRepository,
            accountSessionCoordinator = accountSessionCoordinator,
            downloadViewModel = downloadViewModel,
            downloadCatalogueViewModel = downloadCatalogueViewModel,
            downloadManager = downloadManager,
            moviesCatalogViewModel = com.torve.presentation.catalog.CatalogViewModel(
                metadataRepo = metadataRepository,
                mediaType = "movie",
                watchProgressRepo = watchProgressRepository,
                keywordSearchService = koin.get<com.torve.data.ai.KeywordSearchService>(),
                prefsRepo = prefsRepo,
                ratingsEnricher = koin.get<com.torve.data.mdblist.RatingsEnricher>(),
                integrationSecretStore = koin.get<com.torve.domain.integrations.IntegrationSecretStore>(),
                catalogTopCache = koin.get<com.torve.data.catalog.CatalogTopCacheRepository>(),
            ),
            tvShowsCatalogViewModel = com.torve.presentation.catalog.CatalogViewModel(
                metadataRepo = metadataRepository,
                mediaType = "tv",
                watchProgressRepo = watchProgressRepository,
                keywordSearchService = koin.get<com.torve.data.ai.KeywordSearchService>(),
                prefsRepo = prefsRepo,
                ratingsEnricher = koin.get<com.torve.data.mdblist.RatingsEnricher>(),
                integrationSecretStore = koin.get<com.torve.domain.integrations.IntegrationSecretStore>(),
                catalogTopCache = koin.get<com.torve.data.catalog.CatalogTopCacheRepository>(),
            ),
            metadataRepository = metadataRepository,
            seeAllViewModel = koin.get<SeeAllViewModel>(),
            deviceGovernanceViewModel = koin.get<com.torve.presentation.device.DeviceGovernanceViewModel>(),
            castController = castController,
            statsViewModel = koin.get<com.torve.presentation.stats.StatsViewModel>(),
            setupIntentsViewModel = koin.get<com.torve.presentation.setup.SetupIntentsViewModel>(),
            setupWizardCoordinator = koin.get<com.torve.presentation.setup.SetupWizardCoordinator>(),
            onboardingDeepLink = koin.get<com.torve.desktop.ui.onboarding.DesktopOnboardingDeepLink>(),
            providerHealthInit = koin.get<com.torve.desktop.providerhealth.DesktopProviderHealthInit>(),
        )
        // Phase 3 Slice C: kick the LAN serving controller. It listens
        // to settings (lanServingEnabled) + auth state (sign-out hook)
        // and only binds when the user explicitly opts in.
        runCatching { koin.get<com.torve.desktop.lanlibrary.LanServingController>().start() }
            .onFailure { println("Torve desktop bootstrap: LAN controller start failed: ${it.message}") }

        // Prompt 9B: publish the desktop's LAN hub to the backend
        // registry whenever LAN-bind is on AND the user is signed in.
        // The publisher itself owns the start/stop logic; we just
        // kick its long-running observer.
        runCatching { koin.get<com.torve.desktop.lanlibrary.LanHubPublisher>().start() }
            .onFailure { println("Torve desktop bootstrap: LAN hub publisher start failed: ${it.message}") }

        // Prompt 10B: kick the IPTV recording service. It polls the
        // scheduler every 30 s for SCHEDULED rows whose start time has
        // arrived, opens the upstream HTTP stream, and writes the
        // bytes into the configured recordings root. Storage boundary
        // is enforced inside the service.
        runCatching { koin.get<com.torve.desktop.recording.DesktopRecordingService>().start() }
            .onFailure { println("Torve desktop bootstrap: recording service start failed: ${it.message}") }

        // Pre-cache the top ~1000 highly-rated titles per genre per
        // media type, AND pre-warm the disk image cache for the top
        // 100 posters per genre. By the time the user clicks any
        // genre chip, both metadata and posters are local — the click
        // renders instantly with no network round-trip.
        //
        // Construct directly (not via Koin) so we can wire the
        // desktop-side poster prefetcher. The shared single defaults
        // to no prefetch for platforms without an image cache.
        runCatching {
            val worker = com.torve.data.catalog.CatalogTopCacheWorker(
                repository = koin.get<com.torve.data.catalog.CatalogTopCacheRepository>(),
                posterPrefetcher = { urls ->
                    com.torve.desktop.ui.v2.components.prefetchImagesToDisk(urls)
                },
            )
            globalCatalogTopWorker = worker
            worker.start()
        }.onFailure { println("Torve desktop bootstrap: catalog top-cache worker start failed: ${it.message}") }

        println("Torve desktop bootstrap: DI startup succeeded")
        BootstrapState.Ready(runtime)
    }.getOrElse { throwable ->
        val detailText = buildString {
            appendLine(throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Bootstrap failure")
            appendLine(throwable.message ?: "No exception message")
            throwable.stackTrace.take(8).forEach { frame ->
                appendLine("at $frame")
            }
        }.trim()
        println("Torve desktop bootstrap failed:\n$detailText")
        BootstrapState.Failure(
            message = throwable.message ?: "Desktop bootstrap failed",
            details = detailText,
        )
    }
}

@Composable
private fun TorveDesktopPreview(
    bootstrapState: BootstrapState,
    releaseInfo: DesktopReleaseInfo,
    windowState: WindowState? = null,
    tray: com.torve.desktop.tray.DesktopSystemTray? = null,
    onExit: () -> Unit,
) {
    val savedThemeMode = when (bootstrapState) {
        is BootstrapState.Ready -> bootstrapState.runtime.settingsViewModel.state.collectAsState().value.themeMode
        else -> ThemeMode.SYSTEM
    }
    // Desktop default is dark, not "follow system". When the user hasn't
    // picked a theme yet we still get ThemeMode.SYSTEM from shared, but on
    // this platform we treat that as DARK. LIGHT and DARK explicit picks
    // pass through unchanged.
    val themeMode = if (savedThemeMode == ThemeMode.SYSTEM) ThemeMode.DARK else savedThemeMode

    TorveDesktopTheme(mode = themeMode) {
        val colors = TorveDesktopThemeTokens.colors
        val background = remember {
            Brush.verticalGradient(
                colors = listOf(colors.shellGradientTop, colors.shellGradientBottom),
            )
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(if (bootstrapState is BootstrapState.Ready) 0.dp else 24.dp),
            ) {
                when (bootstrapState) {
                    BootstrapState.Starting -> {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .width(520.dp),
                            color = colors.stageSurface,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(
                                    text = releaseInfo.appName,
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Starting shared desktop bootstrap...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.textSecondary,
                                )
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }

                    is BootstrapState.Ready -> {
                        DesktopRuntimePane(
                            runtime = bootstrapState.runtime,
                            releaseInfo = releaseInfo,
                            windowState = windowState,
                            tray = tray,
                            onExit = onExit,
                        )
                    }

                    is BootstrapState.Failure -> {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .width(760.dp),
                            color = colors.stageSurface,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(
                                    text = "Desktop bootstrap failed",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = colors.error,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = bootstrapState.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                DeveloperFailurePane(bootstrapState.details)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    bootstrapState: BootstrapState,
    releaseInfo: DesktopReleaseInfo,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = releaseInfo.appName,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${releaseInfo.versionLabel} • ${releaseInfo.channel}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when (bootstrapState) {
            BootstrapState.Starting -> {
                StateChip(
                    label = "DI Starting",
                    containerColor = Color(0x1F1E88E5),
                    dotColor = Color(0xFF1E88E5),
                )
            }

            is BootstrapState.Ready -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StateChip(
                        label = "DI Ready",
                        containerColor = Color(0x1F2E7D32),
                        dotColor = Color(0xFF2E7D32),
                    )
                    val authState by bootstrapState.runtime.authController.state.collectAsState()
                    AuthPhaseChip(authState.phase)
                }
            }

            is BootstrapState.Failure -> {
                StateChip(
                    label = "DI Failed",
                    containerColor = Color(0x1FB3261E),
                    dotColor = Color(0xFFB3261E),
                )
            }
        }
    }
}

@Composable
private fun DesktopRuntimePane(
    runtime: DesktopRuntime,
    releaseInfo: DesktopReleaseInfo,
    windowState: WindowState? = null,
    tray: com.torve.desktop.tray.DesktopSystemTray? = null,
    onExit: () -> Unit,
) {
    val authController = runtime.authController
    val admissionController = runtime.admissionController
    val playerController = runtime.playerController
    val shellState by admissionController.state.collectAsState()

    LaunchedEffect(authController, admissionController) {
        authController.start()
        admissionController.start()
    }

    DisposableEffect(authController) {
        onDispose {
            authController.dispose()
        }
    }

    DisposableEffect(playerController) {
        onDispose {
            playerController.dispose()
        }
    }

    DisposableEffect(admissionController) {
        onDispose {
            admissionController.dispose()
        }
    }

    DisposableEffect(runtime.libraryDetailController, runtime.searchController) {
        onDispose {
            runtime.libraryDetailController.dispose()
            runtime.searchController.dispose()
        }
    }

    DisposableEffect(runtime.downloadManager) {
        onDispose {
            runtime.downloadManager.dispose()
        }
    }

    when (val state = shellState) {
        DesktopShellState.Resolving -> RestoringPane(authState = authController.state.value)

        is DesktopShellState.SignedOut -> DesktopOnboardingShell(
            authState = state.authState,
            authController = authController,
            setupWizardViewModel = runtime.setupWizardViewModel,
            setupWizardCoordinator = runtime.setupWizardCoordinator,
            onboardingDeepLink = runtime.onboardingDeepLink,
            releaseInfo = releaseInfo,
            admission = null,
            onExit = onExit,
            onCompleteOnboarding = { Result.failure(IllegalStateException("Sign in before completing setup.")) },
        )

        is DesktopShellState.VerifyEmail ->
            com.torve.desktop.ui.onboarding.DesktopVerifyEmailScreen(
                authState = state.authState,
                authClient = runtime.authClient,
                authController = authController,
            )

        is DesktopShellState.Onboarding -> DesktopOnboardingShell(
            authState = state.authState,
            authController = authController,
            setupWizardViewModel = runtime.setupWizardViewModel,
            setupWizardCoordinator = runtime.setupWizardCoordinator,
            onboardingDeepLink = runtime.onboardingDeepLink,
            releaseInfo = releaseInfo,
            admission = state.admission,
            onExit = onExit,
            onCompleteOnboarding = runtime.admissionController::completeOnboarding,
        )

        is DesktopShellState.Main -> com.torve.desktop.ui.v2.V2App(
            authState = state.authState,
            authController = authController,
            setupIntentsViewModel = runtime.setupIntentsViewModel,
            onboardingDeepLink = runtime.onboardingDeepLink,
            providerHealthInit = runtime.providerHealthInit,
            playerController = runtime.playerController,
            windowState = windowState,
            libraryDetailController = runtime.libraryDetailController,
            searchController = runtime.searchController,
            homeViewModel = runtime.homeViewModel,
            searchViewModel = runtime.searchViewModel,
            settingsViewModel = runtime.settingsViewModel,
            pandaSetupViewModel = runtime.pandaSetupViewModel,
            channelsViewModel = runtime.channelsViewModel,
            channelRepository = runtime.channelRepository,
            addonRepository = runtime.addonRepository,
            watchlistViewModel = runtime.watchlistViewModel,
            mediaFavoritesRepository = runtime.mediaFavoritesRepository,
            accountSessionCoordinator = runtime.accountSessionCoordinator,
            downloadViewModel = runtime.downloadViewModel,
            downloadCatalogueViewModel = runtime.downloadCatalogueViewModel,
            downloadManager = runtime.downloadManager,
            moviesCatalogViewModel = runtime.moviesCatalogViewModel,
            tvShowsCatalogViewModel = runtime.tvShowsCatalogViewModel,
            metadataRepository = runtime.metadataRepository,
            seeAllViewModel = runtime.seeAllViewModel,
            deviceGovernanceViewModel = runtime.deviceGovernanceViewModel,
            castController = runtime.castController,
            tray = tray,
            statsViewModel = runtime.statsViewModel,
            setupRecoveryMessage = state.admission.setupRecoveryMessage,
        )
    }
}

@Composable
private fun StatusPanel(
    authState: DesktopAuthUiState,
) {
    Surface(
        color = Color(0xFFE8F0FE),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Session lifecycle",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = authState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
            )
            authState.authError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB3261E),
                )
            }
        }
    }
}

@Composable
private fun RestoringPane(
    authState: DesktopAuthUiState,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Restoring session",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = authState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SignInPane(
    authState: DesktopAuthUiState,
    authController: DesktopAuthController,
) {
    val isBusy = authState.phase == DesktopAuthPhase.LOADING

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = when (authState.phase) {
                    DesktopAuthPhase.AUTH_ERROR -> "Authentication error"
                    DesktopAuthPhase.LOADING -> "Working..."
                    DesktopAuthPhase.LOGGED_OUT -> "Sign in"
                    DesktopAuthPhase.RESTORING_SESSION -> "Restoring session"
                    DesktopAuthPhase.LOGGED_IN -> "Signed in"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = "Use your existing Torve account. Desktop reuses the shared backend auth client and session storage.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = authState.email,
                onValueChange = authController::updateEmail,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") },
                singleLine = true,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )

            OutlinedTextField(
                value = authState.password,
                onValueChange = authController::updatePassword,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") },
                singleLine = true,
                enabled = !isBusy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = authController::signIn,
                    enabled = !isBusy && authState.email.isNotBlank() && authState.password.isNotBlank(),
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp).height(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Sign In")
                    }
                }

                OutlinedButton(
                    onClick = authController::retrySessionRestore,
                    enabled = !isBusy,
                ) {
                    Text("Restore Session")
                }
            }
        }
    }
}

@Composable
private fun AuthPhaseChip(
    phase: DesktopAuthPhase,
) {
    val label = when (phase) {
        DesktopAuthPhase.LOGGED_OUT -> "Logged Out"
        DesktopAuthPhase.LOADING -> "Loading"
        DesktopAuthPhase.LOGGED_IN -> "Logged In"
        DesktopAuthPhase.AUTH_ERROR -> "Auth Error"
        DesktopAuthPhase.RESTORING_SESSION -> "Restoring"
    }
    val colors = when (phase) {
        DesktopAuthPhase.LOGGED_OUT -> Color(0x1F546E7A) to Color(0xFF546E7A)
        DesktopAuthPhase.LOADING -> Color(0x1F1E88E5) to Color(0xFF1E88E5)
        DesktopAuthPhase.LOGGED_IN -> Color(0x1F2E7D32) to Color(0xFF2E7D32)
        DesktopAuthPhase.AUTH_ERROR -> Color(0x1FB3261E) to Color(0xFFB3261E)
        DesktopAuthPhase.RESTORING_SESSION -> Color(0x1F6A1B9A) to Color(0xFF6A1B9A)
    }

    StateChip(
        label = label,
        containerColor = colors.first,
        dotColor = colors.second,
    )
}

@Composable
private fun StateChip(
    label: String,
    containerColor: Color,
    dotColor: Color,
) {
    Surface(
        color = containerColor,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .background(
                        color = dotColor,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                    ),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DeveloperFailurePane(
    details: String,
) {
    Surface(
        color = Color(0xFFF9DEDC),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    ) {
        Text(
            text = details,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF410E0B),
        )
    }
}
