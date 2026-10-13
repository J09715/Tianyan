#!/bin/sh
# our-free-model 启动脚本 —— 由天衍 launchCommand 调用，也供手动启动。
#
# 契约（与 GenericRecipeInstaller.startService 对齐）：
#   · 必须前台常驻：天衍把本进程当作服务会话持有，脚本返回即视为服务退出；
#   · 必须在 $OFM_PORT 上监听：ToolServiceController 靠端口探测判定就绪，
#     超时未监听会主动杀掉进程并报「网关未在 N 秒内就绪」；
#   · 幂等：重复调用不得起第二个实例（端口占用时直接退出并说明）。
#
# 环境变量由 manifest.environment 注入：
#   OFM_HOME 数据目录，OFM_PORT 监听端口，NODE_OPTIONS 挂 IPv4 修复补丁。
set -eu

APP_DIR="/opt/tianyan/our-free-model/app"
OFM_HOME="${OFM_HOME:-/opt/tianyan/data/our-free-model}"
OFM_PORT="${OFM_PORT:-18900}"
PATCH="$APP_DIR/patches-ipv4-first.mjs"

log() { printf '[OFM] %s\n' "$*"; }

[ -d "$APP_DIR" ] || { log "缺少应用目录 $APP_DIR，请重新安装插件"; exit 2; }
mkdir -p "$OFM_HOME"

# 解析 Node：优先 PATH，回退到沙箱常见位置。
NODE_BIN=""
for candidate in node /usr/local/bin/node /usr/bin/node /opt/tianyan/bin/node; do
    if command -v "$candidate" >/dev/null 2>&1; then NODE_BIN="$candidate"; break; fi
done
[ -n "$NODE_BIN" ] || { log "未找到 Node 运行时；本插件需要 Node 22.4+（沙箱内 node 或经插件依赖安装）"; exit 3; }

NODE_VERSION="$("$NODE_BIN" -v 2>/dev/null || echo unknown)"
log "Node: $NODE_BIN ($NODE_VERSION)"

# 版本闸门：独立服务用到 Promise.withResolvers(22.0+) 与 AbortSignal.any(20.3+)。
# 低于 22 直接拒绝，避免以「启动后立刻退出」的形式失败——那种现象在服务日志里
# 只留下一句语焉不详的语法错误。
NODE_MAJOR="$(printf '%s' "$NODE_VERSION" | sed -n 's/^v\([0-9]*\).*/\1/p')"
if [ -n "$NODE_MAJOR" ] && [ "$NODE_MAJOR" -lt 22 ]; then
    log "Node 版本过低（$NODE_VERSION）：需要 22.0 或更高"
    exit 3
fi

# 端口占用即认为已有实例在跑，直接退出——startService 的幂等由这里兜底。
if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":${OFM_PORT}[[:space:]]"; then
    log "端口 ${OFM_PORT} 已在监听，视为实例已运行，跳过启动"
    exit 0
fi

# 只对本次调用启用 IPv4 修复补丁：该网络下 opencode.ai 的 AAAA 记录不可达，
# 且四个 A 记录里只有一个能完成 TLS 握手，补丁会在解析后探测并剔除坏地址。
if [ -f "$PATCH" ]; then
    NODE_OPTIONS="--import=$PATCH"
    export NODE_OPTIONS
    log "已启用上游连通性修复补丁"
fi

log "启动独立服务：端口 ${OFM_PORT} · 数据目录 ${OFM_HOME}"
cd "$APP_DIR"
exec "$NODE_BIN" packages/standalone/cli.mjs --port "$OFM_PORT" --data-dir "$OFM_HOME"
