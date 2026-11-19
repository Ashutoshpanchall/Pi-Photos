"""Comprehensive unit and integration tests for the PiPhotos storage node server."""

from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import os
import shutil
import stat
import sys
import tempfile
import time
from pathlib import Path

import pytest
from cryptography import x509

# Add server directory to path
sys.path.insert(0, str(Path(__file__).parent.parent))

from auth import (
    AuthIdentity,
    IpRateLimiter,
    PairingManager,
    create_bearer_token,
    hash_token,
)
from database import Database
from discovery import PiPhotosAdvertiser, SERVICE_TYPE
from storage import StorageManager, slugify, sanitise_component, sanitise_filename
from tls_cert import (
    generate_self_signed_cert,
    get_cert_fingerprint,
    get_local_ipv4_addresses,
)


# ---------------------------------------------------------------------------
# Database tests
# ---------------------------------------------------------------------------

class TestDatabase:
    """Tests for the SQLite database layer."""

    def setup_method(self):
        self.tmp = tempfile.mkdtemp()
        self.db = Database(os.path.join(self.tmp, "test.db"))

    def test_create_and_get_user(self):
        user = self.db.create_user("Alice", "owner", 1024 * 1024, organise_by_date=True)
        assert user.name == "Alice"
        assert user.role == "owner"
        assert user.is_active is True
        assert user.organise_by_date is True

        fetched = self.db.get_user(user.id)
        assert fetched is not None
        assert fetched.name == "Alice"
        assert fetched.organise_by_date is True

    def test_list_users(self):
        self.db.create_user("Alice", "owner", 1024)
        self.db.create_user("Bob", "member", 2048)
        users = self.db.list_users()
        assert len(users) == 2
        assert users[0].name == "Alice"
        assert users[1].name == "Bob"

    def test_update_user(self):
        user = self.db.create_user("Alice", "owner", 1024)
        self.db.update_user(user.id, name="Alicia", quota_bytes=2048, organise_by_date=True)
        updated = self.db.get_user(user.id)
        assert updated is not None
        assert updated.name == "Alicia"
        assert updated.quota_bytes == 2048
        assert updated.organise_by_date is True

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

    def test_file_operations_and_bulk_check(self):
        user = self.db.create_user("Alice", "owner", 10 * 1024)
        device = self.db.create_device(user.id, "Phone", "model", "phone")

        sha1 = "a" * 64
        sha2 = "b" * 64
        sha3 = "c" * 64

        self.db.insert_file(user.id, device.id, "Camera", "IMG_001.jpg", sha1, 4096, 1700000000)
        self.db.insert_file(user.id, device.id, "Camera", "IMG_002.jpg", sha2, 2048, 1700000001)

        # Existence
        assert self.db.file_exists_for_user(user.id, sha1)
        assert not self.db.file_exists_for_user(user.id, sha3)

        # Bulk check
        existing = self.db.check_hashes_exist(user.id, [sha1, sha2, sha3])
        assert set(existing) == {sha1, sha2}

        # Partial uploads
        self.db.upsert_partial_upload(user.id, sha3, 1024, "/tmp/sha3.part")
        partials = self.db.get_partial_offsets(user.id, [sha1, sha2, sha3])
        assert partials == {sha3: 1024}


# ---------------------------------------------------------------------------
# Auth and Rate Limiting tests
# ---------------------------------------------------------------------------

class TestPairingManagerSecurity:
    """Tests for pairing code security and lifecycle."""

    def test_pairing_code_generation(self):
        pm = PairingManager()
        code = pm.generate_code()
        assert len(code) == 6
        assert code.isdigit()
        assert pm.is_open

    def test_success_closes_pairing_without_auto_generating(self):
        pm = PairingManager()
        code = pm.generate_code()
        valid, reason = pm.validate_code(code)
        assert valid is True
        assert reason == "ok"
        # Closed after single success
        assert pm.is_open is False

    def test_invalidation_after_five_failed_attempts(self):
        pm = PairingManager()
        code = pm.generate_code()

        for i in range(4):
            valid, reason = pm.validate_code("000000")
            assert valid is False
            assert reason == "invalid_code"
            assert pm.is_open is True
            assert pm.failed_attempts == i + 1

        # 5th failed attempt invalidates code
        valid, reason = pm.validate_code("000000")
        assert valid is False
        assert reason == "too_many_attempts"
        assert pm.is_open is False

        # Even with the right code now, it is invalidated
        valid, reason = pm.validate_code(code)
        assert valid is False
        assert pm.is_open is False

    def test_code_expiry(self):
        pm = PairingManager()
        code = pm.generate_code()
        pm._expires_at = time.time() - 1  # Expired
        valid, reason = pm.validate_code(code)
        assert valid is False
        assert reason == "code_expired"
        assert pm.is_open is False


class TestIpRateLimiter:
    """Tests for IP rate limiting."""

    def test_rate_limiting_exceeded(self):
        limiter = IpRateLimiter(max_requests=3, window_seconds=10.0)
        ip = "192.168.1.50"

        assert limiter.is_allowed(ip) is True
        assert limiter.is_allowed(ip) is True
        assert limiter.is_allowed(ip) is True
        # 4th request in window is rejected
        assert limiter.is_allowed(ip) is False

        # Different IP is still allowed
        assert limiter.is_allowed("192.168.1.51") is True


# ---------------------------------------------------------------------------
# Storage tests
# ---------------------------------------------------------------------------

class TestStorageManager:
    """Tests for the storage manager."""

    def setup_method(self):
        self.tmp = tempfile.mkdtemp()
        self.sm = StorageManager(self.tmp)

    def test_user_dir(self):
        path = self.sm.user_dir("Alice")
        assert "alice" in str(path)
        assert "users" in str(path)

    def test_folder_dir_with_date(self):
        ts = 1768435200  # 2026-01-15 UTC
        path = self.sm.folder_dir("Alice", "Pixel 7", "Camera", date_taken=ts, organise_by_date=True)
        assert "2026" in str(path)
        assert "01" in str(path)

    def test_set_mtime(self):
        f = Path(self.tmp) / "test.txt"
        f.write_text("content")
        target_ts = 1700000000
        self.sm.set_mtime(f, target_ts)
        assert int(f.stat().st_mtime) == target_ts

    def test_resolve_filename_collision(self):
        d = Path(self.tmp) / "test"
        d.mkdir()
        (d / "photo.jpg").write_text("existing")
        res = self.sm.resolve_filename(d, "photo.jpg", "sha")
        assert res == "photo (1).jpg"


# ---------------------------------------------------------------------------
# TLS & Certificate SAN tests
# ---------------------------------------------------------------------------

class TestTLS:
    """Tests for TLS certificate generation, SAN, and file permissions."""

    def test_cert_generation_san_and_permissions(self):
        tmp = tempfile.mkdtemp()
        cert_path = Path(tmp) / "certs" / "cert.pem"
        key_path = Path(tmp) / "certs" / "key.pem"

        fp = generate_self_signed_cert(cert_path, key_path, extra_ips=["192.168.1.99"])
        assert cert_path.exists()
        assert key_path.exists()
        assert ":" in fp

        # Verify key file permissions on platforms supporting posix mode
        if hasattr(os, "stat") and os.name == "posix":
            key_mode = stat.S_IMODE(key_path.stat().st_mode)
            assert key_mode == 0o600

        # Verify SAN contains IPs and localhost
        cert_pem = cert_path.read_bytes()
        cert = x509.load_pem_x509_certificate(cert_pem)
        san_ext = cert.extensions.get_extension_for_oid(x509.OID_SUBJECT_ALTERNATIVE_NAME)
        san_names = san_ext.value

        dns_names = san_names.get_values_for_type(x509.DNSName)
        ip_addrs = [str(ip) for ip in san_names.get_values_for_type(x509.IPAddress)]

        assert "piphotos.local" in dns_names
        assert "localhost" in dns_names
        assert "127.0.0.1" in ip_addrs
        assert "192.168.1.99" in ip_addrs


# ---------------------------------------------------------------------------
# Async mDNS discovery test
# ---------------------------------------------------------------------------

class TestAsyncMDNS:
    """Tests for mDNS service advertisement using AsyncZeroconf."""

    def test_mdns_advertisement_and_discovery(self):
        """Runs with mDNS ON and browses with a second Zeroconf instance on same host."""
        async def run_mdns_test():
            from zeroconf.asyncio import AsyncServiceBrowser, AsyncServiceInfo, AsyncZeroconf

            adv = PiPhotosAdvertiser(
                server_id="test-srv-uuid",
                server_name="Test-Vault",
                port=8443,
                tls_fingerprint="AA:BB:CC:DD:EE",
                protocol_version=1,
            )
            ips = await adv.async_start()
            assert len(ips) > 0

            browser_zc = AsyncZeroconf()
            discovered = []

            class Listener:
                def add_service(self, zc, type_, name):
                    discovered.append((type_, name))

                def remove_service(self, zc, type_, name):
                    pass

                def update_service(self, zc, type_, name):
                    pass

            browser = AsyncServiceBrowser(browser_zc.zeroconf, SERVICE_TYPE, listener=Listener())

            try:
                # Wait for mDNS packet on local interface
                for _ in range(30):
                    if any("Test-Vault" in name for _, name in discovered):
                        break
                    await asyncio.sleep(0.1)

                target = next(((t, n) for t, n in discovered if "Test-Vault" in n), None)
                assert target is not None, f"mDNS service not discovered. Found: {discovered}"

                svc_type, svc_name = target
                info = AsyncServiceInfo(svc_type, svc_name)
                resolved = await info.async_request(browser_zc.zeroconf, 3000)
                assert resolved is True
                assert info.port == 8443

                props = {k.decode("utf-8", "ignore"): v.decode("utf-8", "ignore") for k, v in info.properties.items()}
                assert props["v"] == "1"
                assert props["id"] == "test-srv-uuid"
                assert props["name"] == "Test-Vault"
                assert props["tls_fp"] == "AA:BB:CC:DD:EE"

            finally:
                await browser_zc.async_close()
                await adv.async_stop()

        asyncio.run(run_mdns_test())


# ---------------------------------------------------------------------------
# API Integration Tests
# ---------------------------------------------------------------------------

class TestAPI:
    """Integration tests for all FastAPI endpoints."""

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
        from main import app, ip_limiter
        ip_limiter._requests.clear()
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

    def test_pair_rate_limiting(self):
        from main import pairing
        pairing.generate_code()

        # Send 10 wrong attempts
        for _ in range(10):
            self.client.post("/api/v1/pair", json={
                "code": "000000",
                "device_name": "Test",
                "device_model": "test",
            })

        # 11th request from same IP is rate-limited (429)
        resp = self.client.post("/api/v1/pair", json={
            "code": "000000",
            "device_name": "Test",
            "device_model": "test",
        })
        assert resp.status_code == 429
        assert resp.json()["detail"]["error"] == "rate_limited"

    def test_pair_five_attempts_invalidates(self):
        from main import pairing, ip_limiter
        ip_limiter._requests.clear()
        code = pairing.generate_code()

        for _ in range(4):
            resp = self.client.post("/api/v1/pair", json={
                "code": "000000",
                "device_name": "Test",
                "device_model": "test",
            })
            assert resp.status_code == 401
            assert resp.json()["detail"]["error"] == "invalid_code"

        # 5th attempt invalidates code
        resp = self.client.post("/api/v1/pair", json={
            "code": "000000",
            "device_name": "Test",
            "device_model": "test",
        })
        assert resp.status_code == 401
        assert resp.json()["detail"]["error"] == "code_invalidated"

    def test_bulk_upload_check(self):
        from main import pairing, db
        code = pairing.generate_code()
        pair_resp = self.client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Phone",
            "device_model": "model",
        })
        token = pair_resp.json()["token"]
        headers = {"Authorization": f"Bearer {token}"}

        sha1 = "1" * 64
        sha2 = "2" * 64
        sha3 = "3" * 64

        # Add sha1 as existing
        user = db.list_users()[0]
        dev = db.list_devices(user.id)[0]
        db.insert_file(user.id, dev.id, "Camera", "1.jpg", sha1, 100, None)

        # Add sha2 as partial
        db.upsert_partial_upload(user.id, sha2, 512, "/tmp/part")

        resp = self.client.post(
            "/api/v1/upload/check",
            headers=headers,
            json={"hashes": [sha1, sha2, sha3]},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert sha1 in data["existing"]
        assert data["partial"].get(sha2) == 512
        assert sha3 not in data["existing"]
        assert sha3 not in data["partial"]

    def test_upload_resume_offset_mismatch_writes_nothing(self):
        from main import pairing, storage, db
        code = pairing.generate_code()
        pair_resp = self.client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Phone",
            "device_model": "model",
        })
        token = pair_resp.json()["token"]
        dev_id = pair_resp.json()["device_id"]
        headers = {"Authorization": f"Bearer {token}"}

        content = b"0123456789"
        sha = hashlib.sha256(content).hexdigest()

        # Simulate existing partial file of 5 bytes
        user = db.list_users()[0]
        part_path = storage.part_file_path(user.name, sha)
        part_path.write_bytes(content[:5])

        # Client sends wrong Upload-Offset of 8
        resp = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": dev_id, "folder": "Camera", "name": "test.txt"},
            headers={**headers, "Upload-Offset": "8", "Content-Length": "10"},
            content=b"tail",
        )
        assert resp.status_code == 409
        err = resp.json()["detail"]
        assert err["error"] == "offset_mismatch"
        assert err["offset"] == 5
        # Ensure server did NOT append or corrupt the partial file
        assert part_path.stat().st_size == 5

    def test_upload_resume_success(self):
        from main import pairing, storage, db
        code = pairing.generate_code()
        pair_resp = self.client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Phone",
            "device_model": "model",
        })
        token = pair_resp.json()["token"]
        dev_id = pair_resp.json()["device_id"]
        headers = {"Authorization": f"Bearer {token}"}

        full_content = b"A" * 1024 * 128  # 128 KB
        sha = hashlib.sha256(full_content).hexdigest()
        half_len = 1024 * 64

        # Upload first half
        user = db.list_users()[0]
        part_path = storage.part_file_path(user.name, sha)
        part_path.write_bytes(full_content[:half_len])

        # Resume second half with exact offset
        resp = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": dev_id, "folder": "Camera", "name": "resume.jpg"},
            headers={**headers, "Upload-Offset": str(half_len), "Content-Length": str(len(full_content))},
            content=full_content[half_len:],
        )
        assert resp.status_code == 200, resp.text
        res = resp.json()
        assert res["sha256"] == sha
        assert res["size"] == len(full_content)

    def test_duplicate_reference_same_sha(self):
        """Same sha256 uploaded under different folder/device creates DB record, no duplicate disk write."""
        from main import pairing, db
        code = pairing.generate_code()
        pair_resp = self.client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Phone",
            "device_model": "model",
        })
        token = pair_resp.json()["token"]
        dev_id = pair_resp.json()["device_id"]
        headers = {"Authorization": f"Bearer {token}"}

        content = b"Unique content"
        sha = hashlib.sha256(content).hexdigest()

        # First upload into Camera
        resp1 = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": dev_id, "folder": "Camera", "name": "pic1.jpg"},
            headers={**headers, "Content-Length": str(len(content))},
            content=content,
        )
        assert resp1.status_code == 200

        # Second upload into Screenshots (same sha)
        resp2 = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": dev_id, "folder": "Screenshots", "name": "shot1.png"},
            headers={**headers, "Content-Length": str(len(content))},
            content=content,
        )
        assert resp2.status_code == 200
        assert resp2.json()["path"] == "Screenshots/shot1.png"

        # Check folder stats show both folders
        stats_resp = self.client.get(f"/api/v1/devices/{dev_id}/folders", headers=headers)
        folders = {f["name"]: f["item_count"] for f in stats_resp.json()["folders"]}
        assert folders["Camera"] == 1
        assert folders["Screenshots"] == 1

        # Check user quota deduplication (stored once, size is len(content))
        status = self.client.get("/api/v1/status", headers=headers).json()
        assert status["user"]["used_bytes"] == len(content)
        assert status["user"]["file_count"] == 1

    def test_organise_by_date_and_mtime(self):
        from main import pairing, db, storage
        code = pairing.generate_code()
        pair_resp = self.client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Phone",
            "device_model": "model",
        })
        token = pair_resp.json()["token"]
        dev_id = pair_resp.json()["device_id"]
        headers = {"Authorization": f"Bearer {token}"}

        # Enable organise_by_date on user
        user = db.list_users()[0]
        db.update_user(user.id, organise_by_date=True)

        content = b"Date Photo"
        sha = hashlib.sha256(content).hexdigest()
        taken_ts = 1768435200  # 2026-01-15 UTC

        resp = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": dev_id, "folder": "Camera", "name": "img.jpg", "taken": taken_ts},
            headers={**headers, "Content-Length": str(len(content))},
            content=content,
        )
        assert resp.status_code == 200

        # Check file exists in 2026/01 and mtime matches
        target_path = storage.root / "users" / slugify(user.name) / "devices" / "phone" / "Camera" / "2026" / "01" / "img.jpg"
        assert target_path.exists()
        assert int(target_path.stat().st_mtime) == taken_ts
