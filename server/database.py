"""SQLite database layer for the PiPhotos storage node.

Keeps RAM tiny: no ORM, plain sqlite3 with WAL mode.
"""

from __future__ import annotations

import hashlib
import sqlite3
import threading
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Generator, Optional


# ---------------------------------------------------------------------------
# Row dataclasses (read-only projections)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class UserRow:
    id: str
    name: str
    role: str  # owner | member | guest
    quota_bytes: int
    is_active: bool
    created_at: str


@dataclass(frozen=True)
class DeviceRow:
    id: str
    user_id: str
    name: str
    model: str
    slug: str
    created_at: str


@dataclass(frozen=True)
class FileRow:
    id: int
    user_id: str
    device_id: str
    folder: str
    relative_path: str
    sha256: str
    size: int
    date_taken: Optional[int]
    uploaded_at: str


@dataclass(frozen=True)
class TokenRow:
    token_hash: str
    user_id: str
    device_id: str
    created_at: str


@dataclass(frozen=True)
class InviteRow:
    id: str
    token_hash: str
    role: str
    quota_bytes: int
    created_by: str
    expires_at: str
    redeemed_by: Optional[str]
    created_at: str


@dataclass(frozen=True)
class AuditRow:
    id: int
    actor_id: str
    action: str
    target_id: Optional[str]
    detail: str
    created_at: str


@dataclass(frozen=True)
class FolderStats:
    folder: str
    item_count: int
    total_bytes: int


# ---------------------------------------------------------------------------
# Database class
# ---------------------------------------------------------------------------

_SCHEMA = """\
CREATE TABLE IF NOT EXISTS users (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    role        TEXT NOT NULL DEFAULT 'member',
    quota_bytes INTEGER NOT NULL DEFAULT 0,
    is_active   INTEGER NOT NULL DEFAULT 1,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id),
    name        TEXT NOT NULL,
    model       TEXT NOT NULL,
    slug        TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS files (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id       TEXT NOT NULL REFERENCES users(id),
    device_id     TEXT NOT NULL REFERENCES devices(id),
    folder        TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    sha256        TEXT NOT NULL,
    size          INTEGER NOT NULL,
    date_taken    INTEGER,
    uploaded_at   TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_files_user_sha ON files(user_id, sha256);
CREATE INDEX IF NOT EXISTS idx_files_user_device_folder
    ON files(user_id, device_id, folder);

CREATE TABLE IF NOT EXISTS tokens (
    token_hash TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users(id),
    device_id  TEXT NOT NULL REFERENCES devices(id),
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS invites (
    id          TEXT PRIMARY KEY,
    token_hash  TEXT NOT NULL UNIQUE,
    role        TEXT NOT NULL DEFAULT 'member',
    quota_bytes INTEGER NOT NULL DEFAULT 0,
    created_by  TEXT NOT NULL REFERENCES users(id),
    expires_at  TEXT NOT NULL,
    redeemed_by TEXT,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    actor_id   TEXT NOT NULL,
    action     TEXT NOT NULL,
    target_id  TEXT,
    detail     TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS partial_uploads (
    sha256     TEXT NOT NULL,
    user_id    TEXT NOT NULL,
    offset     INTEGER NOT NULL DEFAULT 0,
    part_path  TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (sha256, user_id)
);
"""


class Database:
    """Thin wrapper around sqlite3 for the PiPhotos storage node."""

    def __init__(self, db_path: str | Path) -> None:
        self._db_path = str(db_path)
        self._local = threading.local()
        # Initialise schema on the calling thread's connection.
        with self._cursor() as cur:
            cur.executescript(_SCHEMA)

    # -- connection management -----------------------------------------------

    def _conn(self) -> sqlite3.Connection:
        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = sqlite3.connect(self._db_path, check_same_thread=False)
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA foreign_keys=ON")
            conn.row_factory = sqlite3.Row
            self._local.conn = conn
        return conn

    @contextmanager
    def _cursor(self) -> Generator[sqlite3.Cursor, None, None]:
        conn = self._conn()
        cur = conn.cursor()
        try:
            yield cur
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            cur.close()

    # -- helpers -------------------------------------------------------------

    @staticmethod
    def _now() -> str:
        return datetime.now(timezone.utc).isoformat()

    @staticmethod
    def hash_token(token: str) -> str:
        return hashlib.sha256(token.encode()).hexdigest()

    # -- users ---------------------------------------------------------------

    def create_user(self, name: str, role: str, quota_bytes: int) -> UserRow:
        uid = f"usr_{uuid.uuid4().hex[:12]}"
        now = self._now()
        with self._cursor() as cur:
            cur.execute(
                "INSERT INTO users (id, name, role, quota_bytes, created_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (uid, name, role, quota_bytes, now),
            )
        return UserRow(uid, name, role, quota_bytes, True, now)

    def get_user(self, user_id: str) -> Optional[UserRow]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM users WHERE id = ?", (user_id,))
            row = cur.fetchone()
            if row is None:
                return None
            return UserRow(
                row["id"], row["name"], row["role"],
                row["quota_bytes"], bool(row["is_active"]), row["created_at"],
            )

    def list_users(self) -> list[UserRow]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM users ORDER BY created_at")
            return [
                UserRow(r["id"], r["name"], r["role"],
                        r["quota_bytes"], bool(r["is_active"]), r["created_at"])
                for r in cur.fetchall()
            ]

    def update_user(self, user_id: str, **kwargs: object) -> None:
        allowed = {"name", "role", "quota_bytes", "is_active"}
        parts: list[str] = []
        vals: list[object] = []
        for k, v in kwargs.items():
            if k not in allowed:
                raise ValueError(f"Cannot update field: {k}")
            parts.append(f"{k} = ?")
            vals.append(v)
        if not parts:
            return
        vals.append(user_id)
        with self._cursor() as cur:
            cur.execute(
                f"UPDATE users SET {', '.join(parts)} WHERE id = ?", vals
            )

    def delete_user(self, user_id: str) -> None:
        with self._cursor() as cur:
            cur.execute("DELETE FROM files WHERE user_id = ?", (user_id,))
            cur.execute("DELETE FROM tokens WHERE user_id = ?", (user_id,))
            cur.execute("DELETE FROM devices WHERE user_id = ?", (user_id,))
            cur.execute("DELETE FROM users WHERE id = ?", (user_id,))

    # -- devices -------------------------------------------------------------

    def create_device(
        self, user_id: str, name: str, model: str, slug: str,
    ) -> DeviceRow:
        did = f"dev_{uuid.uuid4().hex[:12]}"
        now = self._now()
        with self._cursor() as cur:
            cur.execute(
                "INSERT INTO devices (id, user_id, name, model, slug, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (did, user_id, name, model, slug, now),
            )
        return DeviceRow(did, user_id, name, model, slug, now)

    def get_device(self, device_id: str) -> Optional[DeviceRow]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM devices WHERE id = ?", (device_id,))
            row = cur.fetchone()
            if row is None:
                return None
            return DeviceRow(
                row["id"], row["user_id"], row["name"],
                row["model"], row["slug"], row["created_at"],
            )

    def list_devices(self, user_id: str) -> list[DeviceRow]:
        with self._cursor() as cur:
            cur.execute(
                "SELECT * FROM devices WHERE user_id = ? ORDER BY created_at",
                (user_id,),
            )
            return [
                DeviceRow(r["id"], r["user_id"], r["name"],
                          r["model"], r["slug"], r["created_at"])
                for r in cur.fetchall()
            ]

    # -- tokens --------------------------------------------------------------

    def store_token(self, token_hash: str, user_id: str, device_id: str) -> None:
        with self._cursor() as cur:
            cur.execute(
                "INSERT INTO tokens (token_hash, user_id, device_id, created_at) "
                "VALUES (?, ?, ?, ?)",
                (token_hash, user_id, device_id, self._now()),
            )

    def lookup_token(self, token_hash: str) -> Optional[TokenRow]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM tokens WHERE token_hash = ?", (token_hash,))
            row = cur.fetchone()
            if row is None:
                return None
            return TokenRow(
                row["token_hash"], row["user_id"],
                row["device_id"], row["created_at"],
            )

    # -- files ---------------------------------------------------------------

    def file_exists_for_user(self, user_id: str, sha256: str) -> bool:
        with self._cursor() as cur:
            cur.execute(
                "SELECT 1 FROM files WHERE user_id = ? AND sha256 = ? LIMIT 1",
                (user_id, sha256),
            )
            return cur.fetchone() is not None

    def insert_file(
        self,
        user_id: str,
        device_id: str,
        folder: str,
        relative_path: str,
        sha256: str,
        size: int,
        date_taken: Optional[int],
    ) -> FileRow:
        now = self._now()
        with self._cursor() as cur:
            cur.execute(
                "INSERT INTO files "
                "(user_id, device_id, folder, relative_path, sha256, size, date_taken, uploaded_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (user_id, device_id, folder, relative_path, sha256, size, date_taken, now),
            )
            fid = cur.lastrowid
        return FileRow(fid, user_id, device_id, folder, relative_path,
                        sha256, size, date_taken, now)

    def user_storage_used(self, user_id: str) -> int:
        """Returns total bytes stored by this user (deduplicated by sha256)."""
        with self._cursor() as cur:
            cur.execute(
                "SELECT COALESCE(SUM(size), 0) FROM "
                "(SELECT sha256, MAX(size) AS size FROM files "
                " WHERE user_id = ? GROUP BY sha256)",
                (user_id,),
            )
            return cur.fetchone()[0]

    def user_file_count(self, user_id: str) -> int:
        with self._cursor() as cur:
            cur.execute(
                "SELECT COUNT(DISTINCT sha256) FROM files WHERE user_id = ?",
                (user_id,),
            )
            return cur.fetchone()[0]

    def device_folder_stats(self, user_id: str, device_id: str) -> list[FolderStats]:
        with self._cursor() as cur:
            cur.execute(
                "SELECT folder, COUNT(*) AS cnt, COALESCE(SUM(size), 0) AS total "
                "FROM files WHERE user_id = ? AND device_id = ? "
                "GROUP BY folder ORDER BY folder",
                (user_id, device_id),
            )
            return [
                FolderStats(r["folder"], r["cnt"], r["total"])
                for r in cur.fetchall()
            ]

    # -- partial uploads -----------------------------------------------------

    def get_partial_upload(self, user_id: str, sha256: str) -> Optional[tuple[int, str]]:
        """Returns (offset, part_path) or None."""
        with self._cursor() as cur:
            cur.execute(
                "SELECT offset, part_path FROM partial_uploads "
                "WHERE user_id = ? AND sha256 = ?",
                (user_id, sha256),
            )
            row = cur.fetchone()
            if row is None:
                return None
            return (row["offset"], row["part_path"])

    def upsert_partial_upload(
        self, user_id: str, sha256: str, offset: int, part_path: str,
    ) -> None:
        with self._cursor() as cur:
            cur.execute(
                "INSERT INTO partial_uploads (sha256, user_id, offset, part_path, created_at) "
                "VALUES (?, ?, ?, ?, ?) "
                "ON CONFLICT(sha256, user_id) DO UPDATE SET offset=?, part_path=?",
                (sha256, user_id, offset, part_path, self._now(), offset, part_path),
            )

    def delete_partial_upload(self, user_id: str, sha256: str) -> None:
        with self._cursor() as cur:
            cur.execute(
                "DELETE FROM partial_uploads WHERE user_id = ? AND sha256 = ?",
                (user_id, sha256),
            )

    # -- invites -------------------------------------------------------------

    def create_invite(
        self,
        token_hash: str,
        role: str,
        quota_bytes: int,
        created_by: str,
        expires_at: str,
    ) -> InviteRow:
        iid = f"inv_{uuid.uuid4().hex[:12]}"
        now = self._now()
        with self._cursor() as cur:
            cur.execute(
                "INSERT INTO invites "
                "(id, token_hash, role, quota_bytes, created_by, expires_at, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (iid, token_hash, role, quota_bytes, created_by, expires_at, now),
            )
        return InviteRow(iid, token_hash, role, quota_bytes, created_by,
                         expires_at, None, now)

    def lookup_invite_by_hash(self, token_hash: str) -> Optional[InviteRow]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM invites WHERE token_hash = ?", (token_hash,))
            row = cur.fetchone()
            if row is None:
                return None
            return InviteRow(
                row["id"], row["token_hash"], row["role"],
                row["quota_bytes"], row["created_by"], row["expires_at"],
                row["redeemed_by"], row["created_at"],
            )

    def redeem_invite(self, invite_id: str, user_id: str) -> None:
        with self._cursor() as cur:
            cur.execute(
                "UPDATE invites SET redeemed_by = ? WHERE id = ?",
                (user_id, invite_id),
            )

    # -- audit log -----------------------------------------------------------

    def audit(
        self, actor_id: str, action: str,
        target_id: Optional[str] = None, detail: str = "",
    ) -> None:
        with self._cursor() as cur:
            cur.execute(
                "INSERT INTO audit_log (actor_id, action, target_id, detail, created_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (actor_id, action, target_id, detail, self._now()),
            )

    def list_audit_log(self, limit: int = 100) -> list[AuditRow]:
        with self._cursor() as cur:
            cur.execute(
                "SELECT * FROM audit_log ORDER BY id DESC LIMIT ?", (limit,)
            )
            return [
                AuditRow(r["id"], r["actor_id"], r["action"],
                         r["target_id"], r["detail"], r["created_at"])
                for r in cur.fetchall()
            ]
