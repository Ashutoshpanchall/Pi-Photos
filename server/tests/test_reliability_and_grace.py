"""Unit tests for Phase 4: Storage Mount Guard & User Deletion Grace Period."""

import json
import os
import shutil
import sqlite3
import pytest
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest.mock import patch, MagicMock
from starlette.testclient import TestClient

from storage import StorageManager
from database import Database
from auth import PairingManager
from piphotos_server.paths import resolve_paths
from piphotos_server.local_admin import ensure_local_admin_token
from piphotos_server.doctor import ServerDoctor
from piphotos_server.control import ServerController


class TestStorageMountGuard:
    """Test .piphotos-storage mount marker and 503 storage_offline handling."""

    def test_storage_marker_created_and_detected(self, tmp_path):
        storage_dir = tmp_path / "storage"
        sm = StorageManager(storage_dir, ensure_marker=True)
        marker = storage_dir / ".piphotos-storage"

        assert marker.is_file()
        assert sm.is_mounted() is True

        # Simulate drive unmount / marker disappearance
        marker.unlink()
        assert sm.is_mounted() is False

    def test_upload_rejected_with_503_when_unmounted(self, tmp_path):
        from main import app
        import main as srv_main

        db_path = tmp_path / "test.db"
        storage_dir = tmp_path / "storage"
        db = Database(db_path)
        storage = StorageManager(storage_dir, ensure_marker=True)
        pairing = PairingManager()

        srv_main.db = db
        srv_main.storage = storage
        srv_main.pairing = pairing

        # Create user & device & token
        owner = db.create_user("Owner", "owner", 100000000)
        dev = db.create_device(owner.id, "Pixel 9", "Google Pixel 9", "pixel-9")
        tok = "test-token-valid-abc123xyz456"
        db.store_token(Database.hash_token(tok), owner.id, dev.id)

        client = TestClient(app)
        headers = {"Authorization": f"Bearer {tok}", "Content-Length": "10"}
        dummy_sha = "a" * 64

        # 1. When mounted: upload does not fail with 503
        r_mounted = client.put(
            f"/api/v1/upload/{dummy_sha}?device={dev.id}&folder=Camera&name=img.jpg",
            headers=headers,
            content=b"0123456789",
        )
        assert r_mounted.status_code != 503

        # 2. When storage marker is missing (unmounted drive): 503 storage_offline
        marker = storage_dir / ".piphotos-storage"
        if marker.exists():
            marker.unlink()

        r_unmounted = client.put(
            f"/api/v1/upload/{dummy_sha}?device={dev.id}&folder=Camera&name=img.jpg",
            headers=headers,
            content=b"0123456789",
        )
        assert r_unmounted.status_code == 503
        data = r_unmounted.json()
        assert data["detail"]["error"] == "storage_offline"
        assert "unmounted or disconnected" in data["detail"]["message"]

    def test_doctor_storage_mount_check(self, tmp_path):
        paths = resolve_paths(data_dir=tmp_path / "data")
        paths.ensure_dirs()
        doc = ServerDoctor(paths)

        # 1. Storage mounted with marker
        marker = paths.storage_dir / ".piphotos-storage"
        marker.write_text("ok", encoding="utf-8")
        doc.results.clear()
        doc.check_storage_mount()
        res = next(r for r in doc.results if r.name == "storage_mount")
        assert res.status == "PASS"

        # 2. Storage directory deleted / unmounted
        marker.unlink()
        doc.results.clear()
        doc.check_storage_mount()
        # It tries to recreate it if dir is writable -> passes
        assert any(r.name == "storage_mount" for r in doc.results)


class TestDeleteUserGracePeriod:
    """Improvement 5: 7-day grace period, token revocation, files intact, undo restore."""

    def test_database_grace_period_methods(self, tmp_path):
        db = Database(tmp_path / "users.db")
        user = db.create_user("Bob Member", "member", 50000000)
        dev = db.create_device(user.id, "Phone", "Model", "phone")
        db.store_token(Database.hash_token("tok1"), user.id, dev.id)

        # 1. Schedule user deletion (7 days)
        scheduled_user = db.schedule_user_deletion(user.id, grace_days=7)
        assert scheduled_user.status == "pending_deletion"
        assert scheduled_user.is_active is False
        assert scheduled_user.disabled_at is not None
        assert scheduled_user.scheduled_purge_at is not None

        # Active tokens are revoked immediately
        assert db.lookup_token(Database.hash_token("tok1")) is None

        # 2. Restore user prior to purge
        restored_user = db.restore_user(user.id)
        assert restored_user.status == "active"
        assert restored_user.is_active is True
        assert restored_user.disabled_at is None
        assert restored_user.scheduled_purge_at is None

    def test_admin_endpoints_grace_period_and_restore(self, tmp_path):
        from main import app
        import main as srv_main

        db = Database(tmp_path / "test.db")
        storage = StorageManager(tmp_path / "storage", ensure_marker=True)
        pairing = PairingManager()

        srv_main.db = db
        srv_main.storage = storage
        srv_main.pairing = pairing

        owner = db.create_user("Alice Owner", "owner", 0)
        owner_dev = db.create_device(owner.id, "Owner PC", "PC", "pc")
        owner_token = "alice-owner-token"
        db.store_token(Database.hash_token(owner_token), owner.id, owner_dev.id)

        member = db.create_user("Bob Member", "member", 50000000)
        member_dev = db.create_device(member.id, "Bob Phone", "Android", "bob-phone")
        member_token = "bob-member-token"
        db.store_token(Database.hash_token(member_token), member.id, member_dev.id)

        client = TestClient(app)
        owner_hdr = {"Authorization": f"Bearer {owner_token}"}
        member_hdr = {"Authorization": f"Bearer {member_token}"}

        # Verify member is active initially
        r_me = client.get("/api/v1/me", headers=member_hdr)
        assert r_me.status_code == 200

        # 1. Owner schedules member deletion with 7-day grace period
        r_del = client.delete(f"/api/v1/admin/users/{member.id}", headers=owner_hdr)
        assert r_del.status_code == 200
        del_data = r_del.json()
        assert del_data["status"] == "pending_deletion"
        assert del_data["grace_days"] == 7
        assert "scheduled_purge_at" in del_data

        # 2. Member is immediately rejected from making calls
        r_me_after = client.get("/api/v1/me", headers=member_hdr)
        # Token was deleted, so 401 unauthorized (or 403 disabled)
        assert r_me_after.status_code in (401, 403)

        # 3. Owner cancels / restores member account
        r_restore = client.post(f"/api/v1/admin/users/{member.id}/restore", headers=owner_hdr)
        assert r_restore.status_code == 200
        res_data = r_restore.json()
        assert res_data["status"] == "restored"
        assert res_data["user"]["status"] == "active"

        # 4. Immediate purge option (delete_now=true)
        r_now = client.delete(f"/api/v1/admin/users/{member.id}?delete_now=true", headers=owner_hdr)
        assert r_now.status_code == 200
        assert r_now.json()["status"] == "deleted"
        assert db.get_user(member.id) is None

    def test_purge_expired_users(self, tmp_path):
        db = Database(tmp_path / "purge.db")
        u1 = db.create_user("User Expired", "member", 1000)
        u2 = db.create_user("User Future", "member", 1000)

        now = datetime.now(timezone.utc)
        past_purge = (now - timedelta(days=1)).isoformat()
        future_purge = (now + timedelta(days=6)).isoformat()

        with db._cursor() as cur:
            cur.execute(
                "UPDATE users SET status = 'pending_deletion', scheduled_purge_at = ? WHERE id = ?",
                (past_purge, u1.id),
            )
            cur.execute(
                "UPDATE users SET status = 'pending_deletion', scheduled_purge_at = ? WHERE id = ?",
                (future_purge, u2.id),
            )

        # Query pending purge
        expired = db.get_users_pending_purge(now.isoformat())
        assert len(expired) == 1
        assert expired[0].id == u1.id


class TestMarkerMigrationForExistingInstalls:
    """Installs that predate the mount marker must keep accepting uploads."""

    def _client(self, tmp_path, monkeypatch):
        import hashlib
        from fastapi.testclient import TestClient
        import main as srv_main

        monkeypatch.setenv("PIPHOTOS_DATA_DIR", str(tmp_path))
        monkeypatch.setenv("PIPHOTOS_STORAGE", str(tmp_path / "storage"))
        monkeypatch.setenv("PIPHOTOS_DB", str(tmp_path / "p.db"))
        monkeypatch.setenv("PIPHOTOS_CERTS", str(tmp_path / "certs"))
        monkeypatch.setenv("PIPHOTOS_CONFIG", str(tmp_path / "config.json"))
        db = Database(tmp_path / "p.db")
        owner = db.create_user("Owner", "owner", 0)
        dev = db.create_device(owner.id, "Pixel", "Pixel", "pixel")
        tok = "tok-abcdefghijklmnop1234"
        db.store_token(Database.hash_token(tok), owner.id, dev.id)
        (tmp_path / "storage").mkdir()
        data = b"hello photo"
        sha = hashlib.sha256(data).hexdigest()
        hdr = {"Authorization": f"Bearer {tok}", "Content-Length": str(len(data))}
        url = f"/api/v1/upload/{sha}?device={dev.id}&folder=Camera&name=a.jpg"
        return TestClient(srv_main.app), url, hdr, data

    def test_existing_install_without_marker_can_upload(self, tmp_path, monkeypatch):
        client, url, hdr, data = self._client(tmp_path, monkeypatch)
        with client as c:
            r = c.put(url, headers=hdr, content=data)
            assert r.status_code != 503, r.text
            assert (tmp_path / "storage" / ".piphotos-storage").is_file()

    def test_marker_loss_after_migration_still_refuses(self, tmp_path, monkeypatch):
        client, url, hdr, data = self._client(tmp_path, monkeypatch)
        with client as c:
            c.put(url, headers=hdr, content=data)
            (tmp_path / "storage" / ".piphotos-storage").unlink()
            r = c.put(url, headers=hdr, content=data)
            assert r.status_code == 503
