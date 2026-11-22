"""Tests for Improvement 6 (Device Roles: backup vs manage) and
Improvement 8 (Database Backup and Restore).
"""

from __future__ import annotations

import os
import shutil
import sqlite3
import tempfile
import time
from datetime import datetime, timezone, timedelta
from pathlib import Path
import pytest
from starlette.testclient import TestClient

from auth import create_bearer_token, hash_token
from database import Database, TokenRow
import main
from piphotos_server.backup import create_database_backup, restore_database_backup


@pytest.fixture
def test_env():
    temp_dir = tempfile.mkdtemp()
    data_dir = Path(temp_dir) / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    db_path = data_dir / "piphotos.db"

    # Initialize DB with owner
    db = Database(db_path)
    owner = db.create_user("Test Owner", role="owner", quota_bytes=100 * 1024 * 1024)
    member = db.create_user("Test Member", role="member", quota_bytes=50 * 1024 * 1024)

    # Setup storage marker for 503 guard
    (data_dir / ".piphotos-storage").touch()

    # Loopback admin token
    admin_token_path = data_dir / "local-admin.token"
    admin_token = create_bearer_token()
    admin_token_path.write_text(admin_token, encoding="utf-8")

    # Set app globals
    main.db = db
    main.config = {
        "storage": {"path": str(data_dir / "blobs"), "reserve_gb": 10},
        "allocation_bytes": 0,
        "server_name": "TestVault",
    }
    from auth import PairingManager
    main.pairing = PairingManager()
    from storage import StorageManager
    main.storage = StorageManager(data_dir / "blobs", ensure_marker=True)

    yield {
        "temp_dir": temp_dir,
        "data_dir": data_dir,
        "db": db,
        "db_path": db_path,
        "owner": owner,
        "member": member,
        "admin_token": admin_token,
    }

    shutil.rmtree(temp_dir, ignore_errors=True)


class TestDeviceRoles:
    """Improvement 6: Device Roles (backup vs manage)."""

    def test_owner_first_device_gets_manage_subsequent_gets_backup(self, test_env):
        client = TestClient(main.app)
        db: Database = test_env["db"]
        owner = test_env["owner"]

        # 1. Open owner pairing
        code1 = main.pairing.open_pairing()
        resp1 = client.post("/api/v1/pair", json={
            "code": code1,
            "device_name": "Owner Phone 1",
            "device_model": "Pixel 9",
        })
        assert resp1.status_code == 200, resp1.text
        data1 = resp1.json()
        token1 = data1["token"]
        device1_id = data1["device_id"]

        tok_row1 = db.lookup_token(hash_token(token1))
        assert tok_row1 is not None
        assert tok_row1.role == "manage", "First owner device must have 'manage' role"

        # 2. Pair second device for owner
        code2 = main.pairing.open_pairing()
        resp2 = client.post("/api/v1/pair", json={
            "code": code2,
            "device_name": "Owner Tablet",
            "device_model": "Pixel Tablet",
        })
        assert resp2.status_code == 200, resp2.text
        data2 = resp2.json()
        token2 = data2["token"]
        device2_id = data2["device_id"]

        tok_row2 = db.lookup_token(hash_token(token2))
        assert tok_row2 is not None
        assert tok_row2.role == "backup", "Subsequent owner device must default to 'backup' role"

    def test_invited_member_device_gets_backup_role(self, test_env):
        client = TestClient(main.app)
        db: Database = test_env["db"]
        member = test_env["member"]

        # Create invite for member
        invite_code = "TESTINV1"
        expires_at = (datetime.now(timezone.utc) + timedelta(days=1)).isoformat()
        db.create_invite(member.id, hash_token(invite_code), created_by=test_env["owner"].id, expires_at=expires_at)

        resp = client.post("/api/v1/pair", json={
            "code": invite_code,
            "device_name": "Member Phone",
            "device_model": "Galaxy S24",
        })
        assert resp.status_code == 200, resp.text
        token = resp.json()["token"]

        tok_row = db.lookup_token(hash_token(token))
        assert tok_row is not None
        assert tok_row.role == "backup", "Invited user devices must default to 'backup' role"

    def test_backup_role_token_blocked_from_all_admin_endpoints(self, test_env):
        client = TestClient(main.app)
        db: Database = test_env["db"]
        owner = test_env["owner"]

        # Create backup role device and token for owner
        dev = db.create_device(owner.id, "Backup Only Device", "TestModel", "backup-only-device")
        backup_token = create_bearer_token()
        db.store_token(hash_token(backup_token), owner.id, dev.id, role="backup")

        headers = {"Authorization": f"Bearer {backup_token}"}

        # Backup token can access normal endpoints
        me_resp = client.get("/api/v1/me", headers=headers)
        assert me_resp.status_code == 200
        assert me_resp.json()["device_role"] == "backup"

        # Backup token is rejected with 403 on EVERY admin endpoint
        admin_endpoints = [
            ("GET", "/api/v1/admin/overview", None),
            ("GET", "/api/v1/admin/users", None),
            ("POST", "/api/v1/admin/users", {"display_name": "New User"}),
            ("PATCH", f"/api/v1/admin/users/{owner.id}", {"display_name": "Updated"}),
            ("DELETE", f"/api/v1/admin/users/{owner.id}", None),
            ("POST", f"/api/v1/admin/users/{owner.id}/restore", None),
            ("POST", f"/api/v1/admin/users/{owner.id}/invite", None),
            ("DELETE", f"/api/v1/admin/users/{owner.id}/devices/{dev.id}", None),
            ("GET", f"/api/v1/admin/users/{owner.id}/devices", None),
            ("PATCH", f"/api/v1/admin/users/{owner.id}/devices/{dev.id}", {"role": "manage"}),
            ("POST", "/api/v1/admin/pairing/open", None),
            ("POST", "/api/v1/admin/storage/allocation", {"allocation_bytes": 1000}),
        ]

        for method, path, body in admin_endpoints:
            if method == "GET":
                res = client.get(path, headers=headers)
            elif method == "POST":
                res = client.post(path, json=body or {}, headers=headers)
            elif method == "PATCH":
                res = client.patch(path, json=body or {}, headers=headers)
            elif method == "DELETE":
                res = client.delete(path, headers=headers)
            assert res.status_code == 403, f"Endpoint {method} {path} should return 403 for backup token, got {res.status_code}"
            assert res.json().get("detail", {}).get("error") == "insufficient_device_role"

    def test_promote_and_demote_device_role(self, test_env):
        client = TestClient(main.app)
        db: Database = test_env["db"]
        owner = test_env["owner"]

        # Create two devices for owner: one manage, one backup
        dev_manage = db.create_device(owner.id, "Manage Device", "Pixel 9", "manage-device")
        tok_manage = create_bearer_token()
        db.store_token(hash_token(tok_manage), owner.id, dev_manage.id, role="manage")

        dev_backup = db.create_device(owner.id, "Backup Device", "Pixel Tablet", "backup-device")
        tok_backup = create_bearer_token()
        db.store_token(hash_token(tok_backup), owner.id, dev_backup.id, role="backup")

        admin_headers = {"Authorization": f"Bearer {tok_manage}"}

        # List devices for user
        list_resp = client.get(f"/api/v1/admin/users/{owner.id}/devices", headers=admin_headers)
        assert list_resp.status_code == 200
        devices = list_resp.json()
        assert len(devices) == 2
        role_map = {d["id"]: d["role"] for d in devices}
        assert role_map[dev_manage.id] == "manage"
        assert role_map[dev_backup.id] == "backup"

        # Promote backup device to manage
        promote_resp = client.patch(
            f"/api/v1/admin/users/{owner.id}/devices/{dev_backup.id}",
            json={"role": "manage"},
            headers=admin_headers,
        )
        assert promote_resp.status_code == 200
        assert promote_resp.json()["role"] == "manage"

        # Now tok_backup CAN access admin endpoints!
        backup_headers = {"Authorization": f"Bearer {tok_backup}"}
        overview_resp = client.get("/api/v1/admin/overview", headers=backup_headers)
        assert overview_resp.status_code == 200

        # Attempt to demote dev_backup back to backup
        demote_resp = client.patch(
            f"/api/v1/admin/users/{owner.id}/devices/{dev_backup.id}",
            json={"role": "backup"},
            headers=admin_headers,
        )
        assert demote_resp.status_code == 200
        assert demote_resp.json()["role"] == "backup"

        # Cannot demote dev_manage because it's now the only remaining manage device!
        lockout_resp = client.patch(
            f"/api/v1/admin/users/{owner.id}/devices/{dev_manage.id}",
            json={"role": "backup"},
            headers=admin_headers,
        )
        assert lockout_resp.status_code == 400
        assert lockout_resp.json()["detail"]["error"] == "cannot_demote_last_manage_device"


class TestDatabaseBackupRestore:
    """Improvement 8: Scheduled database backup & restore."""

    def test_backup_creation_and_rotation(self, test_env):
        db_path: Path = test_env["db_path"]
        backups_dir = test_env["data_dir"] / "backups"

        # Create 16 backups in sequence with tiny delays
        backup_paths = []
        for i in range(16):
            time.sleep(0.01)
            bpath = create_database_backup(db_path, backups_dir, max_keep=14)
            assert bpath.exists()
            backup_paths.append(bpath)

        # Ensure only 14 files remain
        remaining = sorted(backups_dir.glob("piphotos-*.db"))
        assert len(remaining) == 14
        # Oldest two should have been deleted
        assert backup_paths[0] not in remaining
        assert backup_paths[1] not in remaining
        assert backup_paths[-1] in remaining

    def test_restore_database(self, test_env):
        db_path: Path = test_env["db_path"]
        db: Database = test_env["db"]
        backups_dir = test_env["data_dir"] / "backups"

        # Create a user and a backup
        u1 = db.create_user("User Before Backup", role="member", quota_bytes=1000)
        backup_file = create_database_backup(db_path, backups_dir)

        # Modify database: create another user and delete u1
        u2 = db.create_user("User After Backup", role="member", quota_bytes=2000)
        db.delete_user(u1.id)
        assert db.get_user(u1.id) is None
        assert db.get_user(u2.id) is not None

        # Restore from backup_file
        safety_copy = restore_database_backup(backup_file, db_path)
        assert safety_copy.exists()

        # Reopen database and verify state is restored
        restored_db = Database(db_path)
        assert restored_db.get_user(u1.id) is not None
        assert restored_db.get_user(u2.id) is None
        assert restored_db.get_user(u1.id).name == "User Before Backup"

    def test_cli_backup_and_restore_commands(self, test_env):
        from piphotos_server.cli import main as cli_main
        data_dir = test_env["data_dir"]
        db: Database = test_env["db"]

        # Run backup via CLI
        ret = cli_main(["backup", "--data-dir", str(data_dir)])
        assert ret == 0

        backups_dir = data_dir / "backups"
        backups = list(backups_dir.glob("piphotos-*.db"))
        assert len(backups) == 1
        backup_file = backups[0]

        # Modify database
        user = db.create_user("Temporary User", role="member", quota_bytes=5000)
        assert db.get_user(user.id) is not None

        # Restore via CLI
        ret_restore = cli_main(["restore", str(backup_file), "--data-dir", str(data_dir)])
        assert ret_restore == 0

        # Verify restored DB does not contain Temporary User
        check_db = Database(test_env["db_path"])
        assert check_db.get_user(user.id) is None


class TestDesktopGUI:
    """Native Desktop GUI initialization and structure tests."""

    def test_gui_initialization_headless(self, test_env, monkeypatch):
        # Prevent tkinter window from blocking or opening an actual GUI loop during CI
        import customtkinter as ctk
        from piphotos_server.gui import PiPhotosApp

        data_dir = test_env["data_dir"]
        app = PiPhotosApp(data_dir=data_dir, start_in_tray=True)
        assert app is not None
        assert app.ctrl is not None
        assert "status" in app.views
        assert "users" in app.views
        assert "pair" in app.views
        assert "storage" in app.views
        assert "settings" in app.views

        # Cleanly quit headless instance
        app.quit_app()
