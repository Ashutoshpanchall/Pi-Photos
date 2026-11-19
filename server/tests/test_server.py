"""Tests for the PiPhotos storage node server."""

from __future__ import annotations

import hashlib
import os
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

import pytest

# Add server directory to path
sys.path.insert(0, str(Path(__file__).parent.parent))

from database import Database
from auth import PairingManager, create_bearer_token, hash_token
from storage import StorageManager, slugify, sanitise_component, sanitise_filename


# ---------------------------------------------------------------------------
# Database tests
# ---------------------------------------------------------------------------

class TestDatabase:
    """Tests for the SQLite database layer."""

    def setup_method(self):
        self.tmp = tempfile.mkdtemp()
        self.db = Database(os.path.join(self.tmp, "test.db"))

    def test_create_and_get_user(self):
        user = self.db.create_user("Alice", "owner", 1024 * 1024)
        assert user.name == "Alice"
        assert user.role == "owner"
        assert user.is_active is True

        fetched = self.db.get_user(user.id)
        assert fetched is not None
        assert fetched.name == "Alice"

    def test_list_users(self):
        self.db.create_user("Alice", "owner", 1024)
        self.db.create_user("Bob", "member", 2048)
        users = self.db.list_users()
        assert len(users) == 2
        assert users[0].name == "Alice"
        assert users[1].name == "Bob"

    def test_update_user(self):
        user = self.db.create_user("Alice", "owner", 1024)
        self.db.update_user(user.id, name="Alicia", quota_bytes=2048)
        updated = self.db.get_user(user.id)
        assert updated is not None
        assert updated.name == "Alicia"
        assert updated.quota_bytes == 2048

    def test_delete_user(self):
        user = self.db.create_user("Alice", "owner", 1024)
        self.db.delete_user(user.id)
        assert self.db.get_user(user.id) is None

    def test_create_device(self):
        user = self.db.create_user("Alice", "owner", 1024)
        device = self.db.create_device(user.id, "Pixel 7", "google pixel 7", "pixel-7")
        assert device.name == "Pixel 7"
        assert device.slug == "pixel-7"

    def test_list_devices(self):
        user = self.db.create_user("Alice", "owner", 1024)
        self.db.create_device(user.id, "Phone A", "model a", "phone-a")
        self.db.create_device(user.id, "Phone B", "model b", "phone-b")
        devices = self.db.list_devices(user.id)
        assert len(devices) == 2

    def test_store_and_lookup_token(self):
        user = self.db.create_user("Alice", "owner", 1024)
        device = self.db.create_device(user.id, "Phone", "model", "phone")
        token = "test_token_123"
        token_hash = hashlib.sha256(token.encode()).hexdigest()
        self.db.store_token(token_hash, user.id, device.id)

        result = self.db.lookup_token(token_hash)
        assert result is not None
        assert result.user_id == user.id
        assert result.device_id == device.id

    def test_lookup_missing_token(self):
        result = self.db.lookup_token("nonexistent")
        assert result is None

    def test_file_operations(self):
        user = self.db.create_user("Alice", "owner", 10 * 1024)
        device = self.db.create_device(user.id, "Phone", "model", "phone")

        # Insert file
        f = self.db.insert_file(
            user.id, device.id, "Camera",
            "IMG_001.jpg", "abc123" * 10 + "abcd",
            4096, 1700000000,
        )
        assert f.size == 4096

        # Check existence
        assert self.db.file_exists_for_user(user.id, "abc123" * 10 + "abcd")
        assert not self.db.file_exists_for_user(user.id, "nonexistent")

        # Storage used
        used = self.db.user_storage_used(user.id)
        assert used == 4096

        # File count
        count = self.db.user_file_count(user.id)
        assert count == 1

    def test_folder_stats(self):
        user = self.db.create_user("Alice", "owner", 10 * 1024)
        device = self.db.create_device(user.id, "Phone", "model", "phone")

        self.db.insert_file(user.id, device.id, "Camera", "a.jpg", "h1" * 32, 100, None)
        self.db.insert_file(user.id, device.id, "Camera", "b.jpg", "h2" * 32, 200, None)
        self.db.insert_file(user.id, device.id, "Screenshots", "c.png", "h3" * 32, 50, None)

        stats = self.db.device_folder_stats(user.id, device.id)
        assert len(stats) == 2

        camera = next(s for s in stats if s.folder == "Camera")
        assert camera.item_count == 2
        assert camera.total_bytes == 300

    def test_partial_upload(self):
        user = self.db.create_user("Alice", "owner", 1024)
        sha = "a" * 64

        # No partial initially
        assert self.db.get_partial_upload(user.id, sha) is None

        # Create partial
        self.db.upsert_partial_upload(user.id, sha, 1000, "/tmp/test.part")
        result = self.db.get_partial_upload(user.id, sha)
        assert result is not None
        assert result[0] == 1000

        # Update partial
        self.db.upsert_partial_upload(user.id, sha, 2000, "/tmp/test.part")
        result = self.db.get_partial_upload(user.id, sha)
        assert result is not None
        assert result[0] == 2000

        # Delete partial
        self.db.delete_partial_upload(user.id, sha)
        assert self.db.get_partial_upload(user.id, sha) is None

    def test_audit_log(self):
        self.db.audit("user1", "login", detail="From 192.168.1.1")
        logs = self.db.list_audit_log()
        assert len(logs) == 1
        assert logs[0].action == "login"


# ---------------------------------------------------------------------------
# Auth tests
# ---------------------------------------------------------------------------

class TestPairingManager:
    """Tests for the pairing code manager."""

    def test_generate_and_validate(self):
        pm = PairingManager()
        code = pm.generate_code()
        assert len(code) == 6
        assert code.isdigit()
        assert pm.is_open
        assert pm.validate_code(code)
        # Code is consumed after validation
        assert not pm.is_open

    def test_wrong_code(self):
        pm = PairingManager()
        pm.generate_code()
        assert not pm.validate_code("000000")

    def test_code_expiry(self):
        pm = PairingManager()
        pm.generate_code()
        # Simulate expiry
        pm._expires_at = 0
        assert not pm.is_open
        assert not pm.validate_code(pm._code or "")

    def test_disabled(self):
        pm = PairingManager()
        pm.generate_code()
        pm.set_enabled(False)
        assert not pm.is_open

    def test_single_use(self):
        pm = PairingManager()
        code = pm.generate_code()
        assert pm.validate_code(code)
        assert not pm.validate_code(code)  # second use fails


class TestTokens:
    """Tests for token creation and hashing."""

    def test_bearer_token_uniqueness(self):
        t1 = create_bearer_token()
        t2 = create_bearer_token()
        assert t1 != t2
        assert len(t1) > 20

    def test_hash_deterministic(self):
        assert hash_token("abc") == hash_token("abc")
        assert hash_token("abc") != hash_token("def")


# ---------------------------------------------------------------------------
# Storage tests
# ---------------------------------------------------------------------------

class TestSlugify:
    """Tests for the slugify function."""

    def test_basic(self):
        assert slugify("Home Vault") == "home-vault"

    def test_special_chars(self):
        assert slugify("Ashutosh's Pi <3>") == "ashutosh-s-pi-3"

    def test_unicode(self):
        result = slugify("Ñoño Réné")
        assert ".." not in result
        assert "/" not in result

    def test_empty(self):
        assert slugify("") == "unnamed"

    def test_max_length(self):
        result = slugify("a" * 300)
        assert len(result) <= 200


class TestSanitiseComponent:
    """Tests for path component sanitisation."""

    def test_normal(self):
        assert sanitise_component("Camera") == "Camera"

    def test_dotdot_rejected(self):
        with pytest.raises(ValueError):
            sanitise_component("..")

    def test_empty_rejected(self):
        with pytest.raises(ValueError):
            sanitise_component("")

    def test_special_chars_replaced(self):
        result = sanitise_component('file<name>.txt')
        assert "<" not in result
        assert ">" not in result

    def test_control_chars_replaced(self):
        result = sanitise_component("hello\x00world")
        assert "\x00" not in result


class TestSanitiseFilename:
    """Tests for filename sanitisation."""

    def test_normal(self):
        assert sanitise_filename("IMG_001.jpg") == "IMG_001.jpg"

    def test_path_separator_rejected(self):
        with pytest.raises(ValueError):
            sanitise_filename("../../etc/passwd")

    def test_empty_rejected(self):
        with pytest.raises(ValueError):
            sanitise_filename("")


class TestStorageManager:
    """Tests for the storage manager."""

    def setup_method(self):
        self.tmp = tempfile.mkdtemp()
        self.sm = StorageManager(self.tmp)

    def test_user_dir(self):
        path = self.sm.user_dir("Alice")
        assert "alice" in str(path)
        assert "users" in str(path)

    def test_device_dir(self):
        path = self.sm.device_dir("Alice", "Pixel 7")
        assert "devices" in str(path)

    def test_folder_dir(self):
        path = self.sm.folder_dir("Alice", "Pixel 7", "Camera")
        assert "Camera" in str(path)

    def test_folder_dir_with_date(self):
        # 2026-01-15 00:00:00 UTC
        ts = 1768435200
        path = self.sm.folder_dir(
            "Alice", "Pixel 7", "Camera",
            date_taken=ts, organise_by_date=True,
        )
        assert "2026" in str(path)
        assert "01" in str(path)

    def test_resolve_filename_no_collision(self):
        d = Path(self.tmp) / "test"
        d.mkdir()
        result = self.sm.resolve_filename(d, "photo.jpg", "abc123")
        assert result == "photo.jpg"

    def test_resolve_filename_with_collision(self):
        d = Path(self.tmp) / "test"
        d.mkdir()
        (d / "photo.jpg").write_text("existing")
        result = self.sm.resolve_filename(d, "photo.jpg", "different_hash")
        assert result == "photo (1).jpg"

    def test_part_file_path(self):
        path = self.sm.part_file_path("Alice", "a" * 64)
        assert path.suffix == ".part"
        assert "parts" in str(path)

    def test_atomic_move(self):
        src = Path(self.tmp) / "src.txt"
        src.write_text("hello")
        dst = Path(self.tmp) / "dst" / "out.txt"
        self.sm.atomic_move(src, dst)
        assert dst.read_text() == "hello"
        assert not src.exists()


# ---------------------------------------------------------------------------
# API tests (FastAPI TestClient)
# ---------------------------------------------------------------------------

class TestAPI:
    """Integration tests for the FastAPI endpoints."""

    def setup_method(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["PIPHOTOS_STORAGE"] = os.path.join(self.tmp, "storage")
        os.environ["PIPHOTOS_DB"] = os.path.join(self.tmp, "test.db")
        os.environ["PIPHOTOS_CERTS"] = os.path.join(self.tmp, "certs")
        os.environ["PIPHOTOS_CONFIG"] = os.path.join(self.tmp, "nonexistent.json")
        os.environ["PIPHOTOS_NO_MDNS"] = "1"
        os.environ["PIPHOTOS_NO_QR"] = "1"
        os.environ["PIPHOTOS_DISK_RESERVE_FRACTION"] = "0.0"

        from fastapi.testclient import TestClient
        from main import app
        self._tc = TestClient(app)
        self.client = self._tc.__enter__()

    def teardown_method(self):
        self._tc.__exit__(None, None, None)

    def test_get_info(self):
        resp = self.client.get("/api/v1/info")
        assert resp.status_code == 200
        data = resp.json()
        assert "id" in data
        assert "name" in data
        assert data["protocol_version"] == 1

    def test_pair_no_code(self):
        resp = self.client.post("/api/v1/pair", json={
            "code": "000000",
            "device_name": "Test Phone",
            "device_model": "test model",
        })
        # Code is consumed during lifespan setup, so this should fail
        assert resp.status_code in (401, 409)

    def test_status_no_auth(self):
        resp = self.client.get("/api/v1/status")
        assert resp.status_code == 401

    def test_check_upload_no_auth(self):
        resp = self.client.head("/api/v1/upload/" + "a" * 64)
        assert resp.status_code == 401

    def test_full_pair_and_upload_flow(self):
        """Test the complete pairing and upload flow."""
        from main import pairing, db

        # Generate a new code
        code = pairing.generate_code()

        # Pair
        resp = self.client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Test Phone",
            "device_model": "test model",
        })
        assert resp.status_code == 200, resp.text
        pair_data = resp.json()
        token = pair_data["token"]
        device_id = pair_data["device_id"]
        headers = {"Authorization": f"Bearer {token}"}

        # Check status
        resp = self.client.get("/api/v1/status", headers=headers)
        assert resp.status_code == 200
        status = resp.json()
        assert status["user"]["file_count"] == 0

        # Upload a small file
        content = b"Hello, PiPhotos!"
        sha = hashlib.sha256(content).hexdigest()

        # Check if exists (should be 404)
        resp = self.client.head(f"/api/v1/upload/{sha}", headers=headers)
        assert resp.status_code == 404

        # Upload
        resp = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": device_id, "folder": "Camera", "name": "test.txt", "taken": 1700000000},
            headers={**headers, "Content-Length": str(len(content))},
            content=content,
        )
        assert resp.status_code == 200, resp.text
        result = resp.json()
        assert result["sha256"] == sha
        assert result["size"] == len(content)

        # Check if exists now (should be 200)
        resp = self.client.head(f"/api/v1/upload/{sha}", headers=headers)
        assert resp.status_code == 200

        # Check status again
        resp = self.client.get("/api/v1/status", headers=headers)
        status = resp.json()
        assert status["user"]["file_count"] == 1
        assert status["user"]["used_bytes"] == len(content)

        # Check device folders
        resp = self.client.get(f"/api/v1/devices/{device_id}/folders", headers=headers)
        assert resp.status_code == 200
        folders = resp.json()
        assert len(folders["folders"]) == 1
        assert folders["folders"][0]["name"] == "Camera"
        assert folders["folders"][0]["item_count"] == 1

    def test_upload_hash_mismatch(self):
        """Test that hash mismatch is properly rejected."""
        from main import pairing, db

        code = pairing.generate_code()
        resp = self.client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Test Phone",
            "device_model": "test model",
        })
        pair_data = resp.json()
        token = pair_data["token"]
        device_id = pair_data["device_id"]
        headers = {"Authorization": f"Bearer {token}"}

        content = b"Real content"
        wrong_sha = "b" * 64  # Wrong hash

        resp = self.client.put(
            f"/api/v1/upload/{wrong_sha}",
            params={"device": device_id, "folder": "Camera", "name": "test.txt"},
            headers={**headers, "Content-Length": str(len(content))},
            content=content,
        )
        assert resp.status_code == 409

    def test_invalid_sha_format(self):
        """Test that invalid SHA-256 format is rejected."""
        from main import pairing

        code = pairing.generate_code()
        resp = self.client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Test Phone",
            "device_model": "test model",
        })
        pair_data = resp.json()
        headers = {"Authorization": f"Bearer {pair_data['token']}"}

        resp = self.client.head("/api/v1/upload/not-a-sha", headers=headers)
        assert resp.status_code == 400

    def test_upload_disk_reserve_breach(self):
        """Test that upload fails with 507 when disk reserve is breached."""
        from main import pairing

        code = pairing.generate_code()
        resp = self.client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Test Phone",
            "device_model": "test model",
        })
        pair_data = resp.json()
        token = pair_data["token"]
        device_id = pair_data["device_id"]
        headers = {"Authorization": f"Bearer {token}"}

        content = b"Some data"
        sha = hashlib.sha256(content).hexdigest()

        # Set disk reserve fraction artificially to 1.0 (100% reserved)
        os.environ["PIPHOTOS_DISK_RESERVE_FRACTION"] = "1.0"
        try:
            resp = self.client.put(
                f"/api/v1/upload/{sha}",
                params={"device": device_id, "folder": "Camera", "name": "test.txt"},
                headers={**headers, "Content-Length": str(len(content))},
                content=content,
            )
            assert resp.status_code == 507
            assert resp.json()["detail"]["error"] == "quota_exceeded"
        finally:
            os.environ["PIPHOTOS_DISK_RESERVE_FRACTION"] = "0.0"


# ---------------------------------------------------------------------------
# TLS tests
# ---------------------------------------------------------------------------

class TestTLS:
    """Tests for TLS certificate generation."""

    def test_generate_cert(self):
        from tls_cert import generate_self_signed_cert, get_cert_fingerprint

        tmp = tempfile.mkdtemp()
        cert_path = Path(tmp) / "cert.pem"
        key_path = Path(tmp) / "key.pem"

        fp = generate_self_signed_cert(cert_path, key_path)
        assert cert_path.exists()
        assert key_path.exists()
        assert ":" in fp  # Colon-separated hex

        # Fingerprint should be deterministic for same cert
        fp2 = get_cert_fingerprint(cert_path)
        assert fp == fp2


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
