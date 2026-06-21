# Torve Backend Documentation Extraction

Extracted: March 22, 2026
Updated: May 16, 2026 for access-state, addons, media favorites, and EPG validation
Source: `/opt/torve-backend/app/` (all Python files)
Backend version: 0.1.0 (FastAPI + PostgreSQL + uvicorn)

## Files in this pack

| File | Contents |
|---|---|
| `01_system_overview.md` | Architecture, domains, entity glossary, first-class vs derived concepts |
| `02_auth_and_account_flows.md` | Signup, login, refresh, verification, password reset, deletion, token details, account states, rate limits |
| `03_integrations_inventory.md` | Integration model, known types, storage modes, per-integration details, backend capabilities and limitations |
| `04_sources_and_setup_models.md` | Playlist model (M3U + Xtream), fields, validation, deduplication, sync behavior, client vs backend ownership |
| `05_device_pairing_and_sync.md` | Device registration, 5-device limit, pairing code flow, what syncs vs doesn't, SSE events, conflict handling |
| `06_settings_matrix.md` | Known settings keys, types, defaults, sync behavior, what backend doesn't know |
| `07_errors_and_troubleshooting.md` | Complete error matrix by endpoint, user symptoms, root causes, user-fixable vs support-only |
| `08_endpoint_catalog.md` | All 35+ endpoints with method, path, auth, purpose, request/response, feature mapping |
| `09_unknowns_and_gaps.md` | Naming ambiguities, frontend verification needed, known bugs, data the backend can't answer |

## What is sufficiently known to write final user docs

- Account creation and sign-in flow
- Email verification flow and timing
- Password reset flow
- Account deletion paths (in-app and web)
- Device management (registration, limit, removal)
- Phone-to-TV pairing via 6-digit code
- Integration save/restore lifecycle (account vs device-only modes)
- Playlist backup/restore lifecycle (M3U and Xtream)
- M3U EPG URL backup/restore and EPG URL validation
- Addon/extension account sync
- Media favorites account sync
- Account settings sync behavior
- All error messages and troubleshooting steps
- Security model (encryption at rest, JWT auth, user isolation)

## What still requires frontend/UI review

- Exact UI labels for all features (integration vs addon vs service vs provider)
- Which integration types are defined in client code beyond the 4 observed
- Whether verification gates any features in the app
- What the app does when device limit is reached (UI presentation)
- How the app handles failed integration restore (needs re-auth state)
- Whether watch history and continue watching have any documentation needs
- Full list of settings keys used by the app
- Whether in-app purchase / entitlement exists in each client UI
- Whether each client sends `installation_id` to `/me/access-state`

## Top 10 Documentation Priorities for Registered Users

1. **How to create an account and verify your email** - fully documented from backend
2. **How to add and manage devices** - fully documented, including 5-device limit
3. **How to pair your phone with your TV** - fully documented (code-based flow)
4. **How to add a playlist (M3U or Xtream)** - backend portion documented, client flow needs UI review
5. **How to connect integrations (Real-Debrid, Trakt, Simkl, OMDB)** - backend documented, client-specific setup steps need UI review
6. **How to reset your password** - fully documented
7. **How to delete your account** - fully documented (in-app + web)
8. **What syncs across devices and what doesn't** - fully documented from backend perspective
9. **What to do when you hit the device limit** - fully documented
10. **Troubleshooting sign-in, pairing, and restore issues** - comprehensive error matrix ready
