"""PiPhotos storage-node server — FastAPI application.

Designed for LAN use on low-resource devices (Pi Zero 2 W, 512 MB RAM).
"""

from __future__ import annotations

import asyncio
import hashlib
import io
import json
import logging
import os
import shutil
import ssl
import sys
import uuid
from collections import defaultdict
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional

import uvicorn
from fastapi import FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from auth import (
    AuthIdentity,
    IpRateLimiter,
    PairingManager,
    create_bearer_token,
    hash_token,
    resolve_bearer,
)
from database import Database
from discovery import PiPhotosAdvertiser
from models import (
    CheckUploadRequest,
    CheckUploadResponse,
    DeviceFoldersResponse,
    DiskInfo,
    ErrorResponse,
    FolderInfo,
    OffsetMismatchDetail,
    PairRequest,
    PairResponse,
    ServerInfo,
    StatusResponse,
    UploadResult,
    UserQuota,
)
from storage import StorageManager, slugify
from tls_cert import generate_self_signed_cert, get_cert_fingerprint, get_local_ipv4_addresses

logger = logging.getLogger("piphotos")

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

SERVER_VERSION = "1.0.0"
PROTOCOL_VERSION = 1
CHUNK_SIZE = 65536  # 64 KB streaming buffer
DEFAULT_QUOTA_BYTES = 100 * 1024 * 1024 * 1024  # 100 GB


def get_disk_reserve_fraction() -> float:
    return float(os.environ.get("PIPHOTOS_DISK_RESERVE_FRACTION", "0.10"))


def _config() -> dict:
    """Read configuration from environment / config file."""
    config_path = Path(os.environ.get("PIPHOTOS_CONFIG", "config.json"))
    cfg = {}
    if config_path.exists():
        with open(config_path) as f:
            cfg = json.load(f)

    return {
        "server_name": os.environ.get("PIPHOTOS_NAME", cfg.get("name", "Home Vault")),
        "port": int(os.environ.get("PIPHOTOS_PORT", cfg.get("port", 8443))),
        "storage_root": os.environ.get("PIPHOTOS_STORAGE", cfg.get("storage", "./data/storage")),
        "db_path": os.environ.get("PIPHOTOS_DB", cfg.get("database", "./data/piphotos.db")),
        "cert_dir": os.environ.get("PIPHOTOS_CERTS", cfg.get("certs", "./data/certs")),
        "default_quota": int(os.environ.get(
            "PIPHOTOS_DEFAULT_QUOTA", cfg.get("default_quota", str(DEFAULT_QUOTA_BYTES)))),
    }


# ---------------------------------------------------------------------------
# Global state (initialised in lifespan)
# ---------------------------------------------------------------------------

db: Database
storage: StorageManager
pairing: PairingManager
advertiser: Optional[PiPhotosAdvertiser] = None
server_id: str
config: dict
ip_limiter = IpRateLimiter(max_requests=10, window_seconds=60.0)

# Per-user accounting locks and active uploads tracking
user_locks: dict[str, asyncio.Lock] = defaultdict(asyncio.Lock)
active_uploads_lock = asyncio.Lock()
active_uploads: set[tuple[str, str]] = set()  # (user_id, sha256)


def _load_or_create_server_id(data_dir: Path) -> str:
    id_file = data_dir / "server_id"
    if id_file.exists():
        return id_file.read_text().strip()
    sid = str(uuid.uuid4())
    data_dir.mkdir(parents=True, exist_ok=True)
    id_file.write_text(sid)
    return sid


# ---------------------------------------------------------------------------
# Auth dependency
# ---------------------------------------------------------------------------

def _require_auth(authorization: Optional[str]) -> AuthIdentity:
    identity = resolve_bearer(db, authorization)
    if identity is None:
        raise HTTPException(status_code=401, detail={
            "error": "unauthorized",
            "message": "Missing or invalid authentication token",
        })
    # Check the user is active
    user = db.get_user(identity.user_id)
    if user is None or not user.is_active:
        raise HTTPException(status_code=401, detail={
            "error": "unauthorized",
            "message": "User account is suspended or deleted",
        })
    return identity


# ---------------------------------------------------------------------------
# Threadpool helpers for off-loop hashing and file I/O
# ---------------------------------------------------------------------------

def _hash_and_append_chunk(file_handle, hasher, chunk: bytes) -> None:
    file_handle.write(chunk)
    hasher.update(chunk)


def _read_and_hash_file(path: Path) -> tuple[int, hashlib._Hash]:
    """Read an existing partial file and return its size and initialized SHA-256 hasher."""
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        while True:
            chunk = f.read(CHUNK_SIZE)
            if not chunk:
                break
            h.update(chunk)
            size += len(chunk)
    return size, h


# ---------------------------------------------------------------------------
# Lifespan
# ---------------------------------------------------------------------------

@asynccontextmanager
async def lifespan(app: FastAPI):
    global db, storage, pairing, advertiser, server_id, config

    config = _config()

    data_dir = Path(config["db_path"]).parent
    data_dir.mkdir(parents=True, exist_ok=True)

    # Database
    db = Database(config["db_path"])

    # Storage
    storage = StorageManager(config["storage_root"])

    # Server identity
    server_id = _load_or_create_server_id(data_dir)

    # TLS certificate
    cert_dir = Path(config["cert_dir"])
    cert_path = cert_dir / "cert.pem"
    key_path = cert_dir / "key.pem"
    if not cert_path.exists() or not key_path.exists():
        fp = generate_self_signed_cert(cert_path, key_path)
        logger.info("Generated TLS certificate (fingerprint: %s)", fp)
    else:
        fp = get_cert_fingerprint(cert_path)

    app.state.tls_fingerprint = fp

    # Pairing
    pairing = PairingManager()
    code = pairing.generate_code()

    # Ensure owner user exists
    users = db.list_users()
    if not users:
        owner = db.create_user("Owner", "owner", config["default_quota"])
        db.audit("system", "create_owner", owner.id, "Created initial owner user")
        logger.info("Created owner user: %s", owner.id)

    # mDNS advertisement via AsyncZeroconf
    advertiser = None
    mdns_status = "DISABLED"
    if not os.environ.get("PIPHOTOS_NO_MDNS"):
        advertiser = PiPhotosAdvertiser(
            server_id=server_id,
            server_name=config["server_name"],
            port=config["port"],
            tls_fingerprint=fp,
            protocol_version=PROTOCOL_VERSION,
        )
        try:
            ips = await advertiser.async_start()
            mdns_status = f"ACTIVE ({', '.join(ips)})"
        except Exception as e:
            mdns_status = f"FAILED ({e})"
            logger.error("mDNS registration FAILED: %s", e, exc_info=True)

    # Print startup banner
    port = config["port"]
    print("\n" + "=" * 60, flush=True)
    print(f"  PiPhotos Storage Node — {config['server_name']}", flush=True)
    print(f"  Server ID: {server_id}", flush=True)
    print(f"  Listening: https://0.0.0.0:{port}", flush=True)
    print(f"  TLS fingerprint: {fp}", flush=True)
    print(f"  mDNS: {mdns_status}", flush=True)
    print(f"  Pairing code: {code}  (expires in 10 minutes or 5 failed attempts)", flush=True)
    print("=" * 60, flush=True)

    # Print QR code
    if not os.environ.get("PIPHOTOS_NO_QR"):
        _print_qr(port, fp, code)

    print("[SERVER_READY]", flush=True)

    yield

    # Shutdown
    if advertiser:
        await advertiser.async_stop()
    logger.info("Server shutting down")


# ---------------------------------------------------------------------------
# QR code helper
# ---------------------------------------------------------------------------

def _print_qr(port: int, fingerprint: str, code: str) -> None:
    """Print a QR code with connection info to the console."""
    try:
        import qrcode  # type: ignore

        ips = get_local_ipv4_addresses()
        local_ip = ips[0] if ips else "127.0.0.1"

        payload = json.dumps({
            "host": local_ip,
            "port": port,
            "tls_fp": fingerprint,
            "code": code,
        }, separators=(",", ":"))

        qr = qrcode.QRCode(border=1)
        qr.add_data(payload)
        qr.make(fit=True)

        print("\n  Scan this QR code with PiPhotos app:")
        matrix = qr.get_matrix()
        for y in range(0, len(matrix) - 1, 2):
            line = "  "
            for x in range(len(matrix[y])):
                top = matrix[y][x]
                bot = matrix[y + 1][x] if y + 1 < len(matrix) else False
                if top and bot:
                    line += "█"
                elif top:
                    line += "▀"
                elif bot:
                    line += "▄"
                else:
                    line += " "
            print(line)
        print()
    except ImportError:
        print("  (Install 'qrcode' package for QR code display)")
    except Exception as e:
        logger.debug("QR code generation failed: %s", e)


# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------

app = FastAPI(
    title="PiPhotos Storage Node",
    version=SERVER_VERSION,
    lifespan=lifespan,
)


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get("/api/v1/info")
async def get_info() -> ServerInfo:
    return ServerInfo(
        id=server_id,
        name=config["server_name"],
        version=SERVER_VERSION,
        protocol_version=PROTOCOL_VERSION,
        pairing_open=pairing.is_open,
    )


@app.post("/api/v1/pair")
async def pair_device(req: PairRequest, request: Request) -> PairResponse:
    # IP Rate-Limiting
    client_ip = request.client.host if request.client else "unknown"
    if not ip_limiter.is_allowed(client_ip):
        raise HTTPException(status_code=429, detail={
            "error": "rate_limited",
            "message": "Too many pairing attempts from this IP. Please wait before retrying.",
        })

    if not pairing.is_open:
        raise HTTPException(status_code=409, detail={
            "error": "pairing_closed",
            "message": "Pairing is not currently available. Please request pairing from the server owner.",
        })

    valid, reason = pairing.validate_code(req.code)
    if not valid:
        if reason == "too_many_attempts":
            raise HTTPException(status_code=401, detail={
                "error": "code_invalidated",
                "message": "Too many failed attempts. Pairing code has been invalidated.",
            })
        elif reason == "pairing_closed":
            raise HTTPException(status_code=409, detail={
                "error": "pairing_closed",
                "message": "Pairing is closed.",
            })
        raise HTTPException(status_code=401, detail={
            "error": "invalid_code",
            "message": "Pairing code is invalid or has expired",
        })

    # Find the owner user (first pair always goes to owner)
    users = db.list_users()
    owner = next((u for u in users if u.role == "owner"), None)
    if owner is None:
        raise HTTPException(status_code=500, detail={
            "error": "internal_error",
            "message": "No owner user configured",
        })

    # Create device
    device_slug = slugify(f"{req.device_name}")
    device = db.create_device(owner.id, req.device_name, req.device_model, device_slug)

    # Create token
    token = create_bearer_token()
    db.store_token(hash_token(token), owner.id, device.id)

    db.audit(owner.id, "pair_device", device.id,
             f"Paired device {req.device_name} ({req.device_model})")

    # Pairing closes after one success (no auto-generated new code)
    print("\n  Pairing successful for device: %s. Pairing is now CLOSED.\n" % req.device_name)

    return PairResponse(
        token=token,
        user_id=owner.id,
        device_id=device.id,
    )


@app.get("/api/v1/status")
async def get_status(authorization: Optional[str] = Header(None)) -> StatusResponse:
    identity = _require_auth(authorization)

    # Disk info
    disk_usage = shutil.disk_usage(storage.root)
    reserved = int(disk_usage.total * get_disk_reserve_fraction())

    # User quota
    user = db.get_user(identity.user_id)
    if user is None:
        raise HTTPException(status_code=401, detail={
            "error": "unauthorized", "message": "User not found",
        })

    used = db.user_storage_used(identity.user_id)
    file_count = db.user_file_count(identity.user_id)
    free = max(0, user.quota_bytes - used)

    # Soft quota warnings
    usage_pct = (used / user.quota_bytes * 100) if user.quota_bytes > 0 else 0
    warning = None
    if usage_pct >= 90:
        warning = "90%"
    elif usage_pct >= 80:
        warning = "80%"

    return StatusResponse(
        disk=DiskInfo(
            total_bytes=disk_usage.total,
            free_bytes=disk_usage.free,
            reserved_bytes=reserved,
        ),
        user=UserQuota(
            user_id=identity.user_id,
            quota_bytes=user.quota_bytes,
            used_bytes=used,
            free_bytes=free,
            file_count=file_count,
            soft_quota_warning=warning,
            organise_by_date=user.organise_by_date,
        ),
    )


@app.post("/api/v1/upload/check")
async def check_uploads_bulk(
    req: CheckUploadRequest,
    authorization: Optional[str] = Header(None),
) -> CheckUploadResponse:
    """Batch check for existing files and resumable partial upload offsets."""
    identity = _require_auth(authorization)

    existing = db.check_hashes_exist(identity.user_id, req.hashes)
    partials = db.get_partial_offsets(identity.user_id, req.hashes)

    # Filter out partial records for hashes that are already fully stored
    existing_set = set(existing)
    active_partials = {
        h: offset for h, offset in partials.items()
        if h not in existing_set
    }

    return CheckUploadResponse(
        existing=existing,
        partial=active_partials,
    )


@app.head("/api/v1/upload/{sha256}")
async def check_upload(
    sha256: str,
    authorization: Optional[str] = Header(None),
) -> Response:
    identity = _require_auth(authorization)

    # Validate SHA-256 format
    if len(sha256) != 64 or not all(c in "0123456789abcdef" for c in sha256):
        raise HTTPException(status_code=400, detail={
            "error": "bad_request", "message": "Invalid SHA-256 hash format",
        })

    # Check if file already exists for this user
    if db.file_exists_for_user(identity.user_id, sha256):
        return Response(status_code=200)

    # Check for partial upload
    partial = db.get_partial_upload(identity.user_id, sha256)
    if partial is not None:
        offset, part_path = partial
        if Path(part_path).exists():
            return Response(
                status_code=404,
                headers={"Upload-Offset": str(offset)},
            )

    return Response(status_code=404)


@app.put("/api/v1/upload/{sha256}")
async def upload_file(
    sha256: str,
    request: Request,
    device: str = Query(...),
    folder: str = Query(...),
    name: str = Query(...),
    taken: Optional[int] = Query(None),
    authorization: Optional[str] = Header(None),
    upload_offset: int = Header(0, alias="Upload-Offset"),
    content_length: int = Header(0, alias="Content-Length"),
) -> UploadResult:
    identity = _require_auth(authorization)

    # Validate SHA-256 format
    if len(sha256) != 64 or not all(c in "0123456789abcdef" for c in sha256):
        raise HTTPException(status_code=400, detail={
            "error": "bad_request", "message": "Invalid SHA-256 hash format",
        })

    # Validate device belongs to user
    dev = db.get_device(device)
    if dev is None or dev.user_id != identity.user_id:
        raise HTTPException(status_code=403, detail={
            "error": "forbidden", "message": "Device does not belong to this user",
        })

    user = db.get_user(identity.user_id)
    if user is None:
        raise HTTPException(status_code=401)
    user_slug = user.name

    # Check if this content is already stored for this user (deduplication)
    # If so, create a duplicate reference record for this device/folder/name without second disk copy.
    existing_file = db.get_first_file_by_hash(identity.user_id, sha256)
    if existing_file:
        target_dir = storage.folder_dir(
            user_slug, dev.slug, folder,
            date_taken=taken,
            organise_by_date=user.organise_by_date,
        )
        target_dir.mkdir(parents=True, exist_ok=True)
        resolved_name = storage.resolve_filename(target_dir, name, sha256)

        db.insert_file(
            user_id=identity.user_id,
            device_id=device,
            folder=folder,
            relative_path=resolved_name,
            sha256=sha256,
            size=existing_file.size,
            date_taken=taken,
        )
        return UploadResult(sha256=sha256, size=existing_file.size, path=f"{folder}/{resolved_name}")

    # Concurrency control: reject concurrent upload of the same hash for this user
    upload_key = (identity.user_id, sha256)
    async with active_uploads_lock:
        if upload_key in active_uploads:
            raise HTTPException(status_code=409, detail={
                "error": "upload_in_progress",
                "message": "Upload for this hash is already in progress",
            })
        active_uploads.add(upload_key)

    try:
        # Serialise quota accounting per user
        async with user_locks[identity.user_id]:
            used = db.user_storage_used(identity.user_id)
            final_size = upload_offset + content_length if content_length > 0 else 0
            if final_size > 0 and used + final_size > user.quota_bytes:
                raise HTTPException(status_code=507, detail={
                    "error": "quota_exceeded",
                    "message": f"Upload would exceed your storage quota ({user.quota_bytes // (1024**3)} GB)",
                })

            disk_usage = shutil.disk_usage(storage.root)
            reserved = int(disk_usage.total * get_disk_reserve_fraction())
            if content_length > 0 and disk_usage.free - content_length < reserved:
                raise HTTPException(status_code=507, detail={
                    "error": "quota_exceeded",
                    "message": "Upload would breach the disk reserve",
                })

            # Check resume offset vs actual part file size
            part_path = storage.part_file_path(user_slug, sha256)
            actual_size = part_path.stat().st_size if part_path.exists() else 0

            if upload_offset != actual_size:
                # Mismatch: write nothing and reply 409
                raise HTTPException(status_code=409, detail={
                    "error": "offset_mismatch",
                    "message": f"Upload-Offset {upload_offset} does not match server partial file size {actual_size}",
                    "offset": actual_size,
                })

            # Prepare hasher and file handle
            if upload_offset > 0 and part_path.exists():
                mode = "ab"
                # Hash existing bytes in threadpool to keep event loop free
                total_bytes, hasher = await run_in_threadpool(_read_and_hash_file, part_path)
            else:
                mode = "wb"
                total_bytes = 0
                hasher = hashlib.sha256()

            # Stream body to .part file, checking running quota/disk reserve dynamically
            part_file = open(part_path, mode)
            bytes_written_this_request = 0
            try:
                async for chunk in request.stream():
                    if not chunk:
                        continue
                    chunk_len = len(chunk)

                    # Dynamic quota enforcement while streaming
                    if used + total_bytes + chunk_len > user.quota_bytes:
                        raise HTTPException(status_code=507, detail={
                            "error": "quota_exceeded",
                            "message": f"Upload exceeded user storage quota ({user.quota_bytes // (1024**3)} GB)",
                        })

                    # Dynamic disk reserve enforcement while streaming
                    bytes_written_this_request += chunk_len
                    if (disk_usage.free - bytes_written_this_request) < reserved:
                        raise HTTPException(status_code=507, detail={
                            "error": "quota_exceeded",
                            "message": "Upload breached disk reserve",
                        })

                    # Write and hash in threadpool
                    await run_in_threadpool(_hash_and_append_chunk, part_file, hasher, chunk)
                    total_bytes += chunk_len

            finally:
                part_file.close()
                if total_bytes > 0:
                    db.upsert_partial_upload(identity.user_id, sha256, total_bytes, str(part_path))

            # Verify integrity
            computed_hash = hasher.hexdigest()
            if computed_hash != sha256:
                # Hash mismatch — cleanup corrupted part
                storage.cleanup_part_file(user_slug, sha256)
                db.delete_partial_upload(identity.user_id, sha256)
                raise HTTPException(status_code=409, detail={
                    "error": "hash_mismatch",
                    "message": "SHA-256 of uploaded data does not match the URL",
                })

            # Determine destination directory with per-user date organisation
            target_dir = storage.folder_dir(
                user_slug, dev.slug, folder,
                date_taken=taken,
                organise_by_date=user.organise_by_date,
            )
            target_dir.mkdir(parents=True, exist_ok=True)

            resolved_name = storage.resolve_filename(target_dir, name, sha256)
            target_path = target_dir / resolved_name

            # Atomic move off event loop
            await run_in_threadpool(storage.atomic_move, part_path, target_path)

            # Set file mtime to date_taken
            await run_in_threadpool(storage.set_mtime, target_path, taken)

            # Record in database
            db.insert_file(
                user_id=identity.user_id,
                device_id=device,
                folder=folder,
                relative_path=resolved_name,
                sha256=sha256,
                size=total_bytes,
                date_taken=taken,
            )

            # Clean partial upload record
            db.delete_partial_upload(identity.user_id, sha256)

            return UploadResult(
                sha256=sha256,
                size=total_bytes,
                path=f"{folder}/{resolved_name}",
            )

    finally:
        async with active_uploads_lock:
            active_uploads.discard(upload_key)


@app.get("/api/v1/devices/{device_id}/folders")
async def get_device_folders(
    device_id: str,
    authorization: Optional[str] = Header(None),
) -> DeviceFoldersResponse:
    identity = _require_auth(authorization)

    dev = db.get_device(device_id)
    if dev is None or dev.user_id != identity.user_id:
        raise HTTPException(status_code=404, detail={
            "error": "not_found", "message": "Device not found",
        })

    stats = db.device_folder_stats(identity.user_id, device_id)
    folders = [
        FolderInfo(name=s.folder, item_count=s.item_count, total_bytes=s.total_bytes)
        for s in stats
    ]

    return DeviceFoldersResponse(
        device_id=device_id,
        device_name=dev.name,
        folders=folders,
    )


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    cfg = _config()
    port = cfg["port"]
    cert_dir = Path(cfg["cert_dir"])
    cert_path = cert_dir / "cert.pem"
    key_path = cert_dir / "key.pem"

    if not cert_path.exists() or not key_path.exists():
        generate_self_signed_cert(cert_path, key_path)

    ssl_config = {
        "ssl_certfile": str(cert_path),
        "ssl_keyfile": str(key_path),
    }

    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=port,
        log_level="info",
        **ssl_config,
    )


if __name__ == "__main__":
    main()
