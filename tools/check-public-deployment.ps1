param(
    [string]$SiteBaseUrl = "https://torve.app",
    [string]$ApiBaseUrl = "https://api.torve.app",
    [string]$ProvenancePath = "release\provenance\1.1.6.json",
    [string]$SshHost = ""
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$siteBase = $SiteBaseUrl.TrimEnd('/')
$apiBase = $ApiBaseUrl.TrimEnd('/')
$expected = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $repoRoot $ProvenancePath) | ConvertFrom-Json

function Get-PublicResponse {
    param([string]$Url, [ValidateSet('Get', 'Head')][string]$Method = 'Get')
    if (-not [string]::IsNullOrWhiteSpace($SshHost)) {
        if ($Url -notmatch '^https://[A-Za-z0-9._/-]+$') {
            throw "SSH-backed public check refused an unsafe URL: '$Url'."
        }
        if ($Method -eq 'Head') {
            $headerText = (& ssh $SshHost "curl -fsSIL --max-redirs 8 --connect-timeout 15 --max-time 45 '$Url'" 2>&1 | Out-String)
            if ($LASTEXITCODE -ne 0) {
                throw "Public HEAD request failed through '$SshHost': $Url`n$headerText"
            }
            $lengthMatches = [regex]::Matches($headerText, '(?im)^content-length:\s*(?<length>\d+)\s*$')
            if ($lengthMatches.Count -eq 0) {
                throw "Public HEAD response has no Content-Length: $Url"
            }
            return [pscustomobject]@{
                StatusCode = 200
                Content = ''
                Headers = @{ 'Content-Length' = $lengthMatches[$lengthMatches.Count - 1].Groups['length'].Value }
            }
        }
        $content = (& ssh $SshHost "curl -fsSL --max-redirs 8 --connect-timeout 15 --max-time 45 '$Url'" 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) {
            throw "Public GET request failed through '$SshHost': $Url`n$content"
        }
        return [pscustomobject]@{ StatusCode = 200; Content = $content; Headers = @{} }
    }
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -Method $Method -MaximumRedirection 8 -TimeoutSec 30
    if ([int]$response.StatusCode -lt 200 -or [int]$response.StatusCode -ge 300) {
        throw "Public URL returned HTTP $($response.StatusCode): $Url"
    }
    return $response
}

$pageChecks = @(
    @{ Path = '/'; Required = 'Torve' },
    @{ Path = '/download.html'; Required = 'Download Fire TV APK' },
    @{ Path = '/source.html'; Required = 'Build provenance' },
    @{ Path = '/privacy.html'; Required = 'Torve accounts and synchronization' },
    @{ Path = '/terms.html'; Required = 'free software' },
    @{ Path = '/reset-password'; Required = 'Forgot your password' },
    @{ Path = '/account-deletion.html'; Required = 'Account Deletion' },
    @{ Path = '/app/help.html'; Required = 'Help' },
    @{ Path = '/help.html'; Required = 'Help' },
    @{ Path = '/app/setup.html'; Required = 'Connections' }
)

$checkedUrls = @()
foreach ($hostBase in @($siteBase, $siteBase.Replace('https://', 'https://www.'))) {
    foreach ($check in $pageChecks) {
        $url = "$hostBase$($check.Path)"
        $response = Get-PublicResponse $url
        if ($response.Content.IndexOf($check.Required, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "Public page '$url' is missing required content '$($check.Required)'."
        }
        $checkedUrls += $url
    }
}

$resetResponse = Get-PublicResponse "$siteBase/reset-password"
foreach ($recoveryMarker in @('id="reset-email"', 'noindex', 'no-referrer')) {
    if ($resetResponse.Content.IndexOf($recoveryMarker, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "Production password recovery is missing '$recoveryMarker'."
    }
}
$resetScript = (Get-PublicResponse "$siteBase/assets/password-reset.js").Content
if ($resetScript.IndexOf('/auth/password-reset/request', [StringComparison]::OrdinalIgnoreCase) -lt 0) {
    throw 'Production password-recovery JavaScript is not wired to the reset-request API.'
}

$manifestResponse = Get-PublicResponse "$siteBase/downloads/releases.json"
$manifest = $manifestResponse.Content | ConvertFrom-Json
$provenanceResponse = Get-PublicResponse "$siteBase/downloads/provenance/torve-$($expected.version).json"
$publicProvenance = $provenanceResponse.Content | ConvertFrom-Json

if ([string]$manifest.source.version -ne [string]$expected.version -or
    [string]$manifest.source.tag -ne [string]$expected.source.tag -or
    [string]$manifest.source.commit -ne [string]$expected.source.commit) {
    throw 'Public release manifest source identity does not match tracked provenance.'
}
if ([string]$publicProvenance.source.commit -ne [string]$expected.source.commit -or
    [string]$publicProvenance.source.tag -ne [string]$expected.source.tag) {
    throw 'Published provenance does not match tracked source identity.'
}

$publishedArtifacts = @(
    @{ Channel = $manifest.channels.stable.fire_tv; Id = 'amazon_tv_apk' },
    @{ Channel = $manifest.channels.stable.windows; Id = 'windows_msi' }
)
foreach ($published in $publishedArtifacts) {
    $artifact = @($expected.artifacts | Where-Object { $_.id -eq $published.Id })[0]
    if ([string]$published.Channel.version -ne [string]$expected.version -or
        [string]$published.Channel.sha256 -ne [string]$artifact.sha256 -or
        [long]$published.Channel.size_bytes -ne [long]$artifact.bytes -or
        [string]$published.Channel.url -ne [string]$artifact.public_url) {
        throw "Published release channel '$($published.Id)' does not match tracked provenance."
    }

    $head = Get-PublicResponse ([string]$published.Channel.url) 'Head'
    $contentLength = [long]$head.Headers['Content-Length']
    if ($contentLength -ne [long]$artifact.bytes) {
        throw "Published artifact '$($published.Id)' length is $contentLength; expected $($artifact.bytes)."
    }
    $sidecar = (Get-PublicResponse ([string]$published.Channel.sha256_url)).Content.Trim().ToLowerInvariant()
    if (-not $sidecar.StartsWith(([string]$artifact.sha256).ToLowerInvariant())) {
        throw "Published checksum sidecar does not match '$($published.Id)'."
    }
}

$appcast = (Get-PublicResponse "$apiBase/releases/appcast.xml").Content
$windowsArtifact = @($expected.artifacts | Where-Object { $_.id -eq 'windows_msi' })[0]
foreach ($appcastMarker in @(
    ('sparkle:version="' + $expected.version + '"'),
    ('sparkle:installerSha256="' + $windowsArtifact.sha256 + '"'),
    [string]$windowsArtifact.public_url
)) {
    if ($appcast.IndexOf($appcastMarker, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "Desktop appcast is missing '$appcastMarker'."
    }
}

$health = (Get-PublicResponse "$apiBase/health").Content | ConvertFrom-Json
if ([string]$health.status -notin @('ok', 'healthy')) {
    throw "Public API health response is not healthy: '$($health.status)'."
}

$reportDirectory = Join-Path $repoRoot 'build\reports\public-deployment'
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$report = [ordered]@{
    checkedUtc = [DateTime]::UtcNow.ToString('o')
    passed = $true
    version = [string]$expected.version
    sourceCommit = [string]$expected.source.commit
    checkedPageCount = $checkedUrls.Count
    releaseManifest = "$siteBase/downloads/releases.json"
    desktopAppcast = "$apiBase/releases/appcast.xml"
}
[IO.File]::WriteAllText(
    (Join-Path $reportDirectory 'summary.json'),
    ($report | ConvertTo-Json -Depth 5) + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false)
)

Write-Output "Verified $($checkedUrls.Count) public pages, account recovery, Connections, release downloads, checksums, appcast, provenance, and API health for Torve $($expected.version)."
