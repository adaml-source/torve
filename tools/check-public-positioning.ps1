param(
    [string]$ListingPath = "release/store/LISTING_COPY.md",
    [string]$ScreenshotDirectory = "release/store/screenshots",
    [switch]$RequireScreenshots
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-RepoPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return Join-Path $repoRoot $Path
}

$resolvedListing = Resolve-RepoPath $ListingPath
if (-not (Test-Path -LiteralPath $resolvedListing -PathType Leaf)) {
    throw "Canonical store listing is missing: $ListingPath"
}

$copy = Get-Content -LiteralPath $resolvedListing -Raw
$normalizedCopy = [System.Text.RegularExpressions.Regex]::Replace($copy, "\s+", " ")
$requiredCopy = @(
    "Watch now",
    "Save permanently",
    "Live TV",
    "Requests & downloads",
    "Connections center",
    "There are no subscriptions, paid tiers, premium features, or purchase requirement"
)

$missingCopy = @($requiredCopy | Where-Object {
    $normalizedCopy.IndexOf($_, [System.StringComparison]::OrdinalIgnoreCase) -lt 0
})
if ($missingCopy.Count -gt 0) {
    throw "Canonical store positioning is incomplete. Missing: $($missingCopy -join ', ')"
}

$expectedScreenshots = @(
    "01-home-watch-now",
    "02-detail-watch-or-save",
    "03-acquisition-status",
    "04-permanent-library",
    "05-live-tv-now-next",
    "06-connections-status"
)
$resolvedScreenshots = Resolve-RepoPath $ScreenshotDirectory
$present = @()
$missing = @()
foreach ($baseName in $expectedScreenshots) {
    $matches = @()
    if (Test-Path -LiteralPath $resolvedScreenshots -PathType Container) {
        $matches = @(Get-ChildItem -LiteralPath $resolvedScreenshots -File | Where-Object {
            $_.BaseName.StartsWith($baseName, [System.StringComparison]::OrdinalIgnoreCase) -and
            $_.Extension -match '^\.(png|jpe?g|webp)$'
        })
    }
    if ($matches.Count -gt 0) { $present += $baseName } else { $missing += $baseName }
}

if ($RequireScreenshots -and $missing.Count -gt 0) {
    throw "Store screenshot set is incomplete ($($present.Count)/$($expectedScreenshots.Count)). Missing: $($missing -join ', ')"
}

Write-Host "Public positioning copy: PASS"
Write-Host "Store screenshots: $($present.Count)/$($expectedScreenshots.Count) present"
if ($missing.Count -gt 0) {
    Write-Warning "Missing screenshot slots: $($missing -join ', ')"
}
