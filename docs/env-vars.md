# Environment Variables

All backend configuration is via environment variables or a `.env` file in the `server/` directory.

## Required

| Variable | Description | Example |
|----------|-------------|---------|
| `DATABASE_URL` | PostgreSQL async connection string | `postgresql+asyncpg://user:pass@localhost:5432/torve` |
| `REDIS_URL` | Redis connection string | `redis://localhost:6379/0` |
| `JWT_SECRET` | Secret key for JWT signing (min 32 chars) | `your-256-bit-secret-here` |

## Optional — General

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_NAME` | Torve Sync Server | Application name |
| `API_HOST` | 0.0.0.0 | Bind host |
| `API_PORT` | 8080 | Bind port |
| `DEBUG` | false | Enable debug mode |
| `JWT_ISSUER` | torve-sync | JWT issuer claim |
| `ACCESS_TOKEN_TTL_MINUTES` | 15 | Access token lifetime |
| `REFRESH_TOKEN_TTL_DAYS` | 30 | Refresh token lifetime |
| `PAIRING_CODE_TTL_MINUTES` | 10 | Device pairing code lifetime |

## Optional — Apple App Store

| Variable | Default | Description |
|----------|---------|-------------|
| `APPLE_BUNDLE_ID` | com.torve.app | iOS bundle identifier |
| `APPLE_ISSUER_ID` | (empty) | App Store Connect API issuer ID |
| `APPLE_KEY_ID` | (empty) | App Store Connect API key ID |
| `APPLE_PRIVATE_KEY_PATH` | (empty) | Path to .p8 private key file |

If `APPLE_ISSUER_ID` is empty, Apple JWS payloads are decoded but not verified against Apple's Server API (suitable for development).

## Optional — Google Play

| Variable | Default | Description |
|----------|---------|-------------|
| `GOOGLE_PACKAGE_NAME` | com.torve.app | Android package name |
| `GOOGLE_SERVICE_ACCOUNT_JSON` | (empty) | Full JSON content of Google Cloud service account key |

If `GOOGLE_SERVICE_ACCOUNT_JSON` is empty, Google purchases are accepted on trust with a warning log.

## Optional — Amazon Appstore

| Variable | Default | Description |
|----------|---------|-------------|
| `AMAZON_SHARED_SECRET` | (empty) | Amazon IAP shared secret |
| `AMAZON_RVS_SANDBOX` | false | Use Amazon sandbox RVS endpoint |

If `AMAZON_SHARED_SECRET` is empty, Amazon purchases are accepted on trust with a warning log.

## Optional — Device Governance

| Variable | Default | Description |
|----------|---------|-------------|
| `DEVICE_MAX_ACTIVE` | 5 | Maximum active devices per premium account |
| `DEVICE_STALE_DAYS` | 45 | Days of inactivity before a device auto-expires |
| `DEVICE_MAX_SWAPS_PER_30D` | 3 | Max slot-freeing device removals per rolling 30-day period |

No TV-specific sub-limit. All device types share the same cap.

## Example .env file

```env
DATABASE_URL=postgresql+asyncpg://torve:torve@localhost:5432/torve
REDIS_URL=redis://localhost:6379/0
JWT_SECRET=change-this-to-a-long-random-secret-at-least-32-chars
DEBUG=true

# Apple (optional for dev)
APPLE_BUNDLE_ID=com.torve.app

# Google (optional for dev)
GOOGLE_PACKAGE_NAME=com.torve.app

# Amazon (optional for dev)
AMAZON_RVS_SANDBOX=true
```
