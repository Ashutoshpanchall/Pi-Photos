"""Tests for PiPhotos CLI packaging, path resolution, and local admin authentication (Phase S1)."""

from __future__ import annotations

import json
import os
import stat
import sys
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest
from starlette.testclient import TestClient

# Ensure server package is importable
sys.path.insert(0, str(Path(__file__).parent.parent))

from piphotos_server.paths import (
    get_default_data_dir,
    resolve_paths,
    is_windows_elevated,
    is_root_user,
    ServerPaths,
)
from piphotos_server.local_admin import (
    ensure_local_admin_token,
    read_local_admin_token,
    verify_local_admin_token,
)
from piphotos_server.cli import (
    build_parser,
    main as cli_main,
    cmd_version,
    cmd_setup,
    cmd_info,
    cmd_status,
)


class TestPathResolution:
    """Test OS-specific path resolution with mocks."""

    def test_windows_elevated(self):
        with patch("sys.platform", "win32"), \
             patch("piphotos_server.paths.is_windows_elevated", return_value=True), \
             patch.dict(os.environ, {"PROGRAMDATA": r"C:\ProgramData"}):
            data_dir = get_default_data_dir()
            assert str(data_dir) == r"C:\ProgramData\PiPhotos"

    def test_windows_non_elevated(self):
        with patch("sys.platform", "win32"), \
             patch("piphotos_server.paths.is_windows_elevated", return_value=False), \
             patch.dict(os.environ, {"LOCALAPPDATA": r"C:\Users\test\AppData\Local"}):
            data_dir = get_default_data_dir()
            assert str(data_dir) == r"C:\Users\test\AppData\Local\PiPhotos"

    def test_macos_application_support(self):
        with patch("sys.platform", "darwin"), \
             patch.object(Path, "home", return_value=Path("/Users/testuser")):
            data_dir = get_default_data_dir()
            assert data_dir.as_posix() == "/Users/testuser/Library/Application Support/PiPhotos"

    def test_linux_root(self):
        with patch("sys.platform", "linux"), \
             patch("piphotos_server.paths.is_root_user", return_value=True):
            data_dir = get_default_data_dir()
            assert data_dir.as_posix() == "/var/lib/piphotos"

    def test_linux_user_default(self):
        with patch("sys.platform", "linux"), \
             patch("piphotos_server.paths.is_root_user", return_value=False), \
             patch.dict(os.environ, {}, clear=True), \
             patch.object(Path, "home", return_value=Path("/home/testuser")):
            data_dir = get_default_data_dir()
            assert data_dir.as_posix() == "/home/testuser/.local/share/piphotos"

    def test_linux_user_xdg_override(self):
        with patch("sys.platform", "linux"), \
             patch("piphotos_server.paths.is_root_user", return_value=False), \
             patch.dict(os.environ, {"XDG_DATA_HOME": "/custom/xdg/share"}):
            data_dir = get_default_data_dir()
            assert data_dir.as_posix() == "/custom/xdg/share/piphotos"

    def test_resolve_paths_cli_overrides_env(self, tmp_path):
        env_data = tmp_path / "env_data"
        cli_data = tmp_path / "cli_data"
        cli_storage = tmp_path / "custom_storage"
        with patch.dict(os.environ, {"PIPHOTOS_DATA_DIR": str(env_data)}):
            paths = resolve_paths(data_dir=cli_data, storage_dir=cli_storage)
            assert paths.data_dir == cli_data.resolve()
            assert paths.storage_dir == cli_storage.resolve()
            assert paths.db_path == (cli_data / "piphotos.db").resolve()
            assert paths.certs_dir == (cli_data / "certs").resolve()

    def test_resolve_paths_reads_config_json_storage(self, tmp_path):
        data_dir = tmp_path / "data"
        data_dir.mkdir()
        config_file = data_dir / "config.json"
        config_file.write_text(json.dumps({
            "storage": str(tmp_path / "mounted_drive"),
            "port": 9443
        }), encoding="utf-8")

        paths = resolve_paths(data_dir=data_dir)
        assert paths.storage_dir == (tmp_path / "mounted_drive").resolve()


class TestLocalAdminToken:
    """Test local-admin token creation, validation, and permissions."""

    def test_ensure_and_read_token(self, tmp_path):
        token_path = tmp_path / "local-admin.token"
        assert read_local_admin_token(token_path) is None

        tok1 = ensure_local_admin_token(token_path)
        assert len(tok1) >= 32
        assert read_local_admin_token(token_path) == tok1

        # Idempotency: reading again returns the exact same token
        tok2 = ensure_local_admin_token(token_path)
        assert tok2 == tok1

        # Verification
        assert verify_local_admin_token(tok1, token_path) is True
        assert verify_local_admin_token("wrong_token", token_path) is False

    @pytest.mark.skipif(sys.platform == "win32", reason="Unix permissions check")
    def test_token_permissions_unix(self, tmp_path):
        token_path = tmp_path / "local-admin.token"
        ensure_local_admin_token(token_path)
        mode = stat.S_IMODE(token_path.stat().st_mode)
        assert mode == 0o600


class TestCliCommands:
    """Test CLI argument parsing and execution."""

    def test_parser_subcommands(self):
        parser = build_parser()
        args = parser.parse_args(["version"])
        assert args.subcommand == "version"

        args = parser.parse_args(["setup", "--port", "9443", "--name", "Test Vault", "--yes"])
        assert args.subcommand == "setup"
        assert args.port == 9443
        assert args.name == "Test Vault"
        assert args.yes is True

        args = parser.parse_args(["pair", "--data-dir", "/tmp/piphotos"])
        assert args.subcommand == "pair"
        assert args.data_dir == "/tmp/piphotos"

        args = parser.parse_args(["status"])
        assert args.subcommand == "status"

    def test_cmd_version(self, capsys):
        code = cli_main(["version"])
        assert code == 0
        captured = capsys.readouterr()
        assert "piphotos-server 1.0.0" in captured.out

    def test_cmd_setup_idempotency(self, tmp_path, capsys):
        data_dir = tmp_path / "setup_test_data"
        code1 = cli_main(["setup", "--data-dir", str(data_dir), "--port", "8443", "--name", "First Setup", "--yes"])
        assert code1 == 0

        # Verify files created
        config_path = data_dir / "config.json"
        assert config_path.is_file()
        cfg1 = json.loads(config_path.read_text(encoding="utf-8"))
        assert cfg1["port"] == 8443
        assert cfg1["name"] == "First Setup"

        cert_path = data_dir / "certs" / "cert.pem"
        key_path = data_dir / "certs" / "key.pem"
        assert cert_path.is_file()
        assert key_path.is_file()
        cert_bytes_1 = cert_path.read_bytes()

        db_path = data_dir / "piphotos.db"
        assert db_path.is_file()

        token_path = data_dir / "local-admin.token"
        assert token_path.is_file()
        token1 = token_path.read_text().strip()

        # Run setup second time without --regen-cert: must be identical and not overwrite cert
        code2 = cli_main(["setup", "--data-dir", str(data_dir), "--yes"])
        assert code2 == 0
        assert cert_path.read_bytes() == cert_bytes_1
        assert token_path.read_text().strip() == token1

    def test_cmd_setup_regen_cert(self, tmp_path):
        data_dir = tmp_path / "regen_test_data"
        cli_main(["setup", "--data-dir", str(data_dir), "--yes"])
        cert_path = data_dir / "certs" / "cert.pem"
        orig_cert = cert_path.read_bytes()

        # Run with --regen-cert
        cli_main(["setup", "--data-dir", str(data_dir), "--regen-cert", "--yes"])
        new_cert = cert_path.read_bytes()
        assert orig_cert != new_cert

    def test_cmd_status_stopped(self, tmp_path, capsys):
        data_dir = tmp_path / "status_test_data"
        cli_main(["setup", "--data-dir", str(data_dir), "--yes"])
        # Server is not running on this port
        code = cli_main(["status", "--data-dir", str(data_dir)])
        assert code == 3
        captured = capsys.readouterr()
        assert "STOPPED" in captured.out

    def test_cmd_info(self, tmp_path, capsys):
        data_dir = tmp_path / "info_test_data"
        cli_main(["setup", "--data-dir", str(data_dir), "--yes"])
        code = cli_main(["info", "--data-dir", str(data_dir)])
        assert code == 0
        captured = capsys.readouterr()
        assert "TLS Fingerprint:" in captured.out

    def test_cmd_pair_with_mock_server(self, tmp_path, capsys):
        data_dir = tmp_path / "pair_test_data"
        cli_main(["setup", "--data-dir", str(data_dir), "--yes"])

        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {"code": "123456", "expires_in_seconds": 600}

        with patch("httpx.post", return_value=mock_resp):
            code = cli_main(["pair", "--data-dir", str(data_dir)])
            assert code == 0
            captured = capsys.readouterr()
            assert "123456" in captured.out
            assert "Pairing Opened" in captured.out


class TestLoopbackLocalAdminAuth:
    """Test POST /api/v1/admin/pairing/open using local-admin token."""

    def test_loopback_local_admin_opens_pairing(self, tmp_path):
        from main import app
        from database import Database
        from storage import StorageManager
        from auth import PairingManager
        import main as srv_main

        # Setup test database and state
        db_path = tmp_path / "auth_test.db"
        test_db = Database(str(db_path))
        owner = test_db.create_user("Owner", "owner", 1000000)

        # Inject state
        srv_main.db = test_db
        srv_main.storage = StorageManager(str(tmp_path / "storage"))
        srv_main.pairing = PairingManager()

        token_path = tmp_path / "local-admin.token"
        admin_token = ensure_local_admin_token(token_path)
        app.state.local_admin_token = admin_token

        client = TestClient(app)

        # 1. Without token: 401
        r = client.post("/api/v1/admin/pairing/open")
        assert r.status_code == 401

        # 2. With invalid token: 401
        r = client.post("/api/v1/admin/pairing/open", headers={"Authorization": "Bearer invalid_token"})
        assert r.status_code == 401

        # 3. With valid local admin token on testclient (loopback): 200
        r = client.post("/api/v1/admin/pairing/open", headers={"Authorization": f"Bearer {admin_token}"})
        assert r.status_code == 200
        data = r.json()
        assert "code" in data
        assert len(data["code"]) == 6
        assert data["expires_in_seconds"] == 600

        # Verify pairing manager is now open with that code
        assert srv_main.pairing.is_open is True
        valid, _ = srv_main.pairing.validate_code(data["code"])
        assert valid is True

    def test_non_loopback_local_admin_rejected(self, tmp_path):
        from main import app
        from database import Database
        from storage import StorageManager
        from auth import PairingManager
        from starlette.datastructures import Address
        import main as srv_main

        db_path = tmp_path / "auth_test2.db"
        test_db = Database(str(db_path))
        test_db.create_user("Owner", "owner", 1000000)

        srv_main.db = test_db
        srv_main.storage = StorageManager(str(tmp_path / "storage"))
        srv_main.pairing = PairingManager()

        token_path = tmp_path / "local-admin.token"
        admin_token = ensure_local_admin_token(token_path)
        app.state.local_admin_token = admin_token

        client = TestClient(app)
        with patch("starlette.requests.Request.client", new_callable=lambda: property(lambda self: Address("192.168.1.100", 52341))):
            r = client.post("/api/v1/admin/pairing/open", headers={"Authorization": f"Bearer {admin_token}"})
            assert r.status_code == 401
