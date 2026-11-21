# PiPhotos Protocol — v1

## Overview

PiPhotos uses a simple REST API over TLS (self-signed, trust-on-first-use) for
local-network photo and video backup from Android devices to a personal storage
node. The protocol is designed for LAN use and low-resource devices (Raspberry
Pi Zero 2 W with 512 MB RAM).

## Discovery

### mDNS

The server advertises itself asynchronously via mDNS (`AsyncZeroconf`) as
`_piphotos._tcp.local.` with the machine's actual IPv4 addresses in `addresses`
and TXT records:

| Key      | Value                                      |
|----------|--------------------------------------------|
| `v`      | Protocol version, e.g. `1`                 |
| `id`     | Server instance UUID                       |
| `name`   | Human-readable server name                 |
| `tls_fp` | SHA-256 fingerprint of the TLS certificate |

### QR Code

The server prints a QR code on startup containing a JSON payload:

```json
{
  "host": "192.168.1.42",
  "port": 8443,
  "tls_fp": "AB:CD:...",
  "code": "123456"
}
```

## TLS & Certificate Pinning

The server generates a self-signed EC (P-256) certificate on first run:
- Subject Alternative Name (SAN) contains the server's LAN IP addresses,
  system hostname, `piphotos.local`, `localhost`, and `127.0.0.1`.
- The client pins the SHA-256 fingerprint of the certificate upon initial
  pairing (Trust-On-First-Use — TOFU).
- Because local network addresses vary (DHCP, mDNS `.local`), the Android client
  skips standard CA and hostname verification in favor of strict SHA-256
  fingerprint pinning.
- Server private keys (`key.pem`) are stored with mode `0600` and certificates
  directory with mode `0700`.

## Authentication

All endpoints except `GET /api/v1/info` and `POST /api/v1/pair` require a Bearer
token in the `Authorization` header:

```
Authorization: Bearer <token>
```

Tokens are issued during pairing and are per-device. They are stored hashed
(SHA-256) on the server.

## Endpoints

### GET /api/v1/info

**Auth:** None

Returns server identity and pairing availability.

**Response 200:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Home Vault",
  "version": "1.0.0",
  "protocol_version": 1,
  "pairing_open": true
}
```

### POST /api/v1/pair

**Auth:** None (protected by 6-digit code and IP rate-limiting)

Pairs a device with the server. Accepts **two types of codes**:

1. **Owner pairing code** — 6 digits, printed in the server console on startup (or via `POST /api/v1/admin/pairing/open`), pairs the device to the owner account (expires in 10 minutes).
2. **Member invite code** — 8 uppercase alphanumeric characters from an unambiguous alphabet (`23456789ABCDEFGHJKLMNPQRSTUVWXYZ`, excluding `0`, `O`, `1`, `I`), issued by the owner via `POST /api/v1/admin/users` or `POST /api/v1/admin/users/{id}/invite`. Case-insensitive, single-use, 24-hour expiry.

**Rules:**
- 6-digit owner code expires after 10 minutes; 8-character member invite code expires after 24 hours.
- Rate-limited per client IP (HTTP 429 if exceeded).
- Global failed-attempt limiter: 20 failed pairing attempts across all IPs within a 10-minute sliding window temporarily locks all pairing globally for 10 minutes (HTTP 429 `pairing_locked`).
- Invalidation after 5 failed code attempts on the active owner code (HTTP 401 `code_invalidated`).
- Device slug uniqueness: if a device with the same slug already exists for the user, `-2`, `-3`, etc., is automatically appended.
- Owner pairing closes immediately upon 1 successful pairing. Opens only upon explicit owner action (`POST /api/v1/admin/pairing/open`).

**Request:**
```json
{
  "code": "A3K9X7YW",
  "device_name": "Moto Edge 50 Neo",
  "device_model": "motorola edge 50 neo"
}
```

**Response 200:**
```json
{
  "token": "eyJ...",
  "user_id": "usr_abc123",
  "device_id": "dev_xyz789"
}
```

**Error 401 `invalid_code`:** Invalid code or expired.

**Error 401 `invite_expired`:** Invite code is past its expiry time.

**Error 401 `invite_used`:** Invite code has already been redeemed.

**Error 401 `code_invalidated`:** Code invalidated due to 5 failed attempts.

**Error 403 `user_disabled`:** The user linked to this invite is disabled.

**Error 409:** Pairing is closed.

**Error 429 `rate_limited`:** Too many pairing attempts from this IP.

**Error 429 `pairing_locked`:** Pairing is temporarily locked across all IPs due to excessive failed attempts.

### GET /api/v1/status

**Auth:** Required

Returns disk and quota information for the authenticated user.

**Response 200:**
```json
{
  "disk": {
    "total_bytes": 500107862016,
    "free_bytes": 234881024000,
    "reserved_bytes": 50010786201
  },
  "user": {
    "user_id": "usr_abc123",
    "quota_bytes": 107374182400,
    "used_bytes": 53687091200,
    "free_bytes": 53687091200,
    "file_count": 1204,
    "soft_quota_warning": null,
    "organise_by_date": false
  }
}
```

`soft_quota_warning` is `"80%"` or `"90%"` when the user approaches their quota,
otherwise `null`. 10% of the disk is kept reserved.

### POST /api/v1/upload/check

**Auth:** Required

Batch check for existing files and resumable partial upload offsets. This allows
the client to query status for hundreds of files in a single request instead of
hundreds of `HEAD` requests.

**Request:**
```json
{
  "hashes": [
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"
  ]
}
```

**Response 200:**
```json
{
  "existing": [
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  ],
  "partial": {
    "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb": 1048576
  }
}
```

### HEAD /api/v1/upload/{sha256}

**Auth:** Required

Single-file check whether the authenticated user already has a file with the
given SHA-256 hash.

**Response 200:** File exists (already backed up). No body.

**Response 404:** File not found. If a partial upload exists, the response
includes:

```
Upload-Offset: 1048576
```

indicating how many bytes have been received so far. The client may resume from
this offset.

### PUT /api/v1/upload/{sha256}

**Auth:** Required

Uploads a file. The request body is the raw file bytes, streamed.

**Query parameters:**

| Param    | Required | Description                          |
|----------|----------|--------------------------------------|
| `device` | Yes      | Device ID from pairing               |
| `folder` | Yes      | Folder bucket name (e.g. "Camera")   |
| `name`   | Yes      | Original filename                    |
| `taken`  | No       | Unix epoch seconds when photo was taken |

**Headers:**

| Header           | Required | Description                              |
|------------------|----------|------------------------------------------|
| `Content-Length`  | Yes      | Total file size in bytes                 |
| `Upload-Offset`  | No       | Byte offset for resume (default 0)       |

**Resume & Integrity Rules:**
- If `Upload-Offset` != server partial file size, replies **409 Conflict** with
  `{"error": "offset_mismatch", "message": "...", "offset": <actual>}` and writes
  nothing.
- Hashing and file writes are executed off the event loop.
- Dynamic quota and disk reserve enforcement halts upload and returns **507** if
  exceeded mid-stream.
- Concurrent uploads of the same `(user, sha256)` are rejected with **409**
  `{"error": "upload_in_progress"}`.
- If the content (same SHA-256) already exists for the user, a duplicate reference
  is recorded in the SQLite database under the new device/folder/name without
  writing a second file to disk.
- If `taken` is provided, the stored file's `mtime` is set to `taken`.

**Response 201 / 200:** Upload complete.
```json
{
  "sha256": "abcdef...",
  "size": 4194304,
  "path": "Camera/IMG_20260101_120000.jpg"
}
```

**Error 409:** SHA-256 mismatch, offset mismatch, or upload in progress.

**Error 507:** `quota_exceeded` means the user's own quota is used up (the owner can raise it). `disk_full` means the node's disk is full (the owner must free space). Clients must tell the two apart.

### GET /api/v1/devices/{device_id}/folders

**Auth:** Required

Returns folder information for a specific device.

**Response 200:**
```json
{
  "device_id": "dev_xyz789",
  "device_name": "Moto Edge 50 Neo",
  "folders": [
    {
      "name": "Camera",
      "item_count": 842,
      "total_bytes": 4294967296
    },
    {
      "name": "Screenshots",
      "item_count": 156,
      "total_bytes": 268435456
    }
  ]
}
```

## Folder Layout on Server

Files are stored in a human-browsable directory structure:

```
<storage_root>/
  users/
    <userId-slug>/
      devices/
        <deviceName-slug>/
          Camera/
            IMG_20260101_120000.jpg
            IMG_20260101_120001.jpg
          Screenshots/
            Screenshot_20260101_120000.png
```

### Path Sanitisation

Every path component is sanitised:
- Reject `..`, absolute paths, and control characters (U+0000–U+001F, U+007F)
- Strip characters illegal on Windows: `< > : " / \ | ? *`
- Normalise Unicode to NFC
- Cap each component at 200 characters
- Slugify user and device names: lowercase, replace spaces with `-`, strip
  non-alphanumeric except `-` and `_`

### Name Collisions

- Same SHA-256 → stored once, recorded as a duplicate reference
- Different SHA-256, same name → append ` (1)`, ` (2)`, etc.

### Date Organisation (Optional)

Per-user setting `organise_by_date` (default `false`). When enabled:

```
Camera/2026/01/IMG_20260101_120000.jpg
```

File modification time (`mtime`) is set to `date_taken` when provided.

## Error Codes

| HTTP | Error Key              | Meaning                                    |
|------|------------------------|--------------------------------------------|
| 400  | `bad_request`          | Invalid request parameters                 |
| 401  | `unauthorized`         | Missing or invalid auth token              |
| 401  | `invalid_code`         | Invalid or expired pairing code            |
| 401  | `code_invalidated`     | 5 failed attempts; code invalidated        |
| 403  | `forbidden`            | Insufficient permissions                   |
| 404  | `not_found`            | Resource not found                         |
| 409  | `hash_mismatch`        | Uploaded data SHA-256 does not match URL   |
| 409  | `offset_mismatch`      | Upload-Offset does not match part file     |
| 409  | `upload_in_progress`   | Upload for this hash already in progress   |
| 409  | `pairing_closed`       | Pairing is currently disabled              |
| 429  | `rate_limited`         | Too many requests from this IP             |
| 429  | `pairing_locked`       | Global lockout across all IPs (20 failures)|
| 408  | `timeout`              | Upload stream chunk read timeout (30s)     |
| 507  | `quota_exceeded`       | The user's storage quota is exceeded       |
| 507  | `disk_full`            | The node's disk reserve would be breached  |
| 400  | `bad_request`          | Invalid folder or file name                |
| 500  | `internal_error`       | Unexpected server error                    |

| 401  | `invite_expired`       | Invite code is past its expiry time         |
| 401  | `invite_used`          | Invite code has already been redeemed       |
| 403  | `user_disabled`        | User account has been disabled              |
| 400  | `quota_below_usage`    | Cannot set quota below current usage        |
| 400  | `last_owner_protected` | Cannot disable, delete, or demote last owner|

---

## User Context & File Endpoints (Any Authenticated User)

### GET /api/v1/me

**Auth:** Required

Returns the caller's profile and current device info. Used by the mobile app to cache role and quota limits.

**Response 200:**
```json
{
  "user_id": "usr_abc123",
  "display_name": "Alice",
  "role": "owner",
  "quota_bytes": 107374182400,
  "used_bytes": 15420000000,
  "device_id": "dev_xyz789",
  "device_name": "Alice Pixel 7"
}
```

### GET /api/v1/files

**Auth:** Required

List files uploaded by the authenticated user, optionally filtered by device or folder bucket.

**Query Parameters:**
- `device_id` (optional): Filter by device ID
- `folder` (optional): Filter by folder name (e.g. `Camera`)

**Response 200:**
```json
[
  {
    "id": 1,
    "user_id": "usr_abc123",
    "device_id": "dev_xyz789",
    "folder": "Camera",
    "relative_path": "IMG_20261120.jpg",
    "sha256": "3a7b...",
    "size": 3145728,
    "date_taken": 1732089600,
    "uploaded_at": "2026-11-20T08:00:00+00:00"
  }
]
```

### GET /api/v1/files/{file_id}/content

**Auth:** Required

Stream the file content for `file_id`. Supports HTTP `Range` requests (for partial downloads and media seeking). Scoped strictly to the calling user — querying another user's `file_id` returns 404.

**Response 200 / 206 Partial Content:** Binary stream with `Accept-Ranges: bytes` and `Content-Length`.

---

## Admin Endpoints (Owner Only) — Phase U1

All endpoints under `/api/v1/admin/*` require owner-role authentication.
A non-owner token receives `403 {"error": "forbidden"}`.

### GET /api/v1/admin/users

List all users with usage statistics.

**Response 200:**
```json
[
  {
    "id": "usr_abc123",
    "name": "Owner",
    "display_name": "Owner",
    "slug": "owner",
    "role": "owner",
    "status": "active",
    "quota_bytes": 0,
    "used_bytes": 10737418240,
    "file_count": 842,
    "devices_count": 2,
    "last_seen_at": "2026-11-20T07:00:00+00:00"
  }
]
```

`quota_bytes` of `0` means unlimited (bound only by disk reserve).

### POST /api/v1/admin/users

Create a new member and issue a one-time invite code.

**Request:**
```json
{
  "display_name": "Alice",
  "quota_bytes": 107374182400,
  "unlimited": false
}
```
If `quota_bytes` is omitted, it defaults to the server's configured default quota (100 GB). Passing `unlimited: true` explicitly configures unlimited quota (`0`).

**Response 200:**
```json
{
  "user": { "id": "usr_xyz", "display_name": "Alice", "role": "member", "status": "active" },
  "invite_code": "K3N8Y7WD",
  "expires_at": "2026-11-21T07:00:00+00:00",
  "qr_payload": "piphotos://pair?host=192.168.1.42&port=8443&tls_fp=AB:CD:...&code=K3N8Y7WD"
}
```

The invite code is 8 characters from an unambiguous alphabet (`23456789ABCDEFGHJKLMNPQRSTUVWXYZ`), expires in 24 hours, stored hashed, and is single-use.

### PATCH /api/v1/admin/users/{id}

Update a user's display name, quota, status, or role.

**Query:** `?force=true` — override quota below usage protection.

**Request (all fields optional):**
```json
{
  "display_name": "Alice Smith",
  "quota_bytes": 53687091200,
  "status": "disabled",
  "role": "owner"
}
```

**Rules:**
- Cannot disable, delete, or demote the last active owner.
- Cannot set `quota_bytes` below current `used_bytes` without `?force=true`.

### POST /api/v1/admin/users/{id}/invite

Re-issue an invite code for an existing member (invalidates previous unused invites).

**Response 200:**
```json
{
  "invite_code": "391827",
  "expires_at": "2026-11-21T07:00:00+00:00",
  "qr_payload": "piphotos://pair?host=192.168.1.42&port=8443&tls_fp=AB:CD:...&code=391827"
}
```

### DELETE /api/v1/admin/users/{id}

Delete a user. The last active owner cannot be deleted.

**Query:** `?delete_files=true` — permanently delete `users/<slug>/` from disk.
Without this flag (default `false`), files are archived to `<storage_root>/_removed/<slug>-<timestamp>/`.

**Response 200:**
```json
{ "status": "deleted", "user_id": "usr_xyz", "files_deleted": false }
```

### GET /api/v1/admin/users/{id}/devices

List all devices paired to a user.

**Response 200:**
```json
[
  {
    "id": "dev_abc",
    "name": "Pixel 9",
    "model": "google pixel 9",
    "slug": "pixel-9",
    "created_at": "2026-11-01T10:00:00+00:00",
    "last_seen_at": "2026-11-20T07:00:00+00:00"
  }
]
```

### DELETE /api/v1/admin/devices/{device_id}

Revoke a device token. The device immediately gets `401` on its next request.

### POST /api/v1/admin/pairing/open

Open a new owner pairing window without restarting the server. Generates a fresh 6-digit code valid for 10 minutes.

**Response 200:**
```json
{ "code": "183729", "expires_in_seconds": 600 }
```

### GET /api/v1/admin/overview

Disk and per-user quota summary.

**Response 200:**
```json
{
  "disk": {
    "total_bytes": 500107862016,
    "free_bytes": 234881024000,
    "reserved_bytes": 50010786201
  },
  "allocated_quota_total": 322122547200,
  "over_allocated": false,
  "users": [
    {
      "id": "usr_abc",
      "name": "Owner",
      "role": "owner",
      "quota_bytes": 0,
      "used_bytes": 10737418240,
      "file_count": 842
    }
  ]
}
```

`over_allocated` is `true` when the sum of all users' non-zero quotas exceeds total disk space.

---

## User Data Isolation

- Each user's files live exclusively in `<storage_root>/users/<slug>/`.
- Deduplication is **per-user only** — the same content uploaded by two different users is stored twice.
- A device token can only access its own user's files. Cross-user token swap returns 403/404.
- Path traversal in `folder` or `name` query params is rejected with 400.

## Quota Rules

| Condition                            | Behaviour                             |
|--------------------------------------|---------------------------------------|
| `quota_bytes == 0`                   | Unlimited (but disk reserve enforced) |
| Used ≥ 80% of quota                  | `soft_quota_warning: "80%"` in status |
| Used ≥ 90% of quota                  | `soft_quota_warning: "90%"` in status |
| Upload would exceed quota            | 507 `quota_exceeded` (pre-flight)     |
| Upload exceeds quota mid-stream      | 507 `quota_exceeded` (stream halted)  |
| Upload would breach 10% disk reserve | 507 `quota_exceeded`                  |
