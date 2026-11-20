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
import time
import uuid
import secrets
from collections import defaultdict
from contextlib import asynccontextmanager
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Optional

import uvicorn
from fastapi import FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from starlette.concurrency import run_in_threadpool

from auth import (
    AuthIdentity,
    IpRateLimiter,
    GlobalPairingLimiter,
    PairingManager,
    create_bearer_token,
    generate_invite_code,
    hash_token,
    resolve_bearer,
)
from database import Database, UserRow
from discovery import PiPhotosAdvertiser
from models import (
    CheckUploadRequest,
    CheckUploadResponse,
    DeviceFoldersResponse,
    DiskInfo,
    ErrorResponse,
    FolderInfo,
    FileInfo,
    MeResponse,
    OffsetMismatchDetail,
    PairRequest,
    PairResponse,
    ServerInfo,
    StatusResponse,
    UploadResult,
    UserQuota,
    UserRole,
    AdminUserSummary,
    CreateUserRequest,
    CreateUserResponse,
    UpdateUserRequest,
    InviteResponse,
    DeviceSummary,
    OpenPairingResponse,
    UserOverview,
    AdminOverviewResponse,
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
global_pairing_limiter = GlobalPairingLimiter()

# In-memory throttle for updating device last_seen_at (max 1 DB write per device per 60 s)
_device_last_seen_cache: dict[str, float] = {}
_LAST_SEEN_THROTTLE_SECONDS = 60.0


def _throttled_update_device_last_seen(device_id: str) -> bool:
    """Updates device last_seen_at in DB if at least 60 seconds have passed. Returns True if DB was updated."""
    now = time.time()
    last = _device_last_seen_cache.get(device_id, 0.0)
    if now - last >= _LAST_SEEN_THROTTLE_SECONDS:
        _device_last_seen_cache[device_id] = now
        db.update_device_last_seen(device_id)
        return True
    return False

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
    # Update device last_seen_at (throttled to at most once per 60s)
    _throttled_update_device_last_seen(identity.device_id)

    # Check user exists and active
    user = db.get_user(identity.user_id)
    if user is None:
        raise HTTPException(status_code=401, detail={
            "error": "unauthorized",
            "message": "User not found",
        })
    if user.status == "disabled" or not user.is_active:
        raise HTTPException(status_code=403, detail={
            "error": "user_disabled",
            "message": "User account is disabled",
        })
    return identity


def _require_owner(authorization: Optional[str]) -> AuthIdentity:
    identity = _require_auth(authorization)
    user = db.get_user(identity.user_id)
    if user is None or user.role != UserRole.OWNER:
        raise HTTPException(status_code=403, detail={
            "error": "forbidden",
            "message": "Owner privileges required",
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


def _user_to_admin_summary(u: UserRow) -> AdminUserSummary:
    return AdminUserSummary(
        id=u.id,
        name=u.display_name or u.name,
        display_name=u.display_name or u.name,
        slug=u.slug,
        role=u.role,
        status=u.status,
        quota_bytes=u.quota_bytes,
        used_bytes=db.user_storage_used(u.id),
        file_count=db.user_file_count(u.id),
        devices_count=db.get_user_device_count(u.id),
        last_seen_at=db.get_user_last_seen(u.id),
    )


@app.post("/api/v1/pair")
async def pair_device(req: PairRequest, request: Request) -> PairResponse:
    # Global Rate-Limiting across all IPs (Phase U1.1)
    is_locked, remaining = global_pairing_limiter.is_locked()
    if is_locked:
        logger.warning("Pairing rejected: global limiter active (%d s remaining)", int(remaining))
        raise HTTPException(status_code=429, detail={
            "error": "pairing_locked",
            "message": f"Pairing is temporarily locked across all IPs due to excessive failed attempts. Try again in {int(remaining)} seconds.",
        })

    # IP Rate-Limiting
    client_ip = request.headers.get("x-forwarded-for") or (request.client.host if request.client else "unknown")
    if not ip_limiter.is_allowed(client_ip):
        raise HTTPException(status_code=429, detail={
            "error": "rate_limited",
            "message": "Too many pairing attempts from this IP. Please wait before retrying.",
        })

    def _fail_attempt(error_code: str, message: str, status_code: int = 401):
        pairing.record_failed_attempt()
        if global_pairing_limiter.record_failure():
            logger.warning("Global pairing limiter locked pairing for 10 minutes after 20 failures")
        raise HTTPException(status_code=status_code, detail={
            "error": error_code,
            "message": message,
        })

    code_raw = req.code.strip()
    code_upper = code_raw.upper()
    code_h = hash_token(code_upper)

    # 1. Check if the code matches an invite first (case-insensitive for 8-char codes)
    invite = db.get_invite_by_hash(code_h)
    if invite is None and code_raw != code_upper:
        invite = db.get_invite_by_hash(hash_token(code_raw))

    if invite is not None:
        if invite.used_at is not None:
            _fail_attempt("invite_used", "Invite code has already been used")
        now_iso = datetime.now(timezone.utc).isoformat()
        if invite.expires_at < now_iso:
            _fail_attempt("invite_expired", "Invite code has expired")

        user = db.get_user(invite.user_id)
        if user is None or user.status == "disabled" or not user.is_active:
            raise HTTPException(status_code=403, detail={
                "error": "user_disabled",
                "message": "User account is disabled",
            })

        # Mark invite used and reset attempts
        db.mark_invite_used(invite.id)
        pairing.reset_attempts()

        # Create device and token for invited user
        device_slug = slugify(f"{req.device_name}")
        device = db.create_device(user.id, req.device_name, req.device_model, device_slug)
        token = create_bearer_token()
        db.store_token(hash_token(token), user.id, device.id)
        db.audit(user.id, "pair_device_invite", device.id,
                 f"Paired device {req.device_name} ({req.device_model}) via invite")

        return PairResponse(
            token=token,
            user_id=user.id,
            device_id=device.id,
        )

    # 2. If not an invite, check owner pairing code
    if not pairing.is_open:
        _fail_attempt("pairing_closed", "Pairing is not currently available. Please request pairing from the server owner.", status_code=409)

    valid, reason = pairing.validate_code(code_raw)
    if not valid:
        if global_pairing_limiter.record_failure():
            logger.warning("Global pairing limiter locked pairing for 10 minutes after 20 failures")
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
    owner = next((u for u in users if u.role == "owner" and u.status == "active"), None)
    if owner is None:
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

    if user.quota_bytes > 0:
        free = max(0, user.quota_bytes - used)
        usage_pct = (used / user.quota_bytes * 100)
    else:
        # Unlimited quota bound by disk space
        free = max(0, disk_usage.free - reserved)
        usage_pct = 0

    # Soft quota warnings
    warning = None
    if user.quota_bytes > 0:
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

    # Validate folder and name for path traversal
    if ".." in folder or "/" in folder or "\\" in folder:
        raise HTTPException(status_code=400, detail={
            "error": "bad_request", "message": "Invalid folder name: path traversal characters are not allowed",
        })
    if ".." in name or "/" in name or "\\" in name:
        raise HTTPException(status_code=400, detail={
            "error": "bad_request", "message": "Invalid filename: path traversal characters are not allowed",
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
    user_slug = user.slug

    # Check if this content is already stored for this user (deduplication)
    # If so, create a real file via os.link() (or fallback copy/db_only) and record in DB
    existing_file = db.get_first_file_by_hash(identity.user_id, sha256)
    if existing_file:
        existing_dev = db.get_device(existing_file.device_id)
        existing_dir = storage.folder_dir(
            user_slug, existing_dev.slug if existing_dev else "unknown", existing_file.folder,
            date_taken=existing_file.date_taken,
            organise_by_date=user.organise_by_date,
        )
        existing_path = existing_dir / existing_file.relative_path

        target_dir = storage.folder_dir(
            user_slug, dev.slug, folder,
            date_taken=taken,
            organise_by_date=user.organise_by_date,
        )
        target_dir.mkdir(parents=True, exist_ok=True)
        resolved_name = storage.resolve_filename(target_dir, name, sha256)
        target_path = target_dir / resolved_name

        link_method = "none"
        if existing_path.exists() and not target_path.exists():
            try:
                os.link(str(existing_path), str(target_path))
                link_method = "hardlink"
            except Exception:
                try:
                    shutil.copy2(str(existing_path), str(target_path))
                    link_method = "copy"
                except Exception:
                    link_method = "db_only"
        elif target_path.exists():
            link_method = "existing"

        if target_path.exists() and taken:
            storage.set_mtime(target_path, taken)

        db.insert_file(
            user_id=identity.user_id,
            device_id=device,
            folder=folder,
            relative_path=resolved_name,
            sha256=sha256,
            size=existing_file.size,
            date_taken=taken,
        )
        logger.info("Duplicate upload for sha %s resolved via %s", sha256, link_method)
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
            if user.quota_bytes > 0 and final_size > 0 and used + final_size > user.quota_bytes:
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
            chunk_timeout = float(os.environ.get("PIPHOTOS_CHUNK_TIMEOUT", "30.0"))
            part_file = open(part_path, mode)
            bytes_written_this_request = 0
            stream_iter = request.stream().__aiter__()
            try:
                while True:
                    try:
                        chunk = await asyncio.wait_for(stream_iter.__anext__(), timeout=chunk_timeout)
                    except StopAsyncIteration:
                        break
                    except (asyncio.TimeoutError, TimeoutError):
                        logger.warning("Upload chunk read timed out after %s s", chunk_timeout)
                        raise HTTPException(status_code=408, detail={
                            "error": "timeout",
                            "message": "Upload stream chunk read timed out",
                        })

                    if not chunk:
                        continue
                    chunk_len = len(chunk)

                    # Dynamic quota enforcement while streaming
                    if user.quota_bytes > 0 and used + total_bytes + chunk_len > user.quota_bytes:
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
# Current User and Files Endpoints (Phase U1.1)
# ---------------------------------------------------------------------------

@app.get("/api/v1/me")
async def get_me(authorization: Optional[str] = Header(None)) -> MeResponse:
    identity = _require_auth(authorization)
    user = db.get_user(identity.user_id)
    if user is None:
        raise HTTPException(status_code=401, detail={"error": "unauthorized", "message": "User not found"})
    dev = db.get_device(identity.device_id)
    used = db.user_storage_used(identity.user_id)

    return MeResponse(
        user_id=user.id,
        display_name=user.display_name or user.name,
        role=user.role,
        quota_bytes=user.quota_bytes,
        used_bytes=used,
        device_id=dev.id if dev else "",
        device_name=dev.name if dev else "",
    )


@app.get("/api/v1/files")
async def list_files(
    device_id: Optional[str] = Query(None),
    folder: Optional[str] = Query(None),
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    authorization: Optional[str] = Header(None),
) -> list[FileInfo]:
    identity = _require_auth(authorization)
    files = db.list_files_filtered(identity.user_id, device_id=device_id, folder=folder, limit=limit, offset=offset)
    return [
        FileInfo(
            id=f.id,
            name=f.relative_path,
            size=f.size,
            sha256=f.sha256,
            date_taken=f.date_taken,
            device_id=f.device_id,
            folder=f.folder,
        )
        for f in files
    ]


@app.get("/api/v1/files/{file_id}/content")
async def get_file_content(
    file_id: int,
    request: Request,
    authorization: Optional[str] = Header(None),
) -> Response:
    identity = _require_auth(authorization)
    file_row = db.get_file(file_id)
    if file_row is None or file_row.user_id != identity.user_id:
        raise HTTPException(status_code=404, detail={"error": "not_found", "message": "File not found"})

    user = db.get_user(identity.user_id)
    if user is None:
        raise HTTPException(status_code=401)
    dev = db.get_device(file_row.device_id)
    folder_dir = storage.folder_dir(
        user.slug, dev.slug if dev else "unknown", file_row.folder,
        date_taken=file_row.date_taken,
        organise_by_date=user.organise_by_date,
    )
    file_path = folder_dir / file_row.relative_path

    if not file_path.exists():
        raise HTTPException(status_code=404, detail={"error": "not_found", "message": "File content not found on disk"})

    file_size = file_path.stat().st_size
    range_header = request.headers.get("Range")

    if range_header and range_header.startswith("bytes="):
        try:
            byte_range = range_header.split("bytes=", 1)[1].strip()
            parts = byte_range.split("-")
            start = int(parts[0]) if parts[0] else 0
            end = int(parts[1]) if len(parts) > 1 and parts[1] else file_size - 1
            if start >= file_size or end >= file_size or start > end:
                return Response(
                    status_code=416,
                    headers={"Content-Range": f"bytes */{file_size}"},
                )
            length = end - start + 1

            def iter_range():
                with open(file_path, "rb") as f:
                    f.seek(start)
                    remaining = length
                    while remaining > 0:
                        chunk_to_read = min(CHUNK_SIZE, remaining)
                        data = f.read(chunk_to_read)
                        if not data:
                            break
                        remaining -= len(data)
                        yield data

            return StreamingResponse(
                iter_range(),
                status_code=206,
                headers={
                    "Content-Range": f"bytes {start}-{end}/{file_size}",
                    "Accept-Ranges": "bytes",
                    "Content-Length": str(length),
                    "Content-Type": "application/octet-stream",
                },
            )
        except Exception:
            pass

    def iter_full():
        with open(file_path, "rb") as f:
            while True:
                data = f.read(CHUNK_SIZE)
                if not data:
                    break
                yield data

    return StreamingResponse(
        iter_full(),
        status_code=200,
        headers={
            "Content-Length": str(file_size),
            "Accept-Ranges": "bytes",
            "Content-Type": "application/octet-stream",
        },
    )


# ---------------------------------------------------------------------------
# Owner-Only Admin Endpoints (/api/v1/admin/*)
# ---------------------------------------------------------------------------

@app.get("/api/v1/admin/users")
async def list_admin_users(
    authorization: Optional[str] = Header(None),
) -> list[AdminUserSummary]:
    _require_owner(authorization)
    users = db.list_users()
    return [_user_to_admin_summary(u) for u in users]


@app.post("/api/v1/admin/users")
async def create_admin_user(
    req: CreateUserRequest,
    authorization: Optional[str] = Header(None),
) -> CreateUserResponse:
    identity = _require_owner(authorization)
    display_name = req.display_name.strip()
    if not display_name:
        raise HTTPException(status_code=400, detail={
            "error": "bad_request",
            "message": "Display name is required",
        })

    base_slug = slugify(display_name)
    slug = base_slug
    existing = db.get_user_by_slug(slug)
    suffix = 2
    while existing is not None:
        slug = f"{base_slug}-{suffix}"
        existing = db.get_user_by_slug(slug)
        suffix += 1

    if req.unlimited:
        effective_quota = 0
    elif req.quota_bytes is not None:
        effective_quota = req.quota_bytes
    else:
        effective_quota = config.get("default_quota", 100 * 1024**3)

    new_user = db.create_user(
        name=display_name,
        role="member",
        quota_bytes=effective_quota,
        display_name=display_name,
        slug=slug,
        status="active",
    )

    code = generate_invite_code(8)
    code_h = hash_token(code)
    expires_dt = datetime.now(timezone.utc) + timedelta(hours=24)
    expires_at = expires_dt.isoformat()
    db.create_invite(new_user.id, code_h, created_by=identity.user_id, expires_at=expires_at, quota_bytes=effective_quota)

    ips = get_local_ipv4_addresses()
    local_ip = ips[0] if ips else "127.0.0.1"
    port = config["port"]
    tls_fp = getattr(app.state, "tls_fingerprint", "")
    qr_payload = f"piphotos://pair?host={local_ip}&port={port}&tls_fp={tls_fp}&code={code}"

    return CreateUserResponse(
        user=_user_to_admin_summary(new_user),
        invite_code=code,
        expires_at=expires_at,
        qr_payload=qr_payload,
    )


@app.patch("/api/v1/admin/users/{user_id}")
async def update_admin_user(
    user_id: str,
    req: UpdateUserRequest,
    force: bool = Query(False),
    authorization: Optional[str] = Header(None),
) -> AdminUserSummary:
    _require_owner(authorization)
    target = db.get_user(user_id)
    if target is None:
        raise HTTPException(status_code=404, detail={"error": "not_found", "message": "User not found"})

    # Check last active owner guard
    active_owners = db.count_active_owners()
    if target.role == "owner" and target.status == "active" and active_owners <= 1:
        if req.status == "disabled":
            raise HTTPException(status_code=400, detail={
                "error": "last_owner_protected",
                "message": "Cannot disable the last active owner",
            })
        if req.role is not None and req.role != "owner":
            raise HTTPException(status_code=400, detail={
                "error": "last_owner_protected",
                "message": "Cannot demote the last active owner",
            })

    # Check quota below usage
    if req.quota_bytes is not None and req.quota_bytes > 0:
        used = db.user_storage_used(user_id)
        if req.quota_bytes < used and not force:
            raise HTTPException(status_code=400, detail={
                "error": "quota_below_usage",
                "message": f"Quota ({req.quota_bytes} bytes) cannot be set below current storage usage ({used} bytes). Pass force=true to override.",
            })

    kwargs = {}
    if req.display_name is not None:
        kwargs["display_name"] = req.display_name.strip()
    if req.quota_bytes is not None:
        kwargs["quota_bytes"] = req.quota_bytes
    if req.status is not None:
        kwargs["status"] = req.status
    if req.role is not None:
        kwargs["role"] = req.role

    if kwargs:
        db.update_user(user_id, **kwargs)

    updated = db.get_user(user_id)
    assert updated is not None
    return _user_to_admin_summary(updated)


@app.post("/api/v1/admin/users/{user_id}/invite")
async def reissue_admin_user_invite(
    user_id: str,
    authorization: Optional[str] = Header(None),
) -> InviteResponse:
    identity = _require_owner(authorization)
    target = db.get_user(user_id)
    if target is None:
        raise HTTPException(status_code=404, detail={"error": "not_found", "message": "User not found"})
    if target.status == "disabled":
        raise HTTPException(status_code=400, detail={"error": "user_disabled", "message": "Cannot issue invite for disabled user"})

    # Invalidate existing unused invites
    db.invalidate_user_invites(user_id)

    # Issue new 8-char invite
    code = generate_invite_code(8)
    code_h = hash_token(code)
    expires_dt = datetime.now(timezone.utc) + timedelta(hours=24)
    expires_at = expires_dt.isoformat()
    db.create_invite(user_id, code_h, created_by=identity.user_id, expires_at=expires_at, quota_bytes=target.quota_bytes)

    ips = get_local_ipv4_addresses()
    local_ip = ips[0] if ips else "127.0.0.1"
    port = config["port"]
    tls_fp = getattr(app.state, "tls_fingerprint", "")
    qr_payload = f"piphotos://pair?host={local_ip}&port={port}&tls_fp={tls_fp}&code={code}"

    return InviteResponse(
        invite_code=code,
        expires_at=expires_at,
        qr_payload=qr_payload,
    )


@app.delete("/api/v1/admin/users/{user_id}")
async def delete_admin_user(
    user_id: str,
    delete_files: bool = Query(False),
    authorization: Optional[str] = Header(None),
) -> dict:
    _require_owner(authorization)
    target = db.get_user(user_id)
    if target is None:
        raise HTTPException(status_code=404, detail={"error": "not_found", "message": "User not found"})

    # Last active owner protection
    if target.role == "owner" and target.status == "active" and db.count_active_owners() <= 1:
        raise HTTPException(status_code=400, detail={
            "error": "last_owner_protected",
            "message": "Cannot delete the last active owner",
        })

    user_slug = target.slug

    # 1. DB delete first in transaction
    db.delete_user(user_id)

    # 2. File delete / archive, reporting file errors loudly
    file_warning = None
    try:
        if delete_files:
            storage.safe_delete_user_dir(user_slug)
        else:
            storage.archive_user_dir(user_slug)
    except Exception as e:
        logger.error("Failed to delete/archive files for user %s (%s): %s", user_id, user_slug, e, exc_info=True)
        file_warning = str(e)

    res = {"status": "deleted", "user_id": user_id, "files_deleted": delete_files}
    if file_warning:
        res["file_warning"] = file_warning
    return res


@app.get("/api/v1/admin/users/{user_id}/devices")
async def list_admin_user_devices(
    user_id: str,
    authorization: Optional[str] = Header(None),
) -> list[DeviceSummary]:
    _require_owner(authorization)
    target = db.get_user(user_id)
    if target is None:
        raise HTTPException(status_code=404, detail={"error": "not_found", "message": "User not found"})
    devs = db.list_devices(user_id)
    return [
        DeviceSummary(
            id=d.id,
            name=d.name,
            model=d.model,
            slug=d.slug,
            created_at=d.created_at,
            last_seen_at=d.last_seen_at,
        )
        for d in devs
    ]


@app.delete("/api/v1/admin/devices/{device_id}")
async def delete_admin_device(
    device_id: str,
    authorization: Optional[str] = Header(None),
) -> dict:
    _require_owner(authorization)
    dev = db.get_device(device_id)
    if dev is None:
        raise HTTPException(status_code=404, detail={"error": "not_found", "message": "Device not found"})
    db.delete_device(device_id)
    return {"status": "revoked", "device_id": device_id}


@app.post("/api/v1/admin/pairing/open")
async def open_admin_pairing(
    authorization: Optional[str] = Header(None),
) -> OpenPairingResponse:
    _require_owner(authorization)
    code = pairing.open_pairing(duration_minutes=10)
    return OpenPairingResponse(code=code, expires_in_seconds=600)


@app.get("/api/v1/admin/overview")
async def get_admin_overview(
    authorization: Optional[str] = Header(None),
) -> AdminOverviewResponse:
    _require_owner(authorization)
    disk_usage = shutil.disk_usage(storage.root)
    reserved = int(disk_usage.total * get_disk_reserve_fraction())

    users = db.list_users()
    allocated = db.get_allocated_quota_total()
    over_allocated = allocated > disk_usage.total

    user_overviews = [
        UserOverview(
            id=u.id,
            name=u.display_name or u.name,
            role=u.role,
            quota_bytes=u.quota_bytes,
            used_bytes=db.user_storage_used(u.id),
            file_count=db.user_file_count(u.id),
        )
        for u in users
    ]

    return AdminOverviewResponse(
        disk=DiskInfo(
            total_bytes=disk_usage.total,
            free_bytes=disk_usage.free,
            reserved_bytes=reserved,
        ),
        allocated_quota_total=allocated,
        over_allocated=over_allocated,
        users=user_overviews,
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
