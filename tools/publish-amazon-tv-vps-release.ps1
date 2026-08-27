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
$tempDirectory = Join-Path ([IO.Path]::GetTempPath()) "torve-release-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $tempDirectory | Out-Null

try {
    $stagedApk = Join-Path $tempDirectory $publishedFile
    $stagedChecksum = "$stagedApk.sha256"
    $stagedManifest = Join-Path $tempDirectory "releases.json"
    Copy-Item -LiteralPath $resolvedApk -Destination $stagedApk
    [IO.File]::WriteAllText(
        $stagedChecksum,
        "$sha256  $publishedFile$([Environment]::NewLine)",
        [Text.UTF8Encoding]::new($false)
    )

    $manifest = Get-Content -Raw -Encoding utf8 $resolvedManifest | ConvertFrom-Json
    $manifest.updated_at = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
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

    $publishCommand = @(
        "set -e"
        "cd /opt/torve/downloads"
        "mv 'android/$publishedFile.uploading' 'android/$publishedFile'"
        "mv 'android/$publishedFile.sha256.uploading' 'android/$publishedFile.sha256'"
        "cp releases.json 'releases.json.backup.$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))'"
        "mv releases.json.uploading releases.json"
    ) -join "; "
    & ssh $SshHost $publishCommand
    if ($LASTEXITCODE -ne 0) { throw "Atomic VPS publish failed." }

    Copy-Item -LiteralPath $stagedManifest -Destination $resolvedManifest -Force
    Write-Output "Published Fire TV/Android TV $version build $versionCode to $publishedUrl and updated releases.json atomically."
}
finally {
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}
