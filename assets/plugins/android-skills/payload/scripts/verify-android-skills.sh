#!/bin/sh
# 天衍本地插件 · Android CLI 与官方技能（android-skills）安装后校验
#
# 顺序刻意如此：先做静态 ELF / 文件断言，再真正执行一次 CLI，
# 避免“文件在但根本跑不起来”被记为安装成功。全部步骤都不需要网络。
set -eu

TOOL_DIR="${TIANYAN_TOOL_DIR:-/opt/tianyan/tools/android-skills}"
TOOL_DATA="${TIANYAN_TOOL_DATA:-/opt/tianyan/data/android-skills}"
ANDROID_USER_HOME_DIR="$TOOL_DATA/android-user-home"
SKILLS_COLLECTION="/attachments/skills/android-skills"
MIN_SKILL_COUNT=33

fail() { echo "$1" >&2; exit 1; }
require_aarch64() {
    test -f "$1" || fail "missing file: $1"
    test "$(od -An -t x1 -N 4 "$1" 2>/dev/null | tr -d ' \n')" = "7f454c46" || fail "not an ELF file: $1"
    test "$(od -An -t x1 -j 18 -N 2 "$1" 2>/dev/null | tr -d ' \n')" = "b700" || fail "not an AArch64 ELF: $1"
}

require_aarch64 "$TOOL_DIR/lib/android-launcher"
require_aarch64 "$ANDROID_USER_HOME_DIR/bin/android-cli"
test -x "$TOOL_DIR/bin/android" || fail "android 包装层缺失或不可执行：$TOOL_DIR/bin/android"
test -d "$ANDROID_USER_HOME_DIR/cli/bundles" || fail "内嵌安装尚未解包：$ANDROID_USER_HOME_DIR/cli/bundles"

# 真跑一次（离线）：包装层注入 --no-metrics，并固定插件自有的 ANDROID_USER_HOME。
info_output=$(ANDROID_USER_HOME="$ANDROID_USER_HOME_DIR" "$TOOL_DIR/bin/android" info 2>/dev/null) ||
    fail "android info 执行失败：包装层或 android-cli 载荷不可用"
echo "$info_output" | grep -q '^version:' || fail "android info 输出异常：$info_output"

# 命令入口链路（由安装器在安装步骤之后创建，因此允许尚未就绪，但存在时必须指向本插件）
if [ -e /opt/tianyan/bin/android ]; then
    entry=$(readlink -f /opt/tianyan/bin/android 2>/dev/null || echo "")
    case "$entry" in
        "$TOOL_DIR/bin/android"|/opt/tianyan/bin/android) ;;
        *) fail "命令入口 /opt/tianyan/bin/android 指向了非本插件目标：$entry" ;;
    esac
fi

skill_count=$(find "$SKILLS_COLLECTION" -type f -name 'SKILL.md' | wc -l | tr -d ' ')
test "$skill_count" -ge "$MIN_SKILL_COUNT" ||
    fail "技能目录不完整：/attachments/skills/android-skills 下只有 $skill_count 个 SKILL.md（期望至少 $MIN_SKILL_COUNT）"
