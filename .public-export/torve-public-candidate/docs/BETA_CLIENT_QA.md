# Torve Beta Program Client QA

This document covers QA for the Discord Beta Program client flow after the free-software conversion.

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

## What Is Mocked

- Discord is never called.
- No bot token, webhook URL, staff review payload, or production backend is required.
- Shared API tests use mocked responses for `/me/beta/status` and `/me/beta/discord-link-code`.
- Client tests verify display and auth/account gates only.
- The client never grants beta, paid, premium, donor, or supporter access locally.

## Required Coverage

- Beta status DTO mapping for known and unknown statuses.
- Missing beta date fields and `/me/access-state.beta_access` handling.
- Active beta expiry parsing where beta program state is still surfaced.
- Beta state remains separate from product access.
- Friendly domain error mapping for email verification, signup closed, access ended, rate limiting, auth required, beta unavailable, and network failure.
- Generate-code actions are blocked when email is unverified, signup is closed, or beta access has ended.
- Copy and refresh actions work.
- Store-specific billing or entitlement mutation does not occur in beta state.

## Platform QA

Android mobile, Android TV, Fire TV, Desktop, and iOS should verify:

- beta entry visibility where applicable
- email verification gate
- generated code readability and copy/open behavior
- no premium, paid, donor, or supporter copy
- no store billing or entitlement mutation
- TV D-pad focus where applicable

## Staging Backend QA

Use staging accounts:

1. Signed out: Beta card prompts sign in.
2. Signed in, email unverified: generate code is blocked.
3. Signed in, verified: code generation succeeds if the backend allows tester applications.
4. Submitted status shows pending.
5. Approved tester status renders without changing product access.
6. Signup closed disables new applications when configured.
7. Access ended disables beta-only program actions when configured.

Product feature access must remain free/default for authenticated active accounts regardless of beta status.
