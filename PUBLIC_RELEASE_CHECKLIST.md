# Torve Public Release Checklist

This is the public Torve source repository. Treat every committed file and every
pushed branch as public. Never commit credentials, signing material, private
user data, production logs, database exports, or generated release bundles.

This checklist governs source releases and the relationship between public
source, distributed binaries, the website, and app-store submissions.

## Release Source Integrity

For every public binary release:

1. Start from a reviewed, committed source revision.
2. Run the secret and artifact checks before creating a tag.
3. Ensure the version in Android, shared, desktop, backend, updater metadata,
   and store metadata agrees.
4. Create an immutable source tag for the exact released revision.
5. Build release artifacts from that revision using protected signing
   credentials outside the repository.
6. Publish checksums and associate every binary with its source tag.
7. Verify the website, updater feed, and store listing point to the intended
   version.
8. Preserve previous release artifacts and metadata needed for rollback.
9. Run `tools/check-release-provenance.ps1 -RequireArtifacts -RequireTag`
   and publish the resulting provenance document beside the downloads.

Do not publish binaries built from a dirty working tree or from an unpublished
source revision.

## Secret and Artifact Handling

- Keep production `.env` files, keystores, passwords, API keys, service-account
  keys, admin tokens, webhook secrets, signing certificates, and store
  credentials outside the repository.
- Keep examples placeholder-only.
- Review Firebase client configuration and restrict its API keys to the intended
  applications and APIs.
- Do not commit audit bundles, database dumps, local app data, diagnostic logs,
  screenshots containing account data, or generated release archives.
- If a credential may have entered Git history or a distributed bundle, rotate
  it; deleting the current file is not sufficient.
- Run a history-aware secret scan before major public releases.

## Legal and Product Copy

Verify that every platform and public surface consistently states:

- Torve is free software under `AGPL-3.0-or-later`.
- There are no subscriptions, paid tiers, or paid-only features.
- Donations are optional and never unlock product capabilities.
- Torve does not provide media, playlists, subscriptions, or content rights.
- Users connect their own lawful services and sources.
- TMDB attribution is present.
- Watch-provider availability supplied through TMDB is attributed to JustWatch.
- Privacy disclosures match the Torve account backend, device and settings sync,
  optional credential sync, connected services, diagnostics, and the exact
  Firebase behavior of each release flavor.

Remove obsolete billing, premium, founder, and entitlement copy from user-facing
surfaces. Historical billing code or records must never affect free product
access.

## Public Project Surface

Before announcing a release, verify:

- the public repository, license, README, contribution guide, security policy,
  and issue tracker are reachable without authentication;
- the landing page and source page link to the correct repository;
- build instructions name the actual modules and commands;
- release notes identify the source tag and supported platforms;
- support and privacy contact addresses work;
- password reset, account deletion, and email verification links return a
  successful page on both `torve.app` and `www.torve.app`.

## CI and Deployment Safety

Public pull requests must run tests and static checks without production
secrets. They must not automatically:

- sign or publish application binaries;
- deploy the backend or website;
- upload store releases;
- modify updater feeds;
- access production user data.

Signing, deployments, store uploads, and release publication must use protected
branches/tags, protected environments, or deliberate operator actions.

## Store Release Gates

### Google Play / Google TV

- Required store screenshots and listing assets are complete.
- Data Safety matches the exact release build and backend behavior.
- A review account and reviewer instructions work.
- TV D-pad, Back, focus restoration, search input, and 16 KB page-size behavior
  are verified on current hardware or an appropriate runtime environment.
- `tools/check-android-16kb-compatibility.ps1` passes for the signed Fire TV
  APK and both Google Play AABs, including AAB page-alignment metadata, APK ZIP
  alignment, both ARM ABIs, and every 64-bit ELF `PT_LOAD` segment.
- Provider branding and artwork do not imply unaffiliated endorsement.

### Amazon Fire TV

- The signed release APK—not a debug APK—is installed for final smoke testing.
- `tools/check-connected-release-devices.ps1` passes and confirms that no
  `com.torve.app*.debug` package or debuggable/test build remains installed.
- Direct update metadata points to a signed release APK with a verified checksum.
- No Firebase SDK is present in the Amazon artifact.
- D-pad, Back, playback, account recovery, and update installation pass on the
  supported Fire TV devices.

### Desktop

- The installer is tested on a clean Windows environment.
- Update metadata, download checksum, install, relaunch, rollback, and uninstall
  behavior are verified.
- Signing and SmartScreen limitations are documented honestly.

## Final Gate

A public release is ready only when:

- the relevant release builds and tests are green;
- no debug build is installed as part of release verification;
- legal/privacy/store copy matches actual behavior;
- the source tag and binary versions agree;
- account recovery and deletion work;
- known high-impact playback, navigation, or synchronization regressions are
  either fixed or explicitly block the release.
