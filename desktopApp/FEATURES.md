# Torve Desktop — Feature Inventory

Snapshot of every shipped desktop-only feature, organised so you can find:
the user-facing surface, the file that owns it, and the platform-specific
mechanism it relies on.

> Cross-platform features (catalog, search, watchlist, sync, account access,
> KMP business logic) live in `shared/` and apply to every Torve client.
> They are intentionally **not** listed here — this document is the desktop
> delta only.

---

## Window & Shell

| Feature | Where | Notes |
|---|---|---|
| Window state persistence (size, position, monitor) | `desktop/platform/DesktopWindowStateStore.kt` | Properties file under `desktopDataDir()`. Clamps to visible screens on launch so an unplugged second monitor doesn't strand the app off-screen. |
| Native menu bar | `desktop/Main.kt` (`MenuBar` block) | Compose Desktop maps to NSMenu on macOS automatically. Per-item `KeyShortcut` accelerators because Compose doesn't infer them. Now includes `File → Check for Updates…`. |
| Keyboard shortcuts | `desktop/Main.kt` + `desktop/ui/v2/V2App.kt:handleShortcut` | mac uses Cmd, others Ctrl. See **Cmd+/** for the in-app reference. |
| Cmd+K command palette | `desktop/ui/v2/palette/CommandPalette.kt` | Floating modal with search + action list. Keyboard nav (↑↓/Enter/Esc). Built-in actions: navigate to every destination, Settings, manage devices, Stats, check for updates, keyboard shortcuts, Sign Out, Quit. Fuzzy substring match on label/hint/keywords. |
| Cmd+/ keyboard help overlay | `desktop/ui/v2/help/KeyboardHelpOverlay.kt` | Cheatsheet of every shortcut grouped by surface. ⌘ vs Ctrl rendered correctly per OS. |
| Compact user-badge avatar (top-right) | `desktop/ui/v2/V2App.kt` | 36dp accent-coloured circle with initials. Click expands a dropdown with full identity + Settings / Sign Out / **Quit Torve**. Replaced an earlier wide email-pill that overlapped page chrome. |
| System tray | `desktop/tray/DesktopSystemTray.kt` | AWT SystemTray wrapper. Show / Play-Pause / Continue-Watching submenu / Quit. Now also exposes `notify(title, body)` for OS toasts (used by EPG reminders + update checks). |
| Default-to-dark theme | `desktop/Main.kt` | Falls back to dark when the user's stored mode is SYSTEM. |
| Close-to-tray (window close ≠ quit) | `desktop/Main.kt:onCloseRequest` | Closes the window without exiting; tray stays alive. Tears the player engine down first so libvlc audio doesn't keep playing in the background. |
| Global uncaught exception handler | `desktop/Main.kt` | Prints stack to stderr **and** routes through Sentry (see Diagnostics). |

## Diagnostics

| Feature | Where | Notes |
|---|---|---|
| Sentry crash reporting | `desktop/diagnostics/SentryBootstrap.kt` | DSN read at runtime from `TORVE_SENTRY_DSN`. No DSN → no SDK init → zero traffic. Crashes tagged with `thread.kind` (awt/compose/coroutine/vlc/other). PII off, max 50 breadcrumbs, no perf tracing yet. |
| In-app update checker | `desktop/updates/UpdateChecker.kt` | Auto-detects feed format. Source: `TORVE_UPDATE_FEED` (full URL) wins, then `TORVE_UPDATE_REPO` (`owner/name` for GitHub Releases). Parses both Sparkle/WinSparkle appcast XML and GitHub Releases JSON. |
| Update banner | `desktop/updates/UpdateBanner.kt` | Slim pill at top-center when a newer release is detected. **View release** opens the URL in the system browser; **Dismiss** hides per-session. |
| Update preferences | `desktop/updates/UpdateCheckerPreferences.kt` | Properties file under `desktopDataDir()`. Stores `autoCheckOnLaunch`. Toggle exposed in Settings → Diagnostics & Updates. |
| Diagnostics & Updates settings card | `desktop/ui/v2/settings/V2SettingsPage.kt` | Live read-only state of Sentry + update channel, env-var names with **Copy** buttons, "Check for updates now" trigger, auto-check toggle. |
| Sample appcast generator | `desktop/build.gradle.kts:generateSampleAppcast` | `./gradlew :desktopApp:generateSampleAppcast` writes `release/appcast.sample.xml` with the right shape for Sparkle/WinSparkle. |
| Verbose runtime logging | `TorveRuntimeDebug.verboseLoggingEnabled` (set at `Main.main`) | Boolean flag toggled at startup. |

## Player Surface (VLC)

The desktop video pipeline uses **VLC** via [VLCJ](https://github.com/caprica/vlcj)
with **callback rendering** (RV32 frames → BufferedImage → Compose `ImageBitmap`).
Heavyweight AWT Canvas was removed in favour of callback rendering so the
Compose chrome (controls, overlays) can sit above the video without being
painted over by a heavyweight peer.

| Feature | Where | Notes |
|---|---|---|
| VLC engine | `desktop/vlc/VlcPlaybackEngine.kt`, `VlcPlayerSession.kt` | One MediaPlayerFactory per engine. Sessions are 1:1 with playback runs. Command channel serialises native calls onto a single dedicated thread. |
| VLC runtime locator | `desktop/player/VlcRuntimeLocator.kt` | Probes for system VLC; falls back to bundled VLC under `desktopApp/runtime/windows/vlc/` on Windows. |
| Frame renderer (callback mode) | `desktop/vlc/VlcFrameRenderer.kt` | Double-buffered RV32 → ARGB BufferedImage. Compose polls latest frame at ~60Hz. |
| Compose player surface | `desktop/vlc/VlcComposePlayerSurface.kt` | All chrome: top bar, transport, slider, volume, subtitle/aspect/settings menus, cast, PiP, pin (always-on-top), fullscreen. Keyboard shortcuts: Space, F/F11, ←/→, ↑/↓, M, S. |
| Authoritative audio state | `VlcPlayerSession.kt`, `VlcEventBridge.kt:playing` | Bootstrap publishes the VALUES it set (not what `audio()` queries return). The `playing` event no longer overwrites with stale `audio()` reads — fixes the long-standing "muted X but sound plays" + "slider at 0 but full volume" race. |
| Always-on-top / pin | `VlcComposePlayerSurface.kt:toggleAlwaysOnTop` | Bottom-chrome push-pin button (filled when active). Sets `JFrame.isAlwaysOnTop = true`. |
| Picture-in-Picture | `desktop/ui/v2/playback/V2PipOverlay.kt` | Floating 420×236 overlay at top-right; main shell stays interactive behind it. Shares the `VlcFrameRenderer` with the full-screen surface. |
| Subtitle drag-and-drop | `desktop/dnd/SubtitleDropBus.kt` + `installSubtitleDropTarget` | AWT `DropTarget` on the JFrame root pane. Filters to recognised subtitle extensions. Active player surface subscribes via DisposableEffect. |
| Subtitle file picker | `VlcComposePlayerSurface.kt:loadSubtitleFile` | Native AWT FileDialog. |
| Inline OpenSubtitles search | `desktop/ui/playback/SubtitleSearchOverlay.kt` | "Search online…" item in subtitle dropdown opens modal. Calls `playerController.searchOnlineSubtitles()` which queries every installed Stremio subtitle addon. Language filter chips. Click result → attaches to the running session. |
| Multi-engine playback fallback chain | `desktop/playback/DesktopPlayerController.kt:resolveSelectedCandidate` | Walks `connectedDebridProviders` in `LinkedHashMap` order (active first). Bypasses debrid entirely for addon-hosted URLs. |
| Stream readiness probe (Panda Usenet) | `DesktopPlayerController.kt:awaitStreamReady` | Mirrors Android `PlayerScreen`. For addon-hosted URLs, polls every 15s up to 20 attempts (5 min), surfaces a [DesktopPreparingOverlay](src/main/kotlin/com/torve/desktop/ui/playback/DesktopPreparingOverlay.kt) with elapsed timer + cancel. |
| Casting (Chromecast / DLNA) | `desktop/cast/DesktopCastController.kt` | VLC renderer-discoverer (microdns). |
| Audio output device picker | `desktop/vlc/VlcPlayerSession.kt:availableAudioDevices` | Filters to highest-priority output backend (mmdevice/wasapi → directsound → waveout). |
| Equalizer | `VlcComposePlayerSurface.kt` (Settings → Equalizer panel) | VLC presets + manual band sliders. |
| Audio/subtitle delay sync | `VlcPlayerSession.kt:setAudioDelay`/`setSubtitleDelay` | H/J/K/L hotkeys. |
| Snapshot capture | `VlcComposePlayerSurface.kt:takeSnapshot` | Saves PNG to `~/Pictures/Torve/Snapshots/torve-<timestamp>.png`. |
| Aspect ratio / crop / scale | `VlcPlayerSession.kt:setAspectRatio` etc. | UI in Settings menu. |
| "No playable URL" recovery copy | `StreamRepositoryImpl.requirePlayableUrl` | Provider-aware error message instead of a generic blank. |

## Trailers

| Feature | Where | Notes |
|---|---|---|
| In-app trailer overlay | `desktop/ui/trailer/TrailerOverlay.kt` | Replaces the previous `Desktop.getDesktop().browse(...)` external-browser path. Spins up a dedicated `VlcPlaybackEngine` so trailer playback never disturbs an active main session. Released on dismiss. |
| Trailer chrome | `TrailerOverlay.kt:TrailerChrome` | Play/pause, time scrubber, time readout, mute toggle, volume slider. Plus quality dropdown (Auto/1080p/720p/480p/360p). Reactive — chrome state mirror keyed correctly so it doesn't go stale on quality switch. |
| Trailer fullscreen | `TrailerOverlay.kt:FullscreenTrailerLayout` | Real edge-to-edge video: video Box uses `fillMaxSize()`, chrome floats at the bottom on a translucent scrim, top-right floating cluster (Exit fullscreen / Open YouTube / Close). Shortcut: F / F11 toggle, Esc → exit fullscreen first then dismiss. |
| Trailer popup layout | `TrailerOverlay.kt:PopupTrailerLayout` | Surface auto-sizes to content (no dead space below chrome). Header row with title, fullscreen, open-on-YouTube, Close. |
| Trailer language preference | `shared/data/metadata/TmdbMappers.kt:pickBestTrailer` | TMDB `getDetail` requests `include_video_language=<userLang>,en`. Picker prefers official + non-English (= user's locale) → official English → any matching → any. |
| Quality switch keeps state | `TrailerOverlay.kt` resolve LaunchedEffect | Reuses the existing `VlcPlayerSession` and calls `play(newUrl, :start-time=<sec>)`. Position carries via VLC's `:start-time` media option; volume/mute carried explicitly across the URL swap. Chrome stays connected to the live state flow. |
| YouTube URL resolution chain | `desktop/trailer/YtDlpResolver.kt`, `YtDlpInstaller.kt`, `YouTubeResolver.kt` | (1) `yt-dlp` (auto-installed on first click — ~25MB from the official GitHub releases, cached under `desktopDataDir()`). (2) NewPipeExtractor as a fallback. (3) Raw VLC URL last-ditch. (4) "Open on YouTube" button on hard-failure. |

## Live TV / IPTV

| Feature | Where | Notes |
|---|---|---|
| IPTV playlist support (M3U + Xtream) | `shared/data/channels/ChannelRepositoryImpl.kt` | Cross-platform. |
| Live TV page | `desktop/ui/v2/live/V2LivePage.kt` | Search at top, playlist dropdown, recent-channels dropdown, view toggle (Channels / Guide / Favorites). Inline EPG-error banner with retry. Channel-list duplicate-key crash guarded with `itemsIndexed` keying. |
| EPG (XMLTV) parser | `shared/data/channels/EpgParser.kt`, `EpgParserDb.android.kt` (Android XmlPull → DB), `EpgParserDb.desktop.kt` (StAX → DB) | Desktop StAX impl mirrors Android: same caps, same channel-mapping pipeline, same batch flush rhythm, same skip categories. Hardened against XML bombs (DTD/external-entity/entity-expansion all disabled). |
| EPG fetch + ingest | `ChannelRepositoryImpl.kt:refreshEpg` + `GzipSupport.desktop.kt` | Auto-detects gzip via magic bytes (preferred) or `Content-Encoding` header. Counting-limit input stream against configured max-uncompressed budget. |
| EPG grid / Guide view | `desktop/ui/v2/live/V2EpgGrid.kt` | 12-hour window (1 behind, 11 ahead), 30-min ticks, hour labels. Now-indicator overlay. Catch-up support. Smart empty-state distinguishes "EPG arrived but no channels matched" vs "no EPG at all". |
| EPG programme context menu | `V2EpgGrid.kt:ProgrammeCell` | Click a programme cell: ▶ Play channel, ⏮ Watch from start (catchup if past + supported), ★ Add/Remove favorites, 🔔 Set/Cancel reminder (future only), 🔍 Find other airings, 📋 Copy show info. Programme info footer for context. |
| "Find other airings" sheet | `V2EpgGrid.kt:OtherAiringsSheet` | Walks every channel × every cached programme; case-insensitive title equality. Sorted by start time, LIVE badge on currently-airing copies. |
| EPG reminders (persisted) | `desktop/reminders/EpgReminderStore.kt` | JSON file under `desktopDataDir()/epg_reminders.json`. Atomic-ish write via temp + rename. V2LivePage loads on first composition, prunes >10min stale, re-arms an in-flight notification coroutine for every surviving reminder. Fires tray notification 1 minute before air time. |
| Channel keypad | `desktop/ui/v2/live/ChannelKeypadStateMachine.kt` | Type a channel number, 2.5s idle commits + jumps. Pure state machine, unit-tested. |
| Recent channels dropdown | `V2LivePage.kt:RecentChannelsDropdown` | Up to 15 recently-viewed channels. |
| Catch-up (timeshift) | `ChannelRepositoryImpl.kt:resolveCatchupUrl` | XMLTV catch-up template / Xtream timeshift. Wired into Guide click handler. |
| Channel favorites | `ChannelsViewModel.toggleFavorite` | Persisted across restarts. ★ badge on EPG grid + channel list. |
| Hidden categories / channels | `ChannelsViewModel.toggleHiddenCategory` etc. | User-curated allow/deny lists. |

## Browse / Detail / Search

| Feature | Where | Notes |
|---|---|---|
| V2 catalog shell | `desktop/ui/v2/V2App.kt` | Sidebar nav + page surface. |
| V2 detail page | `desktop/ui/v2/detail/V2DetailPage.kt` | Full-bleed backdrop, hero metadata, summary expand/collapse, similar items rail. Trailer (in-app overlay), Play, Sources, Watchlist, Download. |
| Hover-preview on poster cards | `desktop/ui/v2/components/V2Components.kt:V2PosterCard` | Crossfade poster → backdrop on hover via `graphicsLayer { alpha = ... }` + `animateFloatAsState`. Bottom scrim with overview snippet fades in synchronously. Wired across Home, Movies, Shows, Search, Library, Detail "Similar Items", See-All, Person credits. Falls back gracefully when caller passes null backdrop. |
| Watchlist heat map | `desktop/ui/v2/library/V2LibraryPage.kt` | Visual density of recently-added watchlist items. |
| Search (catalog) | `desktop/ui/v2/search/V2SearchPage.kt` | Multi-paged TMDB search. Sensitive-query gate enforced server-side via `ContentPolicyFilter`. |
| Person page | `desktop/ui/v2/person/V2PersonPage.kt` | Filmography (TMDB credits) with hover-preview poster cards. |
| Stats page | `desktop/ui/v2/stats/V2StatsPage.kt` | Watch history aggregates. |

## Local Library (Phase 2)

| Feature | Where | Notes |
|---|---|---|
| Local library repository | `desktop/library/LocalLibraryRepository.kt` | File-backed JSON: `local_library_folders.json` + `local_library_entries.json` under `desktopDataDir()`. Atomic-ish writes via temp + rename. |
| Folder management | `desktop/ui/v2/library/LocalLibraryView.kt` | Add folder (native AWT FileDialog on macOS, JFileChooser on Win/Linux) → recursive scan → persist. Per-folder file counts, **Remove** folder, **Rescan all**. |
| Recursive video scanner | `LocalLibraryRepository.kt:scanFolder` | `walkTopDown` filtered to recognised extensions (mp4 mkv avi mov m4v webm wmv flv mpg mpeg m2ts ts vob ogv 3gp divx xvid). Skips dot-files / dot-dirs. Capped at 5,000 entries per folder. |
| Filename → metadata heuristic parser | `LocalLibraryRepository.kt:LibraryFilenameParser` | Strips release-group / quality / codec tags, extracts title + year + S01E01 series marker. Handles common P2P / Plex naming. |
| TMDB metadata enrichment | `LocalLibraryRepository.kt:enrichAllNeeded` | Walks unmatched entries on a polite single-flight cadence (80ms inter-call). Calls `MetadataRepository.searchMultiPaged`, prefers a year-matching result. Persists progress as it goes (cancel-safe). 24h backoff on failed matches. Triggered automatically from V2App when folders/entries change. |
| Series grouping | `LocalLibraryView.kt:LocalLibraryGroupedList` | Episodes auto-fold under a series header keyed by `tmdbId` (or matched title). Click to expand season-sorted episode list. Movies render flat below. |
| Folder-grouping toggle | `LocalLibraryView.kt:LibraryBrowseControls` | Switch between **Series** grouping and **Folder** grouping. Folder mode shows folder header rows (collapsible) with **Open** action. |
| Search + sort | `LocalLibraryView.kt:LibraryBrowseControls` + `applyFilterAndSort` | Substring filter on display name + matched title + path. Sort by Recently added / A→Z / Year / Size (largest). |
| Composition badges | `LocalLibraryView.kt` | Header shows file count, movie count, distinct series + episode count, total disk usage. |
| Per-row actions | `LocalLibraryView.kt:LocalEntryRow` | Click row → play in desktop player (`playerController.playLocalFile`). **Reveal** button opens the parent folder in the OS file manager. Size badge shows compact bytes. |

## Settings & Integrations

| Feature | Where | Notes |
|---|---|---|
| Settings page | `desktop/ui/v2/settings/V2SettingsPage.kt` | Account, Playback, Subtitles, Display, Add-ons, Playlists, Trakt, SIMKL, Plex, Diagnostics & Updates, Storage, Maintenance. Torve is free software with no subscription section. |
| Onboarding shell | `desktop/ui/onboarding/DesktopOnboardingShell.kt` | First-run wizard. |
| Panda guided setup | `desktop/ui/panda/DesktopPandaSetupScreen.kt` | Multi-step Panda onboarding. |
| Trakt / SIMKL OAuth | `V2SettingsPage.kt` | Web-based OAuth + manual code entry. |
| Plex integration | `V2SettingsPage.kt` | Library mount + watch progress sync. |
| Playlist add (M3U / Xtream) | `V2SettingsPage.kt` | URL paste or file pick. |
| Add-on add (Stremio addons) | `V2SettingsPage.kt` | Manifest URL paste. |
| Manage devices | `desktop/ui/v2/device/V2ManageDevicesScreen.kt` | Cross-device active-device list. |
| Device limit reached | `desktop/ui/v2/device/V2DeviceLimitReachedScreen.kt` | Technical/account device-management surface. Device limits are not paid slots. |
| Access state | KMP `SubscriptionRepositoryImpl` compatibility surface | Free/default access for authenticated active accounts; historical billing compatibility does not gate features. |

## Storage & Identity

| Feature | Where | Notes |
|---|---|---|
| App data directory | `desktop/platform/DesktopDeviceIdProvider.kt:desktopDataDir()` | OS-conventional dir (macOS `~/Library/Application Support/Torve`, Windows `%APPDATA%\Torve`, Linux `~/.config/torve`). |
| Stable device id | `DesktopDeviceIdProvider.kt` | Two files: installation-id (per-install), stable-device-id (per-machine). |
| Secret store (file-backed) | `desktop/security/DesktopFileSecretStore.kt` | `desktop-secrets.properties`. |
| SQLDelight database | `shared/desktopMain/.../DatabaseDriverFactory.desktop.kt` | Per-user file under app data dir. Walks SQLDelight migrations on existing DBs via column-introspection probe. |
| Image cache | `desktop/ui/components/TorveStreamingComponents.kt` (Coil) | Backed by `desktopDataDir()/image-cache`. |
| Persisted reminder store | `desktop/reminders/EpgReminderStore.kt` | See Live TV section. |
| Persisted local library | `desktop/library/LocalLibraryRepository.kt` | See Local Library section. |
| Update preferences | `desktop/updates/UpdateCheckerPreferences.kt` | See Diagnostics section. |

## Build & Packaging

| Feature | Where | Notes |
|---|---|---|
| Compose Desktop | `desktopApp/build.gradle.kts` | Compose 1.7.3, Material 3, currentOs target. |
| Native installer scaffolding | `desktopApp/build.gradle.kts:nativeDistributions` | All targets declared: Windows EXE/MSI, macOS DMG, Linux DEB/AppImage. Per-OS metadata (UpgradeUUID, bundleID, Linux package name, app categories). macOS code-signing + notarization wired through `TORVE_MAC_*` env vars. Run `./gradlew :desktopApp:packageDmg` / `packageMsi` / `packageDeb` / `packageAppImage` on the appropriate host. |
| Bundled VLC runtime (Windows) | `desktopApp/runtime/windows/vlc/` | Optional drop-in libvlc + plugins so end-users don't need a system VLC install. |
| jpackage prereq check | `desktopApp/build.gradle.kts:verifyWindowsPackagingPrereqs` | Validates a JDK with jpackage exists; gates package tasks. |
| Sample appcast generator | `desktopApp/build.gradle.kts:generateSampleAppcast` | `./gradlew :desktopApp:generateSampleAppcast` writes `release/appcast.sample.xml`. Edit version + URL, host on CDN, set `TORVE_UPDATE_FEED` to enable in-app update detection. |
| WINDOWS_PACKAGING.md | `desktopApp/WINDOWS_PACKAGING.md` | Manual packaging checklist. |

---

## Known Gaps

These are documented separately from "features" because they are
intentionally **not** present yet — listed here so future contributors don't
hunt for missing wiring.

- **MPV engine path** — only VLC ships today. The KMP `PlayerEngine` interface
  exists but desktop only has `VlcPlaybackEngine` registered. MPV bring-up is
  the highest-impact remaining Phase 2 item; multi-week effort (libmpv per-OS
  bundling, JNI bridge, codec parity testing).
- **Code-signing + native auto-update fetch** — the update checker shows
  banners and the appcast format is supported, but actually downloading and
  applying an update requires Sparkle (macOS) / WinSparkle (Windows) wired
  via JNA, plus Developer ID / Authenticode certs. Half-week + ops.
- **SMB / NFS / WebDAV library mounts** — local filesystem libraries work
  today; network mounts need jcifs-ng (SMB), nfs4j (NFS), Sardine (WebDAV).
- **TMDB episode-title resolution** — local library shows "S01E01"; episode
  names need an extra TMDB call per show, which the Phase 2 enrichment loop
  doesn't do yet.
- **Hover video previews** — catalog cards autoplay backdrop crossfade only,
  not a video clip. Real video previews would need yt-dlp + autoplay
  infrastructure for every card; not justified vs the cost.
- **Hardware-accelerated thumbnail/preview generation** — depends on MPV.
- **HDR tone-mapping** — depends on MPV.
- **Sentry breadcrumbs from playback path** — the SDK is wired but
  breadcrumbs aren't emitted from major user actions yet.

---

## Audit / Adding Entries

When adding a new desktop-only feature:
1. Land the code.
2. Append a row to the relevant table above. Each row needs the surface
   name, the file path that owns it, and one short note about the platform-
   specific mechanism (AWT / VLCJ / jpackage / etc).
3. If the feature has a known gap, list it in **Known Gaps** so the next
   reader doesn't look for it.

Cross-platform features (catalog metadata, watchlist, etc.) belong in the
shared-module docs, not here.
