# Public Release Prompt Summaries

This document tracks the public-release prompt work completed so far.

## Prompt 1: Repository-Wide Public-Release Polish

Goal: make the public Torve repository accurate, contributor-ready, and internally consistent with the free-software product model.

Scope covered:

- Rewrote the root README for the public repository.
- Updated backend documentation to use `backend/` instead of the old private/server wording.
- Repaired backend CI metadata for `main` and `backend/**`.
- Renamed the Gradle root project from `StreamVault` to `Torve`.
- Kept GitHub funding disabled because no maintainer-approved donation destination was provided.
- Added contributor, security, issue template, and pull request template files.
- Added a placeholder-only `backend/.env.example`.

Key product invariants preserved:

- Torve is free software.
- There are no subscriptions, paid tiers, premium features, or purchase requirements.
- Donations are optional and never unlock features.
- Login remains an account/sync/device-linking boundary, not a payment or entitlement gate.

Validation summary:

- Static consistency checks on edited files passed.
- `git diff --check` passed.
- Full fresh-clone/build validation was limited by environment constraints:
  - PyPI/network access was blocked for backend dependency installation.
  - Android SDK location was not configured.
  - Gradle wrapper redownload was blocked by network restrictions after generated build directories were cleared.

Remaining risks:

- Tracked Firebase `google-services.json` files still require maintainer public-safe review.
- The prepared repository polish changes were not pushed, deployed, signed, uploaded, or released.

## Prompt 2: Website Discovery and Migration Planning

Goal: audit the Torve website before changing it, identify source/deployment ownership, inventory routes, and plan conversion to the free-software model.

Scope covered:

- Inspected the live website at `https://torve.app`.
- Searched the local and public repository trees for website source and deployment hints.
- Reviewed backend web-session, web-proxy, account, and payment compatibility routes.
- Reviewed website-related deployment notes in backend/download hosting documentation.

Main findings:

- No canonical website source directory was found in the public Torve repository.
- The live website appears to be static HTML served by Nginx.
- Local docs imply deployed website files may live outside the repo, including `/var/www/torve-site/download.html`.
- Backend docs identify the production host as Hetzner with Nginx, Let's Encrypt/Certbot, FastAPI, and PostgreSQL.
- The backend exposes cookie-backed website auth/session routes under `/web/auth/*` and authenticated proxy routes under `/web/api/*`.

Live route findings:

- `/` is live and contains paid-pricing messaging.
- `/index.html` is live and differs from `/`.
- `/download.html` is live and contains Premium/subscription/lifetime language.
- `/privacy.html` is live and contains purchase/subscription/billing language.
- `/terms.html` is live and contains placeholder dates and conditional paid-feature billing language.
- `/account-deletion.html` is live and provides in-app and email deletion instructions.

Paid-product references found:

- Homepage pricing section with Monthly and Lifetime plans.
- Premium feature copy on the homepage and download page.
- Stripe/refund/subscription language in FAQ and legal pages.
- Backend Stripe/Paddle/store purchase compatibility routes remain present as historical/non-gating infrastructure.

Recommended website migration plan:

- Locate or recover the canonical website source before editing.
- Convert homepage and download pages to the canonical free-software product language.
- Remove or reframe Premium, plan, pricing, checkout, subscription, lifetime, and Stripe purchase copy.
- Keep login, signup, session, password reset, privacy, and account deletion functionality.
- Clarify that account deletion is independent of subscription cancellation.
- Add links to GitHub source, AGPL license, issue tracker, downloads, privacy policy, account deletion, and a donation resource only after a maintainer-approved donation destination exists.

Read-only confirmation:

- No website files were edited.
- No deployment, DNS change, database change, or secret disclosure occurred.

## Prompt 3: Website Free-Software Implementation

Goal: create an updated Torve website source that replaces paid-product messaging and active purchase flows with accurate free-software, source, sync, donation, privacy, and account-deletion messaging.

Scope covered:

- Added a static website source package under `website/`.
- Added routes for home, downloads, source, sign-in, sign-up, account services, account deletion, privacy, terms, support, status, sitemap, robots, and 404.
- Replaced pricing and purchase CTAs with free-software, download, source, and sync sign-in CTAs.
- Preserved login and sign-up boundaries for sync and account-scoped data.
- Added a public account-deletion route suitable for mobile, desktop, and TV users.
- Added optional donation handling through a public-safe `TORVE_DONATION_URL` build variable.
- Hid donation CTAs when no approved donation URL is configured.
- Added validation scripts for metadata, local links, required routes, banned paid-product terms, donation behavior, and secret-pattern checks.

Key product invariants preserved:

- Torve is free software under AGPL-3.0-or-later.
- There are no subscriptions, paid tiers, premium features, or purchase requirements.
- Donations are optional and never unlock features.
- Authentication remains required for sync, device linking, account-backed data, export requests, and account deletion.
- Local-only use is not gated by billing or entitlement state.

Validation summary:

- Website lint/typecheck/test validation passed.
- Production static build passed.
- Built-output validation passed.
- Link, accessibility-structure, responsive-structure, and donation-mode checks passed.
- A local preview can be served from `website/dist`.

Remaining risks:

- The canonical production website source was not found during discovery, so `website/` is a proposed source package for review.
- No remote preview deployment was created because no deployment provider or preview pipeline is configured in the public repository.
- Production deployment still requires maintainer approval and confirmation of the actual Nginx/static-site path.
- No maintainer-approved donation URL has been provided.

Safety confirmation:

- No production deployment occurred.
- No DNS, database, environment, signing, release, or production backend changes occurred.

## Prompt 4: Android Mobile Free-Software Google Play Prep

Goal: prepare and verify Android mobile, shared KMP, and backend authentication
boundaries for the free-software Google Play update.

Scope covered:

- Inventoried local, authenticated, owner-only, admin-only, and technical-only boundaries.
- Added backend regression tests for unauthenticated and expired-token rejection, owner-scoped sync preferences, cross-account device protection, pairing ownership, and non-gating payment/donation state.
- Strengthened Android Google mobile unit coverage for `HAS_BILLING=false`, `SUPPORTS_TV_BILLING=false`, hidden donation links, and blank donation URL.
- Added tracked placeholder-safe Google Play reviewer instructions.
- Added tracked Google Play listing copy package.
- Added tracked Android release-prep notes with versioning, Firebase, billing cleanup, account deletion, and upgrade-test checklists.

Key product invariants preserved:

- Local-only functionality must not require payment.
- Sync, device linking, and account-scoped cloud data require authentication.
- Authentication and ownership boundaries remain enforced.
- Billing, purchase, entitlement, and donation state must not determine feature access.
- Account deletion remains available.

Validation summary:

- Backend and Android validation should be run from a machine with backend dependencies, Android SDK, and signing setup available.
- Version code was not changed because the current Play production version code and approved release version were not provided.
- Release upload, signing publication, Play upload, and production deployment were not performed.

Remaining risks:

- The tracked Firebase `google-services.json` files require maintainer review for public-safe publication.
- Legacy premium/subscription names remain in compatibility code and string resources; Google mobile active billing is disabled by build flags, but release QA should inspect the final APK/AAB UI and manifest.
- Upgrade testing from the previous paid production build requires the previous production artifact and a device/emulator.

## Prompt 5: Website, Legal Copy, Android Release Prep, and Play Console Inputs

Goal: prepare Torve website copy, bundled legal assets, Android release checks,
and Play Console input documents for the free-software update without changing
Google Play Console state.

Scope covered:

- Updated website product messaging to the canonical free-software and account/sync model.
- Kept donations optional and hidden unless a maintainer-approved URL is configured.
- Updated bundled Android privacy and terms assets to match account-backed sync behavior.
- Verified the public account-deletion page loads at `https://torve.app/account-deletion.html`.
- Created a Play Console inventory worksheet template.
- Created a private maintainer subscriber decision template.
- Preserved the requirement that version code and version name must come from maintainer input.

Key product invariants preserved:

- Torve is free software under AGPL-3.0-or-later.
- There are no subscriptions, paid tiers, premium features, or purchase requirements.
- Donations are optional and never unlock features.
- Login remains required for sync, device linking, account-backed data, export, and deletion.
- Payment, entitlement, purchase history, and donation status do not affect access.

Validation summary:

- Website lint, static build, link checks, accessibility-structure checks, responsive checks, and donation-mode checks passed.
- `:shared:allTests` passed.
- Backend auth-focused pytest collection was blocked by missing `psycopg2` in the local Python environment.
- Android Google mobile unit tests were blocked by a missing Android SDK location.
- Release AAB generation was not attempted because Play production version code and approved version name were not provided.

Remaining risks:

- Play Console inventory and subscriber-treatment decisions still require maintainer input.
- Android release validation requires an Android SDK, protected signing setup, and approved version values.
- The live production website still contains older footer/legal wording until an approved deployment occurs.

Safety confirmation:

- No Google Play Console changes were made.
- No deployment, upload, publication, product deactivation, subscriber cancellation, refund, or signing action occurred.
