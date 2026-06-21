param(
    [string]$OutputDir = "store_audit\model_audit",
    [int]$MaxZipSizeMb = 30
)

$ErrorActionPreference = "Stop"

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path | Out-Null
    }
}

function Copy-RelativePath {
    param(
        [string]$RepoRoot,
        [string]$StageRoot,
        [string]$RelativePath
    )

    $sourcePath = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $sourcePath)) {
        throw "Missing required path: $RelativePath"
    }

    $destinationPath = Join-Path $StageRoot $RelativePath
    $parent = Split-Path -Parent $destinationPath
    Ensure-Directory -Path $parent
    Copy-Item -LiteralPath $sourcePath -Destination $destinationPath -Recurse -Force
}

function Write-BundleInfo {
    param(
        [string]$FilePath,
        [string]$BundleName,
        [string[]]$IncludedPaths,
        [string[]]$Notes
    )

    $lines = @(
        "Torve Model Audit Bundle",
        "========================",
        "Bundle: $BundleName",
        "Built: $([DateTime]::UtcNow.ToString('yyyy-MM-dd HH:mm:ss')) UTC",
        "",
        "Included paths:"
    )
    $lines += $IncludedPaths | ForEach-Object { "  - $_" }
    $lines += @(
        "",
        "Intentional exclusions:",
        "  - build/, .gradle/, .git/, temp/cache directories",
        "  - local.properties",
        "  - keystore.properties",
        "  - generated APK/AAB/IPA outputs",
        "  - androidApp/src/main/jniLibs (prebuilt native binaries; omitted to satisfy 30 MB zip cap)",
        "  - androidApp/src/debug/assets/fixtures (debug media fixtures; omitted to satisfy 30 MB zip cap)",
        "  - androidApp/src/androidTest/assets/fixtures (test media fixtures; omitted to satisfy 30 MB zip cap)",
        "",
        "Notes:"
    )
    $lines += $Notes | ForEach-Object { "  - $_" }
    Set-Content -LiteralPath $FilePath -Value $lines
}

$repoRoot = Get-RepoRoot
$resolvedOutputDir = Join-Path $repoRoot $OutputDir
$stagingRoot = Join-Path $resolvedOutputDir "_staging"
$maxZipSizeBytes = $MaxZipSizeMb * 1MB

Ensure-Directory -Path $resolvedOutputDir
if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
Ensure-Directory -Path $stagingRoot

$rootPaths = @(
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "analytics.settings",
    "gradle",
    "docs",
    "release\store",
    "shared\build.gradle.kts",
    "shared\src"
)

$androidCommonPaths = @(
    "androidApp\build.gradle.kts",
    "androidApp\proguard-rules.pro",
    "androidApp\README.md",
    "androidApp\src\main\AndroidManifest.xml",
    "androidApp\src\main\assets",
    "androidApp\src\main\java",
    "androidApp\src\main\kotlin",
    "androidApp\src\main\res",
    "androidApp\src\debug\AndroidManifest.xml",
    "androidApp\src\debug\kotlin",
    "androidApp\src\test",
    "androidApp\src\androidTest\kotlin"
)

$bundles = @(
    @{
        Name = "torve-google-mobile-audit-full.zip"
        Paths = $rootPaths + $androidCommonPaths + @(
            "androidApp\src\google",
            "google_play_assets",
            "firebase"
        )
        Notes = @(
            "Google Play mobile audit bundle.",
            "Includes shared KMP module, Android main/debug/test sources, Google-specific store integration, and Play marketing assets."
        )
    },
    @{
        Name = "torve-google-tv-audit-full.zip"
        Paths = $rootPaths + $androidCommonPaths + @(
            "androidApp\src\google",
            "androidApp\src\tv",
            "google_play_assets",
            "firebase"
        )
        Notes = @(
            "Google Play TV audit bundle.",
            "Includes TV-specific source set in addition to Google store integration."
        )
    },
    @{
        Name = "torve-amazon-mobile-audit-full.zip"
        Paths = $rootPaths + $androidCommonPaths + @(
            "androidApp\src\amazon"
        )
        Notes = @(
            "Amazon mobile audit bundle.",
            "Includes Amazon billing/cast integration and the shared Android app stack.",
            "No dedicated Amazon mobile store-asset directory exists in this repo."
        )
    },
    @{
        Name = "torve-amazon-tv-audit-full.zip"
        Paths = $rootPaths + $androidCommonPaths + @(
            "androidApp\src\amazon",
            "androidApp\src\tv",
            "firetv_assets"
        )
        Notes = @(
            "Amazon Fire TV audit bundle.",
            "Includes TV-specific source set and Amazon-specific store integration."
        )
    },
    @{
        Name = "torve-ios-app-audit-full.zip"
        Paths = @(
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "gradle",
            "docs",
            "release\store",
            "shared\build.gradle.kts",
            "shared\src",
            "iosApp",
            "ios_assets"
        )
        Notes = @(
            "iOS App Store audit bundle.",
            "Includes native SwiftUI app, shared KMP source, and iOS store assets."
        )
    }
)

$results = @()

foreach ($bundle in $bundles) {
    $zipPath = Join-Path $resolvedOutputDir $bundle.Name
    $stageDir = Join-Path $stagingRoot ([IO.Path]::GetFileNameWithoutExtension($bundle.Name))

    if (Test-Path -LiteralPath $stageDir) {
        Remove-Item -LiteralPath $stageDir -Recurse -Force
    }
    Ensure-Directory -Path $stageDir

    foreach ($relativePath in $bundle.Paths | Sort-Object -Unique) {
        Copy-RelativePath -RepoRoot $repoRoot -StageRoot $stageDir -RelativePath $relativePath
    }

    Write-BundleInfo `
        -FilePath (Join-Path $stageDir "BUNDLE_INFO.txt") `
        -BundleName $bundle.Name `
        -IncludedPaths ($bundle.Paths | Sort-Object -Unique) `
        -Notes $bundle.Notes

    if (Test-Path -LiteralPath $zipPath) {
        Remove-Item -LiteralPath $zipPath -Force
    }

    Compress-Archive -Path (Join-Path $stageDir "*") -DestinationPath $zipPath -CompressionLevel Optimal

    $zipItem = Get-Item -LiteralPath $zipPath
    if ($zipItem.Length -gt $maxZipSizeBytes) {
        throw "Zip exceeds limit ($MaxZipSizeMb MB): $($bundle.Name) is $([Math]::Round($zipItem.Length / 1MB, 2)) MB"
    }

    $results += [PSCustomObject]@{
        Bundle = $bundle.Name
        SizeMb = [Math]::Round($zipItem.Length / 1MB, 2)
        Path = $zipItem.FullName
    }
}

$results | Sort-Object Bundle | Format-Table -AutoSize
