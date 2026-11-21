#!/bin/sh
# PiPhotos Storage Node Installer for Linux, Raspberry Pi, and macOS
#
# Quick install:
#   curl -fsSL https://raw.githubusercontent.com/Ashutoshpanchall/Pi-Photos/main/install/install.sh | sh
#
# Verification before execution:
#   curl -fsSL https://raw.githubusercontent.com/Ashutoshpanchall/Pi-Photos/main/install/install.sh -o install.sh
#   sha256sum install.sh    # (on macOS: shasum -a 256 install.sh)
#   sh install.sh
#
# Supported options:
#   --dry-run                 Show actions without modifying the system
#   --data-dir <path>         Base directory for database, config, certs, and logs
#   --storage-dir <path>      Storage directory for photos (default: <data-dir>/storage)
#   --port <port>             HTTPS port to listen on (default: 8443)
#   --name <name>             Server display name
#   --ref <git-ref>           Branch, tag, or commit to install (default: main)
#   --no-service              Skip systemd/launchd service and firewall registration
#   --yes, -y                 Non-interactive mode (accept all defaults)

set -e

REF="main"
PORT="8443"
DATA_DIR=""
STORAGE_DIR=""
NAME=""
DRY_RUN=0
NO_SERVICE=0
YES=0

show_help() {
  cat << 'EOF'
PiPhotos Storage Node Installer

Usage:
  install.sh [options]

Options:
  --dry-run             Print planned operations without making any changes
  --data-dir <path>     Directory for server config, database, certs, and logs
  --storage-dir <path>  Directory where uploaded media files are stored
  --port <port>         Port to listen on (default: 8443)
  --name <name>         Server display name in the PiPhotos mobile app
  --ref <ref>           Git branch, tag, or commit to install (default: main)
  --no-service          Do not install systemd/launchd service or configure firewall
  --yes, -y             Accept all prompts and defaults automatically
  -h, --help            Show this help message

Integrity:
  To verify script integrity before running:
  curl -fsSL https://raw.githubusercontent.com/Ashutoshpanchall/Pi-Photos/main/install/install.sh -o install.sh
  sha256sum install.sh && sh install.sh
EOF
}

# Parse command-line arguments
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --data-dir)
      DATA_DIR="$2"
      shift 2
      ;;
    --storage-dir)
      STORAGE_DIR="$2"
      shift 2
      ;;
    --port)
      PORT="$2"
      shift 2
      ;;
    --name)
      NAME="$2"
      shift 2
      ;;
    --ref)
      REF="$2"
      shift 2
      ;;
    --no-service)
      NO_SERVICE=1
      shift
      ;;
    --yes|-y)
      YES=1
      shift
      ;;
    -h|--help)
      show_help
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      show_help >&2
      exit 1
      ;;
  esac
done

# ---------------------------------------------------------------------------
# 1. Detect OS and architecture
# ---------------------------------------------------------------------------
OS="${TARGET_OS:-$(uname -s)}"
ARCH="${TARGET_ARCH:-$(uname -m)}"

case "$OS" in
  Linux*)
    PLATFORM="Linux"
    DEFAULT_DATA_DIR="$HOME/.local/share/piphotos"
    if [ "$(id -u)" = "0" ]; then
      DEFAULT_DATA_DIR="/var/lib/piphotos"
    fi
    ;;
  Darwin*)
    PLATFORM="macOS"
    DEFAULT_DATA_DIR="$HOME/Library/Application Support/PiPhotos"
    ;;
  *)
    echo "Error: Unsupported operating system: $OS. Use install.ps1 for Windows." >&2
    exit 1
    ;;
esac

TARGET_DATA_DIR="${DATA_DIR:-$DEFAULT_DATA_DIR}"

# Raspberry Pi checks
IS_RPI=0
RPI_MODEL=""
if [ -f /proc/device-tree/model ]; then
  if grep -qi "Raspberry Pi" /proc/device-tree/model 2>/dev/null; then
    IS_RPI=1
    RPI_MODEL="$(tr -d '\0' < /proc/device-tree/model)"
  fi
fi

# ---------------------------------------------------------------------------
# 2. Print plan
# ---------------------------------------------------------------------------
echo "============================================================"
echo "          PiPhotos Storage Node Server Installer           "
echo "============================================================"
echo "  Platform:      $PLATFORM ($ARCH)"
if [ "$IS_RPI" = "1" ]; then
  echo "  Device:        $RPI_MODEL"
fi
echo "  Target Ref:    $REF"
echo "  HTTPS Port:    $PORT"
echo "  Data Dir:      $TARGET_DATA_DIR"
if [ -n "$STORAGE_DIR" ]; then
  echo "  Storage Dir:   $STORAGE_DIR"
fi
if [ "$NO_SERVICE" = "1" ]; then
  echo "  Auto-Start:    Disabled (--no-service)"
else
  echo "  Auto-Start:    Enabled (systemd/launchd service)"
fi
echo "============================================================"

if [ "$IS_RPI" = "1" ] && [ "$ARCH" = "armv7l" ]; then
  echo ""
  echo "WARNING: Detected 32-bit Raspberry Pi OS ($ARCH)."
  echo "Precompiled cryptography wheels may not be available for 32-bit systems."
  echo "We strongly recommend 64-bit Raspberry Pi OS for best performance."
  echo ""
fi

if [ "$DRY_RUN" = "1" ]; then
  echo "[DRY-RUN] Verification complete. No system changes made."
  exit 0
fi

if [ "$YES" != "1" ] && [ -t 0 ]; then
  printf "Proceed with installation? [Y/n]: "
  read -r CONFIRM
  case "$CONFIRM" in
    [nN]*)
      echo "Installation cancelled."
      exit 0
      ;;
  esac
fi

# ---------------------------------------------------------------------------
# 3. Locate or install Astral uv (fast Python tool manager)
# ---------------------------------------------------------------------------
UV_BIN=""
if command -v uv >/dev/null 2>&1; then
  UV_BIN="$(command -v uv)"
elif [ -x "$HOME/.local/bin/uv" ]; then
  UV_BIN="$HOME/.local/bin/uv"
  PATH="$HOME/.local/bin:$PATH"
elif [ -x "$HOME/.cargo/bin/uv" ]; then
  UV_BIN="$HOME/.cargo/bin/uv"
  PATH="$HOME/.cargo/bin:$PATH"
fi

if [ -z "$UV_BIN" ]; then
  echo ""
  echo "Astral uv not found on PATH. Installing uv (user-level, no root required)..."
  if command -v curl >/dev/null 2>&1; then
    curl -LsSf https://astral.sh/uv/install.sh | sh
  elif command -v wget >/dev/null 2>&1; then
    wget -qO- https://astral.sh/uv/install.sh | sh
  else
    echo "Error: curl or wget is required to download uv." >&2
    exit 1
  fi

  export PATH="$HOME/.local/bin:$HOME/.cargo/bin:$PATH"
  if command -v uv >/dev/null 2>&1; then
    UV_BIN="$(command -v uv)"
  elif [ -x "$HOME/.local/bin/uv" ]; then
    UV_BIN="$HOME/.local/bin/uv"
  elif [ -x "$HOME/.cargo/bin/uv" ]; then
    UV_BIN="$HOME/.cargo/bin/uv"
  else
    echo "Error: uv was installed but could not be located in ~/.local/bin or ~/.cargo/bin." >&2
    exit 1
  fi
fi

echo "Using uv at: $UV_BIN"

# ---------------------------------------------------------------------------
# 4. Install / Upgrade piphotos-server tool
# ---------------------------------------------------------------------------
if [ -d "$REF" ] || [ -f "$REF/pyproject.toml" ]; then
  PACKAGE_SPEC="$REF"
elif [ "$REF" = "local" ] || [ "$REF" = "." ]; then
  PACKAGE_SPEC="./server"
else
  PACKAGE_SPEC="git+https://github.com/Ashutoshpanchall/Pi-Photos@${REF}#subdirectory=server"
fi
echo ""
echo "Installing/upgrading piphotos-server from $PACKAGE_SPEC..."
"$UV_BIN" tool install --force "$PACKAGE_SPEC"

# Ensure ~/.local/bin is in PATH for piphotos-server executable
export PATH="$HOME/.local/bin:$PATH"

if ! command -v piphotos-server >/dev/null 2>&1; then
  # Look in uv tools directory
  if [ -x "$HOME/.local/bin/piphotos-server" ]; then
    SERVER_BIN="$HOME/.local/bin/piphotos-server"
  else
    SERVER_BIN="$("$UV_BIN" tool dir)/piphotos-server/bin/piphotos-server"
  fi
else
  SERVER_BIN="$(command -v piphotos-server)"
fi

echo "piphotos-server ready at: $SERVER_BIN"

# ---------------------------------------------------------------------------
# 5. Run first-time setup (idempotent, merges existing configs)
# ---------------------------------------------------------------------------
echo ""
echo "Configuring PiPhotos server..."
set -- "$SERVER_BIN" setup --yes --port "$PORT"
if [ -n "$DATA_DIR" ]; then
  set -- "$@" --data-dir "$DATA_DIR"
fi
if [ -n "$STORAGE_DIR" ]; then
  set -- "$@" --storage-dir "$STORAGE_DIR"
fi
if [ -n "$NAME" ]; then
  set -- "$@" --name "$NAME"
fi

"$@"

# ---------------------------------------------------------------------------
# 6. Service installation and start
# ---------------------------------------------------------------------------
if [ "$NO_SERVICE" != "1" ]; then
  echo ""
  echo "Installing auto-start background service..."
  set -- "$SERVER_BIN" install-service
  if [ -n "$DATA_DIR" ]; then
    set -- "$@" --data-dir "$DATA_DIR"
  fi
  "$@"

  echo "Starting PiPhotos service..."
  "$SERVER_BIN" start

  echo "Performing loopback self-check on https://127.0.0.1:$PORT/api/v1/info..."
  HEALTHY=0
  for _ in 1 2 3 4 5; do
    sleep 1
    if command -v curl >/dev/null 2>&1; then
      if curl -k -s -f "https://127.0.0.1:$PORT/api/v1/info" >/dev/null 2>&1; then
        HEALTHY=1
        break
      fi
    elif command -v python3 >/dev/null 2>&1; then
      if python3 -c "import urllib.request, ssl; ctx = ssl._create_unverified_context(); urllib.request.urlopen('https://127.0.0.1:$PORT/api/v1/info', context=ctx, timeout=2)" >/dev/null 2>&1; then
        HEALTHY=1
        break
      fi
    fi
  done

  if [ "$HEALTHY" = "1" ]; then
    echo "Server is healthy and active!"
    echo ""
    set -- "$SERVER_BIN" pair
    if [ -n "$DATA_DIR" ]; then
      set -- "$@" --data-dir "$DATA_DIR"
    fi
    "$@"
    echo ""
    echo "============================================================"
    echo "  Setup Complete! Next steps:"
    echo "  1. Open the PiPhotos app on your Android phone."
    echo "  2. Go to Backup > Set up (or Settings > Storage Nodes)."
    echo "  3. Scan the QR code above or enter the 6-digit code."
    echo "============================================================"
  else
    echo "Notice: Service registered. If loopback check timed out, inspect status with:"
    echo "  $SERVER_BIN status"
    echo "  $SERVER_BIN logs"
  fi
else
  echo ""
  echo "Service skipped (--no-service). To run server in foreground:"
  echo "  $SERVER_BIN run"
fi
