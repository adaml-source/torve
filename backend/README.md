# Torve Backend

The public backend source lives in `backend/` inside the Torve repository.

This backend provides authentication, account-scoped sync services, device registration, account lifecycle endpoints, diagnostics, release metadata, provider integration compatibility, and historical billing/entitlement compatibility code.

## License

This backend source is licensed as `AGPL-3.0-or-later`. See the repository's top-level [LICENSE](../LICENSE).

No noncommercial, no-modification, field-of-use, paid-feature, donor-only, or supporter-only restrictions are added by this backend.

## Access Model

Authenticated active accounts receive free/default product access.

The following records and integrations are historical, compatibility, reconciliation, refund, support, or audit data. They do not unlock product features, remove product features, or decide whether an account may use Torve:

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

Remaining limits and checks are for auth, ownership, privacy, device security, anti-abuse, sync integrity, or technical stability. They must not be changed into paid access gates.

## Stack

- FastAPI + SQLAlchemy + psycopg2-binary
- Postgres
- Alembic migrations in `alembic/versions/`
- Pytest test suite in `tests/`
- Sentry, Resend, Discord, release metadata, and historical billing/support integrations

## Layout

```text
backend/
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

## Local Setup

Use placeholder-only local configuration. Do not use production credentials for contributor development.

```bash
cd backend
python -m venv venv
source venv/bin/activate
python -m pip install --upgrade pip
pip install -r requirements.txt
cp .env.example .env
alembic upgrade head
pytest
```

On Windows PowerShell, activate the virtual environment with:

```powershell
.\venv\Scripts\Activate.ps1
```

## Local Configuration

`.env.example` contains dummy development values. Copy it to `.env` and adjust only local settings such as `DATABASE_URL`.

`INTEGRATION_SECRET_KEY` must be a syntactically valid Fernet key when code imports `app.crypto`. Dummy local and CI values are acceptable as long as they are never used for production data.

Legacy billing and store-provider variables are optional for normal local tests. They remain present for historical compatibility and non-gating test coverage.

## Checks

```bash
cd backend
alembic upgrade head
pytest -v --tb=short
python -c "import app.main; print('backend import ok')"
```

Useful safety checks:

```bash
git grep -n "Depends(require_premium" -- app tests
git grep -n "premium_required" -- app tests
git grep -n "premium_lifetime\|premium_subscription" -- app tests
```

## Secrets

Secrets belong in `.env` or another deployment secret store, not in git.

Examples include database URLs, JWT secrets, integration encryption keys, webhook URLs, provider API keys, service-account JSON, signing material, and admin secrets.

The tracked `.env.example` file must contain placeholders only.

## Maintainer Deployment Notes

Production deployment is maintainer-only. Do not deploy the backend from public contributor workflows.

`scripts/deploy.sh` and `torve-backend.service` may still be relevant to the maintainer-operated environment. If used, deployment secrets remain outside git in `/opt/torve-backend/.env` or the deployment secret manager.

Public contributors should be able to run normal tests with placeholder values and without production credentials.
