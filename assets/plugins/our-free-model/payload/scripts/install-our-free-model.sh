#!/bin/sh
# our-free-model 安装脚本 —— 由 GenericRecipeInstaller 在沙箱内以 TIANYAN_PLUGIN_PAYLOAD 调用。
#
# 部署布局（全部落在 /opt/tianyan 下，随发行版隔离）：
#   /opt/tianyan/our-free-model/app/     应用主体（Node ESM，零 npm 依赖）
#   /opt/tianyan/our-free-model/start.sh 启动脚本（manifest.launchCommand 指向它）
#   /opt/tianyan/bin/ofm                 命令入口
#   /opt/tianyan/data/our-free-model/    用户数据（配置、API Key、用量）
set -eu

PAYLOAD="${TIANYAN_PLUGIN_PAYLOAD:?missing TIANYAN_PLUGIN_PAYLOAD}"
TOOL_DIR="${TIANYAN_TOOL_DIR:?missing TIANYAN_TOOL_DIR}"

APP_ROOT="/opt/tianyan/our-free-model"
APP_DIR="$APP_ROOT/app"
DATA_DIR="/opt/tianyan/data/our-free-model"

need() { test -e "$1" || { echo "缺少离线资源：${2:-$1}" >&2; exit 2; }; }
progress() { percent="$1"; shift; printf '[TIANYAN_PROGRESS:%s] %s\n' "$percent" "$*"; }

need "$PAYLOAD/app/packages/standalone/cli.mjs" "app/packages/standalone/cli.mjs"
need "$PAYLOAD/app/src/forward.js" "app/src/forward.js"
need "$PAYLOAD/scripts/start-our-free-model.sh"
need "$PAYLOAD/checksums/SHA256SUMS"

progress 10 "[VERIFY] 校验离线资源哈希"
(cd "$PAYLOAD" && sha256sum -c checksums/SHA256SUMS)

progress 45 "[INSTALL] 部署应用主体"
mkdir -p "$APP_ROOT" "$DATA_DIR" "$TOOL_DIR/bin" /opt/tianyan/bin
rm -rf "$APP_DIR"
cp -R "$PAYLOAD/app" "$APP_DIR"

progress 70 "[INSTALL] 安装启动脚本与命令入口"
cp "$PAYLOAD/scripts/start-our-free-model.sh" "$APP_ROOT/start.sh"
chmod 755 "$APP_ROOT/start.sh"
ln -sfn "$APP_ROOT/start.sh" "$TOOL_DIR/bin/ofm"
ln -sfn "$APP_ROOT/start.sh" /opt/tianyan/bin/ofm
chmod 700 "$DATA_DIR"

progress 85 "[VERIFY] 检查 Node 运行时"
if command -v node >/dev/null 2>&1; then
    printf 'Node: %s\n' "$(node -v)"
else
    # 不在这里装 Node：沙箱的包管理器在 PRoot 下易失败，且天衍的依赖体系
    # （RuntimeName.NODE）会负责供给。这里只提示，启动脚本会给出明确报错。
    printf '提示：当前未检测到 node，请先在天衍「插件与工具中心」安装 Node 运行时后再启动本插件。\n'
fi

progress 95 "[VERIFY] 运行自检"
/bin/sh "$PAYLOAD/scripts/verify-our-free-model.sh"
progress 100 "[VERIFY] Our Free Model 免费模型网关已就绪"
