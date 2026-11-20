# APK Size & Composition Inspector (PowerShell)
# Target: Release APK < 15 MB
param (
    [string]$ApkPath = "app/build/outputs/apk/release/app-release-unsigned.apk"
)

if (-not (Test-Path $ApkPath)) {
    Write-Warning "APK not found at $ApkPath. Building release APK first..."
    & .\gradlew assembleRelease
}

if (-not (Test-Path $ApkPath)) {
    Write-Error "Release APK not found at $ApkPath"
    exit 1
}

$apkItem = Get-Item $ApkPath
$sizeBytes = $apkItem.Length
$sizeMb = [Math]::Round($sizeBytes / (1024 * 1024.0), 2)

Write-Host "===================================================="
Write-Host " Release APK Size Analysis"
Write-Host " File:   $ApkPath"
Write-Host " Size:   $sizeMb MB ($sizeBytes bytes)"
Write-Host " Target: < 15.0 MB"
if ($sizeMb -lt 15.0) {
    Write-Host " Status: PASS" -ForegroundColor Green
} else {
    Write-Host " Status: FAIL (Exceeds 15.0 MB target)" -ForegroundColor Red
}
Write-Host "===================================================="

Write-Host "`nTop 10 Largest Contributors Inside APK:"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkItem.FullName)
$entries = $zip.Entries | Sort-Object Length -Descending | Select-Object -First 10

$index = 1
foreach ($entry in $entries) {
    $entryKb = [Math]::Round($entry.Length / 1024.0, 1)
    $compKb = [Math]::Round($entry.CompressedLength / 1024.0, 1)
    Write-Host ("{0,2}. {1,-45} {2,8} KB uncompressed ({3,7} KB compressed)" -f $index, $entry.FullName, $entryKb, $compKb)
    $index++
}
$zip.Dispose()
Write-Host "----------------------------------------------------"
