# Generates dummy photos / videos / audio / PDFs with date-stamped filenames,
# spread across years & months, into .\testmedia\.  Push to an emulator with adb
# (commands printed at the end).  Files are minimal but carry the right extension
# so MediaStore indexes them in the correct collection; the app derives each item's
# month from the YYYYMMDD in its filename.
#
# Run from the project root:  powershell -ExecutionPolicy Bypass -File scripts\gen_testmedia.ps1

$ErrorActionPreference = "Stop"
$root = Join-Path (Get-Location) "testmedia"
$dirs = @{ pic = "$root\Pictures"; mov = "$root\Movies"; mus = "$root\Music"; doc = "$root\Download" }
$dirs.Values | ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }

# --- minimal valid-ish file bodies ---
# 1x1 JPEG (renders as a solid tile)
$jpeg = [Convert]::FromBase64String("/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAAAv/EABQQAQAAAAAAAAAAAAAAAAAAAAD/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8AfwD/2Q==")
# MP4: ftyp(isom) header + small mdat filler  -> sniffs as video/mp4
$mp4 = [byte[]]@(0x00,0x00,0x00,0x18,0x66,0x74,0x79,0x70,0x69,0x73,0x6F,0x6D,0x00,0x00,0x02,0x00,0x69,0x73,0x6F,0x6D,0x6D,0x70,0x34,0x32,
                 0x00,0x00,0x01,0x00,0x6D,0x64,0x61,0x74) + (,0 * 240)
# MP3: a few MPEG1-L3 frame headers + filler -> sniffs as audio/mpeg
$mp3 = ([byte[]]@(0xFF,0xFB,0x90,0x44) + (,0 * 380)) * 3
# minimal PDF text (app shows the PDF icon/badge even if first-page render fails)
$pdfText = "%PDF-1.1`n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj`n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj`n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 300]>>endobj`ntrailer<</Root 1 0 R>>`n%%EOF"
$pdf = [System.Text.Encoding]::ASCII.GetBytes($pdfText)

# year -> list of "month,photos,videos,audio,pdfs"
$plan = @{
  2018 = @("3,12,1,0,1","9,8,0,1,0")
  2019 = @("1,20,2,0,1","6,15,1,1,2","11,10,0,0,0")
  2020 = @("2,25,3,0,1","7,18,2,1,0","12,30,1,0,2")
  2021 = @("4,22,2,1,1","10,14,1,0,1")
  2022 = @("1,40,3,2,1","5,28,2,0,2","8,35,4,1,1","12,20,1,0,0")
  2023 = @("3,33,2,1,2","6,45,3,2,1","9,27,1,0,1")
  2024 = @("1,50,4,2,2","4,38,2,1,1","7,29,3,0,0","10,18,1,1,1")
}

$rng = New-Object Random
function Stamp($y,$m){ $d = $rng.Next(1,28); "{0:D4}{1:D2}{2:D2}" -f $y,$m,$d }
$n = 0
function Write-N($dir,$prefix,$ext,$y,$m,$count,$body){
  for($i=0;$i -lt $count;$i++){
    $script:n++
    $name = if($ext -eq "pdf"){ "{0}_{1}-{2:D2}-{3:D2}_{4:D4}.{5}" -f $prefix,$y,$m,$rng.Next(1,28),$script:n,$ext }
            else { "{0}_{1}_{2:D4}.{3}" -f $prefix,(Stamp $y $m),$script:n,$ext }
    [IO.File]::WriteAllBytes((Join-Path $dir $name),$body)
  }
}

foreach($y in $plan.Keys){
  foreach($row in $plan[$y]){
    $p = $row.Split(","); $m=[int]$p[0]
    Write-N $dirs.pic "IMG" "jpg" $y $m ([int]$p[1]) $jpeg
    Write-N $dirs.mov "VID" "mp4" $y $m ([int]$p[2]) $mp4
    Write-N $dirs.mus "AUD" "mp3" $y $m ([int]$p[3]) $mp3
    Write-N $dirs.doc "doc" "pdf" $y $m ([int]$p[4]) $pdf
  }
}

Write-Host "Generated $n files under $root"
Write-Host ""
Write-Host "Push to the running emulator, then trigger a scan:"
Write-Host '  adb push testmedia\Pictures\. /sdcard/Pictures'
Write-Host '  adb push testmedia\Movies\.   /sdcard/Movies'
Write-Host '  adb push testmedia\Music\.    /sdcard/Music'
Write-Host '  adb push testmedia\Download\. /sdcard/Download'
Write-Host '  adb shell "find /sdcard/Pictures /sdcard/Movies /sdcard/Music /sdcard/Download -type f -exec am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{} \;"'
