# TV Settings Focus Mutation Pattern

## Problem

On TV, a focused settings row can disappear during recomposition when the UI swaps state in place. The concrete failure here was the Account section changing from logged-out to logged-in after a successful login:

- `Log In` disappeared
- the row in the same visual slot became `Log Out`
- Compose invalidated the old focused node
- focus fell to the nav rail or another unrelated target

This is not specific to auth. Any settings card that changes identity or visibility while focused can trigger the same bug.

## Reusable rule

When a focused TV settings card mutates:

1. Keep focus inside settings content.
2. Prefer a stable logical slot over a brand-new target.
3. If the exact focused item disappears, restore to the nearest visible item in the same category.
4. Do not let rail focus become the terminal state during repair.
5. Avoid stacking a second manual retarget on top of mutation repair unless the replacement target is truly different and stable.

## Applied fix

The auth flow now uses a shared logical item and requester for the primary action row:

- logged-out state: `Log In`
- logged-in state: `Log Out`
- shared logical item id: `settings/account/auth_primary_action`
- shared `FocusRequester`: `authPrimaryActionRequester`

That lets the focus system treat the UI swap as one logical slot instead of two unrelated rows.

The mutation-repair pass then handles the case where the old focused node still gets invalidated during recomposition:

- capture the focused origin before the mutation
- when the node unregisters, mark it as `pendingFocusRepair`
- resolve repair candidates in the same settings category
- request focus on the exact item if it still exists, otherwise the next visible row, then the previous row, then the default category entry
- keep running the repair even if focus briefly fell to the rail

## What not to do

- Do not retarget login success to `auth_account` just because that card appeared. That target may not be registered yet.
- Do not retarget logout to `auth_email` if the primary action slot already has a stable replacement path.
- Do not bail out of mutation repair just because `isRailFocused == true`. That turns a temporary escape into a dead end.

## Log signatures

If this regresses, inspect `TvSettingsFocus` and `TvNavDebug`.

Typical bad sequence:

- `focus_invalidated ... item=settings/account/auth_primary_action`
- rail/home navigation wins before repair
- `auth_transition_restore_failed ... registered=false`

Healthy sequence:

- `focus_invalidated ...`
- `mutation_repair_begin ...`
- `restore_candidate ...` or `mutation_repair_success ...`
- focus lands on a visible settings item in the same category

## Relevant code

- `androidApp/src/tv/kotlin/com/torve/android/tv/screens/TvSettingsScreen.kt`
- `androidApp/src/tv/kotlin/com/torve/android/tv/focus/TvSettingsFocusStateMachine.kt`

Search terms:

- `authPrimaryActionRequester`
- `pendingFocusRepair`
- `resolveMutationRepairCandidates`
- `auth_transition_restore`
- `focus_invalidated`
