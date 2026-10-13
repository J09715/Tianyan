#!/bin/sh
# our-free-model 自检 —— 安装后与卸载前都会跑，也可手动执行。
#
# 只做「不启动服务」的静态检查：文件到位、Node 版本够、ESM 能加载。
# 不在这里拉起服务——那会占住 manifest.servicePort，干扰随后的启动流程。
set -eu

APP_ROOT="/opt/tianyan/our-free-model"
APP_DIR="$APP_ROOT/app"
DATA_DIR="/opt/tianyan/data/our-free-model"

fail() { printf '自检失败：%s\n' "$*" >&2; exit 1; }
ok() { printf '  ✓ %s\n' "$*"; }

echo "[OFM 自检]"

[ -d "$APP_DIR" ] || fail "应用目录不存在：$APP_DIR"
ok "应用目录 $APP_DIR"

for required in \
    "packages/standalone/cli.mjs" \
    "packages/standalone/service.mjs" \
    "packages/standalone/management.mjs" \
    "packages/standalone/web/index.html" \
    "packages/standalone/web/assets.json" \
    "packages/standalone/channels/business.mjs" \
    "src/forward.js" \
    "src/core/runtime.js" \
    "src/providers/registry.json" \
    "patches-ipv4-first.mjs"
do
    [ -e "$APP_DIR/$required" ] || fail "缺少关键文件：$required"
done
ok "关键文件齐全（含 13 渠道包与 Web 后台）"

[ -x "$APP_ROOT/start.sh" ] || fail "启动脚本不可执行：$APP_ROOT/start.sh"
ok "启动脚本 $APP_ROOT/start.sh"

[ -d "$DATA_DIR" ] || fail "数据目录不存在：$DATA_DIR"
ok "数据目录 $DATA_DIR"

if command -v node >/dev/null 2>&1; then
    NODE_VERSION="$(node -v)"
    NODE_MAJOR="$(printf '%s' "$NODE_VERSION" | sed -n 's/^v\([0-9]*\).*/\1/p')"
    [ -n "$NODE_MAJOR" ] || fail "无法解析 Node 版本：$NODE_VERSION"
    [ "$NODE_MAJOR" -ge 22 ] || fail "Node 版本过低：$NODE_VERSION（需要 22.0+）"
    ok "Node $NODE_VERSION"

    # 真正加载一次入口模块：能捕获语法与缺失依赖问题，且不绑定端口。
    if (cd "$APP_DIR" && node --input-type=module -e "
        import { startStandalone } from './packages/standalone/service.mjs'
        if (typeof startStandalone !== 'function') { console.error('startStandalone 不可用'); process.exit(1) }
    " >/dev/null 2>&1); then
        ok "入口模块可加载（startStandalone 导出正常）"
    else
        fail "入口模块加载失败；请在沙箱内手动执行以查看报错：cd $APP_DIR && node --input-type=module -e \"import('./packages/standalone/service.mjs')\""
    fi
else
    printf '  ! 未检测到 node —— 启动前请先安装 Node 运行时（需 22.0+）\n'
fi

echo "[OFM 自检] 通过"
