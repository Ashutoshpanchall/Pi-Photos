"""Tests for service rendering, auto-start, firewall, and Raspberry Pi detection (Phase S2)."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest

# Ensure server package is importable
sys.path.insert(0, str(Path(__file__).parent.parent))

from piphotos_server.paths import resolve_paths, ServerPaths
from piphotos_server.service import (
    render_systemd_service,
    render_launchd_plist,
    render_windows_task_xml,
    detect_raspberry_pi,
    configure_firewall,
    install_service,
    uninstall_service,
    stream_logs,
)
from piphotos_server.cli import build_parser


@pytest.fixture
def dummy_paths(tmp_path) -> ServerPaths:
    data_dir = tmp_path / "piphotos_data"
    return resolve_paths(data_dir=data_dir)


class TestServiceTemplates:
    """Golden-format and structure verification for service definitions."""

    def test_systemd_user_service(self, dummy_paths):
        content = render_systemd_service(dummy_paths, is_system=False, python_bin="/usr/bin/python3")
        assert "[Unit]" in content
        assert "Description=PiPhotos Storage Node" in content
        assert f'ExecStart="/usr/bin/python3" -m piphotos_server.cli run --data-dir "{dummy_paths.data_dir}"' in content
        assert "Restart=on-failure" in content
        assert "WantedBy=default.target" in content
        assert f'WorkingDirectory="{dummy_paths.data_dir}"' in content
        # User unit should NOT have User=piphotos
        assert "User=piphotos" not in content

    def test_systemd_system_service_hardened(self, dummy_paths):
        content = render_systemd_service(dummy_paths, is_system=True, python_bin="/usr/bin/python3")
        assert "User=piphotos" in content
        assert "NoNewPrivileges=true" in content
        assert "ProtectSystem=strict" in content
        assert "ProtectHome=true" in content
        assert "PrivateTmp=true" in content
        assert f'ReadWritePaths="{dummy_paths.data_dir}" "{dummy_paths.storage_dir}"' in content
        assert "WantedBy=multi-user.target" in content

    def test_systemd_pi_zero_memory_limit(self, dummy_paths):
        content = render_systemd_service(dummy_paths, is_system=True, is_pi_zero=True)
        assert "MemoryMax=200M" in content
        assert "MemoryHigh=150M" in content

    def test_launchd_plist_valid_xml(self, dummy_paths):
        plist = render_launchd_plist(dummy_paths, python_bin="/usr/local/bin/python3")
        root = ET.fromstring(plist)
        assert root.tag == "plist"
        assert "com.piphotos.server" in plist
        assert "<key>RunAtLoad</key>" in plist
        assert "<key>KeepAlive</key>" in plist
        assert "/usr/local/bin/python3" in plist

    def test_windows_task_xml_user(self, dummy_paths):
        xml_text = render_windows_task_xml(dummy_paths, python_bin=r"C:\Python312\python.exe", is_system=False)
        assert "<LogonTrigger>" in xml_text
        assert "LeastPrivilege" in xml_text
        assert "PiPhotos Storage Node" in xml_text
        assert r"C:\Python312\python.exe" in xml_text
        # Validate that the generated XML is strictly well-formed
        root = ET.fromstring(xml_text.encode("utf-16"))
        assert "Task" in root.tag

    def test_windows_task_xml_system(self, dummy_paths):
        xml_text = render_windows_task_xml(dummy_paths, python_bin=r"C:\Python312\python.exe", is_system=True)
        assert "<BootTrigger>" in xml_text
        assert "S-1-5-18" in xml_text
        assert "HighestAvailable" in xml_text
        root = ET.fromstring(xml_text.encode("utf-16"))
        assert "Task" in root.tag


class TestRaspberryPiDetection:
    """Test Raspberry Pi awareness and architecture warnings."""

    def test_pi_zero_detection(self):
        with patch.object(Path, "is_file", return_value=True), \
             patch.object(Path, "read_text", return_value="Raspberry Pi Zero 2 W Rev 1.0\x00"), \
             patch("platform.machine", return_value="aarch64"), \
             patch("shutil.which", return_value="/usr/sbin/avahi-daemon"):
            info = detect_raspberry_pi()
            assert info is not None
            assert info["is_pi_zero"] is True
            assert info["is_32bit"] is False
            assert info["avahi_installed"] is True

    def test_pi_32bit_warning(self):
        with patch.object(Path, "is_file", return_value=True), \
             patch.object(Path, "read_text", return_value="Raspberry Pi 4 Model B\x00"), \
             patch("platform.machine", return_value="armv7l"), \
             patch("shutil.which", return_value=None):
            info = detect_raspberry_pi()
            assert info is not None
            assert info["is_32bit"] is True
            assert any("32-bit" in w for w in info["warnings"])
            assert any("avahi-daemon" in w for w in info["warnings"])

    def test_non_pi_detection(self):
        with patch.object(Path, "is_file", return_value=False):
            info = detect_raspberry_pi()
            assert info is None


class TestFirewallConfig:
    """Test firewall rules generation on different OSes."""

    def test_windows_firewall_add(self):
        with patch("sys.platform", "win32"), \
             patch("subprocess.run") as mock_sub:
            mock_sub.return_value = MagicMock(returncode=0, stdout="Ok.", stderr="")
            ok, msg = configure_firewall("add", port=8443, mdns_port=5353)
            assert ok is True
            assert "Windows Firewall rules added" in msg
            assert mock_sub.call_count == 2
            # Verify no shell=True
            for call_args in mock_sub.call_args_list:
                assert call_args.kwargs.get("shell") is not True

    def test_windows_firewall_failure(self):
        with patch("sys.platform", "win32"), \
             patch("subprocess.run") as mock_sub:
            mock_sub.return_value = MagicMock(returncode=1, stdout="", stderr="Access is denied.")
            ok, msg = configure_firewall("add", port=8443)
            assert ok is False
            assert "Failed to add Windows Firewall rules" in msg

    def test_linux_ufw_firewall(self):
        with patch("sys.platform", "linux"), \
             patch("shutil.which", side_effect=lambda cmd: "/usr/sbin/ufw" if cmd == "ufw" else None), \
             patch("subprocess.run") as mock_sub:
            mock_sub.return_value = MagicMock(returncode=0, stdout="Status: active\n")
            ok, msg = configure_firewall("add", port=8443)
            assert ok is True
            assert "ufw rules added" in msg


class TestPrivilegeChecks:
    """Test privilege validation for system services."""

    def test_windows_system_service_requires_elevation(self, dummy_paths):
        with patch("sys.platform", "win32"), \
             patch("piphotos_server.service.is_windows_elevated", return_value=False):
            ok, msg = install_service(dummy_paths, is_system=True)
            assert ok is False
            assert "Administrator privileges" in msg

            ok_un, msg_un = uninstall_service(dummy_paths, is_system=True)
            assert ok_un is False
            assert "Administrator privileges" in msg_un

    def test_linux_system_service_requires_root(self, dummy_paths):
        with patch("sys.platform", "linux"), \
             patch("piphotos_server.service.is_root_user", return_value=False):
            ok, msg = install_service(dummy_paths, is_system=True)
            assert ok is False
            assert "root privileges" in msg


class TestStreamLogs:
    """Test log display functionality."""

    def test_stream_logs_reads_last_lines(self, dummy_paths, capsys):
        dummy_paths.ensure_dirs()
        log_file = dummy_paths.logs_dir / "test.log"
        log_file.write_text("line1\nline2\nline3\nline4\n", encoding="utf-8")

        stream_logs(dummy_paths, follow=False, lines=2)
        captured = capsys.readouterr().out
        assert "Viewing:" in captured
        assert "line3" in captured
        assert "line4" in captured
        assert "line1" not in captured

    def test_stream_logs_no_files(self, dummy_paths, capsys):
        dummy_paths.ensure_dirs()
        stream_logs(dummy_paths, follow=False)
        captured = capsys.readouterr().out
        assert "No log files found" in captured


class TestServiceCliCommands:
    """Test CLI subcommand argument parsing for service actions."""

    def test_parser_service_commands(self):
        parser = build_parser()

        args = parser.parse_args(["install-service", "--system", "--no-firewall"])
        assert args.subcommand == "install-service"
        assert args.system is True
        assert args.no_firewall is True

        args = parser.parse_args(["uninstall-service", "--system"])
        assert args.subcommand == "uninstall-service"
        assert args.system is True

        args = parser.parse_args(["start"])
        assert args.subcommand == "start"

        args = parser.parse_args(["stop"])
        assert args.subcommand == "stop"

        args = parser.parse_args(["restart"])
        assert args.subcommand == "restart"

        args = parser.parse_args(["logs", "-f", "-n", "100"])
        assert args.subcommand == "logs"
        assert args.follow is True
        assert args.lines == 100
