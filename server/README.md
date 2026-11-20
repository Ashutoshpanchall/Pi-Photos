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

## Multi-User Administration

The server supports multiple users, each with isolated storage and their own quota. The first user is always the **owner**; subsequent users are **members**.

### Adding a New User

As owner, call:
```
POST /api/v1/admin/users
{ "display_name": "Alice", "quota_bytes": 107374182400 }
```

The response includes a 6-digit **invite code** (valid 24 h) and a `piphotos://pair?…` QR payload. Share the invite code or QR with the new user; they pair exactly like the owner does on initial setup.

### User Management via API

| Action | Endpoint |
|--------|----------|
| List users + usage | `GET /api/v1/admin/users` |
| Create member | `POST /api/v1/admin/users` |
| Rename / set quota / enable / disable | `PATCH /api/v1/admin/users/{id}` |
| Re-issue invite | `POST /api/v1/admin/users/{id}/invite` |
| Delete user | `DELETE /api/v1/admin/users/{id}?delete_files=true\|false` |
| List user devices | `GET /api/v1/admin/users/{id}/devices` |
| Revoke device | `DELETE /api/v1/admin/devices/{device_id}` |
| Re-open pairing | `POST /api/v1/admin/pairing/open` |
| Disk + user overview | `GET /api/v1/admin/overview` |

### Owner Protection Rules

- There is always at least one active owner.
- The last active owner cannot be disabled, deleted, or demoted.
- These checks return `400 {"error": "last_owner_protected"}`.

### Quota Rules

- `quota_bytes = 0` means unlimited (still bound by 10% disk reserve).
- Soft warnings at 80% and 90% usage appear in `GET /api/v1/status`.
- Hard quota enforced at 100% — upload returns `507 quota_exceeded`.
- Cannot set quota below current usage without `?force=true`.

### User Data Isolation

- Each user's files live in `<storage_root>/users/<slug>/`.
- Deduplication is **per-user** — the same file from two users is stored twice.
- Cross-user token swap or path traversal is rejected with 403/400.

### Deleted User Files

- `delete_files=false` (default): files archived to `<storage_root>/_removed/<slug>-<timestamp>/`
- `delete_files=true`: files permanently deleted (irreversible)

## Storage Layout

Files are stored in a human-browsable structure:
```
data/storage/
  users/
    <user-slug>/
      devices/
        <device-slug>/
          Camera/
            IMG_20260101_120000.jpg
          Screenshots/
            Screenshot_20260101_120000.png
  _removed/              ← archived deleted-user directories
  parts/                 ← partial uploads (auto-cleaned on completion)
```

No proprietary formats — you can browse and copy files directly.

## Running Tests

```bash
cd server
# Using the managed venv:
./run.sh test   # or on Windows: .\run.ps1 test

# Manually:
python -m pytest tests/ -v
```

## Protocol

See [docs/PROTOCOL.md](../docs/PROTOCOL.md) for the full API specification.
