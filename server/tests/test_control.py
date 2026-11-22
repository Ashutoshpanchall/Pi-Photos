"""Unit tests for piphotos_server.control shared control layer (Phase 3)."""

from __future__ import annotations

import json
import os
import shutil
import sqlite3
import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock

from piphotos_server.control import ServerController, ControlError
from piphotos_server.paths import resolve_paths
from piphotos_server.local_admin import ensure_local_admin_token


@pytest.fixture
def test_env(tmp_path):
    """Create isolated test environment with config and database."""
    data_dir = tmp_path / "piphotos_data"
    data_dir.mkdir(parents=True)
    paths = resolve_paths(data_dir=data_dir)
    paths.ensure_dirs()

    # Create dummy database
    conn = sqlite3.connect(str(paths.db_path))
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE users (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            display_name TEXT,
            slug TEXT UNIQUE NOT NULL,
            role TEXT NOT NULL,
            quota_bytes INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'active',
            created_at TEXT NOT NULL
        )
    """)
    cur.execute("""
        CREATE TABLE devices (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            name TEXT NOT NULL,
            model TEXT NOT NULL,
            slug TEXT NOT NULL,
            created_at TEXT NOT NULL,
            last_seen_at TEXT
        )
    """)
    cur.execute("""
        CREATE TABLE files (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            folder TEXT NOT NULL,
            relative_path TEXT NOT NULL,
            sha256 TEXT NOT NULL,
            size INTEGER NOT NULL,
            created_at TEXT NOT NULL
        )
    """)
    # Insert owner and a member
    cur.execute(
        "INSERT INTO users VALUES ('u1', 'owner', 'Alice Owner', 'alice-owner', 'owner', 0, 'active', '2026-01-01T00:00:00Z')"
    )
    cur.execute(
        "INSERT INTO users VALUES ('u2', 'bob', 'Bob Member', 'bob-member', 'member', 53687091200, 'active', '2026-01-02T00:00:00Z')"
    )
    # Insert device
    cur.execute(
        "INSERT INTO devices VALUES ('d1', 'u1', 'Pixel 9', 'Google Pixel 9', 'pixel-9', '2026-01-01T00:00:00Z', '2026-01-05T00:00:00Z')"
    )
    # Insert files
    cur.execute(
        "INSERT INTO files VALUES (1, 'u1', 'd1', 'Camera', 'Camera/IMG_001.jpg', 'hash1', 5000000, '2026-01-05T12:00:00Z')"
    )
    cur.execute(
        "INSERT INTO files VALUES (2, 'u1', 'd1', 'Camera', 'Camera/IMG_002.jpg', 'hash2', 15000000, '2026-01-05T12:05:00Z')"
    )
    conn.commit()
    conn.close()

    # Save initial config.json
    cfg = {
        "name": "Test Home Vault",
        "port": 18443,
        "node_allocation_bytes": 100 * 1024**3,
        "disk_reserve_fraction": 0.10,
    }
    paths.config_path.write_text(json.dumps(cfg), encoding="utf-8")

    # Generate admin token
    ensure_local_admin_token(paths.local_admin_token_path)

    return paths


class TestServerController:

    def test_init_and_config(self, test_env):
        ctrl = ServerController(data_dir=test_env.data_dir)
        assert ctrl.port == 18443
        assert ctrl.paths.data_dir == test_env.data_dir

        cfg = ctrl.get_config()
        assert cfg["name"] == "Test Home Vault"
        assert cfg["port"] == 18443

        ctrl.save_config({"name": "Renamed Vault"})
        assert ctrl.get_config()["name"] == "Renamed Vault"

    @patch("piphotos_server.control.shutil.disk_usage")
    def test_storage_summary_calculation(self, mock_disk, test_env):
        mock_disk.return_value = shutil._ntuple_diskusage(100 * 1024**3, 12 * 1024**3, 88 * 1024**3)
        ctrl = ServerController(data_dir=test_env.data_dir)
        summary = ctrl.get_storage_summary()

        assert summary["allocation_bytes"] == 100 * 1024**3
        # 5MB + 15MB = 20MB used
        assert summary["used_bytes"] == 20000000
        assert summary["allocation_used_bytes"] == 20000000
        assert summary["available_bytes"] > 0
        assert summary["reserve_percent"] == 10

    def test_status_when_stopped(self, test_env):
        ctrl = ServerController(data_dir=test_env.data_dir)
        status = ctrl.get_status()

        assert status["running"] is False
        assert status["name"] == "Test Home Vault"
        assert status["port"] == 18443
        assert status["users_count"] == 2
        assert status["devices_count"] == 1
        assert "storage" in status

    def test_offline_user_management(self, test_env):
        ctrl = ServerController(data_dir=test_env.data_dir)
        users = ctrl.list_users()
        assert len(users) == 2

        owner = next(u for u in users if u["role"] == "owner")
        assert owner["name"] == "Alice Owner"
        assert owner["used_bytes"] == 20000000
        assert owner["file_count"] == 2
        assert owner["device_count"] == 1

        member = next(u for u in users if u["role"] == "member")
        assert member["name"] == "Bob Member"
        assert member["quota_bytes"] == 53687091200

        # Update member quota directly in DB
        ctrl.update_user(member["id"], quota_bytes=80000000000, display_name="Bob Upgraded")
        updated_users = ctrl.list_users()
        upgraded_member = next(u for u in updated_users if u["id"] == member["id"])
        assert upgraded_member["quota_bytes"] == 80000000000
        assert upgraded_member["name"] == "Bob Upgraded"

    def test_offline_device_management(self, test_env):
        ctrl = ServerController(data_dir=test_env.data_dir)
        users = ctrl.list_users()
        owner = next(u for u in users if u["role"] == "owner")

        devs = ctrl.list_devices(owner["id"])
        assert len(devs) == 1
        assert devs[0]["name"] == "Pixel 9"

        ctrl.delete_device(devs[0]["id"])
        assert len(ctrl.list_devices(owner["id"])) == 0

    def test_recent_uploads(self, test_env):
        ctrl = ServerController(data_dir=test_env.data_dir)
        recent = ctrl.list_recent_uploads(limit=5)
        assert len(recent) == 2
        assert recent[0]["filename"] == "IMG_002.jpg"
        assert recent[0]["size_bytes"] == 15000000
        assert recent[1]["filename"] == "IMG_001.jpg"

    def test_set_capacity_offline(self, test_env):
        ctrl = ServerController(data_dir=test_env.data_dir)

        # Capacity below current usage (20MB) without force should raise
        with pytest.raises(ControlError) as exc_info:
            ctrl.set_storage_capacity(10000000, force=False)
        assert "smaller than current usage" in str(exc_info.value)

        # With force=True it succeeds
        res = ctrl.set_storage_capacity(10000000, force=True)
        assert res["allocation_bytes"] == 10000000

        # Updating to 50 GB
        ctrl.set_storage_capacity(50 * 1024**3)
        summary = ctrl.get_storage_summary()
        assert summary["allocation_bytes"] == 50 * 1024**3

    def test_doctor_integration(self, test_env):
        ctrl = ServerController(data_dir=test_env.data_dir)
        doc_res = ctrl.run_doctor()

        assert "total" in doc_res
        assert "passed" in doc_res
        assert "checks" in doc_res
        assert doc_res["total"] > 0
        assert isinstance(doc_res["checks"], list)

    def test_online_endpoints_and_patch_method(self, test_env):
        ctrl = ServerController(data_dir=test_env.data_dir)

        # Mock running server state
        with patch.object(ctrl, "is_running", return_value=True), \
             patch.object(ctrl, "_http_request") as mock_req:

            # 1. Test update_user uses PATCH method
            mock_req.return_value = {"status": "updated", "user_id": "u2"}
            ctrl.update_user("u2", quota_bytes=60000000000, display_name="Bob New")

            assert mock_req.call_count == 1
            call_args = mock_req.call_args
            assert call_args[0][0] == "PATCH"
            assert call_args[0][1] == "/api/v1/admin/users/u2"
            assert call_args[1]["json_body"] == {"quota_bytes": 60000000000, "display_name": "Bob New"}

            # 2. Test get_status fetches info and overview
            mock_req.reset_mock()
            def mock_http_side_effect(method, path, **kwargs):
                if path == "/api/v1/info":
                    return {"version": "1.2.3", "pairing_open": True}
                if path == "/api/v1/admin/overview":
                    return {
                        "allocation_bytes": 100 * 1024**3,
                        "allocation_used_bytes": 25 * 1024**3,
                        "users": [
                            {"id": "u1", "device_count": 2},
                            {"id": "u2", "device_count": 1},
                        ],
                    }
                return {}

            mock_req.side_effect = mock_http_side_effect
            status = ctrl.get_status()

            assert status["running"] is True
            assert status["version"] == "1.2.3"
            assert status["pairing_open"] is True
            assert status["users_count"] == 2
            assert status["devices_count"] == 3
            assert status["storage"]["allocation_bytes"] == 100 * 1024**3
            assert status["storage"]["allocation_used_bytes"] == 25 * 1024**3

    def test_offline_owner_guards_and_token_revocation(self, test_env):
        ctrl = ServerController(data_dir=test_env.data_dir)

        # 1. Guard against disabling the last active owner
        with pytest.raises(ControlError) as exc_info:
            ctrl.update_user("u1", status="disabled")
        assert "Cannot disable or demote the last active owner" in str(exc_info.value)

        # 2. Guard against demoting the last active owner
        with pytest.raises(ControlError) as exc_info:
            ctrl.update_user("u1", role="member")
        assert "Cannot disable or demote the last active owner" in str(exc_info.value)

        # 3. Guard against setting quota below usage without force
        # u1 currently has 20MB used in test_env
        with pytest.raises(ControlError) as exc_info:
            ctrl.update_user("u1", quota_bytes=10000000, force=False)
        assert "is below current user storage usage" in str(exc_info.value)

        # 4. Token revocation on device deletion
        import sqlite3
        conn = sqlite3.connect(str(test_env.db_path))
        cur = conn.cursor()
        cur.execute("""
            CREATE TABLE IF NOT EXISTS tokens (
                token TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
        """)
        cur.execute("INSERT INTO tokens VALUES ('test-token-123', 'u1', 'd1', '2026-01-01T00:00:00Z')")
        conn.commit()
        conn.close()

        # Delete device d1 offline
        ctrl.delete_device("d1")

        # Verify device and token are gone
        conn = sqlite3.connect(str(test_env.db_path))
        cur = conn.cursor()
        cur.execute("SELECT COUNT(*) FROM tokens WHERE device_id = 'd1'")
        assert cur.fetchone()[0] == 0
        cur.execute("SELECT COUNT(*) FROM devices WHERE id = 'd1'")
        assert cur.fetchone()[0] == 0
        conn.close()
