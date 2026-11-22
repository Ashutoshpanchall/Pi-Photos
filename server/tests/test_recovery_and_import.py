"""Tests for Phase 8: Disaster Recovery & Desktop Import.

Covers:
- Improvement 9: Hash list endpoint (/api/v1/files/hashes)
- Improvement 10: Import command for PC folder (piphotos-server import)
- Improvement 11: Rescan command to rebuild index from filesystem (piphotos-server rescan)
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path
import pytest
from starlette.testclient import TestClient

from auth import create_bearer_token, hash_token, PairingManager
from database import Database
import main
from piphotos_server.control import ServerController
from storage import StorageManager


@pytest.fixture
def recovery_env(tmp_path):
    data_dir = tmp_path / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    db_path = data_dir / "piphotos.db"
    blobs_dir = data_dir / "blobs"

    db = Database(db_path)
    owner = db.create_user("Owner User", role="owner", quota_bytes=100 * 1024 * 1024)
    member = db.create_user("Member User", role="member", quota_bytes=50 * 1024 * 1024)

    dev_owner = db.create_device(owner.id, "Owner PC", "PC", "owner-pc")
    tok_owner = create_bearer_token()
    db.store_token(hash_token(tok_owner), owner.id, dev_owner.id, role="manage")

    dev_member = db.create_device(member.id, "Member Phone", "Android", "member-phone")
    tok_member = create_bearer_token()
    db.store_token(hash_token(tok_member), member.id, dev_member.id, role="backup")

    (data_dir / ".piphotos-storage").touch()
    admin_token_path = data_dir / "local-admin.token"
    admin_token = create_bearer_token()
    admin_token_path.write_text(admin_token, encoding="utf-8")

    # Set app globals
    main.db = db
    main.config = {
        "storage": {"path": str(blobs_dir), "reserve_gb": 10},
        "allocation_bytes": 0,
        "server_name": "TestVault",
    }
    (data_dir / "config.json").write_text(json.dumps(main.config), encoding="utf-8")
    main.storage = StorageManager(blobs_dir, ensure_marker=True)
    main.pairing = PairingManager()

    yield {
        "data_dir": data_dir,
        "db": db,
        "db_path": db_path,
        "blobs_dir": blobs_dir,
        "owner": owner,
        "member": member,
        "dev_owner": dev_owner,
        "tok_owner": tok_owner,
        "tok_member": tok_member,
    }


class TestHashListEndpoint:
    """Improvement 9: /api/v1/files/hashes cursor endpoint."""

    def test_hash_list_pagination_and_user_isolation(self, recovery_env):
        client = TestClient(main.app)
        db: Database = recovery_env["db"]
        owner = recovery_env["owner"]
        member = recovery_env["member"]
        dev_owner = recovery_env["dev_owner"]

        # Insert 5 files for owner and 2 files for member
        for i in range(5):
            sha = hashlib.sha256(f"owner_data_{i}".encode()).hexdigest()
            db.record_file(
                user_id=owner.id,
                device_id=dev_owner.id,
                folder="camera",
                relative_path=f"img_{i}.jpg",
                sha256=sha,
                size=1024,
                date_taken=1700000000 + i,
            )

        for i in range(2):
            sha = hashlib.sha256(f"member_data_{i}".encode()).hexdigest()
            db.record_file(
                user_id=member.id,
                device_id=recovery_env["dev_owner"].id,
                folder="camera",
                relative_path=f"m_img_{i}.jpg",
                sha256=sha,
                size=2048,
                date_taken=1700000000 + i,
            )

        headers_owner = {"Authorization": f"Bearer {recovery_env['tok_owner']}"}
        headers_member = {"Authorization": f"Bearer {recovery_env['tok_member']}"}

        # 1. Owner requests limit=2
        r1 = client.get("/api/v1/files/hashes?limit=2", headers=headers_owner)
        assert r1.status_code == 200
        data1 = r1.json()
        assert len(data1["items"]) == 2
        assert data1["has_more"] is True
        next_cursor = data1["next_cursor"]

        # Next page limit=3
        r2 = client.get(f"/api/v1/files/hashes?cursor={next_cursor}&limit=3", headers=headers_owner)
        assert r2.status_code == 200
        data2 = r2.json()
        assert len(data2["items"]) == 3
        assert data2["has_more"] is False

        # Member requests hashes -> only sees member's 2 files (cross-user isolation)
        rm = client.get("/api/v1/files/hashes", headers=headers_member)
        assert rm.status_code == 200
        datam = rm.json()
        assert len(datam["items"]) == 2
        expected_member_hashes = {hashlib.sha256(f"member_data_{i}".encode()).hexdigest() for i in range(2)}
        assert {item["sha256"] for item in datam["items"]} == expected_member_hashes


class TestDesktopImport:
    """Improvement 10: Desktop import command (piphotos-server import)."""

    def test_import_photos_from_folder(self, recovery_env, tmp_path):
        import_src = tmp_path / "camera_photos"
        import_src.mkdir()

        # Create dummy photos
        (import_src / "photo1.jpg").write_bytes(b"PHOTO1_RAW_BYTES_SAMPLE")
        (import_src / "photo2.png").write_bytes(b"PHOTO2_RAW_BYTES_SAMPLE")
        (import_src / "notes.txt").write_text("Ignore me, not a photo", encoding="utf-8")

        ctrl = ServerController(data_dir=recovery_env["data_dir"])
        result = ctrl.import_folder(import_src, user_id=recovery_env["owner"].id)

        assert result["imported"] == 2
        assert result["skipped"] == 1  # notes.txt skipped

        # Verify files are now registered in database
        db: Database = recovery_env["db"]
        owner_files = db.list_files(recovery_env["owner"].id)
        assert len(owner_files) == 2

        # Verify content download works via API
        client = TestClient(main.app)
        headers = {"Authorization": f"Bearer {recovery_env['tok_owner']}"}
        resp = client.get(f"/api/v1/files/{owner_files[0].id}/content", headers=headers)
        assert resp.status_code == 200
        assert resp.content in (b"PHOTO1_RAW_BYTES_SAMPLE", b"PHOTO2_RAW_BYTES_SAMPLE")

    def test_import_with_user_slug_and_organise_by_date(self, recovery_env, tmp_path):
        client = TestClient(main.app)
        db: Database = recovery_env["db"]
        owner = recovery_env["owner"]

        # Enable date organisation
        db.update_user(owner.id, organise_by_date=True)

        import_src = tmp_path / "date_photos"
        import_src.mkdir()
        test_bytes = b"PHOTO_DATE_ORG_BYTES"
        (import_src / "dated_pic.jpg").write_bytes(test_bytes)

        ctrl = ServerController(data_dir=recovery_env["data_dir"])
        # Pass user slug instead of raw ID
        result = ctrl.import_folder(import_src, user_id=owner.slug)
        assert result["imported"] == 1

        files = db.list_files(owner.id)
        dated_file = next(f for f in files if f.sha256 == hashlib.sha256(test_bytes).hexdigest())

        headers = {"Authorization": f"Bearer {recovery_env['tok_owner']}"}
        resp = client.get(f"/api/v1/files/{dated_file.id}/content", headers=headers)
        assert resp.status_code == 200
        assert resp.content == test_bytes

    def test_import_boundary_guard_rejects_storage_dir(self, recovery_env):
        from piphotos_server.control import ControlError
        ctrl = ServerController(data_dir=recovery_env["data_dir"])
        with pytest.raises(ControlError) as exc_info:
            ctrl.import_folder(recovery_env["blobs_dir"])
        assert "Cannot import from within" in str(exc_info.value)


class TestRescanStorage:
    """Improvement 11: Rescan command to rebuild index from filesystem."""

    def test_rescan_recovers_missing_database_records(self, recovery_env):
        db: Database = recovery_env["db"]
        blobs_dir: Path = recovery_env["blobs_dir"]
        owner = recovery_env["owner"]

        # Manually write photo file to disk in user storage layout
        user_dir = blobs_dir / "users" / owner.slug / "devices" / "pc" / "imports"
        user_dir.mkdir(parents=True, exist_ok=True)
        photo_file = user_dir / "restored_photo.jpg"
        photo_bytes = b"RECOVERED_PHOTO_BYTES_AFTER_CORRUPTION"
        photo_file.write_bytes(photo_bytes)
        expected_sha = hashlib.sha256(photo_bytes).hexdigest()

        # Verify DB does NOT have this file yet
        assert not db.file_exists_for_user(owner.id, expected_sha)

        # Run rescan
        ctrl = ServerController(data_dir=recovery_env["data_dir"])
        rescan_result = ctrl.rescan_storage()

        assert rescan_result["indexed"] >= 1
        assert db.file_exists_for_user(owner.id, expected_sha)

        # Verify content download works for recovered file
        client = TestClient(main.app)
        files = db.list_files(owner.id)
        recovered_file = next(f for f in files if f.sha256 == expected_sha)
        headers = {"Authorization": f"Bearer {recovery_env['tok_owner']}"}
        resp = client.get(f"/api/v1/files/{recovered_file.id}/content", headers=headers)
        assert resp.status_code == 200
        assert resp.content == photo_bytes
