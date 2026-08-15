# Torve Market and Release Readiness Assessment

Last updated: 2026-08-15

Assessment baseline: the source-tagged `1.1.6` release, its signed Android and
Windows artifacts, the live production website/update feeds, current
physical-device evidence, fresh host-runnable tests, and an August 2026 review
of the current media-app market.

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
> remaining constraints are store/hardware proof, rights-safe store assets,
> clean-machine signing reputation, discoverability, and community scale.

## Readiness ratings

Scores describe readiness for the named outcome, not code volume.

| Target | Score | Verdict |
| --- | ---: | --- |
| Controlled existing-user beta | 8.4/10 | **GO.** Fire TV and Android paths have strong real-device evidence, active operator feedback, and automated release checks. |
| Direct Fire TV staged release | 8.3/10 | **GO with monitoring.** Signed 1.1.6, automatic notification/install handoff, immutable provenance, and repeatable soak tooling exist. |
| Android mobile beta | 7.2/10 | **GO for controlled testing.** Recovery and Connections are stronger; mobile remains less differentiated and still needs current store/device proof. |
| Google TV internal testing | 7.1/10 | **GO.** Signed AAB, both ARM ABIs, 16 KB bundle/ELF verification, TV UX tests, and unit tests pass. A physical Google TV/16 KB runtime smoke remains manual. |
| Windows direct beta | 7.0/10 | **CONDITIONAL GO.** Public MSI, automatic appcast, recovery, and checksum validation exist; signing reputation and a current clean-machine pass remain weak points. |
| Public direct-download beta | 7.8/10 | **GO with monitoring.** Website, legal pages, recovery, tagged source, provenance, downloads, and update feeds are live and production-verified. |
| Google Play / Google TV public release | 5.3/10 | **NO-GO today.** Store assets, rights review, review access, policy-form submission, and physical-device evidence remain incomplete. |
| Public free-software project launch | 7.5/10 | **GO for a small beta.** Public source, tag/artifact correspondence, AGPL, issue templates, contribution/security guidance, self-hosting notes, and provenance are live. Discoverability and community proof remain weak. |
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
- Fresh Amazon TV, Google TV, and Google mobile `1.1.6` release artifacts were
  built on 2026-08-15. The Amazon APK verifies against the Torve release
  certificate and the public APK/MSI hashes and byte lengths match provenance.
- Fresh host-runnable test outputs on 2026-08-15 contain 2,327 passing tests and
  zero test failures across shared desktop, desktop, Amazon TV, Google TV, and
  Google mobile suites.

### Evidence that still limits readiness

- The iOS simulator regression at `ChannelRepositoryImpl.kt:2650` was fixed by
  replacing JVM-only map sorting with a common Kotlin operation.
  `:shared:compileKotlinIosSimulatorArm64` and `:shared:allTests` now pass.
- Release source is committed at `bef4824ab1e1e906fb31b53d5913bbbb91c03d21`,
  tagged `v1.1.6`, published, and separated from local ignored diagnostics.
- The complete backend gate passes 652/652 against an isolated PostgreSQL
  database migrated from revision 0001 through 0033. The run also
  exposed and fixed invalid FastAPI response-model inference on three Stripe
  routes, which had prevented the production app module from importing.
- The store positioning script passes its copy check but reports **0/6 required
  screenshots present**.
- Corrected policy/legal, recovery, download, source, and Connections pages are
  deployed and verified from the production VPS.
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
| 1 | Playback and source reliability | Very high | Medium-high | **Exceptional** | False no-source results, stalled playback, stuck overlays, and lost focus erase trust immediately. |
| 2 | Outcome-first Connections setup | Very high | Medium | **Exceptional** | Every integration loses value if users cannot find or configure it. Preserve Panda internally but expose Debrid, Usenet, IPTV, libraries, and tracking directly. |
| 3 | Account recovery and session resilience | Very high | Low-medium | **Exceptional** | Password reset and device recovery prevent otherwise permanent user loss. |
| 4 | Release consistency and automatic updates | Very high | Medium | **Very high** | Fixes have no value until users receive a signed release safely and promptly. |
| 5 | Provider diagnostics and repair actions | High | Medium | **Very high** | A defensible differentiator that also reduces operator support cost. |
| 6 | Public source, documentation, and discoverability | High | Medium | **Very high** | Converts AGPL claims into trust, contributions, reproducibility, and adoption. |
| 7 | TV navigation/focus reliability | High | Medium | **High** | Small focus failures are disproportionately destructive at couch distance. |
| 8 | Google TV compliance and current-device proof | High | Medium | **High** | Unlocks distribution, but carries policy, store-asset, and review overhead. |
| 9 | Permanent-library workflow | High | Medium-high | **High strategic ROI** | This is a stronger differentiator than generic addon playback. |
| 10 | Measured IPTV/EPG first-use performance | Medium-high | Medium-high | **High when profiled** | Valuable to active IPTV households; optimize confirmed bottlenecks only. |
| 11 | Windows control-center polish | Medium | Medium | **Moderate-high** | Useful for setup and acquisition, while TV remains the stronger usage surface. |
| 12 | Android companion expansion | Medium | Medium-high | **Moderate** | Highest value is pairing, setup, repair, account recovery, and text entry—not TV feature parity. |
| 13 | More provider integrations | Low-medium | Medium | **Low now** | Breadth is already ahead of comprehension and reliability. |
| 14 | Additional AI features | Low-medium | High | **Low now** | Adds operating cost and policy surface before core workflows are dependable. |
| 15 | iOS/macOS expansion | High potential | Very high | **Low near-term** | Strategically useful, but build, distribution, and maintenance costs are currently high. |

Recommended next-phase allocation:

- **45% reliability:** playback, sources, lifecycle, focus, and updates;
- **25% onboarding:** Connections, account recovery, pairing, and setup guidance;
- **15% release trust:** privacy, attribution, source tags, documentation, and support;
- **10% platform compliance:** Google TV, store review, and physical verification;
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
| `:androidApp:testAmazonTvDebugUnitTest` | 209 tests, 0 failures. |
| `:androidApp:testGoogleTvDebugUnitTest` | 208 tests, 0 failures. |
| `:androidApp:testGoogleMobileDebugUnitTest` | 150 tests, 0 failures. |
| `:shared:allTests` | PASS, including iOS simulator compilation on the Windows host. |
| complete backend pytest/migration gate | 652 tests, 0 failures against isolated PostgreSQL at migration head 0033. |
| Android release lint | PASS for Amazon TV, Google TV, and Google mobile; no lint baseline suppression added. |
| Android release artifacts | PASS — fresh signed 1.1.6 Amazon TV APK plus Google TV/mobile AABs. |
| Android 16 KB static delivery gate | PASS — Amazon ZIP alignment, Google AAB page-alignment metadata, and all 64-bit ELF load segments verified. Runtime smoke remains manual. |
| Public deployment verification | PASS — recovery, Connections, legal/source/download pages, artifacts, sidecars, manifest, provenance, and appcast. |

| GitHub client CI for the 1.1.6 provenance commit | PASS - version/provenance/website checks, all client tests, every Android release compile, signed delivery artifacts, and whitespace checks. |
| Raven release-only installation gate | PASS - only `com.torve.app.amazon` 1.1.6 (versionCode 20099) is installed; no debug/test package or flag is present. |
| Raven 1.1.6 foreground smoke | PASS - 31/31 foreground and memory samples, with zero process deaths, failures, or foreground losses. |

The 2,327 passing client tests and 652 passing backend tests are strong host
evidence, but they do not replace physical-device, store-review, or long-duration
playback testing.

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

These changes close the automated portion of outcome-first setup, recovery,
updater discovery, public deployment, and immutable source correspondence.
Store artwork, store-console forms, current Google TV hardware, clean Windows
VM/signing reputation, Apple signing hardware, and legal/brand review remain
external manual gates.

## Automated ROI closure and remaining actions

The high-ROI work that could be completed without store accounts, rights-holder
decisions, additional hardware, or a clean external machine is implemented:

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

The remaining highest-value actions require manual evidence or external state:

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
- Distribute signed Fire TV releases through the existing updater to known users.
- Use Android mobile and Google TV internal testing tracks.
- Operate the small public direct-download beta with monitoring and responsive
  issue handling.
- Collect structured setup, focus, source-resolution, and long-session evidence.

### CONDITIONAL GO

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

That phase is now implemented. The next growth constraint is obtaining the
manual store, hardware, rights, signing-reputation, and community evidence
without restarting low-ROI feature accumulation.
