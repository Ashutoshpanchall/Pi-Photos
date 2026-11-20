# Cold Start Measurement Script (PowerShell)
# Target: < 1000 ms (1.0 s) to first grid frame on Motorola Edge 50 Neo
param (
    [string]$Package = "com.example.piphotos",
    [string]$Activity = ".MainActivity",
    [int]$Runs = 10
)

Write-Host "===================================================="
Write-Host " Measuring Cold Start for $Package$Activity ($Runs runs)"
Write-Host "===================================================="

$times = @()

for ($i = 1; $i -le $Runs; $i++) {
    & adb shell am force-stop $Package
    Start-Sleep -Milliseconds 1500

    $output = & adb shell am start -W -n "$Package/$Activity"
    $totalTimeLine = $output | Where-Object { $_ -match "TotalTime:\s*(\d+)" }
    if ($totalTimeLine -match "TotalTime:\s*(\d+)") {
        $ms = [int]$matches[1]
        $times += $ms
        Write-Host "Run ${i}: $ms ms"
    } else {
        Write-Warning "Run ${i}: Could not parse TotalTime from output:"
        $output | ForEach-Object { Write-Host "  $_" }
    }
}

if ($times.Count -gt 0) {
    $sorted = $times | Sort-Object
    $count = $sorted.Count
    if ($count % 2 -eq 1) {
        $median = $sorted[[Math]::Floor($count / 2)]
    } else {
        $median = ($sorted[($count / 2) - 1] + $sorted[$count / 2]) / 2.0
    }
    $avg = ($times | Measure-Object -Average).Average
    $min = ($times | Measure-Object -Minimum).Minimum
    $max = ($times | Measure-Object -Maximum).Maximum

    Write-Host "----------------------------------------------------"
    Write-Host "Cold Start Results over $count runs:"
    Write-Host "  Min:    $min ms"
    Write-Host "  Median: $median ms"
    Write-Host "  Avg:    $([Math]::Round($avg, 1)) ms"
    Write-Host "  Max:    $max ms"
    Write-Host "Target: < 1000 ms"
    if ($median -lt 1000) {
        Write-Host "Status: PASS" -ForegroundColor Green
    } else {
        Write-Host "Status: WARN (Above 1000 ms target)" -ForegroundColor Yellow
    }
    Write-Host "----------------------------------------------------"
} else {
    Write-Error "No valid runs completed. Is a device connected via adb?"
}
