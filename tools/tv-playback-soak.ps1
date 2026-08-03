param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [int]$DurationSeconds = 7200,
    [int]$SampleIntervalSeconds = 30,
    [string]$PackageName = "com.torve.app.amazon",
    [string]$OutputDirectory = "build/reports/tv-soak"
)

$ErrorActionPreference = "Stop"
$state = (& adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($state -ne "device") {
    throw "ADB device $Serial is not connected."
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportDirectory = Join-Path $OutputDirectory "$Serial-$stamp"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$samplesPath = Join-Path $reportDirectory "samples.jsonl"
$failuresPath = Join-Path $reportDirectory "failures.txt"
$logcatPath = Join-Path $reportDirectory "logcat.txt"

& adb -s $Serial logcat -c | Out-Null
$startedAt = [DateTimeOffset]::UtcNow
$deadline = $startedAt.AddSeconds($DurationSeconds)
$failurePattern = "FATAL EXCEPTION|ANR in $PackageName|am_crash|am_anr|Fatal signal|OutOfMemoryError"

while ([DateTimeOffset]::UtcNow -lt $deadline) {
    $now = [DateTimeOffset]::UtcNow
    $pid = (& adb -s $Serial shell pidof $PackageName 2>&1 | Out-String).Trim()
    $activity = (& adb -s $Serial shell dumpsys activity activities 2>&1 |
        Select-String -Pattern "mResumedActivity|topResumedActivity" |
        Select-Object -First 1 | Out-String).Trim()
    $memory = (& adb -s $Serial shell dumpsys meminfo $PackageName 2>&1 |
        Select-String -Pattern "TOTAL PSS" |
        Select-Object -First 1 | Out-String).Trim()
    $recentFailures = (& adb -s $Serial logcat -d -v brief -t 800 2>&1 |
        Select-String -Pattern $failurePattern | Out-String).Trim()

    [ordered]@{
        timestampUtc = $now.ToString("o")
        elapsedSeconds = [math]::Round(($now - $startedAt).TotalSeconds)
        processAlive = -not [string]::IsNullOrWhiteSpace($pid)
        pid = $pid
        resumedActivity = $activity
        totalPss = $memory
        failureDetected = -not [string]::IsNullOrWhiteSpace($recentFailures)
    } | ConvertTo-Json -Compress | Add-Content -Path $samplesPath

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
    startedAtUtc = $startedAt.ToString("o")
    completedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
    requestedDurationSeconds = $DurationSeconds
    failureCount = $failureCount
    passed = $failureCount -eq 0
} | ConvertTo-Json | Set-Content -Path (Join-Path $reportDirectory "summary.json")

Write-Output "TV soak report: $reportDirectory"
if ($failureCount -gt 0) {
    exit 1
}
