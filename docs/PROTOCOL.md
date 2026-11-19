# PiPhotos Protocol — v1

## Overview

PiPhotos uses a simple REST API over TLS (self-signed, trust-on-first-use) for
local-network photo and video backup from Android devices to a personal storage
node. The protocol is designed for LAN use and low-resource devices (Raspberry
Pi Zero 2 W with 512 MB RAM).

## Discovery

### mDNS

The server advertises itself via mDNS as `_piphotos._tcp.local.` with TXT records:

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

## Authentication

All endpoints except `GET /api/v1/info` require a Bearer token in the
`Authorization` header:

```
Authorization: Bearer <token>
```

Tokens are issued during pairing and are per-device. They are stored hashed
(SHA-256) on the server.

## TLS

The server generates a self-signed certificate on first run. The certificate's
SHA-256 fingerprint is advertised via mDNS and QR code. Clients pin the
fingerprint at pairing time (trust on first use — TOFU).

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

**Auth:** None (but requires a valid pairing code)

Pairs a device with the server. The pairing code is a 6-digit number displayed
in the server console and expires after 10 minutes.

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

**Error 401:** Invalid or expired pairing code.
```json
{
  "error": "invalid_code",
  "message": "Pairing code is invalid or has expired"
}
```

**Error 409:** Pairing is not open (disabled by admin).

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
    "soft_quota_warning": null
  }
}
```

`soft_quota_warning` is `"80%"` or `"90%"` when the user approaches their quota,
otherwise `null`.

### HEAD /api/v1/upload/{sha256}

**Auth:** Required

Checks whether the authenticated user already has a file with the given SHA-256
hash.

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

**Response 201:** Upload complete.
```json
{
  "sha256": "abcdef...",
  "size": 4194304,
  "path": "Camera/IMG_20260101_120000.jpg"
}
```

**Error 409:** SHA-256 mismatch — the hash of the received bytes does not match
the hash in the URL.
```json
{
  "error": "hash_mismatch",
  "message": "SHA-256 of uploaded data does not match the URL"
}
```

**Error 507:** Quota exceeded or disk reserve would be breached.
```json
{
  "error": "quota_exceeded",
  "message": "Upload would exceed your storage quota (100 GB)"
}
```

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

## Error Codes

| HTTP | Error Key          | Meaning                                   |
|------|--------------------|-------------------------------------------|
| 400  | `bad_request`      | Invalid request parameters                |
| 401  | `unauthorized`     | Missing or invalid auth token             |
| 401  | `invalid_code`     | Invalid or expired pairing code           |
| 403  | `forbidden`        | Insufficient permissions                  |
| 404  | `not_found`        | Resource not found                        |
| 409  | `hash_mismatch`    | Uploaded data SHA-256 does not match URL  |
| 409  | `pairing_closed`   | Pairing is currently disabled             |
| 507  | `quota_exceeded`   | User quota or disk reserve exceeded       |
| 500  | `internal_error`   | Unexpected server error                   |
