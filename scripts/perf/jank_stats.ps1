# JankStats Measurement Script (PowerShell)
# Target: Janky frames < 2.0% at 120 Hz (8.33 ms frame budget) on Motorola Edge 50 Neo
param (
    [string]$Package = "com.example.piphotos",
    [int]$FlingCount = 5
)

Write-Host "===================================================="
Write-Host " Measuring Frame Jank for $Package ($FlingCount flings)"
Write-Host "===================================================="

# Ensure app is in foreground
& adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 2

# Reset gfxinfo
Write-Host "Resetting gfxinfo counters..."
& adb shell dumpsys gfxinfo $Package reset | Out-Null
Start-Sleep -Milliseconds 500

# Scripted fling gestures on the timeline grid
# Motorola Edge 50 Neo resolution: 1220 x 2712
$startX = 600
$startY = 1800
$endX = 600
$endY = 500

for ($i = 1; $i -le $FlingCount; $i++) {
    Write-Host "Executing fling $i of $FlingCount..."
    & adb shell input swipe $startX $startY $endX $endY 150
    Start-Sleep -Milliseconds 1200
    & adb shell input swipe $startX $endY $endX $startY 150
    Start-Sleep -Milliseconds 1200
}

Write-Host "Collecting framestats from dumpsys gfxinfo..."
$stats = & adb shell dumpsys gfxinfo $Package

$totalFrames = 0
$jankyFrames = 0

$stats | ForEach-Object {
    if ($_ -match "Total frames rendered:\s*(\d+)") {
        $totalFrames = [int]$matches[1]
    }
    if ($_ -match "Janky frames:\s*(\d+)\s*\(([\d\.]+)%\)") {
        $jankyFrames = [int]$matches[1]
        $jankPct = [double]$matches[2]
    }
}

Write-Host "----------------------------------------------------"
Write-Host "Gfxinfo Results:"
Write-Host "  Total frames:  $totalFrames"
Write-Host "  Janky frames:  $jankyFrames"
if ($totalFrames -gt 0) {
    $pct = [Math]::Round(($jankyFrames / $totalFrames) * 100.0, 2)
    Write-Host "  Jank rate:     $pct %"
    Write-Host "Target: < 2.0% jank at 120 Hz"
    if ($pct -le 2.0) {
        Write-Host "Status: PASS" -ForegroundColor Green
    } else {
        Write-Host "Status: WARN (Above 2.0% target)" -ForegroundColor Yellow
    }
} else {
    Write-Warning "Could not parse frame statistics. Ensure app is running and rendered frames."
}
Write-Host "----------------------------------------------------"
