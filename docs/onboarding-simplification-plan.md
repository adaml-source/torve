# Desktop Onboarding Simplification Plan

Status: **executed 2026-05-03 evening.** A + B + C + D + E all
landed. Residue list at the bottom captures what's still loose.

## Product critique we're trying to satisfy

> Setup still surfaces too many source categories. A first-time
> installer has to decide whether they care about debrid, NZB, IPTV,
> Plex, or a local library. The pitch should be **"enter your legal
> credentials once, Torve picks the best source, plays it on the couch,
> and explains what broke when it cannot."**

## Product decisions made (locks in the plan shape)

These came from the operator on 2026-05-03 in response to the
audit:

1. **Usenet is a power-user niche but is *required* for the Adult and
   Sports surfaces.** It can't be deleted from the product. But
   asking a first-time desktop user to configure NZB indexers
   themselves is wrong.
2. **Panda is the answer.** When a user sets up Panda, they have
   everything ready (debrid + NZB + Newznab indexers + provider
   handled in one flow). Make Panda the **primary recommended
   onboarding action**, not a hidden deep-link.
3. **Zero-source admission: YES.** Users who skip setup can enter
   the platform — they just can't stream from debrid/NZB sources
   until they configure something. Built-in addons + Plex/Jellyfin
   (when discovered) still work.
4. **Trakt is user preference, not required setup.** Move it out of
   onboarding entirely. Surface as an optional integration in
   Settings + a Home empty-state CTA.
5. **OAuth device-code flows on desktop must offer "Copy link".**
   Today the Trakt step shows a verification URL the user has to
   manually retype into a browser. Need a Copy button (and ideally
   "Open in browser") next to every device-code URL.

## Current shape (audit summary, unchanged)

- **Hub-first** (`DesktopSetupIntentHub.kt`): 4 source-category cards —
  Debrid, IPTV, Plex/Jellyfin, Usenet. User picks any subset, clicks
  "Continue to Torve" once one path is green.
- **Guided wizard** (legacy, behind "Use guided wizard instead"): 7
  linear steps — Welcome → Terms → Debrid → Trakt → Quality → Channels
  (IPTV) → Done.
- `DesktopShellAdmissionController` admits to V2App when
  `onboardingCompleted && (hasVodPlaybackPath || hasLivePlaybackPath)`.

## Three diagnoses (unchanged)

1. **Categories instead of credentials.** The hub surfaces *source
   types* as the unit of decision. The user has to know what
   category their thing is in.
2. **Wizard is a parallel reality.** It re-asks for terms, asks for
   Quality (defaults are fine), asks for Welcome (no decision).
   Toggling between hub and wizard creates inconsistent state.
3. **Two real dark patterns.** (a) Per-intent deep-links from the
   hub silently complete onboarding and lose any in-progress field
   state. (b) Terms acceptance is forever — versioned re-consent
   isn't possible without manually clearing the flag.

## Revised fix list

Sorted by execution-readiness. Items lower in the list are still
solid but depend on a decision above them.

---

### Fix A (revised) — Reshape onboarding to "Panda primary"

**Why:** User decision (above). Panda is a meta-setup that handles
debrid + NZB + indexers in one flow. Surfacing it as the primary
CTA replaces the four-card "pick a category" decision with a single
"set up everything" decision. Users who want category-by-category
control still have it post-onboarding in Settings → Integrations.

**Target shape:**

```
Auth (existing, unchanged)
  ↓
Terms (existing, simplified — see Fix C)
  ↓
Single onboarding screen:
  Headline:   "One last step — connect your sources"
  Subheading: "Panda configures your debrid + Usenet + indexers
              in one flow. Skip if you want to explore first or
              set things up manually."

  [ Set up with Panda ]              ← primary CTA, large
   small text below: "Recommended — handles debrid, Newznab,
                     and Usenet provider in one go"

  [ Skip for now ]                   ← secondary, smaller
   small text: "You can browse the app and use addons / Plex
               immediately. Add a streaming source later in
               Settings → Integrations."

  Optional, smaller still:
  [ Configure individual sources → Settings ]
```

**Scope:**
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/DesktopSetupIntentHub.kt` — replace the four-card grid with the two-CTA layout above. Drop the per-intent cards from the hub UI. The card composables can stay in the file (used by the post-onboarding Settings → Integrations surface).
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/DesktopOnboardingShell.kt` — remove the "Use guided wizard instead" button (lines `851-855`) and the entire `mode` toggle. Remove the `GUIDED` branch from the `when` (`881-1040`).
- Same file — delete the WELCOME / DEBRID / TRAKT / QUALITY / CHANNELS / DONE step composables (lines `1043-1335`). TERMS stays (see Fix C).
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/SetupWizardViewModel.kt` — remove `SetupStep.WELCOME`, `DEBRID`, `TRAKT`, `QUALITY`, `CHANNELS`, `DONE`. Keep `TERMS`. Remove `mode`-related state. Remove `pendingPostTermsJump` (no longer needed).
- `DesktopShellAdmissionController` — relax the admission condition. Today: `onboardingCompleted && (hasVodPlaybackPath || hasLivePlaybackPath)`. New: `onboardingCompleted` only. The "no playback path" state becomes an empty-state on Home, not an admission gate.

**Effort:** 1 day. Most of the work is deletion + the admission relaxation. The new two-CTA screen is small.

**Risk:** Low for the deletion. Moderate for the admission change — users who land on Home with no source need a decent empty state (see "Home empty-state" follow-up, below).

**Expected user-visible result:** First-run is auth → terms → one decision → Home. No four-card hub, no guided wizard. Power users still configure individual sources via Settings.

---

### Fix B (revised) — Home empty-state for zero-source users

**Why:** Fix A ships zero-source admission. Without a Home empty-state,
those users land on a blank Home with no obvious next step. The
empty-state is also where Trakt prompts live (Fix E).

**Scope:**
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/v2/home/V2HomePage.kt` — add an empty-state composable that renders when `!hasVodPlaybackPath && !hasLivePlaybackPath && noPlexJellyfinDiscovered`. Show:
  - A "Set up sources" CTA → opens Settings → Integrations or relaunches Panda setup
  - A "Sync your watchlist with Trakt" CTA (Fix E) → opens Settings → Integrations → Trakt
  - Optional: a "What can I do without setup?" tooltip explaining addons + Plex auto-discovery still work

**Effort:** Half a day.

**Risk:** Low.

**Expected user-visible result:** The "Skip for now" flow lands somewhere useful.

---

### Fix C — Versioned terms acceptance

**Why:** Today, ticking the terms box once is forever. If legal /
TMDB / Trakt disclosures change, returning users don't re-consent.
This is a real compliance gap.

**Scope:**
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/SetupWizardViewModel.kt` — change `KEY_TERMS_ACCEPTED` from boolean to `KEY_TERMS_ACCEPTED_VERSION` int. Add `CURRENT_TERMS_VERSION = 1` constant.
- `needsTermsAccepted()` returns `true` when persisted version `< CURRENT_TERMS_VERSION`.
- `setTermsAccepted()` writes `CURRENT_TERMS_VERSION`.
- Migration: existing `"true"` → version 1.
- Bump `CURRENT_TERMS_VERSION` whenever terms copy materially changes.

**Effort:** 1 hour.

**Risk:** Almost none.

**Expected user-visible result:** No change today. Future-proofed.

---

### Fix D — "Copy link" on OAuth device-code URLs

**Why:** Trakt and similar device-code OAuth flows show a verification
URL (`https://trakt.tv/activate`) and a code. On desktop, the user
has to alt-tab to a browser and retype. Add a Copy button (and
ideally Open-in-browser) so it's one click.

**Scope (Trakt is the immediate one, but applies anywhere we have a
device-code flow):**
- The Trakt step composable lives in `DesktopOnboardingShell.kt:1148-1194` (until Fix A deletes it). Then it moves to `Settings → Integrations → Trakt` per Fix E.
- Add two buttons next to the verification URL display: `[ Copy link ]` and `[ Open in browser ]`.
- Pattern is already in the codebase — `V2SettingsPage.kt:2457-2462` uses `java.awt.datatransfer.StringSelection` + `Toolkit.getDefaultToolkit().systemClipboard.setContents`. Reuse that.
- "Open in browser" uses `java.awt.Desktop.getDesktop().browse(java.net.URI(url))` — same pattern as the View Release button in `UpdateBanner`.
- Audit for other device-code flows: Plex (plex.tv/link), Simkl, possibly Panda. Apply the same buttons everywhere.

**Effort:** 1-2 hours.

**Risk:** None.

**Expected user-visible result:** OAuth flows feel like 2 clicks instead of "manually retype this URL".

---

### Fix E — Move Trakt out of onboarding entirely

**Why:** Trakt is a sync layer, not a source. Putting it in onboarding
gates first watch behind an OAuth flow some users don't need.

**Scope:**
- Subsumed by Fix A — the TRAKT step gets deleted along with the rest of the wizard.
- Add a Trakt prompt in the Home empty-state (Fix B) and in Settings → Integrations.
- The existing Trakt OAuth machinery in `SetupWizardViewModel` stays — just move the *UI* surface from onboarding to Settings.

**Effort:** Subsumed by Fix A + Fix B. Net cost: small.

**Risk:** Low.

**Expected user-visible result:** Trakt becomes optional polish, not a gate.

---

### Fix F — Block per-intent deep-links from silently completing onboarding

**Why:** Today's hub deep-links to Plex/Jellyfin and Usenet silently
mark onboarding complete and dump the user into Settings with no
breadcrumb back. After Fix A this dark pattern *largely goes away*
because the deep-links live in Settings, not onboarding. But if we
keep any in-onboarding deep-link (e.g., a "Set up Plex now" branch),
add a confirm dialog before leaving.

**Scope:** mostly resolved by Fix A's structural change. If any
deep-links survive, add a dialog at `DesktopSetupIntentHub.kt:756-766`.

**Effort:** 30 minutes if needed, possibly zero if Fix A makes it moot.

**Risk:** None.

---

## Execution status (2026-05-03)

All five fixes landed in one day. Commits:

  - `4e69a14` — Fix C (versioned terms) + Fix D (copy-link buttons)
  - `302fa79` — Fix A (Panda-primary onboarding) + Fix B (Home
    empty state) + Fix E (Trakt out of onboarding, subsumed by A)
  - (this commit) — V2App callbacks wired + DesktopSetupPane
    unused params removed + plan marked executed

Fix F (block per-intent deep-link state loss) was rendered moot by
A's structural change — there are no more in-onboarding deep-links
that can lose field state silently.

## Residue (what's still loose)

  1. **Legacy wizard step composables stay in `DesktopOnboardingShell.kt`
     as dead code** — `WelcomeStep`, `TermsStep`, `DebridStep`,
     `TraktStep` (with the Fix D copy-link buttons preserved),
     `QualityStep`, `ChannelsStep`, `DoneStep`, plus
     `DesktopSetupFlowCard` and `DesktopSetupFooter`. They're
     harmless (compiler warnings only) and `TraktStep` will be
     useful when Settings → Integrations grows a Trakt OAuth
     surface. Deletion is a low-risk follow-up.
  2. **`SetupStep` enum values + `SetupWizardViewModel` methods**
     for the deleted steps (DEBRID, TRAKT, QUALITY, CHANNELS, etc.)
     are still present in `shared/`. Not deleted because some
     methods (Trakt OAuth start, debrid client wiring) will be
     reused by Settings → Integrations once that surface picks
     them up. Same low-risk follow-up applies.
  3. **No Sandbox smoke** of the new flow yet. Compile-clean but
     untested end-to-end. Worth a manual smoke before the next
     desktop release.
  4. **V2App "Set up sources" CTA routes to Settings**, not to
     Panda directly. That's a placeholder — when V2App's settings
     shell grows section-deep links (`Integrations`, `Panda`,
     `Trakt`), the empty-state callbacks should be tightened to
     route to specific destinations instead of just toggling
     `settingsOpen = true`.
  5. **`DesktopSetupIntentHub`'s old per-intent helpers are gone**
     (`SetupIntentCard`, `ReadyToWatchBanner`, status badge
     mapping). If Settings → Integrations later wants the same
     visual cards, they can come back as a dedicated component
     file rather than living inside the onboarding hub.

## Out of scope

- The full **credential-wallet** architecture (one screen, all
  integration credentials auto-mapped to providers). Multi-week
  rewrite. Worth doing eventually.
- **First-watch onboarding** (post-config: "here's what you can
  watch right now"). Bigger product investment.
- **Mobile / Android TV onboarding parity.** Different code base.
  Whatever lands on desktop should inform a mobile audit, but
  separate engineering track.

## Estimated total

If C + D + (A+B+E) all ship: ~2-3 days of focused work.
F is contingency — likely zero.

## Related docs

- `docs/market-readiness-assessment.md` — drives the "set up once" pitch.
- `docs/release-hardening.md` — onboarding fixes don't gate any
  release blocker; they close the product clarity gap.
