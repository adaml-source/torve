# System Overview

## Architecture

Torve backend is a **FastAPI** application (Python 3.12) running on **uvicorn** with 2 workers behind **nginx** reverse proxy. Data is stored in **PostgreSQL** (self-hosted). Secrets are encrypted at rest using **Fernet** (AES-128-CBC + HMAC-SHA256). Transactional email is sent via **Resend**. Real-time push uses **SSE** (Server-Sent Events) with **PostgreSQL LISTEN/NOTIFY** for cross-worker delivery.

- **Host:** Hetzner VPS (Ubuntu 24.04)
- **Domain:** `api.torve.app` (API), `torve.app` (website)
- **TLS:** Let's Encrypt via Certbot
- **Process manager:** systemd (`torve-backend.service`)

## Core Domains and Modules

| Domain | Module(s) | Description |
|---|---|---|
| Auth & Account | `routers/auth.py`, `routers/account.py` | Signup, login, refresh, password reset, email verification, account deletion, GET /me |
| Devices | `routers/devices.py` | Device registration, listing, heartbeat, revoke, rename |
| Pairing | `routers/pairings.py`, `routers/pairing_code.py` | Phone-to-TV code-based pairing |
| Settings | `routers/account_settings.py` | Account-scoped shared settings (JSONB) |
| Integrations | `routers/integrations.py` | Third-party service credentials (encrypted at rest) |
| Playlists | `routers/playlists.py` | M3U/Xtream playlist backup and restore |
| Events | `routers/sse.py`, `events.py` | Real-time SSE stream for account-level events |
| Health | `routers/health.py` | Service and database health check |
| Email | `mail.py` | Verification, password reset, welcome emails |
| Crypto | `crypto.py` | Fernet encrypt/decrypt for secrets at rest |
| Bootstrap | `bootstrap.py` | Google Play reviewer account seeding on startup |

## Core User Objects

### First-class entities (database tables)

| Entity | Table | Description |
|---|---|---|
| User | `users` | Account identity: email, password hash, display name, active/verified flags |
| RefreshToken | `refresh_tokens` | Long-lived opaque tokens for session refresh |
| PasswordResetToken | `password_reset_tokens` | One-time-use password reset links |
| EmailVerificationToken | `email_verification_tokens` | One-time-use email verification links |
| Device | `devices` | Registered phone/tablet/TV linked to account |
| DevicePairing | `device_pairings` | Active controller-to-target device links |
| PairingCode | `pairing_codes` | Short-lived 6-char codes for TV pairing UX |
| AccountSettings | `account_settings` | Shared JSONB settings blob per account |
| UserIntegration | `user_integrations` | Third-party service credentials per account |
| UserPlaylist | `user_playlists` | Playlist backup metadata and encrypted Xtream passwords |

### Concept Glossary

| Term | Backend meaning |
|---|---|
| **Integration** | A `UserIntegration` row: third-party service credentials (API key, OAuth token, etc.) stored under the account. Has `storage_mode` of `account` (encrypted on server) or `device_only` (metadata only, secrets on device). |
| **Playlist** | A `UserPlaylist` row: M3U URL or Xtream server/username/password backup stored under the account. Xtream passwords encrypted at rest. |
| **Device** | A `Device` row: a phone, tablet, or TV registered to the account. Max 5 active per account. |
| **Pairing** | A `DevicePairing` row linking a controller device (phone) to a target device (TV). Created via 6-character pairing codes. |
| **Account Settings** | A single `AccountSettings` row per user: JSONB blob with keys like `language`, `home_layout`, `ratings_provider`. Last-write-wins merge. |
| **Source / Channel Source** | Not a backend concept. The backend stores playlists (M3U URLs and Xtream credentials). The concept of "source" or "channel source" is client-side only. |
| **Extension / Addon** | Not a distinct backend entity. The backend stores integration credentials that may correspond to client-side addons/extensions. |
| **Media Source** | Not a backend concept. Client-side term for where content comes from. |
| **Service Login** | Not a distinct backend concept. Captured as a `UserIntegration` with credentials stored as encrypted JSON. |
| **URL Import** | Not a distinct backend concept. Captured as a `UserPlaylist` of type `m3u` with a URL field. |

### Derived vs First-class

- **First-class:** Users, Devices, DevicePairings, PairingCodes, AccountSettings, UserIntegrations, UserPlaylists
- **Derived/client-only:** Sources, channels, favorites, continue watching, EPG data, watchlists, media content metadata. None of these exist in the backend database.
