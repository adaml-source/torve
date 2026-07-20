# Torve AIO Value Roadmap Implementation Plan

Obsolete after free-software conversion where it discusses monetization, paid add-ons, subscription management, premium access, founder sales, or revenue positioning. Retained for historical implementation context only.

Current canonical positioning: Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

## Goal

Turn Torve from a feature-rich enthusiast app into a credential-only AIO media hub:

- A user signs in, adds the credentials they already own, and gets a working experience without runtime installs or manual source debugging.
- Desktop becomes the heavy-capability hub: playback, local library, downloads, provider diagnostics, LAN serving, and release/update management.
- TV becomes the primary consumption surface: fast, remote-friendly, simple, and reliable.
- Mobile becomes the companion surface: account setup, QR pairing, light playback, downloads, provider status, and remote control.

## Product Rules

- TV-first UX decides priority. If a feature is hard to use with a remote, it is not ready.
- No plaintext credential sync. Secrets stay local or move only through end-to-end encrypted transfer.
- The app must explain failures in plain language: provider unavailable, credential invalid, source unavailable, EPG mismatch, playback runtime missing, storage full.
- No bundled unauthorized content sources. Torve only connects user-provided services, credentials, libraries, and playlists.
- Defaults must work for normal users. Advanced controls stay behind an Advanced section.
- Desktop public release must not require VLC, mpv, Java, terminal commands, or environment variables.

## Implementation Status Update - 2026-04-30

This roadmap is no longer in the "strong beta foundation" state from 2026-04-29. Prompts 5 through 12, including cleanup passes 7B, 9B, 9C, 10B, 10C, 11B, and 11C, have moved most core roadmap items from partial/foundation into implemented beta-candidate territory.

The harsh status is still not "100% done." After Prompt 12B, the current state is **public beta GO for desktop, Android mobile, and Android TV**, **not stable**, and **not iOS-ready from this Windows host**. The Prompt 6-12B work is checkpointed and pushed: `HEAD = origin/master = 79844ed` (`Checkpoint Prompt 6-12B public beta release work`). `git status --short` is clean aside from unrelated pytest cache permission warnings.

### Current Status

| Area | Status | Current truth |
| --- | --- | --- |
| Baseline and hygiene | Implemented | The original 338-path dirty tree and secret/artifact hazards were cleaned and checkpointed. The later Prompt 6-12B wave is now committed and pushed at `79844ed`; local status is clean aside from pytest cache permission warnings. |
| Playback and desktop release foundation | Beta candidate, stable blocked by operator smoke | VLC runtime staging scripts, hard packaging gates, release-build bypass refusal, installer handoff, SHA-256 verification, channel envs, and signing docs exist. A clean Windows VM package/install/playback/update-handoff smoke is still required before stable. |
| Credential-first setup wizard | Implemented | Four setup intents, per-intent state, validation, Ready-to-watch summary, desktop/Android/iOS hub wiring, and desktop/iOS deep-link cleanup are landed. iOS remains operator-build-required on macOS. |
| Encrypted credential transfer | Implemented, operator smoke required | Desktop, Android mobile, Android TV, and iOS surfaces exist with redaction/diagnostics/recovery coverage. The 12-row live-backend/multi-device smoke matrix is still operator-side. iOS compile/runtime verification requires macOS. |
| Provider health and recovery | Implemented for beta | Desktop, Android mobile, and TV provider-health/recovery rows are wired; Panda state freshness and refresh-on-save are fixed. Remaining non-blockers: Android playback-health bridge parity and cross-device non-sensitive health summary sync. |
| Source-aware AI search | Implemented on shared + desktop, parity gaps remain | Availability kinds now include debrid cache, addon, Usenet ready, IPTV live, watch history, plus privacy sanitizer and desktop badges. Android/iOS search UI parity and first-run performance optimization are follow-ups. |
| Cross-device downloads and LAN library | Implemented for Android/TV beta path, stable smoke pending | Backend registry, LAN publish/discovery, stream-token endpoint, authenticated headers, ExoPlayer header injection, TV/mobile detail badge, Wi-Fi/cellular guard, and desktop settings controls exist. Remaining gaps: MPV/iOS header support, title-only matching, stale-token retry, and real two-device LAN smoke. |
| IPTV DVR-grade features | Implemented for one-off desktop recording | Recording scheduler, conflict detection, file-backed repository, desktop recording service, My Recordings UI, EPG correction UI, and correction application to rendered guide state are landed. Series-pass DVR, mobile/iOS surfaces, immediate guide rebuild on correction edit, and live-provider smoke remain follow-ups. |
| TV-first UX | Implemented for Android TV beta after 11C | TV Home renders outcome rails, provider banner, On Now/Live TV, Downloads on Desktop, and direct one-OK playback. TV details has a D-pad source picker and LAN header handoff. Series next-episode source picking remains a follow-up. |
| Public release hardening | Public beta code GO, stable blocked | Backend `User.is_verified` regression fixed, pairing schema drift fixed, stale-device invariant test corrected, backend is now 110/110, account deletion/export landed, legal links centralized, telemetry redaction landed, LAN `auth_secret` wrapping added with prod gate. Stable still needs web delete-account mirror, macOS/iOS verification, Windows clean-VM smoke, and prod wrap-key setup. |

### Closed Gaps From The Original Audit

- Desktop release no longer depends on an informal runtime setup: release packaging gates and staging scripts exist, and release builds cannot bypass the runtime gate.
- Setup is no longer debrid-linear only: debrid, IPTV, Plex/Jellyfin, and Usenet are first-class intents with validation and resume state.
- Panda provider-health no longer reads a fresh empty factory VM: a singleton state store plus settings-refresh observer keeps rows current.
- Source-aware AI is no longer local/Plex/Jellyfin-only: debrid, addon, Usenet, IPTV, and watch-history signals now participate.
- LAN library is no longer loopback-only foundation: backend registry, LAN bind, token issuance, authenticated playback, and Android/TV ExoPlayer headers are wired.
- IPTV is no longer "coming soon" only: one-off desktop DVR, recording library, conflict handling, storage allowlist, and EPG correction are implemented.
- TV Home is no longer generic catalog-first only: Android TV now has outcome rails, provider banner, source picker, On Now rail, and one-OK playback.
- Public hardening is no longer just docs: deletion/export, redacted telemetry, LAN secret wrapping, and release-hardening docs are implemented.

### Remaining Hard Gates

These block stable release and, where noted, block public beta artifact cutting:

| Gate | Blocks | Owner / host |
| --- | --- | --- |
| Keep backend at 110/110 on the release branch during beta artifact production | Public beta signoff | Backend |
| Publish `https://torve.app/delete-account.html` | Public stable and app-store compliance | Web ops |
| Run iOS build + simulator smoke for the Prompt 12 Swift changes | iOS beta/stable | macOS + Xcode |
| Run macOS sign + notarize round-trip | macOS stable | macOS + Apple ID |
| Run clean Windows VM install/launch/playback/update-handoff smoke with real VLC runtime | Desktop stable | Windows VM |
| Set `TORVE_LAN_SECRET_WRAP_KEY` and `TORVE_ENV=prod`, then verify backend LAN hub writes refuse plaintext | Stable with LAN registry enabled | Backend ops |
| Run live credential-transfer smoke across desktop, Android mobile, Android TV, and iOS | Full cross-device claim | Multi-device operator |
| Run live IPTV DVR recording/playback smoke on a real EPG provider | DVR release claim | Desktop + live IPTV |

### Deferred Non-Blockers

- Native WinSparkle/Sparkle updater, delta updates, auto-relaunch, and rollback automation.
- Runtime release-channel selector in Settings; channel is currently build/feed controlled.
- Series-pass DVR and next-episode source picker parity.
- MPV and iOS LAN-header playback support.
- LAN manifest matching by TMDB/imdb id instead of title-only.
- Playlist `password_enc` Fernet wrapping to match LAN `auth_secret`.
- Android/iOS AI search badge parity.
- Immediate guide rebuild after EPG correction edits.
- Cross-device non-sensitive provider-health summary sync.

### GO / NO-GO

| Target | Status | Reason |
| --- | --- | --- |
| Desktop public beta | GO | Prompt 12B fixed the backend blockers and verified the host-runnable code path. Cut beta artifacts only; stable still needs clean Windows VM smoke. |
| Android mobile public beta | GO | Host-runnable build and legal/account surfaces are green by report. |
| Android TV public beta | GO | TV-first flow is accepted after 11C and Prompt 12B kept the build/test signal green. Real-device couch smoke is still required before strong marketing claims. |
| iOS beta | NO-GO from this host | Swift changes exist, but macOS `xcodebuild` and simulator smoke have not run. |
| Public stable | NO-GO | Stable is blocked by web legal mirror, macOS/iOS verification, Windows clean-VM smoke, and prod LAN wrap-key validation. |

## Prompt Packages And Remaining Verification

Prompts 5-12B are retained below as the implementation history and reproduction plan. The next work should be beta artifact production and the stable-release operator gates, not new feature scope.

For the updated commercial/product verdict, see `docs/market-readiness-assessment.md`.

### Prompt 5: Baseline Stabilization And Truth Matrix

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only this stabilization prompt.

Goal:
Establish a truthful baseline before any more feature work. The repo currently contains large uncommitted changes and many newly added files. Do not add features until the implemented slices compile, tests pass where expected, and the remaining gaps are explicitly classified.

Scope:
1. Audit the dirty worktree.
   - Group modified/untracked files by feature area.
   - Identify generated artifacts, logs, screenshots, local secrets, build outputs, and accidental files that must not ship.
   - Do not delete anything unless explicitly safe and scoped.
2. Run the highest-signal build/test checks that can run on this host.
   - Shared transfer/provider-health/recovery tests.
   - Desktop transfer/provider-health/playback tests.
   - Android mobile and TV assemble.
   - Any server transfer/account tests if backend code is present.
3. Produce a roadmap truth matrix.
   - Implemented.
   - Partially implemented.
   - Not implemented.
   - Requires operator smoke.
   - Requires macOS/iOS host.
4. Fix only compile/test failures caused by the recently implemented roadmap work.
   - Do not refactor broad UI.
   - Do not start new features.

Acceptance criteria:
- The repo has a clear implementation status matrix for every roadmap phase.
- Relevant tests/builds are green, or failures are classified as pre-existing/unrelated with concrete evidence.
- Accidental release hazards are identified: local keys, logs, screenshots, build output, copied plugins, raw secrets.
- No new feature scope is introduced.

Return only:
- Worktree audit
- Build/test results
- Roadmap truth matrix
- Release hazards found
- Fixes made
- Remaining blockers
```

### Prompt 6: Desktop Runtime, Signed Package, And Real Update Path

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only the desktop release foundation gaps.

Goal:
Close the biggest public-launch trust gap: a user installs Torve Desktop and playback/update behavior works without external runtime setup or terminal knowledge.

Scope:
1. Stage or script staging of the Windows VLC runtime.
   - `desktopApp/runtime/windows/vlc/` must contain libvlc.dll, libvlccore.dll, plugins/, and VLC license notice for release builds.
   - If binaries cannot be committed for licensing/size reasons, add a deterministic release-prep script and CI/package task that fetches or verifies an operator-staged runtime.
2. Run and harden `verifyWindowsPackagingPrereqs`.
   - It must fail without the complete VLC runtime unless an explicit non-release bypass is set.
3. Build a Windows package and document the exact clean-VM smoke.
   - Fresh install.
   - Local file playback.
   - Remote stream playback.
   - Subtitles.
   - Fullscreen.
   - Audio track switch.
4. Convert update support from "view release" to an apply path, or explicitly wire a platform-native installer handoff.
   - Windows: WinSparkle or installer handoff.
   - macOS: Sparkle-ready appcast/signing path.
   - If full apply is too large, implement the smallest real update handoff that downloads/verifies/launches the installer safely.
5. Verify signing hooks.
   - Windows Authenticode signing config or script.
   - macOS Developer ID signing/notarization config.
   - Release channel config: stable, beta, internal.

Acceptance criteria:
- A release Windows package cannot be built without a complete VLC runtime and license notice.
- A packaged clean Windows install plays without system VLC/mpv/Java.
- Update UX does more than "view release", or the installer handoff is concretely implemented and tested.
- Signing/notarization commands are documented and mechanically runnable.
- No secrets or local paths are embedded in release artifacts.

Return only:
- Audit findings
- Files changed
- Package/sign/update path summary
- Tests/builds run
- Clean-VM smoke remaining
- Known gaps
```

### Prompt 7: Complete Credential-First Setup Wizard And Settings Simplification

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only setup wizard completion and touched Settings simplification.

Goal:
Make first setup credential-first for every promised source path. A new user should not need to browse raw Settings to configure debrid, IPTV, Plex/Jellyfin, or Usenet.

Scope:
1. Replace or extend the current setup flow so the four top-level intents are first-class paths:
   - Debrid.
   - IPTV.
   - Plex/Jellyfin.
   - Usenet/Easynews/NZB indexers/download client.
2. Each path must test credentials immediately.
   - Green: ready.
   - Yellow: partial/missing companion config.
   - Red: invalid/unreachable/unsupported.
3. Persist partial setup progress.
4. Add a "Ready to watch" summary.
   - Playback paths ready.
   - Warnings.
   - Repair actions.
5. Reduce raw Settings duplication for touched source areas.
   - Summary cards first.
   - Advanced fields collapsed.
   - No duplicate credential entry points.

Acceptance criteria:
- User can complete debrid-only, IPTV-only, Plex/Jellyfin-only, or Usenet-only onboarding.
- Invalid credentials are not silently saved as ready.
- Provider-health rows and setup summary agree.
- Advanced fields are hidden by default for touched areas.
- Tests cover wizard state, validation, resume, and summary generation.

Return only:
- Audit findings
- Files changed
- Acceptance criteria satisfied
- Tests added/run
- Manual smoke remaining
- Known gaps
```

### Prompt 8: Full Availability Graph And Source-Aware AI Completion

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only source-aware availability and AI ranking completion.

Goal:
Make AI search answer "what can I actually watch right now?" instead of ranking generic catalog results.

Scope:
1. Extend SourceAvailabilityKind beyond LOCAL_DOWNLOAD, PLEX, and JELLYFIN.
   - Debrid cache/inventory.
   - Stremio/addon source availability.
   - Usenet warm/ready state.
   - IPTV live/EPG on-now.
   - Watch history/profile safety signals where already available.
2. Implement real providers only.
   - No fake "cached" badges.
   - Unknown/unconfigured providers return no signal plus repair suggestion.
3. Update ranking.
   - Local download > LAN desktop stream > Plex/Jellyfin > cached debrid/addon > Usenet ready > IPTV on-now where intent matches > generic catalog.
4. Update AI search UI.
   - Show badges and explanation copy.
   - If no source is available, show setup/repair actions instead of a generic empty/generic result list.
5. Protect privacy.
   - No secrets, tokens, manifest URLs with credentials, or local paths sent to AI.

Acceptance criteria:
- Ranking tests prove available sources beat generic TMDB results.
- Results explain why they are shown.
- Missing provider setup routes to provider-health repair actions.
- Privacy tests prove AI payloads exclude secrets and source URLs with credentials.
- Desktop AI overlay and any shared/mobile search surfaces compile.

Return only:
- Audit findings
- Files changed
- Availability providers added
- Ranking/privacy tests added/run
- Manual smoke remaining
- Known gaps
```

### Prompt 9: Real Cross-Device Downloads And LAN Playback

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only cross-device downloads/LAN library completion.

Goal:
Make desktop downloads useful on TV/mobile. TV and mobile should see that a title is available on the desktop hub and play it over authenticated LAN without manual file sharing.

Scope:
1. Upgrade desktop LAN serving from loopback-only to an explicit authenticated LAN mode.
   - User-controlled toggle.
   - Bind to LAN interface only when enabled.
   - Strict download-folder allowlist.
   - Range requests.
   - Per-item opaque tokens.
2. Add discovery.
   - mDNS/NSD or a backend-assisted local endpoint registry.
   - No raw local file paths in account sync.
3. Add TV/mobile consumption.
   - Show "available on desktop" metadata.
   - Route chooser: local file, LAN desktop stream, provider stream, re-download prompt.
   - Mobile data guard.
4. Add storage controls.
   - Quota or at least cleanup hooks.
   - Auto-delete watched downloads if practical.
   - Guard tests to prevent deleting outside configured folders.

Acceptance criteria:
- TV can play a desktop-downloaded file over LAN.
- Mobile can see desktop download metadata without local paths.
- Route chooser chooses LAN stream before provider stream when appropriate.
- Storage cleanup cannot delete outside configured folders.
- LAN auth and range tests pass.

Return only:
- Audit findings
- Files changed
- Discovery/auth/route design
- Tests added/run
- Manual smoke remaining
- Known gaps
```

### Prompt 10: IPTV DVR, EPG Correction, And Recording Library

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only IPTV DVR-grade features.

Goal:
Move IPTV from playlist playback to a serious live-TV feature set.

Scope:
1. Desktop recording service.
   - One-off recording from EPG.
   - Recording file naming and folder policy.
   - Recording status and failure reason.
2. Scheduler model.
   - One-off schedule.
   - Series pass if feasible.
   - Conflict detection.
3. Recording library.
   - Completed recordings appear in library.
   - Local playback works.
4. EPG/catch-up correction tools.
   - EPG time offset.
   - Manual playlist channel to EPG channel mapping.
   - Hide bad channels/categories.
   - Stale EPG detection.
5. TV guide polish.
   - Remote-first guide actions.
   - Non-blocking warnings for stale/missing guide data.

Acceptance criteria:
- User can schedule at least one IPTV recording from desktop EPG.
- Completed recording appears and plays locally.
- Conflict/stale-EPG diagnostics are actionable.
- EPG correction avoids XML/M3U manual editing for common cases.
- Tests cover scheduler, conflict detection, EPG mapping, and storage boundaries.

Return only:
- Audit findings
- Files changed
- Recording/EPG design
- Tests added/run
- Manual smoke remaining
- Known gaps
```

### Prompt 11: TV-First Home, Source Picker, And Couch Flow

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only TV-first UX completion.

Goal:
Make the main value obvious from the couch. A TV user should understand what is available and start a known available item in fewer than three remote actions from Home.

Scope:
1. Rebuild or upgrade TV Home around outcomes:
   - Continue Watching.
   - Available Now.
   - Live TV.
   - Downloads on Desktop.
   - Recently Added From My Sources.
   - Provider Health.
2. Add a remote-first source picker.
   - Clear best source.
   - Fallback list.
   - Provider issue hints.
3. Add one-click best source autoplay.
   - If best source fails, fall back predictably and explain.
4. Add provider issue banners.
   - Visible but non-blocking.
   - Route to diagnostics or receive credentials.
5. Add TV focus tests and remote-navigation smoke docs.

Acceptance criteria:
- Known available item starts in fewer than three remote actions from Home.
- Source picker is usable with D-pad only.
- Provider issue banner never blocks browsing.
- TV Home exposes credential-transfer pairing/recovery without typing API keys.
- Focus tests cover Home, source picker, Live TV guide, setup, diagnostics.

Return only:
- Audit findings
- Files changed
- TV flow summary
- Tests added/run
- Manual smoke remaining
- Known gaps
```

### Prompt 12: Public Release Compliance, Data Export, Telemetry Sink, Final Smoke

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only final public-release hardening.

Goal:
Make Torve safe to sell publicly and support without hand-holding every install.

Scope:
1. Account/data rights.
   - Account deletion verified end-to-end.
   - Data export endpoint and client UI if missing.
   - Privacy, terms, subscription management links on every platform.
2. Real telemetry/crash sink.
   - Wire a production sink behind DI/env config.
   - Keep NoOp as default for dev.
   - Crash breadcrumbs for major user actions.
   - Strict redaction tests for credentials, source URLs, local paths, transfer payloads, bearer tokens.
3. Release channels.
   - stable, beta, internal without rebuilding code.
   - Update feed/channel selection.
4. Support docs.
   - Provider status/troubleshooting docs.
   - Diagnostics export user flow.
   - Known limitations.
5. Final operator smoke matrix.
   - Desktop clean install.
   - Android mobile.
   - Android TV.
   - iOS on macOS/device.
   - Credential transfer every direction that is supported.
   - LAN downloads.
   - IPTV recording.
   - Update apply path.

Acceptance criteria:
- User can delete account and export data.
- Crash reports have useful breadcrumbs and no secrets.
- Release channel can be promoted without code rebuild.
- Signed packages install without OS warnings.
- Final smoke matrix is either passed or has explicitly accepted release notes.

Return only:
- Audit findings
- Files changed
- Compliance/release summary
- Tests/builds run
- Operator smoke results
- Final go/no-go
```

### Prompt 12B: Public Beta Release Verification Pack

```text
Read docs/aio-value-roadmap-implementation-plan.md and docs/release-hardening.md, but do not implement new product features.

Goal:
Convert the current public-beta-candidate state into a reproducible beta release signoff, or downgrade the GO if verification fails.

Scope:
1. Checkpoint the current Prompt 6-12 work.
   - Start from a clean `git status --short`.
   - Commit or otherwise checkpoint every intended source/doc/test path.
   - Do not include generated artifacts, secrets, local DBs, screenshots, copied Kodi addons, build output, or scratch directories.
2. List the exact 5 failing backend tests by name.
   - Mark each release-blocking or non-blocking.
   - If any touches auth, account deletion, data export, device pairing, stale sessions, entitlement state, or LAN hub isolation, fix it or downgrade the GO.
3. Run final repo and artifact-input sweep.
   - No `.pem`, `.p12`, `.env`, `.db`, logs, screenshots, Kodi addons, local user paths, bearer tokens, transfer payloads, LAN auth headers, provider credentials, AI keys, or source URLs with secrets.
   - Include package input directories, not only tracked source.
4. Verify legal/account flows.
   - Delete-account URL is live or explicitly beta-blocking.
   - In-app delete works on backend + desktop + Android; iOS remains macOS-required if host cannot build it.
   - Export returns no secrets.
   - Privacy/terms/support links are reachable on desktop, Android mobile, Android TV, and iOS where host-verifiable.
5. Run host-runnable beta checks.
   - Backend tests.
   - Shared telemetry/redaction tests.
   - Shared tvhome/LAN/DVR/setup/provider-health/transfer focused tests.
   - Desktop compile/test high-signal slice.
   - Android mobile + Android TV assemble.
   - Windows packaging prerequisite gate with and without release-bypass refusal.
6. Produce operator-required command list.
   - Clean Windows VM package/install/playback/update-handoff smoke.
   - macOS `xcodebuild` and iOS simulator smoke.
   - macOS sign/notarize/staple round-trip.
   - Live multi-device credential-transfer smoke.
   - Live LAN desktop-to-TV playback smoke.
   - Live IPTV DVR record/playback smoke.
   - Backend production env validation for `TORVE_LAN_SECRET_WRAP_KEY` + `TORVE_ENV=prod`.

Acceptance criteria:
- Worktree is clean after checkpointing.
- The 5 backend failures are either fixed or explicitly proven non-blocking by test name.
- No release hazards remain in repo or package inputs.
- Host-runnable checks pass.
- Public beta GO/NO-GO is split by platform: desktop, Android mobile, Android TV, iOS.
- Public stable remains NO-GO until operator gates are cleared.

Return only:
- Checkpoint status
- Backend failure classification
- Artifact/repo sweep
- Host-runnable verification
- Operator-required verification
- Platform GO/NO-GO table
```

## Copy-Paste Implementation Prompts

Use these prompts directly. Each prompt is intentionally self-contained and scoped. Do not ask an agent to "implement the roadmap" in one pass.

### Prompt 1: Foundation, Playback, Release Trust

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only Phase 1 from this prompt.

Goal:
Make Torve Desktop trustworthy on first install. A fresh user must be able to install the desktop app and start playback without installing VLC, mpv, Java, or setting environment variables. Failures must be detected early and explained clearly.

Scope:
1. Bundle or verify a working default desktop playback engine.
   - Short-term acceptable path: bundled VLC runtime for Windows builds.
   - MPV may remain optional/experimental unless embedded MPV is already production-ready.
   - Do not leave a release path where playback depends on a system VLC install.
2. Add a first-run or startup playback runtime self-test.
   - Check default runtime discovery.
   - Check required native files/plugins.
   - Check audio output availability if practical.
   - Check that the app can initialize the selected engine.
3. Add packaging/build checks.
   - Release packaging must fail if the default runtime is missing.
   - Required third-party license notices must be included in packaged distributions.
4. Improve user-facing runtime failure copy.
   - No generic "playback failed" for missing runtime.
   - Explain what failed and what action is available.
5. Harden diagnostics for release readiness.
   - Confirm diagnostics export redacts credentials, addon URLs with tokens, debrid keys, IPTV URLs with usernames/passwords, Panda tokens, AI keys, local sensitive paths where appropriate.
   - Add or update tests for redaction if missing.

Likely implementation areas:
- desktopApp/runtime/windows/vlc/
- desktopApp/runtime/windows/mpv/
- desktopApp/build.gradle.kts
- desktopApp/WINDOWS_PACKAGING.md
- desktopApp/MPV_BUNDLING.md
- desktopApp/src/main/kotlin/com/torve/desktop/playback/
- desktopApp/src/main/kotlin/com/torve/desktop/player/
- desktopApp/src/main/kotlin/com/torve/desktop/mpv/
- desktopApp/src/main/kotlin/com/torve/desktop/vlc/
- desktopApp/src/main/kotlin/com/torve/desktop/diagnostics/
- desktopApp/src/test/

Out of scope:
- Do not build the setup wizard.
- Do not implement QR credential transfer.
- Do not change AI search.
- Do not implement IPTV DVR.
- Do not redesign Settings or TV UX.

Required workflow:
1. Start with a read-only audit of current playback runtime discovery, packaged runtime folders, packaging checks, and diagnostics redaction.
2. Report what already exists and what is missing.
3. Implement the smallest coherent patch that satisfies the acceptance criteria.
4. Add/update tests where practical.
5. Run relevant desktop tests/build checks.

Acceptance criteria:
- Fresh Windows desktop package has a working default playback runtime or packaging fails before release.
- Missing/corrupt runtime is detected before the user gets a confusing playback failure.
- Runtime error copy is specific and actionable.
- Required VLC/mpv license notices are present when those runtimes are bundled.
- Diagnostics export redacts secrets and tokenized URLs.
- No unrelated feature behavior is changed.

Return only:
- Audit findings
- Files changed
- Acceptance criteria satisfied
- Tests added/run
- Manual smoke remaining
- Known gaps
```

### Prompt 2: Credential-First Setup, Provider Health, Settings Simplification

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only Phase 2 from this prompt.

Goal:
Stop making users begin in Settings. Build a true credential-first setup and provider health model so a user can say what they have, test it, and understand what works.

Scope:
1. Build or upgrade the setup wizard around four user intents:
   - "I have Real-Debrid / AllDebrid / Premiumize / TorBox"
   - "I have IPTV"
   - "I have Plex or Jellyfin"
   - "I have Usenet / Easynews / NZB indexers"
2. Each setup path must test credentials immediately and return green/yellow/red diagnostics.
   - Green: working and ready.
   - Yellow: partially working or setup incomplete, with next action.
   - Red: invalid credentials, unreachable provider, unsupported config, or clear blocker.
3. Persist partial setup progress so the user can leave and return.
4. Show a "Ready to watch" summary after setup.
   - List enabled playback paths.
   - List warnings.
   - Provide repair actions.
5. Add a shared provider health model.
   - Debrid providers.
   - Stremio/addon manifests.
   - Panda/Usenet provider.
   - NZB indexers.
   - Download clients.
   - IPTV playlist fetch.
   - EPG fetch and matching.
   - Plex/Jellyfin server and token.
   - Trakt/SIMKL sync if already wired.
   - Last successful playback.
   - Last failed playback with reason.
6. Surface provider health in desktop UI.
   - At minimum: Settings/Diagnostics or Settings/Sources.
   - Prefer reusable shared state for TV/mobile later.
7. Reduce Settings complexity in the touched areas.
   - Replace raw scattered source fields with source summary cards where practical.
   - Hide advanced/provider-specific fields behind Advanced.
   - Do not do a full visual redesign unless required.

Likely implementation areas:
- desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/
- desktopApp/src/main/kotlin/com/torve/desktop/ui/panda/
- desktopApp/src/main/kotlin/com/torve/desktop/ui/v2/settings/
- shared/src/commonMain/kotlin/com/torve/presentation/setup/
- shared/src/commonMain/kotlin/com/torve/presentation/settings/
- shared/src/commonMain/kotlin/com/torve/presentation/providerhealth/
- shared/src/commonMain/kotlin/com/torve/domain/providerhealth/
- shared/src/commonMain/kotlin/com/torve/data/debrid/
- shared/src/commonMain/kotlin/com/torve/data/channels/
- shared/src/commonMain/kotlin/com/torve/data/integrations/
- shared/src/commonMain/kotlin/com/torve/data/panda/
- shared/src/commonTest/
- desktopApp/src/test/

Out of scope:
- Do not implement encrypted QR credential transfer.
- Do not send secrets to backend sync.
- Do not implement source-aware AI search.
- Do not implement IPTV recording/DVR yet.
- Do not implement cross-device downloads yet.
- Do not do public installer signing/auto-update work except if already touched by diagnostics.

Required workflow:
1. Start with a read-only audit of existing setup wizard, Settings source cards/forms, provider test functions, and health/diagnostics code.
2. Report reusable pieces and gaps.
3. Implement shared provider health/status primitives before UI wiring.
4. Implement wizard/status UI using existing visual patterns.
5. Add tests for provider status reducers and setup ViewModels with fake providers.
6. Run targeted shared and desktop tests.

Acceptance criteria:
- A new user can complete at least one playback path without manually browsing raw Settings.
- Debrid, IPTV, Plex/Jellyfin, and Usenet paths have immediate credential/status checks, even if some providers initially return "not configured" or "unsupported".
- Invalid credentials do not save silently.
- Setup summary clearly says what is ready and what needs attention.
- Provider health shows last check time, status, and next action.
- Secrets are not logged or displayed.
- Advanced settings are hidden by default for touched setup/source areas.

Return only:
- Audit findings
- Files changed
- Acceptance criteria satisfied
- Tests added/run
- Manual smoke remaining
- Known gaps
```

### Prompt 3: Secure Multi-Device Setup, Source-Aware AI, Cross-Device Downloads

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only Phase 3 from this prompt.

Goal:
Make mobile/TV/desktop feel like one product without plaintext secret sync. A user should set up credentials once, pair another device by QR, search across what is actually available, and use desktop downloads from TV/mobile.

Scope:
1. Encrypted QR credential transfer.
   - Device A creates a short-lived transfer session and displays QR.
   - Device B scans QR and joins the transfer.
   - Use ephemeral key exchange.
   - Sender encrypts selected secrets locally.
   - Receiver decrypts locally and saves to its own secret store.
   - Backend may relay encrypted payloads/session metadata only.
   - Backend must never see raw credentials.
   - Payload TTL should be short, recommended 10 minutes.
   - Payload should be one-time use.
   - User chooses categories to transfer: debrid, IPTV, Plex/Jellyfin, Trakt/SIMKL, AI keys, Panda config/management token where safe.
   - Add an audit trail with device name, timestamp, and categories only.
2. Source-aware AI search.
   - AI should parse intent into structured filters.
   - Deterministic app code should rank available results.
   - Availability sources should include local library, downloads, Plex/Jellyfin, debrid cache/inventory, Stremio/addon availability, Usenet warm/ready state, IPTV live/EPG, watch history/profiles where already available.
   - AI must not receive secrets.
   - Results should explain why they are shown: local, downloaded, cached debrid, Plex/Jellyfin, IPTV on now, Usenet ready.
   - If nothing is available, suggest setup/repair actions instead of generic TMDB-only results.
3. Cross-device downloads/LAN library.
   - Desktop should advertise downloaded media metadata without exposing raw local paths.
   - Desktop should expose downloaded media over an authenticated local LAN route if feasible.
   - TV/mobile should be able to show "available on desktop" metadata when synced/discovered.
   - Playback route chooser should prefer local file, then LAN desktop stream, then original provider stream, then re-download prompt.
   - Add storage safety controls where touched: configured folders only, quotas or cleanup hooks if practical, mobile data guard in presentation state if practical.

Likely implementation areas:
- shared/src/commonMain/kotlin/com/torve/domain/security/
- desktopApp/src/main/kotlin/com/torve/desktop/security/
- shared/src/commonMain/kotlin/com/torve/data/account/
- shared/src/commonMain/kotlin/com/torve/data/ai/
- desktopApp/src/main/kotlin/com/torve/desktop/ui/v2/search/
- shared/src/commonMain/kotlin/com/torve/domain/repository/AvailabilityRepository.kt
- shared/src/commonMain/kotlin/com/torve/data/acceleration/
- shared/src/commonMain/kotlin/com/torve/data/integrations/
- desktopApp/src/main/kotlin/com/torve/desktop/download/
- desktopApp/src/main/kotlin/com/torve/desktop/library/
- shared/src/commonMain/kotlin/com/torve/data/download/
- shared/src/commonMain/kotlin/com/torve/presentation/download/
- androidApp/src/main/
- androidApp/src/tv/
- iosApp/iosApp/Views/Sync/
- server/backend endpoints if this repo contains them and they are required

Out of scope:
- Do not implement IPTV DVR/recording.
- Do not do broad Settings redesign.
- Do not implement public release signing/auto-update.
- Do not weaken local-only secret storage by syncing plaintext credentials.

Required workflow:
1. Start with a read-only audit of existing secret stores, account sync, device management, QR/pairing screens, AI search, availability/source models, downloads, and LAN/network helpers.
2. If backend support for encrypted relay sessions is missing, stop after producing the client/server protocol and implement only local cryptographic primitives/tests unless backend code is present in this repo.
3. Implement cryptographic/session primitives with tests before UI.
4. Implement availability graph/ranking before AI UI changes.
5. Implement download metadata/route model before LAN streaming UI.
6. Add tests for crypto, redaction, AI ranking, availability badges, and route selection.
7. Run targeted shared/desktop/mobile tests where practical.

Acceptance criteria:
- No raw credential is sent to backend, logs, diagnostics, crash reports, or AI providers.
- QR transfer can expire, be used once, and fail safely.
- Transfer import failure leaves existing credentials untouched.
- AI search prefers actually available/playable items over generic catalog results.
- AI result cards explain availability.
- Download metadata sync does not leak filesystem paths.
- Route chooser can distinguish local file, LAN desktop stream, provider stream, and unavailable.
- Storage operations cannot delete outside configured download folders.

Return only:
- Audit findings
- Protocol/design summary if backend support is involved
- Files changed
- Acceptance criteria satisfied
- Tests added/run
- Manual smoke remaining
- Known gaps
```

### Prompt 4: TV-First UX, IPTV DVR, Public Launch Hardening

```text
Read docs/aio-value-roadmap-implementation-plan.md, but implement only Phase 4 from this prompt.

Goal:
Make Torve feel valuable from the couch and safe to sell publicly. TV should be the primary consumption surface; desktop should be the hub; release builds should be signed, updateable, supportable, and legally/privacy clear.

Scope:
1. TV-first UX.
   - Build/upgrade TV Home around outcomes:
     - Continue Watching
     - Available Now
     - Live TV
     - Downloads on Desktop
     - Recently Added From My Sources
     - Provider Health
   - Add remote-first source picker.
   - Add one-click best source autoplay with clear fallback.
   - Add quick setup pairing via QR if Phase 3 primitives exist.
   - Add non-blocking provider issue banners.
   - Keep advanced forms out of the main TV flow.
2. IPTV toward DVR-grade.
   - Add desktop recording support where feasible.
   - Add recording schedule and one-off recording from EPG.
   - Add series pass model if feasible.
   - Add conflict detection if multiple recordings/tuners are relevant.
   - Improve timeshift/catch-up diagnostics.
   - Add channel logo matching/manual correction where feasible.
   - Add EPG correction tools where feasible:
     - EPG time offset.
     - Manual playlist channel to EPG channel mapping.
     - Hide bad channels/categories.
     - Detect stale EPG.
   - Add parental/profile visibility controls for live TV where the profile model supports it.
3. Public release hardening.
   - Windows signing path or clear signing-ready Gradle/config hooks.
   - macOS signing/notarization path or clear signing-ready Gradle/config hooks.
   - Real auto-update apply path, not just "view release", where feasible.
   - Stable/beta/internal release channel support.
   - Crash breadcrumbs for major user actions with strict redaction.
   - Privacy policy, terms, account deletion, data export, subscription management links if missing.
   - Support-ready diagnostics export and troubleshooting flow.

Likely implementation areas:
- androidApp/src/tv/
- androidApp/src/main/kotlin/com/torve/android/ui/
- shared/src/commonMain/kotlin/com/torve/presentation/home/
- shared/src/commonMain/kotlin/com/torve/presentation/channels/
- shared/src/commonMain/kotlin/com/torve/data/channels/
- desktopApp/src/main/kotlin/com/torve/desktop/ui/v2/live/
- desktopApp/src/main/kotlin/com/torve/desktop/reminders/
- desktopApp/src/main/kotlin/com/torve/desktop/download/
- desktopApp/build.gradle.kts
- desktopApp/src/main/kotlin/com/torve/desktop/diagnostics/
- desktopApp/src/main/kotlin/com/torve/desktop/updates/
- desktopApp/src/main/kotlin/com/torve/desktop/diagnostics/DiagnosticsExporter.kt
- docs/
- backend/server account deletion/export endpoints if this repo contains them

Out of scope:
- Do not reimplement encrypted credential transfer if Phase 3 is not present; only consume existing primitives.
- Do not rebuild AI search unless needed to show "Available Now" surfaces.
- Do not weaken release/privacy posture for convenience.
- Do not bundle or promote unauthorized content sources.

Required workflow:
1. Start with a read-only audit of TV UX, IPTV guide/EPG/catch-up, recording/download primitives, release signing/update code, diagnostics, privacy/account deletion/export docs or endpoints.
2. Split implementation into safe sub-slices if the full scope is too large:
   - TV Home/source picker
   - IPTV recording/EPG correction
   - Release hardening/diagnostics/legal links
3. Implement shared presentation models first where TV and desktop both need the same state.
4. Preserve existing mobile and desktop behavior unless acceptance criteria require a change.
5. Add TV focus tests, IPTV scheduler/parser tests, diagnostics redaction tests, and update/signing config tests where practical.
6. Run targeted Android TV/shared/desktop checks.

Acceptance criteria:
- TV user can understand what is available and start a known available item in fewer than three remote actions from Home.
- TV source picker is remote-first and has clear fallback if the best source fails.
- Provider issues on TV are visible but non-blocking.
- User can schedule at least a one-off IPTV recording from desktop EPG if recording is implemented in this slice.
- EPG/catch-up issues produce actionable diagnostics.
- Release builds have a concrete signing and update path.
- Crash breadcrumbs are useful but do not leak secrets or tokenized URLs.
- Account deletion/export/privacy/subscription links are present or explicitly reported as backend/product gaps.

Return only:
- Audit findings
- Files changed
- Acceptance criteria satisfied
- Tests added/run
- Manual smoke remaining
- Known gaps
```

## Milestones

### Phase 0: Playback And Release Foundation

Objective: remove the biggest trust killer: install app, click play, nothing works.

Scope:

- Pick the default desktop playback path.
- Short-term default: bundle a known-good VLC runtime for Windows builds and keep MPV as optional experimental fallback.
- Medium-term default: finish embedded MPV rendering and make MPV the preferred engine after parity testing.
- Add a first-run playback self-test that verifies runtime discovery, codec path, audio output, and a local synthetic media probe.
- Fail with a clear repair path if playback cannot start.
- Include required VLC/mpv license notices in packaged distributions.
- Add packaging CI checks that fail if the default runtime is missing.

Implementation targets:

- `desktopApp/runtime/windows/vlc/`
- `desktopApp/WINDOWS_PACKAGING.md`
- `desktopApp/MPV_BUNDLING.md`
- `desktopApp/build.gradle.kts`
- `desktopApp/src/main/kotlin/com/torve/desktop/playback/`
- `desktopApp/src/main/kotlin/com/torve/desktop/player/`
- `desktopApp/src/main/kotlin/com/torve/desktop/mpv/`
- `desktopApp/src/main/kotlin/com/torve/desktop/vlc/`

Acceptance criteria:

- Fresh Windows install plays a test video without installing VLC or mpv separately.
- Missing/corrupt runtime is caught before the user opens a movie.
- Playback error copy tells the user what failed and what Torve can do next.
- Packaged app contains required third-party licenses.
- CI blocks a release package if runtime files are absent.

Tests:

- Unit tests for runtime discovery and fallback.
- Packaging smoke test for runtime presence.
- Manual playback matrix: local file, debrid URL, addon-hosted URL, IPTV HLS, subtitles, fullscreen, audio track switch.

### Phase 1: True Credential-First Setup Wizard

Objective: replace settings-first setup with guided onboarding.

Scope:

- Build a setup wizard with four top-level user intents:
  - "I have Real-Debrid / AllDebrid / Premiumize / TorBox"
  - "I have IPTV"
  - "I have Plex or Jellyfin"
  - "I have Usenet / Easynews / NZB indexers"
- Each setup path must test credentials immediately and return green/yellow/red diagnostics.
- Wizard should recommend the simplest path first and defer advanced fields.
- Persist partial setup progress so the user can leave and return.
- After setup, show a single "Ready to watch" summary with what works now.

Implementation targets:

- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/`
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/panda/`
- `shared/src/commonMain/kotlin/com/torve/presentation/setup/`
- `shared/src/commonMain/kotlin/com/torve/presentation/settings/`
- `shared/src/commonMain/kotlin/com/torve/data/panda/`
- `shared/src/commonMain/kotlin/com/torve/data/debrid/`
- `shared/src/commonMain/kotlin/com/torve/data/channels/`
- `shared/src/commonMain/kotlin/com/torve/data/integrations/`

Acceptance criteria:

- A new user can complete at least one playback path without opening Settings.
- Every credential test produces a clear status and next action.
- Invalid credentials never save silently.
- A user with only IPTV can enter the app.
- A user with only debrid/addons can enter the app.
- A user with only Plex/Jellyfin can enter the app.

Tests:

- ViewModel tests for every setup path.
- Fake provider tests for success, invalid credential, timeout, unsupported provider, and partial outage.
- UI smoke tests for wizard resume and completion.

### Phase 2: Encrypted Cross-Device Credential Transfer

Objective: keep "set up once, use everywhere" without plaintext secret sync.

Scope:

- Add device-to-device QR pairing for credential transfer.
- Use ephemeral key exchange between sender and receiver.
- Sender encrypts selected secrets locally.
- Receiver decrypts locally and saves into its own secret store.
- Backend only relays encrypted payloads or pairing metadata; it must never see raw secrets.
- Let users choose what to transfer: debrid, IPTV, Plex/Jellyfin, Trakt/SIMKL, AI keys, Panda config management token.
- Add transfer audit trail: device name, timestamp, categories transferred, no raw values.

Architecture:

- Device A creates a transfer session with an ephemeral public key and displays QR.
- Device B scans QR, posts its ephemeral public key, and proves signed-in account/device identity.
- Device A encrypts selected secrets with a derived shared key and uploads a short-lived encrypted payload.
- Device B downloads once, decrypts, imports, and the backend expires the payload.
- Payload TTL: 10 minutes.
- One-time use only.

Implementation targets:

- Backend: new credential-transfer session endpoints.
- `shared/src/commonMain/kotlin/com/torve/domain/security/`
- `desktopApp/src/main/kotlin/com/torve/desktop/security/`
- `androidApp/src/main/`
- `iosApp/iosApp/Views/Sync/`
- `shared/src/commonMain/kotlin/com/torve/data/account/`

Acceptance criteria:

- No raw credential appears in backend logs, request bodies after encryption, crash reports, diagnostics export, or account settings sync.
- QR transfer works desktop to TV, mobile to TV, desktop to mobile.
- Transfer can be revoked/expired.
- Import failure is safe and leaves existing secrets untouched.

Tests:

- Crypto round-trip tests.
- Payload tamper tests.
- Expired/used session tests.
- Device mismatch tests.
- Diagnostics redaction tests.

### Phase 3: Provider Health Screen

Objective: users should know what is broken before they blame the app.

Scope:

- Add a Provider Health dashboard available from Home, Settings, and TV status menu.
- Show status cards for:
  - Debrid providers
  - Stremio/addon manifests
  - Panda/Usenet provider
  - NZB indexers
  - Download clients
  - IPTV playlist fetch
  - EPG fetch and matching
  - Plex/Jellyfin server and token
  - Trakt/SIMKL sync
  - Last successful playback
  - Last failed playback with reason
- Use green/yellow/red status and one clear next action per issue.
- Store recent health events locally and sync non-sensitive summaries to account devices.

Implementation targets:

- `shared/src/commonMain/kotlin/com/torve/domain/providerhealth/`
- `shared/src/commonMain/kotlin/com/torve/presentation/providerhealth/`
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/v2/settings/`
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/v2/home/`
- `androidApp/src/main/kotlin/com/torve/android/ui/`
- `shared/src/commonMain/kotlin/com/torve/data/*`

Acceptance criteria:

- Every major provider has a test button and last-check timestamp.
- Playback failure records enough structured detail to show a useful diagnosis.
- Health screen redacts secrets.
- TV can show a simplified provider status view without exposing advanced settings.

Tests:

- Health reducer tests.
- Fake provider status tests.
- Redaction tests.
- UI snapshot/smoke tests for green/yellow/red states.

### Phase 4: Source-Aware AI Search

Objective: make AI search useful, not a gimmick.

Scope:

- AI search must consider what the user can actually watch.
- Query examples:
  - "Show me 90-minute sci-fi available from my debrid cache or local library tonight"
  - "Find kid-safe live sports channels on now"
  - "Find movies in my Plex library that I have not watched"
  - "Find German 4K sources that are cached"
- Build an availability graph that combines:
  - Local library
  - Downloads
  - Plex/Jellyfin overlay
  - Debrid cache inventory
  - Stremio/addon source availability
  - Usenet warm/ready state
  - IPTV live/EPG availability
  - Watch history and profiles
- AI should parse intent into structured filters, then deterministic code should rank available results.
- Bring-your-own AI key remains supported, but the product should also support a Torve-hosted AI option if commercial/legal/privacy constraints allow it.

Implementation targets:

- `shared/src/commonMain/kotlin/com/torve/data/ai/`
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/v2/search/`
- `shared/src/commonMain/kotlin/com/torve/domain/model/SourceAccelerationModels.kt`
- `shared/src/commonMain/kotlin/com/torve/data/acceleration/`
- `shared/src/commonMain/kotlin/com/torve/data/integrations/`
- `shared/src/commonMain/kotlin/com/torve/domain/repository/AvailabilityRepository.kt`

Acceptance criteria:

- AI results prefer actually playable or locally available items.
- Search explains why a result is shown: local, downloaded, cached debrid, Plex/Jellyfin, IPTV on now, Usenet ready.
- If nothing is available, AI suggests setup actions instead of showing generic TMDB results.
- Sensitive-query gates still apply.

Tests:

- Prompt parser tests with fixed AI fixtures.
- Ranking tests for local/downloaded/cached/uncached sources.
- Privacy tests: no secrets sent to AI provider.
- UI tests for availability badges and explanation copy.

### Phase 5: IPTV Toward DVR-Grade

Objective: compete with serious IPTV apps instead of only parsing playlists.

Scope:

- Add recording support on desktop.
- Add recording schedule, series pass, and conflict detection.
- Add timeshift reliability checks and better catch-up repair copy.
- Add channel logo matching and manual correction.
- Add EPG correction tools:
  - Shift EPG by offset.
  - Map playlist channel to EPG channel.
  - Hide bad channels/categories.
  - Detect stale EPG.
- Add parental profiles for live TV.
- Add fast channel switching and remote-control first TV guide behavior.

Implementation targets:

- `shared/src/commonMain/kotlin/com/torve/data/channels/`
- `shared/src/commonMain/kotlin/com/torve/presentation/channels/`
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/v2/live/`
- `desktopApp/src/main/kotlin/com/torve/desktop/reminders/`
- New desktop recording scheduler/service.
- Android TV live guide surfaces.

Acceptance criteria:

- User can schedule a one-off recording from the guide.
- User can create a series pass.
- Recordings appear in the library and can be played locally.
- EPG mismatch can be fixed without editing XML/M3U manually.
- TV guide is usable entirely by remote.

Tests:

- EPG parser and mapping tests.
- Recording scheduler tests.
- Conflict detection tests.
- Timeshift URL tests.
- TV focus tests for guide navigation.

### Phase 6: Cross-Device Downloads And LAN Library

Objective: make desktop downloads valuable on TV/mobile.

Scope:

- Desktop exposes downloaded media over a local authenticated LAN endpoint.
- Mobile/TV clients discover desktop hub when on same network.
- Account sync advertises downloaded item metadata without exposing local paths.
- Playback chooses the best route:
  - Local device file
  - LAN desktop stream
  - Original provider stream
  - Re-download prompt
- Add storage controls:
  - Per-category folders
  - Quotas
  - Auto-delete watched downloads
  - Mobile data guard
  - Subtitle/metadata sidecar handling
- Add "available on desktop" badges in TV/mobile library.

Implementation targets:

- `desktopApp/src/main/kotlin/com/torve/desktop/download/`
- `desktopApp/src/main/kotlin/com/torve/desktop/library/`
- `shared/src/commonMain/kotlin/com/torve/data/download/`
- `shared/src/commonMain/kotlin/com/torve/presentation/download/`
- Backend/account metadata sync for download catalog summaries.
- Android/iOS/TV playback route chooser.

Acceptance criteria:

- TV can play a desktop-downloaded file over LAN without manual file sharing.
- Mobile can see which downloads exist on desktop.
- Download metadata sync does not leak filesystem paths.
- Storage cleanup never deletes user files outside configured folders.

Tests:

- LAN auth tests.
- Route selection tests.
- Storage boundary tests.
- Download catalog sync tests.

### Phase 7: Settings Simplification

Objective: make the app feel simple even if the engine is powerful.

Scope:

- Replace flat Settings sprawl with:
  - Account
  - Sources
  - Playback
  - Downloads
  - Profiles and Safety
  - Diagnostics
  - Advanced
- Move rarely changed settings into Advanced.
- Add setup summary cards instead of raw form fields.
- Add "Repair" actions that route to the relevant wizard step.
- Add profile-specific defaults for quality, language, captions, parental limits, and live TV visibility.

Implementation targets:

- `desktopApp/src/main/kotlin/com/torve/desktop/ui/v2/settings/V2SettingsPage.kt`
- Android Settings screens.
- iOS Settings screens.
- Shared settings state models.

Acceptance criteria:

- New user can understand configured sources in under 30 seconds.
- Advanced mode is hidden by default.
- Every source card has status, edit, repair, remove, and test actions.
- No duplicate credential entry points.

Tests:

- Settings state tests.
- UI smoke tests for source cards and Advanced toggle.
- Migration tests from existing settings keys.

### Phase 8: Public Release Hardening

Objective: ship something users can trust and update.

Scope:

- Windows Authenticode signing.
- macOS Developer ID signing and notarization.
- Real auto-update install path:
  - WinSparkle or equivalent for Windows.
  - Sparkle for macOS.
  - AppImage update or documented package repository for Linux.
- Crash reporting with user-action breadcrumbs and strict redaction.
- In-app diagnostics export that excludes secrets.
- Privacy policy, terms, account deletion, data export, and subscription management links.
- Release channel support: stable, beta, internal.
- Store/support readiness:
  - FAQ
  - Provider status page
  - Troubleshooting docs
  - Contact/support flow

Implementation targets:

- `desktopApp/build.gradle.kts`
- `desktopApp/src/main/kotlin/com/torve/desktop/diagnostics/`
- `desktopApp/src/main/kotlin/com/torve/desktop/updates/`
- `desktopApp/src/main/kotlin/com/torve/desktop/diagnostics/DiagnosticsExporter.kt`
- Backend account deletion/export endpoints if missing.
- Public docs/site assets.

Acceptance criteria:

- User can install and update without warnings on Windows/macOS.
- Crash reports contain useful breadcrumbs but no credentials or source URLs with tokens.
- User can delete account and export data.
- Release can be promoted from beta to stable without rebuilding code.

Tests:

- Diagnostics redaction tests.
- Update feed parser and installer tests.
- Signed package verification in CI.
- Account deletion/export integration tests.

### Phase 9: TV UX First

Objective: make the main value obvious from the couch.

Scope:

- Build TV home around outcomes:
  - Continue Watching
  - Available Now
  - Live TV
  - Downloads on Desktop
  - Recently Added From My Sources
  - Provider Health
- Add remote-first source picker.
- Add one-click best source autoplay with clear fallback.
- Add quick setup pairing via mobile/desktop QR.
- Add provider issue banners that do not block browsing.
- Add TV-safe settings summary and hide advanced forms.
- Keep voice search and keyboard search as accelerators.

Implementation targets:

- `androidApp/src/tv/`
- `androidApp/src/main/kotlin/com/torve/android/ui/`
- Shared presentation state for provider health, source availability, and setup summary.
- QR pairing flow from Phase 2.

Acceptance criteria:

- TV user can pair credentials from another device without typing long API keys.
- TV user can start playback in fewer than three remote actions from Home for a known available item.
- TV user can understand source/provider problems without opening advanced settings.
- TV focus tests cover Home, source picker, Live TV guide, setup, and diagnostics.

Tests:

- TV focus mutation tests.
- Remote navigation smoke tests.
- Pairing flow tests.
- Playback route tests.

## Dependency Order

1. Playback/release foundation must come first.
2. Setup wizard and provider health can progress in parallel.
3. Credential transfer depends on secret-store inventory and backend session endpoints.
4. Source-aware AI depends on provider health and availability graph.
5. IPTV DVR and cross-device downloads depend on storage/route models.
6. Settings simplification should follow the wizard and provider health model, otherwise it will be reorganizing unstable surfaces.
7. TV UX should start immediately as design/prototyping, but implementation should consume the setup, health, and pairing primitives.

## Suggested Delivery Slices

### Slice A: Trust Baseline

- Bundle working playback runtime.
- Add runtime self-test.
- Add diagnostics export redaction tests.
- Add provider health model skeleton.

Ship value: app installs and playback does not feel fragile.

### Slice B: Credential-Only Setup

- Add setup wizard paths for debrid, IPTV, Plex/Jellyfin, and Usenet.
- Add immediate credential tests.
- Add setup summary cards.

Ship value: users stop starting in Settings.

### Slice C: Multi-Device Setup

- Add QR credential transfer.
- Add TV pairing flow.
- Add transfer audit trail.

Ship value: mobile/TV become real added value.

### Slice D: Availability Intelligence

- Add availability graph.
- Upgrade AI search to source-aware ranking.
- Add provider health dashboard.

Ship value: Torve answers "what can I watch right now?" better than competitors.

### Slice E: Power Features

- IPTV recording/series pass.
- Cross-device downloads over LAN.
- Advanced storage controls.

Ship value: desktop becomes the household media hub.

### Slice F: Public Launch

- Signed installers.
- Real auto-update.
- Account deletion/export.
- Stable/beta channels.
- Support docs and provider status copy.

Ship value: product can be sold without manual support for every install.

## Open Decisions

- Default engine: ship bundled VLC now and finish embedded MPV later, or delay launch until MPV is production default.
- Backend relay for encrypted credential transfer: use Torve backend short-lived sessions or direct local-network transfer only.
- AI monetization: BYO key only, Torve-hosted AI included, or premium add-on.
- IPTV recording scope: local desktop-only first, or cross-device scheduling from TV/mobile on day one.
- Downloads LAN serving: HTTP range server only, or DLNA/WebDAV-compatible exposure.
- Public positioning: general AIO media hub, or narrower "credential-first media control center" to reduce legal/store risk.

## Non-Negotiable Launch Gates

- Fresh install plays video without external runtime installation.
- Setup wizard verifies at least one provider path before showing the main app as ready.
- No credential is synced plaintext.
- Provider health exists and explains failures.
- TV pairing works without typing long API keys.
- Diagnostics export redacts tokens, credentials, manifest URLs with secrets, and local sensitive paths.
- Signed installer and update story are ready for public desktop release.
