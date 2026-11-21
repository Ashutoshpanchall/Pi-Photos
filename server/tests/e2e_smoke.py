"""Lightweight standalone e2e smoke verification script for CI and server installers (Phase S5).

Runs: pair -> upload 5 MB file -> simulate disconnect & resume -> download -> verify SHA-256.
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
import ssl
import sys
import time
import urllib.request
from pathlib import Path

# Add server directory to path
sys.path.insert(0, str(Path(__file__).parent.parent))

from piphotos_server.paths import resolve_paths
from piphotos_server.local_admin import read_local_admin_token


def run_smoke(port: int, data_dir: Path) -> int:
    paths = resolve_paths(data_dir=data_dir)
    print(f"[Smoke] Starting CI smoke verification against https://127.0.0.1:{port}...")

    ctx = ssl._create_unverified_context()

    # 1. Verify /api/v1/info
    info_url = f"https://127.0.0.1:{port}/api/v1/info"
    req = urllib.request.Request(info_url)
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            print(f"[Smoke] Server info verified: {data.get('server_name', 'PiPhotos')} (version: {data.get('version', '1.0.0')})")
    except Exception as e:
        print(f"[Smoke Error] Failed to connect to server info endpoint: {e}")
        return 1

    # 2. Open pairing window using local admin token
    token = read_local_admin_token(paths.local_admin_token_path)
    if not token:
        print(f"[Smoke Error] Local admin token not found at {paths.local_admin_token_path}")
        return 1

    pair_open_url = f"https://127.0.0.1:{port}/api/v1/admin/pairing/open"
    open_req = urllib.request.Request(
        pair_open_url,
        data=b"{}",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        method="POST"
    )
    with urllib.request.urlopen(open_req, context=ctx, timeout=5) as resp:
        pair_data = json.loads(resp.read().decode("utf-8"))
        pairing_code = pair_data["code"]
        print(f"[Smoke] Pairing window opened, code: {pairing_code}")

    # 3. Pair device as owner (including required device_model)
    pair_url = f"https://127.0.0.1:{port}/api/v1/pair"
    pair_payload = json.dumps({
        "code": pairing_code,
        "device_name": "CI Smoke Runner",
        "device_model": "Generic CI Device",
    }).encode("utf-8")

    pair_req = urllib.request.Request(pair_url, data=pair_payload, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(pair_req, context=ctx, timeout=5) as resp:
        auth_data = json.loads(resp.read().decode("utf-8"))
        jwt_token = auth_data["token"]
        device_id = auth_data["device_id"]
        print(f"[Smoke] Device paired successfully (device_id: {device_id}, user: {auth_data.get('user_id')})")

    auth_header = {"Authorization": f"Bearer {jwt_token}"}

    # 4. Generate 5 MB test file
    size = 5 * 1024 * 1024  # 5 MB
    payload = os.urandom(size)
    sha256_orig = hashlib.sha256(payload).hexdigest()
    print(f"[Smoke] Generated 5 MB test file, SHA-256: {sha256_orig}")

    upload_path = f"/api/v1/upload/{sha256_orig}?device={device_id}&folder=Camera&name=smoke_test.jpg"

    # Simulate chunked upload with disconnect at 2 MB
    cut = 2 * 1024 * 1024
    print("[Smoke] Uploading chunk 1 (2 MB) then simulating abrupt socket disconnect...")
    conn1 = http.client.HTTPSConnection("127.0.0.1", port, context=ctx, timeout=15)
    conn1.putrequest("PUT", upload_path)
    conn1.putheader("Authorization", f"Bearer {jwt_token}")
    conn1.putheader("Upload-Offset", "0")
    conn1.putheader("Content-Length", str(size))
    conn1.endheaders()
    conn1.send(payload[:cut])
    conn1.close()  # Disconnect socket mid-stream

    time.sleep(0.5)

    # Resume upload for remaining 3 MB
    print("[Smoke] Resuming upload of remaining 3 MB...")
    conn2 = http.client.HTTPSConnection("127.0.0.1", port, context=ctx, timeout=20)
    conn2.putrequest("PUT", upload_path)
    conn2.putheader("Authorization", f"Bearer {jwt_token}")
    conn2.putheader("Upload-Offset", str(cut))
    conn2.putheader("Content-Length", str(size - cut))
    conn2.endheaders()
    conn2.send(payload[cut:])
    resp = conn2.getresponse()
    body = resp.read()
    assert resp.status == 200, f"Resume upload failed with status {resp.status}: {body.decode('utf-8', errors='ignore')}"
    print(f"[Smoke] Upload successfully finalized with status {resp.status}")

    # 5. Lookup file ID from user's file list
    files_url = f"https://127.0.0.1:{port}/api/v1/files?device_id={device_id}"
    req_files = urllib.request.Request(files_url, headers=auth_header)
    with urllib.request.urlopen(req_files, context=ctx, timeout=5) as r:
        files = json.loads(r.read().decode("utf-8"))
        assert len(files) > 0, "No files found for device!"
        file_id = files[0]["id"]
        print(f"[Smoke] Found file record in database, id: {file_id}")

    # 6. Download file and verify SHA-256 hash matches
    download_url = f"https://127.0.0.1:{port}/api/v1/files/{file_id}/content"
    dl_req = urllib.request.Request(download_url, headers=auth_header)
    with urllib.request.urlopen(dl_req, context=ctx, timeout=15) as resp:
        downloaded = resp.read()
        sha256_dl = hashlib.sha256(downloaded).hexdigest()
        assert sha256_dl == sha256_orig, f"Hash mismatch! {sha256_dl} != {sha256_orig}"
        print(f"[Smoke] File downloaded and verified! SHA-256 matches: {sha256_dl}")

    print("[Smoke] All smoke checks PASSED successfully!")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="PiPhotos server e2e smoke verification")
    parser.add_argument("--port", type=int, default=8443, help="Server port (default: 8443)")
    parser.add_argument("--data-dir", type=str, required=True, help="Data directory of running server")
    args = parser.parse_args()

    return run_smoke(args.port, Path(args.data_dir))


if __name__ == "__main__":
    sys.exit(main())
