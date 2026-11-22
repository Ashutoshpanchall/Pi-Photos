"""Shared Python Control Layer for PiPhotos Server.

Provides a unified programmatic control, management, and diagnostics interface
for both the command-line interface (CLI) and native desktop GUI.

Handles:
- Server status & process inspection
- Storage allocation & truthful capacity calculation
- Pairing code generation & QR payload creation
- User management (list, invite, update quota, remove)
- Device management (list, revoke)
- Recent uploads activity feed
- Health diagnostics via ServerDoctor
- Service lifecycle management (start, stop, restart, install, uninstall)
"""

from __future__ import annotations

import json
import logging
import os
import shutil
import socket
import sys
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Optional, List, Dict, Any, Tuple

from .paths import resolve_paths, ServerPaths
from .local_admin import read_local_admin_token, ensure_local_admin_token
from .doctor import ServerDoctor, CheckResult
from .service import (
    start_service as svc_start,
    stop_service as svc_stop,
    restart_service as svc_restart,
    install_service as svc_install,
    uninstall_service as svc_uninstall,
)
from tls_cert import get_local_ipv4_addresses, get_cert_fingerprint

logger = logging.getLogger("piphotos_server.control")


class ControlError(Exception):
    """Exception raised by ServerController operations."""
    def __init__(self, message: str, status_code: Optional[int] = None, code: str = "control_error"):
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.code = code


class ServerController:
    """Unified programmatic controller for PiPhotos server instances."""

    def __init__(self, data_dir: Optional[Path | str] = None, port: Optional[int] = None):
        self.paths: ServerPaths = resolve_paths(data_dir=data_dir)
        self.paths.ensure_dirs()
        self._explicit_port = port
        self.port: int = self._resolve_port()

    def _resolve_port(self) -> int:
        if self._explicit_port is not None and self._explicit_port > 0:
            return self._explicit_port
        if self.paths.config_path.is_file():
            try:
                cfg = json.loads(self.paths.config_path.read_text(encoding="utf-8"))
                p = cfg.get("port")
                if p is not None and int(p) > 0:
                    return int(p)
            except Exception:
                pass
        return 8443

    def get_config(self) -> Dict[str, Any]:
        """Read config.json or return defaults."""
        if self.paths.config_path.is_file():
            try:
                return json.loads(self.paths.config_path.read_text(encoding="utf-8"))
            except Exception:
                pass
        return {
            "name": "Home Vault",
            "port": self.port,
            "host": "0.0.0.0",
            "default_quota": 53687091200,
            "disk_reserve_fraction": 0.10,
        }

    def save_config(self, updates: Dict[str, Any]) -> Dict[str, Any]:
        """Update and persist config.json."""
        cfg = self.get_config()
        cfg.update(updates)
        self.paths.config_path.parent.mkdir(parents=True, exist_ok=True)
        self.paths.config_path.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
        if "port" in updates:
            self.port = int(updates["port"])
        return cfg

    def get_admin_token(self) -> Optional[str]:
        """Read local administrator loopback token."""
        return read_local_admin_token(self.paths.local_admin_token_path)

    def ensure_admin_token(self) -> str:
        """Ensure local administrator token exists and return it."""
        return ensure_local_admin_token(self.paths.local_admin_token_path)

    def get_cert_fingerprint(self) -> str:
        """Return SHA-256 fingerprint of node TLS certificate."""
        if self.paths.cert_path.is_file():
            try:
                return get_cert_fingerprint(self.paths.cert_path)
            except Exception:
                pass
        return ""

    def is_running(self) -> bool:
        """Check if server process is actively responding on loopback port."""
        # 1. Quick TCP socket probe (<300ms)
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(0.3)
        try:
            res = sock.connect_ex(("127.0.0.1", self.port))
            if res != 0:
                return False
        except Exception:
            return False
        finally:
            try:
                sock.close()
            except Exception:
                pass

        # 2. HTTP probe to verify it's indeed PiPhotos
        try:
            import httpx
            r = httpx.get(f"https://127.0.0.1:{self.port}/api/v1/info", verify=False, timeout=1.0)
            return r.status_code == 200
        except Exception:
            return False

    def _http_request(
        self,
        method: str,
        path: str,
        json_body: Optional[Dict[str, Any]] = None,
        params: Optional[Dict[str, Any]] = None,
        timeout: float = 5.0,
    ) -> Dict[str, Any]:
        """Make authenticated loopback HTTP request to the running server."""
        import httpx
        token = self.ensure_admin_token()
        url = f"https://127.0.0.1:{self.port}{path}"
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        }
        try:
            m = method.upper()
            if m == "POST":
                resp = httpx.post(
                    url=url,
                    headers=headers,
                    json=json_body,
                    params=params,
                    verify=False,
                    timeout=timeout,
                )
            elif m == "GET":
                resp = httpx.get(
                    url=url,
                    headers=headers,
                    params=params,
                    verify=False,
                    timeout=timeout,
                )
            else:
                resp = httpx.request(
                    method=m,
                    url=url,
                    headers=headers,
                    json=json_body,
                    params=params,
                    verify=False,
                    timeout=timeout,
                )
        except httpx.ConnectError:
            raise ControlError(
                f"Could not connect to PiPhotos server at {url}. Is it running?",
                code="connection_refused"
            )
        except Exception as e:
            raise ControlError(f"HTTP request error: {e}", code="request_error")

        if resp.status_code >= 400:
            try:
                err_data = resp.json()
                msg = err_data.get("message") or err_data.get("detail") or resp.text
            except Exception:
                msg = resp.text
            raise ControlError(msg, status_code=resp.status_code, code="http_error")

        if resp.text:
            try:
                return resp.json()
            except Exception:
                return {"text": resp.text}
        return {}

    def get_status(self) -> Dict[str, Any]:
        """Return comprehensive status, storage, network, and process state."""
        running = self.is_running()
        cfg = self.get_config()
        cert_fp = self.get_cert_fingerprint()
        local_ips = get_local_ipv4_addresses()
        storage_summary = self.get_storage_summary()

        status_data: Dict[str, Any] = {
            "running": running,
            "name": cfg.get("name", "Home Vault"),
            "port": self.port,
            "version": "1.0.0",
            "data_dir": str(self.paths.data_dir),
            "storage_dir": str(self.paths.storage_dir),
            "local_ips": local_ips,
            "lan_urls": [f"https://{ip}:{self.port}" for ip in local_ips],
            "cert_fingerprint": cert_fp,
            "storage": storage_summary,
            "uptime_seconds": None,
            "users_count": 0,
            "devices_count": 0,
            "pairing_open": False,
        }

        if running:
            try:
                info = self._http_request("GET", "/api/v1/info", timeout=2.0)
                status_data["version"] = info.get("version", "1.0.0")
                status_data["pairing_open"] = info.get("pairing_open", False)

                overview = self._http_request("GET", "/api/v1/admin/overview", timeout=2.0)
                if "allocation_bytes" in overview:
                    status_data["storage"]["allocation_bytes"] = overview["allocation_bytes"]
                    status_data["storage"]["allocation_used_bytes"] = overview.get("allocation_used_bytes", storage_summary["used_bytes"])
                    alloc_left = max(0, status_data["storage"]["allocation_bytes"] - status_data["storage"]["allocation_used_bytes"])
                    status_data["storage"]["available_bytes"] = min(alloc_left, storage_summary["usable_disk_bytes"])
                if "users" in overview:
                    users_list = overview["users"]
                    status_data["users_count"] = len(users_list)
                    status_data["devices_count"] = sum(u.get("device_count", 0) for u in users_list)
            except Exception as e:
                logger.warning("Could not fetch running server overview: %s", e)
        else:
            # When server is stopped, read directly from SQLite DB if present
            if self.paths.db_path.is_file():
                try:
                    import sqlite3
                    with sqlite3.connect(str(self.paths.db_path)) as conn:
                        cur = conn.cursor()
                        cur.execute("SELECT COUNT(*) FROM users")
                        status_data["users_count"] = cur.fetchone()[0]
                        cur.execute("SELECT COUNT(*) FROM devices")
                        status_data["devices_count"] = cur.fetchone()[0]
                except Exception as e:
                    logger.debug("Could not read stopped status from DB: %s", e)

        return status_data

    def get_storage_summary(self) -> Dict[str, Any]:
        """Compute truthful storage metrics across physical disk and allocation limits."""
        cfg = self.get_config()
        reserve_fraction = float(cfg.get("disk_reserve_fraction", 0.10))

        disk_total = 0
        disk_free = 0
        try:
            usage = shutil.disk_usage(self.paths.storage_dir)
            disk_total = usage.total
            disk_free = usage.free
        except Exception as e:
            logger.warning("Could not measure disk usage on %s, using defaults: %s", self.paths.storage_dir, e)
            disk_total = 100 * 1024**3
            disk_free = 88 * 1024**3

        reserve_bytes = int(disk_total * reserve_fraction)
        usable_disk = max(0, disk_free - reserve_bytes)

        # Configured node allocation
        alloc_cfg = cfg.get("node_allocation_bytes")
        if alloc_cfg is not None and int(alloc_cfg) > 0:
            allocation_bytes = int(alloc_cfg)
        else:
            allocation_bytes = int(disk_free * 0.90)

        # Database storage used
        used_bytes = 0
        if self.paths.db_path.is_file():
            try:
                import sqlite3
                with sqlite3.connect(str(self.paths.db_path)) as conn:
                    cur = conn.cursor()
                    cur.execute("SELECT COALESCE(SUM(size), 0) FROM files")
                    used_bytes = cur.fetchone()[0]
            except Exception as e:
                logger.debug("Could not query files usage from DB: %s", e)

        alloc_left = max(0, allocation_bytes - used_bytes)
        available_bytes = min(alloc_left, usable_disk)

        return {
            "disk_total_bytes": disk_total,
            "disk_free_bytes": disk_free,
            "disk_reserve_bytes": reserve_bytes,
            "usable_disk_bytes": usable_disk,
            "allocation_bytes": allocation_bytes,
            "allocation_used_bytes": used_bytes,
            "used_bytes": used_bytes,
            "available_bytes": available_bytes,
            "storage_path": str(self.paths.storage_dir),
            "reserve_percent": int(reserve_fraction * 100),
        }

    def set_storage_capacity(self, size_bytes: int, force: bool = False) -> Dict[str, Any]:
        """Set node storage allocation capacity. Updates running server and config.json."""
        if self.is_running():
            res = self._http_request(
                "POST",
                "/api/v1/admin/storage/allocation",
                json_body={"allocation_bytes": size_bytes, "force": force},
            )
            # Also ensure config.json has it
            self.save_config({"node_allocation_bytes": size_bytes})
            return res
        else:
            storage_info = self.get_storage_summary()
            used = storage_info["used_bytes"]
            if size_bytes < used and not force:
                raise ControlError(
                    f"Requested capacity ({size_bytes} B) is smaller than current usage ({used} B). Use force=True to override.",
                    status_code=400,
                    code="capacity_below_usage",
                )
            self.save_config({"node_allocation_bytes": size_bytes})
            return {
                "allocation_bytes": size_bytes,
                "used_bytes": used,
                "free_bytes": max(0, size_bytes - used),
            }

    def open_pairing(self, duration_minutes: int = 10) -> Dict[str, Any]:
        """Open pairing window on running server and return pairing code and QR payload."""
        res = self._http_request("POST", "/api/v1/admin/pairing/open")
        code = res.get("code", "")
        expires_in = res.get("expires_in_seconds", duration_minutes * 60)

        ips = get_local_ipv4_addresses()
        primary_ip = ips[0] if ips else "127.0.0.1"
        fp = self.get_cert_fingerprint()

        qr_payload = json.dumps({
            "host": primary_ip,
            "port": self.port,
            "tls_fp": fp,
            "code": code,
        }, separators=(",", ":"))

        return {
            "code": code,
            "expires_in_seconds": expires_in,
            "expires_at": time.time() + expires_in,
            "host": primary_ip,
            "port": self.port,
            "tls_fingerprint": fp,
            "qr_payload": qr_payload,
        }

    def list_users(self) -> List[Dict[str, Any]]:
        """List all users with their roles, quotas, usage, and device counts."""
        if self.is_running():
            res = self._http_request("GET", "/api/v1/admin/users")
            if isinstance(res, list):
                return res
            return []

        # Offline fallback: query sqlite database
        if not self.paths.db_path.is_file():
            return []

        import sqlite3
        conn = sqlite3.connect(str(self.paths.db_path))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()
        cur.execute("SELECT id, name, display_name, role, quota_bytes, status, created_at FROM users")
        users = []
        for row in cur.fetchall():
            uid = row["id"]
            # Get used bytes
            cur.execute("SELECT COALESCE(SUM(size), 0), COUNT(*) FROM files WHERE user_id = ?", (uid,))
            used_row = cur.fetchone()
            used_bytes = used_row[0] if used_row else 0
            file_count = used_row[1] if used_row else 0

            # Get device count
            cur.execute("SELECT COUNT(*) FROM devices WHERE user_id = ?", (uid,))
            dev_count = cur.fetchone()[0]

            display_val = row["display_name"] or row["name"]
            users.append({
                "id": uid,
                "name": display_val,
                "display_name": display_val,
                "slug": row["slug"] if "slug" in row.keys() else uid,
                "role": row["role"],
                "status": row["status"],
                "quota_bytes": row["quota_bytes"],
                "used_bytes": used_bytes,
                "file_count": file_count,
                "device_count": dev_count,
                "devices_count": dev_count,
                "last_seen_at": None,
            })
        conn.close()
        return users

    def create_user(self, display_name: str, quota_bytes: int = 0) -> Dict[str, Any]:
        """Create a new user / invite."""
        display_name = display_name.strip()
        if not display_name:
            raise ControlError("User display name cannot be blank", status_code=400)

        if self.is_running():
            return self._http_request(
                "POST",
                "/api/v1/admin/users",
                json_body={"display_name": display_name, "quota_bytes": quota_bytes},
            )
        else:
            raise ControlError("Server must be running to create and invite users.", code="server_stopped")

    def update_user(
        self,
        user_id: str,
        quota_bytes: Optional[int] = None,
        display_name: Optional[str] = None,
        status: Optional[str] = None,
        role: Optional[str] = None,
        force: bool = False,
    ) -> Dict[str, Any]:
        """Update user properties (quota, status, display name, role)."""
        if self.is_running():
            payload: Dict[str, Any] = {}
            if quota_bytes is not None:
                payload["quota_bytes"] = quota_bytes
            if display_name is not None:
                payload["display_name"] = display_name
            if status is not None:
                payload["status"] = status
            if role is not None:
                payload["role"] = role

            return self._http_request(
                "PATCH",
                f"/api/v1/admin/users/{user_id}",
                json_body=payload,
                params={"force": str(force).lower()},
            )
        else:
            if not self.paths.db_path.is_file():
                raise ControlError("Database not found", status_code=404)
            import sqlite3
            with sqlite3.connect(str(self.paths.db_path)) as conn:
                conn.row_factory = sqlite3.Row
                cur = conn.cursor()
                cur.execute("SELECT id, role, status FROM users WHERE id = ?", (user_id,))
                u = cur.fetchone()
                if not u:
                    raise ControlError(f"User {user_id} not found", status_code=404)

                # Guard: cannot disable or demote the last active owner
                if (status == "disabled" or (role and role != "owner")) and u["role"] == "owner":
                    cur.execute("SELECT COUNT(*) FROM users WHERE role = 'owner' AND status = 'active'")
                    active_owners = cur.fetchone()[0]
                    if active_owners <= 1:
                        raise ControlError("Cannot disable or demote the last active owner account.", status_code=400)

                # Quota guard: cannot set quota below current usage unless force=True
                if quota_bytes is not None and quota_bytes > 0 and not force:
                    cur.execute("SELECT COALESCE(SUM(size), 0) FROM files WHERE user_id = ?", (user_id,))
                    used = cur.fetchone()[0]
                    if quota_bytes < used:
                        raise ControlError(
                            f"Requested quota ({quota_bytes} bytes) is below current user storage usage ({used} bytes). Use force=True to override.",
                            status_code=400,
                        )

                updates = []
                params_list = []
                if quota_bytes is not None:
                    updates.append("quota_bytes = ?")
                    params_list.append(quota_bytes)
                if display_name is not None:
                    updates.append("display_name = ?")
                    params_list.append(display_name)
                if status is not None:
                    updates.append("status = ?")
                    params_list.append(status)
                if role is not None:
                    updates.append("role = ?")
                    params_list.append(role)

                if updates:
                    params_list.append(user_id)
                    cur.execute(f"UPDATE users SET {', '.join(updates)} WHERE id = ?", tuple(params_list))
                    conn.commit()
            return {"status": "updated", "user_id": user_id}

    def delete_user(
        self,
        user_id: str,
        delete_files: bool = False,
        delete_now: bool = False,
        grace_days: int = 7,
    ) -> Dict[str, Any]:
        """Remove a user or schedule deletion with grace period (Improvement 5)."""
        if self.is_running():
            return self._http_request(
                "DELETE",
                f"/api/v1/admin/users/{user_id}",
                params={
                    "delete_files": str(delete_files).lower(),
                    "delete_now": str(delete_now).lower(),
                    "grace_days": str(grace_days),
                },
            )
        else:
            if not self.paths.db_path.is_file():
                raise ControlError("Database not found", status_code=404)
            import sqlite3
            from datetime import datetime, timezone, timedelta
            conn = sqlite3.connect(str(self.paths.db_path))
            try:
                conn.row_factory = sqlite3.Row
                cur = conn.cursor()
                cur.execute("SELECT id, role, status FROM users WHERE id = ?", (user_id,))
                u = cur.fetchone()
                if not u:
                    raise ControlError(f"User {user_id} not found", status_code=404)

                if u["role"] == "owner" and u["status"] == "active":
                    cur.execute("SELECT COUNT(*) FROM users WHERE role = 'owner' AND status = 'active'")
                    if cur.fetchone()[0] <= 1:
                        raise ControlError("Cannot delete the last active owner account.", status_code=400)

                if not delete_now:
                    now = datetime.now(timezone.utc)
                    disabled_at = now.isoformat()
                    scheduled_purge_at = (now + timedelta(days=grace_days)).isoformat()
                    cur.execute(
                        "UPDATE users SET status = 'pending_deletion', is_active = 0, disabled_at = ?, scheduled_purge_at = ? WHERE id = ?",
                        (disabled_at, scheduled_purge_at, user_id),
                    )
                    cur.execute("DELETE FROM tokens WHERE user_id = ?", (user_id,))
                    conn.commit()
                    return {
                        "status": "pending_deletion",
                        "user_id": user_id,
                        "scheduled_purge_at": scheduled_purge_at,
                        "grace_days": grace_days,
                    }

                # Immediate delete
                cur.execute("DELETE FROM files WHERE user_id = ?", (user_id,))
                cur.execute("DELETE FROM tokens WHERE user_id = ?", (user_id,))
                cur.execute("DELETE FROM devices WHERE user_id = ?", (user_id,))
                cur.execute("DELETE FROM invites WHERE user_id = ?", (user_id,))
                cur.execute("DELETE FROM partial_uploads WHERE user_id = ?", (user_id,))
                cur.execute("DELETE FROM users WHERE id = ?", (user_id,))
                conn.commit()
                return {"status": "deleted", "user_id": user_id, "files_deleted": delete_files}
            finally:
                conn.close()

    def restore_user(self, user_id: str) -> Dict[str, Any]:
        """Restore user from pending_deletion or disabled state back to active (Improvement 5)."""
        if self.is_running():
            return self._http_request("POST", f"/api/v1/admin/users/{user_id}/restore")
        else:
            if not self.paths.db_path.is_file():
                raise ControlError("Database not found", status_code=404)
            import sqlite3
            conn = sqlite3.connect(str(self.paths.db_path))
            try:
                cur = conn.cursor()
                cur.execute("SELECT id FROM users WHERE id = ?", (user_id,))
                if not cur.fetchone():
                    raise ControlError(f"User {user_id} not found", status_code=404)
                cur.execute(
                    "UPDATE users SET status = 'active', is_active = 1, disabled_at = NULL, scheduled_purge_at = NULL WHERE id = ?",
                    (user_id,),
                )
                conn.commit()
                return {"status": "restored", "user_id": user_id}
            finally:
                conn.close()

    def list_devices(self, user_id: str) -> List[Dict[str, Any]]:
        """List registered devices for a user."""
        if self.is_running():
            res = self._http_request("GET", f"/api/v1/admin/users/{user_id}/devices")
            if isinstance(res, list):
                return res
            return []

        if not self.paths.db_path.is_file():
            return []
        import sqlite3
        with sqlite3.connect(str(self.paths.db_path)) as conn:
            conn.row_factory = sqlite3.Row
            cur = conn.cursor()
            try:
                cur.execute("""
                    SELECT d.id, d.name, d.model, d.slug, d.created_at, d.last_seen_at,
                           COALESCE(t.role, 'backup') as role
                    FROM devices d
                    LEFT JOIN tokens t ON d.id = t.device_id
                    WHERE d.user_id = ?
                    ORDER BY d.created_at ASC
                """, (user_id,))
                return [dict(r) for r in cur.fetchall()]
            except sqlite3.OperationalError:
                cur.execute("SELECT id, name, model, slug, created_at, last_seen_at FROM devices WHERE user_id = ?", (user_id,))
                rows = [dict(r) for r in cur.fetchall()]
                for r in rows:
                    r["role"] = "backup"
                return rows

    def set_device_role(self, user_id: str, device_id: str, role: str) -> Dict[str, Any]:
        """Update role of a device ('manage' or 'backup')."""
        if role not in ("manage", "backup"):
            raise ControlError("Device role must be 'manage' or 'backup'", status_code=400)
        if self.is_running():
            return self._http_request(
                "PATCH",
                f"/api/v1/admin/users/{user_id}/devices/{device_id}",
                data={"role": role},
            )
        else:
            if not self.paths.db_path.is_file():
                raise ControlError("Database not found", status_code=404)
            import sqlite3
            conn = sqlite3.connect(str(self.paths.db_path))
            try:
                cur = conn.cursor()
                if role == "backup":
                    cur.execute("SELECT role FROM users WHERE id = ?", (user_id,))
                    u_row = cur.fetchone()
                    if u_row and u_row[0] == "owner":
                        cur.execute("SELECT COUNT(*) FROM tokens WHERE user_id = ? AND role = 'manage' AND device_id != ?", (user_id, device_id))
                        other_manage = cur.fetchone()[0]
                        if other_manage == 0:
                            raise ControlError("Cannot demote owner's last manage-capable device", status_code=400, code="cannot_demote_last_manage_device")
                cur.execute("UPDATE tokens SET role = ? WHERE device_id = ?", (role, device_id))
                conn.commit()
                cur.execute("SELECT id, name, model, slug, created_at, last_seen_at FROM devices WHERE id = ?", (device_id,))
                d_row = cur.fetchone()
                if not d_row:
                    raise ControlError("Device not found", status_code=404)
                return {
                    "id": d_row[0], "name": d_row[1], "model": d_row[2],
                    "slug": d_row[3], "created_at": d_row[4], "last_seen_at": d_row[5],
                    "role": role,
                }
            finally:
                conn.close()

    def backup_database(self, max_keep: int = 14) -> Path:
        """Create a point-in-time snapshot of the database."""
        from .backup import create_database_backup
        backups_dir = self.paths.data_dir / "backups"
        return create_database_backup(self.paths.db_path, backups_dir, max_keep=max_keep)

    def restore_database(self, backup_file: Path | str) -> Path:
        """Restore database from a backup file."""
        from .backup import restore_database_backup
        return restore_database_backup(Path(backup_file), self.paths.db_path)

    def list_backups(self) -> List[Dict[str, Any]]:
        """List available database backups in data/backups/."""
        backups_dir = self.paths.data_dir / "backups"
        if not backups_dir.exists():
            return []
        items = []
        for p in sorted(backups_dir.glob("piphotos-*.db"), key=lambda f: f.stat().st_mtime, reverse=True):
            if p.is_file():
                items.append({
                    "path": str(p),
                    "filename": p.name,
                    "size_bytes": p.stat().st_size,
                    "created_at": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(p.stat().st_mtime)),
                })
        return items

    def delete_device(self, device_id: str) -> Dict[str, Any]:
        """Revoke a registered device."""
        if self.is_running():
            return self._http_request("DELETE", f"/api/v1/admin/devices/{device_id}")
        else:
            if not self.paths.db_path.is_file():
                raise ControlError("Database not found", status_code=404)
            import sqlite3
            with sqlite3.connect(str(self.paths.db_path)) as conn:
                cur = conn.cursor()
                try:
                    cur.execute("DELETE FROM tokens WHERE device_id = ?", (device_id,))
                except Exception:
                    pass
                cur.execute("DELETE FROM devices WHERE id = ?", (device_id,))
                conn.commit()
            return {"status": "revoked", "device_id": device_id}

    def list_recent_uploads(self, limit: int = 10) -> List[Dict[str, Any]]:
        """Return list of recent file uploads with user and device attribution."""
        if not self.paths.db_path.is_file():
            return []
        import sqlite3
        with sqlite3.connect(str(self.paths.db_path)) as conn:
            conn.row_factory = sqlite3.Row
            cur = conn.cursor()
            cur.execute("""
                SELECT f.id, f.relative_path, f.size, f.created_at,
                       u.display_name as user_name, d.name as device_name
                FROM files f
                LEFT JOIN users u ON f.user_id = u.id
                LEFT JOIN devices d ON f.device_id = d.id
                ORDER BY f.id DESC
                LIMIT ?
            """, (limit,))
            rows = []
            for r in cur.fetchall():
                filename = os.path.basename(r["relative_path"])
                rows.append({
                    "id": r["id"],
                    "filename": filename,
                    "size_bytes": r["size"],
                    "user_name": r["user_name"] or "Owner",
                    "device_name": r["device_name"] or "Device",
                    "created_at": r["created_at"],
                })
            return rows

    def run_doctor(self) -> Dict[str, Any]:
        """Run complete health check diagnostics suite."""
        doc = ServerDoctor(self.paths)
        results: List[CheckResult] = doc.run_all()
        checks = [asdict(r) for r in results]
        passed = sum(1 for r in results if r.status == "PASS")
        warns = sum(1 for r in results if r.status == "WARN")
        fails = sum(1 for r in results if r.status == "FAIL")

        return {
            "total": len(results),
            "passed": passed,
            "warns": warns,
            "fails": fails,
            "passed_all": fails == 0,
            "checks": checks,
        }

    # Service lifecycle
    def start_service(self) -> Tuple[bool, str]:
        return svc_start(is_system=False)

    def stop_service(self) -> Tuple[bool, str]:
        return svc_stop(is_system=False)

    def restart_service(self) -> Tuple[bool, str]:
        return svc_restart(is_system=False)

    def install_service(self, no_firewall: bool = False) -> Tuple[bool, str]:
        return svc_install(self.paths, is_system=False, no_firewall=no_firewall)

    def uninstall_service(self, no_firewall: bool = False) -> Tuple[bool, str]:
        return svc_uninstall(self.paths, is_system=False, no_firewall=no_firewall)
