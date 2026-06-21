# Torve Backend

This checkout, `/opt/torve-backend`, is the canonical Torve backend source in
this environment and the backend tree to use for a future sanitized public
source export. Do not treat `server/app` as canonical unless a synced
`server/app` tree is intentionally created and verified from this checkout.

For a combined public Torve repository, include this backend as `backend/` or
another deliberate backend directory, and keep the public export free of local
runtime state, secrets, build outputs, and generated artifacts.

## License

This backend source is licensed as `AGPL-3.0-or-later`. See `LICENSE` in this
directory. In the combined public source export, the official GNU AGPL v3 text
must be present in the repository's top-level `LICENSE` file.

No noncommercial, no-modification, field-of-use, or paid-feature restrictions
are added by this backend.

## Access Model

Torve backend product access is free/default for active authenticated accounts.
The current access resolver returns the `free` access tier and treats legacy
premium booleans as compatibility fields for older clients.

The following records and integrations do not unlock product features, remove
product features, or decide whether an account may use the application:

- subscriptions
- purchases and restore flows
- historical entitlements and lifetime ledgers
- rebate or promo codes
- Stripe checkout, portal, webhooks, refunds, and reconciliation data
- Paddle webhooks and payment metadata
- Google Play verification and voided-purchase reconciliation
- Amazon verification metadata
- Apple or StoreKit receipt state from clients
- donations, donor status, supporter status, or funding status

Those systems may still exist for historical records, refund handling,
reconciliation, compatibility with old clients, support workflows, or tests
that prove they are non-gating.

Remaining limits and checks are for auth, ownership, privacy, device security,
anti-abuse, sync integrity, or technical stability. They must not be changed
into paid access gates.

## Stack

- FastAPI + SQLAlchemy + psycopg2-binary
- Postgres
- Alembic migrations in `alembic/versions/`
- Sentry, Resend, Discord, release metadata, and historical billing/support
  integrations
- Pytest test suite in `tests/`

## Layout

```text
.
|-- app/
|   |-- main.py
|   |-- models.py
|   |-- schemas.py
|   |-- billing.py
|   |-- deps.py
|   |-- routers/
|   `-- nzbdav/
|-- alembic/
|   |-- env.py
|   `-- versions/
|-- docs/
|-- scripts/
|-- tests/
|-- .env.example
|-- alembic.ini
|-- requirements.txt
`-- torve-backend.service
```

## Local Development

```bash
cd /opt/torve-backend
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt

# Copy .env.example to .env and fill in local-only values.
# Never commit .env or secret-bearing backups.
cp .env.example .env

alembic upgrade head
uvicorn app.main:app --reload --port 8000
pytest
```

## Secrets

Secrets belong in `.env` or another deployment secret store, not in git.
Examples include database URLs, JWT secrets, integration encryption keys,
webhook URLs, provider API keys, service-account JSON, signing material, and
admin secrets.

The tracked `.env.example` file must contain placeholders only. Legacy billing
and store-provider variables in that file are for historical, refund,
reconciliation, compatibility, readiness, or test-only workflows. They are not
required for product access.

## Public Export Exclusions

A sanitized public source export must exclude:

- `.git/`
- `.env`
- `.env.*`
- `venv/`
- `__pycache__/`
- `.pytest_cache/`
- build outputs and generated packages
- logs, dumps, and backups
- generated release artifacts such as `*.zip`, `*.apk`, `*.aab`, and `*.msi`
- service-account files, signing keys, certificates, provisioning profiles,
  and other local credentials

If this backend is copied into a combined public repository, copy source,
tests, migrations, docs, scripts, examples, and license notices only.

## Checks

Run the backend test suite before export or deployment:

```bash
/opt/torve-backend/venv/bin/python -m pytest -v --tb=short
```

Useful export-safety checks:

```bash
git diff --check
git grep -n "Depends(require_premium" -- app tests
git grep -n "premium_required" -- app tests
git grep -n "premium_lifetime\|premium_subscription" -- app tests
```

## Deployment Notes

`scripts/deploy.sh` is an on-host helper for this checkout. It installs
dependencies, runs Alembic migrations, restarts `torve-backend`, and performs a
local health check when invoked by an operator with the required permissions.

Deployment secrets remain outside git in `/opt/torve-backend/.env` or the
deployment secret manager. Public contributors should be able to run normal
tests with placeholder values and without production credentials.
