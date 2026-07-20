# Torve desktop release helper — Windows.
#
# Builds the next desktop MSI with the production appcast URL baked
# in, computes SHA-256 + byte length, registers the release with the
# Torve backend's POST /admin/releases endpoint, and verifies the
# appcast URL now serves the new version.
#
# Prereqs:
#   * JDK 21+ on PATH (jpackage is required for packaging — JBR alone
#     doesn't ship jdk.jpackage; set TORVE_JPACKAGE_JDK or rely on the
#     gradle task's auto-discovery).
#   * Admin secret in $env:TORVE_ADMIN_SECRET. The admin endpoint takes
#     it via X-Admin-Secret. Never echo or commit this value.
#   * MSI hosting destination is your responsibility — upload manually
#     after this script builds the MSI; supply the final HTTPS URL via
#     the -MsiUrl parameter.
#
# Usage:
#   .\release\release-desktop.ps1 `
#     -Version 1.0.7 `
#     -MsiUrl  https://YOURDOMAIN/releases/Torve-1.0.7.msi `
#     -Notes   '<p>Bug fixes and the in-app updater finally landing.</p>'
#
# Flow:
#   1. Build MSI via :desktopApp:packageMsiCloseApp with
#      -PtorveUpdateFeed pointing at the production appcast URL.
#   2. Pause for you to upload the MSI to -MsiUrl.
#   3. Compute SHA-256 + length locally.
#   4. POST to https://api.torve.app/admin/releases.
#   5. Probe https://api.torve.app/releases/appcast.xml and confirm
#      the new <enclosure url=...> matches.

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,

    [Parameter(Mandatory)]
    [ValidatePattern('^https://')]
    [string]$MsiUrl,

    [Parameter(Mandatory)]
    [string]$Notes,

    [string]$AppcastUrl = "https://api.torve.app/releases/appcast.xml",
    [string]$AdminUrl   = "https://api.torve.app/admin/releases",
    [switch]$SkipBuild,
    [switch]$SkipUploadPause
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $repoRoot

# ── 1. Build (unless -SkipBuild) ─────────────────────────────────────
$msiOut = Join-Path $repoRoot 'desktopApp\build\compose\binaries\main-closeapp\msi'
$msiPath = Join-Path $msiOut "Torve-$Version.msi"

if (-not $SkipBuild) {
    Write-Host "[release] Building Torve-$Version.msi with appcast feed baked in..." -ForegroundColor Cyan
    if (-not $env:JAVA_HOME) {
        $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    }
    & .\gradlew.bat ":desktopApp:packageMsiCloseApp" `
        "-PtorveMsiVersion=$Version" `
        "-PtorveUpdateFeed=$AppcastUrl"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}

if (-not (Test-Path $msiPath)) {
    throw "MSI not found at $msiPath. Did the build succeed?"
}

# ── 2. Compute SHA-256 + length ──────────────────────────────────────
Write-Host "[release] Computing SHA-256 + length..." -ForegroundColor Cyan
$hashHex = (Get-FileHash -Algorithm SHA256 -Path $msiPath).Hash.ToLower()
$length  = (Get-Item $msiPath).Length
Write-Host "[release]   SHA-256 : $hashHex"
Write-Host "[release]   Length  : $length bytes ($([math]::Round($length / 1MB, 2)) MB)"
Write-Host "[release]   Path    : $msiPath"

# ── 3. Pause for upload (unless -SkipUploadPause) ────────────────────
if (-not $SkipUploadPause) {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Yellow
    Write-Host " UPLOAD STEP" -ForegroundColor Yellow
    Write-Host "============================================================" -ForegroundColor Yellow
    Write-Host " Upload the MSI above to:" -ForegroundColor Yellow
    Write-Host "   $MsiUrl" -ForegroundColor Yellow
    Write-Host ""
    Write-Host " Press Enter once the file is reachable at that URL." -ForegroundColor Yellow
    Read-Host
}

# ── 4. Verify the upload (HEAD + size match) ─────────────────────────
Write-Host "[release] Probing $MsiUrl ..." -ForegroundColor Cyan
try {
    $head = Invoke-WebRequest -Method Head -Uri $MsiUrl -UseBasicParsing
    $remoteLength = [int64]($head.Headers['Content-Length'])
    if ($remoteLength -ne $length) {
        Write-Host "[release] WARNING: remote length=$remoteLength, local=$length — upload may be incomplete." -ForegroundColor Yellow
    } else {
        Write-Host "[release]   Remote length matches local: $remoteLength bytes." -ForegroundColor Green
    }
} catch {
    throw "HEAD on $MsiUrl failed: $($_.Exception.Message). Upload first, then re-run with -SkipBuild."
}

# ── 5. Register release with backend ─────────────────────────────────
$adminSecret = $env:TORVE_ADMIN_SECRET
if (-not $adminSecret) {
    throw "TORVE_ADMIN_SECRET env var is not set. Set it from the private production secret store before running."
}

$body = @{
    version            = $Version
    msi_url            = $MsiUrl
    sha256             = $hashHex
    length             = $length
    release_notes_html = $Notes
    is_published       = $true
} | ConvertTo-Json -Depth 4

Write-Host "[release] POST $AdminUrl ..." -ForegroundColor Cyan
$response = Invoke-RestMethod -Method Post -Uri $AdminUrl `
    -Headers @{ 'X-Admin-Secret' = $adminSecret; 'Content-Type' = 'application/json' } `
    -Body $body
Write-Host "[release]   Saved version $($response.version)" -ForegroundColor Green
if ($response.appcast_url) {
    Write-Host "[release]   Appcast URL : $($response.appcast_url)" -ForegroundColor Green
}

# ── 6. Verify the appcast now lists the new version ──────────────────
Write-Host "[release] Probing $AppcastUrl ..." -ForegroundColor Cyan
$appcast = Invoke-WebRequest -Uri $AppcastUrl -UseBasicParsing
$xml = [xml]$appcast.Content
$advertised = $xml.rss.channel.item.enclosure.'sparkle:version'
$advertisedUrl = $xml.rss.channel.item.enclosure.url
if ($advertised -eq $Version) {
    Write-Host "[release]   Appcast advertises version $advertised" -ForegroundColor Green
    Write-Host "[release]   Appcast enclosure URL: $advertisedUrl" -ForegroundColor Green
} else {
    Write-Host "[release]   WARNING: appcast advertises '$advertised', expected '$Version'." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host " RELEASE DONE" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host " Version : $Version" -ForegroundColor Green
Write-Host " MSI     : $MsiUrl" -ForegroundColor Green
Write-Host " SHA-256 : $hashHex" -ForegroundColor Green
Write-Host " Appcast : $AppcastUrl" -ForegroundColor Green
Write-Host ""
Write-Host " End users on a prior version will pick this up next time" -ForegroundColor Green
Write-Host " they hit Settings → Diagnostics & Updates → Check for" -ForegroundColor Green
Write-Host " updates now (or on next launch if check-on-launch is on)." -ForegroundColor Green
