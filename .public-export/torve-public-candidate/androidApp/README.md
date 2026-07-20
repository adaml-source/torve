# Android App Build And TV Validation

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Google Play, Google TV, and Fire TV store builds hide donation links by default.

## Firebase Configuration

The checked-in `google-services.json` files are public-safe placeholders for
local builds and source export review. Maintainers must provide real Firebase
configuration locally or through protected CI before production builds.

Before any public release, restrict Firebase keys by Android package name, SHA
certificate fingerprint, and API allowlist where applicable. Do not commit
service-account JSON or production-only credentials.

## Repo Fast Path

Use the repo-level wrapper when you want a single stable command surface for Codex or local automation:

```powershell
.\scripts\dev.ps1 -Target dev-google-tv
.\scripts\dev.ps1 -Target android-google-tv-release -SkipCrashlyticsUploads
```

## Build Commands

```bash
./gradlew :androidApp:assembleMobileDebug
./gradlew :androidApp:assembleTvDebug
./gradlew :androidApp:bundleMobileRelease
./gradlew :androidApp:bundleTvRelease
```

On Windows PowerShell use `.\\gradlew.bat` with the same task names.
Run commands from the repository root (the directory that contains `gradlew.bat`), not from `C:\WINDOWS\system32`.
If your build environment blocks outbound network, skip Crashlytics mapping upload:

```bash
./gradlew :androidApp:bundleMobileRelease :androidApp:bundleTvRelease \
  -x :androidApp:uploadCrashlyticsMappingFileMobileRelease \
  -x :androidApp:uploadCrashlyticsMappingFileTvRelease
```

For repo-level automation, prefer `scripts/dev.ps1` over ad hoc Gradle calls.

## Android TV Emulator Steps

1. In Android Studio, open Device Manager and create an `Android TV (1080p)` virtual device.
2. Pick a recent Google TV / Android TV system image (API 34+ recommended).
3. Start the emulator and install/run `tvDebug` (`Run > Select Device > TV emulator`).
4. Navigate only with keyboard D-pad keys (`Up/Down/Left/Right`, `Enter`, `Back/Esc`) and verify focus behavior.

## TV Smoke Test Checklist

- Launch app from TV launcher tile.
- Confirm banner renders in launcher details.
- App opens into TV home and first card has visible focus.
- Left rail is visible in collapsed mode.
- Press left from first card to move focus to rail and confirm rail expands with labels.
- Press right on rail to return to last focused content card.
- D-pad moves left/right within rows and up/down between rows.
- Up from first row reaches header actions and down returns to rails.
- Enter opens details for selected title.
- Details screen shows Play and Watchlist actions and Back returns to previously focused card.
- Start playback and verify remote controls:
  - `Center/Enter` toggles play/pause
  - `Left/Right` seeks
  - `Up` shows controls
  - `Back` closes overlays first, then exits player
- Verify app does not crash when Cast / Play Services are unavailable (Fire TV or emulator image without Play Services).

## Release Notes

- `mobile` and `tv` are separate product flavors under `formFactor`.
- TV flavor adds Leanback-required manifest overlay and banner resource.
- No ABI filters are configured, so default 64-bit packaging remains enabled.

## Phase 2 Migration Note

- New mobile screens: `Account` and `Devices` (from Settings quick links).
- TV Settings now includes pairing code flow and realtime debug status.
- Sync client reads backend URLs from BuildConfig:
  - `SYNC_BASE_URL`
  - `SYNC_WS_URL`
- Default dev values target emulator host `10.0.2.2:8080`. Change them in `androidApp/build.gradle.kts` for physical-device or LAN testing.

## Phase 2 Smoke Checklist

- Start backend (`server/docker compose up --build`) and run DB migration.
- Open mobile app, register a new account from Settings -> Account.
- Open TV app, go to Settings and confirm pairing code appears.
- On mobile Devices screen, claim the TV pairing code.
- Confirm TV transitions to paired state and websocket status becomes `connected`.
- Confirm Devices list shows both mobile and TV entries.
- Revoke a device from mobile and verify it disappears or shows revoked after refresh.

## Phase 3 Migration Note

- Mobile Search adds a TV handoff action to push the current query to a paired TV.
- Player adds a "Play on device" action that sends content and current position to a paired TV.
- TV now listens for realtime `SEARCH_PUSH` and `PLAYBACK_INTENT` events and auto-navigates accordingly.
- Player reports watch state periodically and on exit through `/watch_state/report`.

## Phase 3 Smoke Checklist

- Pair a mobile and TV device (Phase 2 flow).
- On mobile Search, enter a query and use the TV send action.
- Confirm TV navigates to Search with the query prefilled and results loaded.
- On mobile Player, start playback and use Play on device for a paired TV.
- Confirm TV opens details, starts playback, and seeks near the sent position.
- Confirm Back on TV returns to previous context with focus preserved.
- Confirm no crash when target TV is offline and event is delivered after reconnect.

## Phase 4 Migration Note

- Mobile Search and TV Search now include voice-to-text with explicit UI states:
  - `Listening`
  - `Processing voice input`
  - clear fallback message when voice input is unavailable
- Player now supports voice commands while playback is active:
  - `play`, `pause`, `resume`
  - `forward`, `rewind`
  - `search for <query>` to route directly into Search
- Voice command results are shown as a lightweight confirmation overlay in the player controls.

## Phase 4 Smoke Checklist

- Mobile Search:
  - Tap mic button, speak a query, verify query field updates and results load.
  - Verify `Listening` and `Processing voice input` states are visible.
  - On a device without voice recognizer, verify a clear fallback message is shown and app does not crash.
- TV Search:
  - Focus mic action with D-pad and trigger voice input.
  - Verify spoken query populates search input and results update.
  - Verify fallback message on devices without voice recognizer support.
- Player:
  - Trigger voice input and say `pause`, `play`, `forward`, `rewind`, and `search for action movies`.
  - Verify playback actions execute and a confirmation overlay appears.
  - Verify `search for ...` navigates to Search and applies the spoken query.
