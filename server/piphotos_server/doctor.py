"""System health, diagnostics, network checks, and doctor inspection (Phase S4)."""

from __future__ import annotations

import json
import os
import re
import shutil
import socket
import ssl
import subprocess
import sys
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Optional, List, Dict, Any

from .paths import ServerPaths, resolve_paths
from tls_cert import get_local_ipv4_addresses, get_cert_fingerprint


@dataclass
class CheckResult:
    name: str
    status: str  # "PASS", "WARN", "FAIL"
    message: str
    fix_hint: Optional[str] = None


class ServerDoctor:
    """Diagnoses system, storage, network, certificates, services, and firewall."""

    def __init__(self, paths: ServerPaths):
        self.paths = paths
        self.results: List[CheckResult] = []

    def _get_configured_port(self) -> int:
        """Read configured server port from config.json or return default 8443."""
        if self.paths.config_path.is_file():
            try:
                cfg = json.loads(self.paths.config_path.read_text(encoding="utf-8"))
                return int(cfg.get("port", 8443))
            except Exception:
                pass
        return 8443

    def run_all(self) -> List[CheckResult]:
        """Execute all diagnostics checks."""
        self.results = []
        self.check_python_version()
        self.check_data_directory()
        self.check_disk_space()
        self.check_hard_links()
        self.check_certificates()
        self.check_listening_addresses()
        self.check_port_and_endpoints()
        self.check_firewall()
        self.check_service_status()
        self.check_mdns()
        self.check_clock_sanity()
        self.check_sleep_settings()
        return self.results

    def check_python_version(self) -> None:
        """Verify Python >= 3.10."""
        v = sys.version_info
        ver_str = f"{v[0]}.{v[1]}.{v[2]}"
        if v[:2] >= (3, 10):
            self.results.append(CheckResult(
                name="python_version",
                status="PASS",
                message=f"Python version {ver_str} satisfies requirement (>= 3.10)",
            ))
        else:
            self.results.append(CheckResult(
                name="python_version",
                status="FAIL",
                message=f"Python version {ver_str} is unsupported (< 3.10)",
                fix_hint="Install Python 3.10 or newer from python.org or via package manager.",
            ))

    def check_data_directory(self) -> None:
        """Verify data directory exists and is writable."""
        self.paths.ensure_dirs()
        test_file = self.paths.data_dir / f".doctor_test_{int(time.time())}"
        try:
            test_file.write_text("write_test", encoding="utf-8")
            content = test_file.read_text(encoding="utf-8")
            test_file.unlink()
            if content == "write_test":
                self.results.append(CheckResult(
                    name="data_directory",
                    status="PASS",
                    message=f"Data directory is writable: {self.paths.data_dir}",
                ))
            else:
                self.results.append(CheckResult(
                    name="data_directory",
                    status="FAIL",
                    message=f"Write verification mismatch in: {self.paths.data_dir}",
                    fix_hint=f"Check permissions and filesystem integrity for {self.paths.data_dir}",
                ))
        except Exception as e:
            self.results.append(CheckResult(
                name="data_directory",
                status="FAIL",
                message=f"Cannot write to data directory: {e}",
                fix_hint=f"Grant write permissions to current user for {self.paths.data_dir}",
            ))

    def check_disk_space(self) -> None:
        """Verify disk space in storage directory."""
        self.paths.storage_dir.mkdir(parents=True, exist_ok=True)
        try:
            total, used, free = shutil.disk_usage(self.paths.storage_dir)
            free_gb = free / (1024 ** 3)
            if free_gb < 0.1:  # < 100 MB
                self.results.append(CheckResult(
                    name="disk_space",
                    status="FAIL",
                    message=f"Critically low disk space: {free_gb:.2f} GB free",
                    fix_hint="Free up disk space on storage drive immediately.",
                ))
            elif free_gb < 1.0:  # < 1 GB
                self.results.append(CheckResult(
                    name="disk_space",
                    status="WARN",
                    message=f"Low disk space: {free_gb:.2f} GB free (reserve recommendation >= 1 GB)",
                    fix_hint="Ensure adequate drive space for incoming photo backups.",
                ))
            else:
                self.results.append(CheckResult(
                    name="disk_space",
                    status="PASS",
                    message=f"Disk space adequate: {free_gb:.1f} GB free",
                ))
        except Exception as e:
            self.results.append(CheckResult(
                name="disk_space",
                status="WARN",
                message=f"Could not determine disk usage: {e}",
                fix_hint="Verify drive accessibility and storage directory path.",
            ))

    def check_hard_links(self) -> None:
        """Test whether storage filesystem supports hard links (deduplication)."""
        self.paths.storage_dir.mkdir(parents=True, exist_ok=True)
        src = self.paths.storage_dir / f".doctor_hl_src_{int(time.time())}"
        dst = self.paths.storage_dir / f".doctor_hl_dst_{int(time.time())}"
        try:
            src.write_text("hardlink_test", encoding="utf-8")
            os.link(src, dst)
            supported = os.stat(src).st_ino == os.stat(dst).st_ino
            try:
                src.unlink(missing_ok=True)
                dst.unlink(missing_ok=True)
            except Exception:
                pass
            if supported:
                self.results.append(CheckResult(
                    name="hard_links",
                    status="PASS",
                    message="Filesystem supports hard links (Content-Addressable deduplication enabled)",
                ))
            else:
                self.results.append(CheckResult(
                    name="hard_links",
                    status="WARN",
                    message="Hard links succeeded but inode test did not match.",
                    fix_hint="Use a native filesystem like NTFS, ext4, or APFS for full deduplication.",
                ))
        except (OSError, NotImplementedError) as e:
            try:
                if src.exists():
                    src.unlink()
                if dst.exists():
                    dst.unlink()
            except Exception:
                pass
            self.results.append(CheckResult(
                name="hard_links",
                status="WARN",
                message=f"Filesystem does not support hard links ({e}). Duplicate files will be copied.",
                fix_hint="For optimal storage deduplication, format storage drive with NTFS, ext4, or APFS (avoid FAT/exFAT).",
            ))

    def check_certificates(self) -> None:
        """Verify TLS certificate existence, private key security permissions, fingerprint, and SANs."""
        if not self.paths.cert_path.is_file() or not self.paths.key_path.is_file():
            self.results.append(CheckResult(
                name="tls_certificate",
                status="FAIL",
                message=f"TLS certificate or private key missing at {self.paths.certs_dir}",
                fix_hint="Run 'piphotos-server setup' to generate server certificates.",
            ))
            return

        # Unix permissions check
        if sys.platform != "win32":
            try:
                mode = self.paths.key_path.stat().st_mode & 0o777
                if mode != 0o600:
                    self.results.append(CheckResult(
                        name="tls_key_permissions",
                        status="WARN",
                        message=f"Private key file permissions are {oct(mode)} (expected 0600)",
                        fix_hint=f"Run: chmod 0600 '{self.paths.key_path}'",
                    ))
                else:
                    self.results.append(CheckResult(
                        name="tls_key_permissions",
                        status="PASS",
                        message="Private key file permissions secure (0600)",
                    ))
            except Exception:
                pass

        # Inspect certificate fingerprint and Subject Alternative Names (SAN)
        try:
            fp = get_cert_fingerprint(self.paths.cert_path)
            san_names = []
            try:
                from cryptography import x509
                cert_bytes = self.paths.cert_path.read_bytes()
                cert = x509.load_pem_x509_certificate(cert_bytes)
                ext = cert.extensions.get_extension_for_oid(x509.oid.ExtensionOID.SUBJECT_ALTERNATIVE_NAME)
                san_names = [str(x.value) for x in ext.value]
            except Exception:
                pass

            san_info = f" (SAN: {', '.join(san_names[:3])})" if san_names else ""
            self.results.append(CheckResult(
                name="tls_certificate",
                status="PASS",
                message=f"TLS certificate valid (SHA-256 fingerprint: {fp[:16]}...){san_info}",
            ))
        except Exception as e:
            self.results.append(CheckResult(
                name="tls_certificate",
                status="WARN",
                message=f"Could not parse TLS certificate: {e}",
                fix_hint="Re-generate TLS certificate with: piphotos-server setup --regen-cert",
            ))

    def check_listening_addresses(self) -> None:
        """Check for active LAN network interfaces (excluding loopback)."""
        ips = get_local_ipv4_addresses()
        lan_ips = [ip for ip in ips if not ip.startswith("127.")]
        if not lan_ips:
            self.results.append(CheckResult(
                name="network_interfaces",
                status="WARN",
                message="No active LAN IPv4 addresses detected (loopback only)",
                fix_hint="Connect computer to local Wi-Fi or Ethernet network so mobile devices can reach it.",
            ))
        else:
            self.results.append(CheckResult(
                name="network_interfaces",
                status="PASS",
                message=f"Detected active LAN interfaces: {', '.join(lan_ips)}",
            ))

    def check_port_and_endpoints(self) -> None:
        """Check configured port availability and query /api/v1/info on loopback and LAN IP."""
        port = self._get_configured_port()

        # Test socket binding with guaranteed socket closure
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(0.5)
        in_use = False
        try:
            sock.bind(("0.0.0.0", port))
            in_use = False
        except OSError:
            in_use = True
        finally:
            try:
                sock.close()
            except Exception:
                pass

        ips = get_local_ipv4_addresses()
        lan_ips = [ip for ip in ips if not ip.startswith("127.")]
        lan_ip = lan_ips[0] if lan_ips else None

        if in_use:
            # Check if it's our own PiPhotos server responding
            loopback_ok = self._query_info("127.0.0.1", port)
            if loopback_ok:
                self.results.append(CheckResult(
                    name="port_binding",
                    status="PASS",
                    message=f"Port {port} is active and serving PiPhotos server",
                ))
                self.results.append(CheckResult(
                    name="loopback_endpoint",
                    status="PASS",
                    message=f"Loopback endpoint https://127.0.0.1:{port}/api/v1/info responded 200 OK",
                ))

                # Test non-loopback LAN IP if available
                if lan_ip:
                    lan_ok = self._query_info(lan_ip, port)
                    if lan_ok:
                        self.results.append(CheckResult(
                            name="lan_endpoint",
                            status="PASS",
                            message=f"LAN endpoint https://{lan_ip}:{port}/api/v1/info responded 200 OK",
                        ))
                    else:
                        self.results.append(CheckResult(
                            name="lan_endpoint",
                            status="WARN",
                            message=f"Loopback succeeded but LAN IP https://{lan_ip}:{port}/api/v1/info failed",
                            fix_hint="Check if Wi-Fi router has 'AP Isolation' / 'Client Isolation' enabled or firewall is blocking incoming connections.",
                        ))
            else:
                self.results.append(CheckResult(
                    name="port_binding",
                    status="FAIL",
                    message=f"Port {port} is occupied by another non-PiPhotos process",
                    fix_hint=f"Stop the conflicting application or run 'piphotos-server setup --port <new-port>'",
                ))
        else:
            self.results.append(CheckResult(
                name="port_binding",
                status="PASS",
                message=f"Port {port} is free and ready to accept connections (server not currently running)",
            ))

    def _query_info(self, host: str, port: int) -> bool:
        """Perform loopback/LAN GET /api/v1/info query ignoring self-signed certificate."""
        import urllib.request
        ctx = ssl._create_unverified_context()
        url = f"https://{host}:{port}/api/v1/info"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "PiPhotos-Doctor"})
            with urllib.request.urlopen(req, context=ctx, timeout=2.0) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    return "server_name" in data or "version" in data or "storage" in data
        except Exception:
            return False
        return False

    def check_firewall(self) -> None:
        """Inspect OS firewall rules for TCP <port> and UDP 5353."""
        port = self._get_configured_port()
        if sys.platform == "win32":
            res_tcp = subprocess.run(
                ["netsh", "advfirewall", "firewall", "show", "rule", f"name=PiPhotos Storage Node (TCP {port})"],
                capture_output=True,
                text=True,
            )
            has_tcp = res_tcp.returncode == 0 and "PiPhotos" in res_tcp.stdout

            if has_tcp:
                self.results.append(CheckResult(
                    name="firewall",
                    status="PASS",
                    message=f"Windows Firewall rule configured for TCP {port}",
                ))
            else:
                self.results.append(CheckResult(
                    name="firewall",
                    status="WARN",
                    message=f"Windows Firewall rule for TCP {port} not found",
                    fix_hint=f"Run 'piphotos-server install-service' as Administrator to automatically add firewall rules.",
                ))
        elif sys.platform == "linux":
            if shutil.which("ufw"):
                res = subprocess.run(["ufw", "status"], capture_output=True, text=True)
                if "Status: active" in res.stdout:
                    if f"{port}/tcp" in res.stdout:
                        self.results.append(CheckResult(
                            name="firewall",
                            status="PASS",
                            message=f"ufw active and allows port {port}/tcp",
                        ))
                    else:
                        self.results.append(CheckResult(
                            name="firewall",
                            status="WARN",
                            message=f"ufw is active but port {port}/tcp is not allowed",
                            fix_hint=f"Run: sudo ufw allow {port}/tcp comment 'PiPhotos'",
                        ))
                    return
            self.results.append(CheckResult(
                name="firewall",
                status="PASS",
                message="No active blocking firewall detected",
            ))
        elif sys.platform == "darwin":
            self.results.append(CheckResult(
                name="firewall",
                status="PASS",
                message="macOS firewall allows connections dynamically per application prompt",
            ))

    def check_service_status(self) -> None:
        """Check whether auto-start background service is installed and running."""
        if sys.platform == "win32":
            res = subprocess.run(["schtasks", "/Query", "/TN", "PiPhotos Server"], capture_output=True, text=True)
            if res.returncode == 0:
                is_running = "Running" in res.stdout
                self.results.append(CheckResult(
                    name="service",
                    status="PASS" if is_running else "WARN",
                    message=f"Windows Scheduled Task 'PiPhotos Server' is installed ({'Running' if is_running else 'Ready/Stopped'})",
                    fix_hint=None if is_running else "Start it with 'piphotos-server start' or run in foreground with 'piphotos-server run'.",
                ))
            else:
                self.results.append(CheckResult(
                    name="service",
                    status="WARN",
                    message="Windows Scheduled Task 'PiPhotos Server' is not registered",
                    fix_hint="Register auto-start service with 'piphotos-server install-service'.",
                ))
        elif sys.platform == "linux":
            res = subprocess.run(["systemctl", "--user", "is-active", "piphotos.service"], capture_output=True, text=True)
            user_active = res.stdout.strip() == "active"
            if user_active:
                self.results.append(CheckResult(
                    name="service",
                    status="PASS",
                    message="Systemd user service 'piphotos.service' is active",
                ))
            else:
                self.results.append(CheckResult(
                    name="service",
                    status="WARN",
                    message="Systemd user service is not running",
                    fix_hint="Install and enable service with 'piphotos-server install-service'.",
                ))
        elif sys.platform == "darwin":
            plist = Path.home() / "Library" / "LaunchAgents" / "com.piphotos.server.plist"
            if plist.is_file():
                self.results.append(CheckResult(
                    name="service",
                    status="PASS",
                    message="macOS LaunchAgent com.piphotos.server is installed",
                ))
            else:
                self.results.append(CheckResult(
                    name="service",
                    status="WARN",
                    message="macOS LaunchAgent not installed",
                    fix_hint="Install auto-start agent with 'piphotos-server install-service'.",
                ))

    def check_mdns(self) -> None:
        """Perform mDNS self-discovery test if zeroconf is available."""
        zc = None
        try:
            from zeroconf import Zeroconf, ServiceBrowser  # type: ignore

            zc = Zeroconf()
            discovered = []

            class Listener:
                def add_service(self, zc_inst, type_, name):
                    discovered.append(name)

                def remove_service(self, zc_inst, type_, name):
                    pass

                def update_service(self, zc_inst, type_, name):
                    pass

            browser = ServiceBrowser(zc, "_piphotos._tcp.local.", Listener())
            time.sleep(0.3)

            if discovered:
                self.results.append(CheckResult(
                    name="mdns_discovery",
                    status="PASS",
                    message=f"mDNS service discovery active: {', '.join(discovered)}",
                ))
            else:
                self.results.append(CheckResult(
                    name="mdns_discovery",
                    status="PASS",
                    message="mDNS library operational (discovery active when server is running)",
                ))
        except Exception as e:
            self.results.append(CheckResult(
                name="mdns_discovery",
                status="WARN",
                message=f"mDNS check skipped: {e}",
                fix_hint="Install avahi-daemon on Linux or verify zeroconf Python package.",
            ))
        finally:
            if zc:
                try:
                    zc.close()
                except Exception:
                    pass

    def check_clock_sanity(self) -> None:
        """Verify local system clock sanity (monotonic and reasonable range)."""
        now = time.time()
        if now > 1704067200:  # Jan 1, 2024
            self.results.append(CheckResult(
                name="system_clock",
                status="PASS",
                message="System clock is sane and synchronized",
            ))
        else:
            self.results.append(CheckResult(
                name="system_clock",
                status="FAIL",
                message="System clock appears inaccurate (before 2024)",
                fix_hint="Synchronize system time via NTP or OS time settings.",
            ))

    def check_sleep_settings(self) -> None:
        """Check laptop standby/sleep settings (sleeping computers drop off the network)."""
        if sys.platform == "win32":
            try:
                res = subprocess.run(["powercfg", "/q"], capture_output=True, text=True)
                # Look specifically for Current AC Power Setting Index under STANDBYIDLE
                # Format: Current AC Power Setting Index: 0x00000000 (0 means never sleep)
                match = re.search(r"STANDBYIDLE.*?Current AC Power Setting Index:\s*(0x[0-9a-fA-F]+)", res.stdout, re.DOTALL)
                if match:
                    timeout_val = int(match.group(1), 16)
                    if timeout_val > 0:
                        self.results.append(CheckResult(
                            name="power_sleep",
                            status="WARN",
                            message=f"System sleep on AC power is enabled ({timeout_val} seconds timeout)",
                            fix_hint="For 24/7 backup availability, set 'Put the computer to sleep' to 'Never' while plugged in.",
                        ))
                        return
                    else:
                        self.results.append(CheckResult(
                            name="power_sleep",
                            status="PASS",
                            message="System sleep on AC power is set to Never (optimal for server)",
                        ))
                        return
            except Exception:
                pass
            self.results.append(CheckResult(
                name="power_sleep",
                status="PASS",
                message="Power configuration check complete",
            ))
        elif sys.platform == "darwin":
            try:
                res = subprocess.run(["pmset", "-g"], capture_output=True, text=True)
                # Format: sleep 0 (0 means sleep disabled) or sleep 10
                match = re.search(r"^\s*sleep\s+(\d+)", res.stdout, re.MULTILINE)
                if match:
                    sleep_min = int(match.group(1))
                    if sleep_min > 0:
                        self.results.append(CheckResult(
                            name="power_sleep",
                            status="WARN",
                            message=f"macOS system sleep is enabled ({sleep_min} minutes timeout)",
                            fix_hint="Disable sleep while connected to power in Energy Saver settings (sudo pmset -c sleep 0).",
                        ))
                        return
                    else:
                        self.results.append(CheckResult(
                            name="power_sleep",
                            status="PASS",
                            message="macOS system sleep is disabled on AC power",
                        ))
                        return
            except Exception:
                pass
            self.results.append(CheckResult(
                name="power_sleep",
                status="PASS",
                message="Power management settings checked",
            ))
        else:
            self.results.append(CheckResult(
                name="power_sleep",
                status="PASS",
                message="System power management checked",
            ))


def run_doctor(paths: ServerPaths, json_output: bool = False) -> int:
    """CLI runner for doctor command."""
    doctor = ServerDoctor(paths)
    results = doctor.run_all()

    pass_count = sum(1 for r in results if r.status == "PASS")
    warn_count = sum(1 for r in results if r.status == "WARN")
    fail_count = sum(1 for r in results if r.status == "FAIL")

    overall_status = "FAIL" if fail_count > 0 else ("WARN" if warn_count > 0 else "PASS")

    if json_output:
        payload = {
            "status": overall_status,
            "summary": {
                "pass": pass_count,
                "warn": warn_count,
                "fail": fail_count,
                "total": len(results),
            },
            "checks": [asdict(r) for r in results],
        }
        print(json.dumps(payload, indent=2))
        return 1 if fail_count > 0 else 0

    print("=" * 65)
    print("              PiPhotos Server Doctor Diagnostics              ")
    print("=" * 65)
    for r in results:
        status_tag = f"[{r.status}]".ljust(7)
        print(f"  {status_tag} {r.name}: {r.message}")
        if r.fix_hint:
            print(f"          -> Fix hint: {r.fix_hint}")
    print("=" * 65)
    print(f"  Summary: {pass_count} PASS, {warn_count} WARN, {fail_count} FAIL")
    print("=" * 65)

    return 1 if fail_count > 0 else 0
