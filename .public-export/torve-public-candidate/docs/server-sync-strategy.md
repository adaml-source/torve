# `server/` Sync Strategy — Option B picked 2026-05-03

Status: **decided 2026-05-03 — Option B (repo-canonical with manual
deploy)**. See "Decision" section at bottom for what was wired up.

Historical backend synchronization document. References to Paddle billing, rebates, Google Play readiness, entitlements, or device governance describe the pre-conversion backend snapshot and are not current product-access policy.

Current canonical positioning: Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

Created 2026-05-03 after the prod-snapshot reconciliation landed. This
doc lays out the three sustainable options for keeping the repo
`server/` directory in sync with deployed `/opt/torve-backend/`, with
cost / risk / when-to-pick for each.

## Background

Before today, the repo `server/` and prod `/opt/torve-backend/` had
diverged into two structurally different applications:

- Repo: async stack (`SQLAlchemy[asyncio]` + `asyncpg` + `redis`),
  ~13 source files, 7 alembic migrations, 0 features beyond auth +
  entitlements + device governance.
- Prod: sync stack (`sqlalchemy` + `psycopg2`), ~29 source files +
  `routers/` + `nzbdav/`, 29 alembic migrations, full Paddle billing
  + rebates + Resend email + Sentry + Google Play readiness.

The repo's `pytest`/`aiosqlite` test suite was passing against an
architecture that had never shipped to prod. The reconciliation
(commits `5e6253a` + `1060658`) replaced repo `server/` with a
sanitised snapshot of prod at alembic head **0029**, and added
`server/DO_NOT_EDIT.md` declaring the snapshot read-only.

That solved the immediate trap. It does not solve the ongoing
question: how do we keep them in sync from now on?

## Option A — Passive snapshot (current state)

**Mechanism**: edit on prod (SSH to VPS, vim, restart service).
Periodically run a tarball-snapshot script from prod, extract into
repo `server/`, commit. The repo is **read-only documentation**, never
deployed.

**Cost**: very low ongoing. ~5 min per snapshot, run when something
notable changes (new migration, new router, new env var).

**Risk**:
- Repo drifts between snapshots. If "periodically" slips to
  "monthly", we're back to today's situation in a few sprints.
- No CI on backend code. Bugs ship to prod without lint, type-check,
  or test runs unless the operator runs them on the VPS.
- New developers see `server/` and may think it's deployable.
  `DO_NOT_EDIT.md` mitigates but doesn't prevent.

**When to pick**: solo-operator regime where prod is small,
deployment cadence is "edit + restart", and you want zero
infrastructure overhead.

**Discipline required**: a recurring calendar reminder (or a
post-prod-edit script) to snapshot back. Without that, this option
quietly becomes "no sync at all".

## Option B — Repo-canonical with manual deploy

**Mechanism**: repo becomes the source of truth. All edits land in
the repo first, get reviewed (PR), then a deploy script `rsync`s the
repo `server/` onto `/opt/torve-backend/` and restarts the service.
No more in-place editing on the VPS.

**Cost**: ~3-4 hours one-time setup:
- Write a deploy script (`scripts/deploy-backend.sh`) that does
  `rsync -av --exclude=venv --exclude=.env server/ vps:/opt/torve-backend/`,
  followed by `ssh vps 'cd /opt/torve-backend && venv/bin/pip install
  -r requirements.txt && systemctl restart torve-backend'`.
- Decide migration policy: deploy script can run `alembic upgrade
  head` automatically, or that stays a manual step.
- Add CI to run `pytest server/tests/` + `alembic check` on PRs.
- Document the new workflow in `server/README.md` (replaces
  `DO_NOT_EDIT.md`).

**Cost ongoing**: edit-flow is slightly slower (PR cycle vs vim) but
adds review, CI, and rollback. Prod-edit becomes a code smell.

**Risk**:
- First deploy from repo can break prod if the snapshot drifted from
  prod since the reconciliation. Mitigation: do a fresh snapshot
  (Option A) the day before the switch; then deploy from repo and
  confirm prod still works.
- If `.env` lives only on prod, the deploy script must `--exclude=.env`
  to avoid clobbering. Currently true.
- Migration ordering: if someone has run a manual SQL migration on
  prod that's not in `alembic/versions/`, alembic will conflict.
  Migrate-or-handle plan needed before first deploy.

**When to pick**: team grows to 2+ people, deployment cadence picks
up, code review becomes valuable, `pytest` running in CI is
worthwhile.

## Option C — Hybrid (snapshot today, repo-canonical later)

**Mechanism**: stay on Option A for now, but commit to migrating to
Option B before the next significant feature lands on the backend
(billing changes, new router, schema migration). Track the migration
date in the repo.

**Cost**: same as Option A today + Option B's setup cost when you
flip.

**Risk**: deferring the switch indefinitely is the same as never
doing it. Make the trigger explicit: "next time we touch billing.py"
or "by end of Q3".

**When to pick**: you agree B is right long-term but don't have time
this week.

## Recommendation

Superseded by the decision below. The initial recommendation was to
choose Option A for lowest operational overhead unless snapshot
discipline slipped. The repository now implements Option B instead:
repo-canonical backend source, manual deploy script, backend CI, and a
new `server/README.md`.

The remaining hard requirement is operational proof: run the deploy
script in dry-run mode first, confirm the diff is exactly expected,
then perform the first live deploy in a separate controlled session.

## Decision

- [x] **Option B — Repo-canonical with manual deploy.** Wired up
      2026-05-03 (same day as the strategy doc). The repo `server/`
      directory is now the source of truth; prod-side edits become
      hotfix-only and get backported same-day per `server/README.md`.

What was added to make Option B real:

- `scripts/deploy-backend.sh` — client-side deploy helper. Defaults
  to `--dry-run`; `apply` triggers rsync + on-VPS deploy.
- `.github/workflows/backend-ci.yml` — runs on every PR touching
  `server/`. Imports every `app/` module, applies migrations
  against a clean Postgres, runs `pytest`.
- `server/README.md` — replaces the old phase-2/3-era README (and
  the briefly-living `DO_NOT_EDIT.md`). Documents the new flow,
  hotfix escape hatch, and CI shape.

What stays the same:

- `.env` lives only on the VPS (`--exclude=.env` in rsync).
- `venv/` lives only on the VPS (`--exclude=venv`).
- `server/scripts/deploy.sh` (the on-VPS helper) is unchanged —
  the new client-side script just SSHs in and runs it.

First real deploy (separate session, not done in this commit): run
`./scripts/deploy-backend.sh` in dry-run mode first to confirm zero
diff vs prod (because prod was just snapshotted into repo this
morning). Then `./scripts/deploy-backend.sh apply` for the live
switch.

## Related

- `server/README.md` — current workflow + deploy + CI documentation.
- Reconciliation commits: `5e6253a` (initial snapshot), `1060658`
  (stale `secret_wrap.py` cleanup).
- Snapshot anchor: alembic head **0029** as of 2026-05-03.
