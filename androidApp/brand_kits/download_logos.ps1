$ErrorActionPreference = "Stop"

# Output folder (change if you want)
$out = Join-Path (Get-Location) "provider_logos"
$pngDir = Join-Path $out "png"
$svgDir = Join-Path $out "svg"

New-Item -ItemType Directory -Force -Path $pngDir | Out-Null
New-Item -ItemType Directory -Force -Path $svgDir | Out-Null

function Download-File($url, $path) {
  try {
    Invoke-WebRequest -Uri $url -OutFile $path -UseBasicParsing
    Write-Host "OK  $path"
  } catch {
    Write-Host "SKIP $path  ($url)"
  }
}

# Providers detected from your AppleTVPlus.zip
$providers = @(
  "AppleTVPlus","BritBox","Criterion","Crunchyroll","CuriosityStream","DisneyPlus","Freevee","Hulu",
  "Joyn","MagentaTV","Max","Mubi","Netflix","ParamountPlus","Peacock","PlutoTV","PrimeVideo",
  "RTLPlus","Showtime","Shudder","Starz","Tubi","WOW"
)

# 1) Homarr dashboard-icons (via jsDelivr CDN)
# Some icons have both dark/light variants. We download both when defined.
$dashVariants = @{
  "AppleTVPlus"  = @("apple-tv-plus","apple-tv-plus-light")
  "Crunchyroll"  = @("crunchyroll")
  "DisneyPlus"   = @("disney-plus")
  "Hulu"         = @("hulu")
  "Max"          = @("max")
  "Mubi"         = @("mubi","mubi-dark")
  "Netflix"      = @("netflix")
  "ParamountPlus"= @("paramount-plus")
  "Peacock"      = @("peacock","peacock-light")
  "PlutoTV"      = @("pluto-tv")   # often PNG-only upstream, SVG may fail, script will skip if missing
  "PrimeVideo"   = @("prime-video","prime-video-light")
}

$dashPngBase = "https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png"
$dashSvgBase = "https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/svg"

foreach ($prov in $dashVariants.Keys) {
  foreach ($slug in $dashVariants[$prov]) {
    $pngPath = Join-Path $pngDir ("{0}_{1}.png" -f $prov, $slug)
    $svgPath = Join-Path $svgDir ("{0}_{1}.svg" -f $prov, $slug)

    Download-File "$dashPngBase/$slug.png" $pngPath
    Download-File "$dashSvgBase/$slug.svg" $svgPath
  }
}

# 2) Wikimedia Commons fallbacks (SVG + PNG render)
# SVG direct:
#   https://commons.wikimedia.org/wiki/Special:FilePath/<filename.svg>
# PNG render from SVG:
#   https://commons.wikimedia.org/wiki/Special:FilePath/<filename.svg>?width=512
$commonsFiles = @{
  "BritBox"         = "Britbox_2022_(UK).svg"
  "Criterion"       = "The_Criterion_Collection_Logo.svg"
  "CuriosityStream" = "CuriosityStream.svg"
  "Freevee"         = "Freevee_logo.svg"
  "Joyn"            = "Joyn_logo.svg"
  "MagentaTV"       = "Magenta_TV_Logo_2024.svg"
  "RTLPlus"         = "RTL%2B_Logo_2021.svg"
  "WOW"             = "WOW_Logo_2022.svg"
  "Showtime"        = "Showtime.svg"
  "Shudder"         = "Shudder_2017.svg"
  "Starz"           = "Starz_2022.svg"
  "Tubi"            = "Tubi_logo_2024.svg"
}

foreach ($prov in $commonsFiles.Keys) {
  $file = $commonsFiles[$prov]

  $svgUrl = "https://commons.wikimedia.org/wiki/Special:FilePath/$file"
  $pngUrl = "https://commons.wikimedia.org/wiki/Special:FilePath/$file?width=512"

  $svgPath = Join-Path $svgDir ("{0}.svg" -f $prov)
  $pngPath = Join-Path $pngDir ("{0}.png" -f $prov)

  Download-File $svgUrl $svgPath
  Download-File $pngUrl $pngPath
}

# Summary
Write-Host ""
Write-Host "Done."
Write-Host "PNG: $pngDir"
Write-Host "SVG: $svgDir"