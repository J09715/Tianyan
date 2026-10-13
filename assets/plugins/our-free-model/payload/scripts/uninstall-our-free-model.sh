#!/bin/sh
# our-free-model 卸载脚本。
#
# 默认保留用户数据（配置、账号、API Key、用量统计）：卸载插件不等于放弃账号，
# 重装后应能直接接续。只有天衍显式传 deleteData=true 时才清理数据目录
# （GenericRecipeInstaller 会在数据目录上自行追加 rm -rf，这里只清应用主体）。
set -eu

APP_ROOT="/opt/tianyan/our-free-model"
TOOL_DIR="${TIANYAN_TOOL_DIR:-/opt/tianyan/tools/our-free-model}"

echo "[OFM 卸载] 停止残留进程"
# 只清理本插件自己的进程：按启动脚本的绝对路径匹配，不按端口强杀
# （端口可能被用户改成别的服务在用）。
if command -v pkill >/dev/null 2>&1; then
    pkill -f "$APP_ROOT/start.sh" 2>/dev/null || true
    pkill -f "$APP_ROOT/app/packages/standalone/cli.mjs" 2>/dev/null || true
fi

echo "[OFM 卸载] 移除命令入口"
rm -f "$TOOL_DIR/bin/ofm" /opt/tianyan/bin/ofm

echo "[OFM 卸载] 移除应用主体"
rm -rf "$APP_ROOT"

echo "[OFM 卸载] 完成（用户数据保留在 /opt/tianyan/data/our-free-model）"
