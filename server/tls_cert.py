"""Self-signed TLS certificate generation and fingerprinting."""

from __future__ import annotations

import hashlib
import ipaddress
import os
import socket
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Sequence

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID


def get_local_ipv4_addresses() -> list[str]:
    """Find all valid local IPv4 addresses on this host."""
    addresses = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        try:
            s.connect(("8.8.8.8", 80))
            addresses.add(s.getsockname()[0])
        finally:
            s.close()
    except Exception:
        pass

    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                addresses.add(ip)
    except Exception:
        pass

    if not addresses:
        addresses.add("127.0.0.1")

    return list(sorted(addresses))


def generate_self_signed_cert(
    cert_path: Path,
    key_path: Path,
    hostname: str = "piphotos.local",
    extra_ips: Sequence[str] | None = None,
    validity_days: int = 3650,
) -> str:
    """Generate a self-signed EC certificate and return its SHA-256 fingerprint.

    Uses ECDSA P-256 for small key/cert size and fast handshakes
    (important on Pi Zero 2 W).
    Includes the server's LAN IP and hostname in the Subject Alternative Name (SAN).
    Writes key.pem with mode 0600 and the certs directory with 0700.
    """
    key = ec.generate_private_key(ec.SECP256R1())

    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, hostname),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "PiPhotos"),
    ])

    # Build SAN entries
    san_entries: list[x509.GeneralName] = [
        x509.DNSName(hostname),
        x509.DNSName("localhost"),
    ]

    sys_hostname = socket.gethostname()
    if sys_hostname and sys_hostname != hostname:
        san_entries.append(x509.DNSName(sys_hostname))

    san_entries.append(x509.IPAddress(ipaddress.IPv4Address("127.0.0.1")))

    # Collect and add all local LAN IPs
    all_ips = set(get_local_ipv4_addresses())
    if extra_ips:
        all_ips.update(extra_ips)

    for ip_str in sorted(all_ips):
        try:
            san_entries.append(x509.IPAddress(ipaddress.ip_address(ip_str)))
        except ValueError:
            pass

    now = datetime.now(timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now)
        .not_valid_after(now + timedelta(days=validity_days))
        .add_extension(
            x509.SubjectAlternativeName(san_entries),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )

    cert_dir = cert_path.parent
    cert_dir.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(cert_dir, 0o700)
    except OSError:
        pass

    key_bytes = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption(),
    )

    # Write key with 0600 mode
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    if hasattr(os, "O_BINARY"):
        flags |= os.O_BINARY
    fd = os.open(str(key_path), flags, 0o600)
    with open(fd, "wb") as f:
        f.write(key_bytes)
    try:
        os.chmod(key_path, 0o600)
    except OSError:
        pass

    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))

    return get_cert_fingerprint(cert_path)


def get_cert_fingerprint(cert_path: Path) -> str:
    """Return the SHA-256 fingerprint of a PEM certificate, colon-separated."""
    cert_pem = cert_path.read_bytes()
    cert = x509.load_pem_x509_certificate(cert_pem)
    digest = cert.fingerprint(hashes.SHA256())
    return ":".join(f"{b:02X}" for b in digest)
