# Torve Market Readiness Assessment

Last updated: 2026-05-03 (post B1/B4/B5)

## Harsh Verdict

The Windows packaging story is no longer the loudest blocker. With B1
(delete-account legal mirror), B4 (Windows clean-VM install + playback
smoke), and B5 (production LAN wrap-key) closed, **desktop + Android
public beta is no longer "GO with caveats" — it is GO, full stop.** Cut
the artifacts and ship them.

What hasn't moved: stable paid launch is still **NOT ready**, and the
reason has finally narrowed to two real things — **macOS/iOS
verification (B2 + B3) and product/support clarity**. Everything else
is now polish.

The deepest risk has also not moved: source aggregation for normal
users. Three categories cleared on a release-hardening checklist do
not change the fact that a first-time installer still has to decide
whether they care about debrid, NZB, IPTV, Plex, or a local library.
The technical pieces are real. The pitch is still not.

The product sentence still works:

> Torve is the credential-first media hub that tells you what you can
> actually watch, picks the best legal source, plays it on the couch,
> and explains what broke when it cannot.

The job between now and stable is not to add features. It is to close
the macOS gap and to make sure the first-run path enforces that
sentence.

## Current Readiness

| Target                              | Old (04-30) | New (05-03) | Movement |
| ----------------------------------- | ----------: | ----------: | -------- |
| Closed enthusiast beta              | 8.0         | **8.5**     | +0.5 — install gate + legal mirror remove the two most-cited rough edges |
| Public desktop + Android beta       | 7.5         | **8.5**     | +1.0 — Windows VM smoke was the largest residual risk; gone |
| Public paid stable launch           | 5.5         | **6.5**     | +1.0 — 3 of 5 release blockers cleared; remaining gap is structural, not technical |
| iOS beta                            | 3.0         | **3.0**     | unchanged — needs macOS, no Mac available |

The 6.5 on stable is deliberate. Closing 3 of 5 release blockers is
not 3/5 of the way to stable; it is the easy 3. The remaining 2 are
the structurally hardest because they need hardware this host does
not have.

## What changed since 2026-04-30

- **B1 cleared.** Delete-account web page was already live; the bug
  was a constant pointing at the wrong filename. `link-check.sh`
  returns 4/4 PASS. This was a paper blocker, not a product gap.
- **B4 cleared (2026-05-03).** Clean Windows VM install + launch +
  playback smoke. Caveat: run was performed in **Windows Sandbox**,
  which is ephemeral and ships with services stripped — for a media
  app this is *more* hostile than a normal install (no installed
  runtimes whatsoever), so a pass here is meaningful. The updater-
  handoff sub-step (install N-1, let in-app updater swap to latest)
  was not exercised; that residue is non-blocking for beta but must
  be run once before stable.
- **B5 cleared.** Production already runs the wrap key under
  different naming (`INTEGRATION_SECRET_KEY` in `app/crypto.py`, vs.
  the local `server/`'s planned `app/secret_wrap.py`). **The repo's
  `server/` directory is out of sync with prod.** This is now a
  maintenance hazard, not a release blocker — do not rsync
  local→prod naively. Reconciling `server/` with deployed reality
  should be on a near-term todo, separate from release.

## Competitive Reality

Stremio remains the most dangerous comparison. It advertises more
than 30 million users, broad platform support, login-based
cross-device continuity, and a simple addon mental model. Its
homepage also explicitly says users can log in and continue across
devices without configuring each new device again. Torve only wins
if it makes advanced legal sources safer, more reliable, and easier
than addon hunting. Source: <https://www.stremio.com/>

Syncler already owns much of the Android debrid-user psychology:
Android TV focus, synced home layout, Trakt/Simkl integrations,
debrid suite, cloud cache streaming, source filtering/sorting, and
autoplay. Syncler+ pricing is still aggressive: personal yearly is
listed at $15 for 12 months for 5 devices, roughly $1.25/month;
family tiers scale up. Torve cannot beat Syncler by being "also
configurable." It must win through safer setup, stronger
diagnostics, desktop hub power, LAN downloads, and clearer legal
positioning. Sources: <https://syncler.net/> and
<https://app.syncler.net/plus>

Plex, Jellyfin, Emby, Infuse, and Channels still beat Torve inside
their own lanes. Plex has ecosystem trust and Remote Watch Pass
pricing at $1.99/month introductory, then $2.99/month after
June 1, 2026. Jellyfin is free software with no fees. Emby Premiere
lists $4.99/month, $54/year, and $119 lifetime, with DVR/offline
features. Infuse Pro lists $1.99/month, $16.99/year, and $99.99
lifetime on the App Store, with very strong Apple playback polish.
Channels lists $8/month or $80/year and is far ahead for serious
Live TV/DVR, including Series Pass. Sources:
<https://www.plex.tv/plans/>, <https://jellyfin.org/>,
<https://emby.media/premiere.html>,
<https://apps.apple.com/us/app/infuse/id1136220934>,
<https://getchannels.com/get/>

Kodi is still the free power-user baseline. It is free/open source,
supports a 10-foot UI, and is highly customizable, but it explicitly
requires users to provide content or configure third-party services.
Torve's opportunity is "Kodi-level power without Kodi-level setup."
Source: <https://kodi.tv/about/>

## Feature Assessment

| Area | Current strength | Harsh assessment |
| --- | --- | --- |
| AI search | Source-aware availability now includes local, Plex/Jellyfin, debrid cache, addons, Usenet-ready, IPTV live, and watch history. Payload sanitization exists. | Stronger than before, but still desktop-first and BYO-provider-key flavored. Consumer value needs built-in defaults or a clearly bundled AI tier. Android/iOS parity is missing. |
| Debrid | Provider concepts, health checks, setup validation, source ranking, and diagnostics are now real. | High-value niche, but high support and policy risk. Failure copy and provider-health must be excellent because every debrid outage becomes a Torve support event. |
| Usenet / Panda / NZB | Panda health now reads a stable store, refreshes after save, and participates in setup and source availability. | Technically impressive, commercially fragile. Normal users still do not understand indexers, providers, download clients, caps, or warm states. It must remain wizard-led and hidden unless relevant. |
| IPTV | M3U/Xtream/EPG foundation plus one-off desktop DVR, recording library, conflict handling, EPG correction, stale diagnostics. | Much better, but not Channels-class. Series Pass, fast zapping polish, logos/groups quality, remote-first record controls, and live-provider smoke remain gaps. |
| Plex/Jellyfin | Now part of setup intents and availability graph. | Correct positioning is "unified availability layer," not "Plex replacement." Torve should complement these servers, not fight them. |
| Downloads / LAN | Desktop can publish LAN library, backend registry exists, Android/TV ExoPlayer header path is wired, TV Home can show Downloads on Desktop. | This is a real differentiator, but stable claims require two-device smoke. iOS/MPV LAN headers, title-only matching, stale-token retry, and quota/cleanup UX remain follow-ups. |
| Cross-device setup | Encrypted credential transfer and receive flows exist across desktop, Android mobile, Android TV, and iOS surfaces. | Safety story is good. Market story still needs live multi-device smoke. "Set up once" is only believable after the 12-row operator matrix passes. |
| Desktop player | Runtime staging scripts, package gates, installer handoff, and update trust work exist. **Windows clean-VM install + playback verified 2026-05-03 (Sandbox; updater-handoff sub-step deferred).** | Beta-ready. Stable still wants the updater-handoff round-trip and a snapshot-VM pass against Defender + a third-party AV. |
| TV UX | Android TV Home now has outcome rails, provider banner, On Now, Downloads on Desktop, source picker, LAN header handoff, and one-OK playback. | This is the biggest product jump. Remaining concerns are real-device focus smoke, series source picker parity, and making sure the TV first-run path never drops users into raw settings. |
| UI / Settings | Setup intents, provider-health rows, recovery actions, diagnostics, and legal/support cards reduce raw settings pressure. | Still dense. The app risks feeling like "settings with a player attached" unless the default surfaces stay outcome-first and advanced controls stay hidden. |
| Public release hardening | Account deletion/export, legal URLs, telemetry redaction, LAN secret wrapping, update handoff, and release docs are landed. B1/B4/B5 closed. | Public beta candidate confirmed. Stable still needs B2 + B3 (macOS/iOS), an updater-handoff smoke, and reconciliation of the repo `server/` directory with production. |

## What's left to clear stable

| ID  | Owner             | Why it's still hard |
| --- | ----------------- | ------------------- |
| B2  | Operator (macOS)  | Needs Mac to run iOS simulator smoke. Cannot be unblocked from this host. |
| B3  | Operator (macOS)  | Sign + notarize round-trip. Same constraint as B2. |
| —   | Product           | "Set up once, watch anything legal" pitch is not yet enforceable in the UI. Setup still surfaces too many source categories to a first-time user. |
| —   | Support           | Every debrid/NZB/IPTV outage becomes a Torve ticket at $1.99/mo. Self-serve copy + diagnostics export help, but pricing must defend the support load. |

## Honest residue you should not pretend is solved

1. **The local `server/` directory is documentation, not deployable.**
   Until reconciled, anyone treating it as the source of truth will
   break prod. This is now technical debt, but it is load-bearing
   debt.
2. **B4 was run in Windows Sandbox, not a snapshot Hyper-V VM.**
   Sandbox catches "missing runtime" issues better than most VMs,
   but does not catch issues tied to user-profile persistence, prior
   state, AV interaction, or Windows Update mid-session. A "real"
   snapshot-restore pass with Defender + one third-party AV is still
   worth doing before stable.
3. **Updater handoff (B4 step 4) was not exercised.** No N-1 build
   was prepared. Fine for beta. For stable, must run at least once
   with a real release feed.
4. **iOS is still 3/10.** Closing B1/B4/B5 does not move the iOS
   number by even 0.1. Do not let the desktop+Android momentum mask
   that.

## MRR At $1.99

At $1.99/month gross:

| Paid users | Gross MRR |
| ---: | ---: |
| 1,000 | $1,990 |
| 5,000 | $9,950 |
| 10,000 | $19,900 |
| 50,000 | $99,500 |

Reality after store fees, VAT/sales tax, refunds, payment failures,
support, backend, AI costs, signing, and app-store operational
overhead is materially lower. At $1.99, support can eat the business
if every IPTV/debrid/NZB failure becomes a ticket.

Better pricing posture:

- $1.99/month: intro, viewer-only, or early beta supporter.
- $2.99-$4.99/month: standard paid plan.
- $19.99-$29.99/year: low-friction annual entry.
- $49-$79 lifetime: early adopter, capped or time-limited.
- Higher family/power tier: multi-device, desktop hub, LAN library,
  AI, DVR, diagnostics, and priority support.

Do not underprice the full AIO power-user bundle. Syncler can sit
around $1.25/month because its scope and support expectations are
different. Channels can charge $8/month because DVR value is obvious.
Torve should not race to the bottom unless the support model is
self-serve.

## User Base Potential

| Scenario | Plausible paid user range | Conditions |
| --- | ---: | --- |
| Desktop-only enthusiast beta | 500-5,000 | Debrid/Usenet/IPTV communities trust the app and tolerate rough edges. |
| Desktop + Android mobile + Android TV public beta | 5,000-25,000 | Onboarding, playback, provider diagnostics, and LAN desktop-to-TV are reliable. |
| 50,000+ paid users | Possible but difficult | Requires TV-first polish, no runtime issues, legal-safe positioning, community trust, strong docs, and low support volume. |
| Mainstream app-store scale | Unlikely near term | Source aggregation, IPTV, debrid, and addon-adjacent workflows are policy-sensitive. |

## What Would Increase Value Most Now

1. Cut beta artifacts for desktop Windows, Android mobile, and
   Android TV from current green HEAD.
2. Run macOS/iOS build and simulator smoke (closes B2 + B3).
3. Run the updater-handoff sub-step of B4 against a real release
   feed at least once.
4. Run Android TV real-device couch smoke: setup, receive
   credentials, provider banner, Home one-OK playback, source
   picker, LAN playback.
5. Run live multi-device credential-transfer smoke across desktop,
   Android mobile, Android TV, and iOS.
6. Reconcile the repo `server/` directory with production so the
   local tree is no longer a footgun.
7. Reduce visible complexity: keep setup and Home outcome-first;
   hide source-specific expert fields.
8. Improve support self-service: provider-health explanations,
   diagnostics export, LAN troubleshooting, IPTV recording failure
   copy.
9. Decide pricing by support economics, not competitor price
   anchoring.

## Net Call

- **Public desktop + Android beta:** GO. Cut artifacts from current
  green HEAD, publish, open the door.
- **Public paid stable:** NO-GO. Wait on macOS host availability for
  B2 + B3, add an updater-handoff verification, reconcile `server/`,
  and pick a price that survives the support load.
- **iOS:** still gated on a Mac.

The previous assessment's framing of "8/10 closed enthusiast beta,
7.5/10 public desktop + Android beta, 5.5/10 stable, 3/10 iOS"
becomes:

- **8.5/10 closed enthusiast beta**
- **8.5/10 public desktop + Android beta**
- **6.5/10 stable paid consumer launch**
- **3/10 iOS until macOS verification**

The gap is no longer "missing features." The gap is the macOS host
and product clarity. Everything that does not serve the product
sentence above should be hidden, deferred, or made advanced-only.
