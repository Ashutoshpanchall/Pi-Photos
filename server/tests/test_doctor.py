"""Tests for ServerDoctor diagnostics and checks (Phase S4)."""

from __future__ import annotations

import json
import os
import stat
import sys
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest

# Ensure server package is importable
sys.path.insert(0, str(Path(__file__).parent.parent))

from piphotos_server.paths import resolve_paths, ServerPaths
from piphotos_server.doctor import ServerDoctor, run_doctor, CheckResult
from piphotos_server.cli import build_parser


@pytest.fixture
def doctor_paths(tmp_path) -> ServerPaths:
    data_dir = tmp_path / "doctor_data"
    paths = resolve_paths(data_dir=data_dir)
    paths.ensure_dirs()
    return paths


class TestServerDoctorChecks:
    """Test individual checks inside ServerDoctor."""

    def test_python_version_pass(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        with patch("sys.version_info", (3, 11, 2, "final", 0)):
            doctor.check_python_version()
            assert len(doctor.results) == 1
            assert doctor.results[0].status == "PASS"

    def test_python_version_fail(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        with patch("sys.version_info", (3, 9, 7, "final", 0)):
            doctor.check_python_version()
            assert len(doctor.results) == 1
            assert doctor.results[0].status == "FAIL"
            assert "3.10" in doctor.results[0].fix_hint

    def test_data_dir_writable_pass(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        doctor.check_data_directory()
        assert any(r.name == "data_directory" and r.status == "PASS" for r in doctor.results)

    def test_data_dir_writable_fail(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        with patch.object(Path, "write_text", side_effect=PermissionError("Access denied")):
            doctor.check_data_directory()
            assert any(r.name == "data_directory" and r.status == "FAIL" for r in doctor.results)

    def test_disk_space_levels(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        # 50 GB free
        with patch("shutil.disk_usage", return_value=(100 * 1024**3, 50 * 1024**3, 50 * 1024**3)):
            doctor.check_disk_space()
            assert doctor.results[-1].status == "PASS"

        # 500 MB free (WARN)
        with patch("shutil.disk_usage", return_value=(100 * 1024**3, 99.5 * 1024**3, 500 * 1024**2)):
            doctor.check_disk_space()
            assert doctor.results[-1].status == "WARN"

        # 50 MB free (FAIL)
        with patch("shutil.disk_usage", return_value=(100 * 1024**3, 99.95 * 1024**3, 50 * 1024**2)):
            doctor.check_disk_space()
            assert doctor.results[-1].status == "FAIL"

    def test_hard_links_supported(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        real_stat = os.stat
        fake_stat = os.stat_result((stat.S_IFREG | 0o644, 12345, 1, 1, 1000, 1000, 100, 0, 0, 0))

        def selective_os_stat(path, *args, **kwargs):
            if ".doctor_hl" in str(path):
                return fake_stat
            return real_stat(path, *args, **kwargs)

        with patch("os.link", return_value=None), \
             patch("os.stat", side_effect=selective_os_stat):
            doctor.check_hard_links()
            assert any(r.name == "hard_links" and r.status == "PASS" for r in doctor.results)

    def test_hard_links_unsupported(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        with patch("os.link", side_effect=OSError(1, "Operation not permitted")):
            doctor.check_hard_links()
            res = next(r for r in doctor.results if r.name == "hard_links")
            assert res.status == "WARN"
            assert "FAT/exFAT" in res.fix_hint

    def test_certificates_missing(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        doctor.check_certificates()
        res = next(r for r in doctor.results if r.name == "tls_certificate")
        assert res.status == "FAIL"
        assert "setup" in res.fix_hint

    def test_certificates_present_and_permissions(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        doctor_paths.cert_path.write_text("DUMMY_CERT")
        doctor_paths.key_path.write_text("DUMMY_KEY")

        with patch("piphotos_server.doctor.get_cert_fingerprint", return_value="AA:BB:CC:DD"), \
             patch("sys.platform", "linux"), \
             patch.object(Path, "stat") as mock_stat:
            mock_stat.return_value = MagicMock(st_mode=0o100600)  # 0600 permissions
            doctor.check_certificates()
            assert any(r.name == "tls_certificate" and r.status == "PASS" for r in doctor.results)
            assert any(r.name == "tls_key_permissions" and r.status == "PASS" for r in doctor.results)

    def test_listening_addresses_lan_filtering(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        # Only loopback returned -> WARN
        with patch("piphotos_server.doctor.get_local_ipv4_addresses", return_value=["127.0.0.1"]):
            doctor.check_listening_addresses()
            assert doctor.results[-1].status == "WARN"
            assert "loopback only" in doctor.results[-1].message

        # Valid LAN address -> PASS
        with patch("piphotos_server.doctor.get_local_ipv4_addresses", return_value=["192.168.1.50"]):
            doctor.check_listening_addresses()
            assert doctor.results[-1].status == "PASS"

    def test_port_binding_and_ap_isolation_warning(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        # Simulate port bound by PiPhotos server, loopback works, but LAN fails (AP isolation)
        with patch("socket.socket.bind", side_effect=OSError("Address in use")), \
             patch.object(ServerDoctor, "_query_info", side_effect=lambda host, port: host == "127.0.0.1"), \
             patch("piphotos_server.doctor.get_local_ipv4_addresses", return_value=["192.168.1.100"]):
            doctor.check_port_and_endpoints()
            assert any(r.name == "port_binding" and r.status == "PASS" for r in doctor.results)
            assert any(r.name == "loopback_endpoint" and r.status == "PASS" for r in doctor.results)
            lan_res = next(r for r in doctor.results if r.name == "lan_endpoint")
            assert lan_res.status == "WARN"
            assert "AP Isolation" in lan_res.fix_hint

    def test_port_occupied_by_foreign_process(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        with patch("socket.socket.bind", side_effect=OSError("Address in use")), \
             patch.object(ServerDoctor, "_query_info", return_value=False):
            doctor.check_port_and_endpoints()
            port_res = next(r for r in doctor.results if r.name == "port_binding")
            assert port_res.status == "FAIL"
            assert "--port" in port_res.fix_hint

    def test_clock_sanity(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        with patch("time.time", return_value=1710000000):  # 2024
            doctor.check_clock_sanity()
            assert doctor.results[-1].status == "PASS"

        with patch("time.time", return_value=1000000000):  # 2001
            doctor.check_clock_sanity()
            assert doctor.results[-1].status == "FAIL"


class TestDoctorOsDiagnostics:
    """Test firewall, services, and sleep diagnostics with mock subprocess outputs."""

    def test_windows_firewall_rule_present(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        with patch("sys.platform", "win32"), \
             patch("subprocess.run") as mock_sub:
            mock_sub.return_value = MagicMock(returncode=0, stdout="Rule Name: PiPhotos Storage Node (TCP 8443)\nEnabled: Yes\n")
            doctor.check_firewall()
            assert any(r.name == "firewall" and r.status == "PASS" for r in doctor.results)

    def test_windows_firewall_rule_missing(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        with patch("sys.platform", "win32"), \
             patch("subprocess.run") as mock_sub:
            mock_sub.return_value = MagicMock(returncode=1, stdout="No rules match the specified criteria.\n")
            doctor.check_firewall()
            res = next(r for r in doctor.results if r.name == "firewall")
            assert res.status == "WARN"
            assert "install-service" in res.fix_hint

    def test_linux_ufw_firewall(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        with patch("sys.platform", "linux"), \
             patch("shutil.which", return_value="/usr/sbin/ufw"), \
             patch("subprocess.run") as mock_sub:
            mock_sub.return_value = MagicMock(returncode=0, stdout="Status: active\n8443/tcp ALLOW Anywhere\n")
            doctor.check_firewall()
            assert any(r.name == "firewall" and r.status == "PASS" for r in doctor.results)

    def test_windows_service_status(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        with patch("sys.platform", "win32"), \
             patch("subprocess.run") as mock_sub:
            mock_sub.return_value = MagicMock(returncode=0, stdout="TaskName: \\PiPhotos Server  Status: Running\n")
            doctor.check_service_status()
            assert any(r.name == "service" and r.status == "PASS" for r in doctor.results)

    def test_windows_sleep_detection(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        # Sleep enabled (non-zero seconds, e.g. 1800s = 0x00000708)
        output_enabled = "GUID Alias: STANDBYIDLE\n  Current AC Power Setting Index: 0x00000708"
        with patch("sys.platform", "win32"), \
             patch("subprocess.run", return_value=MagicMock(returncode=0, stdout=output_enabled)):
            doctor.check_sleep_settings()
            assert doctor.results[-1].status == "WARN"
            assert "Put the computer to sleep" in doctor.results[-1].fix_hint

        # Sleep disabled (0x00000000)
        output_disabled = "GUID Alias: STANDBYIDLE\n  Current AC Power Setting Index: 0x00000000"
        with patch("sys.platform", "win32"), \
             patch("subprocess.run", return_value=MagicMock(returncode=0, stdout=output_disabled)):
            doctor.check_sleep_settings()
            assert doctor.results[-1].status == "PASS"

    def test_macos_sleep_detection(self, doctor_paths):
        doctor = ServerDoctor(doctor_paths)
        # sleep 10 min -> WARN
        with patch("sys.platform", "darwin"), \
             patch("subprocess.run", return_value=MagicMock(returncode=0, stdout="sleep 10\ndisplaysleep 5\n")):
            doctor.check_sleep_settings()
            assert doctor.results[-1].status == "WARN"

        # sleep 0 -> PASS
        with patch("sys.platform", "darwin"), \
             patch("subprocess.run", return_value=MagicMock(returncode=0, stdout="sleep 0\ndisplaysleep 5\n")):
            doctor.check_sleep_settings()
            assert doctor.results[-1].status == "PASS"


class TestDoctorCliRunner:
    """Test run_doctor in text and JSON modes."""

    def test_run_doctor_json(self, doctor_paths, capsys):
        rc = run_doctor(doctor_paths, json_output=True)
        captured = capsys.readouterr().out
        data = json.loads(captured)
        assert "status" in data
        assert "summary" in data
        assert "checks" in data
        assert isinstance(data["checks"], list)

    def test_run_doctor_text_output_and_failure_exit_code(self, doctor_paths, capsys):
        with patch.object(ServerDoctor, "check_python_version", lambda self: self.results.append(
            CheckResult(name="python_version", status="FAIL", message="Outdated Python", fix_hint="Upgrade")
        )):
            rc = run_doctor(doctor_paths, json_output=False)
            captured = capsys.readouterr().out
            assert "PiPhotos Server Doctor Diagnostics" in captured
            assert "[FAIL]" in captured
            assert "Summary:" in captured
            # Exit code must be non-zero on FAIL
            assert rc == 1

    def test_doctor_parser_args(self):
        parser = build_parser()
        args = parser.parse_args(["doctor", "--json"])
        assert args.subcommand == "doctor"
        assert args.json is True
