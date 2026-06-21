# Endpoint Catalog

## All User-Relevant Endpoints

| Method | Path | Auth | Purpose | Request body | Response | Feature |
|---|---|---|---|---|---|---|
| **Health** | | | | | | |
| GET | `/` | No | Service status | - | `{ "service": "torve-backend", "status": "online" }` | Health |
| GET | `/health` | No | DB health check | - | `{ "status": "ok/degraded", "database": "ok/unreachable", "timestamp": "..." }` | Health |
| **Auth** | | | | | | |
| POST | `/auth/signup` | No | Create account | `SignupRequest` (email, password, display_name, device) | `AuthResponse` (tokens, user, device) | Account creation |
| POST | `/auth/register` | No | Alias for signup | Same | Same | Account creation |
| POST | `/auth/login` | No | Sign in | `LoginRequest` (email, password, device) | `AuthResponse` | Sign in |
| POST | `/auth/refresh` | No | Refresh session | `RefreshRequest` (refresh_token, device) | `RefreshResponse` (new access_token, user, device) | Session refresh |
| POST | `/auth/password-reset/request` | No | Request password reset | `{ "email": "..." }` | `{ "message": "..." }` (always generic) | Password reset |
| POST | `/auth/password-reset/confirm` | No | Complete password reset | `{ "token": "...", "new_password": "..." }` | `{ "message": "..." }` | Password reset |
| GET | `/auth/verify-email` | No | Verify email (browser link) | Query: `?token=...` | Redirect to web page | Email verification |
| POST | `/auth/resend-verification` | No | Resend verification email | `{ "email": "..." }` | `{ "message": "..." }` (always generic) | Email verification |
| **Account** | | | | | | |
| GET | `/me` | Yes | Get current user profile | - | `UserOut` (id, email, display_name, is_active, is_verified, created_at) | Profile / verification check |
| GET | `/me/access-state` | Yes | Get premium/device access state | Query: `?installation_id=...` | `AccessStateOut` | Entitlement / device activation |
| PATCH | `/me/profile` | Yes | Update display name | `{ "display_name": "..." }` | `UserOut` | Profile |
| POST | `/me/change-password` | Yes | Change password and revoke refresh tokens | `{ "current_password": "...", "new_password": "..." }` | `{ "message": "..." }` | Account security |
| DELETE | `/me/account` | Yes | Delete account | - | `{ "message": "..." }` | Account deletion |
| DELETE | `/auth/account` | Yes | Alias for account deletion | - | Same | Account deletion |
| **Devices** | | | | | | |
| GET | `/me/devices` | Yes | List active devices | Query: `?include_revoked=true` | `DeviceOut[]` | Device management |
| POST | `/me/devices/register` | Yes | Register device | `DeviceRegisterRequest` | `DeviceOut` (201) | Device registration |
| POST | `/me/devices/{id}/heartbeat` | Yes | Update last seen | `{ "app_version": "..." }` | `DeviceOut` | Device tracking |
| POST | `/me/devices/{id}/revoke` | Yes | Revoke device | - | `DeviceOut` | Device management |
| POST | `/me/devices/{id}/remove` | Yes | Alias for revoke | - | `DeviceOut` | Device management |
| DELETE | `/me/devices/{id}` | Yes | Alias for revoke | - | `DeviceOut` | Device management |
| POST | `/me/devices/{id}/rename` | Yes | Rename device | `{ "display_name": "..." }` | `DeviceOut` | Device management |
| **Pairing** | | | | | | |
| POST | `/pairing/code` | Yes | Generate pairing code (TV) | `{ "device_id": "tv-uuid" }` | `PairingCodeOut` (code, expires_at, target_device_id) | TV pairing |
| POST | `/pairing/claim` | Yes | Claim pairing code (phone) | `{ "code": "ABC123", "device_id": "phone-uuid" }` | `PairingOut` | TV pairing |
| GET | `/me/pairings` | Yes | List active pairings | - | `PairingOut[]` | Pairing management |
| POST | `/me/pairings` | Yes | Create pairing by device IDs | `{ "controller_device_id": "...", "target_device_id": "..." }` | `PairingOut` (201) | Pairing |
| POST | `/me/pairings/{id}/revoke` | Yes | Revoke pairing | - | `PairingOut` | Pairing management |
| **Settings** | | | | | | |
| GET | `/me/account-settings` | Yes | Get account settings | - | `AccountSettingsOut` (settings, version, updated_at, updated_by_device_id) | Settings sync |
| PATCH | `/me/account-settings` | Yes | Update account settings | `{ "settings": {...}, "device_id": "..." }` | `AccountSettingsOut` | Settings sync |
| **Integrations** | | | | | | |
| GET | `/me/integrations` | Yes | List integrations (metadata only) | - | `IntegrationOut[]` | Integration management |
| GET | `/me/integrations/{type}` | Yes | Get single integration | - | `IntegrationOut` | Integration management |
| PUT | `/me/integrations/{type}` | Yes | Save/update integration | `IntegrationSaveRequest` | `IntegrationOut` | Integration setup |
| PATCH | `/me/integrations/{type}/credentials` | Yes | Merge credential keys into existing account-mode integration | `IntegrationCredentialsPatchRequest` | `IntegrationOut` | Integration restore / repair |
| DELETE | `/me/integrations/{type}` | Yes | Remove integration | - | 204 | Integration management |
| POST | `/me/integrations/{type}/test` | Yes | Test integration | - | `IntegrationTestResult` | Integration troubleshooting |
| GET | `/me/integrations/{type}/credentials` | Yes | Get decrypted credentials | - | `{ "integration_type", "storage_mode", "credentials" }` | Integration restore (hidden) |
| **Addons / Extensions** | | | | | | |
| GET | `/me/addons` | Yes | List installed addons/extensions | - | `AddonOut[]` | Extension sync |
| POST | `/me/addons` | Yes + Premium | Install addon by manifest URL | `AddonInstallRequest` | `AddonOut` (201) | Extension setup |
| PATCH | `/me/addons/{addon_id}` | Yes + Premium | Update addon metadata/state/order | `AddonUpdateRequest` | `AddonOut` | Extension management |
| DELETE | `/me/addons/{addon_id}` | Yes + Premium | Remove addon | - | 204 | Extension management |
| POST | `/me/addons/{addon_id}/toggle` | Yes + Premium | Toggle addon enabled state | - | `AddonOut` | Extension management |
| **Playlists** | | | | | | |
| GET | `/me/playlists` | Yes | List playlists (metadata only) | - | `PlaylistOut[]` | Playlist backup |
| PUT | `/me/playlists/{id}` | Yes + Premium | Save/update playlist, including M3U `epg_url` | `PlaylistSaveRequest` | `PlaylistOut` | Playlist backup |
| DELETE | `/me/playlists/{id}` | Yes | Remove playlist | - | 204 | Playlist management |
| GET | `/me/playlists/{id}/credentials` | Yes | Get Xtream password | - | `{ "playlist_id", "password" }` | Playlist restore (hidden) |
| POST | `/me/playlists/validate-epg` | Yes + Premium | Validate EPG URL reachability/XMLTV data | `EpgValidateRequest` | `EpgValidateResult` | EPG troubleshooting |
| **Media Favorites** | | | | | | |
| GET | `/me/media-favorites` | Yes | List account favorites | - | `MediaFavoritesListResponse` | Favorites sync |
| PUT | `/me/media-favorites/{media_key}` | Yes | Save/update favorite | `MediaFavoriteSaveRequest` | `MediaFavoriteMutationResponse` | Favorites sync |
| DELETE | `/me/media-favorites/{media_key}` | Yes | Delete favorite | - | `MediaFavoriteDeleteResponse` | Favorites sync |
| **Events** | | | | | | |
| GET | `/me/events` | Yes | SSE event stream | - | `text/event-stream` | Real-time verification |

## Compat Aliases (hidden from API docs)

All `/me/devices/...` endpoints also exist under `/devices/...` for backward compatibility.

## Endpoints That Do NOT Exist (but clients may call)

| Path | Status | Notes |
|---|---|---|
| `POST /auth/logout` | 404 | Logout is client-side only |
