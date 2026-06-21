param(
  [string]$MapPath = "androidApp/brand_kits/provider_logo_urls.json",
  [string]$OutDir = "androidApp/src/main/assets/provider_logos"
)

if (-not (Test-Path $MapPath)) {
  Write-Error "Mapping file not found: $MapPath"
  exit 1
}

if (-not (Test-Path $OutDir)) {
  New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}

$map = Get-Content $MapPath -Raw | ConvertFrom-Json
$missing = @()

foreach ($prop in $map.PSObject.Properties) {
  $fileName = $prop.Name
  $url = $prop.Value
  if ([string]::IsNullOrWhiteSpace($url)) {
    $missing += $fileName
    continue
  }

  $dest = Join-Path $OutDir $fileName
  Write-Output "Downloading $fileName ..."
  try {
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
  } catch {
    Write-Warning "Failed: $fileName -> $url"
  }
}

if ($missing.Count -gt 0) {
  Write-Warning "Missing URLs for: $($missing -join ', ')"
} else {
  Write-Output "All downloads attempted."
}
