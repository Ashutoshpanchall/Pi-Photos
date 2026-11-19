# Run the PiPhotos storage node server on Windows.
# Usage: .\run.ps1 [-Port 8443] [-Name "Home Vault"] [-Storage ./data/storage]

param(
    [int]$Port = 8443,
    [string]$Name = "Home Vault",
    [string]$Storage = "./data/storage"
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$env:PIPHOTOS_PORT = $Port
$env:PIPHOTOS_NAME = $Name
$env:PIPHOTOS_STORAGE = $Storage
$env:PIPHOTOS_DB = "./data/piphotos.db"
$env:PIPHOTOS_CERTS = "./data/certs"

# Create virtualenv if needed
if (-not (Test-Path ".venv")) {
    Write-Host "Creating virtual environment..."
    python -m venv .venv
}

# Activate and install
& .venv\Scripts\Activate.ps1
pip install -q -r requirements.txt

Write-Host "Starting PiPhotos server on port $Port..."
python main.py
