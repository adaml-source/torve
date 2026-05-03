# Torve Market Readiness Assessment

Last updated: 2026-05-03 (post B4 smoke teardown + server reconciliation)

## Harsh Verdict

The morning's assessment said desktop + Android beta was "GO, full stop" at
8.5/10. **That was wrong.** Today's B4 smoke — the actual one, against
real binaries in Windows Sandbox — caught **five real defects in the
in-app updater that have been silently shipping in every desktop build
to date**:

1. Runtime image was missing `java.net.http` → wizard "Continue" crashed
   on first run with `NoClassDefFoundError`.
2. `UpdateChecker` received `"Version 1.0.6"` (with literal prefix) as
   `currentVersion` → version comparison always returned `UpToDate`.
   **The in-app updater has never worked in any installed build.**
3. `parseAppcast` enclosure-URL regex matched inconsistently → banner
   rendered without "Download & install" button when a real feed was
   served.
4. Settings → "Check for updates now" was fire-and-forget with zero UI
   feedback. Users had no way to know the check ran or what it found.
5. `UpdateInstallerHandoff` was equally silent. Click "Download &
   install" with a SHA mismatch / network failure / abuse-page
   substitution and nothing visible happens.

All five fixed, four verified working in Sandbox, one (Fix #5)
compiled-clean but its end-to-end UI not re-smoked because ngrok
rate-limited the second test cycle. The handoff *success* path
(download → SHA verify → installer launches → upgrade completes) was
**never proven end-to-end** today.

This matters for the rating because:

- The bugs existed because there was no automated test of the in-app
  updater. The repo had no `UpdateCheckerTest` or end-to-end smoke
  in CI. We caught these by hand because we tried.
- "B4 cleared" earlier today was based on **install + playback only**,
  not the full Case 8 from the smoke matrix. The matrix calls for
  *install + launch + playback + update handoff*, and we declared
  pass with the handoff sub-step deferred. That deferral hid the
  bugs. The lesson: deferring a sub-step is the same as not running
  the test.
- The previous assessment's "8.5/10 public desktop + Android beta"
  was overconfident. Real number is **8.0** — still GO for beta, but
  with explicit "the in-app updater has been fixed but a full
  end-to-end success-path smoke remains pending" caveat, not silent
  hand-waving.

Separately and quietly **good news**: the repo `server/` directory was
reconciled with deployed `/opt/torve-backend/` today (commit `5e6253a`
+ `1060658`). It is no longer a foot-gun. But the reconciliation
revealed the repo had been a parallel-universe fork — async stack vs
prod's sync stack, no Redis on prod, missing 22 migrations, 16+
prod-only files (Paddle billing, rebates, Resend, Sentry, Google Play
readiness, NZB-DAV). **The repo's existing test suite was passing
against an architecture that was never deployed.** Any prior
architecture confidence based on those tests was unearned.

The product sentence still works:

> Torve is the credential-first media hub that tells you what you can
> actually watch, picks the best legal source, plays it on the couch,
> and explains what broke when it cannot.

The job between now and stable hasn't changed: close the macOS gap,
make first-run enforce that sentence. But add: **wire the in-app
updater into CI** and **decide a sustainable repo-prod backend sync
process** so we don't keep paying this debt.

## Current Readiness

| Target                              | Old (this morning) | New (this evening) | Movement |
| ----------------------------------- | ----------------: | -----------------: | -------- |
| Closed enthusiast beta              | 8.5               | **8.5**            | unchanged — power users tolerate updater rough edges |
| Public desktop + Android beta       | 8.5               | **8.0**            | -0.5 — in-app updater was silently broken; fixed but partial verification |
| Public paid stable launch           | 6.5               | **6.0**            | -0.5 — same updater concern + parallel-fork test suite means prior CI confidence was overstated |
| iOS beta                            | 3.0               | **3.0**            | unchanged — still gated on Mac |

The 8.0 on beta is deliberate. The fixes are real and three of five
are verified directly. But "we discovered five bugs in a feature we
thought was clear" is itself a quality signal worth a half-point
haircut. Re-rate to 8.5 once the success-path smoke against a real
release feed actually completes.

## What changed since this morning

- **B4 smoke run for real.** Built N-1 (1.0.6) + N (1.0.7) MSIs,
  served via ngrok, walked through Sandbox install → wizard →
  banner → check. Caught five bugs (all fixed in commits `27db308`
  + `b5db606`). The first four verified working live; the fifth
  (handoff phase in banner) compiles clean and follows the same
  pattern as a verified fix but its UI was not re-smoked because
  ngrok abuse-limited the second cycle.
- **Server reconciliation completed.** Repo `server/` replaced with
  a sanitised snapshot of `/opt/torve-backend/` at alembic head
  0029. Stale `secret_wrap.py` swept. `server/DO_NOT_EDIT.md` added
  documenting the snapshot workflow ("edit on prod, snapshot back,
  never auto-deploy from repo"). The repo is no longer a trap, but
  it is also explicitly **not** a deployable surface.
- **Discovered**: the previous repo backend was structurally
  different from prod (async vs sync, Redis vs none, 22 migrations
  behind, ~16 prod files missing). The repo's `pytest`/`aiosqlite`
  test suite was passing against code that never shipped. Any prior
  "tests are green" claim about `server/` referred to a fictional
  app.
- **Released into commit `1060658`** on origin/master.

## What today did NOT prove

These remain residue, in priority order:

1. **In-app updater Download & install success path** — install N-1,
   click Download & install, watch real installer launch and upgrade
   to N without losing user session. Blocked today by ngrok abuse
   limits. Needs cloudflared / real GitHub release / a CDN to retry.
2. **B4 step "real release feed" pass** — same as #1 but against an
   actual production-grade HTTPS host with code-signed binaries, not
   a mock tunnel. Currently no such infrastructure exists.
3. **Snapshot-Hyper-V-VM smoke against Defender + third-party AV** —
   Sandbox catches "missing runtime" classes well, but not
   AV-quarantine, user-profile persistence, or mid-session Windows
   Update interactions.
4. **B2 / B3 (macOS/iOS)** — iOS simulator smoke + macOS notarize
   round-trip. Still gated on Mac availability.
5. **CI for the in-app updater** — there are zero tests against
   `UpdateChecker.parseAppcast` or `UpdateInstallerHandoff`.
   `parseAppcast` had a regex that worked for some attribute orders
   and not others; a 5-line unit test would have caught it.

## Competitive Reality

Stremio remains the most dangerous comparison. It advertises more
than 30 million users, broad platform support, login-based
cross-device continuity, and a simple addon mental model. Source:
<https://www.stremio.com/>

Syncler still owns Android debrid-user psychology — Android TV focus,
synced home, Trakt/Simkl, source filtering, autoplay — at ~$1.25/mo.
Sources: <https://syncler.net/>, <https://app.syncler.net/plus>

Plex Remote Watch Pass: $1.99 intro / $2.99 after June 1, 2026.
Jellyfin: free. Emby Premiere: $4.99/mo, $54/yr, $119 lifetime.
Infuse Pro: $1.99/mo, $16.99/yr, $99.99 lifetime. Channels: $8/mo or
$80/yr (best Live TV/DVR). Kodi: free, BYO content. Sources:
<https://www.plex.tv/plans/>, <https://jellyfin.org/>,
<https://emby.media/premiere.html>,
<https://apps.apple.com/us/app/infuse/id1136220934>,
<https://getchannels.com/get/>, <https://kodi.tv/about/>

## Feature Assessment

| Area | Current strength | Harsh assessment |
| --- | --- | --- |
| AI search | Source-aware availability across local, Plex/Jellyfin, debrid cache, addons, Usenet, IPTV live, watch history. Payload sanitization. | Still desktop-first and BYO-key. Consumer value needs built-in defaults or a clearly bundled AI tier. Android/iOS parity missing. |
| Debrid | Provider concepts, health checks, setup validation, source ranking, diagnostics. | High-value, high-support-cost niche. Failure copy must be excellent because every debrid outage becomes a Torve ticket. |
| Usenet / Panda / NZB | Panda health reads stable store, refreshes after save, participates in setup and source availability. | Technically strong, commercially fragile. Must remain wizard-led and hidden unless relevant. |
| IPTV | M3U/Xtream/EPG + one-off DVR + recording library + EPG correction. | Better, not Channels-class. Series Pass, fast zapping, logos/groups quality, remote-record polish remain gaps. |
| Plex/Jellyfin | Part of setup intents and availability graph. | Position as "unified availability layer," not "Plex replacement." |
| Downloads / LAN | Desktop publishes LAN library, backend registry, ExoPlayer header path, TV "Downloads on Desktop". | Real differentiator. Stable claim still wants two-device smoke + iOS LAN headers + stale-token retry. |
| Cross-device setup | Encrypted credential transfer across desktop, Android mobile, Android TV, iOS surfaces. | Safety story good. Live multi-device smoke matrix not run against fixed updater. |
| **Desktop player** | Runtime staging gates, packaging gates, **in-app updater fixed today (5 bugs)**, install + launch + playback verified in Sandbox 2026-05-03. | **Beta-ready with caveats.** Stable still wants: handoff success-path smoke, real release feed, snapshot-VM AV pass, CI tests for `UpdateChecker.parseAppcast` + `UpdateInstallerHandoff` so today's bugs cannot regress silently. |
| TV UX | Outcome rails, provider banner, On Now, Downloads on Desktop, source picker, LAN handoff, one-OK playback. | Biggest product jump. Real-device focus smoke + series source picker parity remain. |
| UI / Settings | Setup intents, provider-health rows, recovery actions, diagnostics, legal/support cards. | Still dense. Outcome-first or it feels like "settings with a player attached." |
| **Backend (server/)** | **Reconciled with prod 2026-05-03** at alembic 0029 (commit `5e6253a` + `1060658`). `server/DO_NOT_EDIT.md` documents snapshot workflow. | **No longer a trap, but no longer pretends to be deployable either.** Prod features (Paddle billing, rebates, Resend, Sentry, Google Play readiness, NZB-DAV) are now visible to dev tooling. Sustainable repo-prod sync (CI snapshot job? deploy from repo?) is an open process question, not a code problem. |
| Public release hardening | Account deletion/export, legal URLs, telemetry redaction, secret wrap, update handoff (now actually works), release docs. B1/B4/B5 cleared. | Public beta candidate. Stable still needs B2 + B3, success-path updater smoke, snapshot-VM AV pass, and CI for the updater. |

## What's left to clear stable

| ID  | Owner             | Why it's still hard |
| --- | ----------------- | ------------------- |
| B2  | Operator (macOS)  | iOS simulator smoke; needs Mac. |
| B3  | Operator (macOS)  | Sign + notarize round-trip; needs Mac. |
| —   | Test coverage     | Zero tests against `UpdateChecker.parseAppcast` and `UpdateInstallerHandoff`. The five bugs caught today would have been caught by ~30 lines of unit test. Wire it into CI. |
| —   | Release infra     | No production-grade HTTPS feed exists. Stable means "real GitHub release with code-signed binaries served from a CDN," not "ngrok mock." |
| —   | Product           | "Set up once, watch anything legal" pitch is not yet enforceable in the UI. Setup still surfaces too many source categories. |
| —   | Support           | Every debrid/NZB/IPTV outage = Torve ticket. Pricing must defend the support load. |
| —   | Backend process   | `server/` is now a passive snapshot. Decide: stays passive forever, or move to repo-canonical with CI deploys? |

## Honest residue you should not pretend is solved

1. **In-app updater success path remains theoretical.** Five bugs
   fixed, four verified, one compiled. The success path (real
   download → SHA verify → OS launches installer → upgrade) is still
   unproven end-to-end. ngrok blocked us; cloudflared / real release
   feed should unblock the next attempt.
2. **B4 was Sandbox, not snapshot Hyper-V VM.** AV interaction,
   user-profile persistence, mid-session Windows Update — none of
   that was exercised. Real snapshot-VM pass is still pre-stable.
3. **Repo's prior tests were lying.** The `server/tests/` suite that
   was "passing" before today was running against an async stack
   that never shipped. Don't trust archive `pytest` output from
   before commit `5e6253a` as evidence of anything about prod.
4. **iOS still 3/10.** Today moved nothing here. Don't let any other
   momentum mask that.
5. **No CI for the updater.** Today's bugs were all the kind that
   ~30 lines of unit test would have caught. Until that exists, any
   future updater regression will ship silently again.

## MRR At $1.99

(unchanged from prior — still illustrative, not realistic.)

| Paid users | Gross MRR |
| ---: | ---: |
| 1,000 | $1,990 |
| 5,000 | $9,950 |
| 10,000 | $19,900 |
| 50,000 | $99,500 |

Reality after store fees, VAT, refunds, payment failures, support,
backend, AI costs, signing, and operational overhead is materially
lower. Realistic year-1 net MRR for an indie launch into this niche
is $1,000–$4,000 (see `docs/realistic-mrr-projection.md` if it
exists, or recall from prior session).

Pricing posture: $4.99/mo standard. $1.99 only as intro/early-bird.
$79–99 capped lifetime. $9.99 family/power tier.

## User Base Potential

| Scenario | Plausible paid user range | Conditions |
| --- | ---: | --- |
| Desktop-only enthusiast beta | 500–5,000 | Debrid/Usenet/IPTV communities trust the app. |
| Desktop + Android (mobile + TV) public beta | 5,000–25,000 | Onboarding, playback, provider diagnostics, LAN reliable. |
| 50,000+ paid | Possible but difficult | TV-first polish, no runtime issues, legal-safe positioning, low support volume. |
| Mainstream app-store scale | Unlikely near term | Source-aggregation policy risk. |

## What Would Increase Value Most Now

1. **Wire `UpdateChecker.parseAppcast` and `UpdateInstallerHandoff`
   into CI.** ~30 lines of unit tests against the appcast XML
   shapes Torve will see. Nothing else in this list matters until
   today's bugs cannot regress silently.
2. Run macOS/iOS build and simulator smoke (closes B2 + B3).
3. Run the updater handoff success path against a real release feed
   (cloudflared or CDN-hosted MSI), not a mock tunnel.
4. Run Android TV real-device couch smoke.
5. Run live multi-device credential-transfer smoke.
6. Decide the long-term `server/` story (passive snapshot forever, or
   migrate to repo-canonical with CI deploy).
7. Reduce visible complexity: setup and Home outcome-first.
8. Improve support self-service: provider-health explanations,
   diagnostics export, IPTV failure copy.
9. Pricing by support economics, not competitor anchoring.

## Net Call

- **Public desktop + Android beta:** **GO** — but cut artifacts from
  `1060658` or later, not earlier (the updater bugs were live in any
  build before today). Publish, but ship a known-residue note: "Auto
  update is enabled; if a banner stalls, Settings → Updates → Check
  for updates now will surface the failure inline."
- **Public paid stable:** NO-GO. Wait on B2 + B3, real release feed,
  updater CI, snapshot-VM AV pass, and the success-path smoke.
- **iOS:** still gated on a Mac.

The previous assessment's "8.5/10 desktop+Android beta" was based on
incomplete evidence; the real number is **8.0** until the success
path is proven. Re-rate after that smoke completes.

- **8.5/10 closed enthusiast beta**
- **8.0/10 public desktop + Android beta**
- **6.0/10 stable paid consumer launch**
- **3.0/10 iOS until macOS verification**

The gap is still macOS host + product clarity. New gap added today:
**test discipline.** Anything that depends on auto-update,
auto-anything, or "we run the smoke before each cut" needs CI
backing, or the next silent-failure bug ships exactly the same way
this one did.
