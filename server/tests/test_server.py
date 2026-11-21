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
    GlobalPairingLimiter,
    PairingManager,
    create_bearer_token,
    generate_invite_code,
    hash_token,
    UNAMBIGUOUS_ALPHABET,
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

    def test_lookup_missing_token(self):
        assert self.db.lookup_token("nonexistent_hash") is None

    def test_folder_stats(self):
        user = self.db.create_user("Alice", "owner", 1024 * 1024)
        dev = self.db.create_device(user.id, "Phone", "model", "phone")
        self.db.insert_file(user.id, dev.id, "Camera", "1.jpg", "h1"*32, 100, 1000)
        self.db.insert_file(user.id, dev.id, "Camera", "2.jpg", "h2"*32, 200, 2000)
        self.db.insert_file(user.id, dev.id, "Screenshots", "3.png", "h3"*32, 300, 3000)

        stats = {s.folder: (s.item_count, s.total_bytes) for s in self.db.device_folder_stats(user.id, dev.id)}
        assert stats["Camera"] == (2, 300)
        assert stats["Screenshots"] == (1, 300)

    def test_audit_log(self):
        user = self.db.create_user("Alice", "owner", 1024)
        self.db.audit(user.id, "test_action", "target_123", "detail_msg")

    def test_create_device_slug_uniqueness(self):
        user = self.db.create_user("Alice", "owner", 1024)
        d1 = self.db.create_device(user.id, "Pixel 7", "google pixel 7", "pixel-7")
        d2 = self.db.create_device(user.id, "Pixel 7", "google pixel 7", "pixel-7")
        d3 = self.db.create_device(user.id, "Pixel 7", "google pixel 7", "pixel-7")
        assert d1.slug == "pixel-7"
        assert d2.slug == "pixel-7-2"
        assert d3.slug == "pixel-7-3"

    def test_delete_user_preserves_other_invites(self):
        owner = self.db.create_user("Owner", "owner", 0)
        member = self.db.create_user("Member", "member", 1000)
        inv = self.db.create_invite(member.id, "code_hash_123", created_by=owner.id, expires_at="2099-01-01T00:00:00")
        assert inv.created_by == owner.id

        # Delete owner
        self.db.delete_user(owner.id)

        # Invite for member still exists, but created_by is None
        found_inv = self.db.get_invite_by_hash("code_hash_123")
        assert found_inv is not None
        assert found_inv.created_by is None
        assert found_inv.user_id == member.id


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


    def test_wrong_code(self):
        pm = PairingManager()
        pm.generate_code()
        valid, reason = pm.validate_code("000000")
        assert valid is False
        assert reason == "invalid_code"

    def test_disabled(self):
        pm = PairingManager()
        code = pm.generate_code()
        pm.set_enabled(False)
        valid, reason = pm.validate_code(code)
        assert valid is False
        assert reason == "pairing_closed"

    def test_single_use(self):
        pm = PairingManager()
        code = pm.generate_code()
        assert pm.validate_code(code)[0] is True
        assert pm.validate_code(code)[0] is False


class TestGlobalPairingLimiter:
    """Tests for global failed pairing attempt rate limiter."""

    def test_limiter_locks_after_twenty_failures(self):
        limiter = GlobalPairingLimiter()
        assert limiter.is_locked()[0] is False

        # 19 failures -> not locked
        for _ in range(19):
            locked = limiter.record_failure()
            assert locked is False
            assert limiter.is_locked()[0] is False

        # 20th failure -> triggers lockout
        triggered = limiter.record_failure()
        assert triggered is True
        is_locked, remaining = limiter.is_locked()
        assert is_locked is True
        assert remaining > 0


class TestTokenGeneration:
    """Tests for token and invite code generation."""

    def test_bearer_token_uniqueness(self):
        tokens = {create_bearer_token() for _ in range(100)}
        assert len(tokens) == 100

    def test_hash_deterministic(self):
        h1 = hash_token("test-token-123")
        h2 = hash_token("test-token-123")
        assert h1 == h2
        assert len(h1) == 64

    def test_unambiguous_invite_code(self):
        codes = [generate_invite_code(8) for _ in range(50)]
        for c in codes:
            assert len(c) == 8
            # No ambiguous characters: 0, O, 1, I
            assert "0" not in c
            assert "O" not in c
            assert "1" not in c
            assert "I" not in c
            assert all(char in UNAMBIGUOUS_ALPHABET for char in c)


class TestSlugify:
    """Tests for filesystem slug generation."""

    def test_basic(self):
        assert slugify("Pixel 7 Pro") == "pixel-7-pro"

    def test_special_chars(self):
        assert slugify("Bob's Phone #1!") == "bob-s-phone-1"

    def test_unicode(self):
        assert slugify("München") == "munchen" or len(slugify("München")) > 0

    def test_empty(self):
        assert slugify("   ") == "unnamed"

    def test_max_length(self):
        long_name = "a" * 300
        assert len(slugify(long_name)) <= 200


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
        assert sanitise_component("folder:name") == "folder_name"

    def test_control_chars_replaced(self):
        assert sanitise_component("folder\x00name") == "folder_name"


class TestSanitiseFilename:
    """Tests for filename sanitisation."""

    def test_normal(self):
        assert sanitise_filename("photo.jpg") == "photo.jpg"

    def test_path_separator_rejected(self):
        with pytest.raises(ValueError):
            sanitise_filename("dir/photo.jpg")

    def test_empty_rejected(self):
        with pytest.raises(ValueError):
            sanitise_filename("")


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

    def test_device_dir(self):
        path = self.sm.device_dir("Alice", "Pixel 7")
        assert "pixel-7" in str(path)

    def test_folder_dir(self):
        path = self.sm.folder_dir("Alice", "Pixel 7", "Camera")
        assert "Camera" in str(path)

    def test_folder_dir_with_date(self):
        ts = 1768435200  # 2026-01-15 UTC
        path = self.sm.folder_dir("Alice", "Pixel 7", "Camera", date_taken=ts, organise_by_date=True)
        assert "2026" in str(path)
        assert "01" in str(path)

    def test_resolve_filename_no_collision(self):
        d = Path(self.tmp) / "test_no_coll"
        d.mkdir()
        res = self.sm.resolve_filename(d, "new_photo.jpg", "sha")
        assert res == "new_photo.jpg"

    def test_part_file_path(self):
        part = self.sm.part_file_path("Alice", "sha123")
        assert "sha123.part" in str(part)
        assert "parts" in str(part)

    def test_atomic_move(self):
        src = Path(self.tmp) / "src.bin"
        src.write_bytes(b"data")
        dst = Path(self.tmp) / "sub" / "dst.bin"
        self.sm.atomic_move(src, dst)
        assert not src.exists()
        assert dst.read_bytes() == b"data"

    def test_symlink_pointing_at_other_user_refused_on_delete_and_archive(self):
        users_dir = Path(self.tmp) / "users"
        users_dir.mkdir(parents=True, exist_ok=True)
        victim_dir = users_dir / "victim"
        victim_dir.mkdir()
        (victim_dir / "secret.txt").write_text("secret")

        attacker_link = users_dir / "attacker"
        try:
            attacker_link.symlink_to(victim_dir, target_is_directory=True)
        except (OSError, NotImplementedError):
            pytest.skip("Symlinks not supported in this environment")

        with pytest.raises(ValueError, match="Refusing to delete symlinked"):
            self.sm.safe_delete_user_dir("attacker")

        with pytest.raises(ValueError, match="Refusing to archive symlinked"):
            self.sm.archive_user_dir("attacker")

        # Victim's data must remain completely untouched
        assert (victim_dir / "secret.txt").read_text() == "secret"

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

    def test_pair_no_code(self):
        resp = self.client.post("/api/v1/pair", json={"code": "", "device_name": "Test", "device_model": "test"})
        assert resp.status_code in (400, 401, 409, 422)

    def test_status_no_auth(self):
        resp = self.client.get("/api/v1/status")
        assert resp.status_code == 401

    def test_check_upload_no_auth(self):
        resp = self.client.head(f"/api/v1/upload/{'a'*64}")
        assert resp.status_code == 401

    def test_invalid_sha_format(self):
        from main import pairing
        code = pairing.generate_code()
        pair_resp = self.client.post("/api/v1/pair", json={"code": code, "device_name": "Phone", "device_model": "model"})
        headers = {"Authorization": f"Bearer {pair_resp.json()['token']}"}
        resp = self.client.head("/api/v1/upload/invalid_sha", headers=headers)
        assert resp.status_code == 400

    def test_upload_hash_mismatch(self):
        from main import pairing
        code = pairing.generate_code()
        pair_resp = self.client.post("/api/v1/pair", json={"code": code, "device_name": "Phone", "device_model": "model"})
        headers = {"Authorization": f"Bearer {pair_resp.json()['token']}"}
        dev_id = pair_resp.json()["device_id"]
        resp = self.client.put(
            f"/api/v1/upload/{'0'*64}",
            params={"device": dev_id, "folder": "Camera", "name": "bad.jpg"},
            headers={**headers, "Content-Length": "4"},
            content=b"test",
        )
        assert resp.status_code == 409
        assert resp.json()["detail"]["error"] == "hash_mismatch"

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


class TestUserManager:
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
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _pair_owner(self):
        from main import pairing
        code = pairing.generate_code()
        resp = self.client.post("/api/v1/pair", json={
            "code": code,
            "device_name": "Owner Phone",
            "device_model": "Pixel",
        })
        assert resp.status_code == 200, resp.text
        token = resp.json()["token"]
        return {"Authorization": f"Bearer {token}"}, resp.json()["user_id"], resp.json()["device_id"]

    def test_owner_creates_member_invite_and_pair(self):
        owner_headers, owner_id, _ = self._pair_owner()

        # 1. Create member
        resp = self.client.post("/api/v1/admin/users", headers=owner_headers, json={
            "display_name": "Bob Member",
            "quota_bytes": 10000,
        })
        assert resp.status_code == 200, resp.text
        data = resp.json()
        assert data["user"]["display_name"] == "Bob Member"
        assert data["user"]["role"] == "member"
        invite_code = data["invite_code"]
        assert len(invite_code) == 8
        assert "piphotos://pair" in data["qr_payload"]
        bob_id = data["user"]["id"]

        # 2. Bob pairs using invite code
        bob_pair = self.client.post("/api/v1/pair", json={
            "code": invite_code,
            "device_name": "Bob Phone",
            "device_model": "Galaxy",
        })
        assert bob_pair.status_code == 200, bob_pair.text
        bob_token = bob_pair.json()["token"]
        bob_dev = bob_pair.json()["device_id"]
        assert bob_pair.json()["user_id"] == bob_id
        bob_headers = {"Authorization": f"Bearer {bob_token}"}

        # 3. Bob uploads a file
        content = b"Bob secret photo"
        sha = hashlib.sha256(content).hexdigest()
        up_resp = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": bob_dev, "folder": "Camera", "name": "bob.jpg"},
            headers={**bob_headers, "Content-Length": str(len(content))},
            content=content,
        )
        assert up_resp.status_code == 200, up_resp.text

        # 4. Check user list shows Bob with used_bytes and 1 device
        users_resp = self.client.get("/api/v1/admin/users", headers=owner_headers)
        assert users_resp.status_code == 200
        users = {u["id"]: u for u in users_resp.json()}
        assert users[bob_id]["used_bytes"] == len(content)
        assert users[bob_id]["devices_count"] == 1

        # 5. Non-owner cannot access admin endpoints
        non_owner = self.client.get("/api/v1/admin/users", headers=bob_headers)
        assert non_owner.status_code == 403

    def test_cross_user_isolation(self):
        owner_headers, owner_id, owner_dev = self._pair_owner()

        # Create member Alice
        create_resp = self.client.post("/api/v1/admin/users", headers=owner_headers, json={
            "display_name": "Alice",
            "quota_bytes": 100000,
        })
        invite = create_resp.json()["invite_code"]
        pair_resp = self.client.post("/api/v1/pair", json={
            "code": invite,
            "device_name": "Alice Phone",
            "device_model": "iPhone",
        })
        alice_headers = {"Authorization": f"Bearer {pair_resp.json()['token']}"}
        alice_dev = pair_resp.json()["device_id"]

        # Alice attempts to access Owner's device folders -> 404
        cross_resp = self.client.get(f"/api/v1/devices/{owner_dev}/folders", headers=alice_headers)
        assert cross_resp.status_code == 404

        # Alice attempts upload to Owner's device -> 403
        content = b"Hacked content"
        sha = hashlib.sha256(content).hexdigest()
        hack_resp = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": owner_dev, "folder": "Camera", "name": "bad.jpg"},
            headers={**alice_headers, "Content-Length": str(len(content))},
            content=content,
        )
        assert hack_resp.status_code == 403

        # Path traversal with ../ is rejected
        trav_resp = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": alice_dev, "folder": "../../../etc", "name": "bad.jpg"},
            headers={**alice_headers, "Content-Length": str(len(content))},
            content=content,
        )
        assert trav_resp.status_code in (400, 500)

    def test_quota_soft_warnings_and_hard_limit(self):
        owner_headers, _, _ = self._pair_owner()

        # Create user with 100 byte quota
        create_resp = self.client.post("/api/v1/admin/users", headers=owner_headers, json={
            "display_name": "QuotaUser",
            "quota_bytes": 100,
        })
        user_id = create_resp.json()["user"]["id"]
        invite = create_resp.json()["invite_code"]

        pair = self.client.post("/api/v1/pair", json={
            "code": invite,
            "device_name": "Device",
            "device_model": "Model",
        }).json()
        headers = {"Authorization": f"Bearer {pair['token']}"}
        dev_id = pair["device_id"]

        # Upload 85 bytes -> 85% usage (warning: "80%")
        content85 = b"A" * 85
        sha85 = hashlib.sha256(content85).hexdigest()
        r85 = self.client.put(
            f"/api/v1/upload/{sha85}",
            params={"device": dev_id, "folder": "Test", "name": "f1.bin"},
            headers={**headers, "Content-Length": "85"},
            content=content85,
        )
        assert r85.status_code == 200
        st85 = self.client.get("/api/v1/status", headers=headers).json()
        assert st85["user"]["soft_quota_warning"] == "80%"

        # Attempt upload of 20 more bytes -> 105 total > 100 bytes -> 507
        content20 = b"B" * 20
        sha20 = hashlib.sha256(content20).hexdigest()
        r20 = self.client.put(
            f"/api/v1/upload/{sha20}",
            params={"device": dev_id, "folder": "Test", "name": "f2.bin"},
            headers={**headers, "Content-Length": "20"},
            content=content20,
        )
        assert r20.status_code == 507
        assert r20.json()["detail"]["error"] == "quota_exceeded"

        # Owner attempts to lower quota below 85 without force -> 400
        lower = self.client.patch(f"/api/v1/admin/users/{user_id}", headers=owner_headers, json={
            "quota_bytes": 50,
        })
        assert lower.status_code == 400
        assert lower.json()["detail"]["error"] == "quota_below_usage"

        # With force=True, lower quota is accepted
        forced = self.client.patch(f"/api/v1/admin/users/{user_id}?force=true", headers=owner_headers, json={
            "quota_bytes": 50,
        })
        assert forced.status_code == 200
        assert forced.json()["quota_bytes"] == 50

    def test_disabled_user_and_revoked_device(self):
        owner_headers, _, _ = self._pair_owner()

        create_resp = self.client.post("/api/v1/admin/users", headers=owner_headers, json={
            "display_name": "Dave",
            "quota_bytes": 1000,
        })
        user_id = create_resp.json()["user"]["id"]
        invite = create_resp.json()["invite_code"]

        pair = self.client.post("/api/v1/pair", json={
            "code": invite,
            "device_name": "Dave Device",
            "device_model": "Phone",
        }).json()
        headers = {"Authorization": f"Bearer {pair['token']}"}
        dev_id = pair["device_id"]

        # Dave is active: status works
        assert self.client.get("/api/v1/status", headers=headers).status_code == 200

        # Owner disables Dave
        dis = self.client.patch(f"/api/v1/admin/users/{user_id}", headers=owner_headers, json={
            "status": "disabled",
        })
        assert dis.status_code == 200

        # Dave gets 403 user_disabled
        st = self.client.get("/api/v1/status", headers=headers)
        assert st.status_code == 403
        assert st.json()["detail"]["error"] == "user_disabled"

        # Owner re-enables Dave
        self.client.patch(f"/api/v1/admin/users/{user_id}", headers=owner_headers, json={
            "status": "active",
        })
        assert self.client.get("/api/v1/status", headers=headers).status_code == 200

        # Owner revokes Dave's device
        del_dev = self.client.delete(f"/api/v1/admin/devices/{dev_id}", headers=owner_headers)
        assert del_dev.status_code == 200

        # Dave's token is now revoked (401)
        assert self.client.get("/api/v1/status", headers=headers).status_code == 401

    def test_last_owner_protection(self):
        owner_headers, owner_id, _ = self._pair_owner()

        # Cannot disable last owner
        d_resp = self.client.patch(f"/api/v1/admin/users/{owner_id}", headers=owner_headers, json={
            "status": "disabled",
        })
        assert d_resp.status_code == 400
        assert d_resp.json()["detail"]["error"] == "last_owner_protected"

        # Cannot demote last owner
        dem_resp = self.client.patch(f"/api/v1/admin/users/{owner_id}", headers=owner_headers, json={
            "role": "member",
        })
        assert dem_resp.status_code == 400
        assert dem_resp.json()["detail"]["error"] == "last_owner_protected"

        # Cannot delete last owner
        del_resp = self.client.delete(f"/api/v1/admin/users/{owner_id}", headers=owner_headers)
        assert del_resp.status_code == 400
        assert del_resp.json()["detail"]["error"] == "last_owner_protected"

    def test_invite_single_use_and_reissue(self):
        owner_headers, _, _ = self._pair_owner()

        create_resp = self.client.post("/api/v1/admin/users", headers=owner_headers, json={
            "display_name": "Eve",
            "quota_bytes": 1000,
        })
        user_id = create_resp.json()["user"]["id"]
        invite1 = create_resp.json()["invite_code"]

        # First use succeeds
        p1 = self.client.post("/api/v1/pair", json={
            "code": invite1,
            "device_name": "Eve Phone",
            "device_model": "Phone",
        })
        assert p1.status_code == 200

        # Second use of same invite fails with invite_used
        p2 = self.client.post("/api/v1/pair", json={
            "code": invite1,
            "device_name": "Eve Tablet",
            "device_model": "Tablet",
        })
        assert p2.status_code == 401
        assert p2.json()["detail"]["error"] == "invite_used"

        # Owner re-issues invite
        reissue = self.client.post(f"/api/v1/admin/users/{user_id}/invite", headers=owner_headers)
        assert reissue.status_code == 200
        invite2 = reissue.json()["invite_code"]
        assert invite2 != invite1

        # Re-issued invite succeeds for second device
        p3 = self.client.post("/api/v1/pair", json={
            "code": invite2,
            "device_name": "Eve Tablet",
            "device_model": "Tablet",
        })
        assert p3.status_code == 200

    def test_delete_user_archive_vs_purge(self):
        owner_headers, _, _ = self._pair_owner()

        # User 1: delete with delete_files=False (archive)
        c1 = self.client.post("/api/v1/admin/users", headers=owner_headers, json={"display_name": "KeepFiles"}).json()
        u1_id = c1["user"]["id"]
        p1 = self.client.post("/api/v1/pair", json={"code": c1["invite_code"], "device_name": "D1", "device_model": "M"}).json()
        h1 = {"Authorization": f"Bearer {p1['token']}"}
        content = b"important user data"
        sha = hashlib.sha256(content).hexdigest()
        self.client.put(f"/api/v1/upload/{sha}", params={"device": p1["device_id"], "folder": "Camera", "name": "f.jpg"}, headers={**h1, "Content-Length": str(len(content))}, content=content)

        del1 = self.client.delete(f"/api/v1/admin/users/{u1_id}?delete_files=false", headers=owner_headers)
        assert del1.status_code == 200

        # Check archived in _removed/
        from main import storage
        removed_dirs = list((storage.root / "_removed").glob("keepfiles-*"))
        assert len(removed_dirs) == 1

        # User 2: delete with delete_files=True (purge)
        c2 = self.client.post("/api/v1/admin/users", headers=owner_headers, json={"display_name": "PurgeUser"}).json()
        u2_id = c2["user"]["id"]
        p2 = self.client.post("/api/v1/pair", json={"code": c2["invite_code"], "device_name": "D2", "device_model": "M"}).json()
        h2 = {"Authorization": f"Bearer {p2['token']}"}
        self.client.put(f"/api/v1/upload/{sha}", params={"device": p2["device_id"], "folder": "Camera", "name": "f.jpg"}, headers={**h2, "Content-Length": str(len(content))}, content=content)

        del2 = self.client.delete(f"/api/v1/admin/users/{u2_id}?delete_files=true", headers=owner_headers)
        assert del2.status_code == 200
        assert not (storage.root / "users" / "purgeuser").exists()

    def test_admin_pairing_open_and_overview(self):
        owner_headers, _, _ = self._pair_owner()

        # Open pairing window via admin endpoint
        open_resp = self.client.post("/api/v1/admin/pairing/open", headers=owner_headers)
        assert open_resp.status_code == 200
        new_code = open_resp.json()["code"]
        assert len(new_code) == 6

        # Pairing with new code works
        p = self.client.post("/api/v1/pair", json={
            "code": new_code,
            "device_name": "Second Owner Device",
            "device_model": "MacBook",
        })
        assert p.status_code == 200

        # Overview endpoint
        overview = self.client.get("/api/v1/admin/overview", headers=owner_headers)
        assert overview.status_code == 200
        od = overview.json()
        assert "disk" in od
        assert "allocated_quota_total" in od
        assert len(od["users"]) >= 1

    def test_duplicate_upload_creates_hardlink_and_shares_quota(self):
        owner_headers, owner_id, dev_id = self._pair_owner()
        content = b"Duplicate Hardlink Content 12345678"
        sha = hashlib.sha256(content).hexdigest()

        # Upload into Camera
        r1 = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": dev_id, "folder": "Camera", "name": "orig.jpg"},
            headers={**owner_headers, "Content-Length": str(len(content))},
            content=content,
        )
        assert r1.status_code == 200

        # Upload duplicate into Downloads
        r2 = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": dev_id, "folder": "Downloads", "name": "dup.jpg"},
            headers={**owner_headers, "Content-Length": str(len(content))},
            content=content,
        )
        assert r2.status_code == 200

        from main import storage, db
        user = db.get_user(owner_id)
        dev = db.get_device(dev_id)
        p1 = storage.folder_dir(user.slug, dev.slug, "Camera") / "orig.jpg"
        p2 = storage.folder_dir(user.slug, dev.slug, "Downloads") / "dup.jpg"

        # Both files must exist on disk
        assert p1.exists()
        assert p2.exists()
        assert p1.read_bytes() == content
        assert p2.read_bytes() == content

        # Quota counts it once
        status = self.client.get("/api/v1/status", headers=owner_headers).json()
        assert status["user"]["used_bytes"] == len(content)

    def test_pair_device_slug_uniqueness(self):
        owner_headers, _, _ = self._pair_owner()
        from main import db

        # Open pairing and pair second owner device called "Owner Phone"
        self.client.post("/api/v1/admin/pairing/open", headers=owner_headers)
        from main import pairing
        code2 = pairing.generate_code()
        resp2 = self.client.post("/api/v1/pair", json={
            "code": code2,
            "device_name": "Owner Phone",
            "device_model": "Pixel",
        })
        assert resp2.status_code == 200
        d2 = db.get_device(resp2.json()["device_id"])
        assert d2.slug == "owner-phone-2"

        # Member invite pairing with repeated names
        c = self.client.post("/api/v1/admin/users", headers=owner_headers, json={"display_name": "Bob"}).json()
        inv1 = c["invite_code"]
        p1 = self.client.post("/api/v1/pair", json={"code": inv1, "device_name": "Pixel 7", "device_model": "P"}).json()
        dev1 = db.get_device(p1["device_id"])
        assert dev1.slug == "pixel-7"

        reissue = self.client.post(f"/api/v1/admin/users/{c['user']['id']}/invite", headers=owner_headers).json()
        p2 = self.client.post("/api/v1/pair", json={"code": reissue["invite_code"], "device_name": "Pixel 7", "device_model": "P"}).json()
        dev2 = db.get_device(p2["device_id"])
        assert dev2.slug == "pixel-7-2"

    def test_create_user_quota_default_and_unlimited(self):
        owner_headers, _, _ = self._pair_owner()

        # 1. Unset quota -> defaults to 100 GB (config default_quota)
        c_default = self.client.post("/api/v1/admin/users", headers=owner_headers, json={
            "display_name": "DefaultQuotaUser",
        })
        assert c_default.status_code == 200
        assert c_default.json()["user"]["quota_bytes"] == 107374182400

        # 2. Unlimited -> quota_bytes is 0
        c_unlimited = self.client.post("/api/v1/admin/users", headers=owner_headers, json={
            "display_name": "UnlimitedUser",
            "unlimited": True,
        })
        assert c_unlimited.status_code == 200
        assert c_unlimited.json()["user"]["quota_bytes"] == 0

    def test_throttled_device_last_seen(self):
        from main import _throttled_update_device_last_seen, _device_last_seen_cache
        _device_last_seen_cache.clear()
        dev_id = "test_device_throttle_1"

        # 1st call -> writes to DB (returns True)
        assert _throttled_update_device_last_seen(dev_id) is True

        # Subsequent 4 calls in rapid succession -> throttled (returns False)
        for _ in range(4):
            assert _throttled_update_device_last_seen(dev_id) is False

    def test_invite_code_unambiguous_and_global_pairing_limiter(self):
        owner_headers, _, _ = self._pair_owner()
        from main import global_pairing_limiter, ip_limiter, pairing
        global_pairing_limiter.reset()
        ip_limiter._requests.clear()
        pairing.close()

        # Verify invite code length and characters
        create_resp = self.client.post("/api/v1/admin/users", headers=owner_headers, json={
            "display_name": "InviteTest",
        }).json()
        code = create_resp["invite_code"]
        assert len(code) == 8
        assert not any(c in code for c in "0O1I")

        # 20 failed pairing attempts across distinct simulated client IPs
        for i in range(19):
            r = self.client.post(
                "/api/v1/pair",
                json={"code": "WRONG123", "device_name": "D", "device_model": "M"},
                headers={"X-Forwarded-For": f"192.168.1.{i+10}"},
            )
            assert r.status_code in (401, 409)

        # 20th failed attempt triggers global lockout
        r20 = self.client.post(
            "/api/v1/pair",
            json={"code": "WRONG123", "device_name": "D", "device_model": "M"},
            headers={"X-Forwarded-For": "192.168.1.99"},
        )
        assert r20.status_code in (401, 409)
        assert global_pairing_limiter.is_locked()[0] is True

        # 21st attempt is rejected with 429 pairing_locked
        r21 = self.client.post(
            "/api/v1/pair",
            json={"code": code, "device_name": "D", "device_model": "M"},
            headers={"X-Forwarded-For": "192.168.1.100"},
        )
        assert r21.status_code == 429
        assert r21.json()["detail"]["error"] == "pairing_locked"

        global_pairing_limiter.reset()

    def test_upload_streaming_chunk_timeout(self):
        owner_headers, _, dev_id = self._pair_owner()
        os.environ["PIPHOTOS_CHUNK_TIMEOUT"] = "0.05"

        content = b"slow payload chunk"
        sha = hashlib.sha256(content).hexdigest()

        import httpx
        from main import app

        async def _run_timeout_test():
            async def slow_stream():
                yield b"slow"
                await asyncio.sleep(0.15)  # exceeds 0.05s timeout
                yield b"payload"

            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
                return await ac.put(
                    f"/api/v1/upload/{sha}",
                    params={"device": dev_id, "folder": "Camera", "name": "slow.bin"},
                    headers={**owner_headers, "Content-Length": str(len(content))},
                    content=slow_stream(),
                )

        try:
            r = asyncio.run(_run_timeout_test())
            assert r.status_code == 408
            assert r.json()["detail"]["error"] == "timeout"
            # Verify partial file is kept
            from main import storage
            part_path = storage.part_file_path("owner", sha)
            assert part_path.exists()
            assert part_path.stat().st_size == len(b"slow")
        finally:
            os.environ["PIPHOTOS_CHUNK_TIMEOUT"] = "30.0"

    def test_me_and_files_endpoints_and_cross_user_isolation(self):
        owner_headers, owner_id, owner_dev = self._pair_owner()

        # 1. GET /api/v1/me for owner
        me_resp = self.client.get("/api/v1/me", headers=owner_headers)
        assert me_resp.status_code == 200
        me = me_resp.json()
        assert me["user_id"] == owner_id
        assert me["role"] == "owner"
        assert me["device_id"] == owner_dev

        # 2. Create member Alice and pair
        c_alice = self.client.post("/api/v1/admin/users", headers=owner_headers, json={"display_name": "AliceFiles"}).json()
        p_alice = self.client.post("/api/v1/pair", json={"code": c_alice["invite_code"], "device_name": "Alice Phone", "device_model": "Phone"}).json()
        alice_headers = {"Authorization": f"Bearer {p_alice['token']}"}
        alice_dev = p_alice["device_id"]

        # Alice GET /api/v1/me
        alice_me = self.client.get("/api/v1/me", headers=alice_headers).json()
        assert alice_me["role"] == "member"
        assert alice_me["display_name"] == "AliceFiles"

        # Alice uploads a photo
        payload = b"Alice Secret File Payload 12345"
        sha = hashlib.sha256(payload).hexdigest()
        up = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": alice_dev, "folder": "Camera", "name": "secret.txt"},
            headers={**alice_headers, "Content-Length": str(len(payload))},
            content=payload,
        )
        assert up.status_code == 200

        # Alice lists files
        files_resp = self.client.get("/api/v1/files", headers=alice_headers)
        assert files_resp.status_code == 200
        files = files_resp.json()
        assert len(files) == 1
        f_id = files[0]["id"]
        assert files[0]["name"] == "secret.txt"
        assert files[0]["size"] == len(payload)

        # Alice downloads content (Full)
        dl_resp = self.client.get(f"/api/v1/files/{f_id}/content", headers=alice_headers)
        assert dl_resp.status_code == 200
        assert dl_resp.content == payload

        # Alice downloads content with Range (bytes=0-4)
        range_resp = self.client.get(f"/api/v1/files/{f_id}/content", headers={**alice_headers, "Range": "bytes=0-4"})
        assert range_resp.status_code == 206
        assert range_resp.content == payload[:5]
        assert "bytes 0-4/" in range_resp.headers["Content-Range"]

        # Cross-user isolation: Owner tries to access Alice's file_id -> 404
        cross_resp = self.client.get(f"/api/v1/files/{f_id}/content", headers=owner_headers)
        assert cross_resp.status_code == 404

        # Owner calls GET /api/v1/files -> does NOT contain Alice's file
        owner_files = self.client.get("/api/v1/files", headers=owner_headers).json()
        assert not any(f["id"] == f_id for f in owner_files)


# ---------------------------------------------------------------------------
# Phase H hardening: invalid names are 400 (never 500); disk-full has its own code
# ---------------------------------------------------------------------------

class TestHardening:
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
        self._tc = TestClient(app, raise_server_exceptions=False)
        self.client = self._tc.__enter__()
        from main import pairing  # created during app startup
        resp = self.client.post("/api/v1/pair", json={
            "code": pairing.generate_code(), "device_name": "Phone", "device_model": "Pixel",
        })
        assert resp.status_code == 200, resp.text
        self.headers = {"Authorization": f"Bearer {resp.json()['token']}"}
        self.device = resp.json()["device_id"]

    def teardown_method(self):
        os.environ["PIPHOTOS_DISK_RESERVE_FRACTION"] = "0.0"
        self._tc.__exit__(None, None, None)
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _put(self, content: bytes, folder: str, name: str = "a.jpg"):
        sha = hashlib.sha256(content).hexdigest()
        return self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": self.device, "folder": folder, "name": name},
            headers={**self.headers, "Content-Length": str(len(content))},
            content=content,
        )

    @pytest.mark.parametrize("folder", [".", "", " ", "...", ".."])
    def test_invalid_folder_on_upload_is_400(self, folder):
        r = self._put(b"x" * 100, folder)
        assert r.status_code == 400, (folder, r.status_code, r.text)
        assert r.json()["detail"]["error"] == "bad_request"

    @pytest.mark.parametrize("folder", [".", "", "..", "a/b"])
    def test_invalid_folder_on_listing_never_500(self, folder):
        r = self.client.get(
            "/api/v1/files",
            headers=self.headers,
            params={"device_id": self.device, "folder": folder},
        )
        # Listing filters database rows, so an unknown folder is an empty list, never a crash.
        assert r.status_code in (200, 400, 404), (folder, r.status_code, r.text)
        if r.status_code == 200:
            assert r.json() == []

    def test_valid_upload_still_works(self):
        assert self._put(b"hello photo", "Camera").status_code == 200

    def test_disk_reserve_breach_uses_disk_full_code(self):
        # Reserve 100% of the disk so any upload would breach it.
        os.environ["PIPHOTOS_DISK_RESERVE_FRACTION"] = "1.0"
        r = self._put(b"y" * 2048, "Camera", "big.jpg")
        assert r.status_code == 507, r.text
        detail = r.json()["detail"]
        assert detail["error"] == "disk_full"
        assert "quota" not in str(detail).lower()

    def test_user_quota_breach_still_uses_quota_exceeded(self):
        r = self.client.post("/api/v1/admin/users", headers=self.headers,
                             json={"display_name": "Tiny", "quota_bytes": 10})
        assert r.status_code == 200, r.text
        inv = r.json()["invite_code"]
        p = self.client.post("/api/v1/pair", json={"code": inv, "device_name": "T", "device_model": "m"})
        assert p.status_code == 200, p.text
        content = b"z" * 5000
        sha = hashlib.sha256(content).hexdigest()
        up = self.client.put(
            f"/api/v1/upload/{sha}",
            params={"device": p.json()["device_id"], "folder": "Camera", "name": "z.jpg"},
            headers={"Authorization": f"Bearer {p.json()['token']}", "Content-Length": str(len(content))},
            content=content,
        )
        assert up.status_code == 507, up.text
        assert up.json()["detail"]["error"] == "quota_exceeded"
