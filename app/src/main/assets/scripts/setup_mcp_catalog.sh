#!/bin/sh
# MCP 能力仓库装配 / 更新 / 启动脚本
#
# 由 core/model/McpCatalog.kt 的条目生成调用，不在脚本里硬编码任何包名或版本，
# 保证「改一条 registry 记录就换版本」而不用重发 APK。
#
# 用法：
#   setup_mcp_catalog.sh install <id> <pkgSpec> <expectedIntegrity>
#   setup_mcp_catalog.sh check   <id> <pkgName>
#   setup_mcp_catalog.sh upgrade <id> <pkgName>
#   setup_mcp_catalog.sh probe   <id> <pkgName>
#   setup_mcp_catalog.sh run     <id> <pkgName> [serverArgs...]
#
# 落位标记 /opt/tianyan/mcp/<id>.ok 是**安装状态的唯一真相**，格式为 key=value：
#   NAME=open-websearch
#   VERSION=2.2.0
#   SPEC=open-websearch@2.2.0
#   INTEGRITY=sha512-...
#
# 由此得到三条关键性质：
#   1. 探针（probe）只在本机核对落位标记与包入口，启动期不问 registry、不联网现拉，
#      避免工具发现阶段冷启动下载导致超时；「数据库说装了、文件其实不在」也会当场暴露；
#   2. 运行（run）从标记里读实际版本，而不是从代码里读，否则「升级」只改了全局安装、
#      进程仍按旧 pin 拉起，升级会退化成假动作；
#   3. 升级只重写标记即刻生效，不需要重新发版。
set -u

CMD="${1:-}"
ID="${2:-}"
REGISTRY="${TIANYAN_MCP_NPM_REGISTRY:-https://registry.npmmirror.com}"
MARKER_DIR="/opt/tianyan/mcp"
MARKER="$MARKER_DIR/$ID.ok"

case "$ID" in
  ''|*[!A-Za-z0-9_-]*) printf 'Invalid capability id\n' >&2; exit 1 ;;
esac

log() { printf '[MCP] %s\n' "$1"; }
fail() { printf '[MCP] 错误：%s\n' "$1" >&2; exit 1; }

# 读取落位标记里的一个字段（标记缺失或字段不存在时输出空串）。
marker_get() {
  [ -s "$MARKER" ] || return 0
  sed -n "s/^$1=//p" "$MARKER" 2>/dev/null | head -n 1
}

# 当前实际安装的完整规格（兼容早期只写一行 SPEC 的标记格式）。
installed_spec() {
  s="$(marker_get SPEC)"
  if [ -z "$s" ] && [ -s "$MARKER" ]; then
    s="$(tr -d '\r\n' < "$MARKER")"
  fi
  printf '%s' "$s"
}

# 当前实际安装的版本号。
installed_version() {
  v="$(marker_get VERSION)"
  if [ -z "$v" ]; then
    s="$(installed_spec)"
    if [ -n "$s" ]; then v="${s##*@}"; fi
  fi
  printf '%s' "$v"
}

# 原子写落位标记：先写同目录临时文件再 rename，避免探针读到半截内容。
write_marker() {
  name_s="${1%@*}"
  ver_s="${1##*@}"
  tmp="$MARKER.tmp.$$"
  printf 'NAME=%s\nVERSION=%s\nSPEC=%s\nINTEGRITY=%s\n' "$name_s" "$ver_s" "$1" "$2" > "$tmp" \
    || fail "无法写入落位标记 $MARKER"
  mv -f "$tmp" "$MARKER" || fail "无法写入落位标记 $MARKER"
}

# 固定版本守卫：取最后一个 "@" 之后的片段，必须存在且以数字开头，
# 才能同时挡住 latest / * / 裸包名 / 非版本号 dist-tag 四种写法。
require_pinned() {
  ver="${1##*@}"
  case "$ver" in
    [0-9]*) printf '%s' "$ver" ;;
    *) fail "包规格必须带固定版本号（禁止 latest / * / 裸包名）：$1" ;;
  esac
}

require_npm() {
  command -v npm >/dev/null 2>&1 || fail "npm 不可用，请先安装「Node.js 核心运行时」组件"
}

# 从镜像读取某个固定版本已发布 tarball 的校验和；查不到就返回空串。
remote_integrity() {
  npm view "$1" dist.integrity --registry="$REGISTRY" 2>/dev/null | tr -d '\r\n'
}

# 从镜像读取上游最新版本号；查不到就返回空串。
remote_latest() {
  npm view "$1" version --registry="$REGISTRY" 2>/dev/null | tr -d '\r\n'
}

# 本地探针核对实际包版本与入口，不联网、不执行包代码，也不认 PATH 中的同名命令。
resolve_entry() {
  require_npm
  command -v node >/dev/null 2>&1 || fail "Node.js 不可用"
  spec="$(installed_spec)"
  [ -n "$spec" ] || fail "能力 $ID 尚未安装"
  root="$(npm root -g 2>/dev/null)" || fail "无法定位全局包目录"
  node -e '
    const fs = require("fs"), path = require("path");
    const [root, spec, expected] = process.argv.slice(1);
    const match = spec.match(/^((?:@[a-z0-9._-]+\/)?[a-z0-9._-]+)@(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?)$/);
    if (!match || match[1] !== expected) process.exit(2);
    const dir = path.resolve(root, match[1]);
    const p = JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8"));
    if (p.name !== match[1] || p.version !== match[2]) process.exit(3);
    const bins = typeof p.bin === "string" ? [p.bin] : Object.values(p.bin || {});
    if (bins.length !== 1 || typeof bins[0] !== "string") process.exit(4);
    const entry = path.resolve(dir, bins[0]);
    if (!entry.startsWith(dir + path.sep) || !fs.statSync(entry).isFile()) process.exit(5);
    process.stdout.write(entry);
  ' "$root" "$spec" "$1" || fail "能力 $ID 的已装版本或启动入口无效，请重新安装"
}

case "$CMD" in
  install)
    SPEC="${3:-}"
    EXPECTED="${4:-}"
    [ -n "$ID" ] || fail "缺少能力 id"
    [ -n "$SPEC" ] || fail "缺少包规格"
    [ -n "$EXPECTED" ] || fail "缺少期望校验和"
    require_pinned "$SPEC" >/dev/null
    require_npm

    log "核对 $SPEC 的发布摘要（镜像：$REGISTRY）"
    ACTUAL="$(remote_integrity "$SPEC")"
    [ -n "$ACTUAL" ] || fail "无法从镜像读取 $SPEC 的 dist.integrity（请检查网络与镜像可用性）"
    if [ "$ACTUAL" != "$EXPECTED" ]; then
      fail "校验和不匹配：期望 $EXPECTED，实际 $ACTUAL"
    fi

    log "校验通过，开始全局安装 $SPEC"
    npm install -g "$SPEC" --registry="$REGISTRY" --no-audit --no-fund || fail "安装失败：$SPEC"

    mkdir -p "$MARKER_DIR" || fail "无法创建落位标记目录 $MARKER_DIR"
    write_marker "$SPEC" "$ACTUAL"
    resolve_entry "${SPEC%@*}" >/dev/null
    if [ "$ID" = mcp_memory ]; then
      mkdir -p /opt/tianyan/data/mcp-memory || fail "无法创建 Memory 存储目录"
    fi
    log "$ID 已就绪（$SPEC）"
    ;;

  check)
    PKG="${3:-}"
    [ -n "$ID" ] || fail "缺少能力 id"
    [ -n "$PKG" ] || fail "缺少包名"
    # 先输出已装版本，即使后续联网失败也能让上层判断出「未安装」，不至于整条状态丢失。
    printf 'INSTALLED=%s\n' "$(installed_version)"
    if ! command -v npm >/dev/null 2>&1; then
      printf 'ERROR=Node.js 运行时不可用，无法检查更新\n'
      exit 1
    fi
    LATEST="$(remote_latest "$PKG")"
    if [ -z "$LATEST" ]; then
      printf 'ERROR=无法从镜像读取 %s 的最新版本（请检查网络与镜像可用性）\n' "$PKG"
      exit 1
    fi
    printf 'LATEST=%s\n' "$LATEST"
    ;;

  upgrade)
    PKG="${3:-}"
    [ -n "$ID" ] || fail "缺少能力 id"
    [ -n "$PKG" ] || fail "缺少包名"
    require_npm

    log "查询 $PKG 的上游最新版本（镜像：$REGISTRY）"
    LATEST="$(remote_latest "$PKG")"
    [ -n "$LATEST" ] || fail "无法从镜像读取 $PKG 的最新版本（请检查网络与镜像可用性）"
    require_pinned "$PKG@$LATEST" >/dev/null

    if [ "$LATEST" = "$(installed_version)" ]; then
      resolve_entry "$PKG" >/dev/null
      printf 'INSTALLED=%s\nLATEST=%s\nCHANGED=0\n' "$LATEST" "$LATEST"
      log "$ID 已是最新版本（$LATEST），无需升级"
      exit 0
    fi

    SPEC="$PKG@$LATEST"
    log "读取 $SPEC 的发布摘要"
    ACTUAL="$(remote_integrity "$SPEC")"
    [ -n "$ACTUAL" ] || fail "无法从镜像读取 $SPEC 的 dist.integrity（请检查网络与镜像可用性）"

    log "开始升级 $ID → $LATEST"
    npm install -g "$SPEC" --registry="$REGISTRY" --no-audit --no-fund || fail "升级失败：$SPEC"

    mkdir -p "$MARKER_DIR" || fail "无法创建落位标记目录 $MARKER_DIR"
    # 校验和随版本一起落盘：后续任何一次按标记重装都会拿它做比对，上游若偷偷改包会被挡住。
    write_marker "$SPEC" "$ACTUAL"
    # 升级后同样核对入口：新版若把 bin 移出包目录或改了名字，应当在这里当场失败，
    # 而不是等到会话里 MCP 进程起不来才发现。
    resolve_entry "$PKG" >/dev/null
    printf 'INSTALLED=%s\nLATEST=%s\nCHANGED=1\n' "$LATEST" "$LATEST"
    log "$ID 已升级到 $LATEST"
    ;;

  probe)
    resolve_entry "${3:-}" >/dev/null
    ;;
  run)
    BIN="$(resolve_entry "${3:-}")" || exit 1
    shift 3
    exec node "$BIN" "$@"
    ;;

  *)
    fail "未知操作：${CMD:-<空>}（支持 install / check / upgrade / probe / run）"
    ;;
esac
