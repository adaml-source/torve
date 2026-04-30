<#
.SYNOPSIS
    Stage the Windows VLC runtime under desktopApp/runtime/windows/vlc/ so a
    release Torve build can ship without requiring a system VLC install.

.DESCRIPTION
    Copies libvlc.dll, libvlccore.dll, plugins/, and the upstream
    COPYING.txt from a clean VLC 64-bit install (or a portable .zip
    extraction) into the project's `desktopApp/runtime/windows/vlc/`
    directory. Then runs `:desktopApp:verifyWindowsPackagingPrereqs` so a
    bad drop fails fast.

    The script is idempotent — re-running it overwrites the existing
    drop. It deliberately does NOT download VLC: VideoLAN's terms ask
    redistributors to fetch from videolan.org under their own license
    obligations.

.PARAMETER Source
    Path to a VLC install root (the directory containing libvlc.dll).
    Defaults to "C:\Program Files\VideoLAN\VLC" — the standard 64-bit
    installer location.

.PARAMETER SkipVerify
    Skip the gradle gate at the end. Use only for ad-hoc copy testing.

.EXAMPLE
    .\stage-windows-vlc-runtime.ps1
    Stages from the default install location and runs the gate.

.EXAMPLE
    .\stage-windows-vlc-runtime.ps1 -Source "D:\portable\vlc-3.0.20"
    Stages from a portable extraction.

.NOTES
    LGPL-2.1: VLC's COPYING.txt MUST travel with the bundle. The gate
    refuses to package without it. Don't strip or rename it.

    For day-to-day :desktopApp:run development you don't need to stage
    these files — VlcRuntimeLocator falls back to a system install or
    the TORVE_VLC_PATH env var. Staging is for *release* packaging only.
#>

[CmdletBinding()]
param(
    [string] $Source = "C:\Program Files\VideoLAN\VLC",
    [switch] $SkipVerify
)

$ErrorActionPreference = "Stop"

# ── locate project root ─────────────────────────────────────────────
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$desktopAppDir = Split-Path -Parent $scriptDir
$projectRoot = Split-Path -Parent $desktopAppDir
$targetDir = Join-Path $desktopAppDir "runtime\windows\vlc"

Write-Host "Torve VLC runtime stager" -ForegroundColor Cyan
Write-Host "  source : $Source"
Write-Host "  target : $targetDir"
Write-Host ""

# ── source validation ───────────────────────────────────────────────
if (-not (Test-Path $Source)) {
    throw "VLC source directory not found: $Source. Install VLC 64-bit from https://www.videolan.org/ or pass -Source."
}
$libvlcSrc = Join-Path $Source "libvlc.dll"
$libvlccoreSrc = Join-Path $Source "libvlccore.dll"
$pluginsSrc = Join-Path $Source "plugins"
foreach ($req in @($libvlcSrc, $libvlccoreSrc, $pluginsSrc)) {
    if (-not (Test-Path $req)) {
        throw "Required VLC item not found in source: $req"
    }
}

# License notice — required for LGPL-2.1 compliance.
$licenseCandidates = @("COPYING.txt", "COPYING", "LICENSE-VLC.txt", "LICENSE.txt")
$licenseSrc = $null
foreach ($name in $licenseCandidates) {
    $candidate = Join-Path $Source $name
    if (Test-Path $candidate) { $licenseSrc = $candidate; break }
}
if (-not $licenseSrc) {
    throw "VLC license notice not found in $Source. Expected one of: $($licenseCandidates -join ', '). VLC's LGPL-2.1 obligations require the upstream COPYING file to travel with the bundle."
}

# ── stage ───────────────────────────────────────────────────────────
if (-not (Test-Path $targetDir)) {
    New-Item -ItemType Directory -Path $targetDir | Out-Null
}

# Wipe any prior plugins/ tree to avoid orphan files from older drops.
$targetPlugins = Join-Path $targetDir "plugins"
if (Test-Path $targetPlugins) {
    Remove-Item -Recurse -Force $targetPlugins
}

Copy-Item -Force $libvlcSrc       (Join-Path $targetDir "libvlc.dll")
Copy-Item -Force $libvlccoreSrc   (Join-Path $targetDir "libvlccore.dll")
Copy-Item -Recurse -Force $pluginsSrc $targetDir
Copy-Item -Force $licenseSrc      (Join-Path $targetDir "COPYING.txt")

# Surface what landed.
$pluginCount = (Get-ChildItem -Path $targetPlugins -Filter *.dll -Recurse).Count
Write-Host "Staged successfully:" -ForegroundColor Green
Write-Host "  libvlc.dll        : $((Get-Item (Join-Path $targetDir 'libvlc.dll')).Length) bytes"
Write-Host "  libvlccore.dll    : $((Get-Item (Join-Path $targetDir 'libvlccore.dll')).Length) bytes"
Write-Host "  plugins/*.dll     : $pluginCount file(s)"
Write-Host "  COPYING.txt       : $((Get-Item (Join-Path $targetDir 'COPYING.txt')).Length) bytes"
Write-Host ""

# ── gate ────────────────────────────────────────────────────────────
if ($SkipVerify) {
    Write-Host "Skipping :desktopApp:verifyWindowsPackagingPrereqs (-SkipVerify)." -ForegroundColor Yellow
    exit 0
}

Push-Location $projectRoot
try {
    Write-Host "Running :desktopApp:verifyWindowsPackagingPrereqs ..." -ForegroundColor Cyan
    & cmd /c "gradlew.bat :desktopApp:verifyWindowsPackagingPrereqs"
    if ($LASTEXITCODE -ne 0) {
        throw "verifyWindowsPackagingPrereqs failed (exit $LASTEXITCODE). The drop is incomplete; fix the items it reported."
    }
    Write-Host "Runtime is release-ready." -ForegroundColor Green
} finally {
    Pop-Location
}
