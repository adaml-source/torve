# Torve Physical-Device Performance and E2E Report

Test window: 2026-07-15 to 2026-07-16
Builds tested: Amazon TV `1.1.2 (20095)`; Google Mobile `1.1.2 (10095)`

## Executive verdict

The final candidate is suitable for a **controlled Amazon Fire TV and Google Mobile staged rollout**. The reproduced application-level blockers are fixed, the full automated Android/shared matrix is green, and fresh signed releases completed physical core-path validation on Fire TV `110`, Fire TV `142`, and Samsung `151`.

- Fire TV `110` and `142` run the exact same Amazon APK bits. Channels zero-result recovery, player Back behavior, Search, live playback, and primary navigation pass. Fire `142` still has a high threshold-based jank percentage during short transitions, but its former 150-450 ms Channels stalls are reduced to a 34 ms warm p95/p99 and idle Search/player rendering is zero frames.
- Samsung `151` cold-launched to the true top of Home in `323 ms`, resolved House of the Dragon to the Stream Actions sheet, and started decoded video/audio in Torve. The earlier generic error was optional detail metadata state, not a failed stream resolver.
- Google TV APK/AAB compilation, release lint, minification, signing, and tests pass. A physical Google TV was not connected, so public Google TV rollout remains gated on one hardware smoke pass.

No app crash, ANR, OOM, or fatal playback error was observed in the final physical passes. Public store submission still requires normal operator gates such as a committed/tagged release source, store pre-launch review, and production configuration/metadata verification.

## Final release-candidate verification (2026-07-16)

This section is the current result and supersedes both the initial baseline and the intermediate post-fix result retained later in this document.

### Final candidate identity

| Target | Release artifact | Size | SHA-256 | Verification |
|---|---|---:|---|---|
| Amazon TV | `androidApp-amazon-tv-release.apk` | 91,830,611 bytes | `37c5a26adc3343d19ae12c61f4be6b2ea2e98a472ea482c97923c484409f42e7` | Installed `base.apk` matched exactly on both Fire `110` and `142` |
| Google TV | `androidApp-google-tv-release.apk` | 94,142,979 bytes | `2d44cbbc19fea1ef7dbfff0f78e0d1c66c779d3d3bb978d7370d64839122a3e0` | Release built; no physical Google TV connected |
| Google TV | `androidApp-google-tv-release.aab` | 60,322,452 bytes | `3c38c4535159013a76adf84be7a5b151df7ab1f05d8c16a51dbf7f44d7047961` | Bundle, signing, lint-vital, and minification passed |
| Google Mobile | `androidApp-google-mobile-release.apk` | 92,541,201 bytes | `c635085144afd69d1d422648e9fb58c9a6ac883716ba26c629820a25d107d55e` | Installed `base.apk` matched exactly on Samsung `151` |
| Google Mobile | `androidApp-google-mobile-release.aab` | 56,972,016 bytes | `48d77c0eae7db66dbe58c4232971ad82260d8e37dcd8c10c17a21b5ccf4ff5bf` | Bundle, signing, lint-vital, and minification passed |

Amazon TV version is `1.1.2 (20095)` and Google Mobile is `1.1.2 (10095)`. Installation used `adb install -r`, preserving device data.

### Final fixes and physical outcomes

| Area | Clean implementation | Physical result | Verdict |
|---|---|---|---|
| Large-playlist Channels search | Removed the eager full-playlist UI-state load at two characters; moved unified channel/EPG projection to a cancellable, debounced `Dispatchers.Default` job; discard stale debounced results when the query changes | `zzq` remained remote-recoverable on both Fires. On `142`, warm p95/p99 fell from the earlier `150/450 ms` sample to `34/34 ms`; no long stalls remained | Pass; monitor short-transition jank percentage |
| TV Search background work | Removed the selected synopsis' infinite automatic scroll animation | Fire `142` rendered `0` UI frames during a 10-second idle Search sample. Focus traversal had p50/p90/p95/p99 `18/19/20/22 ms` with no missed Vsync | Pass |
| TV player wakeups and exit | Use a one-second TV position tick while retaining ten-second progress persistence; keep deterministic Back/focus policy | Fire `142` reached `PLAYING_CONFIRMED`; a 12-second settled sample rendered `0` UI frames, and one short Back returned to Channels. Entry transition p95/p99 was `25/150 ms` | Functional pass; transition tail remains |
| Mobile source/playback funnel | Shared addon capability/state fix is retained in the fresh candidate | House of the Dragon reached Stream Actions by the fixed 10-second inspection, then initialized Samsung H.264 video and AAC audio decoders; `playback_ready`, `is_playing_changed`, and `audio_position_advancing` all confirmed | Pass |
| Mobile startup and Home position | Removed the custom splash, retained cache-first Home, deferred nonessential startup work, and consume a one-shot item-zero reset only on process launch | Fresh candidate cold activity start was `323 ms`; the first post-launch inspection showed the fully populated Hero at the true top. In-session tab/detail navigation still preserves scroll state | Pass |

The Samsung player-entry sample covered 104 transition frames with 20.19% modern jank and p50/p90/p95/p99 `17/28/34/97 ms`. This is not a steady-state playback regression: decoding and audio advancement confirmed, and the earlier whole-process playback sample was 5.98% jank over 9,117 frames.

### Final automated verification

- `:shared:allTests` passed. The two stale Channels startup fixtures were updated to the intentional background-refresh contract, and a stale-query regression test was added.
- `:androidApp:testAmazonTvDebugUnitTest`, `:androidApp:testGoogleTvDebugUnitTest`, and `:androidApp:testGoogleMobileDebugUnitTest` passed.
- Amazon TV, Google TV, and Google Mobile release APK assembly passed.
- Google TV and Google Mobile release AAB bundling passed, including lint-vital, R8/minification, signing, and Crashlytics mapping work.
- Targeted `git diff --check` passed; only line-ending normalization warnings were emitted.

### Launch verdict, expected outcome, and ROI

| Platform/fix | Launch verdict | Expected user outcome | ROI |
|---|---|---|---|
| Amazon Fire TV `110`/`142` | **GO for staged rollout** | Same tested APK on both hardware classes; recoverable channel search, deterministic player exit, no idle Search/player churn | Very high: removes living-room dead ends and cuts `142` Channels p95 by about 77% and p99 by about 92% in the reproduced warm path |
| Google Mobile / Samsung | **GO for staged rollout** | Sub-0.4-second activity launch in the final sample, Home starts at the Hero, and the normal search-to-decoded-playback funnel works | Very high: improves every cold launch and restores the core watch conversion path |
| Google TV | **CONDITIONAL GO** | Code/package parity benefits are present, but remote focus, playback, and device-specific rendering still need a real Google TV check | High implementation reuse; remaining validation cost is low, but skipping it creates avoidable store/device risk |
| Search/player idle-work reduction | Included in TV candidates | Static screens stay static instead of waking Compose continuously | Medium-high recurring value: smoother focus response plus lower CPU, heat, and power use on constrained TV hardware |
| Full-suite regression coverage | Included in all candidates | Background catalog refresh and rapid query changes retain correct, non-stale UI state | High: small test cost protects startup and search behavior shared across releases |

The remaining items are release-process gates, not reproduced core-app failures: perform a physical Google TV smoke, cut artifacts from a clean committed/tagged revision, validate store listing/privacy/data-safety and production service configuration, then use staged percentages with crash/ANR/startup/playback monitoring. Fire `142`'s isolated transition tail should be watched during rollout; it is no longer a functional blocker, but it is the most performance-sensitive supported device in this matrix.

## Post-fix verification (2026-07-16)

This is the intermediate result. It superseded the initial baseline at the time, but the final release-candidate section above now supersedes it. It is retained to show the before/after progression.

### Candidate identity and installation

| Target | Candidate artifact | SHA-256 | Physical install verification |
|---|---|---|---|
| Amazon TV | `androidApp-amazon-tv-release.apk` | `d1672015fd09b05bd101788720552eccc9650b339396dd3039c7ac58e9595dd5` | Exact installed `base.apk` hash matched on both `110` and `142` |
| Google Mobile | `androidApp-google-mobile-release.apk` | `72dc9ed09f2643d80fc6501b99387a5405badaa5a2fa2e3098e1b440acfe37e7` | Exact installed `base.apk` hash matched on `151` |

Fire TV `110` and `142` therefore ran the same APK bits. Different measurements between them are device/data/runtime effects, not different application builds.

### Fix verification and resulting outcome

| Finding | Implementation | Physical result | Status |
|---|---|---|---|
| Mobile addon incorrectly gated | One shared `canResolveStreams()` capability rule is now used by Settings, Detail, and stream aggregation; Detail also observes the current addon state instead of relying on startup Settings state | After force-stop/restart on `151`, House of the Dragon showed **Play S1E1**, replacing **Install addon**. The resolver logged one enabled Torrentio stream addon and was invoked. It then returned the generic **Something went wrong** state without a specific logged cause, so network/provider failure versus resolver handling is not yet isolated | App-side gate fixed; resolver E2E still open |
| TV player could trap the user after chrome auto-hide | Back behavior is now an explicit consumed -> hide controls -> exit policy; hidden chrome retries focus handoff to the persistent player surface | A restored hidden player exited to Home with one short Back on both Fire TVs. On `142`, resumed playback advanced from 19:30 to 19:40 and the visible-controls/hidden-controls Back sequence returned to Detail instead of locking | Pass |
| Channels zero-result focus loss | Empty-result focus recovery no longer steals focus from the active inner editor | On both `110` and `142`, query `zzq` produced 0 channels while the `EditText` remained focused; three Delete presses cleared it and repopulated the list without touch injection | Pass |
| Blank TV artwork | Blank URLs are normalized to missing and TV progress/watchlist mapping falls back to a nonblank backdrop; search hydration/merge also treats blanks as missing | `Remarkably Bright Creatures` rendered artwork in Continue Watching on `142` instead of the prior empty tile | Pass |
| Movies + Top Rated + 2025 was slow and returned one item | Top Rated now uses the server browse/top-rated path plus a bounded local score/vote policy instead of per-title IMDb verification | `142` returned **40 results** with a `2025 · Movie` result in at most 3.8 s including accessibility-dump overhead, versus roughly 12-15 s and one result before | Pass; at least 68% latency reduction |
| Mobile splash held Home for about 4.4 s | Timings are centralized and bounded to a 1,440 ms animation budget | Physical scheduled captures moved from the portal phase to the Home skeleton by the next approximately 1.8 s sample; the deterministic budget is about 2.96 s / 67% shorter than the observed baseline | Pass |

### Automated and release verification

- New regression coverage passed for addon capability, TV Back policy, blank artwork, Top Rated policy, and splash timing.
- Full `testAmazonTvDebugUnitTest` and `testGoogleMobileDebugUnitTest` tasks passed.
- Focused shared desktop tests passed.
- `assembleAmazonTvRelease` and `assembleGoogleMobileRelease` passed, including release lint-vital and R8/minification work.
- `git diff --check` passed for the targeted implementation files; only the repository's existing LF-to-CRLF warnings were emitted.
- The broad shared suite ran 1,345 tests and retained two failures in the already-modified Channels startup fixtures (`expected refresh 0 but was 1`; `News One` versus `News One HD`). They are outside these fixes and prevent describing the entire dirty-worktree shared suite as green.

### Post-fix performance and stability

| Device/scenario | Result | Assessment |
|---|---:|---|
| `110` Channels regression sample | 736 frames, 2.58% jank; p50/p90/p95/p99 `5/6/7/85 ms` | Good typical rendering; one long-tail class remains |
| `142` zero-result Channels interaction | 47 frames, 42.55% jank; p50/p90/p95/p99 `10/93/150/450 ms` | Functionally fixed, but still rough on this device |
| `142` filtered-search transition | 30 frames, 86.67% jank; p50/p90/p95/p99 `32/73/85/85 ms` | Result latency fixed; transition rendering still unacceptable |
| `142` resumed-player/exit session | 72 frames, 79.17% jank; p50/p90/p95/p99 `19/150/200/250 ms` | Playback and exit work; session jank remains a release risk |
| `151` reachable mobile flow | 51 frames, 1.96% jank; p50/p90/p95/p99 `5/7/15/26 ms` | Smooth |
| End-of-retest PSS | `110` about 150 MB; `142` about 214 MB; `151` about 232 MB | No memory failure; continue monitoring `142` |
| Fatal/ANR/OOM scan | Zero exact matches on all three active processes; crash buffers empty for the packages | Pass |

### Updated release verdict and ROI

The clean candidate fixes the five directly reproducible application defects and substantially shortens the splash. It is **not yet a full cross-device release pass** because Samsung source resolution still ends in an unresolved generic error and Fire TV `142` still shows severe transition/player-session jank. The TV functional release blockers are removed, but the remaining performance and resolver E2E risks should be closed or explicitly accepted before rollout.

| Fix | Expected product outcome | ROI |
|---|---|---|
| Shared addon capability/state | Restore the mobile play funnel for installed stream addons and prevent Settings/Detail/aggregator disagreement | Very high: a small shared rule recovers a previously blocked core conversion/retention path |
| Deterministic TV Back/focus | Users can always leave playback after chrome auto-hide | Very high: removes a living-room release blocker with contained policy/focus changes |
| Channels editor focus | Zero-result searches remain recoverable using only the remote | High: removes a common dead-end on both TV devices |
| Top Rated browse policy | Useful result volume with bounded network/API work | Very high: 1 -> 40 results and at least 68% faster in the reproduced scenario, while avoiding per-title verification calls |
| Blank-artwork normalization | Fewer empty tiles using already-available artwork | Medium-high: visible quality gain with negligible runtime cost |
| 1.44 s splash budget | Home becomes visible about 3 s earlier on cold start | High: repeated UX benefit on every cold launch with no backend cost |

Post-fix evidence is stored in [`fixes-20260716`](../.codex-e2e/fixes-20260716/).

## Devices and builds

| Device | Hardware | Installed build | Test scope |
|---|---|---|---|
| Fire TV `110` | Amazon `AFTR` / raven | `1.1.2 (20095)` | Cold start, all primary routes, search paging/focus, detail, sources, VOD player controls, Channels search, live TV, memory/jank/crash scan |
| Fire TV `142` | Amazon `AFTGAZL` / gazelle | `1.1.2 (20095)` | Full TV navigation, artwork, detail, sources/player, Channels/live TV, search filters/paging, resources and stability |
| Samsung `151` | Samsung `SM-G991B`, 1080 x 2400, about 7.4 GB RAM | `1.1.2 (10095)` | Fresh-install startup, all reachable routes, artwork/detail, extension installation/restart, scrolling/jank, memory and stability |

The tests preserved existing user data. Fire TV `110` had seven configured addons, including Panda and Torrentio. Samsung `151` was a fresh Google Mobile installation and was not signed into a Torve account.

## Acceptance blockers

### Blocker: mobile installed extension is not recognized

On Samsung `151`, the extension installer reports `1 installed`, and Torrentio no longer appears in the Available list. After a full force-stop/restart, movie Detail still says **Install an addon to play**. The installed extension has no visible management row either.

This is a repository/capability-state inconsistency, not stale Detail navigation. It blocks mobile source discovery, source selection, player loading, and player-control testing through the normal UI.

Evidence:

- [Extension screen after restart](../.codex-e2e/resume-20260716/151_extensions_current.png)
- [Detail still reporting no addon](../.codex-e2e/resume-20260716/151_detail_1000.png)
- [Original installation state](../.codex-e2e/mobile_torrentio.png)

### Blocker: Fire TV 142 player control/exit lock

The selected stream remained decoded at roughly 3-8 Mbps without a playback exception, but after controls auto-hid, remote-equivalent D-pad/OK input did not reliably reopen them and repeated short Back presses did not exit. This is a serious living-room usability failure even though decoding itself continued.

Evidence:

- [Player controls](../.codex-e2e/tv_player_controls.png)
- [D-pad center result](../.codex-e2e/tv_player_dpad_center.png)
- [Player accessibility state](../.codex-e2e/tv_player_window.xml)

### High: Channels no-result focus loss on both Fire TVs

Fire TV `110` reproduced the same defect found on `142`. When a query produced zero channels, focus dropped after only `zzq` was entered. Further text and Delete events did nothing because no node remained focused. A diagnostic touch injection was required to focus the field and clear it.

Evidence:

- [110 zero-result state](../.codex-e2e/resume-20260716/110_channels_noresult.png)
- [110 recovered state](../.codex-e2e/resume-20260716/110_channels_recovered.png)
- [142 zero-result state](../.codex-e2e/tv_channels_empty.png)
- [142 recovered state](../.codex-e2e/tv_channels_recovery.png)

### High: Fire TV 142 artwork fallback failure

`Remarkably Bright Creatures` displayed a valid hero/backdrop and metadata but an empty grid poster on TV. The same title rendered both poster and detail artwork correctly on Samsung `151`, isolating the defect to the TV poster mapping/error fallback rather than upstream metadata.

The corresponding code path treats an empty poster string as a usable value, so later usable detail poster/backdrop data may never replace it.

Evidence:

- [142 search result](../.codex-e2e/tv_remarkably_results.png)
- [151 result with poster](../.codex-e2e/mobile_remarkably.png)

### High: restrictive Top Rated search remains unacceptable on 142

Generic search pagination worked beyond the old limits (`40 -> 80 -> 160`). Filtered pagination also advanced (`40 -> 55 -> 66`). However, **Movies + Top Rated + 2025** collapsed to one result after roughly 12-15 seconds. That path did not meet the functional or performance expectation.

Evidence:

- [Generic pagination](../.codex-e2e/tv_search_paged120.png)
- [Top Rated pagination](../.codex-e2e/tv_search_toprated_paged.png)
- [Movies + Top Rated + 2025](../.codex-e2e/tv_toprated_movies_2025.png)

## Performance results

### Fire TV 110

| Scenario | Measurement | Assessment |
|---|---:|---|
| Android cold activity start | `TotalTime 608 ms`, `WaitTime 824 ms` | Good |
| Cold Home readiness | Spinner still visible at 2 s; complete Home by 5 s | Needs improvement |
| Movies route | Spinner at 0.5 s; posters usable by 1.25 s; hero complete by 2.5 s | Acceptable |
| Warm Sports/Library/Settings | Verified populated within 1.2 s | Good |
| Search initial page | 40 results visible in first 0.5 s sample | Good, cache-assisted |
| Search paging | `40 -> 80 -> 120`; next batches present within about 2.0-2.2 s of boundary input | Acceptable |
| Search-scroll rendering | 7.57% jank; p50/p90/p95/p99 `7/13/20/42 ms` | Noticeable but usable |
| Detail page | Functional at 0.5 s; artwork/ratings complete at 1.0 s | Good |
| Detail transition rendering | 54.55% jank over only 22 transition frames; p95 `250 ms` | Visibly rough transition |
| Source discovery | Finding state at about 3.1 s; 126 ready sources visible by about 6.1 s | Slow but usable |
| Selected-source player start | Controls in less than 0.9 s; playback position advancing by about 1.6 s | Good |
| Live TV | Full-screen player entered by first 1.54 s capture; protected video pixels prevented reliable first-frame measurement | UI path acceptable; video timing unmeasured |
| End-of-pass rendering | 4,756 frames, 3.26% jank; p50/p90/p95/p99 `5/7/10/85 ms` | Good typical frames, long-tail spikes |
| End-of-pass memory | About 224 MB PSS from App Summary; about 69.5 MB graphics | Acceptable for device class, monitor graphics |

Search retained focus at the right edge after paging. VOD pause/resume and +/-10 second navigation worked. Player exit was recoverable, but Back hid and reopened controls instead of directly leaving playback; activating the focused back control returned to Search.

Evidence:

- [Cold Home at 2 seconds](../.codex-e2e/resume-20260716/110_app_2000.png)
- [Cold Home at 5 seconds](../.codex-e2e/resume-20260716/110_app_5000.png)
- [Movies at 1.25 seconds](../.codex-e2e/resume-20260716/110_movies_1250.png)
- [Search at 120 results/right edge](../.codex-e2e/resume-20260716/110_search_paged_edge.png)
- [Detail at 1 second](../.codex-e2e/resume-20260716/110_detail_1000.png)
- [Source picker](../.codex-e2e/resume-20260716/110_play_3000.png)
- [Paused player with decoded video](../.codex-e2e/resume-20260716/110_player_paused.png)
- [Player exit result](../.codex-e2e/resume-20260716/110_player_exit.png)
- [Sports](../.codex-e2e/resume-20260716/110_sports.png), [Library](../.codex-e2e/resume-20260716/110_library.png), [Settings](../.codex-e2e/resume-20260716/110_settings.png)

### Fire TV 142

| Scenario | Measurement | Assessment |
|---|---:|---|
| Android cold activity start | `TotalTime 378 ms`, `WaitTime 503 ms` | Good |
| Cache-first IPTV readiness | About `55 ms` after subsystem initialization | Good |
| Deferred live-category verification | About 24.5 s; background refresh about 2 minutes | Heavy background work; did not block Home |
| VOD source to player | About 13.6 s to player; about 20 s until playback active | Too slow |
| Cached channel group | About 76 ms; logos under 1 s | Good |
| Live-TV first frame | About 1.4 s | Good |
| Player/rendering sample | 1,276 frames, 59.25% jank; p50/p90/p95/p99 `17/29/48/250 ms` | Unacceptable in sampled player transition/session |
| Sample memory components | Java heap ~93.7 MB, native heap ~41.1 MB, graphics ~61.0 MB | High but not an OOM |

The TV pass also verified all primary routes, Library variants, Sports, Settings, channel group search, generic search paging, and right-edge focus retention. Catalogue/live performance was mixed: most cached routes were fast, while restrictive IMDb verification and player startup were slow.

### Samsung 151

| Scenario | Measurement | Assessment |
|---|---:|---|
| Android activity display | Roughly 0.2-0.4 s | Good process start |
| Usable cold Home | Deliberate animated splash held UI for about 4.4 s; Home appeared between 3 and 5 s | Release-blocking UX delay |
| Sustained poster-grid scrolling | About 2.1% jank; p95/p99 `14/19 ms` | Smooth |
| Resume/retest rendering | 6,023 frames, 3.72% jank; p50/p90/p95/p99 `7/11/13/22 ms` | Good |
| Resume/retest memory | `260,640 KB` PSS, `405,678 KB` RSS; graphics `112,778 KB` | Material footprint; no memory failure |
| Artwork/detail | Poster and detail artwork rendered correctly | Pass |
| Sources/player | Blocked by installed-addon capability mismatch | Fail / not testable |

The phone remained alive with no crash-buffer entry, ANR, OOM, or playback exception. The result applies to browsing and reachable settings/detail flows; it is not a player-performance pass because normal source discovery cannot start.

Evidence:

- [Splash](../.codex-e2e/resume-20260716/151_restart_1s.png)
- [Usable Home](../.codex-e2e/resume-20260716/151_restart_45s.png)
- [Correct mobile poster](../.codex-e2e/mobile_remarkably.png)
- [Mobile detail](../.codex-e2e/mobile_detail_1.png)
- [Extension repository state](../.codex-e2e/resume-20260716/151_extensions_current.png)

## Stability and navigation summary

| Area | 110 | 142 | 151 |
|---|---|---|---|
| App startup | Pass, slow Home completion | Pass | Process fast, splash too long |
| Primary navigation | Pass | Pass | Pass |
| Poster rendering | Pass in sampled paths | Fail for known title | Pass |
| Generic search paging | Pass to 120 | Pass to 160 | Reachable browsing smooth |
| Restrictive search | Not repeated | Fail for Movies + Top Rated + 2025 | Not part of mobile UI pass |
| Channels no-result recovery | Fail without diagnostic tap | Fail/requires extra recovery | No channel source configured on fresh install |
| VOD source discovery | Pass, source list slow | Pass, slow | Blocked |
| VOD playback | Pass | Decode pass, control/exit fail | Blocked |
| Live TV | Pass; protected first frame unmeasured | Pass at ~1.4 s | Not configured |
| Crash/ANR/OOM | None observed | None observed | None observed |

## Recommended release order

1. Fix the mobile extension repository/capability mismatch and add a regression test covering install -> Detail -> force-stop/restart -> Detail.
2. Fix TV player input ownership after controls auto-hide and make Back exit semantics deterministic.
3. Fix Channels empty-result focus so the search field remains or regains focus and can always be cleared with a remote.
4. Normalize blank poster URLs to missing values and permit detail/backdrop fallback on TV.
5. Profile and bound restrictive IMDb paging, especially Movies + Top Rated + year filters.
6. Remove or shorten the mobile animated splash; the underlying activity is already displayed quickly.
7. Re-run the same physical-device matrix and require successful mobile source/player coverage before release approval.

## Method and limitations

- Timings were collected with host-side ADB input plus scheduled screenshots, Android `am start -W`, accessibility trees, logs, `gfxinfo`, and `meminfo`.
- Screenshot pull/encoding adds overhead. Values tied to scheduled captures are conservative upper bounds, not microbenchmarks. Accessibility capture on Fire TV `142` took about 1.95 s by itself.
- Some Fire TV video surfaces are protected and appear black in ADB screenshots. Playback was corroborated with player controls, advancing position, decoded frames where capturable, process state, and absence of decoder exceptions.
- Network/provider timings reflect the real services and caches available during the test window. They are not controlled-lab network benchmarks.
- The interrupted original session did not produce a final report. Its raw bundle survived and is stored in [`.codex-e2e`](../.codex-e2e/); resumed evidence is in [`resume-20260716`](../.codex-e2e/resume-20260716/).

## Samsung 151 startup and mobile IPTV follow-up

This follow-up was run after implementing the mobile startup and IPTV changes on the same SM-G991B. The signed `googleMobileRelease` APK was installed as an in-place update, preserving the configured account and 31,878-channel IPTV source.

### Implemented startup changes

- Removed the custom Compose splash; only Android's native launch splash remains.
- Restored the last successful Home snapshot for up to 30 days while refreshing in the background.
- Published policy- and parental-filtered essential discovery shelves before optional account, add-on, schedule, people, and ratings enrichment completes.
- Reduced Home's initial TMDB prefetch from two pages per rail and five upcoming pages to one page per rail.
- Delayed the automatic lightweight catalog warmup by 15 seconds so it does not compete with first paint. Explicit user refreshes remain immediate.

### Samsung startup retest

Five force-stop/process-death launches reported Android cold activity display times of `540`, `483`, `462`, `451`, and `459 ms` (`median 462 ms`, `mean 479 ms`, range `451-540 ms`). No fatal exception or ANR was found in the test log.

Scheduled screenshots are conservative because device-side screenshot capture adds overhead. Home labels and usable shelf content were present by the capture that completed at about `1.45 s`; artwork was filled by about `2.59 s`. In the prior capture set, the custom splash was still visible through the 1.5-second target and Home was still skeleton-only at the 1.8-second target.

Evidence:

- [Improved startup: native splash](../.codex-e2e/fixes-20260716/151_improved_startup_500.png)
- [Improved startup: Home composed](../.codex-e2e/fixes-20260716/151_improved_startup_750.png)
- [Improved startup: useful shelves](../.codex-e2e/fixes-20260716/151_improved_startup_1000.png)
- [Improved startup: artwork filled](../.codex-e2e/fixes-20260716/151_improved_startup_1500.png)

### Mobile IPTV retest

- Portrait: source identity is visible, search remains fixed while content scrolls, tab height is reduced, `Saved` fits cleanly, and recent/favourite quick rails appear when populated.
- Landscape: categories occupy a fixed left pane and recent/favourite/expanded channel content uses the right pane. The Android navigation inset no longer clips the content.
- Real category: the `24/7` category expanded successfully. The visible accessibility tree contained zero `AL:` display prefixes and zero separator markers, while logos, channel counts, playback taps, and favourite controls remained present.
- VOD: the fixed, capped nested grid was replaced by an uncapped responsive row grid (3-6 columns according to width). The provider did not expose filled VOD content during this physical pass, so populated-grid visual timing remains unmeasured.
- Device rotation settings were restored to automatic rotation (`accelerometer_rotation=1`, `user_rotation=0`) after the landscape capture.

Evidence:

- [Improved portrait directory](../.codex-e2e/fixes-20260716/151_iptv_improved_portrait.png)
- [Improved two-pane landscape](../.codex-e2e/fixes-20260716/151_iptv_improved_landscape.png)
- [Final cleaned category](../.codex-e2e/fixes-20260716/151_iptv_final_category.png)

### Verification

- `:androidApp:compileGoogleMobileDebugKotlin` passed.
- `MetadataRepositoryImplRegressionTest` passed through `:shared:desktopTest`.
- `:androidApp:assembleGoogleMobileRelease` passed after the final display-name change.
- `:androidApp:compileAmazonTvReleaseKotlin` passed, covering the shared Home changes for the APK used by Fire TV 110 and 142.
- Signed release installation on Samsung succeeded without clearing app data.
- Targeted `git diff --check` passed; only line-ending normalization warnings were reported.

### Outcome and ROI

The custom animation no longer serializes navigation/content startup, cached Home can survive normal process death and older snapshots, and cold display is consistently below 0.55 seconds on the Samsung. The highest-value improvement is perceived daily startup: useful content now appears during the early Home window instead of after a multi-stage splash/skeleton wait. The network reduction and deferred warmup also lower startup bandwidth, contention, battery use, and third-party API pressure. The IPTV changes improve large-playlist scanability in portrait and turn landscape from a stretched directory into a useful two-pane browser without changing playback or provider data.

### Samsung Home launch-position follow-up

Home's `LazyListState` was also being restored during a new launch, and the initial snapshot could render before the persisted section order inserted Hero and earlier sections. A one-shot launch reset now waits for both content and the final section layout, then moves Home to item zero. It is consumed at the navigation owner, so tab switching, Detail/Back, and rotation retain their in-session position.

Physical verification scrolled Home to Availability Services/Recently Watched, force-stopped the package, and relaunched it. The app returned to the Hero at the true top with a `377 ms` Android cold activity start and no fatal exception or ANR. A separate scroll -> Movies -> Home test returned to the same scrolled position, confirming that normal in-session restoration remains intact.

Evidence:

- [Home before force-stop](../.codex-e2e/fixes-20260716/151_home_before_relaunch.png)
- [Home at top after relaunch](../.codex-e2e/fixes-20260716/151_home_after_relaunch.png)
- [In-session position before tab switch](../.codex-e2e/fixes-20260716/151_home_before_tab_switch.png)
- [In-session position after returning to Home](../.codex-e2e/fixes-20260716/151_home_after_tab_return.png)

## Final combined candidate: original mobile navigation, playback UX, discovery, and account restore

This is the current hand-off section. It supersedes the earlier five-tab mobile experiment and the earlier candidate hashes. Mobile is back to the original six destinations: **Home, Movies, TV Shows, Channels, Library, and Settings**. Search remains available from Home and the dedicated Movies/TV Shows catalog screens instead of replacing those browsing destinations.

### Exact tested release artifacts

| Variant | Bytes | SHA-256 | Installed on |
|---|---:|---|---|
| `androidApp-amazon-tv-release.apk` | 91,884,587 | `DA03CF6DFA5AFB210B3CB0F7BDB930FCE73632390B173B49C45D1DE23CCFF47C` | Fire TV `.110` and `.142` |
| `androidApp-google-mobile-release.apk` | 92,579,273 | `887685D4DD40FF173A4AB4352277EC4652C7129460202586A35FFE3B0C02CACB` | Samsung `.151` (SM-G991B); version `1.1.3` (`10096`) installed successfully |

Fire TV `.110` and `.142` received byte-for-byte the same Amazon APK. Separate device testing remains necessary because the devices use different Fire OS/hardware generations, runtime state, caches, decoders, and graphics behavior.

### Final cold-start measurements

Each series used five `am force-stop` + `am start -W` launches after installing the exact artifacts above. `TotalTime` is Android activity display time; it is not a guarantee that every network-backed shelf has completed enrichment.

| Device | Five `TotalTime` samples | Median | Mean | Range | Result |
|---|---|---:|---:|---:|---|
| Fire TV `.110` | `677, 843, 768, 755, 760 ms` | **760 ms** | 760.6 ms | 677-843 ms | Pass |
| Fire TV `.142` | `680, 909, 688, 936, 715 ms` | **715 ms** | 785.6 ms | 680-936 ms | Pass; larger tail than `.110` |
| Samsung `.151` | `263, 274, 280, 272, 269 ms` | **272 ms** | 271.6 ms | 263-280 ms | Pass; tight distribution |

The Samsung was deliberately left on Settings before a force-stop. The final release reopened on Home at the real top, with Home selected and the hero visible. This confirms that process/task restoration can no longer override the Home-on-launch policy. Rotation and in-process tab switching still retain their current state because only genuine new-task/process activity state is discarded.

Evidence:

- [Samsung final Home launch](../.codex-e2e/ux-player-20260716/151_final_home_verified.png)
- [Separate Movies catalog](../.codex-e2e/ux-player-20260716/151_final_movies.png)
- [Separate TV Shows catalog](../.codex-e2e/ux-player-20260716/151_final_tv_shows.png)
- [Fire `.110` Home](../.codex-e2e/ux-player-20260716/110_home_awake.png)
- [Fire `.142` Home](../.codex-e2e/ux-player-20260716/142_home_awake.png)
- [Fire `.110` simplified Search](../.codex-e2e/ux-player-20260716/110_search.png)
- [Fire `.142` simplified Search](../.codex-e2e/ux-player-20260716/142_search.png)

### Stability, memory, and rendering

- All three crash buffers were empty after the final install and five-launch pass.
- Samsung process-exit history contained only the test's explicit force-stops and APK package updates; no crash or ANR exit reason appeared.
- Samsung after loaded Home/catalog browsing: `236,902 KB` total PSS, `366,710 KB` total RSS, and `64,706 KB` graphics. This is material but below the earlier 260 MB PSS / 112 MB graphics sample and produced no memory failure.
- Samsung rendering sample: 1,531 frames, 7.90% modern jank, p50/p90/p95/p99 `8/17/18/46 ms`. It includes cold composition, image population, tab changes, and ADB input, so it is a mixed stress/navigation sample rather than steady-state scrolling alone.
- Fire App Summary component sums were approximately 84,224 KB on `.110` and 49,396 KB on `.142`. Their post-launch `gfxinfo` samples contained only two frames each, so percentages of 100% and 50% jank are statistically meaningless and are not used as a release score.

### What was implemented and the expected user outcome

| Area | Implementation | Expected outcome | Fix status / remaining qualification | ROI |
|---|---|---|---|---|
| Mobile information architecture | Restored Home, Movies, TV Shows, Channels, Library, Settings. Movies and TV Shows have independent catalogs, Trending/Popular/Top Rated, genres, layouts, and real-page loading. | Users can explore without knowing a title in advance and can avoid a mixed-poster Home feed. | Fixed and physically verified. | **Very high**: primary daily discovery path. |
| Fresh launch position | A genuine new process/task discards stale Compose/navigation saved state; Home then performs its one-shot top reset. Rotation keeps saved state. | Every app launch begins at the Home hero instead of the middle of Settings, Home, or the last tab. | Fixed and physically verified after deliberately leaving Settings selected. | **High**: removes a visible failure on every launch. |
| Startup | Removed the custom animated splash, restored cached Home immediately, reduced first-pass prefetch, and deferred nonessential warmup. | Android display stays around 0.27 s median on the Samsung and useful cached content can render while network enrichment continues. | Fixed. Network-complete shelf time remains service/cache dependent. | **Very high**: eliminates the prior multi-second imposed splash wait. |
| Discovery and paging | Blank Search now shows mixed discovery; query and discovery paging append real API pages; See All backfills filtered results across bounded pages. | Empty Search is useful, and scrolling/filtering is no longer limited to the first already-loaded batch. | Fixed in code and tests; mobile Movies/TV catalogs physically populated. | **High**: substantially increases browsable inventory. |
| Artwork | Normalized TMDB paths, preserved absolute URLs, rejected blank URLs, added poster/backdrop fallback, shimmer, and initials for people photos. | Posters and cast/crew photos no longer remain blank when an alternate image or textual fallback exists. | Poster rendering physically passed. Actor/director fallback is compiled/tested but was not exhaustively device-walked. | **Medium-high**: large perceived-quality gain. |
| Sticky Play/Resume and source choice | Detail keeps the primary Play/Resume action reachable and exposes the recommended source before the full picker. | Fewer taps and less decision overload while manual source choice remains available. | Implemented; provider availability still controls the final source list. | **High** for every playback start. |
| Accidental Back during VOD | The active Exo engine/session is retained when leaving VOD playback; a persistent playback bar offers one-click return, play/pause, and explicit Stop. | Sitting on the remote or checking another screen no longer destroys the source and forces resolution/restart. | Implemented and release-compiled. A final real long-form Back/resume soak is still recommended before a public rollout. | **Very high** where source resolution takes 6-20 seconds. |
| Next episode timing | Episode discovery begins late in the episode, while the visible prompt is held until the final seconds/outro/end window. Modes are At End, At Credits, or Off. | The prompt should no longer interrupt normal viewing so early that it is cancelled almost every time. | Implemented. Outro quality depends on available metadata. | **High**: converts a currently rejected feature into a low-interruption one. |
| Intelligent next-source preparation | Settings control Off / Resolve / Resolve and Buffer, Wi-Fi-only behavior, minimum MB per hour, maximum file size, quality, cached preference, and ordered audio languages. Runtime and current audio language feed selection. | A 30-minute episode can require roughly 1 GB and a 60-minute episode roughly 2 GB when `2048 MB/hour` is selected; English playback prefers English sources rather than random low-quality candidates. | Policy and warm-resolution path are fixed and tested. “Resolve and Buffer” currently warms repository/startup candidates; it does **not yet guarantee a measured second ExoPlayer media buffer**. | **High**, with data/battery cost controlled by settings. |
| TV navigation/search/live UI | Normalized focus treatment, collapsed advanced Search filters, retained result focus, and kept now/next channel rows plus compact live overlay. | More predictable D-pad behavior and less filter clutter without removing advanced controls. | Implemented; Fire screenshots and launch tests pass. | **Medium-high** for remote usability. |
| Provider recovery | Standardized provider failures with recovery actions instead of dead-end messages. | Users know whether to retry, refresh sources, open settings, or choose another source. | Implemented; external provider outages remain outside app control. | **High** for support avoidance. |
| Account/channel recovery | Exposed **Refresh all** to signed-in Google Mobile users even when the build has no billing UI, and connected it to account-data, device, channel, favorite, and catalog refresh. | A user whose channels do not appear immediately after sign-in has an explicit recovery action without signing out, re-entering credentials, or waiting for another lifecycle sync. | Fixed, rebuilt, installed, and physically verified against the live account. | **Very high**: prevents false data-loss reports and avoids manual reconfiguration/support work. |
| PiP/player controls | Kept mobile picture-in-picture and refined player controls around the retained session. | Playback can remain useful during multitasking with clearer recovery/stop ownership. | Implemented; store/device PiP policy still applies. | **Medium-high**. |

### Channel/account restore diagnosis

The account design **does store channel source configuration for the signed-in user**. Xtream passwords are encrypted at rest, excluded from normal playlist responses, and available only through an authenticated endpoint that checks playlist ownership. M3U URLs/EPG URLs and Xtream server/username metadata are account-scoped as well.

The original `Restore completed with 2 error(s)` message did not identify its failures, but the live investigation disproved the suspected paid-entitlement backend blocker. Before deployment, the production router and access dependencies were compared with the local checkout. Production already uses the free/default account-access contract for playlist operations and retains active-device, ownership, encrypted-secret, rate-limit, URL/SSRF, and audit protections. The local playlist router is older than production; uploading it would have removed current hardening, so the backend was intentionally left unchanged.

A privacy-limited production audit then confirmed that the target account is active and verified, the current Samsung device is active, and two account-scoped Xtream playlist rows exist. Both rows have server and username metadata plus encrypted password data. Passwords, usernames, server URLs, tokens, and playlist names were not read or printed.

The actual recovery path succeeded:

- A background-to-foreground account refresh returned HTTP 200 for `GET /me/playlists` and both owner-scoped credential endpoints, after which Channels populated on the Samsung.
- The signed-in Settings **Refresh all** action was found nested inside `BuildConfig.HAS_BILLING`, which made it invisible in the free/no-billing Google Mobile build even though restore is a free product feature.
- **Refresh all** is now visible for signed-in no-billing builds and runs account-data, device, channel, favorite, and full catalog refresh.
- A physical tap on the rebuilt Samsung app again returned HTTP 200 for the playlist list and both encrypted credential restores. The Channels screen remained populated afterward.

No backend deployment was needed or performed. The expected outcome is now present: stored channel sources recover without re-entering credentials, and users have an explicit retry path if lifecycle sync does not complete immediately.

Evidence:

- [Visible signed-in Refresh all action](../.codex-e2e/ux-player-20260716/151_refresh_all_visible.png)
- [Channels populated after manual refresh](../.codex-e2e/ux-player-20260716/151_channels_after_manual_refresh.png)

### Verification completed

- Google Mobile and Amazon TV debug Kotlin compilation: pass.
- Google Mobile and Amazon TV signed release assembly, lint-vital, resource shrinking, and R8: pass.
- Google Mobile release was rebuilt after exposing **Refresh all**; local APK and installed Samsung `base.apk` SHA-256 matched exactly.
- Final APK install: pass on `.110`, `.142`, and `.151`.
- Final five-launch pass: pass on all three devices.
- Crash buffer: empty on all three.
- Samsung Home-at-top and separate Movies/TV Shows physical checks: pass.
- Production backend preflight: service active and `/health` returned HTTP 200.
- Production playlist/credential restore: playlist list and both credential endpoints returned HTTP 200; Channels physically populated on Samsung before and after the explicit manual refresh.
- Production backend state: unchanged. The local router is not a safe deployment candidate until its drift from the hardened production version is reconciled separately.

### Detailed launch verdict

| Target | Verdict now | What can be fixed / required next |
|---|---|---|
| Fire TV `.110` / `.142` | **Staged beta GO** for browsing/startup/navigation. The same signed APK is installed and no crash/ANR was observed. | Run one real long-form VOD session that exercises accidental Back/resume and the late next-episode handoff. Continue watching `.142` long-tail transitions, which have been rough in earlier player samples. |
| Google Mobile / Samsung `.151` | **Staged beta GO for startup, navigation, discovery, artwork, and account/channel restore.** The live account retained both source credentials, both restore calls returned 200, and Channels populated. | Run one real long-form VOD Back/resume + PiP session and a longer portrait/landscape browsing soak before broad rollout. Verify guide/EPG population separately; this pass proved channel-source recovery and catalog population. |
| Google TV | **Conditional / unverified hardware**. Shared TV source compiles and Fire coverage is useful but not equivalent. | Build/install the Google TV store variant and run focus, playback, codec, PiP, and account-channel restore on physical Google TV hardware. |
| Backend/account sync | **Pass for the tested restore path; production remains unchanged.** The hardened production access policy already permits normal account restore while enforcing active-device and ownership checks. | Reconcile the stale local playlist router with production before any future backend deployment. Never replace the current production router with the older local file wholesale. |

### ROI conclusion

The highest ROI work is the navigation/startup/account-restore combination. Navigation and startup affect every session; the measured Samsung activity median is 272 ms and the artificial splash delay is gone. Making **Refresh all** available in the no-billing build is a small, low-risk client change that avoids credential re-entry, false data-loss reports, and support contacts; the live account proved that the stored encrypted credentials were already sufficient. Retained playback can save a full 6-20 second resolution/start sequence after an accidental Back based on earlier physical source timings. Intelligent next-episode preparation reduces repeated prompt cancellation and lets quality/data preferences decide what is prepared. Artwork and provider-recovery changes are medium-cost polish with high trust/support value.

The remaining work is predominantly verification/deployment, not a redesign. The one substantive future enhancement is a true bounded secondary ExoPlayer/MediaSource prebuffer for the next episode; it should be opt-in and memory/data capped because keeping two prepared players on Fire TV can erase the UX gain through memory pressure.
