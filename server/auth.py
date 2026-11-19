"""Authentication helpers: pairing codes, token creation & verification."""

from __future__ import annotations

import hashlib
import secrets
import threading
import time
from dataclasses import dataclass
from typing import Optional

from database import Database


@dataclass(frozen=True)
class AuthIdentity:
    """Resolved identity from a valid Bearer token."""
    user_id: str
    device_id: str


class PairingManager:
    """Generates and validates 6-digit pairing codes with 10-minute expiry."""

    CODE_LIFETIME_SECONDS = 600  # 10 minutes

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._code: Optional[str] = None
        self._expires_at: float = 0.0
        self._enabled: bool = True

    @property
    def is_open(self) -> bool:
        with self._lock:
            return self._enabled and self._code is not None and time.time() < self._expires_at

    def generate_code(self) -> str:
        code = f"{secrets.randbelow(1_000_000):06d}"
        with self._lock:
            self._code = code
            self._expires_at = time.time() + self.CODE_LIFETIME_SECONDS
        return code

    def validate_code(self, code: str) -> bool:
        with self._lock:
            if not self._enabled:
                return False
            if self._code is None:
                return False
            if time.time() >= self._expires_at:
                self._code = None
                return False
            if not secrets.compare_digest(self._code, code):
                return False
            # Code is single-use: consume it after successful validation
            self._code = None
            return True

    def set_enabled(self, enabled: bool) -> None:
        with self._lock:
            self._enabled = enabled
            if not enabled:
                self._code = None


def create_bearer_token() -> str:
    """Generate a cryptographically secure bearer token."""
    return secrets.token_urlsafe(48)


def hash_token(token: str) -> str:
    """Hash a bearer token for storage."""
    return hashlib.sha256(token.encode()).hexdigest()


def resolve_bearer(db: Database, authorization: Optional[str]) -> Optional[AuthIdentity]:
    """Extract and validate a Bearer token from the Authorization header."""
    if not authorization:
        return None
    parts = authorization.split(" ", 1)
    if len(parts) != 2 or parts[0].lower() != "bearer":
        return None
    token = parts[1]
    token_hash = hash_token(token)
    row = db.lookup_token(token_hash)
    if row is None:
        return None
    return AuthIdentity(user_id=row.user_id, device_id=row.device_id)
