<#
.SYNOPSIS
    PiPhotos Storage Node Installer for Windows.

.DESCRIPTION
    One-command setup for PiPhotos self-hosted photo backup server on Windows.
    Configures storage node, auto-start Scheduled Task, Windows Firewall rules,
    and opens the initial phone pairing window.

.EXAMPLE
    # One-liner execution:
    irm https://raw.githubusercontent.com/Ashutoshpanchall/Pi-Photos/main/install/install.ps1 | iex

.EXAMPLE
    # Script file with custom parameters:
    .\install.ps1 -Port 8443 -DataDir "D:\PiPhotosData" -StorageDir "E:\Photos"

.NOTES
    Integrity verification:
    irm https://raw.githubusercontent.com/Ashutoshpanchall/Pi-Photos/main/install/install.ps1 -OutFile install.ps1
    Get-FileHash .\install.ps1 -Algorithm SHA256
    .\install.ps1
#>

[Diagnostics.CodeAnalysis.SuppressMessageAttribute("PSAvoidUsingInvokeExpression", "", Justification = "Official Astral uv bootstrap installation script")]
[Diagnostics.CodeAnalysis.SuppressMessageAttribute("PSAvoidUsingWriteHost", "", Justification = "Installer CLI uses Write-Host for colored terminal output")]
[CmdletBinding()]
param(
    [Alias("dry-run")]
    [switch]$DryRun,

    [Alias("data-dir")]
    [string]$DataDir = "",

    [Alias("storage-dir")]
    [string]$StorageDir = "",

    [int]$Port = 8443,

    [string]$Name = "",

    [string]$Ref = "main",

    [Alias("no-service")]
    [switch]$NoService,

    [Alias("y")]
    [switch]$Yes
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# 1. Detect elevation and environment
# ---------------------------------------------------------------------------
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $DataDir) {
    if ($isAdmin) {
        $effectiveDataDir = Join-Path $env:ProgramData "PiPhotos"
    } else {
        $effectiveDataDir = Join-Path $env:LOCALAPPDATA "PiPhotos"
    }
} else {
    $effectiveDataDir = $DataDir
}

if (-not $DryRun) {
    try {
        $pointerDir = Join-Path $env:LOCALAPPDATA "PiPhotos"
        if (-not (Test-Path $pointerDir)) {
            New-Item -ItemType Directory -Force -Path $pointerDir | Out-Null
        }
        $pointerJson = @{ data_dir = $effectiveDataDir; port = $Port } | ConvertTo-Json
        Set-Content -Path (Join-Path $pointerDir "location") -Value $pointerJson -Encoding UTF8 -ErrorAction SilentlyContinue
    } catch {
        # Non-critical if user profile path is restricted
    }
}

# ---------------------------------------------------------------------------
# 2. Print plan
# ---------------------------------------------------------------------------
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "          PiPhotos Storage Node Server Installer (Windows)  " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Platform:      Windows ($env:PROCESSOR_ARCHITECTURE)"
Write-Host "  Elevated:      $isAdmin"
Write-Host "  Target Ref:    $Ref"
Write-Host "  HTTPS Port:    $Port"
Write-Host "  Data Dir:      $effectiveDataDir"
if ($StorageDir) {
    Write-Host "  Storage Dir:   $StorageDir"
}
if ($NoService) {
    Write-Host "  Auto-Start:    Disabled (-NoService)"
} else {
    Write-Host "  Auto-Start:    Enabled (Windows Scheduled Task + Firewall)"
}
Write-Host "============================================================" -ForegroundColor Cyan

if ($DryRun) {
    Write-Host "`n[DRY-RUN] Verification complete. No changes made to system." -ForegroundColor Green
    return
}

if (-not $isAdmin -and -not $NoService) {
    Write-Host "`n[NOTE] Running in standard user mode." -ForegroundColor Yellow
    Write-Host "Administrator privileges are recommended to open Windows Firewall rules"
    Write-Host "(TCP $Port for sync, UDP 5353 for mDNS discovery) and create a system boot task."
    if (-not $Yes -and [Environment]::UserInteractive) {
        $elevateResp = Read-Host "`nWould you like to elevate to Administrator now? [y/N]"
        if ($elevateResp -match "^[yY]") {
            $myScript = $MyInvocation.MyCommand.Path
            if ($myScript) {
                $elevArgs = "-ExecutionPolicy Bypass -File `"$myScript`" -Ref `"$Ref`" -Port $Port"
                if ($DataDir) { $elevArgs += " -DataDir `"$DataDir`"" }
                if ($StorageDir) { $elevArgs += " -StorageDir `"$StorageDir`"" }
                if ($Name) { $elevArgs += " -Name `"$Name`"" }
                if ($Yes) { $elevArgs += " -Yes" }
                Start-Process powershell.exe -Verb RunAs -ArgumentList $elevArgs
                return
            }
        }
    }
    Write-Host "Continuing with user-level installation..." -ForegroundColor Cyan
    Write-Host "If mobile phones cannot connect across LAN, run this in an Administrator PowerShell:" -ForegroundColor DarkGray
    Write-Host "  netsh advfirewall firewall add rule name=`"PiPhotos (TCP $Port)`" dir=in action=allow protocol=TCP localport=$Port" -ForegroundColor DarkGray
    Write-Host "  netsh advfirewall firewall add rule name=`"PiPhotos mDNS`" dir=in action=allow protocol=UDP localport=5353" -ForegroundColor DarkGray
}

if (-not $Yes -and [Environment]::UserInteractive) {
    $proceed = Read-Host "`nProceed with installation? [Y/n]"
    if ($proceed -match "^[nN]") {
        Write-Host "Installation cancelled." -ForegroundColor Yellow
        return
    }
}

# ---------------------------------------------------------------------------
# 3. Locate or install Astral uv
# ---------------------------------------------------------------------------
$uvPath = $null
$standaloneBinary = $false
$uvCmd = Get-Command uv -ErrorAction SilentlyContinue
if ($uvCmd) {
    $uvPath = $uvCmd.Source
} elseif (Test-Path "$env:LOCALAPPDATA\uv\bin\uv.exe") {
    $uvPath = "$env:LOCALAPPDATA\uv\bin\uv.exe"
    $env:PATH = "$env:LOCALAPPDATA\uv\bin;$env:PATH"
} elseif (Test-Path "$env:USERPROFILE\.cargo\bin\uv.exe") {
    $uvPath = "$env:USERPROFILE\.cargo\bin\uv.exe"
    $env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"
}

if (-not $uvPath) {
    Write-Host "`nInstalling Astral uv (fast Python tool installer, user-level)..." -ForegroundColor Cyan
    try {
        [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
        $installScript = Invoke-RestMethod -Uri "https://astral.sh/uv/install.ps1"
        Invoke-Expression $installScript
    } catch {
        Write-Host "Notice: Astral uv install script failed. Attempting standalone binary download..." -ForegroundColor Yellow
        $binDir = Join-Path $env:LOCALAPPDATA "PiPhotos\bin"
        New-Item -ItemType Directory -Force -Path $binDir | Out-Null
        $binPath = Join-Path $binDir "piphotos-server.exe"
        try {
            Invoke-WebRequest -Uri "https://github.com/Ashutoshpanchall/Pi-Photos/releases/latest/download/piphotos-server-windows-x64.exe" -OutFile $binPath
            $env:PATH = "$binDir;$env:PATH"
            $standaloneBinary = $true
            Write-Host "Downloaded standalone binary to: $binPath" -ForegroundColor Green
        } catch {
            Write-Error "Failed to install uv and pre-built standalone binary unavailable: $_"
            return
        }
    }

    $env:PATH = "$env:LOCALAPPDATA\uv\bin;$env:USERPROFILE\.cargo\bin;$env:PATH"
    if (Test-Path "$env:LOCALAPPDATA\uv\bin\uv.exe") {
        $uvPath = "$env:LOCALAPPDATA\uv\bin\uv.exe"
    } else {
        $uvCmd2 = Get-Command uv -ErrorAction SilentlyContinue
        if ($uvCmd2) {
            $uvPath = $uvCmd2.Source
        } else {
            Write-Error "uv was installed but not found on PATH. Please add $env:LOCALAPPDATA\uv\bin to PATH."
            return
        }
    }
}

if (-not $standaloneBinary) {
    Write-Host "Using uv at: $uvPath" -ForegroundColor Green

    # ---------------------------------------------------------------------------
    # 4. Install / Upgrade piphotos-server
    # ---------------------------------------------------------------------------
    if ((Test-Path $Ref) -or ($Ref -eq "local") -or ($Ref -eq ".")) {
        if ($Ref -eq "local" -or $Ref -eq ".") {
            $packageSpec = ".\server"
        } else {
            $packageSpec = $Ref
        }
    } else {
        $packageSpec = "git+https://github.com/Ashutoshpanchall/Pi-Photos@${Ref}#subdirectory=server"
    }
    Write-Host "`nInstalling/upgrading piphotos-server from $packageSpec..." -ForegroundColor Cyan

    & $uvPath tool install --force "$packageSpec"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed to install piphotos-server via uv."
        return
    }
}

# Add user tool bin paths to PATH
$env:PATH = "$env:USERPROFILE\.local\bin;$env:LOCALAPPDATA\uv\bin;$env:PATH"

# ---------------------------------------------------------------------------
# 5. Run first-time setup
# ---------------------------------------------------------------------------
Write-Host "`nConfiguring PiPhotos storage node..." -ForegroundColor Cyan

$setupArgs = @("setup", "--yes", "--port", "$Port")
if ($DataDir) { $setupArgs += @("--data-dir", $DataDir) }
if ($StorageDir) { $setupArgs += @("--storage-dir", $StorageDir) }
if ($Name) { $setupArgs += @("--name", $Name) }

& piphotos-server @setupArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "piphotos-server setup failed."
    return
}

# ---------------------------------------------------------------------------
# 6. Service registration and start
# ---------------------------------------------------------------------------
if (-not $NoService) {
    Write-Host "`nInstalling auto-start background task..." -ForegroundColor Cyan
    $svcArgs = @("install-service")
    if ($DataDir) { $svcArgs += @("--data-dir", $DataDir) }
    if ($isAdmin) { $svcArgs += @("--system") }

    & piphotos-server @svcArgs

    Write-Host "Starting PiPhotos service..." -ForegroundColor Cyan
    & piphotos-server start

    Write-Host "Checking server health on https://127.0.0.1:$Port/api/v1/info..." -ForegroundColor Cyan
    $healthy = $false
    $prevCallback = [System.Net.ServicePointManager]::ServerCertificateValidationCallback
    try {
        [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
        for ($i = 0; $i -lt 5; $i++) {
            Start-Sleep -Seconds 1
            try {
                $req = [System.Net.WebRequest]::Create("https://127.0.0.1:$Port/api/v1/info")
                $req.Timeout = 2000
                $resp = $req.GetResponse()
                if ($resp.StatusCode -eq [System.Net.HttpStatusCode]::OK) {
                    $healthy = $true
                    $resp.Close()
                    break
                }
                $resp.Close()
            } catch {
                Start-Sleep -Milliseconds 100
            }
        }
    } finally {
        [System.Net.ServicePointManager]::ServerCertificateValidationCallback = $prevCallback
    }

    if ($healthy) {
        Write-Host "Server is running and healthy!" -ForegroundColor Green
        Write-Host ""
        $pairArgs = @("pair")
        if ($DataDir) { $pairArgs += @("--data-dir", $DataDir) }
        & piphotos-server @pairArgs

        Write-Host "`n============================================================" -ForegroundColor Cyan
        Write-Host "  Setup Complete! Next steps:" -ForegroundColor Cyan
        Write-Host "  1. Open the PiPhotos app on your Android phone."
        Write-Host "  2. Go to Backup > Set up (or Settings > Storage Nodes)."
        Write-Host "  3. Scan the QR code above or enter the 6-digit code."
        Write-Host "============================================================" -ForegroundColor Cyan
    } else {
        Write-Host "Notice: Service registered. Check status or logs if connection is delayed:" -ForegroundColor Yellow
        Write-Host "  piphotos-server status"
        Write-Host "  piphotos-server logs"
    }
} else {
    Write-Host "`nService skipped (-NoService). Run server in foreground with:" -ForegroundColor Yellow
    Write-Host "  piphotos-server run"
}
