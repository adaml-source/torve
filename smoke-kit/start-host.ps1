# Torve Updater Smoke — host-side launcher.
#
# Starts:
#   1. A local HTTP server on :8080 serving this folder (Torve-1.0.7.msi + appcast.xml)
#   2. A cloudflared "trycloudflare" quick tunnel that exposes :8080 over HTTPS
#   3. Once the tunnel URL is captured, rewrites appcast.xml so the <enclosure>
#      points at the HTTPS tunnel URL (the Torve handoff refuses non-HTTPS).
#
# Usage:  .\start-host.ps1
# Stop:   Ctrl+C in this window — both child processes get killed.

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

# --- 1. Sanity checks ----------------------------------------------------
$msi = Join-Path $PSScriptRoot "Torve-1.0.7.msi"
if (-not (Test-Path $msi)) {
    Write-Host "[smoke] ERROR: Torve-1.0.7.msi not found at $msi" -ForegroundColor Red
    Write-Host "        Copy it from desktopApp/build/compose/binaries/main/msi/ first." -ForegroundColor Red
    exit 1
}

$cloudflared = Join-Path $PSScriptRoot "cloudflared.exe"
if (-not (Test-Path $cloudflared)) {
    Write-Host "[smoke] cloudflared.exe missing. Downloading..." -ForegroundColor Yellow
    $url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
    Invoke-WebRequest -Uri $url -OutFile $cloudflared -UseBasicParsing
    Write-Host "[smoke] cloudflared.exe ready." -ForegroundColor Green
}

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) {
    Write-Host "[smoke] ERROR: python not on PATH (used to serve the smoke folder)." -ForegroundColor Red
    exit 1
}

# --- 2. Local HTTP server ------------------------------------------------
Write-Host "[smoke] Starting local HTTP server on http://localhost:8080 ..." -ForegroundColor Cyan
$httpJob = Start-Process -FilePath $python.Source `
    -ArgumentList @("-m", "http.server", "8080", "--bind", "127.0.0.1") `
    -WorkingDirectory $PSScriptRoot `
    -PassThru -WindowStyle Hidden

Start-Sleep -Seconds 2

# --- 3. cloudflared quick tunnel ----------------------------------------
$cloudflaredLog = Join-Path $PSScriptRoot "cloudflared.log"
if (Test-Path $cloudflaredLog) { Remove-Item $cloudflaredLog -Force }

Write-Host "[smoke] Starting cloudflared quick tunnel..." -ForegroundColor Cyan
$tunnelJob = Start-Process -FilePath $cloudflared `
    -ArgumentList @("tunnel", "--url", "http://localhost:8080", "--logfile", $cloudflaredLog) `
    -PassThru -WindowStyle Hidden

# Poll for the trycloudflare URL.
$publicUrl = $null
$timeoutSec = 60
$startedAt = Get-Date
while ($null -eq $publicUrl -and ((Get-Date) - $startedAt).TotalSeconds -lt $timeoutSec) {
    Start-Sleep -Seconds 2
    if (Test-Path $cloudflaredLog) {
        $match = Select-String -Path $cloudflaredLog -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' -ErrorAction SilentlyContinue
        if ($match) {
            $publicUrl = ($match[0].Matches[0].Value).TrimEnd('/')
        }
    }
}

if (-not $publicUrl) {
    Write-Host "[smoke] ERROR: tunnel did not come up within ${timeoutSec}s." -ForegroundColor Red
    Write-Host "        See cloudflared.log for details." -ForegroundColor Red
    Stop-Process -Id $tunnelJob.Id -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $httpJob.Id -Force -ErrorAction SilentlyContinue
    exit 1
}

Write-Host "[smoke] Tunnel up: $publicUrl" -ForegroundColor Green

# --- 4. Rewrite appcast.xml with the live tunnel URL --------------------
$msiSize = (Get-Item $msi).Length
$pubDate = (Get-Date).ToUniversalTime().ToString("ddd, dd MMM yyyy HH:mm:ss '+0000'", [System.Globalization.CultureInfo]::InvariantCulture)

$appcast = @"
<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">
  <channel>
    <title>Torve</title>
    <link>$publicUrl/</link>
    <description>Torve smoke-test feed.</description>
    <language>en</language>
    <item>
      <title>Torve 1.0.7</title>
      <pubDate>$pubDate</pubDate>
      <link>$publicUrl/release-notes.html</link>
      <description><![CDATA[Smoke-test 1.0.7 release.]]></description>
      <enclosure
        url="$publicUrl/Torve-1.0.7.msi"
        sparkle:version="1.0.7"
        sparkle:shortVersionString="1.0.7"
        length="$msiSize"
        type="application/octet-stream"
        />
    </item>
  </channel>
</rss>
"@

$appcastPath = Join-Path $PSScriptRoot "appcast.xml"
$appcast | Out-File -FilePath $appcastPath -Encoding utf8 -Force
Write-Host "[smoke] Wrote appcast.xml pointing at $publicUrl/Torve-1.0.7.msi" -ForegroundColor Green

# --- 5. Print Sandbox-side instructions ----------------------------------
Write-Host ""
Write-Host "============================================================" -ForegroundColor Yellow
Write-Host " SMOKE READY" -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Yellow
Write-Host " Appcast URL :  $publicUrl/appcast.xml" -ForegroundColor Yellow
Write-Host " MSI URL     :  $publicUrl/Torve-1.0.7.msi" -ForegroundColor Yellow
Write-Host ""
Write-Host " Now: double-click sandbox.wsb to launch Windows Sandbox." -ForegroundColor Yellow
Write-Host "      Inside the Sandbox, run:" -ForegroundColor Yellow
Write-Host "          C:\smoke\sandbox-run.ps1 -FeedUrl '$publicUrl/appcast.xml'" -ForegroundColor Yellow
Write-Host ""
Write-Host " Press Ctrl+C here when you are done to tear down the tunnel." -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Yellow

# Persist the URL for sandbox-run.ps1 default arg.
$publicUrl | Out-File -FilePath (Join-Path $PSScriptRoot "feed-url.txt") -Encoding ascii -Force

# --- 6. Wait until interrupted ------------------------------------------
try {
    Wait-Process -Id $tunnelJob.Id
} finally {
    Write-Host ""
    Write-Host "[smoke] Cleaning up..." -ForegroundColor Cyan
    Stop-Process -Id $tunnelJob.Id -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $httpJob.Id -Force -ErrorAction SilentlyContinue
    Write-Host "[smoke] Done." -ForegroundColor Cyan
}
