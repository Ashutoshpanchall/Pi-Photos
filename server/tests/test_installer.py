"""Tests for server installers and CLI update/uninstall subcommands (Phase S3)."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest

# Ensure server package is importable
sys.path.insert(0, str(Path(__file__).parent.parent))

from piphotos_server.paths import resolve_paths, ServerPaths
from piphotos_server.cli import build_parser, cmd_update, cmd_uninstall


@pytest.fixture
def dummy_paths(tmp_path) -> ServerPaths:
    data_dir = tmp_path / "piphotos_data"
    paths = resolve_paths(data_dir=data_dir)
    paths.ensure_dirs()
    return paths


class TestCliUpdateAndUninstall:
    """Test piphotos-server update and uninstall CLI subcommands."""

    def test_update_parser_args(self):
        parser = build_parser()
        args = parser.parse_args(["update", "--ref", "v1.2.0", "--system"])
        assert args.subcommand == "update"
        assert args.ref == "v1.2.0"
        assert args.system is True

    def test_uninstall_parser_args(self):
        parser = build_parser()
        args = parser.parse_args(["uninstall", "--purge-data", "--system", "--no-firewall"])
        assert args.subcommand == "uninstall"
        assert args.purge_data is True
        assert args.system is True
        assert args.no_firewall is True

    def test_cmd_update_invokes_uv_and_service_restart(self):
        with patch("shutil.which", return_value="/usr/local/bin/uv"), \
             patch("subprocess.run") as mock_run, \
             patch("piphotos_server.service.restart_service", return_value=(True, "Restarted")) as mock_restart:
            mock_run.return_value = MagicMock(returncode=0, stdout="Success", stderr="")

            args = MagicMock(ref="main", system=False)
            rc = cmd_update(args)
            assert rc == 0
            assert mock_restart.called
            # Verify uv tool install command was invoked
            called_cmd = mock_run.call_args[0][0]
            assert "uv" in called_cmd[0]
            assert "tool" in called_cmd
            assert any("piphotos-server" in s or "Pi-Photos@main" in s for s in called_cmd)

    def test_cmd_uninstall_keeps_data_by_default(self, dummy_paths, capsys):
        # Create a mock database file
        test_file = dummy_paths.data_dir / "test.txt"
        test_file.write_text("safe_data")

        with patch("piphotos_server.service.uninstall_service", return_value=(True, "Service removed")), \
             patch("shutil.which", return_value="/usr/local/bin/uv"), \
             patch("subprocess.run"):
            args = MagicMock(
                data_dir=str(dummy_paths.data_dir),
                purge_data=False,
                system=False,
                no_firewall=False,
            )
            rc = cmd_uninstall(args)
            assert rc == 0
            # Data directory must still exist and file must remain
            assert dummy_paths.data_dir.exists()
            assert test_file.exists()
            assert "Your photos and database remain safe" in capsys.readouterr().out

    def test_cmd_uninstall_purge_data_confirmed(self, dummy_paths):
        test_file = dummy_paths.data_dir / "test.txt"
        test_file.write_text("delete_me")

        with patch("piphotos_server.service.uninstall_service", return_value=(True, "Service removed")), \
             patch("shutil.which", return_value=None), \
             patch("builtins.input", return_value=dummy_paths.data_dir.name):
            args = MagicMock(
                data_dir=str(dummy_paths.data_dir),
                purge_data=True,
                system=False,
                no_firewall=False,
            )
            rc = cmd_uninstall(args)
            assert rc == 0
            # Directory should be permanently removed
            assert not dummy_paths.data_dir.exists()

    def test_cmd_uninstall_purge_data_aborted_on_mismatch(self, dummy_paths, capsys):
        test_file = dummy_paths.data_dir / "test.txt"
        test_file.write_text("keep_me")

        with patch("piphotos_server.service.uninstall_service", return_value=(True, "Service removed")), \
             patch("shutil.which", return_value=None), \
             patch("builtins.input", return_value="WRONG_NAME"):
            args = MagicMock(
                data_dir=str(dummy_paths.data_dir),
                purge_data=True,
                system=False,
                no_firewall=False,
            )
            rc = cmd_uninstall(args)
            assert rc == 0
            assert dummy_paths.data_dir.exists()
            assert "Confirmation mismatch. Data directory was NOT deleted." in capsys.readouterr().out

    def test_cmd_uninstall_root_protection(self, capsys):
        with patch("piphotos_server.service.uninstall_service", return_value=(True, "Service removed")), \
             patch("shutil.which", return_value=None):
            args = MagicMock(
                data_dir="/",
                purge_data=True,
                system=False,
                no_firewall=False,
            )
            rc = cmd_uninstall(args)
            assert rc == 1
            assert "Refusing to purge filesystem root" in capsys.readouterr().out


PWSH_BIN = shutil.which("powershell.exe") or shutil.which("powershell") or shutil.which("pwsh")

BASH_BIN = shutil.which("bash") or shutil.which("sh")
if not BASH_BIN and Path(r"C:\Program Files\Git\bin\bash.exe").is_file():
    BASH_BIN = r"C:\Program Files\Git\bin\bash.exe"


class TestInstallerScripts:
    """Dry-run verification of install.sh and install.ps1."""

    @pytest.mark.skipif(PWSH_BIN is None, reason="PowerShell is not installed on this system")
    def test_install_ps1_dry_run(self):
        script_path = Path(__file__).parent.parent.parent / "install" / "install.ps1"
        assert script_path.is_file(), f"install.ps1 not found at {script_path}"

        res = subprocess.run(
            [PWSH_BIN, "-ExecutionPolicy", "Bypass", "-File", str(script_path), "-DryRun", "-Ref", "test-ref"],
            capture_output=True,
            text=True,
        )
        assert res.returncode == 0
        assert "PiPhotos Storage Node Server Installer (Windows)" in res.stdout
        assert "Target Ref:    test-ref" in res.stdout
        assert "[DRY-RUN]" in res.stdout

    @pytest.mark.skipif(PWSH_BIN is None, reason="PowerShell is not installed on this system")
    def test_install_ps1_posix_args(self):
        script_path = Path(__file__).parent.parent.parent / "install" / "install.ps1"
        res = subprocess.run(
            [PWSH_BIN, "-ExecutionPolicy", "Bypass", "-File", str(script_path), "--dry-run", "--ref", "posix-ref", "--port", "9443"],
            capture_output=True,
            text=True,
        )
        assert res.returncode == 0
        assert "Target Ref:    posix-ref" in res.stdout
        assert "HTTPS Port:    9443" in res.stdout
        assert "[DRY-RUN]" in res.stdout

    def test_install_sh_dry_run_linux(self):
        script_path = Path(__file__).parent.parent.parent / "install" / "install.sh"
        assert script_path.is_file(), f"install.sh not found at {script_path}"

        if not BASH_BIN:
            pytest.skip("Bash shell not available on this host")

        res = subprocess.run(
            [BASH_BIN, "-c", f'TARGET_OS=Linux TARGET_ARCH=x86_64 sh "{script_path.as_posix()}" --dry-run --ref test-ref'],
            capture_output=True,
            text=True,
        )
        assert res.returncode == 0
        assert "Platform:      Linux (x86_64)" in res.stdout
        assert "Target Ref:    test-ref" in res.stdout
        assert "[DRY-RUN]" in res.stdout

    def test_install_sh_dry_run_macos(self):
        script_path = Path(__file__).parent.parent.parent / "install" / "install.sh"
        if not BASH_BIN:
            pytest.skip("Bash shell not available on this host")

        res = subprocess.run(
            [BASH_BIN, "-c", f'TARGET_OS=Darwin TARGET_ARCH=arm64 sh "{script_path.as_posix()}" --dry-run --ref test-ref'],
            capture_output=True,
            text=True,
        )
        assert res.returncode == 0
        assert "Platform:      macOS (arm64)" in res.stdout
        assert "Target Ref:    test-ref" in res.stdout
        assert "[DRY-RUN]" in res.stdout
