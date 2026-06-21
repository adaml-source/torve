# Environment Variables

All backend configuration is via environment variables or a `.env` file in the `server/` directory.

Real secrets must stay outside the repository. Do not commit production `.env` files, service-account files, signing material, webhook secrets, database dumps, logs, generated release artifacts, or local app data.

## Required

| Variable | Description | Example |
| --- | --- | --- |
| `DATABASE_URL` | PostgreSQL connection string | placeholder only |
| `REDIS_URL` | Redis connection string | placeholder only |
| `JWT_SECRET` | Secret key for JWT signing, minimum 32 chars | placeholder only |

## General Optional Variables

| Variable | Description |
| --- | --- |
| `APP_NAME` | Application name |
| `API_HOST` | Bind host |
| `API_PORT` | Bind port |
| `DEBUG` | Enable debug mode |
| `JWT_ISSUER` | JWT issuer claim |
| `ACCESS_TOKEN_TTL_MINUTES` | Access token lifetime |
| `REFRESH_TOKEN_TTL_DAYS` | Refresh token lifetime |
| `PAIRING_CODE_TTL_MINUTES` | Device pairing code lifetime |

## Device Governance

| Variable | Purpose |
| --- | --- |
| `DEVICE_MAX_ACTIVE` | Technical abuse-prevention and sync-stability device limit |
| `DEVICE_STALE_DAYS` | Days of inactivity before a device auto-expires |
| `DEVICE_MAX_SWAPS_PER_30D` | Max user-initiated device removals per rolling 30-day window |

These are not paid device-slot settings.

## Legacy Billing And Store Variables

Billing and store provider variables are legacy/historical unless a specific operations task still needs them for refunds, historical reconciliation, provider readiness checks, or compatibility tests. They are not required for product access.

Examples of legacy or historical variables include:

- Apple App Store / StoreKit verification variables
- Google Play service-account variables
- Amazon Appstore shared-secret variables
- Stripe variables
- Paddle variables
- webhook signing secrets

Do not place real provider credentials in committed docs or examples.

## Ignored Local Files

Keep these local and ignored:

- `.env`
- `.env.*`
- `keystore.properties`
- `local.properties`

Firebase `google-services.json` files in the public source tree are placeholders only. Maintainers must provide real Firebase configuration locally or through protected CI for production builds, and keys must be restricted by package name, SHA certificate fingerprint, and API allowlist where applicable. Android signing keys, TMDB keys, app-store service accounts, updater/admin secrets, and any credential copied into bundles should be rotated if exposure is suspected.

## Example `.env`

```env
DATABASE_URL=postgresql+asyncpg://user:password@localhost:5432/torve
REDIS_URL=redis://localhost:6379/0
JWT_SECRET=replace-with-a-long-random-secret
DEBUG=true
```

The example uses placeholders only. Replace them locally and never commit real values.
