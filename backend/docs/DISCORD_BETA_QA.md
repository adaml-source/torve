# Discord Beta QA

This document covers automated backend QA for the Discord beta application flow. The tests do not use production Discord tokens, production webhook URLs, production user accounts, or a real Discord browser session.

## Automated Coverage

Run:

```bash
venv/bin/pytest tests/test_discord_beta.py tests/test_discord_beta_e2e.py
```

The E2E harness signs real Discord-style interaction payloads with a generated test Ed25519 keypair and posts them to:

```http
POST /discord/interactions
```

It covers:

- Discord signature rejection and valid signed PING.
- Verified and unverified Torve email eligibility.
- Full Apply for Beta flow: apply button, devices, features, stability, final modal.
- DB-backed draft persistence across fresh backend DB sessions.
- Staff approval, rejection, duplicate submit/approve handling.
- `discord_beta` grant creation, campaign end cap, expiry cleanup, role add/remove attempts.
- Signup close and free-access end behavior.
- `/me/beta/status` and `/me/access-state` beta state.
- Paid premium remaining separate from beta grants.
- Controlled device/feature/stability validation.
- Stats output and sanitization checks.

## What Is Mocked

- Discord API calls are replaced with a fake service that captures staff review messages, role add/remove calls, and DMs.
- Discord role assignment and DM failures are simulated in-process.
- Test Discord public/private keys are generated during the test run.
- Torve users are test-only `@test.com` rows created by the harness.

## What Is Real

- FastAPI routing and dependency injection.
- Discord request signature verification.
- Interaction payload parsing and handlers.
- Database writes for drafts, link codes, applications, account links, and grants.
- Authenticated `/me/beta/*` and `/me/access-state` HTTP calls.
- CLI stats command path through `app.discord_beta.main(["stats"])`.

## Staging Smoke Test

Use a staging Discord app/server and staging Torve backend only.

1. Set staging-only Discord bot/public key/channel/role IDs.
2. Set `DISCORD_BETA_AUTO_APPROVE=false`.
3. Run `python -m app.discord_beta_admin publish-application-message`.
4. Create a staging Torve account and verify its email.
5. Generate a beta link code in the staging client.
6. Complete the Discord Apply for Beta flow.
7. Approve the staff review card.
8. Confirm `/me/access-state` shows `beta_access.active=true`.
9. Run `python -m app.discord_beta_admin expire-grants` against a seeded expired staging grant.

Never use production Discord secrets or production user accounts for staging smoke tests.

## Manual Verification Still Needed

The backend tests do not verify Discord's rendered UI. Manually verify in staging:

- The Apply button appears in the intended channel.
- Select menus and modal labels are readable on desktop and mobile Discord.
- Staff review cards are easy to scan.
- The Beta Tester role is visually assigned and removed by Discord.
- DM copy is acceptable when Discord allows delivery.
