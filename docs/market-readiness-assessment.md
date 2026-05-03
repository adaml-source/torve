# Torve Market Readiness Assessment

Last updated: 2026-05-03 (post B4 success-path smoke + updater hardening + multi-user isolation)

## Harsh Verdict

This evening's session moved the desktop updater from "five bugs fixed,
success path theoretical" to **success path verified end-to-end inside
Windows Sandbox**, with three layers of defence stacked so the upgrade
works even when Restart Manager can't talk to the running app. The
in-app updater is real now, not just code that compiles.

But the Sandbox smoke also exposed honest fragility we had been waving
away: `util:CloseApplication` alone wasn't enough on its own (Sandbox
SID mismatch made WiX's Restart Manager close attempts fail), msiexec's
default UI surfaced "Files in Use" prompts every time, and the in-place
upgrade ended in a half-broken runtime image once. Each issue is a real
production risk — Sandbox quirks tell you what fails when the
environment isn't friendly. We layered fixes (A: handoff self-exits
before msiexec touches files, B: `util:CloseApplication` belt-and-
suspenders, C: detached watchdog auto-relaunches the new exe, plus
`/qb` to silence remaining dialogs) and now the upgrade lands cleanly
even in Sandbox.

Separately, **multi-user state isolation** landed in `shared/` —
`addon`, `iptv_channel`, `iptv_category_config`, `iptv_epg_channel`,
`iptv_epg_programme`, and `subscription` are now `user_id`-scoped via
SQLDelight migration v6 → v7, with five passing isolation tests
(two-user, reverse, signed-out, migration, signature-smoke). This
closes a real correctness gap the operator hit personally today: signing
out of `adam.losonczy@gmail.com` and into `losonczy80@gmail.com` was
showing the first user's addons + IPTV configs to the second user.
**The code is done and tested but not yet committed.**

The product sentence still works:

> Torve is the credential-first media hub that tells you what you can
> actually watch, picks the best legal source, plays it on the couch,
> and explains what broke when it cannot.

The job between now and stable hasn't moved much — **macOS gap, signed
binaries, production-grade release feed, and CI discipline** — but the
ceiling on "how good is the desktop experience today" went up
materially.

## Current Readiness

| Target                              | Prior (this morning) | Now (this evening) | Movement |
| ----------------------------------- | -------------------: | -----------------: | -------- |
| Closed enthusiast beta              | 8.5                  | **8.5**            | unchanged — power users tolerate residue, but updater UX got noticeably better |
| Public desktop + Android beta       | 8.0                  | **8.5**            | +0.5 — updater success path verified, watchdog auto-relaunch, multi-user data isolation |
| Public paid stable launch           | 6.0                  | **6.5**            | +0.5 — same desktop wins, but still no signed binaries, no real release feed, no macOS |
| iOS beta                            | 3.0                  | **3.0**            | unchanged — still gated on Mac |

The +0.5 on beta is earned. Three independent fix layers in the updater
+ a watchdog that lands the user back in the app at the new version is a
real UX jump. The +0.5 on stable is more modest — the architectural
work matters but the *release infrastructure* (signing, hosting,
CDN-served appcast) hasn't moved.

## What changed in this session

### Desktop updater (B4) — three-layer hardening

- **Fix A** (handoff self-exit): `UpdateInstallerHandoff` now exits the
  current Torve JVM ~1.5 s after launching msiexec. msiexec's elevated
  child has already started, so it survives our exit. The running
  `Torve.exe` releases its own file locks before msiexec reaches the
  file-copy phase, sidestepping the entire Restart Manager dance.
- **Fix B** (WiX `util:CloseApplication`): a new gradle task
  `packageMsiCloseApp` shells out to `jpackage` directly with
  `--resource-dir` pointing at a hand-rolled WiX template under
  `desktopApp/wix-resources/main.wxs`. The template adds a
  `util:CloseApplication` element targeting `Torve.exe`, which compiles
  to `WixCloseApplications` + `WixCloseApplicationsDeferred` custom
  actions in the MSI. Verified by reading the MSI's `CustomAction`
  table directly. Belt-and-suspenders for environments where Fix A's
  self-exit somehow hasn't fired (corrupted state, debugger attached,
  etc.).
- **`/qb` on msiexec**: handoff invokes `msiexec.exe /i <file> /qb`
  instead of default UI. Suppresses the standard Restart Manager
  "Files in Use" dialog so the install runs to completion silently.
- **Fix C** (watchdog auto-relaunch): before `exitProcess(0)`, handoff
  spawns a detached PowerShell watchdog with stdio redirected to NUL.
  The watchdog waits for the current Torve PID to exit (max 30 s),
  sleeps 12 s for msiexec to finish file replacement, then launches
  `Torve.exe` from the same path the running process was loaded from.
  Result: user clicks Download & install → progress → brief gap →
  Torve reopens at the new version. No Start Menu hop.
- **Icon + shortcut polish**: `torve_icon.png` converted to a
  multi-resolution `torve.ico` (16/32/48/64/128/256), wired to
  jpackage `--icon` so the exe + Add/Remove Programs entry + MSI all
  use it. Also added `--win-shortcut-prompt` so the install UI lets
  the user choose Start Menu / Desktop shortcuts during full-UI
  install.
- **Sandbox smoke kit** (`smoke-kit/`): self-contained smoke harness —
  `start-host.ps1` (cloudflared HTTPS tunnel + appcast.xml emitter),
  `sandbox.wsb` (Windows Sandbox config), `sandbox-run.ps1` (in-Sandbox
  installer), README. cloudflared replaces the rate-limited ngrok
  flow that blocked the previous attempt.
- **Updater handoff regression test** updated to pin the new
  `msiexec /qb` arg list. Existing `UpdateCheckerTest` still passes.

### What the smoke proved end-to-end

In a fresh Windows Sandbox (WDAGUtilityAccount), with Torve 1.0.6
pre-installed:
1. User clicked Settings → Diagnostics & Updates → Check for updates →
   Download & install.
2. Updater downloaded `Torve-1.0.7.msi` to `%TEMP%\torve-update-…`.
3. `defaultOsLauncher` invoked `msiexec /i <path> /qb`.
4. Torve self-exited 1.5 s later (Fix A); detached watchdog spawned
   (Fix C).
5. msiexec ran with `WixCloseApplications` belt-and-suspenders (Fix B,
   confirmed via `CustomAction` table inspection: `WixCloseApplications`
   + `WixCloseApplicationsDeferred` present) and `/qb` UI level.
6. MSI event log: `MsiInstaller Id 11707 — Installation completed
   successfully` for `torve-update-Torve-1.0.7.msi`.
7. Watchdog relaunched Torve.exe.
8. About panel showed **1.0.7**.

The Sandbox-specific runtime-image corruption that hit one earlier
upgrade was traced to the SID-mismatch fallback path that no longer
fires now that all three fix layers are in place.

### Multi-user state isolation (privacy correctness)

Operator demonstration test today: log in as `adam.losonczy@gmail.com`,
configure addons/IPTV/preferences. Log out, log in as
`losonczy80@gmail.com`. **Result: previous account's data was visible.**
That was a real privacy bug.

Fix landed in `shared/`:
- 6 leaky tables now require `user_id`: `addon`, `iptv_channel`,
  `iptv_category_config`, `iptv_epg_channel`, `iptv_epg_programme`,
  `subscription`. PRIMARY KEYS / unique constraints all include
  `user_id`. Indices rebuilt.
- SQLDelight migration `6.sqm` drops + recreates these tables (per
  the explicit "do not preserve contaminated rows" policy — pre-
  migration rows had no `user_id` and were unsafe to migrate).
- Repository layer (`AddonRepositoryImpl`, `ChannelRepositoryImpl`,
  `SubscriptionRepositoryImpl`, EPG ingest pipeline across android +
  desktop + ios) all route every read/write through
  `UserIdProvider.currentUserId()`. Signed-out reads return empty.
- 5 isolation tests pass: two-user isolation, reverse isolation,
  signed-out empty state, v6→v7 migration drops legacy rows, SQLDelight
  signature smoke. `:shared:compileKotlinDesktop` and
  `:shared:compileDebugKotlinAndroid` clean.
- Subscription is treated as cache only — entitlement refreshes from
  backend after login/migration.
- Sign-out cleanup hook (`AccountSessionCoordinator.signOut()`) was
  already adequate; verified it clears in-memory caches for
  channelRepo, addonSyncService, subscription preference cache.
- **Status: code complete, tests green, NOT YET COMMITTED.** Sitting
  as uncommitted changes in the working tree.

Six additional unscoped tables remain out of scope tonight
(`iptv_hidden_channel`, `stream_resolve_memory`, `trakt_rating`,
`trakt_sync_state`, `trakt_sync_queue`, `rating_cache`). Lower
sensitivity (cache + trakt sync metadata) — follow-up work.

## What today did NOT prove

These remain residue, in priority order:

1. **Real-Windows production smoke** — Sandbox is a useful but
   imperfect proxy. WDAGUtilityAccount + the elevated installer have
   different SIDs, which forced us to layer three fixes deep. On a
   normal Windows install with UAC, Restart Manager *should* work
   single-handedly; we have not proved this. Need a snapshot-Hyper-V
   VM run with a real user account to confirm Fixes A/B/C work the
   way we expect (and aren't masking a more fundamental issue).
2. **Code-signed binaries** — Torve.exe and the MSI are unsigned. On
   real user machines, SmartScreen will warn, AV products will
   quarantine, and the auto-update flow will surface a UAC prompt
   the user has no signal to trust. Stable launch is impossible
   without an EV cert and a clean signed pipeline.
3. **Production-grade release feed** — cloudflared trycloudflare
   tunnels are fine for smoke, useless for production. Need a CDN
   (or GitHub Releases via custom domain) hosting `appcast.xml` and
   versioned MSIs at stable URLs.
4. **Side-by-side install bug (#7)** — open from prior session: 1.0.6
   sometimes survived the 1.0.7 upgrade in Add/Remove Programs
   despite the shared `upgradeUuid`. Did not recur in this session's
   smoke (after the fix layers landed). Worth one explicit retest.
5. **B2 / B3 (macOS / iOS)** — iOS simulator smoke + macOS notarize
   round-trip. Still gated on Mac availability.
6. **`UpdateInstallerHandoff` test coverage** — handoff's `osLauncher`
   resolution is now pinned to `msiexec /qb`, but the new self-exit +
   watchdog-spawn paths added today have no unit tests. Easy to add
   (mock the launcher + ProcessBuilder, assert order); currently a
   gap.
7. **Multi-user isolation work uncommitted** — five tests pass but
   the diff is sitting in the working tree. Needs commit + push +
   ideally a CI run before it counts as landed.
8. **Six remaining unscoped tables** — see "Multi-user" section
   above.
9. **TMDB_API_KEY in plaintext** — visible in `Torve.cfg` post-install.
   Known. Real fix is to move to a signed bootstrap fetch or obfuscate
   at packaging time. Not a B4 blocker; is a stable launch concern.
10. **Snapshot-VM AV pass** — Sandbox catches missing-runtime classes
    well, but not Defender quarantine, third-party AV interaction,
    user-profile persistence, or mid-session Windows Update conflict.
11. **Backend Option B operational proof** — `c80fff9` shipped the
    workflow; first dry-run / live deploy / observed CI pass on a real
    push has not happened.

## Competitive Reality

Stremio remains the most dangerous comparison. >30M users, broad
platform support, login-based cross-device continuity, simple addon
mental model. <https://www.stremio.com/>

Syncler still owns Android debrid-user psychology — Android TV focus,
synced home, Trakt/Simkl, source filtering, autoplay — at ~$1.25/mo.
<https://syncler.net/>, <https://app.syncler.net/plus>

Plex Remote Watch Pass: $1.99 intro / $2.99 after June 1, 2026.
Jellyfin: free. Emby Premiere: $4.99/mo, $54/yr, $119 lifetime.
Infuse Pro: $1.99/mo, $16.99/yr, $99.99 lifetime. Channels: $8/mo or
$80/yr (best Live TV/DVR). Kodi: free, BYO content.

Where Torve genuinely differentiates today:
- **"What can I watch right now"** unified availability across debrid
  cache, Plex/Jellyfin, addons, Usenet, IPTV live, watch history.
  Stremio is closer to "what addons say"; Syncler is closer to "what
  debrid says."
- **LAN library + Downloads on Desktop** for couch playback without
  re-uploading.
- **Onboarding via Panda** — one credential transfer instead of
  individually wiring debrid + NZB + indexers.
- **In-app updater** — none of Stremio / Syncler / Infuse on Windows
  do this as cleanly. (Once we ship signed binaries and a real feed.)

## Feature Assessment by Surface

### Desktop (Windows) — strongest, beta-ready

| Strength | Harsh assessment |
| --- | --- |
| Compose Desktop UI, ExoPlayer + libmpv, VLC bundled. | Polished. Player works. |
| In-app updater with three-layer defence + watchdog auto-relaunch. | **Major win this session.** Real UX now. Still needs signed binaries to work in production. |
| Sandbox smoke kit + cloudflared tunnel. | Reusable. Good test discipline foundation. |
| Onboarding A+B+E (Panda-primary, zero-source admission, Home empty state). | Done; Sandbox-smoked today. |
| Multi-user isolation. | Done in code, tests pass, **not committed.** |
| Per-machine MSI install. Icon + shortcut prompt. | Done. |

**Missing for stable**: signed binaries (EV cert + signtool), production
appcast feed (CDN), `UpdateInstallerHandoff` unit test coverage for
self-exit + watchdog, snapshot-VM AV pass, side-by-side install retest,
TMDB key obfuscation.

### Desktop (macOS) — gated

DMG packaging declared in `nativeDistributions.targetFormats`. Signing
hooks via `TORVE_MAC_SIGN_IDENTITY` / `TORVE_MAC_NOTARIZATION_USER` env
vars. **No build has been produced**, no notarization round-trip run.
Cannot ship without a Mac. Treat all macOS readiness as 3.0/10 like
iOS until the build actually happens.

### Desktop (Linux) — neglected

`.deb` + AppImage in `targetFormats`, `--win-shortcut`-equivalent for
Linux is `linux { menuGroup = "AudioVideo"; shortcut = true }`. No
in-app updater path on Linux (the handoff `supportsHandoffOn` filter
returns false). Distribution-format polish missing. Audience for a
Linux build is small but vocal — not actively pursued.

### Android Mobile

Strong base from prior work:
- Kotlin Multiplatform shared module → most business logic shared
- Jetpack Compose UI
- Google Play / Amazon Appstore flavor split (`google` / `amazon` ×
  `mobile` / `tv`)
- Billing abstractions (`GooglePlayBillingManager` /
  `AmazonBillingManager`)
- Cast abstractions (`GoogleCastService` / `AmazonCastService`)
- Device governance (5-device cap, 45-day stale, 3 swaps/30 days)

**Missing**:
- Multi-user isolation tests not run on Android target (the schema
  fix is shared, so it should Just Work, but no Android emulator
  smoke yet).
- Real-device matrix from `docs/smoke/real-device-matrix.md` not
  exercised since updater fixes landed.
- Account deletion / export flows verified on prior commits but
  unrelated to this session.
- Settings dense-ness still flagged.

Mobile didn't move this session; rating sticks near prior level
(reasonable beta, weak vs Syncler on TV-couch ergonomics).

### Android TV

Biggest UX jump in the project: outcome rails, provider banner, On Now,
Downloads on Desktop, source picker, LAN handoff, one-OK playback.

**Missing**:
- Real-device couch smoke against a Shield/Fire TV/Onn.
- Series source picker parity with mobile.
- Logo/group quality polish for IPTV.
- The "Trakt out of onboarding" subsumption from desktop A+B+E
  hasn't been mirrored to TV onboarding.
- Side-by-side parity audit with Syncler is overdue.

### iOS

3.0/10. SwiftUI screen structure exists in `iosApp/`, KMP entitlement
flow + StoreKit JWS verification + `TorveAPIClient.swift` are in
place. **No build has been produced**, no simulator smoke, no
TestFlight, no App Review preparation. Cannot move without a Mac.

## What's left to clear stable

| ID  | Owner             | Why it's still hard |
| --- | ----------------- | ------------------- |
| B2  | Operator (macOS)  | iOS simulator smoke; needs Mac. |
| B3  | Operator (macOS)  | Sign + notarize round-trip; needs Mac. |
| —   | Code signing      | EV cert procurement + signtool wiring + signed appcast feed for Windows. Critical for stable; not yet started. |
| —   | Release infra     | CDN-hosted MSI + appcast.xml at stable URLs. cloudflared is fine for smoke, useless for production. |
| —   | Test coverage     | `UpdateInstallerHandoff` unit tests for self-exit + watchdog spawn; multi-user isolation tests on Android emulator; desktop CI workflow that forces these to run. |
| —   | Multi-user        | Commit + push the uncommitted isolation work; do the six remaining unscoped tables. |
| —   | Snapshot-VM       | Real Hyper-V VM smoke against Defender + third-party AV + Windows Update interaction. |
| —   | Side-by-side bug  | Retest #7 (1.0.6 not auto-removed during 1.0.7 upgrade) on the new MSIs. |
| —   | Backend process   | `scripts/deploy-backend.sh` dry-run, first apply, observed CI pass on real push. |
| —   | Product           | "Set up once, watch anything legal" still not enforced in UI. |
| —   | Support           | Provider-health explanations, diagnostics export polish, IPTV failure copy. |

## Honest residue you should not pretend is solved

1. **Production updater path is unproven.** Sandbox smoke is good, but
   real Windows + UAC + AV + signed installer behaves differently. The
   layering we did (A+B+C) is defence against environments worse than
   real Windows. We have not yet proved the *real Windows* case.
2. **Binaries are unsigned.** Every layer of polish from this session
   is invisible to end users until the EV cert is in place. SmartScreen
   will scare them off the install, AV will delete the MSI mid-
   download, and the in-app updater's downloaded MSI will fail
   `signtool /v` verification because it was never signed.
3. **Multi-user isolation is uncommitted.** Counts as 0.0/10 until
   `git commit` + `git push` happens. Five tests passing locally is
   not the same as code in the tree.
4. **iOS still 3/10.** No movement. Single-mac dependency is the
   project's biggest unsolved structural risk.
5. **Updater CI is still incomplete.** `UpdateCheckerTest` covers
   parser/version. `UpdateInstallerHandoffTest` covers launcher
   resolution. **Self-exit + watchdog + all the new defensive
   plumbing has zero coverage.** A regression here re-introduces
   exactly the kind of silent-failure bug B4 surfaced in the
   morning.
6. **Six unscoped tables.** Hidden channels, stream-resolve memory,
   Trakt rating + sync state + sync queue, rating cache. Lower
   sensitivity than the six we just fixed, but multi-user data
   privacy isn't done until they're scoped too.
7. **The repo's ability to build is still operator-machine-bound.**
   Backend prod is canonical (Option B) and you can deploy from your
   laptop, but there's no third-party way to ship a desktop build —
   it relies on a Windows host with JBR + JDK 21 (for jpackage) +
   WiX 3.11 (auto-downloaded by Compose Desktop). Bus factor of one.

## MRR At $1.99

(Unchanged — illustrative gross before fees.)

| Paid users | Gross MRR |
| ---: | ---: |
| 1,000 | $1,990 |
| 5,000 | $9,950 |
| 10,000 | $19,900 |
| 50,000 | $99,500 |

Realistic year-1 net MRR for an indie launch into this niche, after
store fees, VAT, refunds, payment failures, support, backend, AI
costs, signing, infra, and the developer's own time, is plausibly
**$1,000–$4,000/month** in steady state if support volume is kept
low. Drop the "/mo" if support gets out of control — debrid + NZB +
IPTV outages each become a Torve ticket.

Pricing posture: $4.99/mo standard. $1.99 only as intro/early-bird.
$79–99 capped lifetime. $9.99 family/power tier.

## User Base Potential

| Scenario | Plausible paid user range | Conditions |
| --- | ---: | --- |
| Desktop-only enthusiast beta | 500–5,000 | Debrid/Usenet/IPTV communities trust the app. The updater works in real Windows. |
| Desktop + Android (mobile + TV) public beta | 5,000–25,000 | Onboarding stays simple. Provider diagnostics polished. LAN reliable. Multi-user isolation committed and not regressed. |
| 50,000+ paid | Possible but difficult | TV-first polish. Signed binaries with no SmartScreen warnings. Real release infrastructure. Low support volume. |
| Mainstream app-store scale | Unlikely near term | Source-aggregation policy risk in app-store review. iOS still gated. |

## What Would Increase Value Most Now

1. **Procure an EV code-signing certificate and wire signtool into
   the desktop release pipeline.** Until binaries are signed, every
   real-user install is friction-loaded with SmartScreen and AV. This
   is the single biggest blocker to stable, full stop.
2. **Commit the multi-user isolation work** and push. Five passing
   tests in the working tree don't count.
3. **Run real-Windows snapshot-VM smoke** of the updater path with
   the three fix layers in place. Confirm the path works on a
   normally-installed Windows account, not just Sandbox. If it does,
   the +0.5 to public beta hardens. If not, we have one more bug to
   chase before stable.
4. **Run macOS / iOS build** (closes B2 + B3). The single largest
   remaining structural debt.
5. **Add `UpdateInstallerHandoff` unit tests** for self-exit + watchdog
   spawn paths. They don't need to actually launch processes; mock the
   `osLauncher` + `ProcessBuilder` and assert order. ~50 lines.
6. **Stand up a production HTTPS feed** (CDN + appcast.xml). cloudflared
   has done its job; not stable.
7. **Run Android TV real-device couch smoke** with the latest builds
   and the new updater fixes propagated.
8. **Run the backend Option B dry-run + first apply.** Workflow shipped
   in `c80fff9`; operational proof still missing.
9. **Reduce visible complexity**: setup outcome-first, Home outcome-
   first.
10. **Do the six remaining unscoped tables** for multi-user data
    privacy completeness.

## Net Call

- **Closed enthusiast beta**: **GO**, unchanged. Cut from latest
  master (after multi-user isolation + this session's updater work
  commits).
- **Public desktop + Android beta**: **GO** — but document the
  residue: "Updater is hardened with three independent defence
  layers; if the upgrade ever goes wrong, Settings → Diagnostics &
  Updates surfaces the failure inline. Binaries currently unsigned —
  expect a SmartScreen warning until an EV cert is in place."
- **Public paid stable**: **NO-GO**. Still need: EV cert, signed
  binaries, production CDN feed, B2/B3, real-Windows snapshot-VM
  smoke, handoff unit tests, multi-user commit, side-by-side retest.
- **iOS**: **NO-GO**. Mac dependency unsolved.

Final ratings:
- **8.5/10 closed enthusiast beta**
- **8.5/10 public desktop + Android beta**
- **6.5/10 stable paid consumer launch**
- **3.0/10 iOS**

The gap continues to be **macOS host + production release
infrastructure (signing, CDN) + uncommitted isolation work**. Test
discipline is now better than this morning but still incomplete:
parser + version + handoff-resolution covered, self-exit + watchdog
not. Anything that depends on auto-update needs CI backing, or the
next silent-failure bug ships exactly the same way it did this
morning.
