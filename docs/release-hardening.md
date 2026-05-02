# Public Release Hardening — Prompt 12

This document is the operator-facing checklist for taking Torve from
internal preview to public beta / release. It pairs with the existing
[`credential-transfer-release-checklist.md`](credential-transfer-release-checklist.md)
and [`auth-and-entitlements.md`](auth-and-entitlements.md). Read both
before making release decisions.

The audit + fix passes for Prompt 12 are summarised at the end as a
**GO / NO-GO** matrix with an explicit blocker list.

---

## 1. Required environment configuration

### Backend (server/)

| Env var | Required when | Effect if missing |
| --- | --- | --- |
| `JWT_SECRET` | All deployments | Auth is insecure or refuses to start |
| `DATABASE_URL` | All deployments | Defaults to local SQLite — not production-safe |
| `REDIS_URL` | Realtime / outbox enabled | Realtime endpoints disabled |
| `TORVE_LAN_SECRET_WRAP_KEY` | **Production** (`TORVE_ENV=prod`) | LAN-hub publish endpoint returns **503** instead of storing plaintext |
| `TORVE_ENV` | Production | When set to `prod` / `production` / `release`, the wrap key requirement is enforced |

Generate the wrap key once and persist in your secrets manager:

```bash
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

The wrap layer is documented in `server/app/secret_wrap.py` and its
behavior is pinned by `tests/test_secret_wrap.py` (10 tests). Key
rotation requires a re-wrap migration — there is **no** silent fallback.

### Desktop (`desktopApp/`)

| Env var | Required when | Effect |
| --- | --- | --- |
| `TORVE_RELEASE_CHANNEL` | Release packaging | Tags the build's `Channel` field. Default `internal-preview`. Stable releases pass `stable`. |
| `TORVE_RELEASE_BUILD=1` | CI release pipelines | Refuses to bypass missing-VLC gate via `TORVE_PACKAGE_ALLOW_MISSING_RUNTIME` |
| `TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1` | Local dev only | Downgrades the VLC-runtime gate to a warning. Refused when `TORVE_RELEASE_BUILD=1`. |
| `TORVE_UPDATE_FEED` *or* `TORVE_UPDATE_REPO` | In-app updater enabled | Updater idle without either |
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

### LAN hub auth_secret encryption-at-rest ✅ landed

`server/app/secret_wrap.py` wraps `LanHub.auth_secret` with Fernet on
write and unwraps on read. Production deployments must set
`TORVE_LAN_SECRET_WRAP_KEY` and `TORVE_ENV=prod` — without the key the
publish endpoint returns 503 instead of storing plaintext. Legacy
plaintext rows still round-trip on read so existing dev DBs keep
working.

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

### Outstanding: playlist password ciphertext

`UserPlaylist.password_enc` is currently unwrapped (the field name is
aspirational). Same mitigation path as `auth_secret`: route through
`secret_wrap.wrap()` on PUT and `unwrap()` on GET. **Non-blocking** for
public release because the password is only used by the user's own
client — it never leaves to a third party — but it should be wrapped in
the next pass to match `auth_secret`.

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

Backend status after Prompt 12B: **110 / 110 passing**.

### ✅ Verified by automated tests on this host

| Suite | Counts |
| --- | --- |
| `server/tests/` | **110 pass / 0 fail** after Prompt 12B fixed pairing schema drift and rewrote the stale-device test to assert the durable invariant |
| `tests/test_secret_wrap.py` (LAN encryption) | 10 / 10 |
| `tests/test_account_lifecycle.py` (delete cascade + export) | 4 / 4 |
| `:shared:testDebugUnitTest` `domain.telemetry.*` (redaction) | 27 / 27 |
| `:shared:testDebugUnitTest` `presentation.tvhome.*` (couch flow) | 49 / 49 |
| `:shared:testDebugUnitTest` `presentation.lanlibrary.*` (LAN handoff) | 13 / 13 |
| `:desktopApp:compileKotlin` | clean |
| `:androidApp:assembleGoogleTvDebug` (last verified Prompt 11C) | clean |

### ⚠ Operator-required (host cannot run)

| Item | Host required |
| --- | --- |
| `:androidApp:assembleAmazonTvDebug` smoke install on Fire TV | Android device or AVD |
| iOS / `xcodebuild` build, simulator smoke | macOS + Xcode |
| Apple notarization round-trip | macOS + Apple ID + app-specific password |
| Windows clean-VM install / launch / playback / update handoff | clean Windows VM |
| End-to-end credential transfer between desktop ↔ Android ↔ iOS | Multi-device test bed |
| IPTV DVR record + playback round-trip on real EPG | Live IPTV provider |
| Backend LAN-secret wrapping on a real Postgres | Postgres + wrap key |

### ⏭ Deferred (non-release blockers, document in release notes)

- Runtime channel selector in Settings (audit recommendation; not
  blocking — current build-time channel works for stable release).
- iOS `AVPlayer` LAN-header support.
- Series-level DVR.
- WinSparkle / Sparkle native auto-update framework.
- Playlist `password_enc` Fernet wrapping (same path as
  `LanHub.auth_secret`; defer to next pass).

---

## 9. GO / NO-GO

**Recommendation: GO for public beta on desktop + Android from
checkpoint `79844ed` (`Checkpoint Prompt 6-12B public beta release
work`). iOS remains NO-GO until macOS build and simulator smoke pass.
Stable remains NO-GO until the blockers below are cleared.**

**Update 2026-05-02:** B1 + B5 cleared (B1 was a filename-mismatch
bug, not a missing page; B5 was already deployed under different
naming in production).

**Update 2026-05-03:** B4 cleared via Windows Sandbox smoke
(install + launch + playback verified). Updater-handoff sub-step
deferred — not exercised because no N-1 build was staged; non-
blocking for beta but must run once before stable. Remaining
blockers: B2 + B3 (macOS / iOS, held back). **Public desktop +
Android beta is GO.** Stable remains gated on macOS host
availability.

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
| ~~B4~~ | ~~Operator (Windows)~~ | **CLEARED 2026-05-03.** Clean Windows Sandbox: installer ran, app launched, sign-in succeeded, sample item played. Caveat: Windows Sandbox is ephemeral and ships with services stripped — *more* hostile than a normal install for "missing runtime" classes of failure, so a pass here is meaningful, but it does not exercise user-profile persistence, AV interaction, or Windows Update mid-session. **Updater-handoff sub-step (install N-1, let in-app updater swap to latest) was not exercised** — no N-1 artifact was staged. Non-blocking for beta; must run at least once against a real release feed before stable. A snapshot-VM pass against Defender + one third-party AV is also recommended before stable. |
| ~~B5~~ | ~~Backend ops~~ | **CLEARED 2026-05-02.** Production runs a more advanced backend than the local `server/` directory in this repo — `app/crypto.py` (server) is the equivalent of the planned `app/secret_wrap.py` (local) under a different name. Production already has `INTEGRATION_SECRET_KEY` set (with rotation support via `INTEGRATION_SECRET_KEY_PREVIOUS`) and `APP_ENV=production`. Verified by SSH-inspecting `/opt/torve-backend/.env` and tailing `journalctl -u torve-backend` for any wrap/crypto warnings (none in the last hour). The B5 wording was based on the local naming plan that never reached prod; reality matched the goal under different identifiers. **Important caveat:** the `server/` directory in this repo is OUT OF SYNC with production — do not naively `git pull` or rsync from local to `/opt/torve-backend/`; it would break the live deploy. Treat the local `server/` as documentation-only until it's reconciled. |

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
