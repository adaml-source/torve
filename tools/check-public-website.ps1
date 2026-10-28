param(
    [string]$WebRoot = "web",
    [string]$ManifestPath = ".codex-deploy\releases.json"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$resolvedWebRoot = Join-Path $repoRoot $WebRoot
$androidBuild = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $repoRoot 'androidApp\build.gradle.kts')
$releaseVersionMatch = [regex]::Match($androidBuild, 'versionName\s*=\s*"(?<version>\d+\.\d+\.\d+)"')
if (-not $releaseVersionMatch.Success) {
    throw 'Could not determine the release version from androidApp/build.gradle.kts.'
}
$releaseVersion = $releaseVersionMatch.Groups['version'].Value
# Public pages describe the deployed release, which may differ from in-progress app builds.
$publicSourceTreeUrl = "https://github.com/adaml-source/torve/tree/v$releaseVersion"
$publicReleaseManifestPath = Join-Path $repoRoot $ManifestPath
if (Test-Path -LiteralPath $publicReleaseManifestPath -PathType Leaf) {
    $publicReleaseManifest = Get-Content -Raw -Encoding utf8 -LiteralPath $publicReleaseManifestPath | ConvertFrom-Json
    if (-not $publicReleaseManifest.source.version -or -not $publicReleaseManifest.source.url) {
        throw 'Public release manifest must identify its source version and tree URL.'
    }
    $releaseVersion = [string]$publicReleaseManifest.source.version
    $publicSourceTreeUrl = [string]$publicReleaseManifest.source.url
}
$requiredPages = @(
    'index.html',
    'download.html',
    'source.html',
    'signin.html',
    'reset-password.html',
    'assets/password-reset.js',
    'privacy.html',
    'terms.html',
    'robots.txt',
    'sitemap.xml'
)

foreach ($page in $requiredPages) {
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedWebRoot $page) -PathType Leaf)) {
        throw "Tracked public website source is incomplete. Missing: web/$page"
    }
}

foreach ($trustFile in @(
    'CONTRIBUTING.md',
    'SECURITY.md',
    '.github/ISSUE_TEMPLATE/bug_report.yml',
    '.github/ISSUE_TEMPLATE/feature_request.yml',
    '.github/pull_request_template.md'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $trustFile) -PathType Leaf)) {
        throw "Public project trust surface is incomplete. Missing: $trustFile"
    }
}

$setupFiles = @(
    'app/setup.html',
    'assets/app/setup.js',
    'assets/app/page-setup.js',
    'assets/app/lang/en.js',
    'assets/app/lang/de.js',
    'assets/app/lang/es.js',
    'assets/app/lang/fr.js',
    'assets/app/lang/it.js',
    'assets/app/lang/pt.js',
    'assets/app/lang/tr.js'
)
foreach ($relativePath in $setupFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedWebRoot $relativePath) -PathType Leaf)) {
        throw "Tracked Connections portal source is incomplete. Missing: web/$relativePath"
    }
}

$index = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'index.html')
$jsonLdMatch = [regex]::Match(
    $index,
    '<script\s+type="application/ld\+json">(?<json>.*?)</script>',
    [Text.RegularExpressions.RegexOptions]::Singleline
)
if (-not $jsonLdMatch.Success) {
    throw "Public landing page is missing SoftwareApplication JSON-LD."
}
$jsonLd = $jsonLdMatch.Groups['json'].Value | ConvertFrom-Json
if ([string]$jsonLd.'@type' -ne 'SoftwareApplication') {
    throw "Public landing-page structured data must identify SoftwareApplication metadata."
}
if ([string]$jsonLd.codeRepository -ne 'https://github.com/adaml-source/torve') {
    throw "Public landing-page structured data has the wrong source repository."
}

$signin = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'signin.html')
if ($signin -notmatch 'href="/reset-password"[^>]*>Forgot password\?') {
    throw "Sign-in page must expose the password-reset entry point."
}
$reset = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'reset-password.html')
if ($reset -notmatch '<meta name="robots" content="noindex"' -or $reset -notmatch '<meta name="referrer" content="no-referrer"') {
    throw "Password-reset page must remain out of search indexes and suppress token referrers."
}
$resetScript = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'assets/password-reset.js')
foreach ($recoveryEndpoint in @('/auth/password-reset/request', '/auth/password-reset/confirm')) {
    if ($resetScript.IndexOf($recoveryEndpoint, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "Password-reset JavaScript is missing backend endpoint '$recoveryEndpoint'."
    }
}

$download = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'download.html')
if ($download -match 'play\.google\.com/store/apps') {
    throw "Public download page must not advertise a Google Play listing before production publication."
}
foreach ($copy in @('Controlled testing', 'Download Fire TV APK', 'Download Windows', 'Build provenance')) {
    if ($download.IndexOf($copy, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "Public download page is missing required truthful release copy: '$copy'"
    }
}

$source = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'source.html')
foreach ($sourceLink in @(
    $publicSourceTreeUrl,
    "/downloads/provenance/torve-$releaseVersion.json",
    'https://github.com/adaml-source/torve/blob/master/LICENSE',
    'https://github.com/adaml-source/torve/blob/master/CONTRIBUTING.md',
    'https://github.com/adaml-source/torve/blob/master/SECURITY.md'
)) {
    if ($source.IndexOf($sourceLink, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "Public source page is missing release correspondence link: $sourceLink"
    }
}

$resolvedManifest = Join-Path $repoRoot $ManifestPath
if (Test-Path -LiteralPath $resolvedManifest -PathType Leaf) {
    $manifest = Get-Content -Raw -Encoding utf8 -LiteralPath $resolvedManifest | ConvertFrom-Json
    foreach ($entry in @($manifest.channels.stable.fire_tv, $manifest.channels.stable.windows)) {
        if ($download.IndexOf([string]$entry.file, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "Public download fallback does not reference current release artifact: $($entry.file)"
        }
    }
    if ([string]$manifest.source.version -ne $releaseVersion) {
        throw "Public release manifest source version does not match $releaseVersion."
    }
    if ([string]$manifest.source.provenance_url -ne "https://torve.app/downloads/provenance/torve-$releaseVersion.json") {
        throw "Public release manifest is missing the $releaseVersion provenance URL."
    }
}

$robots = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'robots.txt')
if ($robots -notmatch 'Sitemap:\s+https://torve\.app/sitemap\.xml') {
    throw "robots.txt must advertise the canonical sitemap."
}
$sitemap = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'sitemap.xml')
foreach ($publicUrl in @(
    'https://torve.app/',
    'https://torve.app/download.html',
    'https://torve.app/source.html',
    'https://torve.app/privacy.html',
    'https://torve.app/terms.html',
    'https://torve.app/support.html'
)) {
if ($sitemap.IndexOf($publicUrl, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "sitemap.xml is missing: $publicUrl"
    }
}


$setupHtml = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'app/setup.html')
if ($setupHtml -notmatch '<title>Profiles & connections - Torve</title>' -or $setupHtml -notmatch '20261028-source-editors') {
    throw "Connections portal must expose the current source editors."
}
$setupRegistry = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'assets/app/setup.js')
if ($setupRegistry -match 'externalUrl:|PANDA_ADDON:') {
    throw "Connections portal must use direct source editors, without the retired external setup flow."
}
$setupPage = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'assets/app/page-setup.js')
foreach ($section in @("id:'usenet'", "id:'indexers'", "id:'replays'", "id:'debrid'")) {
    if (-not $setupPage.Contains($section)) { throw "Missing separate connection section: $section" }
}
$sourceEditor = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot 'assets/app/source-setup.js')
foreach ($contract in @('/me/source-setup/', 'expectedRevision', 'Saved — leave blank to keep', 'data-source-status')) {
    if (-not $sourceEditor.Contains($contract)) { throw "Missing source-editor contract: $contract" }
}
foreach ($locale in @('en', 'de', 'es', 'fr', 'it', 'pt', 'tr')) {
    $localeCopy = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $resolvedWebRoot "assets/app/lang/$locale.js")
    if ($localeCopy -notmatch 'openConnectionSetup:' -or $localeCopy -notmatch 'PANDA_ADDON:') {
        throw "Connections localization is incomplete for locale '$locale'."
    }
    $releaseStatusLines = @($localeCopy -split "`r?`n" | Where-Object {
        $_ -match '^\s*(launchNote|ctaSub):'
    })
    if ($releaseStatusLines.Count -ne 2) {
        throw "Connections locale '$locale' must define launchNote and ctaSub exactly once."
    }
    $releaseStatusCopy = $releaseStatusLines -join [Environment]::NewLine
    foreach ($staleReleasePhrase in @(
        'Launching soon',
        'Bald verfügbar',
        'Próximamente',
        'Bientôt disponible',
        'Prossimamente',
        'Em breve',
        'Yakında'
    )) {
        if ($releaseStatusCopy.IndexOf($staleReleasePhrase, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "Connections locale '$locale' still contains stale release copy: '$staleReleasePhrase'."
        }
    }
}

Write-Output "Tracked public website source, recovery, release truth, provenance, and indexing metadata verified."
