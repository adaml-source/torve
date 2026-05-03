# server/ — prod snapshot (read-only reference)

**This directory is a sanitised snapshot of the live backend. Do not edit here
and expect it to reach production.**

## The real backend

- Repo: `github.com/adaml-source/torve-backend` (separate git history)
- Running: `/opt/torve-backend` on the VPS, systemd unit `torve-backend.service`
- DB: Postgres, owner `torve_user`, database `torve`
- Alembic head at time of snapshot: **0029** (2026-05-03)
- Stack: FastAPI + SQLAlchemy (sync) + psycopg2-binary + Sentry + Paddle + Resend + Google Play

## What's in this directory

A sanitised snapshot of `/opt/torve-backend` taken on 2026-05-03 (commit `5e6253a`).
It matches prod exactly: same file tree, same migrations, same requirements.txt.

Excluded from the snapshot (never committed here):
- `venv/` — use `python -m venv venv && pip install -r requirements.txt` locally
- `.env` — see `.env.example` for all required key names
- `__pycache__/`, `*.pyc`

## Workflow

1. **Edit on prod.** SSH to the VPS, edit `/opt/torve-backend/`, restart the service.
2. **Snapshot back periodically.** Run the tarball one-liner, extract into `server/`,
   commit. This directory is visibility-only — it is never deployed from.
3. **Do NOT auto-deploy from this repo.** Prod is ahead; auto-deploy would
   overwrite live billing/schema state.

Snapshot command (run on VPS, then extract into `server/`):
```bash
tar -czf /tmp/torve-backend-snapshot.tar.gz \
    --exclude=venv --exclude=.git --exclude=.env \
    --exclude='__pycache__' --exclude='*.pyc' \
    -C /opt/torve-backend \
    app alembic alembic.ini requirements.txt tests scripts docs BILLING_OPS.md
```

## If you're an AI agent reading this

Before making any change under `server/`, verify:

1. Is the change meant to affect production? If yes, edit `/opt/torve-backend/`
   on the VPS directly — changes here will never reach prod.
2. If the change is client-server coupled (new Android call + new endpoint),
   the server side goes in `github.com/adaml-source/torve-backend`, not here.
3. If the user asks you to "add a backend endpoint" or "fix a backend bug,"
   default assumption: they mean prod (`/opt/torve-backend`).
4. `secret_wrap.py` does not exist on prod. The encryption module is `crypto.py`
   (`encrypt_secret` / `decrypt_secret`), used by integrations, playlists,
   pairing_signin, and nzbdav/account_store.

## Snapshot history

| Date       | Alembic head | Commit    | Notes                                      |
|------------|--------------|-----------|--------------------------------------------|
| 2026-05-03 | 0029         | `5e6253a` | Initial reconciliation — replaced diverged placeholder |
