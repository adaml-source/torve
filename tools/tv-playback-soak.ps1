param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [int]$DurationSeconds = 7200,
    [int]$SampleIntervalSeconds = 30,
    [string]$PackageName = "com.torve.app.amazon",
    [string]$OutputDirectory = "build/reports/tv-soak",
    [switch]$RequireForeground
)

$ErrorActionPreference = "Stop"
$state = (& adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($state -ne "device") {
    throw "ADB device $Serial is not connected."
}
if ($PackageName.EndsWith(".debug", [StringComparison]::OrdinalIgnoreCase)) {
    throw "TV soak tests refuse debug package names."
}

$packageDump = (& adb -s $Serial shell dumpsys package $PackageName 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $packageDump -notmatch '(?m)^\s*versionName=') {
    throw "Package '$PackageName' is not installed on '$Serial'."
}
$versionName = [regex]::Match($packageDump, '(?m)^\s*versionName=(?<version>\S+)').Groups['version'].Value
$flags = [regex]::Match($packageDump, '(?m)^\s*flags=\[\s*(?<flags>[^\]]*)\]').Groups['flags'].Value
if ($flags -match '(^|\s)(DEBUGGABLE|TEST_ONLY)(\s|$)') {
    throw "Installed package '$PackageName' is debug/test-only; refusing TV soak."
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
$reportDirectory = Join-Path $OutputDirectory "$safeSerial-$stamp"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$samplesPath = Join-Path $reportDirectory "samples.jsonl"
$failuresPath = Join-Path $reportDirectory "failures.txt"
$logcatPath = Join-Path $reportDirectory "logcat.txt"

& adb -s $Serial logcat -c | Out-Null
$startedAt = [DateTimeOffset]::UtcNow
$deadline = $startedAt.AddSeconds($DurationSeconds)
$escapedPackageName = [regex]::Escape($PackageName)
$processFailurePattern = "FATAL EXCEPTION|Fatal signal|OutOfMemoryError"
$systemFailurePattern = "ANR in $escapedPackageName|am_crash.*$escapedPackageName|am_anr.*$escapedPackageName"
$failurePattern = "$processFailurePattern|$systemFailurePattern"
$processDeathDetected = $false
$foregroundLossDetected = $false
$sampleCount = 0

while ([DateTimeOffset]::UtcNow -lt $deadline) {
    $now = [DateTimeOffset]::UtcNow
    # $PID is a read-only automatic PowerShell variable (names are case-insensitive).
    $appProcessId = (& adb -s $Serial shell pidof $PackageName 2>&1 | Out-String).Trim()
    $activity = (& adb -s $Serial shell dumpsys activity activities 2>&1 |
        Select-String -Pattern "mResumedActivity|topResumedActivity" |
        Select-Object -First 1 | Out-String).Trim()
    $memory = (& adb -s $Serial shell dumpsys meminfo $PackageName 2>&1 |
        # Fire OS reports this row as `TOTAL:` while newer Android builds may
        # label it `TOTAL PSS:`. Accept both without depending on column widths.
        Select-String -Pattern '^\s*TOTAL(?:\s+PSS)?:' |
        Select-Object -First 1 | Out-String).Trim()
    $processFailures = if (-not [string]::IsNullOrWhiteSpace($appProcessId)) {
        (& adb -s $Serial logcat -d -v brief -t 800 --pid=$appProcessId 2>&1 |
            Select-String -Pattern $processFailurePattern | Out-String).Trim()
    } else {
        ""
    }
    $systemFailures = (& adb -s $Serial logcat -d -v brief -t 800 2>&1 |
        Select-String -Pattern $systemFailurePattern | Out-String).Trim()
    $recentFailures = @($processFailures, $systemFailures) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Out-String
    $recentFailures = $recentFailures.Trim()
    $isExpectedForeground = $activity -match [regex]::Escape($PackageName)

    [ordered]@{
        timestampUtc = $now.ToString("o")
        elapsedSeconds = [math]::Round(($now - $startedAt).TotalSeconds)
        processAlive = -not [string]::IsNullOrWhiteSpace($appProcessId)
        expectedForeground = $isExpectedForeground
        pid = $appProcessId
        resumedActivity = $activity
        totalPss = $memory
        failureDetected = -not [string]::IsNullOrWhiteSpace($recentFailures)
    } | ConvertTo-Json -Compress | Add-Content -Path $samplesPath

    $sampleCount++
    if ([string]::IsNullOrWhiteSpace($appProcessId)) {
        $processDeathDetected = $true
    }
    if ($RequireForeground -and -not $isExpectedForeground) {
        $foregroundLossDetected = $true
    }

    if (-not [string]::IsNullOrWhiteSpace($recentFailures)) {
        Add-Content -Path $failuresPath -Value "[$($now.ToString("o"))]"
        Add-Content -Path $failuresPath -Value $recentFailures
        Add-Content -Path $failuresPath -Value ""
    }

    Start-Sleep -Seconds $SampleIntervalSeconds
}

& adb -s $Serial logcat -d -v threadtime | Set-Content -Path $logcatPath
$failureCount = if (Test-Path $failuresPath) {
    (Select-String -Path $failuresPath -Pattern $failurePattern).Count
} else {
    0
}

[ordered]@{
    serial = $Serial
    packageName = $PackageName
    versionName = $versionName
    releaseOnly = $true
    startedAtUtc = $startedAt.ToString("o")
    completedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
    requestedDurationSeconds = $DurationSeconds
    sampleCount = $sampleCount
    failureCount = $failureCount
    processDeathDetected = $processDeathDetected
    requireForeground = [bool]$RequireForeground
    foregroundLossDetected = $foregroundLossDetected
    passed = $failureCount -eq 0 -and -not $processDeathDetected -and -not $foregroundLossDetected
} | ConvertTo-Json | Set-Content -Path (Join-Path $reportDirectory "summary.json")

Write-Output "TV soak report: $reportDirectory"
if ($failureCount -gt 0 -or $processDeathDetected -or $foregroundLossDetected) {
    exit 1
}
