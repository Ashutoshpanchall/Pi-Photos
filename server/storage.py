"""File storage layer with path sanitisation and CAS deduplication."""

from __future__ import annotations

import os
import re
import shutil
import unicodedata
from pathlib import Path
from typing import Optional


# Characters illegal on Windows (and generally unsafe in paths)
_ILLEGAL_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f\x7f]')

# Maximum length for a single path component
_MAX_COMPONENT_LENGTH = 200


def slugify(name: str) -> str:
    """Create a filesystem-safe slug from a human name.

    - Normalise Unicode to NFC
    - Lowercase
    - Replace whitespace and non-alphanumeric chars (except - and _) with hyphens
    - Collapse multiple hyphens
    - Strip leading/trailing hyphens
    - Cap length
    """
    name = unicodedata.normalize("NFC", name)
    name = name.lower()
    name = re.sub(r"[^a-z0-9_-]", "-", name)
    name = re.sub(r"-{2,}", "-", name)
    name = name.strip("-")
    if not name:
        name = "unnamed"
    return name[:_MAX_COMPONENT_LENGTH]


def sanitise_component(component: str) -> str:
    """Sanitise a single path component.

    Rejects '..' and empty strings. Strips illegal characters and caps length.
    """
    if not component or component in (".", ".."):
        raise ValueError(f"Invalid path component: {component!r}")

    component = unicodedata.normalize("NFC", component)
    component = _ILLEGAL_CHARS.sub("_", component)
    component = component.strip(". ")

    if not component:
        raise ValueError("Path component is empty after sanitisation")

    return component[:_MAX_COMPONENT_LENGTH]


def sanitise_filename(filename: str) -> str:
    """Sanitise a filename, preserving the extension."""
    if not filename:
        raise ValueError("Filename cannot be empty")

    # Reject path separators in filenames
    if "/" in filename or "\\" in filename:
        raise ValueError(f"Filename contains path separator: {filename!r}")

    name = unicodedata.normalize("NFC", filename)
    name = _ILLEGAL_CHARS.sub("_", name)
    name = name.strip(". ")

    if not name:
        name = "unnamed"

    return name[:_MAX_COMPONENT_LENGTH]


class StorageManager:
    """Manages the on-disk storage layout for PiPhotos.

    Layout::

        <root>/users/<user-slug>/devices/<device-slug>/<folder>/<filename>

    Optionally with date organisation::

        <root>/users/<user-slug>/devices/<device-slug>/<folder>/YYYY/MM/<filename>
    """

    def __init__(self, root: str | Path) -> None:
        self._root = Path(root)
        self._root.mkdir(parents=True, exist_ok=True)

    @property
    def root(self) -> Path:
        return self._root

    def user_dir(self, user_slug: str) -> Path:
        return self._root / "users" / slugify(user_slug)

    def device_dir(self, user_slug: str, device_slug: str) -> Path:
        return self.user_dir(user_slug) / "devices" / slugify(device_slug)

    def folder_dir(
        self,
        user_slug: str,
        device_slug: str,
        folder: str,
        date_taken: Optional[int] = None,
        organise_by_date: bool = False,
    ) -> Path:
        folder_name = sanitise_component(folder)
        base = self.device_dir(user_slug, device_slug) / folder_name
        if organise_by_date and date_taken is not None:
            from datetime import datetime, timezone
            dt = datetime.fromtimestamp(date_taken, tz=timezone.utc)
            base = base / f"{dt.year:04d}" / f"{dt.month:02d}"
        return base

    def resolve_filename(
        self,
        target_dir: Path,
        filename: str,
        sha256: str,
    ) -> str:
        """Resolve name collisions within a directory.

        - Same SHA-256 → reuse existing filename (dedup reference)
        - Different SHA-256, same name → append " (1)", " (2)", etc.
        """
        safe_name = sanitise_filename(filename)
        target = target_dir / safe_name

        if not target.exists():
            return safe_name

        # Check if the existing file has the same hash — dedup
        # We rely on the caller to verify this via DB lookup,
        # so here we just handle name collisions.
        stem = Path(safe_name).stem
        suffix = Path(safe_name).suffix

        for i in range(1, 10000):
            candidate = f"{stem} ({i}){suffix}"
            candidate_path = target_dir / candidate
            if not candidate_path.exists():
                return candidate

        raise ValueError(f"Too many name collisions for {safe_name}")

    def part_file_path(self, user_slug: str, sha256: str) -> Path:
        """Path for a partial upload (.part file)."""
        parts_dir = self._root / "parts" / slugify(user_slug)
        parts_dir.mkdir(parents=True, exist_ok=True)
        return parts_dir / f"{sha256}.part"

    def cleanup_part_file(self, user_slug: str, sha256: str) -> None:
        """Remove a partial upload file."""
        part = self.part_file_path(user_slug, sha256)
        if part.exists():
            part.unlink()

    def atomic_move(self, src: Path, dst: Path) -> None:
        """Move a file atomically (rename within same filesystem, else copy+delete)."""
        dst.parent.mkdir(parents=True, exist_ok=True)
        try:
            src.rename(dst)
        except OSError:
            # Cross-filesystem: copy then delete
            shutil.copy2(str(src), str(dst))
            src.unlink()

    def set_mtime(self, file_path: Path, date_taken: Optional[int]) -> None:
        """Set the file's access and modification time to date_taken epoch seconds."""
        if date_taken is not None and date_taken > 0:
            try:
                os.utime(str(file_path), (date_taken, date_taken))
            except OSError:
                pass

    def delete_user_files(self, user_slug: str) -> None:
        """Remove all files for a user."""
        user_dir = self.user_dir(user_slug)
        if user_dir.exists():
            shutil.rmtree(user_dir)
        # Also clean up parts
        parts_dir = self._root / "parts" / slugify(user_slug)
        if parts_dir.exists():
            shutil.rmtree(parts_dir)
