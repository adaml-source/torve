# Torve Market and Release Readiness Assessment

Last updated: 2026-08-15 (rerun after `39d0df8`)

Assessment baseline: the source-tagged and publicly distributed `1.1.6`
release; the post-release Fire TV Search candidate at `39d0df8`; its locally
signed Amazon TV APK installed on Raven; the live production website/update
feeds; fresh host-runnable tests; and a refreshed August 2026 review of the
current media-app market. Published evidence and candidate evidence are kept
separate throughout this rerun.

This supersedes the obsolete paid-product assessment. Torve is assessed here as
free software. There are no subscriptions, paid tiers, premium features, or
purchase requirement. Donations are optional and must never unlock features.

This is a product and release-readiness assessment, not legal or investment
advice.

## Executive verdict

Torve is a credible, unusually capable **public direct-download beta** and a
strong controlled Android/Fire TV beta, but it is not yet ready for a broad
app-store or consumer-stable release.

The product is no longer blocked by a lack of features. It now combines:

- progressive on-demand source resolution and fallback;
- Stremio-style addon support and debrid/provider connections;
- permanent-library acquisition through Seerr, *Arr, and Jellyfin;
- IPTV, EPG, catch-up, multiview, and recording foundations;
- account-backed settings and connection synchronization;
- provider health, diagnostics, and failure explanations;
- Android mobile, Android TV/Fire TV, and Windows clients;
- signed release builds and an automatic Fire TV update flow.

That is real product value. The current bottleneck is turning it into something
new users can discover, trust, configure, and keep working without the operator
personally guiding each installation.

The harsh conclusion is:

> Torve now has a source-tagged, checksummed public release, automatic update
> feeds, account recovery, and an outcome-first Connections surface. Its largest
> immediate constraint is release discipline: the Search/focus/rating candidate
> is installed locally as `1.1.6`, but the public updater serves a different
> `1.1.6` binary. After that is corrected with a monotonically newer release,
> the largest constraints remain store/hardware proof, rights-safe store assets,
> clean-machine signing reputation, discoverability, and community scale.

## Readiness ratings

Scores describe readiness for the named outcome, not code volume.

| Target | Score | Verdict |
| --- | ---: | --- |
| Controlled existing-user beta | 8.5/10 | **GO.** Fire TV and Android paths have strong real-device evidence; the new Search candidate is release-built, installed, and regression-tested on Raven. |
| Direct Fire TV staged release | 8.0/10 | **CONDITIONAL GO.** The public 1.1.6 remains valid, but the newer candidate must receive a new version, tag, provenance, and updater entry before distribution. |
| Android mobile beta | 7.2/10 | **GO for controlled testing.** Recovery and Connections are stronger; mobile remains less differentiated and still needs current store/device proof. |
| Google TV internal testing | 7.1/10 | **GO.** The 1.1.6 AAB has both ARM ABIs and passed 16 KB static verification; current candidate unit tests pass. A fresh candidate AAB and physical Google TV/16 KB runtime smoke remain. |
| Windows direct beta | 7.0/10 | **CONDITIONAL GO.** Public MSI, automatic appcast, recovery, and checksum validation exist; signing reputation and a current clean-machine pass remain weak points. |
| Public direct-download beta | 7.7/10 | **GO with monitoring.** The published website/release is healthy, but the deployment gate contains a stale source-page assertion and the candidate is not yet public. |
| Google Play / Google TV public release | 5.3/10 | **NO-GO today.** Store assets, rights review, review access, policy-form submission, and physical-device evidence remain incomplete. |
| Public free-software project launch | 7.4/10 | **GO for a small beta.** Public release correspondence remains strong; the candidate branch is ahead of master and must not be represented as shipped until merged, tagged, and published. Discoverability and community proof remain weak. |
| iOS / macOS release | 3.5/10 | **NO-GO.** The common-source compile regression is fixed locally, but Apple builds and devices are not operator-verified. |
| Broad consumer stable release | 5.2/10 | **NO-GO.** Long-term reliability, support capacity, store proof, signing reputation, and distribution still lag behind the feature set. |

## What changed since July 2026

### Material improvements

- Android and desktop versions align at `1.1.6`.
- Provider-backed browsing and filtering now exist on TV.
- Provider artwork, cinematic title artwork, focus restoration, source failure
  handling, and TV details behavior received substantial real-device work.
- IPTV/EPG refresh coalescing, cache-first behavior, and lower-memory ingestion
  were added.
- Debrid activation preference is separated from credentials and synchronized.
- Fire TV now has an application update worker and guided download/install
  activity.
- Password-reset web pages and Android/TV entry points exist; Desktop sign-in
  now invokes the same non-enumerating recovery API.
- Fire TV Search at `39d0df8` now keeps immutable result identities during
  metadata hydration, preserves poster order and focus, exposes explicit IMDb,
  Rotten Tomatoes, RT audience, TMDB, vote, popularity, and title sorts, and
  retries missing IMDb/RT hero enrichment through configured account providers.
- The public source-tagged `1.1.6` Amazon, Google TV, Google mobile, and Windows
  artifacts remain provenance-verified. A fresh candidate Amazon release APK was
  built and installed locally; it is not the public `1.1.6` APK.
- Fresh host-runnable test outputs on 2026-08-15 contain 2,339 passing tests and
  zero test failures across shared desktop, desktop, Amazon TV, Google TV, and
  Google mobile suites.

### Evidence that still limits readiness

- The iOS simulator regression at `ChannelRepositoryImpl.kt:2650` was fixed by
  replacing JVM-only map sorting with a common Kotlin operation.
  `:shared:compileKotlinIosSimulatorArm64` and `:shared:allTests` now pass.
- Release source is committed at `bef4824ab1e1e906fb31b53d5913bbbb91c03d21`,
  tagged `v1.1.6`, published, and separated from local ignored diagnostics.
- Candidate source is committed at `39d0df8fabd3227f85e96b3b5d984e033e9cc336`
  on `codex-publish-local-updates`, while `origin/master` remains at `a07d31e`.
  The locally installed candidate APK and public APK both report `1.1.6` but
  have different hashes (`b979bd6d...` versus `d349d788...`). Existing clients
  cannot discover this candidate through a version comparison.
- The complete backend gate passes 652/652 against an isolated PostgreSQL
  database migrated from revision 0001 through 0033. The run also
  exposed and fixed invalid FastAPI response-model inference on three Stripe
  routes, which had prevented the production app module from importing.
- The store positioning script passes its copy check but reports **0/6 required
  screenshots present**.
- Corrected policy/legal, recovery, download, source, and Connections pages are
  deployed. The tracked production gate incorrectly requires the obsolete text
  `Build provenance`; the tracked and live page say `Release provenance`.
  Re-running the gate with only that assertion corrected in memory verifies all
  20 pages, recovery, downloads, checksums, appcast, provenance, and API health.
- Production currently has neither a global OMDb nor MDBList API key. The client
  now correctly uses account-configured fallback providers, but users without a
  configured key or cached data cannot receive universal IMDb/RT enrichment.
- Direct free competitors have moved closer to Torve's feature set.

## Current product wedge

Torve should not be positioned as “another media center,” “a free Stremio,” or
“an IPTV player with more settings.” Those categories already have powerful
incumbents.

The strongest positioning is:

> Connect the services and libraries you already use. Torve shows what can play
> now, can save a title into your permanent library, and explains which
> connection needs attention when something fails.

The user-visible product hierarchy should be:

1. **Watch now** — progressively find and retry usable sources.
2. **Save permanently** — request through Seerr/*Arr and play from Jellyfin.
3. **Live now** — IPTV with guide, replay, recording, and multiview where supported.
4. **Fix connections** — plain-language health and repair actions.
5. **Continue anywhere** — account-backed non-secret state plus secure credential transfer.

This is more defensible than any single playback engine or catalog design.

## Feature and engineering ROI

For a free-software product, ROI means user impact, adoption, retention, trust,
support-cost reduction, and competitive differentiation relative to build and
maintenance cost. It does not mean paywall revenue.

A useful decision model is:

```text
(user impact × affected-user share × retention/trust gain
 × differentiation × support-cost reduction)
÷ implementation and maintenance cost
```

| Rank | Investment | Impact | Effort | ROI | Current assessment |
| ---: | --- | ---: | ---: | --- | --- |
| 1 | Publish the current candidate as a genuinely newer release | Very high | Low-medium | **Exceptional / immediate** | The fix exists and is installed on one device, but same-version public/local APKs make auto-update ineffective and weaken provenance. Cut a newer version; never overwrite 1.1.6. |
| 2 | Playback and source reliability | Very high | Medium-high | **Exceptional** | False no-source results, stalled playback, stuck overlays, and mid-playback failures erase trust immediately. |
| 3 | Outcome-first setup completion | Very high | Medium | **Exceptional** | Connections is visible, but first-run completion without operator guidance remains unmeasured. Every integration loses value if setup is abandoned. |
| 4 | TV navigation, focus, and paging stability | Very high | Medium | **Exceptional** | The Search fix closes a confirmed regression, but repeated focus/paging failures show that route-level device tests have unusually high value. |
| 5 | Provider diagnostics and repair actions | High | Medium | **Very high** | This reduces support cost and should explicitly distinguish missing operator/account rating providers from transport success. |
| 6 | Store assets, policy evidence, and Google TV hardware proof | High | Medium | **Very high** | This is the shortest path to wider distribution, but 0/6 screenshot slots and rights review still block it. |
| 7 | Public source, documentation, and discoverability | High | Medium | **Very high** | The public surface exists, but search/community proof remains far behind Nuvio and Stremio. |
| 8 | Permanent-library workflow | High | Medium-high | **High strategic ROI** | This remains a stronger differentiator than generic addon playback and needs reliability/evidence work rather than more scope. |
| 9 | Measured IPTV/EPG first-use performance | Medium-high | Medium-high | **High when profiled** | Preserve the measurement-first rule and optimize only confirmed stages. |
| 10 | Account recovery and session resilience | High | Low | **High maintenance ROI** | The lifecycle is implemented across platforms; remaining work is regression monitoring rather than another feature project. |
| 11 | Windows signing reputation and clean-machine proof | Medium-high | Medium | **Moderate-high** | The installer/update path exists, but a credential-managing app without trusted signing reputation loses cold users. |
| 12 | Android companion expansion | Medium | Medium-high | **Moderate** | Highest value is pairing, setup, repair, account recovery, and text entry—not TV feature parity. |
| 13 | Additional rating/provider integrations | Low-medium | Medium | **Low until configured providers are reliable** | Production configuration and honest diagnostics have higher ROI than adding another source. |
| 14 | Additional AI features | Low-medium | High | **Low now** | Adds operating cost and policy surface before core workflows are dependable. |
| 15 | iOS/macOS expansion | High potential | Very high | **Low near-term** | Strategically useful, but build, distribution, and maintenance costs are currently high. |

Recommended next-phase allocation:

- **45% reliability:** playback, sources, lifecycle, focus, paging, and updates;
- **20% onboarding:** Connections, pairing, and measured setup completion;
- **20% release/distribution trust:** versioning, provenance, signing, documentation, and support;
- **10% platform compliance:** Google TV, store review, assets, and physical verification;
- **5% new features:** only additions that unblock an existing user outcome.


## Competitive reality

### Broad free media platforms

| Product | Current reality | Effect on Torve |
| --- | --- | --- |
| Stremio | Free across Windows, macOS, Linux, Android, Android TV, Samsung, LG, web, and other devices. Its web repository has roughly 11.9k stars, and it launched optional Supporters funding in June 2026 while promising existing features remain free. Sources: [downloads](https://www.stremio.com/downloads), [GitHub](https://github.com/Stremio/stremio-web), [Supporters](https://blog.stremio.com/stremio-supporters-a-way-to-sustain-our-development/). | Torve cannot win on free addon playback or platform breadth. It must win on setup, provider health, permanent-library automation, IPTV integration, and failure recovery. |
| Kodi | Mature, free, GPL software with a TV-first ten-foot interface, add-ons, PVR, local/network media, and broad platform support. Source: [Kodi about](https://kodi.tv/about/). | Kodi sets the customization and longevity benchmark. Torve's advantage must be opinionated setup and cross-device workflow rather than extensibility alone. |
| Jellyfin | A mature free-software server ecosystem with Android, Android TV/Fire TV, iOS, Roku, webOS, Tizen, Xbox, desktop, and other clients. Its server repository has more than 50k stars. Sources: [clients](https://jellyfin.org/downloads/), [GitHub](https://github.com/jellyfin/jellyfin). | Torve should complement Jellyfin as a control/discovery/acquisition layer, not imply it replaces Jellyfin's server and client ecosystem. |

### Direct and emerging competitors

| Product | Current reality | Effect on Torve |
| --- | --- | --- |
| Nuvio | GPL-licensed, TV-first Stremio/debrid client with public repositories, a visible contributor community, roughly 2.2k stars on its TV repository, and separate mobile/desktop/web work. Source: [Nuvio TV](https://github.com/NuvioMedia/NuvioTV), [Nuvio organization](https://github.com/NuvioMedia). | This is a direct warning: cinematic TV UI, addons, debrid, sync, and open source are no longer a unique bundle. Nuvio currently has much stronger public-community proof. |
| Debrify | Public cross-platform project with debrid management, Stremio addons, Trakt, IPTV, WebDAV, Jackett/Prowlarr, downloads, remote setup, Android TV, desktop, and an unsigned iOS path. It has roughly 436 stars. Source: [Debrify repository](https://github.com/varunsalian/debrify). | Debrify overlaps Torve's credential/source-manager story. Torve needs to demonstrate superior couch UX, account recovery, provider diagnostics, permanent-library workflow, and reliability—not merely a longer feature list. |
| TiviMate | Android TV-focused IPTV player with more than one million Play downloads, strong EPG, multiple playlists, catch-up, recording, search, parental controls, and multiview. Source: [Google Play listing](https://play.google.com/store/apps/details?id=ar.tvplayer.tv). | Torve will not beat a specialist on IPTV polish quickly. IPTV should strengthen Torve's unified workflow, not consume the roadmap trying to clone every TiviMate option. |
| Syncler+ | Low-cost multi-device debrid/source filtering product with autoplay and provider integration. Source: [official pricing/features](https://app.syncler.net/plus). | Price is no longer Torve's differentiator because Torve is free. Reliability and understandable setup must justify switching. |
| Plex / Emby | Mature personal-media products with trusted brands, remote playback, server management, and paid convenience. Plex now charges for more remote-TV usage; Emby remains around $4.99/month, $54/year, or $119 lifetime. Sources: [Plex plans](https://www.plex.tv/plans/), [Emby Premiere](https://emby.media/premiere.html). | Their pricing creates an opening for free software, but their trust, documentation, server maturity, and household usability remain far ahead. |

## Market opportunity

There is a real niche for a product that unifies debrid/addons, personal library,
acquisition automation, IPTV, and connection diagnostics. The appearance of
Nuvio and Debrify confirms demand, but also removes any assumption that Torve is
alone.

Torve's most plausible first audience is:

- Android TV and Fire TV enthusiasts;
- users already operating Jellyfin, Seerr, Sonarr, Radarr, Prowlarr, or Usenet;
- debrid users frustrated by opaque source failures;
- IPTV households that also want on-demand and personal-library workflows;
- multi-device users who value setup transfer and consistent watch state.

Torve is not yet suitable for mainstream “install and watch” households. Those
users will not understand Panda, indexers, debrid, Newznab, provider activation,
or why credentials are split across account sync and encrypted device transfer.

Directional adoption scenarios, not forecasts:

| Outcome | Active-user shape | What must be true |
| --- | ---: | --- |
| Useful public proof | 100–500 | Public repo, install page, reliable Fire/Android release, responsive issue handling. |
| Credible niche project | 1,000–5,000 | Setup works without operator help, weekly regressions fall, legal/store posture is clear, community contributors appear. |
| Strong enthusiast outcome | 10,000–30,000 | Store or frictionless updater distribution, stable provider integrations, excellent TV UX, documentation, and low support burden. |
| Breakout | 100,000+ | iOS/smart-TV reach, trusted brand, mature maintainership, and a much simpler first-run experience. This is not supported by current evidence. |

Torve has a live official landing page, download page, source page, public GitHub
repository, issue tracker, contribution guide, and security policy. Search
discovery on 2026-08-15 still failed to surface those official pages and found
mainly the earlier Reddit feedback posts. The problem is therefore indexing,
external links, and audience development rather than the complete absence of a
public product surface.

## Setup and onboarding assessment

Setup remains the largest product-conversion risk.

The correct decision is **not** to remove Panda's orchestration or backend value.
The correct decision is to stop making “Panda” the concept a new user must first
understand.

Recommended product language:

- Top-level destination: **Connections**.
- Primary CTA: **Set up streaming sources**.
- Visible connection categories: **Streaming provider**, **Personal library**,
  **Live TV**, **Downloads and requests**, and **Watch history**.
- Show Real-Debrid, TorBox, Usenet, indexers, Jellyfin, Plex, IPTV, and Trakt
  directly inside the relevant category.
- Present Panda as “Recommended automatic setup” or an advanced service detail,
  not as the only door to debrid and Usenet.
- After sign-in, ask for one outcome: **Watch now**, **Connect my library**, or
  **Add live TV**. Do not ask users to choose infrastructure terminology first.
- Always provide **Skip and explore**, followed by a prominent Home empty-state
  action that returns to the exact missing connection.

This preserves Panda while removing brand-dependent knowledge from the setup
funnel.

The account requirement also conflicts with normal free-software expectations.
Torve should either support a useful local/guest mode or publish clear self-host
backend instructions and explain exactly which features require a Torve account.
“Free software” is less persuasive if users cannot verify the source or use core
local functionality without a centrally operated account.

## Platform assessment

### Fire TV

This is Torve's strongest current surface.

- Signed Amazon TV release artifacts exist.
- The same APK has been exercised on Raven and Gazelle Fire TV hardware.
- TV focus, playback, IPTV, source resolution, updates, and settings have had
  repeated real-device testing.
- The new updater reduces sideload-distribution friction.
- The 2026-08-14 Eddington interruption was traced to an external HDMI-CEC
  Standby command, not a Torve crash or source error.

Risks:

- Recent regressions repeatedly affected D-pad focus, provider actions, source
  error overlays, next-episode overlays, and pagination.
- A two-hour 1.1.5 Raven run completed with 234 samples, no process death, and no
  matching fatal/ANR/OOM record. Only 24 samples had Torve in the foreground;
  SmartTube owned the remainder, so this is background process-survival evidence,
  not a two-hour Torve playback claim.
- The exact signed 1.1.6 production APK was then installed in place. A strict
  three-minute Raven smoke retained Torve foreground ownership for all 31
  samples, captured memory in every sample, and recorded no process death,
  fatal/ANR/OOM event, or foreground loss.
- Fire TV update installation still depends on platform permission and should be
  smoothed and documented for nontechnical users.

### Google TV / Android TV

The product is technically promising but store proof is incomplete.

- Android TV quality rules require complete D-pad navigation, correct Back
  behavior, no clipped UI, AAB delivery, accurate screenshots, review login, and
  current architecture compatibility. From 2026-08-01 TV apps must support both
  32-bit and 64-bit architectures and 16 KB page-size requirements. Source:
  [Android TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality).
- Torve declares both `armeabi-v7a` and `arm64-v8a`. The 1.1.6 Amazon APK passed
  16 KB ZIP alignment; both Google AABs declare `PAGE_ALIGNMENT_16K`; and every
  packaged 64-bit ELF `PT_LOAD` segment passed the 16 KB alignment gate.
- A physical or emulated 16 KB Google runtime smoke is still required because
  static bundle/ELF validation cannot prove device runtime behavior.
- The July report explicitly lacked a physical Google TV smoke. Fire TV parity is
  valuable but not a substitute for Google TV review-device behavior.

### Android mobile

Mobile works best as setup, account, credential-transfer, diagnostics, download,
and remote-control companion. Competing directly as a primary cinematic player
is much harder because Stremio, Nuvio, Debrify, Plex, Jellyfin, and general media
players already cover that space.

The mobile strategy should prioritize:

- effortless TV pairing;
- account recovery and password reset;
- connection setup and repair;
- download/request monitoring;
- remote control and text input;
- optional playback, not feature-parity pressure with TV.

### Windows desktop

Desktop has a credible role as the power-user control center. MSI packaging,
VLC bundling, updater handoff, local library, automation, and dense settings are
valuable. The public blocker is trust: an unsigned or low-reputation installer
that asks for media credentials will lose cold users. A current clean Windows VM
install, update, uninstall, playback, and Defender/SmartScreen pass remains
mandatory.

### iOS, macOS, and Linux

These are not release claims today.

- Apple client code exists, but macOS/Xcode build, signing, notarization,
  TestFlight, and App Store evidence are absent.
- The shared iOS simulator target now compiles in the aggregate gate, but no
  signed Xcode/TestFlight build or physical Apple-device smoke was performed.
- Linux packaging is configured but lacks current release artifacts and clean
  distribution smoke.

Do not market Torve as fully cross-platform until these targets are proven.

## Policy, legal, and privacy readiness

The corrected legal pages are deployed and production-verified. Store-form
entry and rights-safe store-asset review remain large manual blockers.

### Google and Amazon content policy

Google Play prohibits apps that encourage infringement and explicitly flags
streaming apps that enable unauthorized downloading. It also requires rights or
permission for third-party logos and entertainment artwork used in the app or
store listing. Source: [Google Play intellectual-property policy](https://support.google.com/googleplay/android-developer/answer/9888072?hl=en).

Amazon independently reviews content, intellectual property, abuse risk, and
Fire TV quality. Source: [Amazon Appstore content policy](https://developer.amazon.com/docs/policy-center/understanding-content-policy.html).

Torve should maintain one conservative rule:

> The app connects only user-authorized services, libraries, playlists, and
> automation. Store screenshots and copy must never imply bundled media,
> official Netflix/Prime affiliation, or one-click access to unauthorized
> copyrighted works.

Provider-browse screens need especially careful wording. “Browse Netflix” can
look like an official integration when the data is a TMDB/JustWatch discovery
filter and playback may come from a different user-configured source.

### TMDB and JustWatch

TMDB requires its approved logo and the notice that the product uses TMDB but is
not endorsed or certified. Torve includes the text notice, but the full visual
attribution should be rechecked. Source: [TMDB attribution FAQ](https://developer.themoviedb.org/docs/faq).

TMDB's watch-provider endpoint is powered by JustWatch and explicitly requires
JustWatch attribution. Source: [TMDB watch-provider documentation](https://developer.themoviedb.org/reference/movie-watch-providers).

Torve uses these watch-provider endpoints extensively. This pass added JustWatch
attribution to the Android bundled policy and terms, setup disclosure, iOS legal
screen, tracked website privacy/terms source, and store disclosure inventory.
The updated website files are deployed; the exact store assets still require
rights review before submission.

### Privacy accuracy

The contradictory bundled privacy pages and store inventory were corrected in
this working tree. They now disclose account identity, devices, settings and
watch-state synchronization, optional encrypted credential storage, connected
services, diagnostics, account deletion, and flavor-specific Firebase behavior.
The internal private-repository instruction was removed from user terms.

Remaining work is operational: compare each submitted build/configuration with
the published policy and enter the exact Google, Amazon, and Apple privacy
answers for each submitted artifact.

## Free-software and community readiness

Positive evidence:

- a complete `AGPL-3.0-or-later` license is present;
- the README states the free-access model clearly;
- the repository is publicly reachable without authentication;
- the official source page links the repository, issue tracker, contribution
  guide, security policy, and license;
- client and backend CI workflows exist;
- contribution language exists;
- public release and secret-handling gates are documented.

Remaining weaknesses:

- public search engines do not yet surface the official repository or website;
- discoverability and external indexing remain weak even though source and
  binary correspondence are now documented by the immutable tag and provenance;
- generated diagnostics and local artifacts make release hygiene easy to get
  wrong even though they are currently untracked;
- historical payment infrastructure increases audit surface even though it no
  longer controls product access;
- visible maintainer/community activity remains small;
- `.github/FUNDING.yml` contains no active funding link.

The correct path is to release from reviewed public commits, create an immutable
source tag for every binary, keep signing/deployment secrets outside Git, and
improve discoverability and community response. A second public repository or
history rewrite is neither required nor desirable without a concrete security
reason.

## Technical verification snapshot

### Fresh commands and results

| Check | Result |
| --- | --- |
| `tools/check-release-version-alignment.ps1` | PASS — Android, Desktop, manifest channels, and source identity align at `1.1.6`. |
| `tools/check-public-positioning.ps1` | Copy PASS; store screenshots **0/6**. |
| `:shared:desktopTest` | 1,471 tests, 0 failures. |
| `:desktopApp:test` | 289 tests, 0 failures. |
| `:androidApp:testAmazonTvDebugUnitTest` | 215 tests, 0 failures. |
| `:androidApp:testGoogleTvDebugUnitTest` | 214 tests, 0 failures. |
| `:androidApp:testGoogleMobileDebugUnitTest` | 150 tests, 0 failures. |
| `:shared:allTests` | PASS, including iOS simulator compilation on the Windows host. |
| complete backend pytest/migration gate | Prior 652-test pass remains applicable because the candidate contains no backend changes; it was not rerun in this delta. |
| Android release lint | Fresh Amazon TV candidate PASS. The Google TV/mobile result is carried forward from source-tagged 1.1.6. |
| Android release artifacts | Public source-tagged 1.1.6 artifacts remain verified. A fresh signed Amazon candidate APK exists locally; fresh candidate Google bundles were not produced in this rerun. |
| Android 16 KB static delivery gate | PASS — Amazon ZIP alignment, Google AAB page-alignment metadata, and all 64-bit ELF load segments verified. Runtime smoke remains manual. |
| Public deployment verification | Tracked gate FAILS on stale `Build provenance` copy. With only that assertion corrected to tracked/live `Release provenance`, PASS for 20 pages, recovery, Connections, artifacts, sidecars, manifest, provenance, appcast, and API health. |

| GitHub client CI for the 1.1.6 provenance commit | PASS - version/provenance/website checks, all client tests, every Android release compile, signed delivery artifacts, and whitespace checks. |
| Raven release-only installation gate | PASS - only `com.torve.app.amazon` 1.1.6 (versionCode 20099) is installed; no debug/test package or flag is present. The installed APK is the local candidate, not the public APK. |
| Raven candidate launch smoke | PASS - final activity is `TvMainActivity`, Torve owns focus, and no matching fatal startup exception was observed. The earlier 31/31 foreground smoke remains evidence for the published 1.1.6 build, not this candidate. |

The 2,339 passing current client tests and prior 652-test backend result are
strong host evidence, but they do not replace physical-device, store-review, or
long-duration playback testing.

### Physical performance and release evidence

The July 2026 physical report recorded:

- Samsung mobile cold activity launch to populated Home at 323 ms;
- Fire TV Channels warm p95/p99 improved to 34/34 ms from earlier 150/450 ms;
- idle TV Search and settled playback rendered zero unnecessary frames in sampled
  windows;
- signed Fire TV APK identity matched on both tested devices;
- no crash, ANR, OOM, or fatal playback error in the final pass.

The 15 August Raven comparison used the same package ID, device, network, ADB
activity-launch method, and three iterations on release-only 1.1.5 and 1.1.6:

- 1.1.5 cold median: 521 ms; warm median: 91 ms;
- 1.1.6 cold median: 544 ms; warm median: 70 ms;
- cold changed by +23 ms and warm by -21 ms. With three samples and visible
  outliers, this supports **no material activity-launch regression**, not a claim
  of performance improvement or time-to-interactive Home.
- while 1.1.5 was installed, Fire OS recorded a Torve update notification on the
  `app_updates` channel (notification 1104) with one install action. The exact
  production 1.1.6 APK then installed in place successfully.

### 15 August implementation delta

- Fire TV Search results now retain stable item identity through metadata/rating
  hydration, preserve the user's explicit order, and reclaim a visible poster if
  a focused item is actually removed. Eight focused Search policy tests pass.
- Search sort choices now name their source: IMDb, Rotten Tomatoes critics, RT
  audience, TMDB, IMDb votes, popularity, and title. Unknown provider scores are
  placed after known values and enrichment does not silently reshuffle focus.
- Search hero enrichment now continues when IMDb or Rotten Tomatoes is missing
  and no longer treats a reachable empty/partial backend response as complete.
  Local fan-out remains bounded to accounts with OMDb or MDBList configured.
- Desktop onboarding now starts from user outcomes—streaming sources, personal
  library, or live TV—and hands each choice to the existing connection surface.
- Settings and source-repair prompts use **Connections / Streaming sources** in
  all supported desktop and Android languages; Panda remains an internal add-on
  identity and advanced implementation detail.
- Packaged desktop releases now default to the official HTTPS appcast. The
  packaging and release-readiness gates reject a blank/non-HTTPS feed or missing
  packaged version, and the custom updater-capable MSI path is the required one.
- Release hygiene now excludes root focus/hero/provider diagnostics and rejects
  them if they become tracked. Version metadata is aligned at 1.1.6.
- The public Connections portal exposes Debrid, Usenet, Live TV, libraries,
  tracking, and metadata by user outcome instead of requiring Panda knowledge.
- Public contribution/security templates, source correspondence, 16 KB checks,
  release-only device checks, and production-deployment verification are now
  first-class gates.

These changes largely close outcome-first setup, recovery, updater discovery,
and immutable source correspondence. This rerun corrects the earlier conclusion
that all automated release work was closed: the current Search candidate still
needs a newer public version, and the production gate's source-page assertion is
stale. Store artwork, store-console forms, current Google TV hardware, clean
Windows VM/signing reputation, Apple signing hardware, and legal/brand review
remain external manual gates.

## Automated ROI closure and remaining actions

The published high-ROI foundation is implemented:

1. **Reproducible release:** 1.1.6 is built from an immutable public source tag,
   signed, checksummed, described by provenance, and served through atomic
   manifests/update feeds.
2. **Outcome-first setup:** Connections exposes streaming sources, Debrid,
   Usenet, Live TV, libraries, tracking, and metadata directly; Panda remains an
   internal implementation detail rather than the discovery requirement.
3. **Account recovery:** the website, Fire/Google TV, Android mobile, and Windows
   sign-in surfaces all reach the same password-reset lifecycle.
4. **Update delivery:** Fire TV receives background update notifications and a
   verified download/install handoff; Windows receives the signed-hash appcast
   and updater-capable MSI.
5. **Public trust:** legal pages, source, contribution/security policy, issue
   templates, self-hosting guidance, release correspondence, and production
   deployment checks are live.
6. **TV regression protection:** provider Search returns to posters; release-only
   startup/device checks prevent debug installations; and the soak harness now
   scopes process failures to Torve, parses Fire memory, detects process death,
   and optionally requires Torve to retain foreground ownership.
7. **Google delivery compatibility:** both AABs and the Amazon APK have automated
   16 KB packaging/ELF gates in addition to release lint and flavor tests.
8. **Search candidate regression protection:** stable result keys, explicit
   rating-source sorts, order-preserving enrichment, and primary IMDb/RT hero
   fallback are covered by focused policy tests and the full client gate.

The remaining highest-value actions that do not require store or hardware access
are:

1. Merge the candidate, bump the version above `1.1.6`, rebuild from the clean
   public commit, tag it, generate provenance, and atomically publish the updater
   entry. Do not replace the existing 1.1.6 artifact in place.
2. Update `check-public-deployment.ps1` to assert `Release provenance`, then run
   the tracked gate without an in-memory correction.
3. Make IMDb/RT availability explicit in Connections/provider health. A global
   OMDb or MDBList credential is an operator decision; without one, account keys
   and cached data are the only legitimate enrichment sources.

The remaining actions that require manual evidence or external state are:

1. Capture and rights-review the six real store screenshots and any short TV
   demo; do not generate misleading provider-affiliation assets.
2. Upload the signed bundles in Google Play Console, complete policy/privacy
   forms, supply review access, and run current physical Google TV/16 KB runtime
   validation.
3. Run the MSI on a clean Windows VM and establish Authenticode/SmartScreen
   reputation; the current package is checksum-verified but not reputation-proven.
4. Complete Apple signing, notarization/TestFlight, and physical Apple-device
   testing before making Apple platform claims.
5. Obtain final legal/provider-brand review and build external links/community
   activity so search engines and prospective contributors can establish trust.

## Release decision

### GO

- Continue the controlled Fire TV/Android tester program.
- Continue serving the immutable published 1.1.6 release while monitoring it.
- Use Android mobile and Google TV internal testing tracks.
- Operate the small public direct-download beta with monitoring and responsive
  issue handling.
- Collect structured setup, focus, source-resolution, and long-session evidence.

### CONDITIONAL GO

- Distribute the Search candidate through the updater only after it becomes a
  monotonically newer, source-tagged, provenance-backed release.
- Windows direct beta after a fresh clean-VM install/update/playback smoke.

### NO-GO

- Broad Google Play or Google TV production release today.
- iOS, macOS, or Linux release claims today.
- Calling the product stable today.

## Final call

Torve's problem is no longer “is there enough here?” There is more than enough.
The problem is that breadth is creating setup cost, policy surface, regression
risk, and a support obligation before the project has public trust or community
scale.

The completed automated phase is:

```text
one green release
→ truthful legal/privacy copy
→ outcome-first setup
→ source-tagged public artifacts
→ controlled direct distribution
→ measurable reliability gates
```

That phase is implemented for the public 1.1.6 baseline, but the current
candidate has not completed the source-to-update chain. The immediate task is
to ship it truthfully as a newer release and repair the stale deployment gate.
After that, the growth constraint returns to manual store, hardware, rights,
signing-reputation, and community evidence—not low-ROI feature accumulation.
