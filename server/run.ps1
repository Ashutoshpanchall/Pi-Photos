# Run the PiPhotos storage node server on Windows.
# Usage: .\run.ps1 [-Port 8443] [-Name "Home Vault"] [-DataDir path]

param(
    [int]$Port,
    [string]$Name,
    [string]$Storage,
    [string]$DataDir
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path ".venv")) {
    Write-Host "Creating virtual environment..."
    python -m venv .venv
    & .venv\Scripts\Activate.ps1
    pip install -q -r requirements.txt
} else {
    & .venv\Scripts\Activate.ps1
}

$cliArgs = @("run")
if ($Port) { $cliArgs += @("--port", $Port) }
if ($Name) { $cliArgs += @("--name", $Name) }
if ($Storage) { $env:PIPHOTOS_STORAGE = $Storage }
if ($DataDir) { $cliArgs += @("--data-dir", $DataDir) }

& python -m piphotos_server.cli @cliArgs
