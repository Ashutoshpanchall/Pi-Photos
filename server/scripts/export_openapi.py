#!/usr/bin/env python3
"""Export PiPhotos OpenAPI schema to JSON (Improvement 7).

Used in local development and CI to export the canonical schema and detect API drift.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# Add server directory to path
server_dir = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(server_dir))

from main import app  # noqa: E402


def export_openapi(dest_path: Path) -> None:
    schema = app.openapi()
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    with open(dest_path, "w", encoding="utf-8") as f:
        json.dump(schema, f, indent=2, sort_keys=True)
        f.write("\n")
    print(f"OpenAPI schema exported to {dest_path}")


if __name__ == "__main__":
    out_file = server_dir / "openapi.json"
    if len(sys.argv) > 1:
        out_file = Path(sys.argv[1])
    export_openapi(out_file)
