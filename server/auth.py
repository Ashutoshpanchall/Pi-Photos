"""Authentication helpers: pairing codes, rate limiting, token creation & verification."""

from __future__ import annotations

import hashlib
import secrets
import threading
import time
from collections import defaultdict
from dataclasses import dataclass
from typing import Optional

from database import Database


@dataclass(frozen=True)
class AuthIdentity:
    """Resolved identity from a valid Bearer token."""
    user_id: str
    device_id: str


class IpRateLimiter:
    """Rate limiter for IP-based endpoint protection (e.g. POST /pair)."""

    def __init__(self, max_requests: int = 10, window_seconds: float = 60.0) -> None:
        self._max_requests = max_requests
        self._window_seconds = window_seconds
        self._lock = threading.Lock()
        self._requests: dict[str, list[float]] = defaultdict(list)

    def is_allowed(self, ip: str) -> bool:
        now = time.time()
        cutoff = now - self._window_seconds
        with self._lock:
            history = self._requests[ip]
            # Prune older entries
            pruned = [t for t in history if t > cutoff]
            if len(pruned) >= self._max_requests:
                self._requests[ip] = pruned
                return False
            pruned.append(now)
            self._requests[ip] = pruned
            return True

    def reset_ip(self, ip: str) -> None:
        with self._lock:
            self._requests.pop(ip, None)


class PairingManager:
    """Generates and validates 6-digit pairing codes with security hardening.

    - 10-minute expiry
    - Invalidation after 5 failed attempts
    - Closes after 1 successful pairing
    - Does NOT auto-generate a new code (requires explicit owner action)
    """

    CODE_LIFETIME_SECONDS = 600  # 10 minutes
    MAX_FAILED_ATTEMPTS = 5

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._code: Optional[str] = None
        self._expires_at: float = 0.0
        self._enabled: bool = True
        self._failed_attempts: int = 0

    @property
    def is_open(self) -> bool:
        with self._lock:
            return (
                self._enabled
                and self._code is not None
                and time.time() < self._expires_at
                and self._failed_attempts < self.MAX_FAILED_ATTEMPTS
            )

    @property
    def failed_attempts(self) -> int:
        with self._lock:
            return self._failed_attempts

    def generate_code(self) -> str:
        """Explicitly open pairing with a new 6-digit code."""
        code = f"{secrets.randbelow(1_000_000):06d}"
        with self._lock:
            self._code = code
            self._expires_at = time.time() + self.CODE_LIFETIME_SECONDS
            self._failed_attempts = 0
            self._enabled = True
        return code

    def validate_code(self, code: str) -> tuple[bool, str]:
        """Validate a pairing code.

        Returns (is_valid, reason).
        On success: code is consumed and pairing is closed.
        On 5 failed attempts: code is invalidated.
        """
        with self._lock:
            if not self._enabled:
                return False, "pairing_closed"
            if self._code is None:
                return False, "pairing_closed"
            if time.time() >= self._expires_at:
                self._code = None
                return False, "code_expired"
            if self._failed_attempts >= self.MAX_FAILED_ATTEMPTS:
                self._code = None
                return False, "too_many_attempts"

            if not secrets.compare_digest(self._code, code):
                self._failed_attempts += 1
                if self._failed_attempts >= self.MAX_FAILED_ATTEMPTS:
                    self._code = None
                    return False, "too_many_attempts"
                return False, "invalid_code"

            # Success: consume code and close pairing until next explicit owner action
            self._code = None
            self._failed_attempts = 0
            return True, "ok"

    def record_failed_attempt(self) -> int:
        with self._lock:
            self._failed_attempts += 1
            if self._failed_attempts >= self.MAX_FAILED_ATTEMPTS:
                self._code = None
            return self._failed_attempts

    def reset_attempts(self) -> None:
        with self._lock:
            self._failed_attempts = 0

    def open_pairing(self, duration_minutes: int = 10) -> str:
        with self._lock:
            code = f"{secrets.randbelow(1_000_000):06d}"
            self._code = code
            self._expires_at = time.time() + (duration_minutes * 60)
            self._failed_attempts = 0
            self._enabled = True
            return code

    def close(self) -> None:
        """Close pairing immediately."""
        with self._lock:
            self._code = None
            self._failed_attempts = 0

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


# ---------------------------------------------------------------------------
# Unambiguous alphabet and Invite code generator (Phase U1.1)
# ---------------------------------------------------------------------------

UNAMBIGUOUS_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"


def generate_invite_code(length: int = 8) -> str:
    """Generate an unambiguous alphanumeric invite code (no 0, O, 1, I)."""
    return "".join(secrets.choice(UNAMBIGUOUS_ALPHABET) for _ in range(length))


# ---------------------------------------------------------------------------
# Global Pairing Limiter across all IPs (Phase U1.1)
# ---------------------------------------------------------------------------

class GlobalPairingLimiter:
    """Global failed-attempt rate limiter across all IPs for /pair.

    After 20 failures in 10 minutes (600 s), all pairing is rejected
    for 10 minutes.
    """
    MAX_FAILURES = 20
    WINDOW_SECONDS = 600.0
    LOCKOUT_DURATION_SECONDS = 600.0

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._failures: list[float] = []
        self._locked_until: float = 0.0

    def is_locked(self) -> tuple[bool, float]:
        """Returns (is_locked, seconds_remaining)."""
        with self._lock:
            now = time.time()
            if now < self._locked_until:
                return True, self._locked_until - now
            return False, 0.0

    def record_failure(self) -> bool:
        """Record a pairing failure across any IP. Returns True if this triggered a lockout."""
        with self._lock:
            now = time.time()
            self._failures = [t for t in self._failures if now - t < self.WINDOW_SECONDS]
            self._failures.append(now)
            if len(self._failures) >= self.MAX_FAILURES:
                self._locked_until = now + self.LOCKOUT_DURATION_SECONDS
                self._failures.clear()
                return True
            return False

    def reset(self) -> None:
        with self._lock:
            self._failures.clear()
            self._locked_until = 0.0


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
