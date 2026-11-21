"""Command-line interface for the PiPhotos Storage Node server."""

from __future__ import annotations

import argparse
import json
import logging
import os
import shutil
import sys
from pathlib import Path
from typing import Optional

from .paths import resolve_paths, ServerPaths
from .local_admin import ensure_local_admin_token, read_local_admin_token
from tls_cert import generate_self_signed_cert, get_cert_fingerprint, get_local_ipv4_addresses

VERSION = "1.0.0"


def _print_qr(port: int, fingerprint: str, code: str) -> None:
    """Print terminal QR code with pairing payload."""
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

        print("\n  Scan this QR code with the PiPhotos app:")
        matrix = qr.get_matrix()
        for y in range(0, len(matrix), 2):
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
    except Exception:
        pass


def cmd_version(args: argparse.Namespace) -> int:
    """Print package version."""
    print(f"piphotos-server {VERSION}")
    return 0


def cmd_setup(args: argparse.Namespace) -> int:
    """Initialize server data directory, config, certificates, database, and admin token."""
    paths: ServerPaths = resolve_paths(data_dir=args.data_dir, storage_dir=args.storage_dir)
    paths.ensure_dirs()

    # 1. Config file
    cfg = {}
    if paths.config_path.is_file():
        try:
            with open(paths.config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            cfg = {}

    server_name = args.name or cfg.get("name") or os.environ.get("PIPHOTOS_NAME") or "Home Vault"
    port = args.port or cfg.get("port") or int(os.environ.get("PIPHOTOS_PORT", "8443"))
    storage_path = str(paths.storage_dir)

    cfg["name"] = server_name
    cfg["port"] = port
    cfg["storage"] = storage_path
    cfg["database"] = str(paths.db_path)
    cfg["certs"] = str(paths.certs_dir)
    if "default_quota" not in cfg:
        cfg["default_quota"] = 100 * 1024 * 1024 * 1024  # 100 GB

    with open(paths.config_path, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)

    # 2. TLS Certificates
    regen = getattr(args, "regen_cert", False)
    if regen:
        print("WARNING: Regenerating TLS certificate! All previously paired phones must re-pair.")
        fp = generate_self_signed_cert(paths.cert_path, paths.key_path)
    elif not paths.cert_path.is_file() or not paths.key_path.is_file():
        fp = generate_self_signed_cert(paths.cert_path, paths.key_path)
    else:
        fp = get_cert_fingerprint(paths.cert_path)

    # 3. Database initialization
    from database import Database
    db = Database(paths.db_path)
    users = db.list_users()
    if not users:
        owner = db.create_user("Owner", "owner", cfg["default_quota"])
        db.audit("system", "setup", owner.id, "Initialized owner user during setup")

    # 4. Local admin token
    token = ensure_local_admin_token(paths.local_admin_token_path)

    # 5. Server UUID
    if not paths.server_id_path.is_file():
        import uuid
        paths.server_id_path.write_text(str(uuid.uuid4()), encoding="utf-8")

    # Print setup summary
    ips = get_local_ipv4_addresses()
    print("=" * 60)
    print(f"  PiPhotos Storage Node Setup Complete — {server_name}")
    print(f"  Data Directory: {paths.data_dir}")
    print(f"  Storage Directory: {paths.storage_dir}")
    print(f"  Port: {port}")
    print(f"  TLS Fingerprint: {fp}")
    print(f"  Listening Addresses: {', '.join(ips) if ips else '127.0.0.1'}")
    print("=" * 60)
    print("\nRun 'piphotos-server run' to start the server.")
    return 0


def cmd_run(args: argparse.Namespace) -> int:
    """Run the server in the foreground."""
    paths: ServerPaths = resolve_paths(data_dir=args.data_dir)
    paths.ensure_dirs()

    # Pass paths via environment so lifespan and endpoints pick them up seamlessly
    os.environ["PIPHOTOS_DATA_DIR"] = str(paths.data_dir)
    os.environ["PIPHOTOS_CONFIG"] = str(paths.config_path)
    os.environ["PIPHOTOS_STORAGE"] = str(paths.storage_dir)
    os.environ["PIPHOTOS_DB"] = str(paths.db_path)
    os.environ["PIPHOTOS_CERTS"] = str(paths.certs_dir)
    if getattr(args, "port", None):
        os.environ["PIPHOTOS_PORT"] = str(args.port)
    if getattr(args, "host", None):
        os.environ["PIPHOTOS_HOST"] = str(args.host)
    if getattr(args, "name", None):
        os.environ["PIPHOTOS_NAME"] = str(args.name)

    from main import main as server_main
    server_main()
    return 0


def cmd_pair(args: argparse.Namespace) -> int:
    """Open pairing window via loopback local-admin token without restarting server."""
    paths: ServerPaths = resolve_paths(data_dir=args.data_dir)
    token = read_local_admin_token(paths.local_admin_token_path)
    if not token:
        print(f"Error: Local admin token not found at {paths.local_admin_token_path}.")
        print("Ensure 'piphotos-server setup' has been run.")
        return 1

    # Read port from config
    port = 8443
    if paths.config_path.is_file():
        try:
            with open(paths.config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
                port = int(cfg.get("port", 8443))
        except Exception:
            pass

    # Call POST /api/v1/admin/pairing/open on loopback
    import httpx
    url = f"https://127.0.0.1:{port}/api/v1/admin/pairing/open"
    headers = {"Authorization": f"Bearer {token}"}
    try:
        resp = httpx.post(url, headers=headers, verify=False, timeout=5.0)
    except httpx.ConnectError:
        print(f"Error: Could not connect to PiPhotos server on https://127.0.0.1:{port}.")
        print("Is the server currently running? Start it with 'piphotos-server run' or 'piphotos-server start'.")
        return 1
    except Exception as e:
        print(f"Error connecting to server: {e}")
        return 1

    if resp.status_code != 200:
        print(f"Error from server (HTTP {resp.status_code}): {resp.text}")
        return 1

    data = resp.json()
    code = data.get("code")
    expires = data.get("expires_in_seconds", 600)

    fp = ""
    if paths.cert_path.is_file():
        fp = get_cert_fingerprint(paths.cert_path)

    ips = get_local_ipv4_addresses()
    primary_ip = ips[0] if ips else "127.0.0.1"

    print("=" * 60)
    print("  PiPhotos Storage Node — Pairing Opened")
    print("=" * 60)
    print(f"\n  PAIRING CODE:  {code}\n")
    print(f"  Valid for {expires // 60} minutes (or until 1 successful pairing).")
    print(f"  Server URL:    https://{primary_ip}:{port}")
    if fp:
        print(f"  Fingerprint:   {fp}")
    print("=" * 60)

    if fp:
        _print_qr(port, fp, code)

    return 0


def cmd_status(args: argparse.Namespace) -> int:
    """Check running status, URL, users, and storage capacity."""
    paths: ServerPaths = resolve_paths(data_dir=args.data_dir)
    port = 8443
    name = "Home Vault"
    if paths.config_path.is_file():
        try:
            with open(paths.config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
                port = int(cfg.get("port", 8443))
                name = cfg.get("name", "Home Vault")
        except Exception:
            pass

    import httpx
    is_running = False
    info_data = {}
    try:
        resp = httpx.get(f"https://127.0.0.1:{port}/api/v1/info", verify=False, timeout=3.0)
        if resp.status_code == 200:
            is_running = True
            info_data = resp.json()
    except Exception:
        pass

    # Disk usage
    disk_free_gb = 0.0
    disk_total_gb = 0.0
    if paths.storage_dir.exists():
        usage = shutil.disk_usage(paths.storage_dir)
        disk_free_gb = usage.free / (1024 ** 3)
        disk_total_gb = usage.total / (1024 ** 3)

    ips = get_local_ipv4_addresses()
    print("=" * 60)
    print(f"  PiPhotos Storage Node Status — {name}")
    print("=" * 60)
    print(f"  Status:          {'RUNNING' if is_running else 'STOPPED'}")
    print(f"  Version:         {info_data.get('version', VERSION)}")
    print(f"  Data Directory:  {paths.data_dir}")
    print(f"  Storage Root:    {paths.storage_dir}")
    print(f"  Port:            {port}")
    print(f"  Disk Capacity:   {disk_free_gb:.1f} GB free of {disk_total_gb:.1f} GB")

    if is_running:
        urls = [f"https://{ip}:{port}" for ip in ips] or [f"https://127.0.0.1:{port}"]
        print(f"  LAN URLs:        {', '.join(urls)}")
        print(f"  Pairing Open:    {info_data.get('pairing_open', False)}")
    else:
        print("  Hint:            Start the server with 'piphotos-server run' or the installed service.")

    print("=" * 60)
    return 0 if is_running else 3


def cmd_info(args: argparse.Namespace) -> int:
    """Print server identification, URLs, TLS fingerprint, and terminal QR code."""
    paths: ServerPaths = resolve_paths(data_dir=args.data_dir)
    port = 8443
    name = "Home Vault"
    if paths.config_path.is_file():
        try:
            with open(paths.config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
                port = int(cfg.get("port", 8443))
                name = cfg.get("name", "Home Vault")
        except Exception:
            pass

    fp = ""
    if paths.cert_path.is_file():
        fp = get_cert_fingerprint(paths.cert_path)

    ips = get_local_ipv4_addresses()
    urls = [f"https://{ip}:{port}" for ip in ips] or [f"https://127.0.0.1:{port}"]

    print("=" * 60)
    print(f"  PiPhotos Storage Node Info — {name}")
    print("=" * 60)
    print(f"  Port:            {port}")
    print(f"  TLS Fingerprint: {fp or '(certificate not generated)'}")
    print(f"  LAN URLs:        {', '.join(urls)}")
    print("=" * 60)

    if fp:
        _print_qr(port, fp, "")

    return 0


def build_parser() -> argparse.ArgumentParser:
    """Build command-line parser with all subcommands."""
    parser = argparse.ArgumentParser(
        prog="piphotos-server",
        description="PiPhotos self-hosted photo backup server CLI",
    )
    subparsers = parser.add_subparsers(dest="subcommand", help="Available subcommands")

    # setup
    p_setup = subparsers.add_parser("setup", help="Initial setup or update of data directory and configuration")
    p_setup.add_argument("--yes", "-y", action="store_true", help="Accept default choices without prompts")
    p_setup.add_argument("--data-dir", type=str, default=None, help="Root directory for configs, database, and certs")
    p_setup.add_argument("--storage-dir", type=str, default=None, help="Root directory for photo storage files")
    p_setup.add_argument("--port", type=int, default=None, help="Port to listen on (default: 8443)")
    p_setup.add_argument("--name", type=str, default=None, help="Server display name")
    p_setup.add_argument("--regen-cert", action="store_true", help="Force regenerating TLS certificate (forces phones to re-pair)")

    # run
    p_run = subparsers.add_parser("run", help="Run the server in foreground")
    p_run.add_argument("--data-dir", type=str, default=None, help="Data directory")
    p_run.add_argument("--host", type=str, default="0.0.0.0", help="Host interface to bind to")
    p_run.add_argument("--port", type=int, default=None, help="Port to listen on")
    p_run.add_argument("--name", type=str, default=None, help="Server display name")

    # pair
    p_pair = subparsers.add_parser("pair", help="Open a 10-minute pairing window and display code + QR")
    p_pair.add_argument("--data-dir", type=str, default=None, help="Data directory")

    # status
    p_status = subparsers.add_parser("status", help="Show server running status, URLs, users, and disk space")
    p_status.add_argument("--data-dir", type=str, default=None, help="Data directory")

    # info
    p_info = subparsers.add_parser("info", help="Display server connection banner, fingerprint, and QR code")
    p_info.add_argument("--data-dir", type=str, default=None, help="Data directory")

    # version
    subparsers.add_parser("version", help="Show server software version")

    return parser


def main(args: Optional[list[str]] = None) -> int:
    """CLI entrypoint."""
    parser = build_parser()
    parsed = parser.parse_args(args)

    if not parsed.subcommand:
        parser.print_help()
        return 0

    handlers = {
        "setup": cmd_setup,
        "run": cmd_run,
        "pair": cmd_pair,
        "status": cmd_status,
        "info": cmd_info,
        "version": cmd_version,
    }

    handler = handlers.get(parsed.subcommand)
    if handler:
        return handler(parsed)

    parser.print_help()
    return 1


if __name__ == "__main__":
    sys.exit(main())

