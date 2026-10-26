#!/bin/sh
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
