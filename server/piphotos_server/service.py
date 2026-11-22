"""Service management, firewall configuration, auto-start, and Raspberry Pi tuning (Phase S2)."""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional, Dict, Any

from .paths import ServerPaths, resolve_paths, is_windows_elevated, is_root_user


# ---------------------------------------------------------------------------
# Service definition renderers
# ---------------------------------------------------------------------------

def render_systemd_service(
    paths: ServerPaths,
    is_system: bool = False,
    python_bin: Optional[str] = None,
    is_pi_zero: bool = False,
) -> str:
    """Render systemd unit file content."""
    py = python_bin or sys.executable
    exec_start = f'"{py}" -m piphotos_server.cli run --data-dir "{paths.data_dir}"'

    lines = [
        "[Unit]",
        "Description=PiPhotos Storage Node",
        "After=network.target",
        "Wants=network-online.target",
        f"RequiresMountsFor={paths.storage_dir}",
        "",
        "[Service]",
        "Type=simple",
        f"ExecStart={exec_start}",
        "Restart=on-failure",
        "RestartSec=5",
        f'WorkingDirectory="{paths.data_dir}"',
        f'Environment="PIPHOTOS_DATA_DIR={paths.data_dir}"',
        "Environment=PYTHONUNBUFFERED=1",
    ]

    if is_system:
        lines.extend([
            "User=piphotos",
            "Group=piphotos",
            "NoNewPrivileges=true",
            "ProtectSystem=strict",
            "ProtectHome=true",
            f'ReadWritePaths="{paths.data_dir}" "{paths.storage_dir}"',
            "PrivateTmp=true",
            "PrivateDevices=true",
        ])

    if is_pi_zero:
        lines.extend([
            "# Resource limits tuned for Pi Zero 2 W",
            "MemoryMax=200M",
            "MemoryHigh=150M",
        ])

    lines.extend([
        "",
        "[Install]",
        f"WantedBy={'multi-user.target' if is_system else 'default.target'}",
        "",
    ])
    return "\n".join(lines)


def render_launchd_plist(
    paths: ServerPaths,
    python_bin: Optional[str] = None,
) -> str:
    """Render macOS launchd LaunchAgent XML plist."""
    py = python_bin or sys.executable
    log_out = str(paths.logs_dir / "service_out.log")
    log_err = str(paths.logs_dir / "service_err.log")

    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.piphotos.server</string>
    <key>ProgramArguments</key>
    <array>
        <string>{py}</string>
        <string>-m</string>
        <string>piphotos_server.cli</string>
        <string>run</string>
        <string>--data-dir</string>
        <string>{paths.data_dir}</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>{log_out}</string>
    <key>StandardErrorPath</key>
    <string>{log_err}</string>
    <key>WorkingDirectory</key>
    <string>{paths.data_dir}</string>
</dict>
</plist>
"""


def render_windows_task_xml(
    paths: ServerPaths,
    python_bin: Optional[str] = None,
    is_system: bool = False,
) -> str:
    """Render Windows Scheduled Task XML definition."""
    py = python_bin or sys.executable
    py_path = Path(py)
    pythonw = py_path.parent / "pythonw.exe"
    exe_cmd = str(pythonw if pythonw.is_file() else py_path)
    log_file = paths.logs_dir / "service_windows.log"

    args = f'/c ""{exe_cmd}" -m piphotos_server.cli run --data-dir "{paths.data_dir}" &gt;&gt; "{log_file}" 2&gt;&amp;1"'
    user_id = "S-1-5-18" if is_system else (os.environ.get("USERNAME") or "Users")
    logon_type = "ServiceAccount" if is_system else "InteractiveToken"
    run_level = "HighestAvailable" if is_system else "LeastPrivilege"

    trigger = """    <BootTrigger>
      <Enabled>true</Enabled>
    </BootTrigger>""" if is_system else """    <LogonTrigger>
      <Enabled>true</Enabled>
    </LogonTrigger>"""

    return f"""<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>PiPhotos Storage Node server auto-start</Description>
  </RegistrationInfo>
  <Triggers>
{trigger}
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>{user_id}</UserId>
      <LogonType>{logon_type}</LogonType>
      <RunLevel>{run_level}</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <RestartOnFailure>
      <Interval>PT1M</Interval>
      <Count>3</Count>
    </RestartOnFailure>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>cmd.exe</Command>
      <Arguments>{args}</Arguments>
      <WorkingDirectory>{paths.data_dir}</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"""


# ---------------------------------------------------------------------------
# Raspberry Pi awareness
# ---------------------------------------------------------------------------

def detect_raspberry_pi() -> Optional[Dict[str, Any]]:
    """Detect Raspberry Pi hardware, architecture, RAM, and Avahi configuration."""
    model_path = Path("/proc/device-tree/model")
    if not model_path.is_file():
        cpuinfo = Path("/proc/cpuinfo")
        if not cpuinfo.is_file():
            return None
        try:
            content = cpuinfo.read_text(encoding="utf-8", errors="ignore")
            if "Raspberry Pi" not in content and "BCM" not in content:
                return None
            model_name = "Raspberry Pi"
        except Exception:
            return None
    else:
        try:
            model_name = model_path.read_text(encoding="utf-8", errors="ignore").strip().rstrip("\x00")
        except Exception:
            model_name = "Raspberry Pi"

    # Architecture check
    machine = platform.machine()
    is_32bit = machine in ("armv7l", "armv6l", "i686", "x86")

    # RAM check
    ram_mb = 1024
    meminfo = Path("/proc/meminfo")
    if meminfo.is_file():
        try:
            for line in meminfo.read_text().splitlines():
                if line.startswith("MemTotal:"):
                    parts = line.split()
                    ram_mb = int(parts[1]) // 1024
                    break
        except Exception:
            pass

    is_pi_zero = "Zero" in model_name or ram_mb < 768

    # Avahi mDNS daemon check
    avahi_installed = shutil.which("avahi-daemon") is not None

    warnings = []
    if is_32bit:
        warnings.append(
            f"Detected 32-bit OS ({machine}). Binary wheels for cryptography may be missing; "
            "we strongly recommend using 64-bit Raspberry Pi OS."
        )
    if is_pi_zero:
        warnings.append(
            f"Detected low-RAM device ({model_name}, {ram_mb} MB RAM). "
            "Pi Zero resource limits will be enforced."
        )
    if not avahi_installed:
        warnings.append(
            "avahi-daemon is not installed. Run 'sudo apt install avahi-daemon' "
            "so phones can discover the server via hostname.local."
        )

    return {
        "model": model_name,
        "machine": machine,
        "is_32bit": is_32bit,
        "ram_mb": ram_mb,
        "is_pi_zero": is_pi_zero,
        "avahi_installed": avahi_installed,
        "warnings": warnings,
    }


# ---------------------------------------------------------------------------
# Firewall management
# ---------------------------------------------------------------------------

def configure_firewall(action: str = "add", port: int = 8443, mdns_port: int = 5353) -> tuple[bool, str]:
    """Open or close inbound firewall rules for PiPhotos server (TCP 8443 and UDP 5353).

    Action: 'add' or 'remove'
    """
    if sys.platform == "win32":
        rule_tcp = "PiPhotos Storage Node (TCP 8443)"
        rule_udp = "PiPhotos mDNS Discovery (UDP 5353)"
        if action == "add":
            cmd_tcp = ["netsh", "advfirewall", "firewall", "add", "rule", f"name={rule_tcp}", "dir=in", "action=allow", "protocol=TCP", f"localport={port}"]
            cmd_udp = ["netsh", "advfirewall", "firewall", "add", "rule", f"name={rule_udp}", "dir=in", "action=allow", "protocol=UDP", f"localport={mdns_port}"]
            res1 = subprocess.run(cmd_tcp, capture_output=True, text=True)
            res2 = subprocess.run(cmd_udp, capture_output=True, text=True)
            if res1.returncode == 0 and res2.returncode == 0:
                return True, "Windows Firewall rules added for TCP 8443 and UDP 5353."
            return False, f"Failed to add Windows Firewall rules: {res1.stderr or res2.stderr or res1.stdout or 'Access denied (run as Administrator)'}"
        else:
            cmd_tcp = ["netsh", "advfirewall", "firewall", "delete", "rule", f"name={rule_tcp}"]
            cmd_udp = ["netsh", "advfirewall", "firewall", "delete", "rule", f"name={rule_udp}"]
            subprocess.run(cmd_tcp, capture_output=True, text=True)
            subprocess.run(cmd_udp, capture_output=True, text=True)
            return True, "Windows Firewall rules removed."

    elif sys.platform == "linux":
        # Check ufw
        if shutil.which("ufw"):
            res = subprocess.run(["ufw", "status"], capture_output=True, text=True)
            if "Status: active" in res.stdout:
                if action == "add":
                    r1 = subprocess.run(["ufw", "allow", f"{port}/tcp", "comment", "PiPhotos Server"], capture_output=True, text=True)
                    r2 = subprocess.run(["ufw", "allow", f"{mdns_port}/udp", "comment", "PiPhotos mDNS"], capture_output=True, text=True)
                    if r1.returncode == 0 and r2.returncode == 0:
                        return True, f"ufw rules added for {port}/tcp and {mdns_port}/udp."
                    return False, f"Failed to add ufw rules: {r1.stderr or r2.stderr}"
                else:
                    subprocess.run(["ufw", "delete", "allow", f"{port}/tcp"], capture_output=True)
                    subprocess.run(["ufw", "delete", "allow", f"{mdns_port}/udp"], capture_output=True)
                    return True, "ufw rules removed."
        # Check firewalld
        if shutil.which("firewall-cmd"):
            res = subprocess.run(["firewall-cmd", "--state"], capture_output=True, text=True)
            if "running" in res.stdout:
                if action == "add":
                    r1 = subprocess.run(["firewall-cmd", "--permanent", f"--add-port={port}/tcp"], capture_output=True, text=True)
                    r2 = subprocess.run(["firewall-cmd", "--permanent", f"--add-port={mdns_port}/udp"], capture_output=True, text=True)
                    r3 = subprocess.run(["firewall-cmd", "--reload"], capture_output=True, text=True)
                    if r1.returncode == 0 and r2.returncode == 0 and r3.returncode == 0:
                        return True, f"firewalld rules added for {port}/tcp and {mdns_port}/udp."
                    return False, f"Failed to add firewalld rules: {r1.stderr or r2.stderr or r3.stderr}"
                else:
                    subprocess.run(["firewall-cmd", "--permanent", f"--remove-port={port}/tcp"], capture_output=True)
                    subprocess.run(["firewall-cmd", "--permanent", f"--remove-port={mdns_port}/udp"], capture_output=True)
                    subprocess.run(["firewall-cmd", "--reload"], capture_output=True)
                    return True, "firewalld rules removed."

        return True, "No active ufw or firewalld detected; firewall configuration skipped."

    elif sys.platform == "darwin":
        return True, "macOS Application Firewall will prompt on first incoming connection."

    return False, f"Unsupported platform: {sys.platform}"


# ---------------------------------------------------------------------------
# Service install, uninstall, start, stop, restart, logs
# ---------------------------------------------------------------------------

TASK_NAME = "PiPhotos Server"
SYSTEMD_NAME = "piphotos.service"
LAUNCHD_PLIST = "com.piphotos.server.plist"


def install_service(paths: ServerPaths, is_system: bool = False, no_firewall: bool = False) -> tuple[bool, str]:
    """Install auto-start background service per OS."""
    if is_system:
        if sys.platform == "win32" and not is_windows_elevated():
            return False, "System-level service on Windows requires Administrator privileges. Run terminal as Administrator."
        elif sys.platform == "linux" and not is_root_user():
            return False, "System-level service on Linux requires root privileges. Run with sudo or omit --system for user service."

    paths.ensure_dirs()

    # Firewall setup
    fw_msg = ""
    if not no_firewall:
        _, fw_msg = configure_firewall("add")

    if sys.platform == "win32":
        xml_content = render_windows_task_xml(paths, is_system=is_system)
        xml_path = paths.data_dir / "piphotos_task.xml"
        # Write UTF-16 XML as expected by schtasks
        xml_path.write_text(xml_content, encoding="utf-16")

        cmd = ["schtasks", "/Create", "/TN", TASK_NAME, "/XML", str(xml_path), "/F"]
        res = subprocess.run(cmd, capture_output=True, text=True)
        try:
            xml_path.unlink()
        except Exception:
            pass

        if res.returncode == 0:
            return True, f"Windows Scheduled Task '{TASK_NAME}' installed successfully. {fw_msg}".strip()
        return False, f"Failed to install Scheduled Task: {res.stderr or res.stdout}"

    elif sys.platform == "linux":
        pi_info = detect_raspberry_pi()
        is_pi_zero = pi_info.get("is_pi_zero", False) if pi_info else False
        if pi_info and pi_info.get("warnings"):
            for w in pi_info["warnings"]:
                print(f"[Raspberry Pi Warning] {w}", file=sys.stderr)

        unit_content = render_systemd_service(paths, is_system=is_system, is_pi_zero=is_pi_zero)

        if is_system:
            unit_path = Path("/etc/systemd/system") / SYSTEMD_NAME
            unit_path.write_text(unit_content, encoding="utf-8")
            subprocess.run(["systemctl", "daemon-reload"], check=False)
            subprocess.run(["systemctl", "enable", SYSTEMD_NAME], check=False)
            return True, f"Systemd system unit installed at {unit_path}. {fw_msg}".strip()
        else:
            unit_dir = Path.home() / ".config" / "systemd" / "user"
            unit_dir.mkdir(parents=True, exist_ok=True)
            unit_path = unit_dir / SYSTEMD_NAME
            unit_path.write_text(unit_content, encoding="utf-8")
            subprocess.run(["systemctl", "--user", "daemon-reload"], check=False)
            subprocess.run(["systemctl", "--user", "enable", SYSTEMD_NAME], check=False)
            # Enable lingering so user services run without active login session
            subprocess.run(["loginctl", "enable-linger"], check=False)
            return True, f"Systemd user unit installed at {unit_path} (linger enabled). {fw_msg}".strip()

    elif sys.platform == "darwin":
        plist_content = render_launchd_plist(paths)
        agents_dir = Path.home() / "Library" / "LaunchAgents"
        agents_dir.mkdir(parents=True, exist_ok=True)
        plist_path = agents_dir / LAUNCHD_PLIST
        plist_path.write_text(plist_content, encoding="utf-8")
        subprocess.run(["launchctl", "load", "-w", str(plist_path)], check=False)
        return True, f"macOS LaunchAgent installed at {plist_path}."

    return False, f"Unsupported platform: {sys.platform}"


def uninstall_service(paths: ServerPaths, is_system: bool = False, no_firewall: bool = False) -> tuple[bool, str]:
    """Uninstall auto-start service and remove firewall rules."""
    if is_system:
        if sys.platform == "win32" and not is_windows_elevated():
            return False, "Uninstalling system-level service on Windows requires Administrator privileges."
        elif sys.platform == "linux" and not is_root_user():
            return False, "Uninstalling system-level service on Linux requires root privileges."

    stop_service(is_system=is_system)

    if not no_firewall:
        configure_firewall("remove")

    if sys.platform == "win32":
        cmd = ["schtasks", "/Delete", "/TN", TASK_NAME, "/F"]
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode == 0:
            return True, f"Windows Scheduled Task '{TASK_NAME}' removed."
        return False, f"Failed to delete Scheduled Task: {res.stderr or res.stdout}"

    elif sys.platform == "linux":
        if is_system:
            subprocess.run(["systemctl", "disable", SYSTEMD_NAME], check=False)
            unit_path = Path("/etc/systemd/system") / SYSTEMD_NAME
            if unit_path.exists():
                unit_path.unlink()
            subprocess.run(["systemctl", "daemon-reload"], check=False)
            return True, "Systemd system unit removed."
        else:
            subprocess.run(["systemctl", "--user", "disable", SYSTEMD_NAME], check=False)
            unit_path = Path.home() / ".config" / "systemd" / "user" / SYSTEMD_NAME
            if unit_path.exists():
                unit_path.unlink()
            subprocess.run(["systemctl", "--user", "daemon-reload"], check=False)
            return True, "Systemd user unit removed."

    elif sys.platform == "darwin":
        plist_path = Path.home() / "Library" / "LaunchAgents" / LAUNCHD_PLIST
        if plist_path.exists():
            subprocess.run(["launchctl", "unload", "-w", str(plist_path)], check=False)
            plist_path.unlink()
            return True, "macOS LaunchAgent removed."

    return False, f"Unsupported platform: {sys.platform}"


def start_service(is_system: bool = False) -> tuple[bool, str]:
    """Start the registered background service."""
    if sys.platform == "win32":
        res = subprocess.run(["schtasks", "/Run", "/TN", TASK_NAME], capture_output=True, text=True)
        if res.returncode == 0:
            return True, f"Scheduled task '{TASK_NAME}' triggered."
        return False, f"Failed to start task: {res.stderr or res.stdout}"
    elif sys.platform == "linux":
        cmd = ["systemctl", "start", SYSTEMD_NAME] if is_system else ["systemctl", "--user", "start", SYSTEMD_NAME]
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode == 0:
            return True, "Systemd service started."
        return False, f"Failed to start service: {res.stderr}"
    elif sys.platform == "darwin":
        subprocess.run(["launchctl", "start", "com.piphotos.server"], check=False)
        return True, "LaunchAgent started."
    return False, "Unsupported platform"


def stop_service(is_system: bool = False) -> tuple[bool, str]:
    """Stop the registered background service."""
    if sys.platform == "win32":
        res = subprocess.run(["schtasks", "/End", "/TN", TASK_NAME], capture_output=True, text=True)
        return True, "Scheduled task ended."
    elif sys.platform == "linux":
        cmd = ["systemctl", "stop", SYSTEMD_NAME] if is_system else ["systemctl", "--user", "stop", SYSTEMD_NAME]
        subprocess.run(cmd, capture_output=True, check=False)
        return True, "Systemd service stopped."
    elif sys.platform == "darwin":
        subprocess.run(["launchctl", "stop", "com.piphotos.server"], check=False)
        return True, "LaunchAgent stopped."
    return False, "Unsupported platform"


def restart_service(is_system: bool = False) -> tuple[bool, str]:
    """Restart the registered background service."""
    stop_service(is_system=is_system)
    return start_service(is_system=is_system)


def stream_logs(paths: ServerPaths, follow: bool = False, lines: int = 50) -> None:
    """Print or follow server logs."""
    log_files = list(paths.logs_dir.glob("*.log"))
    if not log_files:
        print(f"No log files found in {paths.logs_dir}.")
        print("Logs are written to the logs directory or system journal.")
        if sys.platform == "linux":
            print(f"To inspect systemd logs, run: journalctl --user -u {SYSTEMD_NAME} -n {lines}")
        return

    # Pick the most recently modified log file
    log_file = max(log_files, key=lambda f: f.stat().st_mtime)
    print(f"Viewing: {log_file} (last {lines} lines)\n")

    try:
        with open(log_file, "r", encoding="utf-8", errors="ignore") as f:
            all_lines = f.readlines()
            for line in all_lines[-lines:]:
                sys.stdout.write(line)
            sys.stdout.flush()

            if not follow:
                return

            while True:
                line = f.readline()
                if line:
                    sys.stdout.write(line)
                    sys.stdout.flush()
                else:
                    time.sleep(0.5)
    except KeyboardInterrupt:
        return
    except Exception as e:
        print(f"Error reading log file: {e}")
