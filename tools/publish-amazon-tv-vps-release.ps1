param(
    [string]$ApkPath = "androidApp\build\outputs\apk\amazonTv\release\androidApp-amazon-tv-release.apk",
    [string]$ManifestPath = ".codex-deploy\releases.json",
    [string]$SshHost = "torve-vps"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$resolvedApk = (Resolve-Path -LiteralPath (Join-Path $repoRoot $ApkPath)).Path
$resolvedManifest = (Resolve-Path -LiteralPath (Join-Path $repoRoot $ManifestPath)).Path
$outputMetadataPath = Join-Path (Split-Path -Parent $resolvedApk) "output-metadata.json"
if (-not (Test-Path -LiteralPath $outputMetadataPath)) {
    throw "Android output metadata is missing beside the APK: $outputMetadataPath"
}

$outputMetadata = Get-Content -Raw -Encoding utf8 $outputMetadataPath | ConvertFrom-Json
$apkName = Split-Path -Leaf $resolvedApk
$artifactMetadata = @($outputMetadata.elements) |
    Where-Object { $_.outputFile -eq $apkName } |
    Select-Object -First 1
if ($null -eq $artifactMetadata -or [string]::IsNullOrWhiteSpace([string]$artifactMetadata.versionName)) {
    throw "Could not determine the signed APK version from output-metadata.json."
}

$version = [string]$artifactMetadata.versionName
$versionCode = [long]$artifactMetadata.versionCode
if ($version.EndsWith("-debug", [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to publish a debug APK."
}
if ($versionCode -le 0) {
    throw "Could not determine a positive signed APK versionCode from output-metadata.json."
}

function Resolve-AndroidBuildTool([string]$ToolName) {
    $sdkRoots = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Container) }
    foreach ($sdkRoot in $sdkRoots) {
        $buildToolsRoot = Join-Path $sdkRoot "build-tools"
        if (-not (Test-Path -LiteralPath $buildToolsRoot -PathType Container)) { continue }
        foreach ($versionDirectory in @(Get-ChildItem -LiteralPath $buildToolsRoot -Directory | Sort-Object Name -Descending)) {
            foreach ($extension in @('.bat', '.exe', '')) {
                $candidate = Join-Path $versionDirectory.FullName ($ToolName + $extension)
                if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
            }
        }
    }
    throw "Android build tool '$ToolName' was not found."
}

$aapt = Resolve-AndroidBuildTool "aapt"
$apkSigner = Resolve-AndroidBuildTool "apksigner"
$badging = (& $aapt dump badging $resolvedApk 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "The release APK is not readable by aapt.`n$badging" }
$packageMatch = [regex]::Match($badging, "(?m)^package: name='(?<package>[^']+)' versionCode='(?<code>\d+)' versionName='(?<name>[^']+)'" )
if (-not $packageMatch.Success) { throw "Could not read package/version metadata from the release APK." }
if ($packageMatch.Groups['package'].Value -ne 'com.torve.app.amazon') {
    throw "Release APK package is '$($packageMatch.Groups['package'].Value)', expected 'com.torve.app.amazon'."
}
if ([long]$packageMatch.Groups['code'].Value -ne $versionCode -or $packageMatch.Groups['name'].Value -ne $version) {
    throw "APK package metadata does not match output-metadata.json."
}
$signingOutput = (& $apkSigner verify --verbose --print-certs $resolvedApk 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "Release APK signature verification failed.`n$signingOutput" }
$signerMatch = [regex]::Match($signingOutput, '(?im)^Signer #1 certificate SHA-256 digest:\s*(?<sha>[0-9a-f]{64})\s*$')
if (-not $signerMatch.Success) { throw "Could not read the release signing certificate SHA-256 fingerprint." }
$releaseSignerSha256 = $signerMatch.Groups['sha'].Value.ToLowerInvariant()

function Compare-Version([string]$Candidate, [string]$Existing) {
    $candidateParts = @($Candidate.Split('.') | ForEach-Object { [int]($_ -replace '[^0-9].*$', '') })
    $existingParts = @($Existing.Split('.') | ForEach-Object { [int]($_ -replace '[^0-9].*$', '') })
    $width = [Math]::Max($candidateParts.Count, $existingParts.Count)
    for ($index = 0; $index -lt $width; $index++) {
        $candidatePart = if ($index -lt $candidateParts.Count) { $candidateParts[$index] } else { 0 }
        $existingPart = if ($index -lt $existingParts.Count) { $existingParts[$index] } else { 0 }
        if ($candidatePart -gt $existingPart) { return 1 }
        if ($candidatePart -lt $existingPart) { return -1 }
    }
    return 0
}

$remoteManifestJson = & ssh $SshHost "cat /opt/torve/downloads/releases.json"
if ($LASTEXITCODE -ne 0) {
    throw "Could not read the currently published VPS release manifest."
}
$remoteManifest = ($remoteManifestJson -join [Environment]::NewLine) | ConvertFrom-Json
$publishedVersion = [string]$remoteManifest.channels.stable.fire_tv.version
$publishedVersionCode = if ($null -ne $remoteManifest.channels.stable.fire_tv.version_code) {
    [long]$remoteManifest.channels.stable.fire_tv.version_code
} else {
    0L
}
$publishedProvenancePath = Join-Path $repoRoot "release\provenance\$publishedVersion.json"
if (-not (Test-Path -LiteralPath $publishedProvenancePath -PathType Leaf)) {
    throw "Cannot verify signing continuity because $publishedProvenancePath is missing."
}
$publishedProvenance = Get-Content -Raw -Encoding utf8 -LiteralPath $publishedProvenancePath | ConvertFrom-Json
$publishedAmazonArtifact = @($publishedProvenance.artifacts | Where-Object { $_.id -eq 'amazon_tv_apk' })[0]
$expectedSignerSha256 = ([string]$publishedAmazonArtifact.signer_certificate_sha256).ToLowerInvariant()
if ($expectedSignerSha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Published provenance has no valid Amazon TV signer fingerprint."
}
if ($releaseSignerSha256 -ne $expectedSignerSha256) {
    throw "Release signer '$releaseSignerSha256' does not match the installed-channel signer '$expectedSignerSha256'."
}
$versionComparison = Compare-Version $version $publishedVersion
if ($versionComparison -lt 0) {
    throw "Refusing to replace published Fire TV $publishedVersion with older version $version."
}
if ($versionComparison -eq 0 -and $publishedVersionCode -gt 0L -and $versionCode -le $publishedVersionCode) {
    throw "Refusing to replace published Fire TV $publishedVersion build $publishedVersionCode with non-newer build $versionCode."
}
if ($publishedVersionCode -gt 0L -and $versionCode -le $publishedVersionCode) {
    throw "Refusing to publish Android versionCode $versionCode over installed channel versionCode $publishedVersionCode."
}

$publishedFile = "torve-android-tv-$version-$versionCode-$([DateTime]::UtcNow.ToString('yyyyMMdd')).apk"
$publishedUrl = "https://torve.app/downloads/android/$publishedFile"
$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedApk).Hash.ToLowerInvariant()
$sizeBytes = (Get-Item -LiteralPath $resolvedApk).Length
$candidateProvenancePath = Join-Path $repoRoot "release\provenance\$version.json"
if (-not (Test-Path -LiteralPath $candidateProvenancePath -PathType Leaf)) {
    throw "Candidate release provenance is missing: $candidateProvenancePath"
}
$candidateProvenance = Get-Content -Raw -Encoding utf8 -LiteralPath $candidateProvenancePath | ConvertFrom-Json
$candidateArtifact = @($candidateProvenance.artifacts | Where-Object { $_.id -eq 'amazon_tv_apk' })[0]
if (
    [string]$candidateProvenance.version -ne $version -or
    [long]$candidateArtifact.version_code -ne $versionCode -or
    [long]$candidateArtifact.bytes -ne $sizeBytes -or
    ([string]$candidateArtifact.sha256).ToLowerInvariant() -ne $sha256 -or
    ([string]$candidateArtifact.signer_certificate_sha256).ToLowerInvariant() -ne $releaseSignerSha256 -or
    [string]$candidateArtifact.public_url -ne $publishedUrl
) {
    throw "Candidate provenance does not describe the exact signed APK being published."
}
$sourceCommit = ([string]$candidateProvenance.source.commit).ToLowerInvariant()
$sourceTag = [string]$candidateProvenance.source.tag
$sourceTagUrl = [string]$candidateProvenance.source.tag_url
$tagCommit = (& git rev-list -n 1 $sourceTag 2>$null | Out-String).Trim().ToLowerInvariant()
if ($sourceCommit -notmatch '^[0-9a-f]{40}$' -or $tagCommit -ne $sourceCommit) {
    throw "Candidate provenance source tag '$sourceTag' does not resolve to '$sourceCommit'."
}
$publishedProvenanceFile = "torve-$version.json"
$publishedProvenanceUrl = "https://torve.app/downloads/provenance/$publishedProvenanceFile"
$provenanceSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $candidateProvenancePath).Hash.ToLowerInvariant()
$tempDirectory = Join-Path ([IO.Path]::GetTempPath()) "torve-release-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $tempDirectory | Out-Null

try {
    $stagedApk = Join-Path $tempDirectory $publishedFile
    $stagedChecksum = "$stagedApk.sha256"
    $stagedManifest = Join-Path $tempDirectory "releases.json"
    $stagedProvenance = Join-Path $tempDirectory $publishedProvenanceFile
    Copy-Item -LiteralPath $resolvedApk -Destination $stagedApk
    Copy-Item -LiteralPath $candidateProvenancePath -Destination $stagedProvenance
    [IO.File]::WriteAllText(
        $stagedChecksum,
        "$sha256  $publishedFile$([Environment]::NewLine)",
        [Text.UTF8Encoding]::new($false)
    )

    $manifest = Get-Content -Raw -Encoding utf8 $resolvedManifest | ConvertFrom-Json
    $manifest.updated_at = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $manifest.source.version = $version
    $manifest.source.tag = $sourceTag
    $manifest.source.commit = $sourceCommit
    $manifest.source.url = $sourceTagUrl
    $manifest.source.provenance_url = $publishedProvenanceUrl
    foreach ($platform in @("android_tv", "fire_tv")) {
        $entry = $manifest.channels.stable.$platform
        $entry.version = $version
        if ($entry.PSObject.Properties.Name -contains "version_code") {
            $entry.version_code = $versionCode
        } else {
            $entry | Add-Member -NotePropertyName version_code -NotePropertyValue $versionCode
        }
        $entry.file = $publishedFile
        $entry.url = $publishedUrl
        $entry.sha256 = $sha256
        $entry.sha256_url = "$publishedUrl.sha256"
        $entry.size_bytes = $sizeBytes
        $entry.status = "available"
    }
    [IO.File]::WriteAllText(
        $stagedManifest,
        ($manifest | ConvertTo-Json -Depth 12) + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false)
    )

    # Artifacts become reachable first. The manifest is moved into place last,
    # so clients can never observe a release whose APK is only partly uploaded.
    & scp $stagedApk "${SshHost}:/opt/torve/downloads/android/$publishedFile.uploading"
    if ($LASTEXITCODE -ne 0) { throw "APK upload failed." }
    & scp $stagedChecksum "${SshHost}:/opt/torve/downloads/android/$publishedFile.sha256.uploading"
    if ($LASTEXITCODE -ne 0) { throw "Checksum upload failed." }
    & scp $stagedManifest "${SshHost}:/opt/torve/downloads/releases.json.uploading"
    if ($LASTEXITCODE -ne 0) { throw "Manifest upload failed." }
    & scp $stagedProvenance "${SshHost}:/opt/torve/downloads/provenance/$publishedProvenanceFile.uploading"
    if ($LASTEXITCODE -ne 0) { throw "Provenance upload failed." }

    $publishCommand = @(
        "set -e"
        "cd /opt/torve/downloads"
        "mv 'android/$publishedFile.uploading' 'android/$publishedFile'"
        "mv 'android/$publishedFile.sha256.uploading' 'android/$publishedFile.sha256'"
        "mv 'provenance/$publishedProvenanceFile.uploading' 'provenance/$publishedProvenanceFile'"
        "cp releases.json 'releases.json.backup.$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))'"
        "mv releases.json.uploading releases.json"
    ) -join "; "
    & ssh $SshHost $publishCommand
    if ($LASTEXITCODE -ne 0) { throw "Atomic VPS publish failed." }

    $publicHashLine = (& ssh $SshHost "curl -fsSL --max-redirs 8 --connect-timeout 15 --max-time 180 '$publishedUrl' | sha256sum" 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $publicHashLine.StartsWith($sha256, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The publicly resolved APK does not match the final signed release artifact."
    }
    $publicProvenanceHashLine = (& ssh $SshHost "curl -fsSL --max-redirs 8 --connect-timeout 15 --max-time 60 '$publishedProvenanceUrl' | sha256sum" 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $publicProvenanceHashLine.StartsWith($provenanceSha256, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The publicly resolved provenance does not match the candidate release record."
    }

    Copy-Item -LiteralPath $stagedManifest -Destination $resolvedManifest -Force
    Write-Output "Published Fire TV/Android TV $version build $versionCode to $publishedUrl and updated releases.json atomically."
}
finally {
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}
