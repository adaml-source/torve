# Torve Market Readiness Assessment

Last updated: 2026-05-27

Assessment baseline: current local workspace in `C:\Users\Anwender\StudioProjects\streamvault`.
The workspace is heavily modified and contains the recent Watch Stats, Discord
Beta Program, diagnostics/support, Android TV/Fire TV, mobile, desktop, and
release-artifact work. This assessment assumes the current implementation is the
product baseline, not only `origin/master`.

This is not tax, legal, or investment advice. It is a product, release, pricing,
and revenue-readiness assessment for deciding whether Torve can realistically
become a 2,000-3,000 USD/month after-tax income source for a Germany-based solo
operator.

## Executive Verdict

Torve is materially more market-ready than it was in the previous assessment.
The biggest change is that the product now has credible "launch machinery":

- Android Google Mobile and Google TV release AABs build at version code 82.
- Fire TV release APK builds and was installed successfully on both test Fire TV
  devices, `192.168.178.110:5555` and `192.168.178.142:5555`.
- Windows MSI packaging works and produced `Torve-1.0.72.msi`.
- Discord Beta Program exists across shared KMP, Android mobile, Android TV /
  Fire TV, and desktop, with verified-email gating, code generation, status
  states, premium separation, and a non-expiring Discord invite fallback.
- TV Watch Stats exists as a Settings > About sub-route, uses the watch_session
  pipeline, and now has semantic filters, source/confidence visuals, recent
  activity, and premium/unavailable states.
- Report a Problem / diagnostics work is now much stronger, especially for a
  beta/support-heavy product.
- Watch history, Trakt/manual/imported session writes, runtime confidence labels,
  and truthful stats are much more defensible than before.

That changes the verdict:

> Torve is now credible for a controlled public beta and founder sale, but not
> yet safe to present as a finished stable product.

The limiting factor is no longer "there is not enough product." The limiting
factors are release trust, policy risk, support load, app-store approval, and
the missing iOS surface.

The commercial wedge is still strong:

> Torve is a credential-first media hub for people who already use servers,
> playlists, debrid, Usenet, Trakt/SIMKL, local files, or TV boxes, and want one
> account-backed app that explains what is playable and keeps their watch state
> honest.

At the current price point, Torve is underpriced for the amount of product, but
that may be acceptable as an adoption strategy. The 39.99 founder lifetime offer
capped at 500 users is useful for runway and early trust. The 69.99 post-founder
lifetime is commercially reasonable, but lifetime must remain capped or
deliberately rationed because it can cannibalize the 1.99/month base.

## Current Readiness Ratings

| Target | Rating | Verdict |
| --- | ---: | --- |
| Closed enthusiast beta | 9.0/10 | GO. Use Discord beta flow, verified email, diagnostics, and known users. |
| Public beta, no iOS | 8.4/10 | GO if positioned honestly as beta and support capacity is protected. |
| Founder paid beta | 8.0/10 | GO with clear limitations, capped founder lifetime, and no "finished stable" wording. |
| Public paid stable | 7.2/10 | Not yet. Needs store approvals, Windows signing/trust proof, backend smoke, and broader real-device QA. |
| Android TV / Fire TV | 8.4/10 | Strong enough for beta. Needs continued D-pad smoke and visual QA, but release APK installed on real Fire TV devices. |
| Android mobile | 8.1/10 | Strong companion/account surface; Beta Program and Discord entry are now real. |
| Desktop Windows | 8.2/10 | Strong product surface; MSI builds. Still needs signing/SmartScreen clean-machine proof. |
| Watch Stats | 8.0/10 | Differentiating and truthful. Needs real-history QA and continued runtime metadata cleanup. |
| Discord Beta Program | 8.5/10 | Product-ready client flow. Backend must keep premium/beta separation authoritative. |
| iOS | 2.5/10 | Not in scope yet. This is the largest platform/trust gap. |
| Income readiness | 6.7/10 | Plausible with beta/founder strategy; monthly-only 1.99 requires high volume. |

The honest improvement is significant: Torve moved from "promising but not
sellable" to "sellable as a serious beta." The honest limitation is also clear:
without iOS, signed Windows reputation, and store approval proof, it is not yet
a broad consumer launch.

## What Changed Since The Previous Assessment

### Discord Beta Program

The Beta Program is now a real cross-platform feature instead of a plan:

- Settings entry is visible from main settings surfaces.
- Email verification gates code generation.
- Premium users can still apply as beta testers for early builds/features.
- Paid premium remains separate from temporary `discord_beta` free premium.
- The July 31, 2026 date is now treated as the end of free beta premium access,
  not the end of Discord beta tester opt-in.
- Android mobile can open the Torve Discord invite and prefers the Discord app
  when installed.
- Android TV / Fire TV shows the invite and instructs users to use phone/desktop
  for Discord rather than forcing a TV browser flow.
- Desktop shows the invite and opens it through the OS browser.
- The current non-expiring invite fallback is:
  `https://discord.gg/dVHFAh7Amx`.

This matters commercially. A Discord beta gives Torve a launch funnel, a support
venue, a way to verify tester intent, and a controlled path for early feedback.
It also reduces the risk of dumping a complex media app straight into public
store reviews.

Remaining risk: the client must never grant beta or premium locally. Backend
state from `/me/beta/status` and `/me/access-state` must remain authoritative.

### Watch Stats

Watch Stats is now one of Torve's clearest premium differentiators:

- `watch_session` exists as a truthful source of watch activity.
- Playback, manual watched, and Trakt imported completed paths write explicit
  sessions.
- `WatchProgressRepository.saveProgress()` does not create watch sessions.
- TV Watch Stats opens from Settings > About only, not as a top-level route.
- Source/status/runtime confidence labels exist: Torve, Trakt, Manual,
  Migrated; measured, estimated, unknown; completed, partial, manual completed,
  imported completed, abandoned.
- Semantic dashboard modes now exist in product terms: All/Movies/Shows scope,
  Source, Ratings, Genres, Years, Activity.
- The dashboard no longer needs fake charts to look useful: Source and runtime
  confidence are real, while ratings/genres/years can show unavailable states
  when local metadata is missing.

Commercial value: Watch Stats is not just decoration. It gives Torve a concrete
"premium insight" story, especially for users importing Trakt history or moving
between devices. The strongest marketing wording is not "analytics dashboard";
it is "Torve tells you what it counted, where it came from, and whether runtime
was measured, estimated, or unknown."

Remaining risk: imported episodes can still expose runtime metadata gaps. Users
expect episode runtimes to exist from Trakt/SIMKL/TMDB/IMDb-style metadata. The
UI must keep avoiding misleading `0m` / `Unknown` presentation when the app
could lazily hydrate locally without blocking page open.

### TV / Fire TV

TV is now much closer to the sales surface Torve needs:

- Settings > About > Watch Stats route is implemented and remains a sub-route.
- TV Beta Program entry is reachable and focusable.
- Report a Problem text input behavior was corrected so keyboard opening is
  controlled instead of stealing focus.
- D-pad/focus fixes landed across settings, beta, and Watch Stats.
- Fire TV release APK built and installed successfully on both real devices:
  `192.168.178.110:5555` and `192.168.178.142:5555`.

Commercial value: Android TV / Fire TV is probably Torve's best first market.
The no-iOS limitation hurts less for a TV-box/debrid/IPTV audience than it would
for mainstream mobile-first software.

Remaining risk: TV UX bugs are conversion killers. A mouse-user might forgive a
layout glitch; a couch user will quit if focus gets stuck. Every TV release
still needs manual D-pad smoke on real hardware.

### Desktop Windows

Desktop remains one of Torve's strongest surfaces:

- MSI packaging now works through `packageMsiCloseApp`.
- Latest MSI artifact produced: `Torve-1.0.72.msi`.
- Desktop Beta Program uses the same Discord invite fallback.
- Desktop is still the best place for account/settings, diagnostics, local
  source management, downloads, and high-density media browsing.

Commercial value: Windows can be Torve's "control center" while TV is the
consumption surface.

Remaining risk: unsigned MSI / SmartScreen friction can destroy cold traffic
conversion. A product asking users to connect sensitive media credentials cannot
look like an unknown executable from a random folder.

### Diagnostics and Report a Problem

The diagnostics/support story is much stronger:

- Report a Problem exists on TV.
- Android diagnostics export/redaction work exists.
- Server-side support route/tests exist in the workspace.
- TV input behavior was corrected after real-device feedback.

Commercial value: diagnostics reduce support cost, which is critical at
1.99/month. At that price, you cannot manually debug every playlist, account,
provider, and TV-box issue.

Remaining risk: support must be operationalized. A diagnostic zip without a
triage process still becomes manual support work.

### Billing, Entitlements, and Beta Separation

The current product rules are much healthier:

- Paid premium remains separate from beta access.
- Premium users can apply for beta tester access.
- Non-premium approved beta testers may receive temporary free premium through
  backend state.
- Temporary free premium ends no later than July 31, 2026.
- Discord beta tester opt-in can continue after that without granting free
  premium.
- Founder lifetime is capped at 500 users.

Commercial value: this avoids the worst beta mistake: confusing "tester" with
"free paid entitlement forever."

Remaining risk: backend and client copy must stay aligned. If one layer says
applications are closed while another says beta opt-in continues, users will not
trust the program.

## Verification Snapshot

Recent commands run successfully in this workspace:

```powershell
.\gradlew.bat :shared:desktopTest --tests "com.torve.presentation.beta.*" --tests "com.torve.data.beta.*"
.\gradlew.bat :androidApp:compileGoogleMobileDebugKotlin :androidApp:compileAmazonTvDebugKotlin :desktopApp:compileKotlin
.\gradlew.bat :androidApp:bundleGoogleMobileRelease :androidApp:bundleGoogleTvRelease
.\gradlew.bat :androidApp:assembleAmazonTvRelease
.\gradlew.bat "-PtorveMsiVersion=1.0.72" :desktopApp:packageMsiCloseApp
git diff --check
```

Recent artifact results:

- Google Mobile AAB:
  `androidApp/build/outputs/bundle/googleMobileRelease/androidApp-google-mobile-release.aab`
- Google TV AAB:
  `androidApp/build/outputs/bundle/googleTvRelease/androidApp-google-tv-release.aab`
- Amazon / Fire TV APK:
  `androidApp/build/outputs/apk/amazonTv/release/androidApp-amazon-tv-release.apk`
- Windows MSI:
  `desktopApp/build/compose/binaries/main-closeapp/msi/Torve-1.0.72.msi`

Android versioning:

- Base version code: `82`
- Google Mobile version code: `10082`
- Google TV / Amazon TV version code: `20082`
- Version name: `1.0.72`

Fire TV install results:

- `adb -s 192.168.178.110:5555 install -r ...androidApp-amazon-tv-release.apk`: success
- `adb -s 192.168.178.142:5555 install -r ...androidApp-amazon-tv-release.apk`: success

What this does not prove:

- It does not prove Google Play / Google TV review approval.
- It does not prove Amazon Appstore approval.
- It does not prove Windows SmartScreen trust.
- It does not prove iOS/macOS readiness.
- It does not prove full backend pytest passed in a clean environment.
- It does not prove long-session TV playback stability across real provider
  variability.

## Competitive Reality

Torve is entering a market with brutal free and low-cost anchors.

| Competitor | Current reality | Impact on Torve |
| --- | --- | --- |
| Stremio | Official marketing says Stremio is free and available across Windows, macOS, Linux, Android, Android TV, Samsung/LG TV, browser, and iOS web/sideload paths; official pages also claim more than 30M users. Sources: [Stremio device support](https://stremio-app.com/), [Stremio 30M claim](https://www.stremio.com/?data1=google_disp_cp2_adv1_ag1_ad2). | Torve cannot win as a cheaper Stremio. It must win on account-backed setup, source explanation, diagnostics, TV focus quality, desktop hub value, and trustworthy premium support. |
| Syncler+ | Syncler+ is still the cheap Android/debrid mental anchor; official pricing positions Personal 5-device access as the entry plan. Source: [Syncler+ pricing](https://syncler.net/plus). | 1.99/month is competitive, but Syncler trains users to expect very low pricing. Torve needs broader value than debrid playback. |
| Plex | Plex validates paid personal-media convenience. Remote Watch Pass is 1.99/month before June 1, 2026 and moves to 2.99/month after that; Plex Pass is the stronger mainstream trust product. Source: [Plex plans](https://www.plex.tv/plans/). | Torve at 1.99/month is plausible, but Plex is trusted. Torve must overcome unknown-brand friction. |
| Jellyfin | Official positioning is free software, and official server/clients are free. Sources: [Jellyfin home](https://jellyfin.org/), [Jellyfin clients](https://jellyfin.org/downloads/). | Jellyfin creates a zero-price anchor. Torve must sell convenience, cross-source workflow, TV UX, setup, and diagnostics. |
| Emby Premiere | Common pricing remains around 4.99/month, 54/year, 119 lifetime. Source: [Emby Premiere](https://emby.tv/premiere.html). | 4.99/month is market-accepted for media utility if the product feels mature. Torve's 1.99/month is low, not high. |
| Channels DVR | Whole-home DVR subscription remains a paid niche product; official docs position it as a subscription media powerhouse. Source: [Channels subscription docs](https://getchannels.com/docs/getting-started/quick-start-guide/subscription/). | IPTV/DVR users will pay, but reliability expectations are high. |
| Infuse Pro | App Store pricing examples show monthly and lifetime Pro options, e.g. UK listing shows 1.99/month and 99.99 lifetime. Source: [Infuse App Store listing](https://apps.apple.com/gb/app/infuse-video-player/id1136220934). | Infuse proves polished playback can support paid subscriptions/lifetime, but it also sets an Apple-grade UX expectation Torve cannot claim without iOS/macOS proof. |

### Torve's Real Differentiation

Torve is not strongest as a player. It is strongest as a multi-source media
operating layer:

- Credential-first setup and account restoration.
- TV-first browsing and playback decisions.
- Desktop control-center behavior.
- Watch Stats with measured/estimated/unknown truthfulness.
- Source/status diagnostics users can understand.
- Discord beta funnel and support loop.
- BYO services, local libraries, playlists, debrid, Usenet, Trakt/SIMKL, and
  provider-health thinking in one product.

### Where Torve Still Loses

- No iOS.
- Unknown brand.
- Unsigned/low-reputation Windows installer risk.
- App-store policy uncertainty.
- Pricing psychology: Stremio/Jellyfin are free; Syncler is cheap.
- Support complexity: every provider outage can look like a Torve bug.
- Metadata edge cases: runtimes, years, genres, ratings, show/episode identity.

## Market Potential Without iOS

No iOS does not kill the first market. The first serious Torve buyers are likely
Android TV / Fire TV / Windows / self-hosted / debrid / IPTV users. That market
is more tolerant of non-iOS products than mainstream families are.

But no iOS reduces:

- household trust,
- family sharing,
- App Store credibility,
- creator/influencer confidence,
- mainstream "this is a real product" perception.

### Paid User Potential

| Scenario | Paid accounts | Gross MRR at 1.99 | Honest interpretation |
| --- | ---: | ---: | --- |
| Weak beta conversion | 100-300 | 199-597 USD | Easy to hit from Discord/Reddit, but not a business. |
| Useful niche validation | 500-1,000 | 995-1,990 USD | Good signal. Still not enough after German tax/health/support. |
| Strong no-iOS indie outcome | 2,000-4,000 | 3,980-7,960 USD | Plausible if TV/Fire TV is reliable and Discord/Reddit trust forms. |
| Breakout niche | 8,000-15,000 | 15,920-29,850 USD | Possible but requires creator/community distribution and very low support drag. |

My updated no-iOS estimate:

- 500-1,000 paid accounts is realistic if the beta funnel is handled well.
- 2,000-4,000 paid accounts is possible but not automatic.
- 8,000+ is not impossible, but it requires Torve to become a known niche tool,
  not merely "another media app."

The current product can plausibly support the first 500-1,000 paying users as a
beta if support is tightly controlled. It is not yet proven for 5,000+ users.

## Pricing Assessment

Current intended pricing:

- Monthly: `1.99`
- Founder lifetime: `39.99`, capped at `500` users
- Regular lifetime after founder cap: `69.99`

### Monthly 1.99

1.99/month is good for adoption but weak for income. It is psychologically easy
to buy and lines up with low-cost media utilities like Plex Remote Watch Pass
and Infuse monthly pricing, but it requires volume.

Assumptions for rough solo-operator math:

- Consumer price is 1.99/month.
- App-store subscriptions generally cost around 15% platform fee on Google Play
  subscriptions and Apple Small Business Program IAPs. Sources: [Google Play
  service fees](https://support.google.com/googleplay/android-developer/answer/112622?hl=en-CA),
  [Apple Small Business Program](https://developer.apple.com/app-store/small-business-program/).
- Direct Stripe is cheaper than app stores, but low-ticket payments still have
  meaningful fixed-fee drag.
- EU VAT / store tax handling, refunds, payment failures, support tooling, and
  hosting reduce the useful gross.
- Germany self-employed health/care contributions are serious; TK lists 2026
  self-employed health contribution structures with 14.0%-14.6% base plus a
  2.69% TK supplementary contribution, before long-term care. Source:
  [TK self-employed contribution rates](https://www.tk.de/en/member/health-care-contribution-self-employed-2176982).
- USD/EUR reference is around 0.859 EUR per USD on May 25-27, 2026. Sources:
  [ExchangeRates UK USD/EUR](https://www.exchangerates.org.uk/Dollars-to-Euros-currency-conversion-page.html),
  [X-Rates USD/EUR](https://www.x-rates.com/calculator/).

Practical after-platform/support/tax value per 1.99 subscriber is likely around
0.65-0.95 USD/month to the operator, depending on channel mix, VAT handling,
support cost, and personal tax facts.

| Desired after-tax operator income | Required active monthly subscribers at 1.99 |
| ---: | ---: |
| 1,000 USD/month | roughly 1,100-1,550 |
| 2,000 USD/month | roughly 2,200-3,100 |
| 3,000 USD/month | roughly 3,200-4,700 |

Harsh read: at 1.99/month, the product must become a volume product. Torve can
reach the lower end if Discord/Reddit/TV-box communities trust it. It will not
reach the upper end from passive app-store discovery.

### Founder Lifetime 39.99, Capped At 500

Gross potential:

```text
500 * 39.99 = 19,995 gross
```

This is a good beta runway mechanism, not a long-term business model.

Why it works:

- It creates urgency.
- It rewards early risk-taking.
- It gives a cash cushion for signing, hosting, devices, store fees, and
  support infrastructure.
- The cap prevents permanent underpricing.

Why it is dangerous:

- 500 lifetime users can create a lot of support without recurring revenue.
- If the first 500 are the most enthusiastic users, you may cannibalize the
  best future monthly customers.
- At 39.99, the customer breaks even versus 1.99/month after about 20 months.
  That is generous.

Recommendation: keep the 500 cap hard. Do not extend it casually.

### Regular Lifetime 69.99

69.99 is reasonable for the post-founder lifetime price.

At 1.99/month, 69.99 equals about 35 months of monthly revenue before fees.
That is fair for a media utility if Torve looks stable. It is still cheaper
than many mature lifetime media products:

- Emby lifetime is commonly positioned around 119.
- Infuse lifetime examples are around 99.99 in App Store listings.

Recommendation:

- 39.99 founder lifetime: yes, capped at 500.
- 69.99 regular lifetime: yes, but do not make it the default CTA forever.
- Monthly 1.99: yes for beta/adoption, but consider raising later.
- Future annual: strongly consider `19.99/year` or `24.99/year`.
- Future monthly after beta: consider `2.99` or `3.99` if support load is high.

## Revenue Model Scenarios

### Scenario A: Founder Beta Launch

Assume:

- 500 founder lifetime accounts sell over 1-3 months.
- 300-800 monthly subscribers remain after founder cap starts closing.

Gross:

- Founder lifetime: 19,995 one-time gross.
- Monthly: 597-1,592 MRR gross.

Verdict: good runway, not yet income stability. This can fund the next phase:
code signing, devices, backend/support, landing page, iOS prep.

### Scenario B: Conservative Paid Beta

Assume:

- 250 founder lifetime users.
- 700 monthly users at 1.99.

Gross:

- 9,997.50 one-time founder gross.
- 1,393 MRR gross.

Verdict: useful validation. Not enough as full income, but enough to prove
people pay.

### Scenario C: Strong Niche No-iOS Launch

Assume:

- 500 founder lifetime users sold out.
- 2,500 monthly subscribers at 1.99.

Gross:

- 19,995 one-time founder gross.
- 4,975 MRR gross.

Verdict: close to the lower end of the 2,000-3,000 USD after-tax goal if support
does not explode. This is a realistic target for the first serious year without
iOS, but it requires strong community distribution.

### Scenario D: Mature Cross-Platform With iOS Later

Assume:

- 4,000-8,000 monthly subscribers.
- Lifetime remains available at 69.99 but is not over-promoted.

Gross:

- 7,960-15,920 MRR gross, plus lifetime cash spikes.

Verdict: the income target becomes realistic. iOS is not necessary for the first
validation, but it makes the long-term business much less fragile.

## Go-To-Market Recommendation

### Positioning

Do not position Torve as:

- "watch anything,"
- "Netflix replacement,"
- "piracy app,"
- "Stremio but paid,"
- "just another player."

Position Torve as:

> A premium media control center for your own sources, services, libraries,
> playlists, and watch history.

Best short pitch:

> Connect what you already use. Torve shows what is playable, tracks what was
> actually watched, and keeps TV, desktop, and mobile in sync.

### First Funnel

Use Discord beta as the first funnel:

1. Settings-visible Beta Program card.
2. Verified email required.
3. Generate Discord code.
4. User joins Discord and applies in `#beta-info`.
5. Staff approves testers.
6. Non-premium testers may receive temporary free premium until July 31, 2026.
7. Premium users can still become beta testers without needing a free premium
   grant.

This is better than opening paid access to everyone immediately because Torve's
support surface is broad.

### Founder Offer

Use founder lifetime carefully:

- "First 500 founder accounts."
- "39.99 one-time."
- "Includes premium features for the life of the Torve product."
- "Beta software; iOS not available yet."
- "Discord beta tester access is separate from paid premium."

Avoid:

- unlimited lifetime sale,
- false urgency beyond the 500 cap,
- implying free beta premium is permanent,
- hiding known platform limitations.

### Reddit / Community

Reddit can work, but not with a hard sales post first.

Useful first posts:

- "How I fixed D-pad focus traps in a complex Android TV app."
- "What makes watch stats dishonest, and how to label measured vs estimated
  runtime."
- "Why media apps need diagnostics that redact credentials."
- "How to avoid leaking playlist/provider secrets in bug reports."
- "What I learned building a beta flow that does not grant access locally."

Then launch:

- show a TV demo,
- show Watch Stats,
- show Discord beta flow,
- be explicit about no iOS,
- be explicit that users bring their own legal sources/services.

## Remaining Stable-Launch Blockers

| Blocker | Severity | Why it matters |
| --- | ---: | --- |
| iOS missing | High | Limits trust, household coverage, App Store legitimacy, and mainstream appeal. |
| Windows code signing / SmartScreen | High | Cold users will not trust an unsigned media app that handles credentials. |
| Store review approval not proven | High | Google TV/Amazon policy may constrain wording/features. |
| Full backend pytest / deployment smoke | High | Beta, billing, support, and entitlement trust depend on backend correctness. |
| Real-device TV smoke matrix | High | Fire TV install succeeded, but focus/playback/settings/reporting need repeated manual QA. |
| Support operations | High | 1.99/month cannot support high-touch manual debugging. |
| Runtime metadata gaps | Medium | Watch Stats must avoid `Unknown` where runtime can be locally hydrated. |
| Landing page/demo | Medium | Product is too complex to sell without a short demo. |
| Pricing migration plan | Medium | 1.99 is good for beta but may be too low long-term. |
| App-store legal copy | Medium | Must avoid piracy wording and credential/provider leakage. |

## Highest-Value Next Actions

1. Upload Google Mobile and Google TV AABs to internal testing and record review
   or install-track result.
2. Run a structured Fire TV QA pass on both devices after the latest build:
   Settings, Beta Program, Watch Stats, Report a Problem, playback, details,
   account restore.
3. Install `Torve-1.0.72.msi` on a clean Windows VM and record SmartScreen,
   launch, sign-in, playback, update/uninstall behavior.
4. Procure Windows code signing before any broad paid traffic.
5. Run backend tests in a clean environment and document the result.
6. Create a beta landing page with:
   - Windows,
   - Android mobile,
   - Android TV,
   - Fire TV,
   - "iOS coming later",
   - Discord beta application instructions,
   - founder lifetime cap.
7. Record a 60-90 second TV + desktop demo:
   - sign in,
   - connect/setup,
   - play on TV,
   - view Watch Stats,
   - report a problem safely.
8. Keep founder lifetime capped at 500.
9. Start beta with 50-100 curated Discord users before opening the founder sale
   to a wider audience.
10. Plan iOS/macOS feasibility as the next major trust multiplier, not as a
    blocker for initial beta.

## Updated Net Call

### Closed Beta

GO.

Torve is now strong enough for curated testers. The Discord flow, diagnostics,
TV install proof, MSI build, and Watch Stats work make this credible.

### Public Beta

GO, if the wording is honest.

Use "beta" loudly. Say no iOS. Say users bring their own sources/services. Say
metadata and provider behavior can vary. Say diagnostics are safe/redacted.

### Founder Paid Beta

GO, with guardrails.

The 39.99 founder lifetime capped at 500 is commercially reasonable. It should
be framed as an early-supporter offer, not as proof the app is finished.

### Public Paid Stable

Not yet.

Stable needs store approval proof, Windows signing/trust, backend smoke, and a
larger TV QA pass. The product can charge now as beta; it should not yet claim
stable.

### Income Potential

Possible, not guaranteed.

At 1.99/month, Torve needs roughly 2,200-4,700 active monthly subscribers to
net around 2,000-3,000 USD/month after platform fees, support/ops, and German
tax/health drag. The founder lifetime sale can create meaningful runway, but it
does not replace recurring revenue.

The strongest realistic path is:

1. curated Discord beta,
2. founder lifetime capped at 500,
3. TV/Windows proof,
4. internal/public store tracks,
5. controlled Reddit/community launch,
6. then decide whether 1.99 remains the long-term price or becomes the beta
   acquisition price before moving to 2.99/3.99.

Final harsh sentence: Torve is now good enough to ask enthusiasts for money as a
beta, but not yet proven enough to ask the general market to trust it as stable.
The product value is there; the next bottleneck is trust, not features.
