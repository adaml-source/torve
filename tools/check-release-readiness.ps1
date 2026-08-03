param(
    [string]$ManifestPath = ".codex-deploy\releases.json",
    [switch]$AllowDirty,
    [switch]$SkipTests,
    [switch]$SkipArtifacts,
    [switch]$SkipStoreAssets
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
            @{ Name = "Windows MSI"; Pattern = "desktopApp\build\compose\binaries\main\msi\*.msi" }
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
