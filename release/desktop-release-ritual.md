# Desktop release ritual

Private per-release procedure once `https://api.torve.app/releases/appcast.xml`
is live. Do not run this from public pull requests or untrusted forks.

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

This private repository should not be made public directly. The public release should be created from a fresh sanitized source export after final audit.

## One-time setup

1. Get the backend's admin secret from the private production secret
   store and put it in the local environment as `TORVE_ADMIN_SECRET`.
   Never paste the value into committed files, terminal transcripts,
   screenshots, logs, public CI, or issue comments. Examples:

   ```powershell
   # PowerShell (this session only)
   $env:TORVE_ADMIN_SECRET = '<set-from-private-secret-store>'

   # Or persist it for the user
   [Environment]::SetEnvironmentVariable('TORVE_ADMIN_SECRET', '<set-from-private-secret-store>', 'User')
   ```

2. Make sure JDK 21+ is reachable. `jpackage.exe` is required and JBR
   does NOT ship `jdk.jpackage`. If it's not on `JAVA_HOME`, set
   `TORVE_JPACKAGE_JDK` to a JDK 21+ install root (e.g.
   `C:\Program Files\Java\jdk-21.0.10`).

3. Decide where MSIs are hosted. Options:
   - Your own webpage (e.g. `https://torve.app/downloads/`)
   - GitHub Releases (free, easy)
   - Any S3-compatible bucket
   - Cloudflare R2 (cheap, no egress fees)

   Constraint: the URL **must be HTTPS**. The desktop client refuses
   non-HTTPS installer URLs in `UpdateInstallerHandoff.kt:81`.

## Per-release procedure

```powershell
.\release\release-desktop.ps1 `
  -Version 1.0.7 `
  -MsiUrl  https://torve.app/downloads/Torve-1.0.7.msi `
  -Notes   '<p>Bug fixes and the in-app updater finally landing.</p>'
```

What it does:

1. Builds `Torve-1.0.7.msi` with `-PtorveUpdateFeed=https://api.torve.app/releases/appcast.xml`
   baked in, so the MSI's `Torve.cfg` contains `-Dtorve.update.feed=…`
   and end users get auto-update without setting any env var.
2. Computes SHA-256 + byte length locally.
3. Pauses for you to upload the MSI to `-MsiUrl`.
4. HEADs the URL to confirm the upload size matches local size.
5. POSTs version + URL + sha256 + length + notes to
   `https://api.torve.app/admin/releases` with the
   `X-Admin-Secret: $TORVE_ADMIN_SECRET` header.
6. Probes the appcast and confirms it now advertises the new version.

After it returns success, users on a prior version pick up the
update next time they hit Settings → Diagnostics & Updates → Check
for updates now (or on next launch if check-on-launch is on).

## Manual fallback

If the helper script breaks for any reason, the same flow by hand:

```powershell
# 1. Build
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :desktopApp:packageMsiCloseApp `
  -PtorveMsiVersion=1.0.7 `
  -PtorveUpdateFeed=https://api.torve.app/releases/appcast.xml

# 2. Hash + length
$msi = 'desktopApp\build\compose\binaries\main-closeapp\msi\Torve-1.0.7.msi'
$hash = (Get-FileHash -Algorithm SHA256 -Path $msi).Hash.ToLower()
$len  = (Get-Item $msi).Length

# 3. Upload the MSI to your hosting (manual step)

# 4. Register
curl -X POST https://api.torve.app/admin/releases `
  -H "X-Admin-Secret: $env:TORVE_ADMIN_SECRET" `
  -H "Content-Type: application/json" `
  -d "{\"version\":\"1.0.7\",\"msi_url\":\"https://torve.app/downloads/Torve-1.0.7.msi\",\"sha256\":\"$hash\",\"length\":$len,\"release_notes_html\":\"<p>Bug fixes.</p>\",\"is_published\":true}"

# 5. Verify
Invoke-WebRequest https://api.torve.app/releases/appcast.xml
```

## Smoke vs production

The smoke kit (`smoke-kit/`) still serves a synthetic appcast via
cloudflared so you can verify upgrade UX without hitting prod. Don't
run the helper above when you're just smoke-testing — it'll register a
real release row.

For dev / QA: `TORVE_UPDATE_FEED` env var still wins over the baked-in
URL, so a packaged build can be redirected at a Sandbox cloudflared
tunnel without rebuilding.

## Public-readiness rules

- Normal tests must not require private secrets.
- Release signing, appcast publication, backend deploys, and store uploads must remain manual/protected.
- Public pull requests must not trigger deployment, signing, appcast publication, backend production deployment, or store upload.
- Keep `TORVE_ADMIN_SECRET`, signing keys, appcast credentials, and release tokens outside the repository.
- Desktop packaging license text must remain aligned with `AGPL-3.0-or-later`.

## What to watch on first real release

- **No code-signing means SmartScreen warns.** First-install users
  see "Windows protected your PC"; they need to click "More info" →
  "Run anyway". This is expected for public beta until an EV cert is
  in place. The auto-update path UAC-prompts but doesn't block.
- **The handoff verifies SHA-256 before launching the MSI.** If
  upload corruption causes a mismatch, the in-app updater fails
  closed — the user sees an error in the banner, not a broken
  install.
- **Admin secret rotation**: if you rotate the backend release-admin
  secret, also rotate `TORVE_ADMIN_SECRET` locally.
