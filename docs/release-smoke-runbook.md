# Release Smoke Runbook

Historical smoke runbook. Any references to purchase flows, paid entitlements, premium access, subscriptions, or billing as active product behavior are obsolete after the free-software conversion.

Current canonical positioning: Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

Runbook the operator walks before tagging a stable release. Builds on
`docs/smoke/real-device-matrix.md` (Prompt 20's beta harness) and adds
the stable-release coverage Prompt 21 names: routing determinism,
transfer-relay fallback, IPTV stale-error guard, source-picker tiers,
LAN-header round trip, cellular guard, and cold-restart truth.

## Conventions

- Every case has: **Goal**, **Steps**, **Expected**, **Failure triage**.
- "Operator-only" means the case requires a human (UI walk, real
  cellular/Wi-Fi swap, real TV in a living room, real Windows VM). Mark
  these clearly so they aren't silently skipped.
- "Host-runnable" means a script or `adb` command can produce evidence
  this host can capture without a human.
- Run cases in numeric order; later cases assume earlier ones passed.
- Capture failure evidence into `smoke-results/YYYY-MM-DD-<build>-<operator>.md`
  using `docs/smoke/result-template.md`.

## Environment matrix

| Surface         | Required                                                | This-host status |
| --------------- | ------------------------------------------------------- | ---------------- |
| Mobile (Android)| Real phone OR `emulator-XXXX` with `leanback=false`     | `emulator-5556`  |
| TV (Android)    | Fire TV / Android TV OR `emulator-XXXX` with `leanback=true` | `emulator-5554` |
| Desktop         | `desktopApp/build/distributions/...` from `:desktopApp:packageDistributionForCurrentOS` | run on demand |
| Backend         | `api.torve.app` reachable                               | host-checkable   |
| Windows clean VM| Snapshot-restored Windows install                       | operator-only    |

## Case index

|  # | Case                                              | Mode            |
| -- | ------------------------------------------------- | --------------- |
| 1  | Fresh-account signup                              | operator-only   |
| 2  | Email-verification state holds until verified     | operator-only   |
| 3  | Existing configured account sign-in               | operator-only   |
| 4  | First-run setup choice (wizard OR manual)         | operator-only   |
| 5  | Phone signs in TV via QR                          | operator-only   |
| 6  | TV receives credentials from phone or desktop     | operator-only   |
| 7  | Phone receives credentials from desktop           | operator-only   |
| 8  | Transfer relay unavailable → manual fallback works| operator-only   |
| 9  | IPTV playlist loads with no stale "couldn't load" | operator-only   |
| 10 | EPG zero strict matches → informational, not RED  | operator-only   |
| 11 | TV Home → known available item → one OK plays     | operator-only   |
| 12 | Detail source picker shows Local + LAN + Provider | operator-only   |
| 13 | LAN playback survives the auth-header round trip  | operator-only   |
| 14 | Cellular guard suppresses LAN when Wi-Fi-only on  | operator-only   |
| 15 | Clean app restart preserves source status         | operator-only   |
| H1 | Build compile gates                               | host-runnable   |
| H2 | Mobile + TV install + version verification        | host-runnable   |
| H3 | First-launch crash watch                          | host-runnable   |
| H4 | Public legal/help URL resolution                  | host-runnable   |
| H5 | Backend test suite                                | host-runnable   |

---

## 1. Fresh-account signup

- **Goal:** new account creation completes and lands the user in the
  email-verification state, never directly into Home or Setup.
- **Steps:**
  1. Open Torve on a fresh install.
  2. Tap **Create account** → enter a fresh email + password.
  3. Submit.
- **Expected:** verify-email screen appears immediately; copy says to
  check inbox.
- **Failure triage:** if it lands on Home → routing regression
  (`MobilePostAuthRouter.decide` may be missing the new
  `AuthEvent.Registered` branch). Bug title: "Signup bypasses verify
  screen". Owner: shared/presentation/session.

## 2. Email-verification state holds until verified

- **Goal:** the verify-email screen does not let the user past until
  the verification link is clicked.
- **Steps:** with the account from Case 1, tap **I've verified** before
  clicking the email link; expect to remain on verify-email or get a
  "still not verified" toast.
- **Expected:** user stays on verify-email; clicking link in inbox
  then advances them.
- **Failure triage:** if the app advances without verification →
  `verifyTokenIsCurrent` not being called or `is_verified` not set.
  Bug title: "Verify-email gate bypassable". Owner: shared/data/auth +
  backend `/auth/me`.

## 3. Existing configured account sign-in

- **Goal:** a returning configured user lands on Home, never on Setup
  Wizard, never on Manage Devices unless device-cap is real.
- **Steps:** install fresh; sign in with a known-good account that has
  active devices and configured providers.
- **Expected:** post sign-in lands on Home within 3s.
- **Failure triage:** if it lands on Setup Wizard → check
  `mobileOnboardingRequired`; if it lands on Manage Devices →
  `MANAGE_DEVICES_REASONS` may be including `device_not_registered`
  again. Bug title matches landing screen.

## 4. First-run setup choice (wizard OR manual)

- **Goal:** verified-but-unconfigured user sees a clear choice between
  wizard and manual; manual exit drops to Home.
- **Steps:** create a fresh account and verify it; on the choice
  screen, tap **Set up manually**.
- **Expected:** choice screen shows two options; manual lands on Home
  with empty Library; wizard lands at the wizard's first step and is
  exitable from any step.
- **Failure triage:** if no choice screen → `decide()` jumping
  straight to wizard; if exit returns to wizard → wizard not closing
  properly.

## 5. Phone signs in TV via QR

- **Goal:** QR claim flow against the deployed `api.torve.app/pairing/signin/*`
  routes works end-to-end.
- **Steps:**
  1. TV: Settings → **Sign in with your phone** → QR appears.
  2. Phone (signed in): Settings → **Sign in a TV with this phone**.
  3. Scan QR.
- **Expected:** TV's Settings header switches to signed-in within
  ~10s; phone's Manage Devices lists the new TV.
- **Failure triage:** TV-side filter: `TvPairingSignIn`, `PairingApi`;
  phone-side: `SyncCoordinator`. If phone shows "not signed in",
  re-check `authClient.isSignedIn` flow.

## 6. TV receives credentials from phone or desktop

- **Goal:** TV imports a sealed credential bundle either via relay or
  manual paste.
- **Steps:**
  1. Phone or desktop: Settings → **Send credentials to another device**.
  2. TV: Settings → **Receive credentials**.
  3. If relay live: bundle auto-applies. Else: paste sealed code.
- **Expected:** banner reads "Imported"; IPTV / Panda credentials
  appear in TV's settings.
- **Failure triage:** TV logcat `TransferReceiver`; sender logcat
  `TransferSender`. If "Automatic transfer unavailable" → relay
  unreachable; manual paste is the fallback (Case 8).

## 7. Phone receives credentials from desktop

- **Goal:** desktop-to-phone transfer works (mirror of Case 6 with
  swapped roles).
- **Steps:** same as 6 with phone in the receiver role.
- **Expected:** as 6.
- **Failure triage:** as 6.

## 8. Transfer relay unavailable → manual fallback works

- **Goal:** when the relay is unreachable, manual paste is the path
  forward and the diagnostics screen says so in user language.
- **Steps:**
  1. On phone, simulate relay unreachable: airplane-mode-on the phone
     for 3s, then back to Wi-Fi (or block `api.torve.app` in the
     emulator's hosts).
  2. Settings → **Transfer diagnostics** → **Probe relay now**.
  3. Try **Send credentials** anyway; expect manual-paste path to work.
- **Expected:** diagnostics pill reads "Manual fallback only" /
  "Network error" / "Sign in needed" depending on cause; help text
  references "manual paste fallback"; manual sender flow still
  produces a sealed code that the receiver imports.
- **Failure triage:** if diagnostics still says "unknown" or "reachable"
  during a known outage → `TransferDiagnosticsViewModel` probe path;
  if manual paste fails → `SecretsTransferReceiverViewModel` decode
  path.

## 9. IPTV playlist loads with no stale "couldn't load" copy

- **Goal:** if the playlist parsed previously (channels populated),
  the IPTV row in Status & Repair must read "X channels loaded", not
  "Couldn't load …", regardless of stale `state.error` flag.
- **Steps:**
  1. Add a playlist that initially fails (bad URL); confirm RED row.
  2. Edit the URL to the working one; refresh.
- **Expected:** row flips to GREEN with channel count. Re-opening
  Settings does not bring the RED back.
- **Failure triage:** see `IptvProviderHealthChecker` lines 56–73;
  the `hasLoadedContent → GREEN` branch must run before the
  `parseError != null` branch.

## 10. EPG zero strict matches → informational, not RED

- **Goal:** strict-EPG-zero-match with channels playable via name
  fallback shows GREEN with "0 of N matched, runtime falls back to
  name match", not RED.
- **Steps:** add a playlist whose channels and the EPG XMLTV file
  have no matching `tvg-id`s.
- **Expected:** Status & Repair shows two rows — playlist GREEN,
  EPG GREEN with informational copy.
- **Failure triage:** check `iptvEvidenceFrom` in
  `ProviderEvidenceBuilders.kt`: the `epgMatchedCount == 0 + channels
  loaded` branch must produce `ProviderHealthStatus.GREEN`.

## 11. TV Home → known available item → one OK plays

- **Goal:** TV one-OK autoplay (Prompt 11B) — focusing a Continue-
  Watching tile and pressing OK starts playback without a stream
  picker for the BEST tier.
- **Steps:** TV: Home → focus a Continue-Watching item with a known
  Provider source → press OK once.
- **Expected:** player opens and plays within ~3s.
- **Failure triage:** TV logcat `TvSourcePicker`, `PlayerScreen`;
  `TvSourcePicker.autoPlayBest` must return the route, not null.

## 12. Detail source picker shows Local + LAN + Provider

- **Goal:** Detail screen exposes all three tiers when each is
  available; mobile + TV.
- **Steps:** open Detail for an item with a local file, an active
  desktop LAN library, and a provider-resolved stream.
- **Expected:** picker lists Local (BEST), LAN (FALLBACK), Provider
  (FALLBACK). Selecting any plays without dropping back to picker
  except on failure (Prompt 24).
- **Failure triage:** check `TvSourcePicker.build` order;
  `MobileSourcePickerSheet` route stratification.

## 13. LAN playback survives the auth-header round trip

- **Goal:** `X-Torve-Lan-Auth` header reaches the desktop hub; the
  hub returns 200 and bytes flow.
- **Steps:**
  1. Start desktop with LAN library on; record the active
     `X-Torve-Lan-Auth` token (logcat / desktop log).
  2. Mobile: pick a LAN tile and play.
- **Expected:** desktop log shows incoming request with the matching
  header; mobile/TV plays without 401.
- **Failure triage:** if 401 → header not staged
  (`pendingRequestHeaders` empty in `ExoPlayerEngine` /
  `MPVPlayerEngine`); see Prompt 19's MPV header fix and the
  `formatHttpHeaderFields` helper.

## 14. Cellular guard suppresses LAN when Wi-Fi-only on

- **Goal:** with Wi-Fi-only set and the phone on cellular, the LAN
  option is hidden in the picker (because the desktop is unreachable
  on cellular anyway).
- **Steps:** flip pref **LAN over Wi-Fi only = on**; disable Wi-Fi;
  open Detail.
- **Expected:** picker omits the LAN row; provider/local rows still
  appear.
- **Failure triage:** `TvSourcePicker.build` honours `wifiOnlyForLan`
  + `networkMode == CELLULAR`; the mobile picker reads the same flag.

## 15. Clean app restart preserves source status

- **Goal:** after a force-stop + cold-launch, Status & Repair rows
  reflect the same connected/disconnected truth they did before
  restart (no false "you need to reconfigure").
- **Steps:**
  1. Note the providers shown as connected.
  2. Force-stop the app (`adb shell am force-stop com.torve.app`).
  3. Re-launch.
- **Expected:** post-launch Settings shows the same connected
  providers immediately, before any background re-check has had time
  to run.
- **Failure triage:** the secret store (Keystore-backed) must rehydrate
  on first DI access; if rows show UNCONFIGURED on cold launch,
  `IntegrationSecretStore` is starting empty.

---

## Host-runnable cases

These run without a human in the loop. Capture evidence with the
listed command.

### H1. Build compile gates

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew \
  :androidApp:compileGoogleMobileDebugKotlin \
  :androidApp:compileGoogleTvDebugKotlin \
  :desktopApp:compileKotlin \
  :shared:testDebugUnitTest
```

- **Pass:** all four tasks BUILD SUCCESSFUL; tests report 0 failures.
- **Fail triage:** Kotlin compile error → fix the named symbol; test
  failure → narrow with `--tests "<class>"` and read the report at
  `shared/build/reports/tests/`.

### H2. Mobile + TV install + version verification

```bash
bash scripts/smoke/capture-version.sh
adb -s <mobile-serial> install -r androidApp/build/outputs/apk/googleMobile/release/androidApp-google-mobile-release.apk
adb -s <tv-serial>     install -r androidApp/build/outputs/apk/googleTv/release/androidApp-google-tv-release.apk
bash scripts/smoke/capture-version.sh
```

- **Pass:** version matches what `gradle.properties` advertises.
- **Fail triage:** `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → uninstall
  then re-install; signature mismatch usually means the device has a
  different keystore's build.

### H3. First-launch crash watch

```bash
adb -s <serial> shell am force-stop com.torve.app
adb -s <serial> logcat -c
adb -s <serial> shell am start -n com.torve.app/com.torve.android.MainActivity
sleep 6
adb -s <serial> logcat -d AndroidRuntime:E *:S | tee smoke-results/firstlaunch-<serial>.log
```

- **Pass:** log is empty.
- **Fail triage:** any `FATAL EXCEPTION` is a release blocker; file
  with stack trace + repro.

### H4. Public legal/help URL resolution

```bash
bash scripts/release-checks/link-check.sh
```

- **Pass:** exit 0; PASSED count equals number of HTTP(S) constants in
  `LegalUrls.kt`.
- **Fail triage:** see `docs/release-hardening.md` blockers table.
  `delete-account.html` 404 is **B1**; assign to web ops with
  `web/delete-account.html` as the source-of-truth draft.

### H5. Backend test suite

```bash
cd server
.venv/bin/python -m pytest -q
```

- **Pass:** all collected tests pass.
- **Fail triage:** `tests/test_account_lifecycle.py` failures gate the
  account-deletion path; `tests/test_pairing_signin.py` failures gate
  Case 5; access-state and compatibility tests must confirm historical entitlement data does not gate product access.

---

## Failed-case escalation template

When a case fails, append a row in the result file:

```
### Case <N> — <name>
- Build: <versionCode> / <commit-sha>
- Bug title: "<symptom in 8 words>"
- Suspected owner: <module path>
- Next fix prompt: "<one-line ask>"
- Evidence: <log path / screenshot path>
```

The next fix prompt should be small enough to drop into a single
follow-up task. Avoid "investigate X"; prefer "check that Y is set
when Z happens".
