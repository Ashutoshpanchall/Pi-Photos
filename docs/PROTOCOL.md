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

Pairs a device with the server.

**Rules:**
- 6-digit code displayed in server console; expires after 10 minutes.
- Rate-limited per client IP (HTTP 429 if exceeded).
- Invalidation after 5 failed code attempts (HTTP 401 `code_invalidated`).
- Closes immediately upon 1 successful pairing (does NOT auto-generate a new code).
  Pairing opens only upon explicit owner action.

**Request:**
```json
{
  "code": "123456",
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

**Error 401:** Invalid code or code invalidated due to 5 failed attempts.
```json
{
  "error": "invalid_code",
  "message": "Pairing code is invalid or has expired"
}
```

**Error 409:** Pairing is closed.

**Error 429:** Rate limited (too many pairing attempts from this IP).

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

**Error 507:** Quota exceeded or disk reserve would be breached.

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
| 507  | `quota_exceeded`       | User quota or disk reserve exceeded        |
| 500  | `internal_error`       | Unexpected server error                    |
