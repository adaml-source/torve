param(
    [string]$ManifestPath = ".codex-deploy\releases.json",
    [switch]$Fix,
    [switch]$SourceOnly
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidBuild = Get-Content -Raw -Encoding utf8 (Join-Path $repoRoot "androidApp\build.gradle.kts")
$desktopBuild = Get-Content -Raw -Encoding utf8 (Join-Path $repoRoot "desktopApp\build.gradle.kts")
$iosInfo = Get-Content -Raw -Encoding utf8 (Join-Path $repoRoot "iosApp\iosApp\Info.plist")
$manifestFile = Join-Path $repoRoot $ManifestPath

$androidMatch = [regex]::Match($androidBuild, 'versionName\s*=\s*"([^"]+)"')
$desktopMatch = [regex]::Match($desktopBuild, 'torveMsiVersion[^\r\n]*\?:\s*"([^"]+)"')
$iosMatch = [regex]::Match(
    $iosInfo,
    '<key>CFBundleShortVersionString</key>\s*<string>([^<]+)</string>'
)
if (-not $androidMatch.Success -or -not $desktopMatch.Success -or -not $iosMatch.Success) {
    throw "Could not determine Android, desktop, or iOS release version."
}
if (-not $SourceOnly -and -not (Test-Path -LiteralPath $manifestFile)) {
    throw "Release manifest not found: $manifestFile"
}

$expected = $androidMatch.Groups[1].Value
$desktop = $desktopMatch.Groups[1].Value
$ios = $iosMatch.Groups[1].Value
if ($desktop -ne $expected) {
    throw "Desktop version $desktop does not match Android version $expected."
}
if ($ios -ne $expected) {
    throw "iOS version $ios does not match Android version $expected."
}

if ($SourceOnly) {
    Write-Output "Source release versions aligned at $expected across Android, desktop, and iOS."
    exit 0
}

$manifest = Get-Content -Raw -Encoding utf8 $manifestFile | ConvertFrom-Json
$stable = $manifest.channels.stable
$platforms = @("windows", "android_tv", "android_mobile", "fire_tv")
foreach ($platform in $platforms) {
    $entry = $stable.$platform
    if ($null -eq $entry) {
        throw "Stable release manifest is missing $platform."
    }
    if ($entry.version -ne $expected) {
        if (-not $Fix) {
            throw "$platform advertises $($entry.version); expected $expected. Run with -Fix to repair generated manifest metadata."
        }
        $oldVersion = [string]$entry.version
        $entry.version = $expected
        if ($entry.file) {
            $entry.file = ([string]$entry.file).Replace($oldVersion, $expected)
        }
        if ($entry.url) {
            $entry.url = ([string]$entry.url).Replace($oldVersion, $expected)
        }
        if ($entry.sha256_url) {
            $entry.sha256_url = ([string]$entry.sha256_url).Replace($oldVersion, $expected)
        }
    }
}

if ($Fix) {
    $json = $manifest | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($manifestFile, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

Write-Output "Release versions aligned at $expected across Android, desktop, iOS, Windows, Google TV/mobile, and Fire TV."
