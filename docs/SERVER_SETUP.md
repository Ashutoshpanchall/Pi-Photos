# PiPhotos Storage Node — One-Command Server Setup Guide

PiPhotos includes a privacy-first, self-hosted storage node that pairs seamlessly with the PiPhotos Android gallery app. It provides automatic encrypted backup, deduplication, multi-user accounts, and zero-configuration pairing.

---

## 1. Quick Start: One-Command Setup

Run the single command for your platform in a terminal or PowerShell prompt. No Python knowledge, manual dependency compilation, or database configuration is required.

### macOS, Linux & Raspberry Pi
```bash
curl -fsSL https://raw.githubusercontent.com/Ashutoshpanchall/Pi-Photos/main/install/install.sh | sh
```

### Windows (PowerShell)
```powershell
irm https://raw.githubusercontent.com/Ashutoshpanchall/Pi-Photos/main/install/install.ps1 | iex
```

Both installers:
- Print a summary plan before taking any action.
- Are **100% idempotent** (re-running updates the server and safely preserves your database and existing photos).
- Set up auto-start background services and firewall rules by default.
- Print your LAN URLs, a 10-minute pairing code, and an ASCII QR code ready to scan in the PiPhotos mobile app.

---

## 2. Verifying Script Integrity Before Execution

If you prefer to verify the installation script before executing it:

### Linux / macOS
```bash
# 1. Download installer script
curl -fsSL https://raw.githubusercontent.com/Ashutoshpanchall/Pi-Photos/main/install/install.sh -o install.sh

# 2. Inspect the file and check SHA-256 checksum
sha256sum install.sh    # On macOS: shasum -a 256 install.sh

# 3. Run the verified script
sh install.sh
```

### Windows
```powershell
# 1. Download installer script
irm https://raw.githubusercontent.com/Ashutoshpanchall/Pi-Photos/main/install/install.ps1 -OutFile install.ps1

# 2. Check SHA-256 hash
Get-FileHash .\install.ps1 -Algorithm SHA256

# 3. Run the verified script
.\install.ps1
```

---

## 3. What the Installer Does

1. **System & Architecture Detection**: Identifies whether the machine is Windows, macOS, Linux, or a Raspberry Pi (checking CPU architecture and available RAM).
2. **Astral `uv` Installation**: Reuses or installs [Astral uv](https://github.com/astral-sh/uv) (a fast, Rust-based Python package manager) at the user level without requiring root/Administrator permissions.
3. **Server Tool Installation**: Installs `piphotos-server` in an isolated virtual environment via `uv tool install`.
4. **Data & Storage Initialization**:
   - Creates the OS standard data directory:
     - **Windows**: `%PROGRAMDATA%\PiPhotos` (when elevated) or `%LOCALAPPDATA%\PiPhotos` (standard user)
     - **macOS**: `~/Library/Application Support/PiPhotos`
     - **Linux / Raspberry Pi**: `~/.local/share/piphotos` (user) or `/var/lib/piphotos` (system)
   - Generates persistent TLS certificates (`cert.pem`, `key.pem` mode `0600`). Certificates are **never regenerated on update** so mobile devices never lose trust.
   - Initializes SQLite database with WAL journaling.
   - Writes a secure local-admin credential token (`local-admin.token`, readable only by the owner).
5. **Auto-Start Service & Firewall Setup**:
   - **Linux / Raspberry Pi**: Configures `systemd` user service (`~/.config/systemd/user/piphotos.service`) with `loginctl enable-linger` so the server persists after logout and reboots. Allows TCP 8443 and UDP 5353 through `ufw` or `firewalld` if active.
   - **macOS**: Configures `launchd` LaunchAgent (`~/Library/LaunchAgents/com.piphotos.server.plist`).
   - **Windows**: Registers a Windows Scheduled Task (`PiPhotos Server`) and creates inbound firewall rules for TCP 8443 and UDP 5353.
6. **Loopback Self-Check & Pairing Window**: Verifies that `https://127.0.0.1:8443/api/v1/info` is responding and opens an initial 10-minute pairing window with an interactive QR code.

---

## 4. Platform-Specific Notes

### Windows
- **Firewall & Task Scheduler Prompt**: When running in standard user mode, the installer offers to elevate to configure Windows Firewall rules and the scheduled task. If you decline, user-mode setup proceeds, and the installer displays the exact manual `netsh` firewall commands.
- **Sleep & Standby**: Sleeping computers drop off the network. To ensure 24/7 backup availability from your phone, set *"Put the computer to sleep"* to **"Never"** in Windows Power & Battery settings while connected to AC power, or run:
  ```powershell
  powercfg /change standby-timeout-ac 0
  ```

### macOS
- **Incoming Connections Prompt**: macOS Application Firewall will show a one-time prompt: *"Do you want the application to accept incoming network connections?"*. Click **Allow**.
- **Energy Saver**: Ensure *"Prevent computer from sleeping automatically when the display is off"* is enabled in System Settings, or disable sleep while on AC power via:
  ```bash
  sudo pmset -c sleep 0
  ```

### Raspberry Pi
- **64-bit OS Recommended**: We strongly recommend using **64-bit Raspberry Pi OS** (`aarch64`). 32-bit OS (`armv7l`) may lack precompiled wheels for cryptographic dependencies.
- **Pi Zero 2 W Memory Tuning**: On devices with $\le 512\text{ MB}$ RAM, the service renderer automatically applies memory constraints (`MemoryMax=200M`) and restricts uvicorn to a single worker.
- **mDNS Hostname Lookup**: Ensure `avahi-daemon` is installed so phones can locate `hostname.local`:
  ```bash
  sudo apt install -y avahi-daemon
  ```

### Storage Drives & Filesystem Deduplication
- PiPhotos uses streaming Content-Addressable Storage (CAS) with hard links for deduplication.
- For optimal storage efficiency, format your backup drive with **NTFS** (Windows), **ext4** (Linux), or **APFS** (macOS).
- Filesystems like **FAT32** and **exFAT** do not support hard links; PiPhotos will safely copy duplicate files rather than link them, which uses additional disk space.

---

## 5. Pairing Your Android Phone

1. Open the **PiPhotos** gallery app on your Android device.
2. Tap the Cloud icon on the top app bar or navigate to **Backup > Set up Storage Node**.
3. Point your camera at the QR code displayed in the server terminal, or enter your server's LAN IP and the 6-digit code.
4. The app pins the server's TLS fingerprint for end-to-end authentication.

### Re-opening a Pairing Window Anytime
You never need to restart the server to pair a new device or user. Simply run:
```bash
piphotos-server pair
```
This contacts the running server over loopback using `local-admin.token` and displays a fresh 10-minute pairing code and QR code.

---

## 6. CLI Command Reference

The `piphotos-server` CLI provides complete management of your node:

| Command | Description |
|---------|-------------|
| `piphotos-server setup` | Create or update data directories, certificates, and config (idempotent) |
| `piphotos-server run` | Run server in foreground (used by services and for debugging) |
| `piphotos-server pair` | Open 10-minute pairing window and display code + QR |
| `piphotos-server status` | Show server state, LAN URLs, user count, and free disk space |
| `piphotos-server info` | Re-display server connection banner, TLS fingerprint, and QR code |
| `piphotos-server doctor [--json]` | Run 12-point health, network, TLS, and service diagnostics |
| `piphotos-server logs [-f] [-n 50]` | View or follow rotating server logs (`<data>/logs/server.log`) |
| `piphotos-server update [--ref main]` | Update server package to latest release and restart service |
| `piphotos-server uninstall [--purge-data]` | Stop and remove service, firewall rules, and CLI tool |
| `piphotos-server start` / `stop` / `restart` | Control background service |

### Command Options
- `--data-dir <path>`: Override base configuration and database directory
- `--storage-dir <path>`: Store photos on an external drive or secondary volume
- `--port <port>`: Change listening port (default: 8443)
- `--name <name>`: Custom server display name in mobile app

---

## 7. Diagnostics & Troubleshooting (`doctor`)

If you encounter connection issues or are moving the server to a new network, run the built-in diagnostic tool:

```bash
piphotos-server doctor
```

Sample output:
```text
=================================================================
              PiPhotos Server Doctor Diagnostics              
=================================================================
  [PASS]  python_version: Python version 3.12.2 satisfies requirement (>= 3.10)
  [PASS]  data_directory: Data directory is writable: /home/pi/.local/share/piphotos
  [PASS]  disk_space: Disk space adequate: 450.2 GB free
  [PASS]  hard_links: Filesystem supports hard links (Content-Addressable deduplication enabled)
  [PASS]  tls_certificate: TLS certificate valid (SHA-256 fingerprint: 46:4C:67:C8:04:C...)
  [PASS]  network_interfaces: Detected active LAN interfaces: 192.168.1.120
  [PASS]  port_binding: Port 8443 is active and serving PiPhotos server
  [PASS]  loopback_endpoint: Loopback endpoint https://127.0.0.1:8443/api/v1/info responded 200 OK
  [PASS]  lan_endpoint: LAN endpoint https://192.168.1.120:8443/api/v1/info responded 200 OK
  [PASS]  firewall: ufw active and allows port 8443/tcp
  [PASS]  service: Systemd user service 'piphotos.service' is active
  [PASS]  mdns_discovery: mDNS library operational (discovery active when server is running)
  [PASS]  system_clock: System clock is sane and synchronized
  [PASS]  power_sleep: System power management checked
=================================================================
  Summary: 14 PASS, 0 WARN, 0 FAIL
=================================================================
```

### Common Fixes

| Symptom | Cause | Solution |
|---------|-------|----------|
| `[WARN] lan_endpoint failed (AP Isolation)` | Router blocks device-to-device communication | Disable "AP Isolation" / "Client Isolation" in Wi-Fi router settings |
| `[WARN] Windows Firewall rule not found` | Service installed without admin rights | Run `piphotos-server install-service` in an Administrator terminal |
| `[WARN] Low disk space (< 1 GB)` | Storage drive full | Free up space or move storage to external drive via `piphotos-server setup --storage-dir <path>` |
| `[WARN] Hard links unsupported` | Backup drive formatted as FAT/exFAT | Reformat drive as NTFS, ext4, or APFS for deduplication support |

---

## 8. Safe Uninstallation

To remove the background service, firewall rules, and the CLI tool while **preserving your photos and database**:
```bash
piphotos-server uninstall
```

To permanently delete everything (service, tool, database, and all uploaded photos):
```bash
piphotos-server uninstall --purge-data
```
*(Requires interactive confirmation by typing the data directory folder name to prevent accidental deletion.)*
