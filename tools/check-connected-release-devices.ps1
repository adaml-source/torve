param(
    [string[]]$Serial,
    [string]$ExpectedVersionName = "",
    [switch]$AllowNoDevices
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($ExpectedVersionName)) {
    $gradleFile = Join-Path $repoRoot "androidApp\build.gradle.kts"
    $versionMatch = [regex]::Match(
        (Get-Content -Raw -Encoding utf8 -LiteralPath $gradleFile),
        'versionName\s*=\s*"(?<version>[^"]+)"'
    )
    if (-not $versionMatch.Success) {
        throw "Unable to determine the Android release version from androidApp/build.gradle.kts."
    }
    $ExpectedVersionName = $versionMatch.Groups['version'].Value
}

if ($null -eq $Serial -or $Serial.Count -eq 0) {
    $Serial = @(
        & adb devices |
            Select-Object -Skip 1 |
            ForEach-Object {
                if ($_ -match '^(?<serial>\S+)\s+device(?:\s|$)') {
                    $Matches['serial']
                }
            }
    )
}

if ($Serial.Count -eq 0) {
    if ($AllowNoDevices) {
        Write-Output "No connected Android devices; release-only installation check skipped."
        exit 0
    }
    throw "No connected Android devices were found."
}

$results = @()
foreach ($deviceSerial in $Serial) {
    $state = (& adb -s $deviceSerial get-state 2>&1 | Out-String).Trim()
    if ($state -ne 'device') {
        throw "ADB target '$deviceSerial' is not available as a device (state: '$state')."
    }

    $torvePackages = @(
        & adb -s $deviceSerial shell pm list packages com.torve.app 2>&1 |
            ForEach-Object { ([string]$_).Trim() } |
            Where-Object { $_ -match '^package:com\.torve\.app' } |
            ForEach-Object { $_.Substring('package:'.Length) }
    )
    $debugPackages = @($torvePackages | Where-Object { $_ -match '(^|\.)debug($|\.)' })
    if ($debugPackages.Count -gt 0) {
        throw "Debug Torve package(s) installed on '$deviceSerial': $($debugPackages -join ', '). Remove them before release verification."
    }

    $releasePackages = @($torvePackages | Where-Object { $_ -in @('com.torve.app', 'com.torve.app.amazon') })
    if ($releasePackages.Count -eq 0) {
        throw "No Torve release package is installed on '$deviceSerial'."
    }

    foreach ($packageName in $releasePackages) {
        $packageDump = (& adb -s $deviceSerial shell dumpsys package $packageName 2>&1 | Out-String)
        if ($packageDump -match '(?im)^\s*(?:pkg)?flags=\[[^\]]*(DEBUGGABLE|TEST_ONLY)') {
            throw "Installed package '$packageName' on '$deviceSerial' has a debug/test package flag."
        }
        $installedVersion = [regex]::Match($packageDump, '(?m)^\s*versionName=(?<version>\S+)').Groups['version'].Value
        $versionCode = [regex]::Match($packageDump, '(?m)^\s*versionCode=(?<code>\d+)').Groups['code'].Value
        if ([string]::IsNullOrWhiteSpace($installedVersion)) {
            throw "Unable to read the installed version for '$packageName' on '$deviceSerial'."
        }
        if ($installedVersion -ne $ExpectedVersionName) {
            throw "Installed release '$packageName' on '$deviceSerial' is version '$installedVersion'; expected '$ExpectedVersionName'."
        }

        $results += [ordered]@{
            serial = $deviceSerial
            packageName = $packageName
            versionName = $installedVersion
            versionCode = $versionCode
            debuggable = $false
        }
    }
}

$reportDirectory = Join-Path $repoRoot 'build\reports\release-devices'
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$report = [ordered]@{
    checkedUtc = [DateTime]::UtcNow.ToString('o')
    expectedVersionName = $ExpectedVersionName
    passed = $true
    installations = $results
}
[System.IO.File]::WriteAllText(
    (Join-Path $reportDirectory 'summary.json'),
    ($report | ConvertTo-Json -Depth 6) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Output "Verified $($results.Count) Torve release installation(s) on $($Serial.Count) connected device(s); no debug package is installed."
