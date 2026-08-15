param(
    [Parameter(Mandatory)]
    [string]$Serial,
    [string]$PackageName = "com.torve.app.amazon",
    [string]$ActivityName = "com.torve.android.TvMainActivity",
    [ValidateRange(1, 20)]
    [int]$Iterations = 3,
    [string]$Label = "release"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$safeLabel = $Label -replace '[^A-Za-z0-9._-]', '_'
$reportDirectory = Join-Path $repoRoot "build\reports\tv-startup"
$reportPath = Join-Path $reportDirectory "$safeSerial-$safeLabel.json"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

$deviceState = (& adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($deviceState -ne "device") {
    throw "ADB target '$Serial' is not connected (state='$deviceState')."
}
if ($PackageName.EndsWith(".debug", [StringComparison]::OrdinalIgnoreCase)) {
    throw "Release startup probes refuse debug package names."
}

$packageDump = (& adb -s $Serial shell dumpsys package $PackageName 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $packageDump -notmatch '(?m)^\s*versionName=') {
    throw "Package '$PackageName' is not installed on '$Serial'."
}
$versionName = [regex]::Match($packageDump, '(?m)^\s*versionName=(?<version>\S+)').Groups['version'].Value
$flags = [regex]::Match($packageDump, '(?m)^\s*flags=\[\s*(?<flags>[^\]]*)\]').Groups['flags'].Value
if ($flags -match '(^|\s)(DEBUGGABLE|TEST_ONLY)(\s|$)') {
    throw "Installed package '$PackageName' is debug/test-only; refusing release measurement."
}

function Measure-ActivityLaunch([string]$Kind, [int]$Iteration) {
    $output = (& adb -s $Serial shell am start -W -n "$PackageName/$ActivityName" 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $output -notmatch '(?m)^Status:\s+ok\s*$') {
        throw "$Kind launch $Iteration failed: $output"
    }
    $metric = [ordered]@{
        kind = $Kind
        iteration = $Iteration
        thisTimeMs = $null
        totalTimeMs = $null
        waitTimeMs = $null
    }
    foreach ($name in @('ThisTime', 'TotalTime', 'WaitTime')) {
        $match = [regex]::Match($output, "(?m)^${name}:\s+(?<value>\d+)\s*$")
        if ($match.Success) {
            $property = $name.Substring(0, 1).ToLowerInvariant() + $name.Substring(1) + 'Ms'
            $metric[$property] = [int]$match.Groups['value'].Value
        }
    }
    if ($null -eq $metric.totalTimeMs) {
        throw "$Kind launch $Iteration did not report TotalTime: $output"
    }
    return [pscustomobject]$metric
}

function Get-Median([int[]]$Values) {
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) { return $sorted[$middle] }
    return [Math]::Round(($sorted[$middle - 1] + $sorted[$middle]) / 2.0, 1)
}

$samples = @()
for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
    & adb -s $Serial shell am force-stop $PackageName | Out-Null
    Start-Sleep -Milliseconds 700
    $samples += Measure-ActivityLaunch "cold" $iteration
    Start-Sleep -Milliseconds 1200
    & adb -s $Serial shell input keyevent 3 | Out-Null
    Start-Sleep -Milliseconds 700
    $samples += Measure-ActivityLaunch "warm" $iteration
    Start-Sleep -Milliseconds 1200
}

$cold = @($samples | Where-Object kind -eq 'cold' | ForEach-Object totalTimeMs)
$warm = @($samples | Where-Object kind -eq 'warm' | ForEach-Object totalTimeMs)
$report = [ordered]@{
    measuredAtUtc = [DateTime]::UtcNow.ToString("o")
    serial = $Serial
    packageName = $PackageName
    versionName = $versionName
    releaseOnly = $true
    iterations = $Iterations
    coldTotalTimeMs = $cold
    coldMedianMs = Get-Median $cold
    warmTotalTimeMs = $warm
    warmMedianMs = Get-Median $warm
    samples = $samples
}
[IO.File]::WriteAllText(
    $reportPath,
    ($report | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false)
)
Write-Output "TV release startup probe PASS: $reportPath"
Write-Output "Version $versionName cold median $($report.coldMedianMs) ms; warm median $($report.warmMedianMs) ms."
