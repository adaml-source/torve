# Torve Updater Smoke — Sandbox-side runner.
#
# Runs INSIDE Windows Sandbox after sandbox.wsb maps the smoke-kit folder
# to C:\smoke. Steps:
#   1. Install the OLD version (1.0.6 MSI) silently.
#   2. Set TORVE_UPDATE_FEED to the cloudflared URL captured by start-host.ps1.
#   3. Launch Torve. The updater hits the feed, finds 1.0.7, downloads,
#      verifies, and hands off to msiexec → 1.0.7 lands.
#
# Usage:  .\sandbox-run.ps1                       (uses feed-url.txt)
#         .\sandbox-run.ps1 -FeedUrl https://...  (override)

param(
    [string]$FeedUrl
)

$ErrorActionPreference = "Stop"
Set-Location -Path 'C:\smoke'

if (-not $FeedUrl) {
    if (Test-Path 'C:\smoke\feed-url.txt') {
        $base = (Get-Content 'C:\smoke\feed-url.txt' -Raw).Trim()
        $FeedUrl = "$base/appcast.xml"
    } else {
        Write-Host "ERROR: no -FeedUrl given and feed-url.txt is missing." -ForegroundColor Red
        Write-Host "       Did start-host.ps1 finish on the host side?" -ForegroundColor Red
        exit 1
    }
}

Write-Host "[smoke] Feed URL: $FeedUrl" -ForegroundColor Cyan

# --- 1. Install Torve 1.0.6 ---------------------------------------------
$oldMsi = 'C:\smoke\Torve-1.0.6.msi'
if (-not (Test-Path $oldMsi)) {
    Write-Host "ERROR: $oldMsi missing. Copy it into smoke-kit before running." -ForegroundColor Red
    exit 1
}
Write-Host "[smoke] Installing Torve 1.0.6 (silent)..." -ForegroundColor Cyan
$install = Start-Process msiexec.exe -ArgumentList @('/i', $oldMsi, '/qn', '/L*v', 'C:\smoke\install-1.0.6.log') -Wait -PassThru
if ($install.ExitCode -ne 0) {
    Write-Host "ERROR: 1.0.6 install failed with exit code $($install.ExitCode). See install-1.0.6.log." -ForegroundColor Red
    exit 1
}
Write-Host "[smoke] 1.0.6 installed." -ForegroundColor Green

# --- 2. Locate the installed exe and verify version ---------------------
$exe = $null
$candidates = @(
    "$env:ProgramFiles\Torve\Torve.exe",
    "${env:ProgramFiles(x86)}\Torve\Torve.exe",
    "$env:LOCALAPPDATA\Torve\Torve.exe"
)
foreach ($c in $candidates) {
    if (Test-Path $c) { $exe = $c; break }
}
if (-not $exe) {
    Write-Host "ERROR: Torve.exe not found after install. Searched:" -ForegroundColor Red
    $candidates | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "[smoke] Found installed exe at $exe" -ForegroundColor Green
$installedVersion = (Get-Item $exe).VersionInfo.ProductVersion
Write-Host "[smoke] Installed version reports: $installedVersion (expecting 1.0.6)" -ForegroundColor Cyan

# --- 3. Launch Torve with TORVE_UPDATE_FEED -----------------------------
$env:TORVE_UPDATE_FEED = $FeedUrl
Write-Host "[smoke] TORVE_UPDATE_FEED set; launching Torve..." -ForegroundColor Cyan
Write-Host ""
Write-Host "============================================================" -ForegroundColor Yellow
Write-Host " WHAT TO CHECK MANUALLY ONCE TORVE OPENS:" -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Yellow
Write-Host " 1. Sign in (or skip)." -ForegroundColor Yellow
Write-Host " 2. ONBOARDING smoke (Fix A+B+E):" -ForegroundColor Yellow
Write-Host "      - Confirm the Panda-primary hub shows two CTAs:" -ForegroundColor Yellow
Write-Host "          [Set up with Panda]   [Skip for now]" -ForegroundColor Yellow
Write-Host "      - Click 'Skip for now' -> lands on Home." -ForegroundColor Yellow
Write-Host "      - Confirm Home shows the zero-source empty state with:" -ForegroundColor Yellow
Write-Host "          'Set up sources'  /  'Sync your watchlist with Trakt'" -ForegroundColor Yellow
Write-Host " 3. UPDATER smoke (B4):" -ForegroundColor Yellow
Write-Host "      - Open Settings -> About -> Check for updates." -ForegroundColor Yellow
Write-Host "      - Banner should report 'Update available: 1.0.7'." -ForegroundColor Yellow
Write-Host "      - Click 'Download & install'." -ForegroundColor Yellow
Write-Host "      - Watch progress -> handoff -> msiexec installs 1.0.7." -ForegroundColor Yellow
Write-Host "      - Re-launch Torve. About should now show 1.0.7." -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Yellow
Write-Host ""

Start-Process -FilePath $exe
