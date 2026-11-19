#!/usr/bin/env bash
# Run the PiPhotos storage node server.
# Usage: ./run.sh [--port PORT] [--name NAME] [--storage PATH]

set -euo pipefail
cd "$(dirname "$0")"

# Default configuration
export PIPHOTOS_PORT="${PIPHOTOS_PORT:-8443}"
export PIPHOTOS_NAME="${PIPHOTOS_NAME:-Home Vault}"
export PIPHOTOS_STORAGE="${PIPHOTOS_STORAGE:-./data/storage}"
export PIPHOTOS_DB="${PIPHOTOS_DB:-./data/piphotos.db}"
export PIPHOTOS_CERTS="${PIPHOTOS_CERTS:-./data/certs}"

# Parse command-line overrides
while [[ $# -gt 0 ]]; do
    case $1 in
        --port) export PIPHOTOS_PORT="$2"; shift 2 ;;
        --name) export PIPHOTOS_NAME="$2"; shift 2 ;;
        --storage) export PIPHOTOS_STORAGE="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# Create virtualenv if needed
if [ ! -d ".venv" ]; then
    echo "Creating virtual environment..."
    python3 -m venv .venv
fi

# Activate and install dependencies
source .venv/bin/activate
pip install -q -r requirements.txt

echo "Starting PiPhotos server on port $PIPHOTOS_PORT..."
python main.py
