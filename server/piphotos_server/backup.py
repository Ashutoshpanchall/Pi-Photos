"""Database backup, rotation, and restoration helpers (Improvement 8).

Piphotos creates online SQLite snapshots using the sqlite3 backup API without
locking out concurrent readers or writers. Backups are named
piphotos-YYYYMMDD-HHMMSS.db and rotated to retain the 14 most recent copies.
"""

from __future__ import annotations

import logging
import os
import shutil
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

logger = logging.getLogger("piphotos.backup")


def create_database_backup(
    db_path: Path,
    backups_dir: Path,
    max_keep: int = 14,
) -> Path:
    """Create a point-in-time snapshot of the database and prune older backups.

    Args:
        db_path: Path to the active SQLite database file.
        backups_dir: Directory where backups are kept.
        max_keep: Maximum number of backup copies to keep (default 14).

    Returns:
        Path to the newly created backup file.
    """
    db_path = Path(db_path)
    backups_dir = Path(backups_dir)
    backups_dir.mkdir(parents=True, exist_ok=True)

    if not db_path.exists():
        raise FileNotFoundError(f"Database file not found: {db_path}")

    timestamp_str = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")
    dest_path = backups_dir / f"piphotos-{timestamp_str}.db"

    # Avoid collision if multiple backups in the same microsecond
    counter = 1
    while dest_path.exists():
        dest_path = backups_dir / f"piphotos-{timestamp_str}_{counter:03d}.db"
        counter += 1

    temp_path = dest_path.with_suffix(".tmp")

    src_conn = sqlite3.connect(str(db_path))
    dest_conn = sqlite3.connect(str(temp_path))
    try:
        src_conn.backup(dest_conn)
    finally:
        dest_conn.close()
        src_conn.close()

    # Atomically move temp snapshot to final path
    temp_path.replace(dest_path)
    logger.info("Created database backup at %s", dest_path)

    # Rotate backups: keep the most recent max_keep files
    prune_old_backups(backups_dir, max_keep=max_keep)

    return dest_path


def prune_old_backups(backups_dir: Path, max_keep: int = 14) -> list[Path]:
    """Delete backups beyond max_keep, sorting oldest to newest."""
    backups_dir = Path(backups_dir)
    if not backups_dir.exists():
        return []

    backups = sorted(
        [p for p in backups_dir.glob("piphotos-*.db") if p.is_file()],
        key=lambda p: (p.stat().st_mtime, p.name)
    )

    pruned = []
    if len(backups) > max_keep:
        to_delete = backups[:len(backups) - max_keep]
        for p in to_delete:
            try:
                p.unlink()
                pruned.append(p)
                logger.debug("Pruned old backup: %s", p)
            except OSError as e:
                logger.warning("Could not prune backup %s: %s", p, e)

    return pruned


def verify_database_file(db_path: Path) -> bool:
    """Validate that a file is a readable SQLite database with PiPhotos tables."""
    db_path = Path(db_path)
    if not db_path.is_file() or db_path.stat().st_size == 0:
        return False

    try:
        conn = sqlite3.connect(str(db_path))
        cur = conn.cursor()
        cur.execute("PRAGMA integrity_check")
        row = cur.fetchone()
        if not row or row[0].lower() != "ok":
            conn.close()
            return False

        # Verify key tables exist
        cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
        tables = {r[0] for r in cur.fetchall()}
        required = {"users", "devices", "files"}
        conn.close()
        return required.issubset(tables)
    except Exception:
        return False


def restore_database_backup(backup_path: Path, target_db_path: Path) -> Path:
    """Restore database from backup file after safety snapshotting existing DB.

    Args:
        backup_path: Path to the backup file to restore.
        target_db_path: Path to the active database file being replaced.

    Returns:
        Path to the safety snapshot created before restoration.
    """
    backup_path = Path(backup_path)
    target_db_path = Path(target_db_path)

    if not backup_path.exists():
        raise FileNotFoundError(f"Backup file not found: {backup_path}")

    if not verify_database_file(backup_path):
        raise ValueError(f"File {backup_path} is not a valid, intact PiPhotos database backup")

    # Safety snapshot of existing target DB
    safety_snapshot = None
    if target_db_path.exists() and target_db_path.stat().st_size > 0:
        safety_ts = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        safety_snapshot = target_db_path.with_name(f"{target_db_path.name}.pre-restore.{safety_ts}")
        cur_conn = sqlite3.connect(str(target_db_path))
        safety_conn = sqlite3.connect(str(safety_snapshot))
        try:
            cur_conn.backup(safety_conn)
        finally:
            safety_conn.close()
            cur_conn.close()
        logger.info("Created safety snapshot of current database at %s", safety_snapshot)

    # Online transactional restore into target DB (avoids Windows file lock issues)
    backup_conn = sqlite3.connect(str(backup_path))
    target_conn = sqlite3.connect(str(target_db_path))
    try:
        backup_conn.backup(target_conn)
    finally:
        target_conn.close()
        backup_conn.close()

    logger.info("Restored database from %s to %s", backup_path, target_db_path)
    return safety_snapshot or target_db_path
