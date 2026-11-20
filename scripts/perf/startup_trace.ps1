# Startup Trace Capture Script (PowerShell)
# Captures system and app traces during cold start for Perfetto analysis
param (
    [string]$Package = "com.example.piphotos",
    [string]$Activity = ".MainActivity",
    [string]$OutputFile = "startup_trace.perfetto-trace"
)

Write-Host "===================================================="
Write-Host " Capturing Startup Trace for $Package ($OutputFile)"
Write-Host "===================================================="

# Kill running instance
& adb shell am force-stop $Package
Start-Sleep -Seconds 1

Write-Host "Starting background Perfetto trace recording (5s)..."
$perfettoCmd = "perfetto -o /data/misc/perfetto-traces/trace.perfetto-trace -t 5s sched freq idle am wm gfx view binder_driver app"
$job = Start-Job -ScriptBlock { param($cmd) & adb shell $cmd } -ArgumentList $perfettoCmd

Start-Sleep -Milliseconds 800

Write-Host "Launching app cold start..."
& adb shell am start -W -n "$Package/$Activity"

Wait-Job $job | Out-Null
Receive-Job $job | Out-Null
Remove-Job $job

Write-Host "Pulling trace file to local machine..."
& adb pull /data/misc/perfetto-traces/trace.perfetto-trace $OutputFile
& adb shell rm -f /data/misc/perfetto-traces/trace.perfetto-trace

if (Test-Path $OutputFile) {
    $sizeKb = [Math]::Round((Get-Item $OutputFile).Length / 1024.0, 1)
    Write-Host "Saved trace: $OutputFile ($sizeKb KB)" -ForegroundColor Green
    Write-Host "Open https://ui.perfetto.dev to view and inspect trace slices."
} else {
    Write-Warning "Could not pull trace file. Ensure adb root/permissions are available."
}
Write-Host "----------------------------------------------------"
