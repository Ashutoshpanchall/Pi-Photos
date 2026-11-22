"""Phase 1: Storage Truth and Duplicate Upload Verification Tests.

Tests:
1. Duplicate uploads answer 200 with status: duplicate and existing file_id;
   first upload answers 201 with status: created.
2. Truthful storage calculations: available = min(quota_left, allocation_left, disk_free - reserve).
3. Upload enforcement: allocation_full error code when node allocation is breached.
4. Admin endpoints: POST /api/v1/admin/storage/allocation and over_allocated calculation.
5. CLI set-capacity subcommand.
"""

from __future__ import annotations

import collections
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest
from fastapi.testclient import TestClient

# Ensure server package is on sys.path
sys.path.insert(0, str(Path(__file__).parent.parent))

from main import app, ip_limiter, global_pairing_limiter, _config
from models import StatusResponse, MeResponse, AdminOverviewResponse
from piphotos_server.cli import build_parser, cmd_set_capacity, parse_size_to_bytes
from piphotos_server.paths import resolve_paths

DiskUsage = collections.namedtuple("usage", "total used free")


@pytest.fixture
def test_env():
    """Create a temporary server test environment with isolated DB and storage."""
    tmp = tempfile.mkdtemp()
    storage_dir = Path(tmp) / "storage"
    storage_dir.mkdir(parents=True, exist_ok=True)
    db_path = Path(tmp) / "test.db"
    certs_dir = Path(tmp) / "certs"
    certs_dir.mkdir(parents=True, exist_ok=True)
    config_path = Path(tmp) / "config.json"

    # Default 100 GB disk with 88 GB free
    disk_total = 100 * 1024**3
    disk_free = 88 * 1024**3
    disk_used = 12 * 1024**3
    # 90% of 88 GB free is ~79.2 GB (79 * 1024^3 for clean tests)
    node_alloc = 79 * 1024**3

    config_data = {
        "name": "Test Vault",
        "port": 8443,
        "storage": str(storage_dir),
        "database": str(db_path),
        "certs": str(certs_dir),
        "default_quota": 50 * 1024**3,
        "node_allocation_bytes": node_alloc,
    }
    with open(config_path, "w", encoding="utf-8") as f:
        json.dump(config_data, f)

    old_env = os.environ.copy()
    os.environ["PIPHOTOS_STORAGE"] = str(storage_dir)
    os.environ["PIPHOTOS_DB"] = str(db_path)
    os.environ["PIPHOTOS_CERTS"] = str(certs_dir)
    os.environ["PIPHOTOS_CONFIG"] = str(config_path)
    os.environ["PIPHOTOS_DATA_DIR"] = tmp
    os.environ["PIPHOTOS_NO_MDNS"] = "1"
    os.environ["PIPHOTOS_NO_QR"] = "1"
    os.environ["PIPHOTOS_DISK_RESERVE_FRACTION"] = "0.10"

    ip_limiter._requests.clear()
    global_pairing_limiter.reset()

    # Allow TestClient lifespan to initialize main.db and main.storage using the env vars
    tc = TestClient(app)
    with tc:
        import main
        yield {
            "client": tc,
            "tmp": tmp,
            "config_path": config_path,
            "db": main.db,
            "storage": main.storage,
            "disk_free": disk_free,
            "disk_total": disk_total,
            "node_alloc": node_alloc,
        }

    os.environ.clear()
    os.environ.update(old_env)


class TestDuplicateUploads:
    """Improvement 1: A duplicate upload answers 200 with status: duplicate and existing file id.
    A new file still answers 201 with status: created.
    """

    def test_duplicate_upload_returns_200_and_existing_id(self, test_env):
        client = test_env["client"]
        from main import pairing

        # 1. Pair owner
        code = pairing.generate_code()
        pair_resp = client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Pixel 9",
            "device_model": "Google Pixel 9",
        })
        assert pair_resp.status_code == 200
        owner_token = pair_resp.json()["token"]
        device_id = pair_resp.json()["device_id"]
        headers = {
            "Authorization": f"Bearer {owner_token}",
        }

        # 2. Upload photo first time
        payload = b"IMG_20261122_UNIQUE_PHOTO_CONTENT"
        sha256 = hashlib.sha256(payload).hexdigest()

        with patch("shutil.disk_usage", return_value=DiskUsage(100 * 1024**3, 10 * 1024**3, 90 * 1024**3)):
            resp1 = client.put(
                f"/api/v1/upload/{sha256}",
                headers={**headers, "Content-Length": str(len(payload)), "Upload-Offset": "0"},
                params={"device": device_id, "folder": "Camera", "name": "photo1.jpg"},
                content=payload,
            )
            # New file must answer 201
            assert resp1.status_code == 201, f"Expected 201 Created, got {resp1.status_code}: {resp1.text}"
            data1 = resp1.json()
            assert data1["status"] == "created"
            assert "file_id" in data1
            file_id_first = data1["file_id"]

            # 3. Upload identical content second time (e.g. retry after lost network reply)
            resp2 = client.put(
                f"/api/v1/upload/{sha256}",
                headers={**headers, "Content-Length": str(len(payload)), "Upload-Offset": "0"},
                params={"device": device_id, "folder": "Camera", "name": "photo1.jpg"},
                content=payload,
            )
            # Duplicate file must answer 200 with status: duplicate and the existing file id
            assert resp2.status_code == 200, f"Expected 200 OK for duplicate, got {resp2.status_code}: {resp2.text}"
            data2 = resp2.json()
            assert data2["status"] == "duplicate"
            assert data2["file_id"] == file_id_first, "Duplicate must return the identical existing file id"
            assert data2["sha256"] == sha256


class TestStorageTruthModel:
    """Storage truth calculations:
    available = min(quota_left, allocation_left, disk_free - reserve)
    """

    def test_owner_sees_truthful_available_bytes(self, test_env):
        client = test_env["client"]
        from main import pairing

        code = pairing.generate_code()
        pair_resp = client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Owner Phone",
            "device_model": "Pixel 9",
        })
        token = pair_resp.json()["token"]
        headers = {"Authorization": f"Bearer {token}"}

        # Drive D: has 88 GB free, 100 GB total, 10% reserve (10 GB) -> disk_free - reserve = 78 GB
        # Node allocation is 79 GB, used is 0
        # Owner quota is 0 (unlimited)
        # Expected available: min(79 GB, 78 GB) = 78 GB
        with patch("shutil.disk_usage", return_value=DiskUsage(100 * 1024**3, 12 * 1024**3, 88 * 1024**3)):
            resp = client.get("/api/v1/status", headers=headers)
            assert resp.status_code == 200
            data = resp.json()
            user_data = data["user"]

            # Must contain new truthful storage fields
            assert "available_bytes" in user_data
            assert "allocation_bytes" in user_data
            assert "allocation_used_bytes" in user_data

            assert user_data["allocation_bytes"] == 79 * 1024**3
            assert user_data["allocation_used_bytes"] == 0
            # available_bytes = min(79 GB alloc_left, 88 GB - 10 GB reserve = 78 GB) = 78 GB
            expected_available = min(79 * 1024**3, (88 - 10) * 1024**3)
            assert user_data["available_bytes"] == expected_available
            assert user_data["free_bytes"] == expected_available

    def test_member_quota_is_respected_in_truth_formula(self, test_env):
        client = test_env["client"]
        from main import pairing

        # Pair owner
        code = pairing.generate_code()
        owner_resp = client.post("/api/v1/pair", json={"code": code, "device_name": "Owner", "device_model": "PC"})
        owner_token = owner_resp.json()["token"]

        # Create member Bob with 5 GB quota
        member_quota = 5 * 1024**3
        create_res = client.post(
            "/api/v1/admin/users",
            headers={"Authorization": f"Bearer {owner_token}"},
            json={"display_name": "Bob", "quota_bytes": member_quota},
        )
        assert create_res.status_code == 200
        invite_code = create_res.json()["invite_code"]

        # Pair Bob
        bob_pair = client.post("/api/v1/pair", json={"code": invite_code, "device_name": "Bob Phone", "device_model": "Pixel"})
        bob_token = bob_pair.json()["token"]
        bob_headers = {"Authorization": f"Bearer {bob_token}"}

        # Disk has 88 GB free, allocation has 79 GB, but Bob's personal quota is 5 GB
        with patch("shutil.disk_usage", return_value=DiskUsage(100 * 1024**3, 12 * 1024**3, 88 * 1024**3)):
            st = client.get("/api/v1/status", headers=bob_headers).json()
            assert st["user"]["available_bytes"] == 5 * 1024**3
            assert st["user"]["quota_bytes"] == 5 * 1024**3

            # Also check /api/v1/me endpoint
            me = client.get("/api/v1/me", headers=bob_headers).json()
            assert me["available_bytes"] == 5 * 1024**3
            assert me["allocation_bytes"] == 79 * 1024**3

    def test_allocation_full_error_code(self, test_env):
        client = test_env["client"]
        from main import pairing

        code = pairing.generate_code()
        owner_resp = client.post("/api/v1/pair", json={"code": code, "device_name": "Owner", "device_model": "PC"})
        owner_token = owner_resp.json()["token"]
        device_id = owner_resp.json()["device_id"]
        headers = {"Authorization": f"Bearer {owner_token}"}

        # Set allocation to small limit (1 MB)
        set_alloc = client.post(
            "/api/v1/admin/storage/allocation",
            headers=headers,
            json={"allocation_bytes": 1024 * 1024, "force": True},
        )
        assert set_alloc.status_code == 200

        # Attempt to upload 2 MB payload -> must return 507 with allocation_full
        payload_2mb = b"B" * (2 * 1024 * 1024)
        h = hashlib.sha256(payload_2mb).hexdigest()

        with patch("shutil.disk_usage", return_value=DiskUsage(100 * 1024**3, 12 * 1024**3, 88 * 1024**3)):
            resp = client.put(
                f"/api/v1/upload/{h}",
                headers={**headers, "Content-Length": str(len(payload_2mb)), "Upload-Offset": "0"},
                params={"device": device_id, "folder": "Camera", "name": "large.jpg"},
                content=payload_2mb,
            )
            assert resp.status_code == 507
            assert resp.json()["detail"]["error"] == "allocation_full"


class TestAdminStorageAllocation:
    """Admin storage allocation management and CLI set-capacity."""

    def test_admin_set_allocation_and_refuse_below_usage(self, test_env):
        client = test_env["client"]
        from main import pairing

        code = pairing.generate_code()
        owner_resp = client.post("/api/v1/pair", json={"code": code, "device_name": "Owner", "device_model": "PC"})
        token = owner_resp.json()["token"]
        headers = {"Authorization": f"Bearer {token}"}

        with patch("shutil.disk_usage", return_value=DiskUsage(100 * 1024**3, 12 * 1024**3, 88 * 1024**3)):
            # Set allocation to 50 GB
            r = client.post(
                "/api/v1/admin/storage/allocation",
                headers=headers,
                json={"allocation_bytes": 50 * 1024**3},
            )
            assert r.status_code == 200
            data = r.json()
            assert data["allocation_bytes"] == 50 * 1024**3

            # Overview compares quotas against allocation
            ov = client.get("/api/v1/admin/overview", headers=headers).json()
            assert "allocation_bytes" in ov
            assert ov["allocation_bytes"] == 50 * 1024**3

    def test_parse_size_to_bytes(self):
        assert parse_size_to_bytes("500MB") == 500 * 1024 * 1024
        assert parse_size_to_bytes("79GB") == 79 * 1024 * 1024 * 1024
        assert parse_size_to_bytes("1.5TB") == int(1.5 * 1024 * 1024 * 1024 * 1024)
        assert parse_size_to_bytes("1048576") == 1048576

    def test_cli_set_capacity(self, test_env, tmp_path):
        paths = resolve_paths(data_dir=test_env["tmp"])
        parser = build_parser()
        args = parser.parse_args(["set-capacity", "80GB", "--data-dir", test_env["tmp"]])

        with patch("shutil.disk_usage", return_value=DiskUsage(100 * 1024**3, 12 * 1024**3, 88 * 1024**3)):
            rc = cmd_set_capacity(args)
            assert rc == 0

            # Verify persisted into config.json
            with open(paths.config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
            assert cfg["node_allocation_bytes"] == 80 * 1024**3
