param(
    [string[]]$ArtifactPath,
    [int64]$MinimumLoadAlignment = 16384,
    [string[]]$Required16KbAbis = @('arm64-v8a', 'x86_64')
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-ProtobufVarint {
    param([byte[]]$Bytes, [ref]$Offset)
    [uint64]$value = 0
    $shift = 0
    while ($Offset.Value -lt $Bytes.Length -and $shift -lt 64) {
        $current = $Bytes[$Offset.Value]
        $Offset.Value++
        $value = $value -bor ([uint64]($current -band 0x7f) -shl $shift)
        if (($current -band 0x80) -eq 0) {
            return $value
        }
        $shift += 7
    }
    throw 'Invalid protobuf varint.'
}

function Get-ProtobufField {
    param([byte[]]$Bytes, [int]$FieldNumber, [int]$WireType)
    $offset = 0
    while ($offset -lt $Bytes.Length) {
        $key = Read-ProtobufVarint $Bytes ([ref]$offset)
        $currentField = [int]($key -shr 3)
        $currentWireType = [int]($key -band 7)
        if ($currentWireType -eq 0) {
            $value = Read-ProtobufVarint $Bytes ([ref]$offset)
            if ($currentField -eq $FieldNumber -and $currentWireType -eq $WireType) {
                return $value
            }
        } elseif ($currentWireType -eq 1) {
            $offset += 8
        } elseif ($currentWireType -eq 2) {
            $length = Read-ProtobufVarint $Bytes ([ref]$offset)
            if ($offset + $length -gt $Bytes.Length) {
                throw 'Invalid length-delimited protobuf field.'
            }
            [byte[]]$value = if ($length -eq 0) { @() } else { $Bytes[$offset..($offset + $length - 1)] }
            $offset += $length
            if ($currentField -eq $FieldNumber -and $currentWireType -eq $WireType) {
                return $value
            }
        } elseif ($currentWireType -eq 5) {
            $offset += 4
        } else {
            throw "Unsupported protobuf wire type '$currentWireType'."
        }
    }
    return $null
}

function Read-UnsignedInteger {
    param(
        [byte[]]$Bytes,
        [int64]$Offset,
        [int]$Size,
        [bool]$LittleEndian
    )
    if ($Offset -lt 0 -or $Offset + $Size -gt $Bytes.LongLength) {
        throw "ELF field at offset $Offset with size $Size is outside the file."
    }
    [byte[]]$field = $Bytes[$Offset..($Offset + $Size - 1)]
    if ([BitConverter]::IsLittleEndian -ne $LittleEndian) {
        [Array]::Reverse($field)
    }
    switch ($Size) {
        2 { return [uint64][BitConverter]::ToUInt16($field, 0) }
        4 { return [uint64][BitConverter]::ToUInt32($field, 0) }
        8 { return [BitConverter]::ToUInt64($field, 0) }
        default { throw "Unsupported integer width: $Size" }
    }
}

function Get-ElfLoadAlignments {
    param([byte[]]$Bytes, [string]$EntryName)

    if ($Bytes.Length -lt 52 -or $Bytes[0] -ne 0x7f -or $Bytes[1] -ne 0x45 -or $Bytes[2] -ne 0x4c -or $Bytes[3] -ne 0x46) {
        throw "$EntryName is not a valid ELF file."
    }
    $elfClass = $Bytes[4]
    $littleEndian = switch ($Bytes[5]) {
        1 { $true }
        2 { $false }
        default { throw "$EntryName has an unsupported ELF byte order." }
    }

    if ($elfClass -eq 1) {
        $programHeaderOffset = Read-UnsignedInteger $Bytes 0x1c 4 $littleEndian
        $programHeaderEntrySize = Read-UnsignedInteger $Bytes 0x2a 2 $littleEndian
        $programHeaderCount = Read-UnsignedInteger $Bytes 0x2c 2 $littleEndian
        $alignmentOffset = 0x1c
        $alignmentSize = 4
    } elseif ($elfClass -eq 2) {
        $programHeaderOffset = Read-UnsignedInteger $Bytes 0x20 8 $littleEndian
        $programHeaderEntrySize = Read-UnsignedInteger $Bytes 0x36 2 $littleEndian
        $programHeaderCount = Read-UnsignedInteger $Bytes 0x38 2 $littleEndian
        $alignmentOffset = 0x30
        $alignmentSize = 8
    } else {
        throw "$EntryName has unsupported ELF class '$elfClass'."
    }

    $loadAlignments = @()
    for ($index = 0; $index -lt $programHeaderCount; $index++) {
        $headerOffset = [int64]$programHeaderOffset + ([int64]$index * [int64]$programHeaderEntrySize)
        $type = Read-UnsignedInteger $Bytes $headerOffset 4 $littleEndian
        if ($type -eq 1) {
            $loadAlignments += Read-UnsignedInteger $Bytes ($headerOffset + $alignmentOffset) $alignmentSize $littleEndian
        }
    }
    if ($loadAlignments.Count -eq 0) {
        throw "$EntryName contains no ELF PT_LOAD program headers."
    }
    return $loadAlignments
}

function Resolve-AndroidSdkRoot {
    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return $candidate
        }
    }
    $localProperties = Join-Path $repoRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($null -ne $sdkLine) {
            $candidate = $sdkLine.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
            if (Test-Path -LiteralPath $candidate -PathType Container) {
                return $candidate
            }
        }
    }
    throw 'Android SDK root was not found through ANDROID_SDK_ROOT, ANDROID_HOME, or local.properties.'
}

if ($null -eq $ArtifactPath -or $ArtifactPath.Count -eq 0) {
    $patterns = @(
        'androidApp\build\outputs\apk\amazonTv\release\*.apk',
        'androidApp\build\outputs\bundle\googleTvRelease\*.aab',
        'androidApp\build\outputs\bundle\googleMobileRelease\*.aab'
    )
    $ArtifactPath = @()
    foreach ($pattern in $patterns) {
        $candidate = Get-ChildItem -Path (Join-Path $repoRoot $pattern) -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
        if ($null -eq $candidate) {
            throw "Missing Android release artifact matching '$pattern'."
        }
        $ArtifactPath += $candidate.FullName
    }
}

$sdkRoot = Resolve-AndroidSdkRoot
$zipalign = Get-ChildItem -Path (Join-Path $sdkRoot 'build-tools') -Recurse -Filter 'zipalign.exe' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '-rc\d*\\' } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if ($null -eq $zipalign) {
    throw "zipalign.exe was not found under '$sdkRoot\build-tools'."
}

$artifactResults = @()
foreach ($rawPath in $ArtifactPath) {
    $resolvedPath = (Resolve-Path -LiteralPath $rawPath).Path
    if ([IO.Path]::GetExtension($resolvedPath) -eq '.apk') {
        & $zipalign.FullName -c -P 16 -v 4 $resolvedPath *> $null
        if ($LASTEXITCODE -ne 0) {
            throw "16 KB ZIP alignment failed for '$resolvedPath'."
        }
    }

    $archive = [IO.Compression.ZipFile]::OpenRead($resolvedPath)
    try {
        $bundlePageAlignmentVerified = $false
        if ([IO.Path]::GetExtension($resolvedPath) -eq '.aab') {
            $bundleConfigEntry = $archive.GetEntry('BundleConfig.pb')
            if ($null -eq $bundleConfigEntry) {
                throw "AAB '$resolvedPath' does not contain BundleConfig.pb."
            }
            $configStream = $bundleConfigEntry.Open()
            $configMemory = [IO.MemoryStream]::new()
            try {
                $configStream.CopyTo($configMemory)
                $bundleConfig = $configMemory.ToArray()
            }
            finally {
                $configMemory.Dispose()
                $configStream.Dispose()
            }
            # android.bundle.BundleConfig.optimizations = field 2;
            # Optimizations.uncompress_native_libraries = field 2;
            # UncompressNativeLibraries.alignment = field 2, where enum 2
            # is PAGE_ALIGNMENT_16K.
            [byte[]]$optimizations = Get-ProtobufField $bundleConfig 2 2
            [byte[]]$nativeLibraryConfig = Get-ProtobufField $optimizations 2 2
            $pageAlignment = Get-ProtobufField $nativeLibraryConfig 2 0
            if ($pageAlignment -ne 2) {
                throw "AAB '$resolvedPath' requests native-library page alignment enum '$pageAlignment' instead of PAGE_ALIGNMENT_16K."
            }
            $bundlePageAlignmentVerified = $true
        }

        $nativeEntries = @($archive.Entries | Where-Object { $_.FullName -match '^(?:base/)?lib/(?<abi>[^/]+)/[^/]+\.so$' })
        if ($nativeEntries.Count -eq 0) {
            throw "No packaged native libraries were found in '$resolvedPath'."
        }
        $abis = @($nativeEntries | ForEach-Object { [regex]::Match($_.FullName, '^(?:base/)?lib/(?<abi>[^/]+)/').Groups['abi'].Value } | Sort-Object -Unique)
        foreach ($requiredAbi in @('arm64-v8a', 'armeabi-v7a')) {
            if ($requiredAbi -notin $abis) {
                throw "Artifact '$resolvedPath' is missing required ABI '$requiredAbi'."
            }
        }

        $minimumAlignmentByAbi = @{}
        foreach ($entry in $nativeEntries) {
            $entryAbi = [regex]::Match($entry.FullName, '^(?:base/)?lib/(?<abi>[^/]+)/').Groups['abi'].Value
            $stream = $entry.Open()
            $memory = [IO.MemoryStream]::new()
            try {
                $stream.CopyTo($memory)
                $alignments = @(Get-ElfLoadAlignments $memory.ToArray() $entry.FullName)
            }
            finally {
                $memory.Dispose()
                $stream.Dispose()
            }
            $entryMinimum = ($alignments | Measure-Object -Minimum).Minimum
            if (-not $minimumAlignmentByAbi.ContainsKey($entryAbi) -or $entryMinimum -lt $minimumAlignmentByAbi[$entryAbi]) {
                $minimumAlignmentByAbi[$entryAbi] = $entryMinimum
            }
            $inadequate = @($alignments | Where-Object { $_ -lt $MinimumLoadAlignment })
            # Android's 16 KB runtime environments are 64-bit ARM/x86. Keep
            # auditing 32-bit libraries for ABI completeness, but do not
            # reject their normal 4 KB ELF alignment.
            if ($entryAbi -in $Required16KbAbis -and $inadequate.Count -gt 0) {
                $hexValues = $inadequate | ForEach-Object { '0x{0:x}' -f $_ }
                throw "Native library '$($entry.FullName)' in '$resolvedPath' has PT_LOAD alignment below $MinimumLoadAlignment bytes: $($hexValues -join ', ')."
            }
        }

        $artifactResults += [ordered]@{
            path = $resolvedPath.Substring($repoRoot.Length + 1)
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedPath).Hash.ToLowerInvariant()
            abis = $abis
            nativeLibraryCount = $nativeEntries.Count
            apkZipAlignmentVerified = [IO.Path]::GetExtension($resolvedPath) -eq '.apk'
            bundlePageAlignmentVerified = $bundlePageAlignmentVerified
            minimumElfLoadAlignment = $MinimumLoadAlignment
            observedMinimumLoadAlignmentByAbi = $minimumAlignmentByAbi
        }
    }
    finally {
        $archive.Dispose()
    }
}

$reportDirectory = Join-Path $repoRoot 'build\reports\android-16kb'
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$report = [ordered]@{
    checkedUtc = [DateTime]::UtcNow.ToString('o')
    passed = $true
    artifacts = $artifactResults
}
[System.IO.File]::WriteAllText(
    (Join-Path $reportDirectory 'summary.json'),
    ($report | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false)
)

Write-Output "Verified Android 16 KB packaging and ELF alignment for $($artifactResults.Count) release artifact(s)."
