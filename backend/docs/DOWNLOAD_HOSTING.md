# Torve Download Hosting

Torve direct downloads are served as static files from Nginx, not through the FastAPI process.

Public URL prefix:

```text
https://torve.app/downloads/
```

Server directory:

```text
/opt/torve/downloads/
```

Expected layout:

```text
/opt/torve/downloads/
  releases.json
  windows/
    torve-windows-1.0.0.msi
    torve-windows-1.0.0.msi.sha256
  android/
    torve-android-tv-1.0.0.apk
    torve-android-tv-1.0.0.apk.sha256
    torve-android-mobile-1.0.0.apk
    torve-android-mobile-1.0.0.apk.sha256
```

## Naming

Use lowercase, versioned filenames:

```text
torve-windows-x.y.z.msi
torve-android-tv-x.y.z.apk
torve-android-mobile-x.y.z.apk
```

Keep old versioned files in place after publishing a newer release so old links continue to work.

## Publish With The Script

From `/opt/torve-backend`:

```bash
sudo scripts/publish_download_release.sh \
  --version 1.0.0 \
  --channel stable \
  --release-notes-summary "Windows and Fire TV direct downloads are now available." \
  --windows-msi /path/to/Torve-1.0.0.msi \
  --android-tv-apk /path/to/androidApp-amazon-tv-release.apk
```

Optional mobile APK:

```bash
sudo scripts/publish_download_release.sh \
  --version 1.0.0 \
  --channel stable \
  --release-notes-summary "Windows, Fire TV, and direct Android mobile downloads are now available." \
  --windows-msi /path/to/Torve-1.0.0.msi \
  --android-tv-apk /path/to/androidApp-amazon-tv-release.apk \
  --android-mobile-apk /path/to/androidApp-mobile-release.apk
```

The script:

- creates `/opt/torve/downloads/windows` and `/opt/torve/downloads/android`
- refuses to overwrite existing versioned files
- copies MSI/APK files into place
- writes `.sha256` sidecar files
- calculates `size_bytes`
- updates `/opt/torve/downloads/releases.json` atomically
- verifies the public download page, manifest, and artifact URLs respond over HTTPS
- sends non-blocking Discord release notifications when `DISCORD_RELEASE_WEBHOOK_URL` is configured
- prints the final public URLs

## Discord Release Notifications

Set these backend environment variables on the server:

```text
TORVE_PUBLIC_DOWNLOAD_URL=https://torve.app/download.html
DISCORD_RELEASE_WEBHOOK_URL=
```

Keep the webhook URL only in the real server environment or `.env`; do not commit it. The notifier posts only the public download page, platform, version, build type, release notes summary, file size, checksum, and the Windows unsigned warning when applicable. It does not include webhook URLs, direct storage URLs, signed URLs, admin URLs, credentials, or tokens.

The publish script sends one Discord notification per available artifact after all local copy/checksum work is complete and public HTTPS checks pass. Discord delivery is best-effort and does not fail an otherwise successful release publish.

## Manual Publishing

Create directories:

```bash
sudo install -d -m 0755 /opt/torve/downloads/windows /opt/torve/downloads/android
```

Copy files:

```bash
sudo cp Torve-1.0.0.msi /opt/torve/downloads/windows/torve-windows-1.0.0.msi
sudo cp androidApp-amazon-tv-release.apk /opt/torve/downloads/android/torve-android-tv-1.0.0.apk
```

Generate SHA-256 on Linux:

```bash
cd /opt/torve/downloads/windows
sha256sum torve-windows-1.0.0.msi | sudo tee torve-windows-1.0.0.msi.sha256

cd /opt/torve/downloads/android
sha256sum torve-android-tv-1.0.0.apk | sudo tee torve-android-tv-1.0.0.apk.sha256
```

Generate SHA-256 on Windows before copying:

```powershell
certutil -hashfile Torve-1.0.0.msi SHA256
certutil -hashfile androidApp-amazon-tv-release.apk SHA256
```

A sidecar file should contain the lowercase hex hash and the published filename:

```text
<sha256>  torve-windows-1.0.0.msi
```

Update `/opt/torve/downloads/releases.json` with the latest URLs, SHA-256 values, sizes, and statuses. Use `status: "available"` for Windows and Fire TV/Android TV files that exist. Use `status: "optional"` for a published direct mobile APK. Use `status: "unavailable"` for anything not published.

If `download.html` is ever changed to hardcode release URLs instead of fetching `/downloads/releases.json`, update the hardcoded version, size, and checksum text in `/var/www/torve-site/download.html` at the same time.

## Nginx

The `torve.app` Nginx server serves:

- `/downloads/releases.json` from `/opt/torve/downloads/releases.json` with a short cache
- `/downloads/windows/*.msi`
- `/downloads/windows/*.msi.sha256`
- `/downloads/android/*.apk`
- `/downloads/android/*.apk.sha256`

Directory listing is disabled, public uploads are not supported, only GET and HEAD are allowed for published files, and Nginx sends `X-Content-Type-Options: nosniff`.

## Test

After publishing and reloading Nginx:

```bash
curl -I https://torve.app/download.html
curl -I https://torve.app/downloads/releases.json
curl -I https://torve.app/downloads/windows/torve-windows-1.0.0.msi
curl -I https://torve.app/downloads/android/torve-android-tv-1.0.0.apk
curl -I https://torve.app/downloads/
```

Expected:

- `download.html` opens
- `releases.json` returns `200` and `Cache-Control: public, max-age=60`
- MSI/APK links return `200`
- `/downloads/` does not show a directory listing
- Fire TV shows `Download Fire TV APK` when `android_tv.status` is `available`
- Windows shows `Download Windows MSI` when `windows.status` is `available`
- Google Play remains primary for Android phone/tablet and Android TV/Google TV
- macOS, Linux, and iOS remain coming soon
- SHA-256 links match the uploaded files

Every direct download counts as outgoing server traffic. Current Hetzner included traffic is likely enough for early Torve downloads. A CDN or object storage bucket can be added later if download volume grows.
