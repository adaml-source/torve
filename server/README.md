# Torve Backend (`server/`)

This directory is the **canonical source** of the Torve backend. Edits
land here, get reviewed via PR, then ship to prod via
`scripts/deploy-backend.sh` (run from the repo root). Direct edits to
`/opt/torve-backend` on the VPS are no longer the workflow — they
should only happen for emergency hotfixes, and any such hotfix must
be backported into this directory the same day.

This is **Option B** from `docs/server-sync-strategy.md`, picked on
2026-05-03.

## Stack

- FastAPI + SQLAlchemy (sync) + psycopg2-binary
- Postgres (owner `torve_user`, db `torve` on prod)
- Alembic for migrations (current head: see `alembic/versions/`)
- Sentry, Paddle (billing), Resend (email), Google Play (verification)
- Tests: pytest (sync)

## Layout

```
server/
├── app/
│   ├── main.py                    # FastAPI app factory + middleware
│   ├── models.py                  # SQLAlchemy models
│   ├── schemas.py                 # Pydantic request/response shapes
│   ├── crypto.py                  # Secret wrap/unwrap (INTEGRATION_SECRET_KEY)
│   ├── billing.py                 # Paddle webhook + entitlement reconciliation
│   ├── routers/                   # 29 route modules (auth, account, devices, ...)
│   └── nzbdav/                    # NZB-DAV integration internals
├── alembic/
│   ├── versions/                  # Migrations 0001..NNNN
│   └── env.py
├── scripts/
│   └── deploy.sh                  # On-VPS helper — runs deps + migrate + restart
├── tests/                         # pytest suite (sync)
├── requirements.txt
├── alembic.ini
└── Dockerfile
```

## Local development

```bash
cd server
python -m venv venv
source venv/bin/activate          # Windows: venv\Scripts\activate
pip install -r requirements.txt

# Copy .env.example -> .env and fill in real values
cp .env.example .env

# Run migrations against your local Postgres
alembic upgrade head

# Run the API locally
uvicorn app.main:app --reload --port 8000

# Run tests
pytest
```

## Deploy to prod

The deploy script lives at the **repo root**, not in `server/`:

```bash
# Dry run first (always, on first attempt of the day):
./scripts/deploy-backend.sh

# Then apply:
./scripts/deploy-backend.sh apply           # full: rsync + deps + migrate + restart
./scripts/deploy-backend.sh apply migrate   # rsync + alembic upgrade head only
./scripts/deploy-backend.sh apply restart   # rsync + restart + verify only
```

Required env (set in your shell before running):

- `TORVE_SSH_TARGET` — defaults to `torve@torve.app`. Override if your
  SSH config uses a different host alias.
- `TORVE_REQUIRE_CLEAN=1` — refuses to deploy when `server/` has
  uncommitted changes. Recommended for shared / production-grade
  deploys; optional for solo testing.

The deploy script:

1. `rsync`s `server/` → `/opt/torve-backend/` on the VPS, with
   `--delete` so prod matches repo exactly.
2. `--exclude=.env` and `--exclude=venv` so VPS-only files are
   preserved.
3. SSHs in and runs `sudo /opt/torve-backend/scripts/deploy.sh
   <mode>` which handles ownership check + pip install +
   `alembic upgrade head` + `systemctl restart torve-backend` +
   `/health` curl check.

## CI

`.github/workflows/backend-ci.yml` runs on PRs that touch `server/`:

- Installs `requirements.txt` (catches dep-resolution issues).
- Walks `app/` and imports every module (catches import-time errors,
  including the missing-runtime-module class of bug B4 caught for
  desktop).
- Spins up a Postgres service container, runs `alembic upgrade head`
  against it (catches migration drift).
- Runs `pytest` against that database.

Required PR-side secrets: none — CI uses dummy values for
`JWT_SECRET`, `INTEGRATION_SECRET_KEY`, etc. Real secrets stay on the
VPS in `/opt/torve-backend/.env`.

## Hotfix-on-prod escape hatch

If something is on fire and the deploy script is in the way (CI
broken, GitHub down, whatever), you can still SSH to the VPS and edit
`/opt/torve-backend/` directly. **If you do, immediately**:

1. SCP the changed files back to your local clone.
2. Commit them to a `hotfix/` branch.
3. Open a PR for review.
4. Merge to master.

Otherwise the next `deploy-backend.sh apply` will overwrite your
hotfix with whatever the repo says — `--delete` doesn't ask.

## History

- **2026-05-03 (afternoon)** — workflow switched from Option A
  (passive snapshot) to Option B (repo-canonical with manual deploy).
  Added `scripts/deploy-backend.sh`, `.github/workflows/backend-ci.yml`,
  and this README. See `docs/server-sync-strategy.md`.
- **2026-05-03 (morning)** — repo `server/` reconciled with prod
  snapshot at alembic head 0029 (commits `5e6253a` + `1060658`).
  Removed `DO_NOT_EDIT.md` since edits in this directory are now
  the workflow.
- Earlier history: this directory used to be a parallel-universe
  fork of the backend (async stack, Redis, partial schema). Anything
  in git log before `5e6253a` referring to `server/` is referring
  to that defunct fork.
