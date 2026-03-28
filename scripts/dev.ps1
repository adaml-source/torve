[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        "android-google-mobile-debug",
        "android-google-tv-debug",
        "android-amazon-tv-debug",
        "android-google-mobile-release",
        "android-google-tv-release",
        "android-amazon-tv-release",
        "android-release-all",
        "backend-up",
        "backend-down",
        "backend-migrate",
        "backend-test",
        "backend-local",
        "dev-google-mobile",
        "dev-google-tv",
        "dev-amazon-tv"
    )]
    [string]$Target,
    [switch]$BuildImages,
    [switch]$SkipCrashlyticsUploads,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$serverDir = Join-Path $repoRoot "server"
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"

function Format-Command {
    param(
        [string]$FilePath,
        [string[]]$Arguments
    )

    $parts = @($FilePath) + $Arguments
    return ($parts | ForEach-Object {
            if ($_ -match "\s") {
                '"' + $_ + '"'
            }
            else {
                $_
            }
        }) -join " "
}

function Invoke-Step {
    param(
        [string]$Label,
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    Write-Host ""
    Write-Host "[$Label] $(Format-Command -FilePath $FilePath -Arguments $Arguments)"
    Write-Host "  cwd: $WorkingDirectory"

    if ($DryRun) {
        return
    }

    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Label failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

function Get-AndroidReleaseArgs {
    param([string[]]$Tasks)

    $args = @($Tasks)
    if (-not $SkipCrashlyticsUploads) {
        return $args
    }

    if ($Tasks -contains ":androidApp:bundleGoogleMobileRelease") {
        $args += "-x"
        $args += ":androidApp:uploadCrashlyticsMappingFileGoogleMobileRelease"
    }

    if ($Tasks -contains ":androidApp:bundleGoogleTvRelease") {
        $args += "-x"
        $args += ":androidApp:uploadCrashlyticsMappingFileGoogleTvRelease"
    }

    return $args
}

function Invoke-AndroidGradle {
    param(
        [string]$Label,
        [string[]]$Tasks
    )

    Invoke-Step -Label $Label -FilePath $gradleWrapper -Arguments (Get-AndroidReleaseArgs -Tasks $Tasks) -WorkingDirectory $repoRoot
}

function Invoke-BackendUp {
    $args = @("compose", "up", "-d")
    if ($BuildImages) {
        $args += "--build"
    }
    $args += @("postgres", "redis", "api")
    Invoke-Step -Label "backend-up" -FilePath "docker" -Arguments $args -WorkingDirectory $serverDir
}

switch ($Target) {
    "android-google-mobile-debug" {
        Invoke-AndroidGradle -Label $Target -Tasks @(":androidApp:assembleGoogleMobileDebug")
    }
    "android-google-tv-debug" {
        Invoke-AndroidGradle -Label $Target -Tasks @(":androidApp:assembleGoogleTvDebug")
    }
    "android-amazon-tv-debug" {
        Invoke-AndroidGradle -Label $Target -Tasks @(":androidApp:assembleAmazonTvDebug")
    }
    "android-google-mobile-release" {
        Invoke-AndroidGradle -Label $Target -Tasks @(":androidApp:bundleGoogleMobileRelease")
    }
    "android-google-tv-release" {
        Invoke-AndroidGradle -Label $Target -Tasks @(":androidApp:bundleGoogleTvRelease")
    }
    "android-amazon-tv-release" {
        Invoke-AndroidGradle -Label $Target -Tasks @(":androidApp:assembleAmazonTvRelease")
    }
    "android-release-all" {
        Invoke-AndroidGradle -Label "android-google-mobile-release" -Tasks @(":androidApp:bundleGoogleMobileRelease")
        Invoke-AndroidGradle -Label "android-google-tv-release" -Tasks @(":androidApp:bundleGoogleTvRelease")
        Invoke-AndroidGradle -Label "android-amazon-tv-release" -Tasks @(":androidApp:assembleAmazonTvRelease")
    }
    "backend-up" {
        Invoke-BackendUp
    }
    "backend-down" {
        Invoke-Step -Label $Target -FilePath "docker" -Arguments @("compose", "down") -WorkingDirectory $serverDir
    }
    "backend-migrate" {
        Invoke-Step -Label $Target -FilePath "docker" -Arguments @("compose", "exec", "api", "alembic", "upgrade", "head") -WorkingDirectory $serverDir
    }
    "backend-test" {
        Invoke-Step -Label $Target -FilePath "python" -Arguments @("-m", "pytest", "tests", "-q") -WorkingDirectory $serverDir
    }
    "backend-local" {
        Invoke-Step -Label $Target -FilePath "python" -Arguments @("-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080") -WorkingDirectory $serverDir
    }
    "dev-google-mobile" {
        Invoke-BackendUp
        Invoke-AndroidGradle -Label "android-google-mobile-debug" -Tasks @(":androidApp:assembleGoogleMobileDebug")
    }
    "dev-google-tv" {
        Invoke-BackendUp
        Invoke-AndroidGradle -Label "android-google-tv-debug" -Tasks @(":androidApp:assembleGoogleTvDebug")
    }
    "dev-amazon-tv" {
        Invoke-BackendUp
        Invoke-AndroidGradle -Label "android-amazon-tv-debug" -Tasks @(":androidApp:assembleAmazonTvDebug")
    }
    default {
        throw "Unsupported target: $Target"
    }
}
