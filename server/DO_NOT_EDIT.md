# This directory is NOT the Torve backend.

**Production runs a different repo.** Stop before editing anything here.

## The real backend

- Lives at `github.com/adaml-source/torve-backend`
- Deployed to `/opt/torve-backend` on the VPS, run by systemd
- Has its own git history (first commit `273a00f`, unrelated to this monorepo)
- Has 28 routers in `app/routers/`, multi-module structure
- Uses the `/me/...` URL prefix for authenticated endpoints
  (e.g. `/me/purchases/google-play/verify`, not `/purchases/google/verify`)
- Uses env vars prefixed `GOOGLE_PLAY_*`, `APPLE_*`, etc. — product IDs
  are env-configurable, service-account credentials are file paths.

## What's in this directory

A single-commit drop-in that shipped in commit `936a438` ("Add Panda guided
setup entry points") as part of the Android monorepo. **It is not deployed
anywhere.** Every endpoint here — including everything under `app/main.py`
and any router in `app/routers/` — is parallel to prod, not pulled from
prod, and silently diverged in ways that make copy-paste dangerous:

- Flat route structure (all handlers in `main.py`) vs prod's per-concern
  routers
- URL prefixes: `/purchases/google/verify` here vs `/me/purchases/google-play/verify`
  on prod
- Env var names: `GOOGLE_SERVICE_ACCOUNT_JSON` (JSON content) here vs
  `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (file path) on prod
- Product-ID handling: hardcoded dict in `store_verification.py:20-25`
  here vs `GOOGLE_PLAY_LIFETIME_PRODUCT_ID` / `GOOGLE_PLAY_SUBSCRIPTION_ID`
  env vars on prod
- Verification fallback: this directory returns `valid=True` on trust when
  the service account is unconfigured; prod returns
  `{verified: false, "Product not recognized"}` and refuses to verify

## Port status (as of 2026-04-23)

Two modules from this directory have been ported to
`github.com/adaml-source/torve-backend`. Everything else is classified as
die-with-directory — don't resurrect it.

Ported to prod:
- `watch_state` endpoints (F3 cross-device resume) → prod commit `d33d294`.
  The Android client at `TorveSyncApiClient.kt` posts to
  `/me/watch_state/report` and gets from `/me/watch_state/latest` on prod.
- `account_settings_policy` (secret-bearing-key scrubber) → prod commit
  `9b833c6`. Backfill against prod caught a real leak during deploy.

Die-with-directory (intentionally not ported):
- `store_verification.py` — dev-mode-trust-on-empty-env-var is a footgun;
  prod's fail-closed `purchase_verify.py` is safer.
- `entitlements.py` — coupled to the dead `store_verification`; prod's
  Paddle-webhook-based pipeline is the shipping path.
- `realtime.py` — in-memory registry doesn't survive worker restart;
  prod's Postgres LISTEN/NOTIFY is multi-worker-safe.
- `db.py` — async SQLAlchemy session, while prod is sync throughout.
  Porting would invert prod's stack.

Lower-priority-but-valid port candidate (not done yet):
- A ~80-line subset of `device_policy.py` — specifically the swap-rate
  check in `count_recent_swaps`, which would strengthen prod's device-cap
  enforcement against churn-based bypass. Worth doing if the devices
  table shows evidence of rapid-churn patterns; hardening otherwise.

## If you're an AI agent reading this

Before making any change under `server/`, verify:

1. Is the change meant to affect production? If yes, this is the wrong repo.
   Your edits here will never reach prod.
2. If the change is client-server coupled (e.g. a new Android call that
   expects a new server endpoint), the server side goes in
   `github.com/adaml-source/torve-backend`, not here.
3. If the user asks you to "add a backend endpoint" or "fix a backend
   bug," confirm which repo they mean. Default assumption: prod.

## If you're a human reading this

Either delete this directory or replace its contents with a symlink once
you've confirmed nothing local depends on the code here. The tests under
`server/tests/` may still run as an isolated suite, but they test code
paths that don't exist in production, so their signal is limited.
