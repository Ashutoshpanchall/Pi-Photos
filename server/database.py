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

from storage import slugify


# ---------------------------------------------------------------------------
# Row dataclasses (read-only projections)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class UserRow:
    id: str
    name: str
    display_name: str
    slug: str
    role: str  # owner | member | guest
    quota_bytes: int
    status: str  # active | disabled | pending_deletion
    created_at: str
    organise_by_date: bool = False
    disabled_at: Optional[str] = None
    scheduled_purge_at: Optional[str] = None

    @property
    def is_active(self) -> bool:
        return self.status == "active"


@dataclass(frozen=True)
class DeviceRow:
    id: str
    user_id: str
    name: str
    model: str
    slug: str
    created_at: str
    last_seen_at: Optional[str] = None


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
    role: str = "backup"


@dataclass(frozen=True)
class InviteRow:
    id: str
    user_id: str
    code_hash: str
    created_by: str
    expires_at: str
    used_at: Optional[str]
    created_at: str
    role: str = "member"
    quota_bytes: int = 0
    token_hash: Optional[str] = None
    redeemed_by: Optional[str] = None


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
    id               TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    role             TEXT NOT NULL DEFAULT 'member',
    quota_bytes      INTEGER NOT NULL DEFAULT 0,
    is_active        INTEGER NOT NULL DEFAULT 1,
    created_at       TEXT NOT NULL,
    organise_by_date INTEGER NOT NULL DEFAULT 0
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
CREATE INDEX IF NOT EXISTS idx_files_user_id ON files(user_id, id);

CREATE TABLE IF NOT EXISTS tokens (
    token_hash TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users(id),
    device_id  TEXT NOT NULL REFERENCES devices(id),
    created_at TEXT NOT NULL,
    role       TEXT NOT NULL DEFAULT 'backup'
);

CREATE TABLE IF NOT EXISTS invites (
    id          TEXT PRIMARY KEY,
    token_hash  TEXT NOT NULL,
    role        TEXT NOT NULL DEFAULT 'member',
    quota_bytes INTEGER NOT NULL DEFAULT 0,
    created_by  TEXT REFERENCES users(id),
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
        # Initialise schema and run migrations.
        with self._cursor() as cur:
            cur.executescript(_SCHEMA)
            for col, definition in [
                ("display_name", "TEXT"),
                ("slug", "TEXT"),
                ("status", "TEXT NOT NULL DEFAULT 'active'"),
                ("organise_by_date", "INTEGER NOT NULL DEFAULT 0"),
                ("disabled_at", "TEXT"),
                ("scheduled_purge_at", "TEXT"),
            ]:
                try:
                    cur.execute(f"ALTER TABLE users ADD COLUMN {col} {definition}")
                except Exception:
                    pass

            try:
                cur.execute("ALTER TABLE devices ADD COLUMN last_seen_at TEXT")
            except Exception:
                pass

            try:
                cur.execute("ALTER TABLE invites ADD COLUMN user_id TEXT")
            except Exception:
                pass
            try:
                cur.execute("ALTER TABLE invites ADD COLUMN code_hash TEXT")
            except Exception:
                pass
            try:
                cur.execute("ALTER TABLE invites ADD COLUMN used_at TEXT")
            except Exception:
                pass

            try:
                cur.execute("ALTER TABLE tokens ADD COLUMN role TEXT NOT NULL DEFAULT 'backup'")
                cur.execute("""
                    UPDATE tokens SET role = 'manage'
                    WHERE rowid IN (
                        SELECT MIN(t.rowid) FROM tokens t
                        JOIN users u ON t.user_id = u.id
                        WHERE u.role = 'owner'
                        GROUP BY t.user_id
                    )
                """)
            except Exception:
                pass

            # Allow created_by to be NULL when creating user is deleted
            try:
                cur.execute("PRAGMA table_info(invites)")
                info = {r["name"]: r["notnull"] for r in cur.fetchall()}
                if info.get("created_by") == 1:
                    cur.execute("""
                        CREATE TABLE invites_temp (
                            id          TEXT PRIMARY KEY,
                            user_id     TEXT,
                            code_hash   TEXT,
                            token_hash  TEXT,
                            role        TEXT NOT NULL DEFAULT 'member',
                            quota_bytes INTEGER NOT NULL DEFAULT 0,
                            created_by  TEXT REFERENCES users(id),
                            expires_at  TEXT NOT NULL,
                            used_at     TEXT,
                            redeemed_by TEXT,
                            created_at  TEXT NOT NULL
                        )
                    """)
                    cur.execute("INSERT INTO invites_temp SELECT id, user_id, code_hash, token_hash, role, quota_bytes, created_by, expires_at, used_at, redeemed_by, created_at FROM invites")
                    cur.execute("DROP TABLE invites")
                    cur.execute("ALTER TABLE invites_temp RENAME TO invites")
            except Exception:
                pass

            # Backfill existing users
            cur.execute("SELECT id, name, display_name, slug, is_active, status FROM users")
            rows = cur.fetchall()
            for r in rows:
                d_name = r["display_name"] or r["name"]
                slug = r["slug"]
                if not slug:
                    slug = slugify(d_name)
                    cur.execute("SELECT COUNT(*) FROM users WHERE slug = ? AND id != ?", (slug, r["id"]))
                    if cur.fetchone()[0] > 0:
                        slug = f"{slug}-{r['id'][:4]}"
                status = r["status"] if r["status"] else ("active" if r["is_active"] else "disabled")
                cur.execute(
                    "UPDATE users SET display_name = ?, slug = ?, status = ? WHERE id = ?",
                    (d_name, slug, status, r["id"]),
                )

            try:
                cur.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_slug ON users(slug)")
            except Exception:
                pass

    # -- connection management -----------------------------------------------

    def _conn(self) -> sqlite3.Connection:
        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = sqlite3.connect(self._db_path, check_same_thread=False)
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
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

    def _row_to_user(self, row: sqlite3.Row) -> UserRow:
        keys = row.keys()
        d_name = row["display_name"] if "display_name" in keys and row["display_name"] else row["name"]
        slug = row["slug"] if "slug" in keys and row["slug"] else slugify(d_name)
        status = row["status"] if "status" in keys and row["status"] else ("active" if row["is_active"] else "disabled")
        organise = bool(row["organise_by_date"]) if "organise_by_date" in keys else False
        disabled_at = row["disabled_at"] if "disabled_at" in keys else None
        scheduled_purge_at = row["scheduled_purge_at"] if "scheduled_purge_at" in keys else None
        return UserRow(
            id=row["id"],
            name=d_name,
            display_name=d_name,
            slug=slug,
            role=row["role"],
            quota_bytes=row["quota_bytes"],
            status=status,
            created_at=row["created_at"],
            organise_by_date=organise,
            disabled_at=disabled_at,
            scheduled_purge_at=scheduled_purge_at,
        )

    def create_user(
        self,
        name: str,
        role: str = "member",
        quota_bytes: int = 0,
        organise_by_date: bool = False,
        display_name: Optional[str] = None,
        slug: Optional[str] = None,
        status: str = "active",
    ) -> UserRow:
        uid = f"usr_{uuid.uuid4().hex[:12]}"
        now = self._now()
        disp_name = display_name or name
        user_slug = slug or slugify(disp_name)
        with self._cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM users WHERE slug = ?", (user_slug,))
            if cur.fetchone()[0] > 0:
                user_slug = f"{user_slug}-{uuid.uuid4().hex[:4]}"

            cur.execute(
                "INSERT INTO users (id, name, display_name, slug, role, quota_bytes, status, is_active, created_at, organise_by_date) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (uid, disp_name, disp_name, user_slug, role, quota_bytes, status, 1 if status == "active" else 0, now, 1 if organise_by_date else 0),
            )
        return UserRow(uid, disp_name, disp_name, user_slug, role, quota_bytes, status, now, organise_by_date)

    def get_user(self, user_id: str) -> Optional[UserRow]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM users WHERE id = ?", (user_id,))
            row = cur.fetchone()
            if row is None:
                return None
            return self._row_to_user(row)

    def get_user_by_slug(self, slug: str) -> Optional[UserRow]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM users WHERE slug = ?", (slug,))
            row = cur.fetchone()
            if row is None:
                return None
            return self._row_to_user(row)

    def list_users(self) -> list[UserRow]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM users ORDER BY created_at")
            return [self._row_to_user(r) for r in cur.fetchall()]

    def count_active_owners(self) -> int:
        with self._cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM users WHERE role = 'owner' AND status = 'active'")
            return cur.fetchone()[0]

    def update_user(self, user_id: str, **kwargs: object) -> None:
        allowed = {"name", "display_name", "slug", "role", "quota_bytes", "status", "is_active", "organise_by_date"}
        parts: list[str] = []
        vals: list[object] = []
        for k, v in kwargs.items():
            if k not in allowed:
                raise ValueError(f"Cannot update field: {k}")
            parts.append(f"{k} = ?")
            if k == "organise_by_date":
                vals.append(1 if v else 0)
            elif k == "status":
                vals.append(v)
                parts.append("is_active = ?")
                vals.append(1 if v == "active" else 0)
            elif k == "is_active":
                vals.append(1 if v else 0)
                parts.append("status = ?")
                vals.append("active" if v else "disabled")
            elif k == "display_name":
                vals.append(v)
                parts.append("name = ?")
                vals.append(v)
            elif k == "name":
                vals.append(v)
                parts.append("display_name = ?")
                vals.append(v)
            else:
                vals.append(v)
        if not parts:
            return
        vals.append(user_id)
        with self._cursor() as cur:
            cur.execute(
                f"UPDATE users SET {', '.join(parts)} WHERE id = ?", vals
            )

    def schedule_user_deletion(self, user_id: str, grace_days: int = 7) -> Optional[UserRow]:
        """Disable user immediately and schedule permanent deletion after grace_days."""
        from datetime import datetime, timezone, timedelta
        now = datetime.now(timezone.utc)
        disabled_at = now.isoformat()
        scheduled_purge_at = (now + timedelta(days=grace_days)).isoformat()
        with self._cursor() as cur:
            cur.execute(
                "UPDATE users SET status = 'pending_deletion', is_active = 0, disabled_at = ?, scheduled_purge_at = ? WHERE id = ?",
                (disabled_at, scheduled_purge_at, user_id),
            )
            # Revoke all active tokens immediately so removed user cannot make calls
            cur.execute("DELETE FROM tokens WHERE user_id = ?", (user_id,))
        return self.get_user(user_id)

    def restore_user(self, user_id: str) -> Optional[UserRow]:
        """Restore user from pending_deletion or disabled state back to active."""
        with self._cursor() as cur:
            cur.execute(
                "UPDATE users SET status = 'active', is_active = 1, disabled_at = NULL, scheduled_purge_at = NULL WHERE id = ?",
                (user_id,),
            )
        return self.get_user(user_id)

    def get_users_pending_purge(self, before_iso: Optional[str] = None) -> List[UserRow]:
        """Return all users with pending deletion whose grace period has expired."""
        if before_iso is None:
            from datetime import datetime, timezone
            before_iso = datetime.now(timezone.utc).isoformat()
        with self._cursor() as cur:
            cur.execute(
                "SELECT * FROM users WHERE status = 'pending_deletion' AND scheduled_purge_at IS NOT NULL AND scheduled_purge_at <= ?",
                (before_iso,),
            )
            return [self._row_to_user(r) for r in cur.fetchall()]

    def delete_user(self, user_id: str) -> None:
        with self._cursor() as cur:
            cur.execute("DELETE FROM files WHERE user_id = ?", (user_id,))
            cur.execute("DELETE FROM tokens WHERE user_id = ?", (user_id,))
            cur.execute("DELETE FROM devices WHERE user_id = ?", (user_id,))
            cur.execute("DELETE FROM invites WHERE user_id = ?", (user_id,))
            cur.execute("UPDATE invites SET created_by = NULL WHERE created_by = ?", (user_id,))
            cur.execute("DELETE FROM partial_uploads WHERE user_id = ?", (user_id,))
            cur.execute("DELETE FROM users WHERE id = ?", (user_id,))

    # -- devices -------------------------------------------------------------

    def _row_to_device(self, row: sqlite3.Row) -> DeviceRow:
        keys = row.keys()
        last_seen = row["last_seen_at"] if "last_seen_at" in keys else None
        return DeviceRow(
            id=row["id"],
            user_id=row["user_id"],
            name=row["name"],
            model=row["model"],
            slug=row["slug"],
            created_at=row["created_at"],
            last_seen_at=last_seen,
        )

    def create_device(
        self, user_id: str, name: str, model: str, slug: str,
    ) -> DeviceRow:
        did = f"dev_{uuid.uuid4().hex[:12]}"
        now = self._now()
        base_slug = slug
        with self._cursor() as cur:
            cur.execute("SELECT slug FROM devices WHERE user_id = ?", (user_id,))
            existing = {r[0] for r in cur.fetchall()}
            final_slug = base_slug
            suffix = 2
            while final_slug in existing:
                final_slug = f"{base_slug}-{suffix}"
                suffix += 1

            cur.execute(
                "INSERT INTO devices (id, user_id, name, model, slug, created_at, last_seen_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (did, user_id, name, model, final_slug, now, now),
            )
        return DeviceRow(did, user_id, name, model, final_slug, now, now)

    def get_device(self, device_id: str) -> Optional[DeviceRow]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM devices WHERE id = ?", (device_id,))
            row = cur.fetchone()
            if row is None:
                return None
            return self._row_to_device(row)

    def list_devices(self, user_id: str) -> list[DeviceRow]:
        with self._cursor() as cur:
            cur.execute(
                "SELECT * FROM devices WHERE user_id = ? ORDER BY created_at",
                (user_id,),
            )
            return [self._row_to_device(r) for r in cur.fetchall()]

    def update_device_last_seen(self, device_id: str) -> None:
        with self._cursor() as cur:
            cur.execute(
                "UPDATE devices SET last_seen_at = ? WHERE id = ?",
                (self._now(), device_id),
            )

    def delete_device(self, device_id: str) -> None:
        with self._cursor() as cur:
            cur.execute("DELETE FROM tokens WHERE device_id = ?", (device_id,))
            cur.execute("DELETE FROM devices WHERE id = ?", (device_id,))

    def get_user_device_count(self, user_id: str) -> int:
        with self._cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM devices WHERE user_id = ?", (user_id,))
            return cur.fetchone()[0]

    def get_user_last_seen(self, user_id: str) -> Optional[str]:
        with self._cursor() as cur:
            cur.execute("SELECT MAX(last_seen_at) FROM devices WHERE user_id = ?", (user_id,))
            row = cur.fetchone()
            return row[0] if row and row[0] else None

    def get_allocated_quota_total(self) -> int:
        with self._cursor() as cur:
            cur.execute("SELECT COALESCE(SUM(quota_bytes), 0) FROM users WHERE quota_bytes > 0 AND status = 'active'")
            return cur.fetchone()[0]

    # -- tokens --------------------------------------------------------------

    def store_token(self, token_hash: str, user_id: str, device_id: str, role: Optional[str] = None) -> None:
        if role is None:
            user = self.get_user(user_id)
            if user and user.role == "owner" and self.count_user_manage_devices(user_id) == 0:
                role = "manage"
            else:
                role = "backup"
        with self._cursor() as cur:
            cur.execute(
                "INSERT INTO tokens (token_hash, user_id, device_id, created_at, role) "
                "VALUES (?, ?, ?, ?, ?)",
                (token_hash, user_id, device_id, self._now(), role),
            )

    def lookup_token(self, token_hash: str) -> Optional[TokenRow]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM tokens WHERE token_hash = ?", (token_hash,))
            row = cur.fetchone()
            if row is None:
                return None
            role = row["role"] if "role" in row.keys() and row["role"] else "backup"
            return TokenRow(
                row["token_hash"], row["user_id"],
                row["device_id"], row["created_at"],
                role=role,
            )

    def get_device_token_role(self, device_id: str) -> Optional[str]:
        with self._cursor() as cur:
            cur.execute("SELECT role FROM tokens WHERE device_id = ? LIMIT 1", (device_id,))
            row = cur.fetchone()
            return row[0] if row and row[0] else None

    def set_device_token_role(self, device_id: str, role: str) -> None:
        with self._cursor() as cur:
            cur.execute("UPDATE tokens SET role = ? WHERE device_id = ?", (role, device_id))

    def count_user_manage_devices(self, user_id: str) -> int:
        with self._cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM tokens WHERE user_id = ? AND role = 'manage'", (user_id,))
            return cur.fetchone()[0]

    def list_user_devices(self, user_id: str) -> list[dict]:
        with self._cursor() as cur:
            cur.execute("""
                SELECT d.id, d.name, d.model, d.slug, d.created_at, d.last_seen_at,
                       COALESCE(t.role, 'backup') as role
                FROM devices d
                LEFT JOIN tokens t ON d.id = t.device_id
                WHERE d.user_id = ?
                ORDER BY d.created_at ASC
            """, (user_id,))
            rows = cur.fetchall()
            return [
                {
                    "id": r["id"],
                    "name": r["name"],
                    "model": r["model"],
                    "slug": r["slug"],
                    "created_at": r["created_at"],
                    "last_seen_at": r["last_seen_at"],
                    "role": r["role"],
                }
                for r in rows
            ]

    # -- files ---------------------------------------------------------------

    def file_exists_for_user(self, user_id: str, sha256: str) -> bool:
        with self._cursor() as cur:
            cur.execute(
                "SELECT 1 FROM files WHERE user_id = ? AND sha256 = ? LIMIT 1",
                (user_id, sha256),
            )
            return cur.fetchone() is not None

    def file_exists_at_path(self, user_id: str, device_id: str, folder: str, relative_path: str) -> bool:
        with self._cursor() as cur:
            cur.execute(
                "SELECT 1 FROM files WHERE user_id = ? AND device_id = ? AND folder = ? AND relative_path = ? LIMIT 1",
                (user_id, device_id, folder, relative_path),
            )
            return cur.fetchone() is not None

    def get_first_file_by_hash(self, user_id: str, sha256: str) -> Optional[FileRow]:
        with self._cursor() as cur:
            cur.execute(
                "SELECT * FROM files WHERE user_id = ? AND sha256 = ? LIMIT 1",
                (user_id, sha256),
            )
            row = cur.fetchone()
            if row is None:
                return None
            return FileRow(
                row["id"], row["user_id"], row["device_id"], row["folder"],
                row["relative_path"], row["sha256"], row["size"],
                row["date_taken"], row["uploaded_at"],
            )

    def get_file(self, file_id: int) -> Optional[FileRow]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM files WHERE id = ?", (file_id,))
            row = cur.fetchone()
            if row is None:
                return None
            return FileRow(
                row["id"], row["user_id"], row["device_id"], row["folder"],
                row["relative_path"], row["sha256"], row["size"],
                row["date_taken"], row["uploaded_at"],
            )

    def list_files_filtered(
        self,
        user_id: str,
        device_id: Optional[str] = None,
        folder: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> list[FileRow]:
        query = "SELECT * FROM files WHERE user_id = ?"
        params: list[object] = [user_id]
        if device_id:
            query += " AND device_id = ?"
            params.append(device_id)
        if folder:
            query += " AND folder = ?"
            params.append(folder)
        query += " ORDER BY id DESC LIMIT ? OFFSET ?"
        params.extend([limit, offset])
        with self._cursor() as cur:
            cur.execute(query, params)
            return [
                FileRow(
                    r["id"], r["user_id"], r["device_id"], r["folder"],
                    r["relative_path"], r["sha256"], r["size"],
                    r["date_taken"], r["uploaded_at"],
                )
                for r in cur.fetchall()
            ]

    def check_hashes_exist(self, user_id: str, hashes: list[str]) -> list[str]:
        if not hashes:
            return []
        placeholders = ",".join("?" for _ in hashes)
        with self._cursor() as cur:
            cur.execute(
                f"SELECT DISTINCT sha256 FROM files WHERE user_id = ? AND sha256 IN ({placeholders})",
                [user_id, *hashes],
            )
            return [r["sha256"] for r in cur.fetchall()]

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

    record_file = insert_file

    def list_files(self, user_id: str) -> list[FileRow]:
        return self.list_files_filtered(user_id, limit=5000)

    def get_file_hashes_paginated(
        self,
        user_id: str,
        cursor: int = 0,
        limit: int = 1000,
    ) -> tuple[list[dict], Optional[int], bool]:
        limit = min(max(1, limit), 5000)
        with self._cursor() as cur:
            cur.execute("""
                SELECT id, sha256, size, date_taken
                FROM files
                WHERE user_id = ? AND id > ?
                ORDER BY id ASC
                LIMIT ?
            """, (user_id, cursor, limit + 1))
            rows = cur.fetchall()
            has_more = len(rows) > limit
            items = [
                {
                    "id": r["id"],
                    "sha256": r["sha256"],
                    "size": r["size"],
                    "date_taken": r["date_taken"],
                }
                for r in rows[:limit]
            ]
            next_cursor = items[-1]["id"] if items and has_more else None
            return items, next_cursor, has_more

    def total_storage_used(self) -> int:
        """Returns total physical bytes stored across all users (deduplicated by sha256)."""
        with self._cursor() as cur:
            cur.execute(
                "SELECT COALESCE(SUM(size), 0) FROM "
                "(SELECT sha256, MAX(size) AS size FROM files GROUP BY sha256)"
            )
            return cur.fetchone()[0]

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

    def get_partial_offsets(self, user_id: str, hashes: list[str]) -> dict[str, int]:
        if not hashes:
            return {}
        placeholders = ",".join("?" for _ in hashes)
        with self._cursor() as cur:
            cur.execute(
                f"SELECT sha256, offset FROM partial_uploads WHERE user_id = ? AND sha256 IN ({placeholders})",
                [user_id, *hashes],
            )
            return {r["sha256"]: r["offset"] for r in cur.fetchall()}

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

    def _row_to_invite(self, row: sqlite3.Row) -> InviteRow:
        keys = row.keys()
        u_id = row["user_id"] if "user_id" in keys and row["user_id"] else ""
        c_hash = row["code_hash"] if "code_hash" in keys and row["code_hash"] else row["token_hash"]
        used_at = row["used_at"] if "used_at" in keys else row["redeemed_by"]
        role = row["role"] if "role" in keys else "member"
        quota = row["quota_bytes"] if "quota_bytes" in keys else 0
        return InviteRow(
            id=row["id"],
            user_id=u_id,
            code_hash=c_hash,
            created_by=row["created_by"],
            expires_at=row["expires_at"],
            used_at=used_at,
            created_at=row["created_at"],
            role=role,
            quota_bytes=quota,
            token_hash=c_hash,
            redeemed_by=used_at,
        )

    def create_invite(
        self,
        user_id: str,
        code_hash: str,
        created_by: str,
        expires_at: str,
        role: str = "member",
        quota_bytes: int = 0,
    ) -> InviteRow:
        iid = f"inv_{uuid.uuid4().hex[:12]}"
        now = self._now()
        with self._cursor() as cur:
            cur.execute(
                "INSERT INTO invites "
                "(id, user_id, code_hash, token_hash, role, quota_bytes, created_by, expires_at, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (iid, user_id, code_hash, code_hash, role, quota_bytes, created_by, expires_at, now),
            )
        return InviteRow(
            id=iid,
            user_id=user_id,
            code_hash=code_hash,
            created_by=created_by,
            expires_at=expires_at,
            used_at=None,
            created_at=now,
            role=role,
            quota_bytes=quota_bytes,
            token_hash=code_hash,
            redeemed_by=None,
        )

    def get_invite_by_hash(self, code_hash: str) -> Optional[InviteRow]:
        with self._cursor() as cur:
            cur.execute(
                "SELECT * FROM invites WHERE code_hash = ? OR token_hash = ? LIMIT 1",
                (code_hash, code_hash),
            )
            row = cur.fetchone()
            if row is None:
                return None
            return self._row_to_invite(row)

    def lookup_invite_by_hash(self, token_hash: str) -> Optional[InviteRow]:
        return self.get_invite_by_hash(token_hash)

    def mark_invite_used(self, invite_id: str) -> None:
        now = self._now()
        with self._cursor() as cur:
            cur.execute(
                "UPDATE invites SET used_at = ?, redeemed_by = ? WHERE id = ?",
                (now, now, invite_id),
            )

    def redeem_invite(self, invite_id: str, user_id: str) -> None:
        self.mark_invite_used(invite_id)

    def invalidate_user_invites(self, user_id: str) -> None:
        now = self._now()
        with self._cursor() as cur:
            cur.execute(
                "UPDATE invites SET used_at = 'revoked' WHERE user_id = ? AND used_at IS NULL",
                (user_id,),
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
