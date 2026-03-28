# Android Refresh Token Rotation Compatibility

## Previous app refresh behavior

- The shared Android auth flow lived in [shared/src/commonMain/kotlin/com/torve/data/auth/AuthClient.kt](/C:/Users/Anwender/StudioProjects/streamvault/shared/src/commonMain/kotlin/com/torve/data/auth/AuthClient.kt).
- Access and refresh tokens were stored in `SecureStorage` under:
  - `auth_access_token`
  - `auth_refresh_token`
  - `auth_token_expires_at`
- `/auth/refresh` was called from:
  - `AuthClient.getValidAccessToken()`
  - `AuthClient.refreshTokens()`
  - `AuthClient.restoreSession()`
  - the SSE verification reconnect path
  - shared callers used by both mobile and TV via `AuthClient`
- The old app behavior assumed refresh responses could omit `refresh_token` and left the old refresh token in place.
- Refresh serialization was incomplete:
  - `getValidAccessToken()` used a mutex
  - `refreshTokens()` itself did not
  - direct refresh callers could race
- Token writes were split across multiple storage operations, so old and new values could temporarily diverge.

## Updated storage behavior

- Refresh is now serialized through one shared auth-state mutex in `AuthClient`.
- Every successful refresh requires and persists:
  - new `access_token`
  - new `refresh_token`
  - updated expiry timestamp
- Token persistence now uses batched storage updates through:
  - [SecureStorage.updateStrings(...)](/C:/Users/Anwender/StudioProjects/streamvault/shared/src/commonMain/kotlin/com/torve/domain/security/SecureStorage.kt)
  - [AndroidKeystoreSecretStore.updateStrings(...)](/C:/Users/Anwender/StudioProjects/streamvault/androidApp/src/main/kotlin/com/torve/android/security/AndroidKeystoreSecretStore.kt)
- The old refresh token is replaced immediately after a successful refresh and is no longer retained for later use.

## Concurrency protection added

- `AuthClient` now uses a single mutex for:
  - migration from legacy plaintext token storage
  - login token persistence
  - register token persistence
  - refresh token rotation
  - logout clearing
  - delete-account clearing
- `refreshTokens()` now reads the refresh token only after acquiring the mutex.
- Concurrent `getValidAccessToken()` calls collapse to one refresh request.
- Later callers observe the newest stored token set instead of reusing a revoked refresh token.

## Invalid or revoked refresh handling

- Refresh failures with `400`, `401`, or `403` are treated as invalid-session outcomes.
- In those cases the app:
  - clears local auth tokens and cached auth user state
  - emits `AuthEvent.SessionExpired`
  - returns a non-success `AuthResult`
- Mobile and TV entry activities now collect `authEvents` and show a session-expired toast:
  - [MainActivity.kt](/C:/Users/Anwender/StudioProjects/streamvault/androidApp/src/main/kotlin/com/torve/android/MainActivity.kt)
  - [TvMainActivity.kt](/C:/Users/Anwender/StudioProjects/streamvault/androidApp/src/tv/kotlin/com/torve/android/TvMainActivity.kt)
- Network failures do not clear the local session.

## Files changed

- [AuthClient.kt](/C:/Users/Anwender/StudioProjects/streamvault/shared/src/commonMain/kotlin/com/torve/data/auth/AuthClient.kt)
- [SecureStorage.kt](/C:/Users/Anwender/StudioProjects/streamvault/shared/src/commonMain/kotlin/com/torve/domain/security/SecureStorage.kt)
- [AndroidKeystoreSecretStore.kt](/C:/Users/Anwender/StudioProjects/streamvault/androidApp/src/main/kotlin/com/torve/android/security/AndroidKeystoreSecretStore.kt)
- [MainActivity.kt](/C:/Users/Anwender/StudioProjects/streamvault/androidApp/src/main/kotlin/com/torve/android/MainActivity.kt)
- [TvMainActivity.kt](/C:/Users/Anwender/StudioProjects/streamvault/androidApp/src/tv/kotlin/com/torve/android/TvMainActivity.kt)
- [AuthClientRefreshRotationTest.kt](/C:/Users/Anwender/StudioProjects/streamvault/shared/src/commonTest/kotlin/com/torve/data/auth/AuthClientRefreshRotationTest.kt)
- [libs.versions.toml](/C:/Users/Anwender/StudioProjects/streamvault/gradle/libs.versions.toml)
- [shared/build.gradle.kts](/C:/Users/Anwender/StudioProjects/streamvault/shared/build.gradle.kts)

## Validation results

- Added shared tests for:
  - refresh response parsing of rotated `refresh_token`
  - persisted refresh token replacement
  - serialized concurrent refreshes
  - revoked refresh cleanup and session-expired event emission
  - transient network refresh failure preserving local session state
- Android mobile and TV startup paths both use the same shared `AuthClient`, so the rotation fix applies to both.

## Remaining edge cases

- Session-expired messaging is currently a toast at the app-entry activity layer. It is intentionally lightweight and does not yet route through a global snackbar/banner system.
- `restoreSession()` still returns `false` on startup refresh failure. That is correct for invalid refresh sessions, but offline startup UX may still depend on the surrounding screen logic.
