param(
    [string]$ManifestPath = ".codex-deploy\releases.json",
    [string]$ProvenancePath = "release\provenance\1.2.0.json",
    [switch]$AllowDirty,
    [switch]$SkipTests,
    [switch]$SkipArtifacts,
    [switch]$SkipStoreAssets,
    [switch]$CheckConnectedDevices
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$reportDirectory = Join-Path $repoRoot "build\reports\release-readiness"
$reportPath = Join-Path $reportDirectory "summary.json"

Push-Location $repoRoot
try {
    if (-not $AllowDirty) {
        $dirty = @(git status --porcelain)
        if ($dirty.Count -gt 0) {
            throw "Release worktree is not clean. Commit intentional changes or use -AllowDirty for a local diagnostic run."
        }
    }

    # Root-level UI dumps and smoke screenshots are useful locally but must
    # never enter a release commit or public source archive.
    $forbiddenTrackedDiagnostics = @(
        git ls-files |
            Where-Object {
                $_ -match '^(focus-.*\.xml|hero-.*\.xml|provider-.*\.png)$'
            }
    )
    if ($forbiddenTrackedDiagnostics.Count -gt 0) {
        throw "Tracked local diagnostic artifacts found: $($forbiddenTrackedDiagnostics -join ', '). Remove them from version control before release."
    }

    & powershell -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $PSScriptRoot "check-release-version-alignment.ps1") `
        -ManifestPath $ManifestPath
    if ($LASTEXITCODE -ne 0) {
        throw "Release version alignment failed."
    }

    $positioningArgs = @()
    if (-not $SkipStoreAssets) { $positioningArgs += "-RequireScreenshots" }
    & powershell -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $PSScriptRoot "check-public-positioning.ps1") @positioningArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Public positioning or store screenshot gate failed."
    }

    & powershell -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $PSScriptRoot "check-public-website.ps1") `
        -ManifestPath $ManifestPath
    if ($LASTEXITCODE -ne 0) {
        throw "Public website/release truth verification failed."
    }

    if (-not $SkipTests) {
        $testTasks = @(
            ":shared:allTests",
            ":desktopApp:test",
            ":androidApp:testAmazonTvDebugUnitTest",
            ":androidApp:testGoogleTvDebugUnitTest",
            ":androidApp:testGoogleMobileDebugUnitTest",
            "--console=plain"
        )
        & (Join-Path $repoRoot "gradlew.bat") @testTasks
        if ($LASTEXITCODE -ne 0) {
            throw "Client quality gates failed."
        }
    }

    $artifacts = @()
    if (-not $SkipArtifacts) {
        $requirements = @(
            @{ Name = "Amazon TV release APK"; Pattern = "androidApp\build\outputs\apk\amazonTv\release\*.apk" },
            @{ Name = "Google TV release AAB"; Pattern = "androidApp\build\outputs\bundle\googleTvRelease\*.aab" },
            @{ Name = "Google mobile release AAB"; Pattern = "androidApp\build\outputs\bundle\googleMobileRelease\*.aab" },
            @{ Name = "Google TV native symbols"; Pattern = "androidApp\build\outputs\native-debug-symbols\googleTvRelease\*.zip" },
            @{ Name = "Google mobile native symbols"; Pattern = "androidApp\build\outputs\native-debug-symbols\googleMobileRelease\*.zip" },
            @{ Name = "Amazon TV native symbols"; Pattern = "androidApp\build\outputs\native-debug-symbols\amazonTvRelease\*.zip" },
            @{ Name = "Amazon TV R8 mapping"; Pattern = "androidApp\build\outputs\mapping\amazonTvRelease\mapping.txt" },
            @{ Name = "Google TV R8 mapping"; Pattern = "androidApp\build\outputs\mapping\googleTvRelease\mapping.txt" },
            @{ Name = "Google mobile R8 mapping"; Pattern = "androidApp\build\outputs\mapping\googleMobileRelease\mapping.txt" },
            @{ Name = "Windows MSI"; Pattern = "desktopApp\build\compose\binaries\main-closeapp\msi\*.msi" }
        )

        foreach ($requirement in $requirements) {
            $matches = @(Get-ChildItem -Path (Join-Path $repoRoot $requirement.Pattern) -File -ErrorAction SilentlyContinue)
            if ($matches.Count -eq 0) {
                throw "Missing required release output: $($requirement.Name) ($($requirement.Pattern))."
            }
            $newest = $matches | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
            $artifacts += [ordered]@{
                name = $requirement.Name
                path = $newest.FullName.Substring($repoRoot.Length + 1)
                bytes = $newest.Length
                sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $newest.FullName).Hash.ToLowerInvariant()
                writtenUtc = $newest.LastWriteTimeUtc.ToString("o")
            }
        }

        $desktopConfigPath = Join-Path $repoRoot "desktopApp\build\compose\binaries\main\app\Torve\app\Torve.cfg"
        if (-not (Test-Path -LiteralPath $desktopConfigPath -PathType Leaf)) {
            throw "Missing packaged desktop launcher config: $desktopConfigPath."
        }
        $feedPrefix = "java-options=-Dtorve.update.feed="
        $feedLine = Get-Content -LiteralPath $desktopConfigPath |
            Where-Object { $_.Trim().StartsWith($feedPrefix) } |
            Select-Object -First 1
        $feedUrl = if ($null -ne $feedLine) { $feedLine.Trim().Substring($feedPrefix.Length) } else { "" }
        $parsedFeed = $null
        if (-not [Uri]::TryCreate($feedUrl, [UriKind]::Absolute, [ref]$parsedFeed) -or $parsedFeed.Scheme -ne "https") {
            throw "Packaged desktop updater feed must be a non-blank HTTPS URL; found '$feedUrl'."
        }

        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "check-android-16kb-compatibility.ps1")
        if ($LASTEXITCODE -ne 0) {
            throw "Android 16 KB packaging/ELF compatibility verification failed."
        }
    }

    if ($CheckConnectedDevices) {
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "check-connected-release-devices.ps1")
        if ($LASTEXITCODE -ne 0) {
            throw "Connected-device release-only installation verification failed."
        }
    }

    $provenanceArgs = @('-ProvenancePath', $ProvenancePath, '-RequireTag')
    if (-not $SkipArtifacts) { $provenanceArgs += '-RequireArtifacts' }
    & powershell -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $PSScriptRoot "check-release-provenance.ps1") @provenanceArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Release provenance verification failed."
    }

    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    $summary = [ordered]@{
        passed = $true
        checkedUtc = [DateTime]::UtcNow.ToString("o")
        commit = (git rev-parse HEAD).Trim()
        dirtyAllowed = [bool]$AllowDirty
        testsSkipped = [bool]$SkipTests
        storeAssetsSkipped = [bool]$SkipStoreAssets
        artifactsSkipped = [bool]$SkipArtifacts
        artifacts = $artifacts
    }
    [System.IO.File]::WriteAllText(
        $reportPath,
        ($summary | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
    )
    Write-Output "Release readiness gate passed. Report: $reportPath"
}
finally {
    Pop-Location
}
