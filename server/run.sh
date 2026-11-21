#!/usr/bin/env bash
# Run the PiPhotos storage node server.
# Usage: ./run.sh [--port PORT] [--name NAME] [--data-dir DIR]

set -euo pipefail
cd "$(dirname "$0")"

# Create virtualenv if needed
if [ ! -d ".venv" ]; then
    echo "Creating virtual environment..."
    python3 -m venv .venv
    source .venv/bin/activate
    pip install -q -r requirements.txt
else
    source .venv/bin/activate
fi

# Run via CLI module
exec python -m piphotos_server.cli run "$@"
