"""Local administrator credential management for loopback CLI commands."""

from __future__ import annotations

import os
import secrets
import stat
import sys
from pathlib import Path
from typing import Optional


def ensure_local_admin_token(token_path: Path) -> str:
    """Read existing local-admin token or generate a new cryptographically secure token.
    Ensures file permissions are mode 0600 (owner read/write only).
    """
    token_path = Path(token_path)
    token_path.parent.mkdir(parents=True, exist_ok=True)

    if token_path.is_file():
        try:
            token = token_path.read_text(encoding="utf-8").strip()
            if len(token) >= 32:
                _set_strict_permissions(token_path)
                return token
        except Exception:
            pass

    # Generate high-entropy 256-bit token
    token = secrets.token_urlsafe(32)
    # Write with restrictive permissions
    if sys.platform != "win32":
        # Create with 0600 atomically
        fd = os.open(str(token_path), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, stat.S_IRUSR | stat.S_IWUSR)
        with open(fd, "w", encoding="utf-8") as f:
            f.write(token)
    else:
        token_path.write_text(token, encoding="utf-8")
        _set_strict_permissions(token_path)

    return token


def read_local_admin_token(token_path: Path) -> Optional[str]:
    """Read local admin token if file exists."""
    token_path = Path(token_path)
    if not token_path.is_file():
        return None
    try:
        token = token_path.read_text(encoding="utf-8").strip()
        return token if token else None
    except Exception:
        return None


def verify_local_admin_token(provided_token: str, token_path: Path) -> bool:
    """Verify provided token against token file using constant-time comparison."""
    expected = read_local_admin_token(token_path)
    if not expected or not provided_token:
        return False
    return secrets.compare_digest(provided_token.strip(), expected.strip())


def _set_strict_permissions(path: Path) -> None:
    """Set 0600 mode on Unix or user-only access on Windows."""
    if sys.platform != "win32":
        try:
            path.chmod(stat.S_IRUSR | stat.S_IWUSR)
        except Exception:
            pass
    else:
        # On Windows, try icacls to restrict to current user if possible
        try:
            import subprocess
            username = os.environ.get("USERNAME")
            if username:
                subprocess.run(
                    ["icacls", str(path), "/inheritance:r", "/grant:r", f"{username}:(R,W)"],
                    capture_output=True,
                    check=False,
                )
        except Exception:
            pass

