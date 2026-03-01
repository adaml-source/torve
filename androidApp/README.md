# Android App Build And TV Validation

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
