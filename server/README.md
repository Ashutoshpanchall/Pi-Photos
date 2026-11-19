# PiPhotos Storage Node

A lightweight, privacy-first storage node for backing up photos and videos from the PiPhotos Android app. Designed to run on a Raspberry Pi Zero 2 W (512 MB RAM) or any Linux/Windows/macOS system.

## Quick Start

### On Linux / macOS
```bash
cd server
chmod +x run.sh
./run.sh
```

### On Windows
```powershell
cd server
.\run.ps1
```

### On Raspberry Pi
```bash
# Install as a system service
sudo cp piphotos.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable piphotos
sudo systemctl start piphotos

# Check status
sudo systemctl status piphotos
```

## Configuration

Configuration can be set via:
1. `config.json` in the server directory
2. Environment variables (take precedence)

| Setting | Env Variable | Default |
|---------|-------------|---------|
| Server name | `PIPHOTOS_NAME` | Home Vault |
| Port | `PIPHOTOS_PORT` | 8443 |
| Storage path | `PIPHOTOS_STORAGE` | ./data/storage |
| Database path | `PIPHOTOS_DB` | ./data/piphotos.db |
| Certificate dir | `PIPHOTOS_CERTS` | ./data/certs |
| Default quota | `PIPHOTOS_DEFAULT_QUOTA` | 107374182400 (100 GB) |

## Pairing

On startup, the server:
1. Generates a self-signed TLS certificate (first run only)
2. Prints a 6-digit pairing code (expires after 10 minutes)
3. Displays a QR code for easy connection
4. Advertises itself via mDNS (`_piphotos._tcp`)

Open the PiPhotos app → Backup → Connect, and either scan the QR code or enter the server address and pairing code manually.

## Storage Layout

Files are stored in a human-browsable structure:
```
data/storage/users/<user>/devices/<device>/<folder>/<filename>
```

No proprietary formats — you can browse and copy files directly.

## Running Tests

```bash
cd server
pip install pytest
pytest tests/ -v
```

## Protocol

See [docs/PROTOCOL.md](../docs/PROTOCOL.md) for the full API specification.
