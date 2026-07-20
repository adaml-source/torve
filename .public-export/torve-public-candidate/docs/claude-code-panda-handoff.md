# Claude Code Handoff: Panda + Torve Integration

Last updated: 2026-04-19

## Purpose

This document is a working handoff for continuing the Panda addon project and its Torve integration.

There are two repositories involved:

- Main Torve app repo:
  - Local path: `C:\Users\Anwender\StudioProjects\streamvault`
  - Remote: `https://github.com/adaml-source/torve`
  - Branch used: `master`
  - Latest pushed commit for this work: `936a438` (`Add Panda guided setup entry points`)
- Panda addon repo:
  - Local path: `C:\Users\Anwender\StudioProjects\torve_unofficial_panda`
  - Remote: `https://github.com/adaml-source/torve_unofficial_panda`
  - Branch used: `main`
  - Latest pushed commit for this work: `85192b4` (`Implement Panda v1 Torrentio proxy`)

## What Was Built

### 1. Panda v1 exists as a working standalone service

Panda is a Node-based addon service intended to simplify onboarding for non-power users while keeping advanced controls available.

Implemented in Panda:

- guided setup page at `/configure`
- base addon manifest at `/manifest.json`
- server-side config persistence in local JSON storage
- signed tokens for user-specific manifest URLs
- server-side handling of debrid credentials
- Torrentio-backed stream proxying via Panda endpoints
- simple branding/logo endpoint
- updated `README.md`

Key Panda files:

- `src/server.js`
- `src/config/config-store.js`
- `src/config/config-token.js`
- `src/config/schema.js`
- `src/providers/provider-registry.js`
- `src/providers/torrentio-adapter.js`
- `src/streams/pipeline.js`
- `src/ui/config-page.js`

### 2. Torve now surfaces Panda as the recommended setup path

Torve was not switched fully to Panda-only mode. Instead, the onboarding and addon surfaces now encourage Panda first while preserving manual advanced setup.

Implemented in Torve:

- configurable `PANDA_BASE_URL` build config field
- Panda added to the addon catalog as a recommended item
- Panda shown in setup wizard as the recommended flow
- advanced manual debrid/API-key setup still remains under that recommendation
- localized strings added for the new Panda UI labels

Key Torve files:

- `androidApp/build.gradle.kts`
- `androidApp/src/main/kotlin/com/torve/android/ui/settings/AddonCatalogScreen.kt`
- `androidApp/src/main/kotlin/com/torve/android/ui/setup/SetupWizardScreen.kt`
- `androidApp/src/main/res/values/strings.xml`
- `androidApp/src/main/res/values-de/strings.xml`
- `androidApp/src/main/res/values-es/strings.xml`
- `androidApp/src/main/res/values-fr/strings.xml`
- `androidApp/src/main/res/values-it/strings.xml`
- `androidApp/src/main/res/values-pt/strings.xml`
- `androidApp/src/main/res/values-tr/strings.xml`

## Current Architecture

### Panda request flow

Current intended user flow:

1. User opens Panda `/configure`
2. User selects:
   - sources/providers
   - debrid provider
   - debrid API key/token
   - quality profile
   - max quality
   - release language
   - result limit / sorting / cached behavior
3. Panda stores config server-side
4. Panda returns a manifest URL of the form:
   - `/u/{signedToken}/manifest.json`
5. Stremio-compatible client requests Panda stream endpoint:
   - `/u/{signedToken}/stream/{type}/{id}.json`
6. Panda resolves stored config and proxies Torrentio upstream

### Security model

Important: Panda does not put debrid credentials in the manifest URL.

Current storage model:

- configs stored in `.data/configs.json`
- signing secret stored in `.data/signing-secret.txt` unless `PANDA_SECRET` is set

This is acceptable for local development and early deployment, but not enough for real production security. There is no DB, auth layer, user ownership model, encryption-at-rest, or config editing flow yet.

## What Was Verified

### Panda

Locally verified:

- `GET /healthz`
- `POST /api/configs`
- generated per-user manifest URL
- `GET /debug/config/{token}`
- live upstream Torrentio stream fetch through Panda

Observed behavior:

- Panda returned valid stream results for `movie/tt0133093` when the network restriction was lifted
- inside the sandbox, upstream Torrentio fetches fail with `fetch failed`
- this was a tooling/network limitation, not a code-path failure

### Torve

Compiled successfully:

```powershell
./gradlew :androidApp:compileGoogleMobileDebugKotlin :androidApp:compileGoogleTvDebugKotlin
```

No integration compile failures were left unresolved.

## Important Context From Earlier Torve Work

These changes were already made before the Panda work and remain relevant:

- locale handling fix so English selection is applied even on non-English phones
- text-overflow / no-wrap hardening for settings UI and TV settings UI
- debrid labels changed from abbreviations to full names:
  - Real Debrid
  - Premiumize Me
  - All Debrid
  - Torbox
- Torrentio was added earlier as a built-in optional addon entry

The user’s product direction is:

- easy onboarding for non-power users
- keep full customization for power users
- eventually centralize streaming configuration in Panda instead of making users manually combine app setup and addon setup

## Files and Behavior Added in This Phase

### Panda

#### `src/config/config-store.js`

- file-backed config persistence
- stores config records by generated id
- exposes config lookup and secret-redaction helper

#### `src/config/config-token.js`

- signs config ids with HMAC
- validates tokens before access
- replaces the earlier plain base64 config token approach

#### `src/config/schema.js`

- config schema for:
  - providers
  - debrid service
  - API key/token
  - put.io client id
  - quality profile
  - max quality
  - release language
  - sorting / limit / hide flags

#### `src/providers/torrentio-adapter.js`

- maps Panda config to Torrentio config-path segments
- constructs upstream Torrentio stream URL
- fetches upstream stream JSON

#### `src/server.js`

Important routes:

- `GET /`
- `GET /healthz`
- `GET /manifest.json`
- `GET /configure`
- `POST /api/configs`
- `GET /u/:token/manifest.json`
- `GET /u/:token/stream/:type/:id.json`
- `GET /debug/config/:token`
- `GET /logo.svg`

### Torve

#### `androidApp/build.gradle.kts`

- added `PANDA_BASE_URL`
- default is currently:
  - `https://panda.torve.app`
- can be overridden with:
  - `TORVE_PANDA_BASE_URL`
  - `-PpandaBaseUrl=...`

#### `AddonCatalogScreen.kt`

- added Panda as a recommended entry
- Panda entry opens browser setup instead of trying to directly install a generic static manifest
- keeps existing addon catalog behavior for normal installable addons

#### `SetupWizardScreen.kt`

- debrid/setup step now has:
  - Panda recommended setup card
  - browser-launch button to Panda `/configure`
  - advanced manual setup section still visible underneath

## Current Gaps / Known Limitations

### Panda product gaps

These are the biggest next items:

1. No real deployment yet
   - Torve defaults to `https://panda.torve.app`, but Panda is not actually deployed there in this workspace
2. No account/auth concept
   - configs are anonymous and token-based only
3. No config editing UI
   - only create, not manage/edit/delete
4. No production-grade storage
   - local JSON file only
5. No user ownership / multi-tenant protection
6. No encryption at rest for secrets
7. No catalog/meta/subtitle support yet
8. No non-Torrentio providers beyond the abstraction
9. No direct Torve in-app manifest installation flow for Panda
   - current UX opens browser to Panda setup

### Torve integration gaps

1. Setup wizard still keeps old manual path and does not yet install Panda automatically
2. Torve does not yet have a dedicated “Connect Panda” or “Manage Panda” screen
3. No status indicator showing whether Panda is already installed/configured
4. No migration path from current direct streaming service setup to Panda-managed setup
5. No dedicated TV-first Panda onboarding surface yet

## Recommended Next Steps

Recommended priority order:

### Phase 1: Make Panda deployable

1. Add a minimal deployment target for Panda
   - Dockerfile or simple Node deployment packaging
2. Move config persistence from `.data/configs.json` to a proper backend
   - SQLite is acceptable as a next step if speed matters
   - Postgres is better if a server environment already exists
3. Add environment configuration docs
4. Deploy Panda somewhere stable
5. Point Torve builds at the real Panda base URL

### Phase 2: Improve onboarding UX

1. Add a proper Torve screen for Panda connection/setup
2. Detect whether Panda is already installed/configured
3. Add a clear choice:
   - Recommended setup
   - Advanced manual setup
4. Optionally deep-link users directly to Panda configuration/install flow

### Phase 3: Add config management

1. Add edit/update flow for existing Panda configs
2. Add “regenerate manifest” and “change debrid provider” flow
3. Add validation of debrid credentials before save
4. Add better presets:
   - easiest setup
   - best quality
   - anime-focused
   - low bandwidth

### Phase 4: Broaden source support

1. Add more upstream adapters besides Torrentio
2. Add ranking and policy logic on Panda side instead of relying mostly on Torrentio behavior
3. Add metadata/catalog support where needed

## Notes About the Torve Worktree

The `streamvault` repo is very dirty locally with many unrelated modified and untracked files that were not part of this Panda work.

Important:

- do not assume the local worktree is clean
- do not reset or revert broadly
- only touch the files needed for the next task
- the Panda integration commit was already pushed, but the local Torve workspace still contains many unrelated local changes

The Panda repo, by contrast, was clean after push.

## Exact Commands Previously Used

Useful validation commands:

### Torve compile

```powershell
./gradlew :androidApp:compileGoogleMobileDebugKotlin :androidApp:compileGoogleTvDebugKotlin
```

### Panda local run

```powershell
node src/server.js
```

### Panda local test flow

1. Open `http://127.0.0.1:7000/configure`
2. Create config
3. Open returned manifest URL
4. Test:
   - `/debug/config/{token}`
   - `/u/{token}/stream/movie/tt0133093.json`

## Recommended Immediate Task for Claude Code

If continuing immediately, the highest-value next step is:

1. make Panda deployable and persistent beyond local JSON storage
2. then add a real Torve “Manage Panda” UX around that deployment

That is the shortest path from prototype to something users can actually rely on.
