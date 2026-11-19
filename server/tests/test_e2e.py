"""End-to-end test starting real storage node process with mDNS enabled,

performing a 50 MB file upload with mid-upload disconnect and resume.
"""

from __future__ import annotations

import asyncio
import hashlib
import http.client
import os
import re
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))

from discovery import SERVICE_TYPE
from storage import slugify


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def test_e2e_real_server_50mb_upload_resume():
    """Starts the real server process with mDNS enabled, verifies mDNS discovery,

    and uploads a 50 MB file with mid-upload disconnect and resume.
    """
    tmp_dir = tempfile.mkdtemp()
    port = find_free_port()

    server_dir = Path(__file__).parent.parent
    main_py = server_dir / "main.py"

    env = os.environ.copy()
    env["PIPHOTOS_PORT"] = str(port)
    env["PIPHOTOS_STORAGE"] = os.path.join(tmp_dir, "storage")
    env["PIPHOTOS_DB"] = os.path.join(tmp_dir, "piphotos.db")
    env["PIPHOTOS_CERTS"] = os.path.join(tmp_dir, "certs")
    env["PIPHOTOS_DISK_RESERVE_FRACTION"] = "0.0"
    env.pop("PIPHOTOS_NO_MDNS", None)
    env["PIPHOTOS_NO_QR"] = "1"

    print(f"\n[E2E] Starting server on port {port}...", flush=True)
    proc = subprocess.Popen(
        [sys.executable, "-u", str(main_py)],
        cwd=str(server_dir),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )

    pairing_code = None
    tls_fp = None
    output_lines = []
    try:
        # 1. Read stdout until pairing code appears
        start_time = time.time()
        while time.time() - start_time < 20:
            line = proc.stdout.readline()
            if not line:
                if proc.poll() is not None:
                    break
                time.sleep(0.05)
                continue
            output_lines.append(line)
            if "Pairing code:" in line:
                m = re.search(r"Pairing code:\s*(\d{6})", line)
                if m:
                    pairing_code = m.group(1)
            if "TLS fingerprint:" in line:
                m = re.search(r"TLS fingerprint:\s*([A-F0-9:]+)", line)
                if m:
                    tls_fp = m.group(1)
            if "[SERVER_READY]" in line:
                break
            time.sleep(0.01)

        import threading
        drainer = threading.Thread(target=lambda: [l for l in proc.stdout], daemon=True)
        drainer.start()

        assert pairing_code is not None, f"Could not find pairing code in server output:\n{''.join(output_lines)}"
        print(f"[E2E] Server up with pairing code: {pairing_code}, TLS fp: {tls_fp}", flush=True)

        base_url = f"https://127.0.0.1:{port}"

        # 2. Wait for server to respond to GET /api/v1/info
        client = httpx.Client(base_url=base_url, verify=False, timeout=5.0)
        ready = False
        last_err = None
        for _ in range(50):
            if proc.poll() is not None:
                remaining_out = proc.stdout.read()
                raise RuntimeError(f"Server exited prematurely with code {proc.poll()}:\n{remaining_out}")
            try:
                r = client.get("/api/v1/info")
                if r.status_code == 200:
                    ready = True
                    break
            except Exception as e:
                last_err = e
                time.sleep(0.2)
        assert ready is True, f"Server did not become ready in time. Last error: {last_err}"
        print("[E2E] Server /api/v1/info verified", flush=True)

        # 3. Verify mDNS advertisement with second Zeroconf instance
        print("[E2E] Browsing mDNS for advertised service...", flush=True)
        async def verify_mdns():
            from zeroconf.asyncio import AsyncServiceBrowser, AsyncServiceInfo, AsyncZeroconf
            zc = AsyncZeroconf()
            found = []

            class Listener:
                def add_service(self, _, type_, name):
                    found.append((type_, name))

                def remove_service(self, *args):
                    pass

                def update_service(self, *args):
                    pass

            browser = AsyncServiceBrowser(zc.zeroconf, SERVICE_TYPE, listener=Listener())
            try:
                for _ in range(30):
                    if any("Home Vault" in name for _, name in found):
                        break
                    await asyncio.sleep(0.1)

                target = next(((t, n) for t, n in found if "Home Vault" in n), None)
                assert target is not None, f"mDNS did not discover server on port {port}. Found: {found}"
                info = AsyncServiceInfo(target[0], target[1])
                await info.async_request(zc.zeroconf, 2000)
                assert info.port == port
            finally:
                await zc.async_close()

        asyncio.run(verify_mdns())
        print("[E2E] mDNS advertisement successfully verified", flush=True)

        # 4. Pair device
        pair_resp = client.post("/api/v1/pair", json={
            "code": pairing_code,
            "device_name": "E2E Moto Phone",
            "device_model": "motorola edge 50 neo",
        })
        assert pair_resp.status_code == 200, pair_resp.text
        pair_data = pair_resp.json()
        token = pair_data["token"]
        device_id = pair_data["device_id"]
        headers = {"Authorization": f"Bearer {token}"}
        print(f"[E2E] Paired device successfully: device_id={device_id}", flush=True)

        # 5. Generate 50 MB payload (52,428,800 bytes)
        print("[E2E] Generating 50 MB test payload...", flush=True)
        chunk_pattern = b"PIPHOTOS_TEST_DATA_BLOCK_" * 2560  # exactly 65536 bytes
        chunk_count = 800
        total_size = len(chunk_pattern) * chunk_count  # 52,428,800 bytes
        hasher = hashlib.sha256()
        for _ in range(chunk_count):
            hasher.update(chunk_pattern)
        sha256 = hasher.hexdigest()

        part1_chunks = 320  # 20 MB (20,971,520 bytes)
        part1_bytes = part1_chunks * len(chunk_pattern)
        print(f"[E2E] Payload ready: sha256={sha256}, total_size={total_size}", flush=True)

        # 6. Upload Part 1 and simulate mid-upload disconnect at 20 MB
        print(f"[E2E] Uploading Part 1 ({part1_bytes} bytes) and disconnecting socket...", flush=True)
        ssl_ctx = ssl.create_default_context()
        ssl_ctx.check_hostname = False
        ssl_ctx.verify_mode = ssl.CERT_NONE

        conn = http.client.HTTPSConnection("127.0.0.1", port, context=ssl_ctx, timeout=15)
        path = f"/api/v1/upload/{sha256}?device={device_id}&folder=Camera&name=video_50mb.mp4"
        conn.putrequest("PUT", path)
        conn.putheader("Authorization", f"Bearer {token}")
        conn.putheader("Upload-Offset", "0")
        conn.putheader("Content-Length", str(total_size))
        conn.endheaders()

        # Send only 20 MB
        for _ in range(part1_chunks):
            conn.send(chunk_pattern)

        # Forcibly cut the connection mid-transfer
        conn.close()
        print("[E2E] Socket forcibly closed at 20 MB to simulate disconnect", flush=True)

        # Allow server a brief moment to process disconnect and flush partial record
        time.sleep(0.5)

        # 7. Query bulk check endpoint to see partial progress
        print("[E2E] Calling POST /api/v1/upload/check...", flush=True)
        check_resp = client.post("/api/v1/upload/check", headers=headers, json={"hashes": [sha256]})
        assert check_resp.status_code == 200, check_resp.text
        check_data = check_resp.json()
        assert sha256 not in check_data["existing"]
        server_offset = check_data["partial"].get(sha256)
        assert server_offset is not None and server_offset > 0, f"Expected non-zero partial offset, got {check_data}"
        print(f"[E2E] /upload/check confirmed partial offset: {server_offset}", flush=True)

        # Also verify HEAD returns 404 with Upload-Offset matching server_offset
        head_resp = client.head(f"/api/v1/upload/{sha256}", headers=headers)
        assert head_resp.status_code == 404
        assert head_resp.headers.get("Upload-Offset") == str(server_offset)
        print(f"[E2E] HEAD confirmed 404 with Upload-Offset={server_offset}", flush=True)

        # 8. Resume upload for remaining bytes from server_offset
        print(f"[E2E] Resuming remaining bytes from offset {server_offset}...", flush=True)
        # Full payload bytes for slicing
        full_payload = chunk_pattern * chunk_count
        remaining_data = full_payload[server_offset:]

        conn2 = http.client.HTTPSConnection("127.0.0.1", port, context=ssl_ctx, timeout=30)
        conn2.putrequest("PUT", f"{path}&taken=1768435200")
        conn2.putheader("Authorization", f"Bearer {token}")
        conn2.putheader("Upload-Offset", str(server_offset))
        conn2.putheader("Content-Length", str(len(remaining_data)))
        conn2.endheaders()

        # Stream remaining bytes in 64 KB chunks
        chunk_sz = len(chunk_pattern)
        for offset in range(0, len(remaining_data), chunk_sz):
            conn2.send(remaining_data[offset:offset + chunk_sz])

        resp2 = conn2.getresponse()
        resp2_body = resp2.read().decode()
        assert resp2.status == 200, f"Resume failed with status {resp2.status}: {resp2_body}"
        print(f"[E2E] Resume PUT completed: status={resp2.status}, body={resp2_body}", flush=True)

        # 9. Verify completed state
        check_resp2 = client.post("/api/v1/upload/check", headers=headers, json={"hashes": [sha256]})
        assert sha256 in check_resp2.json()["existing"]
        assert sha256 not in check_resp2.json()["partial"]

        head_resp2 = client.head(f"/api/v1/upload/{sha256}", headers=headers)
        assert head_resp2.status_code == 200

        # Verify file on disk
        stored_path = Path(tmp_dir) / "storage" / "users" / "owner" / "devices" / "e2e-moto-phone" / "Camera" / "video_50mb.mp4"
        assert stored_path.exists(), f"File not found at {stored_path}"
        assert stored_path.stat().st_size == total_size
        assert int(stored_path.stat().st_mtime) == 1768435200
        print("[E2E] File verified on disk (52,428,800 bytes, mtime matched date_taken)", flush=True)

    finally:
        print("[E2E] Terminating server process...", flush=True)
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
        shutil.rmtree(tmp_dir, ignore_errors=True)
        print("[E2E] Cleaned up temporary directory", flush=True)
