# Real-Device Smoke Matrix

Repeatable beta-cut verification. Run before tagging a release; capture
logs + version evidence for any failure so the result is reviewable, not
anecdotal.

## Why this exists

Manual smoke testing was producing one-line "looks fine" notes that
nobody could re-run. Prompt 20 turns that into a checklist with:

- A **named cases** list every operator runs in order.
- Helper **scripts** under `scripts/smoke/` for the mechanical parts
  (install APK, dump filtered logcat, capture version).
- A **result file** (`smoke-results/<date>-<build>.md`) per run so two
  cuts can be compared side-by-side.

## Required environment

| Variable / setup       | Used for                                          |
| ---------------------- | ------------------------------------------------- |
| `adb` on PATH          | Mobile + TV install / logcat capture              |
| Connected mobile (USB) | Mobile-side cases (sign-in, transfer receive)     |
| Connected TV (emulator OR device) | TV-side cases (QR signin, EPG)         |
| Desktop binary built   | Desktop→TV credential transfer case               |
| Windows VM (clean)     | Windows clean-install case (host-side, manual)    |

`scripts/smoke/run-smoke.sh` autodetects connected devices via
`adb devices` and routes mobile-vs-TV cases to the right serial. Pass
`--device-mobile <serial>` / `--device-tv <serial>` to override.

## Cases

Each case has: a one-line goal, the scripted entry point, the manual
verification steps, and the pass/fail criteria. Operator records
PASS / FAIL / SKIP in the result file along with notes + the path of
any captured logs.

### 1. Mobile sign-in (existing account)

- **Goal:** confirm a returning configured user lands on Home, never
  bounced through setup or Manage Devices.
- **Setup:** install latest mobile release on a fresh device that has
  a known-good Torve account credential (account already configured
  on another device).
- **Steps:**
  1. `bash scripts/smoke/install-mobile.sh`
  2. Open Torve, sign in with the existing account.
- **Pass:** post-sign-in lands on Home. No setup wizard, no "Manage
  devices" screen.
- **Fail evidence:** `bash scripts/smoke/capture-logcat.sh > tmp.log`
  while reproducing; attach the log path.

### 2. New-account setup

- **Goal:** new register flow goes Verify Email → Setup Choice →
  Wizard/Manual → Home (Prompt 15 acceptance).
- **Steps:**
  1. Install mobile release.
  2. Register a brand-new account.
  3. Click verification link in inbox; return to app.
- **Pass:** verify-email screen shown until link clicked, then Setup
  Choice appears, then Home.
- **Fail evidence:** screen recording of any unexpected screen +
  `capture-logcat.sh`.

### 3. Phone signs in TV via QR

- **Goal:** confirm `/pairing/signin/code` end-to-end against the
  deployed backend.
- **Steps:**
  1. Install latest TV build (`bash scripts/smoke/install-tv.sh`).
  2. On TV: Settings → "Sign in with your phone".
  3. On phone: Settings → "Sign in a TV with this phone".
  4. Scan the QR shown on TV.
- **Pass:** TV's Settings header switches to signed-in state within
  ~10s; manage-devices entry on phone shows the new TV.
- **Fail evidence:** TV logcat filtered to `TvPairingSignIn` +
  `PairingApi`; phone logcat filtered to `SyncCoordinator`.

### 4. Credential transfer desktop→TV

- **Goal:** confirm the credential-transfer relay or manual paste
  hands a sealed bundle to TV.
- **Steps:**
  1. On desktop: Settings → Send credentials to another device.
  2. On TV: Settings → Receive credentials.
  3. Use the sealed code shown on desktop in the TV's paste field
     (relay path may auto-apply if both signed into the same
     account).
- **Pass:** TV imports IPTV / Panda creds without errors; status
  banner reads "Imported".
- **Fail evidence:** transfer diagnostics screen on TV +
  `capture-logcat.sh -s TransferReceiver`.

### 5. LAN playback

- **Goal:** mobile/TV plays a file off the desktop's LAN library
  without re-fetching.
- **Pre:** desktop running with at least one local-library item
  scanned and the LAN server enabled.
- **Steps:**
  1. On mobile or TV: open Library, pick the item.
  2. Tap Play.
- **Pass:** playback starts in <3s; bandwidth dashboard shows LAN
  hit, not WAN.
- **Fail evidence:** capture-logcat filtered to `LanLibrary` +
  `Player`; note the playback-start timestamp.

### 6. Cellular guard

- **Goal:** mobile must not autoplay heavy items on cellular when
  the cellular-guard pref is on (default).
- **Steps:**
  1. Disable Wi-Fi on the phone; ensure cellular is up.
  2. Open Torve → Channels → tap a high-bitrate live channel.
- **Pass:** confirmation modal appears explaining cellular bandwidth
  before play starts.
- **Fail:** silent play. Capture: `capture-logcat.sh -s
  CellularGuard`.

### 7. TV couch QR readability

- **Goal:** confirm QR code on the TV's "Sign in with your phone"
  screen scans from a typical living-room distance (≈3m on a 50–65"
  screen).
- **Operator-run, no script.** Verify by scanning with the phone
  from couch distance. Snapshot photo into the result file's notes
  column.
- **Pass:** scanner detects within ~3 attempts.

### 8. Windows clean install / playback / update handoff

- **Goal:** confirm the Windows installer / portable build runs on a
  clean VM, plays a sample item, and the in-app updater hands off
  cleanly to the new build.
- **Operator-run on a clean Windows VM (snapshot before each run).**
- **Steps:**
  1. Copy the latest desktop installer / portable archive to the VM.
  2. Run the installer / extract the portable.
  3. Sign in. Open a sample item from the local library. Play.
  4. Trigger the in-app updater (or simulate by installing N-1, then
     pointing it at the latest release feed).
- **Pass:** install completes; playback starts; updater swaps to new
  build without losing the user's session.
- **Fail evidence:** screenshots; export `desktop.log` from
  `%LOCALAPPDATA%\Torve\logs\`.

## Result template

After running, copy `docs/smoke/result-template.md` to
`smoke-results/YYYY-MM-DD-<build>-<operator>.md` and fill in PASS /
FAIL / SKIP per case. Commit the file alongside the build tag (or
attach to the GitHub release).

## Helper scripts (under `scripts/smoke/`)

| Script                 | What it does                                             |
| ---------------------- | -------------------------------------------------------- |
| `install-mobile.sh`    | `adb install -r` the mobile release APK                  |
| `install-tv.sh`        | `adb install -r` the TV debug or release APK             |
| `capture-logcat.sh`    | `adb logcat -d` filtered to Torve tags, output to file   |
| `capture-version.sh`   | `dumpsys package com.torve.app` → versionName/versionCode|
| `run-smoke.sh`         | Orchestrator: capture versions + walk operator through    |
|                        | each case, write the result markdown.                    |

All scripts are POSIX-compatible bash so they run on Git Bash on
Windows, macOS, and Linux. Each accepts `--help`.
