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


def get_pointer_file_path() -> Path:
    """Return platform-standard path to the PiPhotos pointer file.

    - Windows: %LOCALAPPDATA%\\PiPhotos\\location
    - Linux/macOS: ~/.config/piphotos/location (or $XDG_CONFIG_HOME/piphotos/location)
    """
    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        return Path(base) / "PiPhotos" / "location"
    else:
        xdg_config = os.environ.get("XDG_CONFIG_HOME")
        if xdg_config:
            base = xdg_config
        else:
            base = str(Path.home() / ".config")
        return Path(base) / "piphotos" / "location"


def read_pointer_file() -> tuple[Optional[Path], Optional[int]]:
    """Read persisted data directory and port from pointer file, if present."""
    try:
        p = get_pointer_file_path()
        if not p.is_file():
            return None, None
        content = p.read_text(encoding="utf-8").strip()
        if not content:
            return None, None
        if content.startswith("{"):
            import json
            data = json.loads(content)
            data_dir = Path(data["data_dir"]).expanduser().resolve() if "data_dir" in data and data["data_dir"] else None
            port = int(data["port"]) if "port" in data and data["port"] is not None else None
            return data_dir, port
        else:
            lines = content.splitlines()
            data_dir = Path(lines[0].strip()).expanduser().resolve() if lines and lines[0].strip() else None
            port = int(lines[1].strip()) if len(lines) > 1 and lines[1].strip().isdigit() else None
            return data_dir, port
    except Exception:
        return None, None


def write_pointer_file(data_dir: Path | str, port: Optional[int] = None) -> None:
    """Persist chosen data directory and port in pointer file."""
    try:
        p = get_pointer_file_path()
        p.parent.mkdir(parents=True, exist_ok=True)
        import json
        payload = {
            "data_dir": str(Path(data_dir).expanduser().resolve()),
            "port": port,
        }
        p.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    except Exception:
        pass


def remove_pointer_file() -> None:
    """Remove pointer file (e.g. during purge uninstall)."""
    try:
        p = get_pointer_file_path()
        if p.is_file():
            p.unlink()
    except Exception:
        pass


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
        if sys.platform != "win32":
            from pathlib import PureWindowsPath
            return PureWindowsPath(base) / "PiPhotos"  # type: ignore
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
    CLI arguments (--data-dir) > Environment variables (PIPHOTOS_DATA_DIR) > Pointer file > Default data dir.
    """
    # 1. Resolve data_dir
    if data_dir is not None:
        resolved_data = Path(data_dir).expanduser().resolve()
    elif os.environ.get("PIPHOTOS_DATA_DIR"):
        resolved_data = Path(os.environ["PIPHOTOS_DATA_DIR"]).expanduser().resolve()
    else:
        pointer_data, _ = read_pointer_file()
        if pointer_data is not None:
            resolved_data = pointer_data
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
                if isinstance(cfg_storage, dict):
                    cfg_storage = cfg_storage.get("path")
                cfg_db = cfg.get("database")
                if isinstance(cfg_db, dict):
                    cfg_db = cfg_db.get("path")
                cfg_certs = cfg.get("certs")
                if isinstance(cfg_certs, dict):
                    cfg_certs = cfg_certs.get("path")
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

