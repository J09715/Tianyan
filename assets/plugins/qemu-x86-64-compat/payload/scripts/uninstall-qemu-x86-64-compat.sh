#!/bin/sh
set -eu

TOOL_DIR="${TIANYAN_TOOL_DIR:?missing TIANYAN_TOOL_DIR}"
COMPAT_ROOT="/opt/tianyan/compat/x86_64"

rm -f /opt/tianyan/bin/qemu-x86_64

rm -rf "$TOOL_DIR"
rm -rf "$COMPAT_ROOT"

echo "QEMU x86_64 user-mode 插件已卸载"
