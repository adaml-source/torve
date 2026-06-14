# Torve Beta Program Client QA

This document covers automated QA for the Discord Beta Program client flow.

## What Is Mocked

- Discord is never called.
- No bot token, webhook URL, staff review payload, or production backend is required.
- Shared API tests use Ktor `MockEngine` responses for `/me/beta/status` and `/me/beta/discord-link-code`.
- Shared ViewModel tests use fake repositories, fake auth state, and test dispatchers.
- Client tests verify display and gating only. The client never grants beta access locally.

## Shared KMP Coverage

Automated tests cover:

- `DiscordBetaStatusDto` mapping for all known application statuses, beta grant statuses, and blocked reasons.
- Unknown enum values mapping to `UNKNOWN`.
- Missing `beta_signup_close_at`, `beta_free_access_end_at`, and `/me/access-state.beta_access` handling.
- Active `beta_access` expiry parsing.
- Paid premium access fields staying separate from temporary beta access.
- Mocked `GET /me/beta/status` success.
- Mocked `POST /me/beta/discord-link-code` success.
- Friendly domain error mapping for `email_not_verified`, `beta_signup_closed`, `beta_access_ended`, `rate_limited`, `auth_required`, `beta_unavailable`, and network failure.
- ViewModel states for signed out, email unverified, eligible, code generated, pending, active, rejected, expired, signup closed, access ended, premium copy, and network error.
- Generate-code actions are blocked when email is unverified, signup is closed, or beta access has ended.
- Copy action sets `copySuccess`.
- Refresh action reloads status.
- Signup entry visibility before and after the July 1, 2026 cutoff.
- Displayed beta expiry is capped at July 31, 2026.
- Required beta safety and deadline copy.

## Android Mobile Coverage

Current automated coverage for Android mobile is compile-level plus shared ViewModel coverage.

Covered indirectly:

- Settings card visibility logic.
- Email verification gate.
- Generate/copy/open Discord state.
- Friendly error strings.
- Signup close and access-ended gates.

Manual or future Compose UI coverage should verify:

- Settings root displays `Torve Beta Program`.
- Eligible users see `Generate Discord Link Code`.
- Generated code appears and can be copied.
- Open Discord action appears only when a URL is configured.
- Raw backend detail text is not displayed.

## Android TV / Fire TV Coverage

Current automated coverage for TV / Fire TV is compile-level plus shared ViewModel coverage.

Covered indirectly:

- TV beta state text, including the TV-specific unverified-email instruction.
- Settings entry visibility logic.
- Generate-code state and signup gates.
- No store-specific entitlement mutation in beta state.

Manual or future TV Compose UI coverage should verify:

- TV Settings can focus the Beta Program entry.
- Pressing OK opens the beta screen.
- Primary actions are reachable by D-pad.
- Generated code is readable on TV.
- Fire TV copy does not mention Google Play.
- Focus does not trap or skip primary beta actions.

## Desktop Coverage

Current automated coverage for desktop is compile-level plus shared ViewModel coverage.

Covered indirectly:

- Beta Program card state.
- Copy-code state.
- Refresh state.
- Active expiry display.
- Signup closed and ended states.

Manual or future desktop UI coverage should verify:

- Settings and Account surfaces show the Beta Program card before signup close.
- Copy Code copies the generated value.
- Open Discord opens the configured URL.
- Active, pending, rejected, expired, signup closed, and ended states render as expected.

## Staging Backend QA

Use staging accounts for these checks:

1. Signed out: Beta card prompts sign in.
2. Signed in, email unverified: generate code is blocked.
3. Signed in, verified, non-premium: code generation succeeds.
4. Signed in, verified, paid premium: code generation succeeds if backend allows tester applications.
5. Backend `already_active` response for premium users maps to the premium tester unavailable copy.
6. Submitted status shows pending.
7. Approved non-premium tester receives capped `discord_beta` access no later than July 31, 2026.
8. Paid premium remains premium after beta expiry.
9. Signup closed disables new applications after July 1, 2026.
10. Access ended disables applications after July 31, 2026.

## Manual Visual QA Still Required

- Android mobile copy/layout/accessibility labels.
- TV and Fire TV D-pad traversal.
- TV code readability from couch distance.
- Desktop copy/open-url behavior.
- Real staging backend status transitions.
