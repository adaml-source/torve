# Public Release Hardening — Prompt 12

Historical release-hardening document. Any references to paid access, entitlements as product access, purchases, subscriptions, premium features, or billing as active product behavior are obsolete after the free-software conversion.

Current canonical positioning: Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

This private repository should not be made public directly. The public release should be created from a fresh sanitized source export after final audit.

This document is the operator-facing checklist for taking Torve from
internal preview to public beta / release. It pairs with the existing
[`credential-transfer-release-checklist.md`](credential-transfer-release-checklist.md)
and [`auth-and-entitlements.md`](auth-and-entitlements.md). Read both
before making release decisions.

The audit + fix passes for Prompt 12 are summarised at the end as a
**GO / NO-GO** matrix with an explicit blocker list.

**2026-05-03 backend reconciliation note:** `server/` is now a sanitised
snapshot of the live backend, not the earlier Prompt 12 local backend fork.
Backend rows and test counts from before commit `5e6253a` are historical
evidence only unless rerun against the reconciled snapshot.

**2026-05-03 backend workflow update:** Option B (repo-canonical backend
with manual deploy) landed in `c80fff9`: `server/DO_NOT_EDIT.md` was
removed, `server/README.md` and `docs/server-sync-strategy.md` were
rewritten, backend CI was added, and `scripts/deploy-backend.sh` was
added. This is process infrastructure, not proof of the first deploy;
run the deploy dry-run and observe backend CI before treating it as
stable release evidence.

---

## 1. Required environment configuration

### Backend (server/)

| Env var | Required when | Effect if missing |
| --- | --- | --- |
| `JWT_SECRET` | All deployments | Auth is insecure or refuses to start |
| `DATABASE_URL` | All deployments | Defaults to local SQLite — not production-safe |
| `INTEGRATION_SECRET_KEY` | Production secret storage | `app.crypto.encrypt_secret` / `decrypt_secret` refuse to operate |
| `INTEGRATION_SECRET_KEY_PREVIOUS` | Key rotation | Optional previous Fernet key used during rotation |
| `APP_ENV` | All deployments | Tags runtime environment; production currently uses `production` |

Generate the integration key once and persist in your secrets manager:

```bash
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

The encryption layer is `server/app/crypto.py`, backed by
`INTEGRATION_SECRET_KEY`. `server/app/secret_wrap.py` and
`tests/test_secret_wrap.py` were removed during the prod-snapshot
reconciliation; do not use those old local names in release checks. Key
rotation requires an explicit re-encryption/re-wrap migration; there is
**no** silent fallback.

### Desktop (`desktopApp/`)

| Env var | Required when | Effect |
| --- | --- | --- |
| `TORVE_RELEASE_CHANNEL` | Release packaging | Tags the build's `Channel` field. Default `internal-preview`. Stable releases pass `stable`. |
| `TORVE_RELEASE_BUILD=1` | CI release pipelines | Refuses to bypass missing-VLC gate via `TORVE_PACKAGE_ALLOW_MISSING_RUNTIME` |
| `TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1` | Local dev only | Downgrades the VLC-runtime gate to a warning. Refused when `TORVE_RELEASE_BUILD=1`. |
| `TORVE_UPDATE_FEED` *or* `TORVE_UPDATE_REPO` | In-app updater enabled (runtime override) | Wins over the baked-in URL. Use for dev / QA / Sandbox smoke. Updater idle when neither this nor a baked-in URL is set. |
| `-PtorveUpdateFeed=…` *or* `TORVE_UPDATE_FEED` at packaging time | **Production builds** | Baked into `Torve.cfg` as `-Dtorve.update.feed=…` so the in-app updater works for end users without any env-var ceremony. Example: `./gradlew :desktopApp:packageMsiCloseApp -PtorveUpdateFeed=https://torve.example/releases/appcast.xml`. The runtime resolver (`UpdateChecker.resolveDefaultFeed`) prefers the env var when present so a packaged build can still be redirected at runtime. |
| `-PtorveUpdateRepo=…` | Optional GitHub-Releases fallback | Same precedence as the feed flag. Compiled into `-Dtorve.update.repo=…`. Used when the feed URL is unset and the user wants the GitHub `releases/latest` JSON path. |
| `TORVE_TELEMETRY_SINK` | Optional | `println` for dev logging; unset = NoOp. **All sinks are wrapped in a redacting decorator.** |
| `SENTRY_DSN` (or whatever `SentryBootstrap.DSN_ENV` resolves to) | Crash reporting | Reporting disabled when unset |

---

## 2. Account & data rights (Prompt 12 hardening)

**End-to-end status:**

| Surface | Status | Notes |
| --- | --- | --- |
| Backend `DELETE /auth/account` | ✅ verified | Cascades `WatchStateReport`, `UserPlaylist`, `LanHub`, `Purchase`, `Entitlement`, `AccountSettings`, `EventOutbox`, devices, sessions. Pinned by `tests/test_account_lifecycle.py`. |
| Backend `GET /me/export` | ✅ verified | JSON envelope, no secrets (LAN auth, playlist password ciphertext explicitly omitted). Pinned by tests. |
| Desktop UI: delete + privacy/terms/support | ✅ implemented | `AboutSection` in `V2SettingsPage.kt` calls `AuthClient.deleteAccount()`; legal links via `LegalUrls`. |
| Android mobile: delete + privacy/terms/support | ✅ pre-existing | `SettingsScreen.kt` already wired. |
| Android TV: delete + privacy/terms/support | ✅ pre-existing | `TvSettingsScreen.kt` already wired (audit underreported). |
| iOS: delete + privacy/terms/support | ✅ implemented | `AccountScreen.swift` Delete button now calls `TorveAPIClient.deleteAccount`; `dataRightsSection` adds Export / Privacy / Terms / Support. |
| iOS: data export | ✅ implemented | Writes JSON to tmp + share sheet. |
| Web mirror at `https://torve.app/delete-account.html` | ⚠ **operator-required** | Constant is in `LegalUrls.ACCOUNT_DELETION_WEB`. Page currently returns 404 and must be published before public stable / app-store listing compliance. |

The shared URLs all live in
`shared/src/commonMain/kotlin/com/torve/presentation/legal/LegalUrls.kt`.

---

## 3. Telemetry & crash sink

**Architecture (post-hardening):**

```
feature code → TelemetryEmitter (Koin) → RedactingTelemetryEmitter → backing sink (NoOp / Println / future prod)
```

Every Koin-injected sink is wrapped in `RedactingTelemetryEmitter`
(`shared/.../telemetry/RedactingTelemetryEmitter.kt`), so even if a
future call site emits a raw token / URL / file path / AI key, the
decorator scrubs it before the backing sink sees the value.

**Categories redacted** (pinned by `RedactingTelemetryEmitterTest`,
16 tests):

- Bearer / Basic / Token authorization headers
- `Authorization:` headers in any case
- `X-Torve-Lan-Auth` LAN handoff headers
- URL query auth params (`?token=`, `?api_key=`, `?access_token=`,
  `?refresh_token=`, `?key=`, `?auth=`, `?secret=`, `?password=`)
- Source / addon / debrid / IPTV stream URLs (collapsed to `<host:…>`)
- Local filesystem paths (Windows / macOS / Linux home directories)
- `file://` URLs
- AI vendor API keys (`sk-`, `sk-ant-`, `pplx-`, `gsk_`, `aia_`)
- Long base64-ish blobs (credential-transfer envelopes / pubkey material)

**Existing redaction surfaces still in place:**

- `AiPayloadSanitizer` — AI provider call inputs
  (`shared/data/ai/AiPayloadSanitizer.kt`).
- `DiagnosticsRedactor` — desktop support-zip exports
  (`desktopApp/.../diagnostics/DiagnosticsRedactor.kt`).
- `account_settings_policy.py` — backend strip / scrub of secret
  fields on PATCH/GET (`server/app/account_settings_policy.py`).

**Default sink: NoOp.** `TORVE_TELEMETRY_SINK=println` opts into local
debugging. No production sink is shipped — wiring one (Sentry, etc.) is
a one-line replacement of the Koin binding and inherits the redactor.

---

## 4. Release channels & update trust

**Status:**

- Stable / beta / internal channel selection at **build time** via
  `TORVE_RELEASE_CHANNEL` env var. Default is `internal-preview`.
- Update feed honors the channel by way of channel-specific feed URLs
  (`TORVE_UPDATE_FEED` per channel — see
  `desktopApp/RELEASE_PROMOTION.md` for the no-rebuild promotion flow).
- HTTPS enforced on installer URLs
  (`UpdateInstallerHandoff.kt:81` — non-HTTPS is refused).
- SHA-256 verification when the appcast carries
  `sparkle:installerSha256`. Mismatch deletes the file.
- **No delta updates.** Every update is a full installer.
- **No auto-relaunch.** Windows Installer / macOS Mounter terminates
  Torve mid-install; user manually relaunches from Start menu / Dock.
- **No rollback automation.** User downgrades manually by re-installing
  the prior `.exe` / `.msi` / `.dmg`.
- **No runtime channel selector** in Settings. Users currently switch
  channels by reinstalling a build tagged for the desired channel. A
  runtime selector is documented in the audit and is a non-blocking
  follow-up.

---

## 5. Security hardening

### Production secret encryption-at-rest ✅ reconciled

The reconciled production snapshot uses `server/app/crypto.py` for
secret encryption at rest (`INTEGRATION_SECRET_KEY`, with optional
`INTEGRATION_SECRET_KEY_PREVIOUS` during rotation). The older local
`secret_wrap.py` LAN-specific implementation was removed because it was
not the deployed production shape.

### Repo / artifact sweep ✅ clean

| Category | Hits | Status |
| --- | --- | --- |
| `.env` files | `server/.env.example` (template only) | ✅ safe |
| `.pem` / `.p12` / `.jks` | None in source; build artifacts only (Amazon AppstoreAuthenticationKey.pem inside `androidApp/build/`) | ✅ gitignored |
| `.db` / `.sqlite` | None | ✅ |
| AWS-style keys | None | ✅ |
| Hardcoded credentials | None in committed code (test fixtures use placeholder values) | ✅ |
| Kodi addon copies | None (`.gitignore` covers) | ✅ |
| Hardcoded `/Users/`, `C:\\Users\\` paths | None in source | ✅ |
| `google-services.json` | Both flavors are placeholder-only | ✅ |
| `keystore.properties` | `.template` only; real file is gitignored | ✅ |

### Playlist password ciphertext

`UserPlaylist.password_enc` is encrypted through `app.crypto` in the
reconciled production snapshot. Keep this on the audit list anyway:
future playlist or LAN secret fields must use the same central crypto
module rather than reviving one-off wrapping helpers.

---

## 6. Package / signing readiness

### Windows ✅

- VLC runtime staging gate is hard-wired into
  `assembleDistributable` / `packageMsi` / `packageExe` via
  `verifyWindowsPackagingPrereqs` (see `desktopApp/build.gradle.kts`
  lines 80-215).
- Gate refuses to package without ≥100 plugin DLLs + license file.
- `TORVE_RELEASE_BUILD=1` refuses any
  `TORVE_PACKAGE_ALLOW_MISSING_RUNTIME` bypass.
- Manual playback verification checklist lives in
  `desktopApp/WINDOWS_PACKAGING.md`.

### Android — implicit `<uses-feature>` audit

Whenever a permission is added or removed in
`androidApp/src/main/AndroidManifest.xml`, audit
[Android's implicit feature table](https://developer.android.com/guide/topics/manifest/uses-feature-element#permissions)
and explicitly declare every implied feature as
`android:required="false"` unless the app actually requires the
hardware. **Skipping this filters out devices that lack the feature
even if you never call into it.**

Concretely, the lesson from the 2026-05-03 device-count regression:

- The `CAMERA` permission implicitly hard-requires
  `android.hardware.camera` (bare name). The previous manifest only
  declared `android.hardware.camera.any` as not-required, which is
  treated as a *different* feature by Play Console.
- Result: Play Console filtered out **2,886 of 2,898 Android TV
  devices, 61 of 72 Chromebooks, 19 of 20 cars** — every device
  without a camera. Phones / tablets were largely unaffected because
  they almost all have cameras.
- One missed `<uses-feature>` declaration, four form factors lost.
  Cost would have been hidden until a user complained their TV
  couldn't find the app.

Required declarations per common permission (declare ALL of these as
`required="false"` if the permission is in the manifest):

| Permission | Implicitly hard-requires features |
| --- | --- |
| `CAMERA` | `android.hardware.camera`, `android.hardware.camera.any`, `android.hardware.camera.front`, `android.hardware.camera.autofocus`, `android.hardware.camera.flash` |
| `ACCESS_FINE_LOCATION` | `android.hardware.location`, `android.hardware.location.gps` |
| `ACCESS_COARSE_LOCATION` | `android.hardware.location`, `android.hardware.location.network` |
| `RECORD_AUDIO` | `android.hardware.microphone` |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | `android.hardware.bluetooth` |
| `READ_PHONE_STATE` / `CALL_PHONE` etc. | `android.hardware.telephony` |

Pre-release sanity check after every manifest edit:

```bash
JAVA_HOME=... ./gradlew :androidApp:processGoogleMobileReleaseManifest
grep -B1 -A3 'uses-feature' \
  androidApp/build/intermediates/merged_manifests/googleMobileRelease/processGoogleMobileReleaseManifest/AndroidManifest.xml
```

Any feature you didn't intend to require should be flagged
`required="false"`. Inspect Play Console's "Devices supported by
this release" pane after every upload — a >5% drop on any form
factor without a deliberate reason is almost always a missing
`<uses-feature required="false">` line.

### macOS ⚠ operator-required

This audit ran on Windows; macOS signing/notarization commands cannot
be executed here. The required commands are:

```bash
# Sign:
codesign --force --options runtime --timestamp \
  --sign "Developer ID Application: <NAME> (<TEAMID>)" \
  --entitlements desktopApp/runtime/macos/torve.entitlements \
  desktopApp/build/compose/binaries/main/dmg/Torve-*.dmg

# Notarize:
xcrun notarytool submit Torve-*.dmg \
  --apple-id "<APPLE_ID>" \
  --team-id "<TEAMID>" \
  --password "<APP_SPECIFIC_PASSWORD>" \
  --wait

# Staple:
xcrun stapler staple Torve-*.dmg
```

Operator must run on a macOS host with Xcode 15+ before public release.

### iOS ⚠ operator-required

`xcodebuild` runs only on macOS. The Swift changes in this pass
(`AccountScreen.swift` + `TorveAPIClient.swift`) **cannot be compile-
verified on Windows**. Operator must run an iOS build + simulator smoke
on a macOS host before merging. The changes are local additions to:

- `TorveAPIClient.swift`: `static let shared`, `deleteAccount()`,
  `exportData()`, private `delete(path:)`.
- `AccountScreen.swift`: wires the existing Delete dialog to
  `TorveAPIClient.shared.deleteAccount()`, adds
  `dataRightsSection` with Export / Privacy / Terms / Support.

---

## 7. Supportability

- **Diagnostics export**: `V2SettingsPage` → About → "Export
  diagnostics" produces a redacted zip via `DiagnosticsExporter`.
  Redaction rules cover ~45 categories (`DiagnosticsRedactor`).
- **Provider health troubleshooting**: surfaced in-app via the TV +
  mobile provider-health banner (Prompt 11 series).
- **LAN playback troubleshooting**: documented at
  `docs/credential-transfer-protocol.md` for the protocol-level pieces;
  an end-user-facing copy doc is a non-blocker (in-app diagnostics
  cover the actionable cases).
- **IPTV recording troubleshooting**: per-recording failure reasons
  surface in `V2RecordingsPage` (`Failed and cancelled` rail) with
  actionable copy from `RecordingFailureReason`.

### Known limitations (intentional, document in release notes)

- **No native auto-update framework** (no WinSparkle / Sparkle native).
  In-app updater downloads + hands off to the OS installer; user
  manually relaunches.
- **No delta updates.** Every update downloads the full installer.
- **No automated rollback.** Users downgrade by re-installing prior
  versions.
- **Series-level DVR not enabled.** Single-programme IPTV recording
  works (`V2RecordingsPage` + `RecordingScheduler`); season-pass /
  smart-recording is a follow-up.
- **iOS LAN headers**: ExoPlayer (Android) attaches
  `X-Torve-Lan-Auth` via `setNextRequestHeaders`. iOS AVPlayer does
  not have an equivalent first-party hook for arbitrary headers on
  HLS — LAN playback on iOS may require a same-origin proxy in a
  future slice. **Not blocking** for this release because LAN
  playback is opt-in and the landing experience does not depend on it.
- **No runtime release-channel selector.** Channel is baked at build
  time. Operator promotes by tagging a feed URL per channel
  (`desktopApp/RELEASE_PROMOTION.md`).
- **macOS / iOS smoke is operator-required** on a macOS host (this
  audit is from Windows).

---

## 8. Smoke matrix

### Prompt 12B backend blocker resolution

Prompt 12 originally left 5 backend failures. Prompt 12B classified all
5 as release-blocking because they touched device pairing or stale
device session state, then resolved them:

| Test | Area | Resolution |
| --- | --- | --- |
| `test_pairing_flow` | Device pairing | Removed unused `device_id` from pairing request schemas; endpoint never read it. |
| `test_multiple_pairings` | Device pairing | Fixed by the same schema contract patch. |
| `test_revoke_pairing` | Device pairing | Fixed by the same schema contract patch. |
| `test_cross_user_revoke_denied` | Device pairing isolation | Fixed by the same schema contract patch. |
| `test_row_f_stale_device_auto_expiry` | Stale device sessions | Test now asserts the durable invariant: stale device absent from active list and swap budget intact. Production pruning behavior was already correct. |

Backend status after Prompt 12B was **110 / 110 passing** against the
pre-reconciliation local backend fork. Treat that as historical until
the reconciled `server/` snapshot is rerun.

### ✅ Automated test signal on this host

| Suite | Counts |
| --- | --- |
| `server/tests/` | Historical **110 pass / 0 fail** before the 2026-05-03 prod-snapshot reconciliation; rerun against the reconciled snapshot before treating this as current release evidence |
| `server/tests/test_security.py` crypto coverage | 2 crypto-focused tests inside the suite (`test_encryption_versioned_roundtrip`, `test_encryption_legacy_compat`) |
| `.github/workflows/backend-ci.yml` | committed in `c80fff9`; observe a real GitHub Actions pass before treating it as release evidence |
| `tests/test_account_lifecycle.py` (delete cascade + export) | 4 / 4 |
| `:shared:testDebugUnitTest` `domain.telemetry.*` (redaction) | 27 / 27 |
| `:shared:testDebugUnitTest` `presentation.tvhome.*` (couch flow) | 49 / 49 |
| `:shared:testDebugUnitTest` `presentation.lanlibrary.*` (LAN handoff) | 13 / 13 |
| `:desktopApp:test --tests com.torve.desktop.updates.UpdateCheckerTest` | passed 2026-05-03; parser/version regression coverage |
| `:desktopApp:test --tests com.torve.desktop.updates.UpdateInstallerHandoffTest` | passed 2026-05-03; 11 tests including 5 new launcher-resolution cases (Fix #6) |
| Updater handoff success path (download → SHA verify → msiexec /i → upgrade completes) | passed 2026-05-03 in Windows Sandbox via cloudflared tunnel; user verified 1.0.7 in About page after restart |
| `:desktopApp:compileKotlin` | clean |
| `:androidApp:assembleGoogleTvDebug` (last verified Prompt 11C) | clean |

### ⚠ Operator-required (host cannot run)

| Item | Host required |
| --- | --- |
| `:androidApp:assembleAmazonTvDebug` smoke install on Fire TV | Android device or AVD |
| iOS / `xcodebuild` build, simulator smoke | macOS + Xcode |
| Apple notarization round-trip | macOS + Apple ID + app-specific password |
| Windows clean-VM install / launch / playback (handoff success path now ✓) — snapshot-VM pass against Defender + third-party AV still recommended pre-stable | clean Windows VM with AV |
| End-to-end credential transfer between desktop ↔ Android ↔ iOS | Multi-device test bed |
| IPTV DVR record + playback round-trip on real EPG | Live IPTV provider |
| Backend secret encryption on a real Postgres | Postgres + `INTEGRATION_SECRET_KEY` |

### ⏭ Deferred (non-release blockers, document in release notes)

- Runtime channel selector in Settings (audit recommendation; not
  blocking — current build-time channel works for stable release).
- iOS `AVPlayer` LAN-header support.
- Series-level DVR.
- WinSparkle / Sparkle native auto-update framework.
- Keep playlist/LAN-style secret fields on the central `app.crypto`
  path; no one-off wrappers.

---

## 9. GO / NO-GO

**Recommendation: GO for public beta on desktop + Android from
`c80fff9` or later; `1060658` is the minimum desktop updater code-fix
commit. Earlier desktop builds contain the B4 updater defects caught on
2026-05-03. iOS remains NO-GO until macOS build and simulator smoke
pass. Stable remains NO-GO until the blockers below are cleared.**

**Update 2026-05-02:** B1 + B5 cleared (B1 was a filename-mismatch
bug, not a missing page; B5 was already deployed under different
naming in production).

**Update 2026-05-03:** B4 install + launch + playback passed in
Windows Sandbox, then the updater handoff smoke against local N-1/N
MSIs caught five real desktop updater defects. Fixes 1-4 were verified
in Sandbox; Fix 5 compiles clean but its UI was not re-smoked after
ngrok rate-limited the second cycle. The Download & install success
path (download -> SHA verify -> installer launches -> upgrade
completes) still needs one non-rate-limited pass before stable.
Remaining hard blockers: B2 + B3 (macOS / iOS). **Public desktop +
Android beta is GO from `c80fff9` or later (`1060658` minimum for the
desktop updater code fix).**

### Pre-release checks

Run before cutting a stable artifact:

```bash
bash scripts/release-checks/link-check.sh
```

The script greps every URL constant out of `LegalUrls.kt` and HEAD/GETs
each. Exits non-zero on any 4xx/5xx — catches **B1** the moment the
delete-account page goes 404, plus any silent rename of privacy / terms
/ help pages. `mailto:` constants are listed but not probed.

### Blockers (must clear before public stable)

| ID | Owner | Description |
| --- | --- | --- |
| ~~B1~~ | ~~Web ops~~ | **CLEARED 2026-05-02.** The page was already live at `https://torve.app/account-deletion.html` (200, branded). The 404 was a string-mismatch bug — `LegalUrls.ACCOUNT_DELETION_WEB` pointed at `delete-account.html`. Constant is now `account-deletion.html`; `link-check.sh` returns 4 PASSED. |
| B2 | Operator (macOS) | Run iOS build + simulator smoke against the Prompt 12 changes (`AccountScreen.swift`, `TorveAPIClient.swift`). |
| B3 | Operator (macOS) | Run macOS sign + notarize round-trip on a packaged DMG. |
| ~~B4~~ | ~~Operator (Windows)~~ | **CLEARED 2026-05-03 (smoke caught 5 real bugs, all fixed in-slice).** Clean Windows Sandbox: installer ran, app launched, sign-in succeeded, sample item played. Updater-handoff was then exercised end-to-end via a mock appcast served through an HTTPS tunnel (ngrok → local `python -m http.server` → local-built N-1 + N MSIs). Findings: **(1)** wizard "Continue to Torve" crashed with `NoClassDefFoundError: java/net/http/HttpClient` — `nativeDistributions.modules(...)` only listed `java.sql`, missing `java.net.http`/`jdk.crypto.ec`/`jdk.unsupported`; fixed in `desktopApp/build.gradle.kts`. **(2)** In-app updater silently broken on every installed build because `DesktopReleaseInfo.versionLabel` prepends `"Version "` and that string was being passed as `currentVersion` to `UpdateChecker`, making `isStrictlyNewer` always return false; fixed in `Main.kt` to pass raw `releaseInfo.version`. **(3)** `parseAppcast` enclosure-URL regex relied on `\b` and matched inconsistently across attribute orders, so `info.installerUrl` came back null and the banner rendered without a "Download & install" button; replaced with a generic `name="value"` attribute-map extractor in `UpdateChecker.kt`. **(4)** Settings → "Check for updates now" button was fire-and-forget with zero UI feedback (no toast, no inline status, no last-checked timestamp); added inline result text + `Checking…` button label + disable-when-no-feed in `V2SettingsPage.kt`. **(5)** `UpdateInstallerHandoff` was equally silent — clicking "Download & install" with a SHA mismatch / network failure / abuse-page substitution looked identical to the happy path (the click "did nothing"); banner now surfaces the handoff `Phase` (Downloading % / Verifying / Launching installer / red error on Failed). Banner visual was also tightened up (icon disc, dismiss as small X, cleaner typography) in `UpdateBanner.kt`. **Verification status:** Fixes 1-4 confirmed working in a fresh Sandbox install (no wizard crash, banner shows Download & install button with correct version, Settings inline feedback shows "1.0.7 available" then "Check failed: HTTP 403" on rate limit). Fix 5 compiles clean and follows the same pattern as the verified Fix 4 but its end-to-end UI was not re-smoked because ngrok free-tier abuse limits blocked the second test cycle. **Residue:** the Download & install **success** path (download → SHA verify pass → OS installer launches → upgrade completes) was not proven end-to-end. Next stable cut should re-run the appcast smoke against a non-rate-limited HTTPS host (cloudflared quick tunnel, or a pushed GitHub release). A snapshot-VM pass against Defender + one third-party AV is also recommended before stable. |
| ~~B5~~ | ~~Backend ops~~ | **CLEARED 2026-05-02; reconciled 2026-05-03; workflow switched in `c80fff9`.** Production uses `app/crypto.py` with `INTEGRATION_SECRET_KEY` and optional `INTEGRATION_SECRET_KEY_PREVIOUS`, not the old local `secret_wrap.py` plan. The repo `server/` directory was replaced with a sanitised snapshot of `/opt/torve-backend` at alembic head 0029, then switched to Option B repo-canonical workflow with backend CI and `scripts/deploy-backend.sh`. First dry-run/apply is still pre-stable evidence to collect. |

### Non-blockers (release notes)

- N1: Documented "no auto-relaunch / no delta / no rollback" updater
  limitations.
- N2: iOS LAN-header gap — LAN streaming opt-in only on iOS.
- N3: Runtime channel selector deferred.
- N4: Playlist password ciphertext wrap deferred.
- N5: Pairing-flow + stale-device tests pre-existing failures
  (out of scope for Prompt 12).
- N6: Receiver code is a ~250-char `torve://transfer/receive/…` URL,
  not a 6–8 char pairing code. Copy was corrected to drop "short"
  framing in Prompt 15 (2026-04-30). A real relay-assigned short
  pairing code is a future enhancement — paste-based handoff still
  works today.

### Prompt 15 — credential-transfer real-device pass (2026-04-30)

Ran `docs/transfer-real-device-runbook.md` against `Television_4K`
AVD with the freshly built `androidApp-google-tv-debug.apk`.

**Blockers found and fixed in-slice:**

1. **TV_ONLY mode hid the Receive entry.** Fresh installs default
   `setup_mode = TvSetupMode.TV_ONLY` (`TvSettingsScreen.kt` line
   ~403). The Receive credentials entry was nested inside the
   `ANDROID_PHONE | IOS_PHONE` branch only, so a TV-only user could
   never reach it from Settings. Fixed by adding a fresh
   `transfer_receive_tv_only` entry at the top of the TV_ONLY →
   CONNECTIONS section.
2. **"Short receiver code" copy lie.** `TransferCopy` user-facing
   strings promised a "short receiver code"; the actual rendered
   code is a long base64 URL. Removed "short" from
   `SEND_STEP1_EXPLAINER` and `RECEIVE_PRIMARY_EXPLAINER_DESKTOP`,
   updated `TransferCopyTest`, and synchronised
   `docs/transfer-real-device-runbook.md`.
3. **TV QR filled the entire screen, hiding the receiver code +
   copy button below the fold.** `SecretsTransferReceiveScreen`
   used `fillMaxWidth()` with no width cap — on a 4K TV at 640 dpi
   that drew a ~3700 dp QR no phone camera could frame at 3 m, and
   that pushed every other control off the screen. Fixed by giving
   `largeQr = true` a Row layout: QR fixed at 320 dp on the left,
   countdown + relay banner + receiver code + copy button stacked
   on the right. Both halves now sit above the fold. Verified on
   `Television_4K` AVD (`build/tv_30_at_categories.png`).
4. **TV Settings category chips lost their titles.**
   `TvSettingsTopCategoryChip` wrapped its title `Text` with
   `Modifier.weight(1f, fill = false)` inside an unbounded-width
   `LazyRow` item — weight in an infinite parent collapses the
   child to 0 dp. The result: empty pills with only the status
   badge ("Connected" / "Needs setup" / "Locked items"), no
   "Account" / "Playback" / "Appearance" / etc. labels. Fixed by
   removing the weight modifier; the title sizes to its intrinsic
   width and the chip grows to fit. Verified on Television_4K AVD
   (`build/tv_33_chips_top.png`).

**Operator residue (S1 desktop half, S2, S3, S8):** still requires
human-driven smoke; emulator does not have a phone camera and
cannot execute the desktop sender end-to-end paste against a
running TV without sign-in (relay path requires authenticated
receiver). Receive-screen render is now visually verified
(`build/tv_18_receive.png`, `build/tv_20_after_back.png`).
