# Memory Measurement Script (PowerShell)
# Target: Peak heap while scrolling < 150 MB on Motorola Edge 50 Neo
param (
    [string]$Package = "com.example.piphotos"
)

Write-Host "===================================================="
Write-Host " Measuring Memory Usage for $Package"
Write-Host "===================================================="

# Ensure app is active
& adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 1

# Perform 3 fast scrolls to stress heap and image cache
Write-Host "Triggering grid flings to exercise thumbnail memory cache..."
for ($i = 1; $i -le 3; $i++) {
    & adb shell input swipe 600 1800 600 500 150
    Start-Sleep -Milliseconds 800
    & adb shell input swipe 600 500 600 1800 150
    Start-Sleep -Milliseconds 800
}

Write-Host "Fetching dumpsys meminfo..."
$meminfo = & adb shell dumpsys meminfo $Package

$totalPssKb = 0
$nativeHeapKb = 0
$dalvikHeapKb = 0
$graphicsKb = 0

$meminfo | ForEach-Object {
    if ($_ -match "TOTAL PSS:\s*(\d+)") {
        $totalPssKb = [int]$matches[1]
    }
    if ($_ -match "Native Heap\s+(\d+)") {
        $nativeHeapKb = [int]$matches[1]
    }
    if ($_ -match "Dalvik Heap\s+(\d+)") {
        $dalvikHeapKb = [int]$matches[1]
    }
    if ($_ -match "Graphics\s+(\d+)") {
        $graphicsKb = [int]$matches[1]
    }
}

$totalPssMb = [Math]::Round($totalPssKb / 1024.0, 1)
$nativeHeapMb = [Math]::Round($nativeHeapKb / 1024.0, 1)
$dalvikHeapMb = [Math]::Round($dalvikHeapKb / 1024.0, 1)
$graphicsMb = [Math]::Round($graphicsKb / 1024.0, 1)

Write-Host "----------------------------------------------------"
Write-Host "Memory Summary for $Package:"
Write-Host "  Total PSS:    $totalPssMb MB"
Write-Host "  Dalvik Heap:  $dalvikHeapMb MB"
Write-Host "  Native Heap:  $nativeHeapMb MB"
Write-Host "  Graphics:     $graphicsMb MB"
Write-Host "Target: Peak Heap < 150 MB"
if ($totalPssMb -lt 150.0) {
    Write-Host "Status: PASS" -ForegroundColor Green
} else {
    Write-Host "Status: WARN (Above 150 MB target)" -ForegroundColor Yellow
}
Write-Host "----------------------------------------------------"
