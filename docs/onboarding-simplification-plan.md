# Desktop Onboarding Simplification Plan

Status: scoped 2026-05-03, **execution pending**. This is a plan, not work
done. Each item below names files + lines, expected effort, and risk.
The intent is that the operator (or me, in a fresh session) picks 1-3 to
execute next without re-investigating.

## Product critique we're trying to satisfy

> Setup still surfaces too many source categories. A first-time
> installer has to decide whether they care about debrid, NZB, IPTV,
> Plex, or a local library. The pitch should be **"enter your legal
> credentials once, Torve picks the best source, plays it on the couch,
> and explains what broke when it cannot."**

## Current shape (audit summary)

The desktop first-run flow has two co-existing paths, both reachable from
`DesktopOnboardingShell`:

1. **Hub-first** (default): `DesktopSetupIntentHub` shows **4 source-category
   cards** — Debrid, IPTV, Plex/Jellyfin, Usenet — each with a "Set up"
   button. User can configure any subset and click "Continue to Torve" once
   one path is green.
2. **Guided wizard** (legacy, behind "Use guided wizard instead" button):
   7 linear steps: Welcome → Terms → Debrid → Trakt → Quality → Channels
   (IPTV) → Done.

`DesktopShellAdmissionController` admits the user to V2App when
`onboardingCompleted && (hasVodPlaybackPath || hasLivePlaybackPath)`.

## Three diagnoses

1. **Categories instead of credentials.** The hub surfaces *source types*
   (Debrid, IPTV, Plex, Usenet) as the unit of decision. The user has to
   know what category their thing is in before configuring. The pitch is
   credential-first, not category-first.
2. **Wizard is a parallel reality.** It re-asks for terms, asks for
   Quality (which has fine defaults), asks for Welcome (no decision).
   Users who toggle in/out of the wizard get inconsistent state.
3. **Two dark patterns.** (a) Per-intent deep-links from the hub
   silently complete onboarding and lose any in-progress field state.
   (b) Terms acceptance is persisted forever — versioned re-consent
   isn't possible without manually clearing the flag.

## Prioritised fix list

Sorted by leverage / cost ratio.

---

### Fix A — Drop Usenet from first-run, retire the guided wizard

**Why:** Usenet is the highest-friction, lowest-fit-with-pitch card.
It's already a deep-link to post-onboarding Panda setup, so removing
it from the hub doesn't lose any capability — power users find it in
Settings. The guided wizard duplicates the hub plus three steps that
add no value (Welcome, Quality, Done).

**Scope:**
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/DesktopSetupIntentHub.kt` — drop the Usenet card from the four-card layout.
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/SetupIntent.kt` — remove the `USENET` enum value (or mark deprecated; check that the DI module / coordinator handles its absence).
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/DesktopOnboardingShell.kt` — remove the "Use guided wizard instead" button (`851-855`), remove the `mode` toggle, remove the `GUIDED` branch from the `when` (`881-1040` area), delete the WELCOME / TERMS / DEBRID / TRAKT / QUALITY / CHANNELS / DONE step composables. Keep just the hub render path.
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/SetupWizardViewModel.kt` — remove `SetupStep.WELCOME`, `QUALITY`, `DONE`. Remove `mode`-related state. Probably also remove `pendingPostTermsJump` since terms-from-deep-link still works.
- Add a small "Advanced sources" link at the bottom of the hub that opens Settings → Integrations (Usenet lives there).

**Effort:** Half a day. Lots of mechanical deletions but the wizard touches a lot of state. Test risk is moderate — kill the wizard tests too.

**Risk:** Some users may have learned to rely on the wizard. Mitigation: keep the underlying step composables one commit, then delete after a release.

**Expected user-visible result:** Hub is the only flow. Three cards instead of four. No "guided wizard" toggle anywhere.

---

### Fix B — Re-frame the hub from "source categories" to "what do you already have?"

**Why:** The current hub asks "configure X". The pitch is "tell us what you
have, we'll figure out the rest." Re-copying the cards moves toward that
without yet doing the architectural credential-wallet rewrite.

**Scope:**
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/SetupIntent.kt` — rewrite the `title` / `subtitle` strings.
  - DEBRID: "Real-Debrid / AllDebrid / Premiumize / TorBox" → "I have a streaming subscription" / subtitle: "Real-Debrid, AllDebrid, Premiumize, or TorBox — paste your API key once."
  - IPTV: "M3U or Xtream credentials for live TV" → "I have an IPTV provider" / subtitle: "M3U URL or Xtream login. Live TV + EPG."
  - PLEX: "Plex or Jellyfin" → "I have a media server" / subtitle: "Plex or Jellyfin. We'll use it as a source when available."
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/DesktopSetupIntentHub.kt` — change the page title above the cards from "Set up Torve" to "What do you have?" Change the subtitle from a generic intro to "Each of these is optional. Add what you've got, leave the rest. You can always set up more later in Settings."
- Re-frame the "Continue to Torve" button copy when no source is configured: "Continue without a source" instead of the current "Set up at least one path" disabled state. Add explanatory text below: "Torve will use built-in addons + your Plex/Jellyfin server when you connect one. You can add a streaming source any time."
- This requires actually allowing zero-source admission. Today, `completeOnboarding()` requires `(hasVodPlaybackPath || hasLivePlaybackPath)`. **This is a real architectural decision** — see "Open product question" below.

**Effort:** 2 hours for copy + layout. The zero-source admission question is the gating decision. If we don't relax that, this fix becomes "copy-only" and doesn't ship the pitch.

**Risk:** Allowing zero-source admission creates a "you have an empty app" state. Mitigation: addons + watchlist still work; clear empty-state messaging on Home; follow-up "Add a source" CTA on Home.

**Expected user-visible result:** Hub feels like a wallet, not a wizard. User who already has just a Trakt account (no debrid, no IPTV) can still enter the app.

---

### Fix C — Versioned terms acceptance

**Why:** Today, ticking the terms box once is forever. If legal/TMDB/Trakt
disclosures change, returning users don't re-consent. This is a real
compliance gap, not just UX polish.

**Scope:**
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/SetupWizardViewModel.kt` — change `KEY_TERMS_ACCEPTED` (currently a boolean) to `KEY_TERMS_ACCEPTED_VERSION` (an int). Add a `CURRENT_TERMS_VERSION = 1` constant.
- `needsTermsAccepted()` returns `true` when persisted version `< CURRENT_TERMS_VERSION`.
- `setTermsAccepted()` writes `CURRENT_TERMS_VERSION` not `"true"`.
- Migration: existing `"true"` value → treat as version 1.
- When you ever change terms copy, bump `CURRENT_TERMS_VERSION` and the next launch surfaces the consent screen again.

**Effort:** 1 hour.

**Risk:** Almost none. Migration is straightforward.

**Expected user-visible result:** No change today. Future-proofed for legal updates.

---

### Fix D — Block per-intent deep-links from silently completing onboarding

**Why:** The audit found a real dark pattern: clicking "Set up Plex"
while mid-Debrid setup wipes the in-progress Debrid state with no
confirmation. The user lands in Settings → Integrations with no
breadcrumb back.

**Scope:**
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/DesktopSetupIntentHub.kt:756-766` — change `deepLinkAndComplete()` to check whether any field state is unsaved. If there is, show an "Are you sure? You'll lose unsaved changes" dialog before deep-linking.
- Better long-term: the hub should not complete onboarding on a deep-link at all. Plex/Jellyfin and Usenet should be configurable from inside onboarding (or after onboarding) without requiring "I'm done with onboarding" as a side effect.

**Effort:** 2-3 hours. The cleaner version (don't complete onboarding on deep-link) is more invasive because it requires the deep-link target to know how to return to onboarding.

**Risk:** If we just add a dialog, it's a 30-minute change with low risk. If we do the cleaner version, it touches the admission state machine.

**Expected user-visible result:** No silent state loss. Either confirm-before-leave dialog or stay-in-onboarding flow.

---

### Fix E — Move Trakt out of onboarding entirely

**Why:** Trakt is a sync/personalization layer, not a source. The pitch
is "watch anything legal" — Trakt doesn't help with watching. It helps
with discovery + watch tracking, both of which are post-watching
concerns. Putting it in onboarding gates a user's first watch behind a
Trakt OAuth flow they may not need.

**Scope:**
- `desktopApp/src/main/kotlin/com/torve/desktop/ui/onboarding/SetupWizardViewModel.kt` — remove `SetupStep.TRAKT`.
- `DesktopOnboardingShell.kt` — delete the TRAKT step composable (`1148-1194`).
- Add a Trakt prompt in Home → first-time empty state: "Sync your watchlist and progress" CTA → opens existing Trakt OAuth in Settings → Integrations.

**Effort:** 1-2 hours. Most of the work is wiring the Home empty-state CTA, which is also a Fix B follow-up (the empty-state needs to exist anyway if we allow zero-source admission).

**Risk:** Existing Trakt users who liked the current onboarding step will discover Trakt later. No functional loss; it's a discoverability question.

**Expected user-visible result:** One fewer step in any path. Trakt becomes optional polish, not a gate.

---

## Suggested execution order

1. **Fix A** first. It's the biggest visible simplification and unblocks B by removing the wizard's parallel-reality problem.
2. **Fix C**. 1 hour, zero risk, future-proofs legal.
3. **Fix D (dialog version)**. 30 minutes, closes a real dark-pattern gap.
4. **Open product decision: zero-source admission?** This is the gate for Fix B's full scope. Two paths:
   - **Yes, allow zero source.** Fix B can fully ship. User can enter Torve with just an account.
   - **No, require ≥1 source.** Fix B becomes copy-only. Useful but smaller.
5. **Fix B** (depending on the decision in step 4).
6. **Fix E** if/when product team agrees Trakt-on-Home is preferable to Trakt-in-onboarding.

A and C and D are safe to do in any order without further product input.
B and E need a product call first.

## Out of scope for this plan

- The full **credential-wallet** architecture (one screen, all integration
  credentials, app figures out sources). That's a multi-week rework
  touching `SetupIntent`, `IntegrationStorage`, `PreferencesRepository`,
  every router that consumes credentials. Worth doing eventually but
  doesn't fit here.
- **First-watch onboarding** (post-source-config: "here's what you can
  watch right now"). Would dramatically improve the "watch anything
  legal" pitch but requires real source-resolution work in V2Home.
- **Mobile / Android / Android TV onboarding parity.** Different code
  base, different shell. Whatever lands here should inform a similar
  audit for mobile, but they're separate engineering tracks.

## Estimated total

If A + C + D + B(copy-only) + E ship: ~2-3 days of focused work.
If we also do B(full) with the zero-source admission decision: +1 day for
admission state + Home empty state.

Compare to "do nothing": product critique persists; first-run keeps
forcing source-category decisions.

## Related docs

- `docs/market-readiness-assessment.md` — drives the "set up once" pitch.
- `docs/release-hardening.md` — onboarding fixes don't gate any release blocker, they just close the product clarity gap.
