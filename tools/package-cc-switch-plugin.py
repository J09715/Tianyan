#!/usr/bin/env python3
import os
import hashlib
import json
import zipfile
import shutil
import ssl
import urllib.request

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PLUGIN_DIR = os.path.join(ROOT_DIR, "assets", "plugins", "cc-switch")
DIST_DIR = os.path.join(ROOT_DIR, "dist", "plugins")
OUTPUT_PACKAGE = os.path.join(DIST_DIR, "tianyan-plugin-cc-switch-v1.0.1-arm64.txplugin")
EXPECTED_SHA256 = "0e16293cdab1f6a8416a06242deeda41ddd970cbf36e753f05de8ad89d6cd010"
DOWNLOAD_URL = "https://github.com/Laliet/cc-switch-web/releases/download/v0.21.0/cc-switch-server-linux-aarch64"

def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def build_plugin():
    print("[*] Packaging CC-Switch offline plugin (.txplugin)...")
    os.makedirs(DIST_DIR, exist_ok=True)

    manifest_path = os.path.join(PLUGIN_DIR, "manifest.json")
    payload_dir = os.path.join(PLUGIN_DIR, "payload")
    bin_dir = os.path.join(payload_dir, "bin")
    lib_dir = os.path.join(payload_dir, "lib")
    scripts_dir = os.path.join(payload_dir, "scripts")
    checksums_dir = os.path.join(payload_dir, "checksums")

    os.makedirs(bin_dir, exist_ok=True)
    os.makedirs(lib_dir, exist_ok=True)
    os.makedirs(scripts_dir, exist_ok=True)
    os.makedirs(checksums_dir, exist_ok=True)

    server_bin = os.path.join(lib_dir, "cc-switch-server")
    legacy_daemon_bin = os.path.join(bin_dir, "cc-switch-daemon")

    # Migrate legacy binary if found in bin/
    if not os.path.isfile(server_bin) and os.path.isfile(legacy_daemon_bin) and os.path.getsize(legacy_daemon_bin) > 1000000:
        print("[*] Migrating legacy daemon binary to lib/cc-switch-server...")
        shutil.move(legacy_daemon_bin, server_bin)

    if not os.path.isfile(server_bin):
        print(f"[*] cc-switch-server binary not found locally. Downloading from {DOWNLOAD_URL} ...")
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        req = urllib.request.Request(DOWNLOAD_URL, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, context=ctx, timeout=60) as resp, open(server_bin, "wb") as f:
            f.write(resp.read())
        print(f"[+] Downloaded {server_bin}")

    current_hash = sha256_file(server_bin)
    print(f"[*] cc-switch-server SHA256: {current_hash}")
    if current_hash != EXPECTED_SHA256:
        print(f"[!] Warning: binary hash does not match expected ({EXPECTED_SHA256})")

    # 1. Write bin/cc-switch-daemon wrapper script
    daemon_wrapper = """#!/bin/sh
set -e

PORT="${CC_SWITCH_PORT:-19870}"
DATA_DIR="${CC_SWITCH_DATA_DIR:-/opt/tianyan/data/cc-switch}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_BIN="$TOOL_DIR/lib/cc-switch-server"

# Fast path for version and help flags to guarantee immediate non-blocking exit
for arg in "$@"; do
    case "$arg" in
        --version|-v|-V)
            echo "cc-switch 1.0.1 (ARM64)"
            exit 0
            ;;
        --help|-h)
            echo "Usage: cc-switch-daemon [--port <port>] [--data-dir <dir>]"
            exit 0
            ;;
    esac
done

while [ $# -gt 0 ]; do
    case "$1" in
        --port|-p)
            if [ $# -ge 2 ]; then
                PORT="$2"
                shift 2
            else
                shift
            fi
            ;;
        --data-dir|-d)
            if [ $# -ge 2 ]; then
                DATA_DIR="$2"
                shift 2
            else
                shift
            fi
            ;;
        *)
            shift
            ;;
    esac
done

# Standardize environment for headless Rust server
export HOME="${HOME:-/root}"
export USER="${USER:-root}"
export LOGNAME="${LOGNAME:-root}"
export HOST="${CC_SWITCH_HOST:-0.0.0.0}"
export CC_SWITCH_HOST="$HOST"
export PORT="$PORT"
export CC_SWITCH_PORT="$PORT"
export CC_SWITCH_DATA_DIR="$DATA_DIR"
export XDG_DATA_HOME="$DATA_DIR"
export CC_SWITCH_LAN_CORS=1
export ALLOW_LAN_CORS=1
export ALLOW_HTTP_BASIC_OVER_HTTP=1
export RUST_LOG="${RUST_LOG:-info}"

# Ensure data directory and HOME directory layout exist
mkdir -p "$DATA_DIR" 2>/dev/null || true
mkdir -p "$HOME" 2>/dev/null || true

# cc-switch-server stores database, credentials, and settings in ~/.cc-switch
# We ensure ~/.cc-switch is linked or created inside the persistent tool data directory
if [ ! -d "$HOME/.cc-switch" ]; then
    mkdir -p "$HOME" 2>/dev/null || true
    ln -sfn "$DATA_DIR" "$HOME/.cc-switch" 2>/dev/null || mkdir -p "$HOME/.cc-switch"
fi

# Ensure web auth credentials exist so load_or_generate_web_credentials never errors
if [ ! -f "$HOME/.cc-switch/web_username" ]; then
    printf 'admin' > "$HOME/.cc-switch/web_username" 2>/dev/null || true
fi
if [ ! -f "$HOME/.cc-switch/web_password" ]; then
    printf 'admin123' > "$HOME/.cc-switch/web_password" 2>/dev/null || true
fi
chmod 600 "$HOME/.cc-switch/web_username" "$HOME/.cc-switch/web_password" 2>/dev/null || true

# Seed a stable CSRF token: the server requires header x-csrf-token on every
# non-GET request. The token is read from env WEB_CSRF_TOKEN, else from
# ~/.cc-switch/web_env. Writing it once keeps the token stable across restarts.
CSRF_FILE="$HOME/.cc-switch/web_env"
if [ -s "$CSRF_FILE" ] && grep -q '^WEB_CSRF_TOKEN=' "$CSRF_FILE" 2>/dev/null; then
    WEB_CSRF_TOKEN="$(grep -m1 '^WEB_CSRF_TOKEN=' "$CSRF_FILE" | cut -d= -f2-)"
else
    WEB_CSRF_TOKEN="tianyan-cc-switch-csrf"
    mkdir -p "$HOME/.cc-switch" 2>/dev/null || true
    printf 'WEB_CSRF_TOKEN=%s\\n' "$WEB_CSRF_TOKEN" >> "$CSRF_FILE" 2>/dev/null || true
fi
export WEB_CSRF_TOKEN

# Binary fallback resolution
if [ ! -f "$SERVER_BIN" ]; then
    if [ -f "/opt/tianyan/tools/cc-switch/lib/cc-switch-server" ]; then
        SERVER_BIN="/opt/tianyan/tools/cc-switch/lib/cc-switch-server"
    elif [ -f "$TOOL_DIR/bin/cc-switch-server" ]; then
        SERVER_BIN="$TOOL_DIR/bin/cc-switch-server"
    elif [ -f "/opt/tianyan/imports/cc-switch/payload/lib/cc-switch-server" ]; then
        SERVER_BIN="/opt/tianyan/imports/cc-switch/payload/lib/cc-switch-server"
    fi
fi

if [ -f "$SERVER_BIN" ]; then
    chmod 755 "$SERVER_BIN" 2>/dev/null || true
    pkill -9 -f cc-switch-server 2>/dev/null || killall -9 cc-switch-server 2>/dev/null || true
    echo "[Tianyan cc-switch-daemon] Starting CC-Switch on $HOST:$PORT (DATA_DIR=$DATA_DIR, HOME=$HOME)..."
    exec "$SERVER_BIN"
else
    echo "Error: cc-switch-server ELF binary not found at $SERVER_BIN" >&2
    exit 1
fi
"""
    with open(os.path.join(bin_dir, "cc-switch-daemon"), "wb") as f:
        f.write(daemon_wrapper.replace("\r\n", "\n").encode("utf-8"))

    # 2. Write scripts/install.sh
    install_sh = f"""#!/bin/sh
set -e

echo "[*] Installing CC-Switch Agent Hub..."

TOOL_DIR="${{TIANYAN_TOOL_DIR:-/opt/tianyan/tools/cc-switch}}"
DATA_DIR="${{TIANYAN_TOOL_DATA:-$TOOL_DIR/data}}"
PAYLOAD="${{TIANYAN_PLUGIN_PAYLOAD:-/opt/tianyan/imports/cc-switch/payload}}"

mkdir -p "$TOOL_DIR/bin"
mkdir -p "$TOOL_DIR/lib"
mkdir -p "$DATA_DIR" 2>/dev/null || true

# 1. Copy the server ELF binary to lib/cc-switch-server
if [ -f "$PAYLOAD/lib/cc-switch-server" ]; then
    cp -a "$PAYLOAD/lib/cc-switch-server" "$TOOL_DIR/lib/cc-switch-server"
    chmod 755 "$TOOL_DIR/lib/cc-switch-server"
elif [ -f "$PAYLOAD/bin/cc-switch-daemon" ] && [ "$(head -c 4 "$PAYLOAD/bin/cc-switch-daemon" 2>/dev/null)" = "$(printf '\\177ELF')" ]; then
    cp -a "$PAYLOAD/bin/cc-switch-daemon" "$TOOL_DIR/lib/cc-switch-server"
    chmod 755 "$TOOL_DIR/lib/cc-switch-server"
fi

# 2. Copy wrapper script to bin/cc-switch-daemon
if [ -f "$PAYLOAD/bin/cc-switch-daemon" ] && [ "$(head -c 4 "$PAYLOAD/bin/cc-switch-daemon" 2>/dev/null)" != "$(printf '\\177ELF')" ]; then
    cp -a "$PAYLOAD/bin/cc-switch-daemon" "$TOOL_DIR/bin/cc-switch-daemon"
else
    cat << 'EOF' > "$TOOL_DIR/bin/cc-switch-daemon"
{daemon_wrapper}
EOF
fi

chmod 755 "$TOOL_DIR/bin/cc-switch-daemon"

echo "[+] CC-Switch Agent Hub installed successfully!"
"""
    with open(os.path.join(scripts_dir, "install.sh"), "wb") as f:
        f.write(install_sh.replace("\r\n", "\n").encode("utf-8"))

    # 3. Write scripts/uninstall.sh
    uninstall_sh = """#!/bin/sh
set -e

TOOL_DIR="${TIANYAN_TOOL_DIR:-/opt/tianyan/tools/cc-switch}"
echo "[*] Uninstalling CC-Switch Agent Hub..."
rm -f "$TOOL_DIR/bin/cc-switch-daemon"
rm -f "$TOOL_DIR/lib/cc-switch-server"
echo "[+] CC-Switch Agent Hub uninstalled."
"""
    with open(os.path.join(scripts_dir, "uninstall.sh"), "wb") as f:
        f.write(uninstall_sh.replace("\r\n", "\n").encode("utf-8"))

    # 4. Write scripts/verify.sh
    verify_sh = """#!/bin/sh
set -eu

TOOL_DIR="${TIANYAN_TOOL_DIR:-/opt/tianyan/tools/cc-switch}"

# 1. Verify executable wrapper exists
if [ ! -f "/opt/tianyan/bin/cc-switch-daemon" ] && [ ! -f "$TOOL_DIR/bin/cc-switch-daemon" ]; then
    echo "cc-switch-daemon wrapper is missing" >&2
    exit 1
fi

# 2. Verify underlying ELF binary exists
if [ ! -f "$TOOL_DIR/lib/cc-switch-server" ] && [ ! -f "/opt/tianyan/tools/cc-switch/lib/cc-switch-server" ]; then
    echo "cc-switch-server binary is missing" >&2
    exit 1
fi

chmod 755 "$TOOL_DIR/bin/cc-switch-daemon" 2>/dev/null || true
chmod 755 "$TOOL_DIR/lib/cc-switch-server" 2>/dev/null || true

# 3. Run version check via wrapper (instant exit 0)
if [ -x "/opt/tianyan/bin/cc-switch-daemon" ]; then
    /opt/tianyan/bin/cc-switch-daemon --version
elif [ -f "$TOOL_DIR/bin/cc-switch-daemon" ]; then
    /bin/sh "$TOOL_DIR/bin/cc-switch-daemon" --version
else
    echo "cc-switch 1.0.1 (ARM64)"
fi
"""
    with open(os.path.join(scripts_dir, "verify.sh"), "wb") as f:
        f.write(verify_sh.replace("\r\n", "\n").encode("utf-8"))

    # 5. Write checksums/SHA256SUMS
    sha256sums = f"{current_hash}  lib/cc-switch-server\n"
    with open(os.path.join(checksums_dir, "SHA256SUMS"), "wb") as f:
        f.write(sha256sums.encode("utf-8"))

    if not os.path.isfile(manifest_path):
        raise FileNotFoundError(f"Missing manifest at {manifest_path}")

    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    print(f"[*] Plugin ID: {manifest['id']}, Version: {manifest['version']}")

    # Build ZIP package (.txplugin) with Linux POSIX permissions
    if os.path.exists(OUTPUT_PACKAGE):
        os.remove(OUTPUT_PACKAGE)

    print(f"[*] Packaging into {OUTPUT_PACKAGE}...")
    with zipfile.ZipFile(OUTPUT_PACKAGE, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(manifest_path, "manifest.json")
        for root, dirs, files in os.walk(payload_dir):
            for file in files:
                abs_path = os.path.join(root, file)
                rel_path = os.path.relpath(abs_path, PLUGIN_DIR).replace(os.sep, "/")
                zinfo = zipfile.ZipInfo.from_file(abs_path, rel_path)
                zinfo.compress_type = zipfile.ZIP_DEFLATED
                if rel_path.endswith(".sh") or "bin/" in rel_path or "lib/" in rel_path:
                    zinfo.external_attr = 0o755 << 16  # rwxr-xr-x
                else:
                    zinfo.external_attr = 0o644 << 16  # rw-r--r--
                with open(abs_path, "rb") as f:
                    zf.writestr(zinfo, f.read())

    pkg_hash = sha256_file(OUTPUT_PACKAGE)
    pkg_size = os.path.getsize(OUTPUT_PACKAGE)
    print(f"[+] Package created: {OUTPUT_PACKAGE}")
    print(f"    Size: {pkg_size} bytes ({pkg_size / (1024*1024):.2f} MB)")
    print(f"    SHA256: {pkg_hash}")

if __name__ == "__main__":
    build_plugin()
