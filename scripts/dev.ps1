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

function Write-BackendTestSetupInstructions {
    param([string]$Reason)

    Write-Host ""
    Write-Host "backend-test setup required: $Reason" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Expected one-command path:"
    Write-Host "  ./scripts/dev.ps1 backend-test"
    Write-Host ""
    Write-Host "Prerequisites:"
    Write-Host "  1. Python 3.12+ is available as 'python'."
    Write-Host "  2. Backend dependencies are installed:"
    Write-Host "       cd server"
    Write-Host "       python -m pip install -r requirements.txt pytest pytest-asyncio"
    Write-Host "  3. Docker is running, unless you provide your own DATABASE_URL."
    Write-Host ""
    Write-Host "Manual environment fallback:"
    Write-Host "  `$env:DATABASE_URL='postgresql+psycopg2://torve:torve@localhost:5432/torve'"
    Write-Host "  `$env:JWT_SECRET='local-test-jwt-secret'"
    Write-Host "  `$env:INTEGRATION_SECRET_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='"
    Write-Host "  `$env:APP_ENV='test'"
}

function Test-CommandAvailable {
    param([string]$CommandName)

    return $null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function Test-DockerDaemonAvailable {
    if (-not (Test-CommandAvailable -CommandName "docker")) {
        return $false
    }

    if ($DryRun) {
        return $true
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & docker info *> $null
        return $LASTEXITCODE -eq 0
    }
    catch {
        return $false
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Set-BackendTestEnvironment {
    $usesLocalComposeDatabase = [string]::IsNullOrWhiteSpace($env:DATABASE_URL)

    if ($usesLocalComposeDatabase) {
        $env:DATABASE_URL = "postgresql+psycopg2://torve:torve@localhost:5432/torve"
    }
    if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
        $env:JWT_SECRET = "local-test-jwt-secret"
    }
    if ([string]::IsNullOrWhiteSpace($env:INTEGRATION_SECRET_KEY)) {
        $env:INTEGRATION_SECRET_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
    if ([string]::IsNullOrWhiteSpace($env:APP_ENV)) {
        $env:APP_ENV = "test"
    }

    return $usesLocalComposeDatabase
}

function Wait-BackendPostgres {
    Write-Host ""
    Write-Host "[backend-test-db] waiting for postgres healthcheck"
    Write-Host "  cwd: $serverDir"

    if ($DryRun) {
        return
    }

    Push-Location $serverDir
    try {
        for ($attempt = 1; $attempt -le 30; $attempt++) {
            & docker compose exec -T postgres pg_isready -U torve -d torve *> $null
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  postgres ready"
                return
            }
            Start-Sleep -Seconds 1
        }
    }
    finally {
        Pop-Location
    }

    Write-BackendTestSetupInstructions -Reason "local Docker Postgres did not become healthy."
    throw "backend-test database did not become ready."
}

function Invoke-BackendTest {
    if (-not (Test-Path $serverDir)) {
        throw "server directory not found: $serverDir"
    }
    if (-not (Test-CommandAvailable -CommandName "python")) {
        Write-BackendTestSetupInstructions -Reason "python was not found on PATH."
        throw "backend-test requires python."
    }

    $usesLocalComposeDatabase = Set-BackendTestEnvironment
    if ($usesLocalComposeDatabase) {
        if (-not (Test-DockerDaemonAvailable)) {
            Write-BackendTestSetupInstructions -Reason "DATABASE_URL is unset and Docker is not available or not running."
            throw "backend-test requires docker or an explicit DATABASE_URL."
        }
        try {
            Invoke-Step -Label "backend-test-db" -FilePath "docker" -Arguments @("compose", "up", "-d", "postgres") -WorkingDirectory $serverDir
            Wait-BackendPostgres
        }
        catch {
            Write-BackendTestSetupInstructions -Reason $_.Exception.Message
            throw
        }
    }

    try {
        Invoke-Step -Label "backend-test-deps" -FilePath "python" -Arguments @("-c", "import pytest, fastapi, sqlalchemy, psycopg2, pydantic_settings") -WorkingDirectory $serverDir
        Invoke-Step -Label "backend-test-migrate" -FilePath "python" -Arguments @("-m", "alembic", "upgrade", "head") -WorkingDirectory $serverDir
        Invoke-Step -Label "backend-test" -FilePath "python" -Arguments @("-m", "pytest", "tests", "-q") -WorkingDirectory $serverDir
    }
    catch {
        Write-BackendTestSetupInstructions -Reason $_.Exception.Message
        throw
    }
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
        Invoke-BackendTest
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
