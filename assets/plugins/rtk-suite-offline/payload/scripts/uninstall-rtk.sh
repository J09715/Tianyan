#!/bin/sh
set -eu

TOOL_DIR="${TIANYAN_TOOL_DIR:?missing TIANYAN_TOOL_DIR}"

rm -f /opt/tianyan/bin/rtk
rm -f "$TOOL_DIR/bin/rtk"
rm -rf /opt/tianyan/data/rtk
echo "RTK 终端命令优化插件已卸载"
