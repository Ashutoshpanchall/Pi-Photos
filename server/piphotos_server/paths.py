"""OS-specific path resolution for PiPhotos server data, configs, and storage."""

from __future__ import annotations

import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


def is_windows_elevated() -> bool:
    """Return True if running with administrative privileges on Windows."""
    if sys.platform != "win32":
        return False
    try:
        import ctypes
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def is_root_user() -> bool:
    """Return True if running as root on Unix."""
    if hasattr(os, "geteuid"):
        return os.geteuid() == 0
    return False


def get_default_data_dir() -> Path:
    """Return standard per-OS default data directory for PiPhotos.

    - Windows (elevated): %PROGRAMDATA%\\PiPhotos
    - Windows (user):     %LOCALAPPDATA%\\PiPhotos
    - macOS:             ~/Library/Application Support/PiPhotos
    - Linux (root):      /var/lib/piphotos
    - Linux (user):      ~/.local/share/piphotos (or $XDG_DATA_HOME/piphotos)
    """
    if sys.platform == "win32":
        if is_windows_elevated():
            base = os.environ.get("PROGRAMDATA") or r"C:\ProgramData"
        else:
            base = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        return Path(base) / "PiPhotos"
    elif sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "PiPhotos"
    else:
        # Linux / Raspberry Pi / BSD
        if is_root_user():
            return Path("/var/lib/piphotos")
        xdg_data = os.environ.get("XDG_DATA_HOME")
        if xdg_data:
            return Path(xdg_data) / "piphotos"
        return Path.home() / ".local" / "share" / "piphotos"


@dataclass(frozen=True)
class ServerPaths:
    """Resolved file and directory paths for the server instance."""
    data_dir: Path
    config_path: Path
    certs_dir: Path
    cert_path: Path
    key_path: Path
    db_path: Path
    storage_dir: Path
    local_admin_token_path: Path
    logs_dir: Path
    server_id_path: Path

    def ensure_dirs(self) -> None:
        """Create necessary base directories."""
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.certs_dir.mkdir(parents=True, exist_ok=True)
        self.storage_dir.mkdir(parents=True, exist_ok=True)
        self.logs_dir.mkdir(parents=True, exist_ok=True)


def resolve_paths(
    data_dir: Optional[Path | str] = None,
    storage_dir: Optional[Path | str] = None,
    config_path: Optional[Path | str] = None,
) -> ServerPaths:
    """Resolve full ServerPaths hierarchy with precedence:
    CLI arguments > Environment variables > config.json / Default data dir.
    """
    # 1. Resolve data_dir
    if data_dir is not None:
        resolved_data = Path(data_dir).expanduser().resolve()
    elif os.environ.get("PIPHOTOS_DATA_DIR"):
        resolved_data = Path(os.environ["PIPHOTOS_DATA_DIR"]).expanduser().resolve()
    else:
        resolved_data = get_default_data_dir()

    # 2. Resolve config_path
    if config_path is not None:
        resolved_config = Path(config_path).expanduser().resolve()
    elif os.environ.get("PIPHOTOS_CONFIG"):
        resolved_config = Path(os.environ["PIPHOTOS_CONFIG"]).expanduser().resolve()
    else:
        resolved_config = resolved_data / "config.json"

    # Read config.json if it exists to pick up custom storage/db/certs paths
    cfg_storage: Optional[str] = None
    cfg_db: Optional[str] = None
    cfg_certs: Optional[str] = None
    if resolved_config.is_file():
        try:
            import json
            with open(resolved_config, "r", encoding="utf-8") as f:
                cfg = json.load(f)
                cfg_storage = cfg.get("storage")
                cfg_db = cfg.get("database")
                cfg_certs = cfg.get("certs")
        except Exception:
            pass

    # 3. Resolve storage_dir
    if storage_dir is not None:
        resolved_storage = Path(storage_dir).expanduser().resolve()
    elif os.environ.get("PIPHOTOS_STORAGE"):
        resolved_storage = Path(os.environ["PIPHOTOS_STORAGE"]).expanduser().resolve()
    elif cfg_storage:
        resolved_storage = (resolved_data / cfg_storage if not Path(cfg_storage).is_absolute() else Path(cfg_storage)).resolve()
    else:
        resolved_storage = (resolved_data / "storage").resolve()

    # 4. Resolve certs_dir
    if os.environ.get("PIPHOTOS_CERTS"):
        resolved_certs = Path(os.environ["PIPHOTOS_CERTS"]).expanduser().resolve()
    elif cfg_certs:
        resolved_certs = (resolved_data / cfg_certs if not Path(cfg_certs).is_absolute() else Path(cfg_certs)).resolve()
    else:
        resolved_certs = (resolved_data / "certs").resolve()

    # 5. Resolve db_path
    if os.environ.get("PIPHOTOS_DB"):
        resolved_db = Path(os.environ["PIPHOTOS_DB"]).expanduser().resolve()
    elif cfg_db:
        resolved_db = (resolved_data / cfg_db if not Path(cfg_db).is_absolute() else Path(cfg_db)).resolve()
    else:
        resolved_db = (resolved_data / "piphotos.db").resolve()

    return ServerPaths(
        data_dir=resolved_data,
        config_path=resolved_config,
        certs_dir=resolved_certs,
        cert_path=resolved_certs / "cert.pem",
        key_path=resolved_certs / "key.pem",
        db_path=resolved_db,
        storage_dir=resolved_storage,
        local_admin_token_path=resolved_data / "local-admin.token",
        logs_dir=resolved_data / "logs",
        server_id_path=resolved_data / "server_id",
    )

