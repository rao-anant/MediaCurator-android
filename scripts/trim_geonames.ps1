<#
  trim_geonames.ps1  —  Build the offline Place-search dataset (v1.1).

  Downloads GeoNames `cities15000` (cities > 15k population, ~sub-1 MB trimmed) and the admin1
  name table, then trims to the bundled asset consumed by GeoIndex.kt:

      app/src/main/assets/geo_cities.tsv
      format:  name|alt,alt,...|lat|lon|country|admin1     (pipe-separated, UTF-8, no BOM)

  Alt names are filtered to Latin/ASCII aliases (keeps Bangalore/Bombay, drops CJK/Cyrillic/etc.)
  and capped, to keep the asset small and English-first. Attribution: GeoNames (CC-BY) — credit in
  About/Help. Re-run to refresh the data.

  Usage:  pwsh scripts/trim_geonames.ps1 [-MaxAlt 4]
#>
param(
  [int]$MaxAlt = 4,
  [string]$OutFile
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path $root 'build\geonames'
New-Item -ItemType Directory -Force -Path $work | Out-Null
if (-not $OutFile) { $OutFile = Join-Path $root 'app\src\main\assets\geo_cities.tsv' }
New-Item -ItemType Directory -Force -Path (Split-Path $OutFile) | Out-Null

function Fetch($url, $dest) {
  if (-not (Test-Path $dest)) {
    Write-Host "Downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $dest
  }
}

$citiesZip = Join-Path $work 'cities15000.zip'
$citiesTxt = Join-Path $work 'cities15000.txt'
$adminTxt  = Join-Path $work 'admin1CodesASCII.txt'
Fetch 'https://download.geonames.org/export/dump/cities15000.zip' $citiesZip
Fetch 'https://download.geonames.org/export/dump/admin1CodesASCII.txt' $adminTxt
if (-not (Test-Path $citiesTxt)) { Expand-Archive -Path $citiesZip -DestinationPath $work -Force }

# admin1 map: "CC.code" -> ascii region name (e.g. "IN.19" -> "Karnataka")
$admin = @{}
foreach ($line in [System.IO.File]::ReadLines($adminTxt, [System.Text.Encoding]::UTF8)) {
  $f = $line.Split("`t")
  if ($f.Length -ge 3) { $admin[$f[0]] = $f[2] }
}

# GeoNames cities15000 columns (tab-separated): 1=name 2=ascii 3=altnames 4=lat 5=lon 8=country 10=admin1
$reader = New-Object System.IO.StreamReader($citiesTxt, [System.Text.Encoding]::UTF8)
$writer = New-Object System.IO.StreamWriter($OutFile, $false, (New-Object System.Text.UTF8Encoding($false)))
$n = 0
try {
  while (($line = $reader.ReadLine()) -ne $null) {
    $f = $line.Split("`t")
    if ($f.Length -lt 11) { continue }
    $name = $f[1]; $ascii = $f[2]; $lat = $f[4]; $lon = $f[5]; $cc = $f[8]; $a1code = $f[10]

    $alts = New-Object System.Collections.Generic.List[string]
    $seen = New-Object System.Collections.Generic.HashSet[string]
    [void]$seen.Add($name.ToLower())
    foreach ($a in (@($ascii) + ($f[3] -split ','))) {
      if ($alts.Count -ge $MaxAlt) { break }
      if (-not $a) { continue }
      $a = $a.Trim()
      if (-not $a) { continue }
      if ($a -notmatch '^[\x20-\x7E]+$') { continue }   # Latin/ASCII aliases only
      if ($seen.Add($a.ToLower())) { $alts.Add($a) }
    }

    $admin1 = ''
    $ak = "$cc.$a1code"
    if ($admin.ContainsKey($ak)) { $admin1 = $admin[$ak] }

    # Guard the field separators out of any value.
    $nm  = ($name    -replace '\|',' ')
    $al  = (($alts.ToArray() -join ',') -replace '\|',' ')
    $ad  = ($admin1  -replace '[\|,]',' ')
    $writer.WriteLine("$nm|$al|$lat|$lon|$cc|$ad")
    $n++
  }
} finally { $reader.Close(); $writer.Close() }

$sizeKb = [math]::Round((Get-Item $OutFile).Length / 1KB, 1)
Write-Host "Wrote $n cities -> $OutFile ($sizeKb KB)"
