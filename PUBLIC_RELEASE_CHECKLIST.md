# Torve Public Release Checklist

This private repository is not ready to be made public. Do not change repository visibility, create a public GitHub repository, push public branches, publish releases, or rewrite history as part of preparation work.

## Recommended Publication Path

Use a fresh sanitized public repository created from a clean source export after free-software conversion work is complete. Do not make this private repository public unless the full git history, archives, generated bundles, local files, and release artifacts have been audited and cleaned.

Minimum path for a future public repository:

1. Confirm backend, shared client, and platform-client free-software refactors remain complete.
2. Export only source files, public-safe docs, public-safe examples, and public-safe build scripts into a clean directory without `.git`.
3. Exclude local secrets, signing assets, generated archives, audit bundles, logs, dumps, databases, screenshots with sensitive data, and private assessment bundles.
4. Verify legal, license, and donation policy copy matches `AGPL-3.0-or-later` and the free-software product model.
5. Verify the top-level `LICENSE` and public `README` are present and accurate.
6. Run a secret scan over the clean export before the first public commit.
7. Create the public repository only after the sanitized export is reviewed.

## Current Publication Blockers

- Local signing and developer configuration files exist in the working tree and must remain ignored.
- Firebase configuration files are committed and require public-safe review before reuse in a public export.
- Audit archives and assessment bundles are tracked or present in history and should not be included in a public repository.
- The project license is `AGPL-3.0-or-later`; final audit must verify all package metadata and app legal surfaces agree.
- Product documentation has been rewritten or marked obsolete, but final audit must confirm no user-facing paid-product copy remains.
- Release scripts and release docs reference private deployment or billing secrets by variable name and require hardening before public use.
- Final audit must confirm the top-level license is included in the sanitized export.

## Secret And Artifact Handling

Before any public release:

- Rotate credentials that appeared in local secret files or could have been copied into bundles.
- Keep keystore passwords, signing keys, service-account files, API keys, webhook secrets, and production credentials outside the repository.
- Keep `.env.example` and config examples placeholder-only.
- Review committed Firebase config files for public exposure risk.
- Exclude archives such as audit bundles, assessment bundles, generated release bundles, logs, database dumps, and local app data from the public export.

## Licensing And Documentation

Project license: `AGPL-3.0-or-later`.

Files and areas that need replacement or cleanup before public release:

- top-level `LICENSE`
- `desktopApp/LICENSE`
- top-level `README` if added or restored
- store listing docs under `release/store/`
- premium, billing, entitlement, device-management, and market-readiness docs under `docs/`
- release scripts and release docs that reference billing or private deployment paths

Future public docs should state:

- Torve is free software.
- There are no subscriptions.
- There are no paid tiers.
- There are no premium features.
- Donations are optional and never unlock features, badges, quota, storage, sync, themes, quality settings, devices, or content.

## CI And Release Hardening

Public CI should run tests and static checks without private secrets. Deployment, store upload, desktop signing, app signing, and release publication jobs should be gated by protected branches, tags, environments, or manual approval, and must not run on untrusted pull requests.

Release scripts that reference production secrets should either be excluded from the first public export or rewritten to use documented placeholder configuration.

## Final Audit Gate

Public release remains blocked until secrets are rotated as needed, store metadata cleanup is completed externally, final cross-client audit passes, and a fresh sanitized public repository is prepared.
