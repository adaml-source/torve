param(
    [string]$ProvenancePath = "release\provenance\1.2.0.json",
    [switch]$RequireArtifacts,
    [switch]$RequireTag
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$resolvedProvenance = if ([IO.Path]::IsPathRooted($ProvenancePath)) {
    $ProvenancePath
} else {
    Join-Path $repoRoot $ProvenancePath
}

if (-not (Test-Path -LiteralPath $resolvedProvenance -PathType Leaf)) {
    throw "Release provenance is missing: $ProvenancePath"
}

$provenance = Get-Content -Raw -Encoding utf8 -LiteralPath $resolvedProvenance | ConvertFrom-Json
if ([int]$provenance.schema_version -ne 1) {
    throw "Unsupported release provenance schema: $($provenance.schema_version)"
}

$version = [string]$provenance.version
if ($version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Release provenance version is not semantic: '$version'"
}

$sourceCommit = ([string]$provenance.source.commit).ToLowerInvariant()
if ($sourceCommit -notmatch '^[0-9a-f]{40}$') {
    throw "Release provenance source commit must be a full 40-character SHA."
}
if ([string]$provenance.source.tag -ne "v$version") {
    throw "Release provenance tag must be v$version."
}

foreach ($url in @([string]$provenance.source.repository, [string]$provenance.source.tag_url)) {
    $parsed = $null
    if (-not [Uri]::TryCreate($url, [UriKind]::Absolute, [ref]$parsed) -or $parsed.Scheme -ne 'https') {
        throw "Release provenance source URL must use HTTPS: '$url'"
    }
}

Push-Location $repoRoot
try {
    & git cat-file -e ($sourceCommit + '^{commit}')
    if ($LASTEXITCODE -ne 0) {
        throw "Release source commit is not present in this checkout: $sourceCommit"
    }

    & git merge-base --is-ancestor $sourceCommit HEAD
    if ($LASTEXITCODE -ne 0) {
        throw "Release source commit $sourceCommit is not an ancestor of HEAD."
    }

    if ($RequireTag) {
        $tagCommit = (& git rev-list -n 1 ([string]$provenance.source.tag) 2>$null | Out-String).Trim().ToLowerInvariant()
        if ($LASTEXITCODE -ne 0 -or $tagCommit -ne $sourceCommit) {
            throw "Tag $($provenance.source.tag) does not resolve to release commit $sourceCommit."
        }
    }
}
finally {
    Pop-Location
}

$artifactIds = @{}
foreach ($artifact in @($provenance.artifacts)) {
    $id = [string]$artifact.id
    if ([string]::IsNullOrWhiteSpace($id) -or $artifactIds.ContainsKey($id)) {
        throw "Release provenance artifact IDs must be non-blank and unique: '$id'"
    }
    $artifactIds[$id] = $true

    $expectedHash = ([string]$artifact.sha256).ToLowerInvariant()
    if ($expectedHash -notmatch '^[0-9a-f]{64}$') {
        throw "Artifact $id has an invalid SHA-256 value."
    }
    if ([long]$artifact.bytes -le 0) {
        throw "Artifact $id must have a positive byte length."
    }

    $publicUrl = [string]$artifact.public_url
    if (-not [string]::IsNullOrWhiteSpace($publicUrl)) {
        $parsed = $null
        if (-not [Uri]::TryCreate($publicUrl, [UriKind]::Absolute, [ref]$parsed) -or $parsed.Scheme -ne 'https') {
            throw "Artifact $id public URL must use HTTPS: '$publicUrl'"
        }
    }

    $localPath = Join-Path $repoRoot ([string]$artifact.local_path)
    if (-not (Test-Path -LiteralPath $localPath -PathType Leaf)) {
        if ($RequireArtifacts) {
            throw "Artifact $id is missing at $localPath."
        }
        continue
    }

    # Gradle's release output paths are intentionally stable and are therefore
    # overwritten by the next build. Historical records still need structural
    # and source verification, but local byte verification is meaningful only
    # for the release currently being assembled.
    if (-not $RequireArtifacts) {
        continue
    }

    $actualFile = Get-Item -LiteralPath $localPath
    if ($actualFile.Length -ne [long]$artifact.bytes) {
        throw "Artifact $id byte length mismatch: expected $($artifact.bytes), found $($actualFile.Length)."
    }
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $localPath).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) {
        throw "Artifact $id SHA-256 mismatch: expected $expectedHash, found $actualHash."
    }
}

$requiredArtifacts = if ($null -ne $provenance.required_artifacts) {
    @($provenance.required_artifacts | ForEach-Object { [string]$_ })
} else {
    # Schema-v1 releases originally shipped every client together. Keep that
    # behavior for existing records, while allowing a later hotfix provenance
    # to explicitly scope itself to independently versioned client artifacts.
    @('amazon_tv_apk', 'windows_msi', 'google_tv_aab', 'google_mobile_aab')
}
if ($requiredArtifacts.Count -eq 0 -or @($requiredArtifacts | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
    throw 'Release provenance required_artifacts must contain at least one non-blank artifact ID.'
}
if (@($requiredArtifacts | Select-Object -Unique).Count -ne $requiredArtifacts.Count) {
    throw 'Release provenance required_artifacts must be unique.'
}
$missingIds = @($requiredArtifacts | Where-Object { -not $artifactIds.ContainsKey($_) })
if ($missingIds.Count -gt 0) {
    throw "Release provenance is missing required artifacts: $($missingIds -join ', ')"
}

Write-Output "Release provenance verified for Torve $version at $sourceCommit."
