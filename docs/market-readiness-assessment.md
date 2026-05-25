# Torve Market Readiness Assessment

Last updated: 2026-05-23

Repo baseline for this assessment: `master` / `origin/master` at `af33771`
(`Update desktop V2 experience and playback recovery`).

This is not tax, legal, or investment advice. It is a product and revenue
readiness assessment for deciding whether Torve can realistically become a
2,000-3,000 USD/month after-tax income source for a Germany-based solo
operator.

## Harsh Verdict

Torve is no longer just a "strong beta foundation." Since the last assessment,
the product has gained a much more serious desktop V2 experience, a materially
better Android TV couch interface, account-scoped favorites, Panda/TorBox
credential improvements, Stripe/direct billing, trust-signal hardening, more
diagnostics, more provider intelligence, and a much wider test surface.

The product now has a credible commercial sentence:

> Torve is a credential-first media hub for people who already pay for services,
> servers, playlists, debrid, Usenet, or local storage, and want one app that
> tells them what is actually playable right now.

That is a real wedge. It is also not yet a stable paid launch.

The previous red Gradle signal has been resolved: the Channels startup tests now
match the current local-first startup contract, and
`:shared:desktopTest :desktopApp:test` has been reported green. That removes an
important beta blocker. Public paid stable still remains **NO-GO** until the
backend test path is exercised with Docker running, and the release/smoke gates
are cleared.

The income goal is possible, but not with hobby pricing, not with a vague Reddit
launch, and not with support-heavy instability. To reliably net 2,000-3,000 USD
after German tax/health-insurance drag at the stated **1.99/month** price,
Torve likely needs roughly **3,000-5,100 active monthly subscribers**, or a
similar mix of monthly plus annual/founder cash, with support volume kept low.
The updated lifetime structure is much healthier than the earlier 23.99 idea:
**39.99 founder lifetime capped at 500 users**, then **69.99 regular lifetime**.

## Current Readiness

| Target | Rating | Verdict |
| --- | ---: | --- |
| Closed enthusiast beta | 8.2/10 | GO from the shared/desktop test perspective. Still use known users who tolerate provider variability. |
| Public desktop + Android beta | 8.1/10 | Conditional GO after backend test path is run with Docker, Android TV real-device smoke runs, and beta artifacts install cleanly. |
| Public paid stable | 6.7/10 | NO-GO. Feature set is sellable; release trust, tests, iOS/macOS, signing, and real-device proof are not done. |
| Android TV commercial UX | 8.0/10 | Much closer to a couch product. Needs real Shield/Fire TV/Onn smoke and fewer sharp edges. |
| Desktop commercial UX | 8.5/10 | Now the strongest surface. Needs signed installer, clean-VM proof, and no broken updater/test residue. |
| Android mobile | 7.5/10 | Useful companion and playback surface, but not the main sales story. |
| iOS beta | 3.5/10 | Code exists, but no macOS build, no simulator smoke, no TestFlight, no App Review proof. |
| Income readiness | 5.5/10 | The app can plausibly reach the target, but the current release state cannot be marketed hard yet. |

The rating went up on product value and down on release confidence. That is the
right tradeoff to state honestly: Torve is more valuable than it was on May 3,
but the current branch is not clean enough to sell as stable.

## What Changed Since The Previous Assessment

### Desktop V2, layout, and playback recovery

Major movement landed in the desktop app:

- V2 detail page rebuilt into a high-density, premium-feeling view with richer
  hero metadata, ratings, source actions, related content, watchlist/download
  controls, and cleaner media presentation.
- Discovery controls now have dedicated filter config and tests, including
  TV/movie/mixed modes instead of one generic browse surface.
- Live TV V2 gained premium components, richer EPG behavior, improved grid
  handling, channel search/filtering, and better dense layout.
- Recording UI and services improved materially: path resolution, recording
  service tests, recording notification copy, and a much more complete
  recordings page.
- Desktop handoff expiry recovery was added and tested. This matters because
  expiring provider URLs are a common real-world failure path.
- Provider health initialization and playback-health bridging improved the
  "tell me what broke" side of the product.
- Desktop V2 now feels like the hub surface, not just a port of mobile flows.

Harsh read: this is the right product direction, but the desktop surface is now
large enough that test failures and layout regressions are more likely. It needs
release discipline, not more features.

### Android TV design, focus, and browsing

Android TV moved from "promising" to "actually plausible as the primary
consumption surface":

- Catalog rails, search, see-all, library, sports, IPTV, details, and settings
  screens were significantly reworked for D-pad navigation.
- Search navigation and rating display were refined, with TMDB/IMDb/Rotten
  Tomatoes assets and rating enrichment logic.
- `TvImagePrefetcher` and metadata caching reduce TV browse jank.
- Focus panels, hero overlays, media rails, nav rail behavior, and settings
  focus repair all improved.
- VOD library and Xtream movie/series paths are more explicit.
- TV startup and content loading were stabilized with warmup workers and
  cache-first patterns.

Harsh read: this is where Torve can beat Stremio/Syncler for households if the
real-device smoke passes. But "works in code" is not enough for TV. Remote UX
bugs feel ten times worse from the couch.

### Panda, TorBox, debrid, and credential intelligence

The credential-first story became stronger:

- Panda now supports multiple debrid credentials instead of forcing a single
  debrid path.
- Provider switching has dedicated tests.
- TorBox credentials sync across clients and restore after sign-in/bootstrap.
- Account-session bootstrap now does more work to hydrate credentials and
  settings after login.
- Desktop and Android setup flows steer more through Panda and reduce manual
  setup burden.

Harsh read: this is commercially important. "Sign in, connect what you already
pay for, and Torve makes it usable" is the clearest reason someone pays. It must
be made boringly reliable.

### Billing, subscription, and trust hardening

Monetization is less theoretical now:

- Stripe checkout, billing portal, webhook handling, and eligibility rules were
  added on the backend.
- Android billing flavor wiring was hardened across Google/Amazon/mobile/TV
  combinations.
- Store and direct billing states are clearer in subscription UI.
- Backend device limits are now used by clients instead of duplicated
  client-side assumptions.
- Google Play Integrity / client trust-signal plumbing was added.
- Desktop secure secret storage was upgraded.
- Diagnostics redaction and network error sanitization improved.
- Account-scoped media favorites sync landed on backend/shared/desktop/Android
  surfaces.

Harsh read: the money path exists, but the current test failure in media
favorites means the sync layer cannot be called stable today. Do not sell this
as "cross-device reliable" until that regression is fixed.

### Intelligence and source selection

Torve's intelligence layer is now a real differentiator:

- Source availability combines debrid cache, addon, Usenet readiness, IPTV live,
  Plex/Jellyfin, local download, LAN library, and watch history signals.
- Source-aware AI search and privacy sanitization are present.
- Stream scoring/filtering/handoff policy improved, including telemetry for
  provider categories and playback path outcomes.
- Mood and rating filters were added with tests.
- Media ratings are richer: TMDB, IMDb, Rotten Tomatoes style display, rating
  predicates, rating preferences, and detail/search/TV presentation.
- Live TV logo and EPG resolvers improved.
- Sports release display parsing was added.

Harsh read: this is the layer competitors do not fully have. The danger is
explainability. Users will not pay for "AI says no"; they will pay for "Torve
knows you have a cached 1080p source, your Plex copy, and tonight's live airing."

### IPTV, EPG, VOD, and DVR

The IPTV/VOD surface is much stronger:

- Xtream live, movie, and series handling is more explicit.
- Startup cache hydration improved, though current tests show regressions in
  cache-first channel restoration.
- EPG guide source resolution and channel logo resolution now have dedicated
  logic/tests.
- Recording metadata, file naming, storage quota, recording path resolution,
  recording service behavior, and UI states are better covered.
- Series-pass schema/resolver logic exists, but scheduler comments still mark
  series passes as not fully active unless the resolver hook is wired. Treat DVR
  as beta unless real provider smoke proves it.

Harsh read: IPTV users are valuable but high-support. Every playlist/provider
weirdness becomes your ticket. This can be a moat or a time sink.

### Release and platform work

Release tooling improved but still is not stable-launch complete:

- Desktop release appcast endpoint and release ritual exist.
- Linux packaging path and docs were added.
- Kotlin/AGP/Gradle readiness work landed.
- Updater handoff tests were adjusted so test workers do not exit.
- Windows install polish from the previous assessment still stands: appcast,
  MSI handoff, close-app WiX path, watchdog, and release script.

Harsh read: unsigned desktop binaries remain a commercial trust problem.
SmartScreen friction can destroy conversion from Reddit traffic.

## Verification Run On 2026-05-23

Latest verification reported after the startup-contract fix:

```powershell
.\gradlew.bat :shared:desktopTest :desktopApp:test --continue
```

Result:

- Passed. The two prior `ChannelsViewModelStartupTest` failures were realigned
  with the intended local-first startup contract: cached channels render
  immediately and startup does not issue a catalog refresh call.

Backend test command:

```powershell
.\scripts\dev.ps1 backend-test
```

- `-DryRun` passes and shows the intended flow: set safe test env defaults,
  start local Postgres when needed, wait for readiness, run dependency import
  check, run Alembic migrations, then run pytest.
- Real execution currently fails cleanly with setup instructions because Docker
  is installed but not running on this machine. This is a better failure mode
  than the prior raw Pydantic `DATABASE_URL` / `JWT_SECRET` import error, but it
  is not a backend test pass.

Current test inventory is broad: 255 Kotlin/Python test files across backend,
shared, desktop, Android unit tests, and Android instrumentation tests. That is
good. The desktop/shared slice is now green by report; backend still needs a
full run with Docker or an explicit database.

## Competitive Reality

### Direct competitor set

| Competitor | Current reality | Impact on Torve |
| --- | --- | --- |
| Stremio | Official site claims more than 30M users and broad platform support across Android, Android TV, Windows, macOS, Linux, LG TV, and more. Source: <https://www.stremio.com/?lang=en> | Torve cannot beat Stremio on free scale. Torve must beat it on credential-aware reliability, source explanation, LAN/downloads/Desktop hub, and paid support quality. |
| Syncler+ | Android/debrid psychology is strong. Official pricing is very cheap: Personal 5 devices is 15 USD/year, about 1.25 USD/month. Source: <https://app.syncler.net/plus> | Torve's 1.99/month can compete on price, but it still cannot win as "cheap debrid app only." It must win on AIO setup, cross-device, desktop, TV polish, and diagnostics. |
| Plex | Plex now monetizes remote personal media via Plex Pass or Remote Watch Pass; official Remote Watch Pass moves from 1.99/month intro to 2.99/month after June 1, 2026. Source: <https://www.plex.tv/plans/> | Plex validates that users pay for media convenience, but Plex is trusted and mainstream. Torve must look safer than an unsigned niche app. |
| Jellyfin | Free software, official clients free, no fees. Source: <https://jellyfin.org/> | Jellyfin sets a zero-price anchor. Torve must sell setup simplification and multi-source playback, not "media server." |
| Emby Premiere | 4.99/month, 54/year, 119 lifetime. Source: <https://emby.tv/premiere.html> | 4.99/month is market-acceptable if Torve looks serious and solves a painful workflow. |
| Channels DVR | 8/month or 80/year for a polished whole-home DVR. Source: <https://getchannels.com/docs/getting-started/quick-start-guide/subscription/> | IPTV/DVR users will pay, but only for reliability. Channels is the quality bar for live TV ergonomics. |
| Infuse Pro | App Store shows 1.99/month, 16.99/year, 99.99 lifetime in US pricing; Firecore support confirms monthly/yearly/lifetime Pro options. Sources: <https://apps.apple.com/us/app/infuse/id1136220934>, <https://support.firecore.com/hc/en-us/articles/360046954753-Purchases-and-Family-Sharing> | Infuse owns Apple playback polish. Torve on iOS must avoid competing as "video player only." |
| Kodi | Free, plugin-heavy, enormous install base. | Torve's advantage is opinionated setup, account sync, and provider explanation. Kodi users tolerate complexity; Torve buyers pay to remove it. |

### Where Torve is genuinely differentiated

- Credential-first onboarding through Panda instead of "install 10 addons and
  debug every provider manually."
- Source availability intelligence: debrid cache, addon, Usenet, IPTV, LAN,
  Plex/Jellyfin, local downloads, and watch history can all participate.
- Desktop as a real hub: local library, recordings, downloads, LAN serving,
  diagnostics, update management, and rich playback.
- Android TV is becoming a first-class couch surface instead of a stretched
  mobile app.
- Provider health and recovery explanations can make Torve feel safer than
  hobby apps if the copy is specific and honest.
- Direct billing plus store billing gives monetization flexibility.

### Where competitors still crush Torve

- Trust: Stremio/Plex/Infuse/Emby/Channels are known names. Torve is unknown.
- Platform coverage: iOS/macOS are still not verified.
- Price: Syncler is dramatically cheaper.
- Store distribution: Torve still has App Review / Google TV / Amazon review
  and policy risk.
- Reputation: unsigned Windows binaries and unproven real-device/release smoke
  are not acceptable for a hard public push.
- Support burden: Torve combines many fragile domains. Every provider outage,
  playlist bug, and debrid/Usenet failure can look like Torve's fault.

## Market Potential

Torve should not aim at mainstream streaming users first. The mainstream buyer
does not understand debrid, Usenet, Stremio addons, M3U, Xtream, EPG, LAN
handoff, or provider health. The first market is power users who already live in
one or more of these worlds and are tired of glue code.

### Without iOS

No iOS does not kill the niche. The debrid/IPTV/Android TV audience is heavily
Android/Windows/TV-box weighted. But no iOS reduces household trust, family
sharing, review credibility, and mainstream conversion.

| Scenario | Paid users | Gross MRR at 1.99 | Harsh interpretation |
| --- | ---: | ---: | --- |
| Weak launch | 150-400 | 300-800 USD | Not enough. Likely if Reddit sees it as another paid wrapper or install friction is high. |
| Modest niche win | 600-1,200 | 1,200-2,400 USD | Useful validation, but below the Germany after-tax income target. |
| Good indie outcome | 3,000-5,000 | 6,000-10,000 USD | This is the realistic target band for 2,000-3,000 USD/month after tax if support is controlled. |
| Strong niche breakout | 10,000+ | 19,900+ USD | Possible only if Android TV is excellent, Reddit trust forms, and churn/support stay low. |

My honest estimate without iOS: **3,000 active monthly users is possible but
hard**. Torve must ship a clean beta, show real TV/desktop demos, and earn trust
in communities. The current branch is not ready for that push because the test
run is red.

### With iOS Implemented And Approved

iOS changes the trust story more than the technical story. Many people in the
target niche use Android TV boxes and Windows desktops, but families often have
iPhones/iPads. iOS also gives Torve a stronger "this is a real product" signal.

| Scenario | Paid users | Gross MRR at 1.99 | Harsh interpretation |
| --- | ---: | ---: | --- |
| iOS approved but weak demand | 1,000-2,000 | 2,000-4,000 USD | Still not enough if support is heavy. |
| Solid cross-platform product | 3,000-6,000 | 6,000-12,000 USD | Income target becomes realistic and less fragile. |
| Real brand in the niche | 10,000-25,000 | 19,900-49,750 USD | Requires app-store acceptance, excellent TV UX, low churn, and community trust. |

Harsh iOS caveat: App Review can be the blocker. If Torve looks like a source
aggregation/piracy-adjacent app instead of a legal BYO-credentials/local-media
tool, iOS may not ship or may ship with features constrained.

## MRR And Germany After-Tax Reality

Exchange-rate reference for this assessment: 1 USD is roughly 0.862 EUR on May
23, 2026. Source: <https://www.xe.com/en-us/currencyconverter/convert/?Amount=1&From=USD&To=EUR>

Germany-specific constraints:

- The 2026 German basic tax-free allowance is 12,348 EUR for a single taxpayer
  under Section 32a EStG. Source: <https://ao.bundesfinanzministerium.de/lsth/2026/A-Einkommensteuergesetz/IV-Tarif-31-34b/Paragraf-32a/paragraf-32a.html>
- Self-employed statutory health insurance is a serious monthly cost. TK's 2026
  self-employed contribution table shows health insurance around 16.69%-17.29%
  depending on sickness benefit, plus long-term care insurance around 3.6%-4.2%,
  with minimum and maximum income bases. Source: <https://www.tk.de/en/member/health-care-contribution-self-employed-2176982>
- Google Play subscriptions are generally 15% service fee. Source:
  <https://support.google.com/googleplay/android-developer/answer/112622>
- Apple Small Business Program is 15% commission if accepted. Source:
  <https://developer.apple.com/app-store/small-business-program/>
- Amazon says developers under 1M USD/year receive 80/20 revenue share. Source:
  <https://www.developer.amazon.com/apps-and-games>
- Stripe Germany card processing is much lower than app-store commission but
  still not zero. Source: <https://stripe.com/en-de/payments>

### Required paid user count

Assumptions:

- Price is 1.99/month standard.
- Lifetime structure is 39.99 founder lifetime capped at 500 users, then 69.99
  regular lifetime.
- Average platform/payment drag: 10%-20%.
- Refunds/payment failures/support/ops reserve: 8%-18%.
- German income tax + health/care reserve on operator profit: roughly 35%-45%
  at the target income band, depending on personal facts.
- No pension provision is included. If you want retirement savings, the target
  gross must be higher.

| Monthly price | Paid users likely needed for 2,000-3,000 USD/month after tax | Verdict |
| ---: | ---: | --- |
| 1.99 | 3,000-5,100 | Too many for a first launch unless Torve becomes a viral niche app. Do not make this the normal price. |
| 2.99 | 2,000-3,400 | Possible but still hard; leaves weak support margin. |
| 3.99 | 1,500-2,500 | Viable if churn is low and annual conversion is good. |
| 4.99 | 1,200-2,000 | Best default target. Comparable to Emby, cheaper than Channels, above Plex Remote Watch because Torve does more. |
| 7.99 | 750-1,250 | Only plausible for a power/pro tier with DVR/LAN/family/device benefits. |

### Pricing recommendation

- Default monthly: **1.99 USD/EUR** if low-friction adoption is the strategy.
- Founder lifetime: **39.99**, capped at **500 accounts**.
- Regular lifetime after the founder cap: **69.99**.
- Do not reopen 23.99 lifetime. It was too cheap and would have cannibalized MRR
  after only 12 months of monthly-equivalent revenue.
- Later premium lifetime, once Android TV/desktop/LAN/DVR/support are proven:
  **99.99** is optional.
- Family/power tier: **7.99-9.99/month** only after TV/DVR/LAN is proven.
- At 1.99/month, Torve needs volume. The pricing can work only if support
  volume stays low and Reddit/community trust drives thousands of paid users.

## Reddit Marketing Assessment

Reddit can work for Torve because the audience is there. Reddit can also bury
Torve immediately if it smells like spam, piracy bait, or a paid wrapper around
free tools.

Relevant Reddit facts:

- Reddit ads support community, interest, and keyword targeting around active
  communities/conversations. Source:
  <https://www.business.reddit.com/advertise/targeting/community-and-interest>
- Reddit ad formats include free-form/text/link/image/video/carousel/conversation
  styles, and Reddit explicitly says authenticity matters. Source:
  <https://www.business.reddit.com/learning-hub/articles/what-are-reddit-ads-formats-smbs>
- Many communities still enforce strict self-promotion norms; Reddit's own mod
  guidance notes some communities use a 10% self-promo rule. Source:
  <https://support.reddithelp.com/hc/en-us/articles/28012014962580-How-do-I-keep-spam-out-of-my-community>

### Organic Reddit plan

Do not launch with "I built an app, please buy it." That will underperform.

1. Spend 2-4 weeks participating before launch with the founder account.
2. Post useful technical writeups, not sales pages:
   - "How to debug IPTV EPG mismatch without leaking playlist URLs"
   - "Why debrid cached-source detection lies sometimes"
   - "What I learned making Android TV focus not feel broken"
   - "How to transfer media credentials without syncing plaintext secrets"
3. Ask mods before posting in communities that are strict about tools/products.
4. Use transparent disclosure every time: "I am building Torve."
5. Avoid piracy-coded claims like "watch anything." Use "your services, your
   libraries, your legal playlists, your credentials."
6. Launch with a demo video, exact platform status, known limitations, and a
   founder beta discount.

Likely target communities, subject to their rules:

- `r/AndroidTV`, `r/ShieldAndroidTV`, `r/FireTV`
- `r/PleX`, `r/jellyfin`, `r/kodi`
- `r/selfhosted`, `r/HomeServer`, `r/usenet`
- debrid and Stremio-adjacent communities only with careful positioning and mod
  approval
- IPTV communities only if legal/BYO wording is clear and no provider sourcing

### Paid Reddit ads

Start small:

- 20-50 USD/day for 14 days.
- Separate ad groups for Android TV, Plex/Jellyfin/local library, IPTV/EPG/DVR,
  and debrid/Usenet setup.
- Use free-form/text ads that look like a real founder post.
- Landing page must show a 60-90 second product demo above the fold.
- Do not send ad traffic to a generic homepage.

KPI math:

- If visitor-to-install is 8% and install-to-paid is 5%, 10,000 qualified
  visitors produce about 40 paid users.
- To reach 1,200 paid users, you need either much better conversion, sustained
  organic trust, affiliates/YouTube, or repeated community exposure.
- Reddit alone probably will not carry the whole business. It can seed the
  first 100-500 serious users if the product demo is strong.

Harsh Reddit verdict: Torve has enough differentiated product to earn attention,
but not enough launch trust while tests are red and installers are unsigned. Fix
the release trust issues first, then use Reddit to recruit beta users, not to
declare stable.

## What Blocks Stable Paid Launch

| Blocker | Why it matters |
| --- | --- |
| Backend pytest not fully run yet | `scripts/dev.ps1 backend-test` now has preflight/setup handling, but real execution still needs Docker running or an explicit test database. |
| Unsigned Windows artifacts | SmartScreen/Defender friction kills cold traffic conversion. |
| Clean Windows VM smoke | Sandbox smoke was useful, but stable needs clean real-user Windows install/update/playback proof. |
| Android TV real-device smoke | TV is now the sales surface. Emulator/build success is not enough. |
| iOS/macOS not built | iOS remains hypothetical until Xcode/simulator/TestFlight exist. |
| App-store policy proof | Google TV, Amazon, and Apple review can reject or constrain source-aggregation language/features. |
| Support workflow | Need diagnostics export, known-issue status, provider outage copy, and a support triage routine before paid users arrive. |
| Landing page and demo | The product is too complex to sell from screenshots. Needs a short, concrete demo. |
| Pricing discipline | 1.99/month requires thousands of active subscribers; the 39.99 founder lifetime cap must stay hard, then lifetime should move to 69.99. |

## Highest Value Next Actions

1. Run `.\scripts\dev.ps1 backend-test` with Docker running or an explicit
   `DATABASE_URL`, and record the actual pytest result.
2. Keep `.\gradlew.bat :shared:desktopTest :desktopApp:test` green in the
   release checklist now that the local-first startup contract is locked.
3. Run Android TV real-device smoke on at least Shield/Google TV and Fire TV.
4. Produce a fresh Windows beta MSI, install it in a clean VM, verify launch,
   playback, updates, uninstall, and Defender behavior.
5. Procure/wire Windows code signing before any serious paid traffic.
6. Cut a no-iOS beta launch page with exact platform badges: Windows, Android
   mobile, Android TV, Fire TV; iOS "not yet."
7. Record a founder demo focused on one workflow: sign in, connect credentials,
   see what is playable, play on TV/desktop, and show provider failure copy.
8. Prepare a Reddit beta campaign with useful posts first, launch post second,
   paid ads third.
9. Run macOS/iOS build and simulator smoke as soon as a Mac is available.
10. Keep feature scope frozen until the above is done.

## Net Call

- **Closed beta:** GO from the shared/desktop test perspective. Use known
  enthusiast users who understand provider variability.
- **Public beta:** Conditional GO after backend pytest is actually run,
  Android TV real-device smoke, and clean Windows VM smoke.
- **Public paid stable:** NO-GO today.
- **iOS:** NO-GO today.
- **Income target:** Possible, not guaranteed. Without iOS, the practical target
  is roughly 3,000-5,100 active monthly users at 1.99/month, or a strong
  monthly/founder-lifetime mix. With iOS approved and good TV execution, the
  target becomes meaningfully easier, but App Review is a real risk.

Final harsh sentence: Torve now has enough product to justify charging money,
but not enough release proof to confidently ask strangers on Reddit for money.
Fix the red tests and trust gates first; then market it as a serious beta, not
as finished stable software.
