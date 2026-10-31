#!/bin/sh
# 天衍本地插件 · Android CLI 与官方技能（android-skills）安装配方
#
# 设计要点（均为实测结论，详见 UPSTREAM.md 与 docs/ANDROID_SKILLS_PLUGIN.md）：
#   - linux_arm64/android 只是引导器（约 3.97 MiB），缺少 android-cli 时会联网下载
#     约 85.63 MiB 的真身；本插件把两者一起内置，装配期即完成离线就位。
#   - 首次运行会就地解包内嵌安装（main.jar + 自带 JRE，约 70 MB）到
#     $ANDROID_USER_HOME/cli/bundles/<sha>/；该解包不联网，因此这里主动触发一次，
#     避免把耗时算进 60 秒超时的 verifyCommand。
#   - 33 个官方技能落到 /attachments/skills/android-skills/，即天衍技能自动发现的扫描根。
set -eu

PAYLOAD="${TIANYAN_PLUGIN_PAYLOAD:?missing TIANYAN_PLUGIN_PAYLOAD}"
TOOL_DIR="${TIANYAN_TOOL_DIR:?missing TIANYAN_TOOL_DIR}"
TOOL_DATA="${TIANYAN_TOOL_DATA:?missing TIANYAN_TOOL_DATA}"

ANDROID_USER_HOME_DIR="$TOOL_DATA/android-user-home"
SKILLS_ROOT="/attachments/skills"
SKILLS_COLLECTION="$SKILLS_ROOT/android-skills"
SKILLS_STAGING="$SKILLS_ROOT/android-skills.staging"
SKILLS_PREVIOUS="$SKILLS_ROOT/android-skills.previous"
MIN_SKILL_COUNT=33

progress() { printf '[TIANYAN_PROGRESS:%s] %s\n' "$1" "$2"; }
fail() { echo "$1" >&2; exit 1; }
need() { test -s "$1" || fail "missing offline resource: ${2:-$1}"; }

# POSIX ELF64 AArch64 断言：e_machine 位于偏移 18，AArch64 = 183（小端 b7 00）
elf_bytes() { od -An -t x1 "$@" 2>/dev/null | tr -d ' \n'; }
require_aarch64() {
    test -f "$1" || fail "missing file: $1"
    test "$(elf_bytes -N 4 "$1")" = "7f454c46" || fail "not an ELF file: $1"
    test "$(elf_bytes -j 18 -N 2 "$1")" = "b700" || fail "not an AArch64 ELF: $1"
}

cleanup_staging() {
    rm -rf "$SKILLS_STAGING"
    rm -rf "$ANDROID_USER_HOME_DIR/bin/android-cli.staging"
}
trap cleanup_staging EXIT HUP INT TERM

progress 5 "[VERIFY] 校验内置引导器 / android-cli 载荷哈希"
need "$PAYLOAD/checksums/SHA256SUMS"
need "$PAYLOAD/bin/android-launcher" "Android CLI 引导器 (payload/bin/android-launcher)"
need "$PAYLOAD/bin/android-cli" "Android CLI 真身 (payload/bin/android-cli)"
need "$PAYLOAD/scripts/android-wrapper.sh"
test -d "$PAYLOAD/skills" || fail "missing offline resource: payload/skills（官方技能目录）"
(cd "$PAYLOAD" && sha256sum -c checksums/SHA256SUMS >/dev/null) || fail "内置载荷 SHA-256 校验失败"

progress 20 "[COPY] 部署 android 包装层与引导器"
mkdir -p "$TOOL_DIR/bin" "$TOOL_DIR/lib" "$TOOL_DATA"
cp "$PAYLOAD/scripts/android-wrapper.sh" "$TOOL_DIR/bin/android"
chmod 755 "$TOOL_DIR/bin/android"
cp "$PAYLOAD/bin/android-launcher" "$TOOL_DIR/lib/android-launcher"
chmod 755 "$TOOL_DIR/lib/android-launcher"
require_aarch64 "$TOOL_DIR/lib/android-launcher"

progress 35 "[COPY] 预置 android-cli 真身（离线可用，杜绝首次运行联网下载 85 MB）"
mkdir -p "$ANDROID_USER_HOME_DIR/bin"
rm -f "$ANDROID_USER_HOME_DIR/bin/android-cli.staging"
cp "$PAYLOAD/bin/android-cli" "$ANDROID_USER_HOME_DIR/bin/android-cli.staging"
chmod 755 "$ANDROID_USER_HOME_DIR/bin/android-cli.staging"
require_aarch64 "$ANDROID_USER_HOME_DIR/bin/android-cli.staging"
mv "$ANDROID_USER_HOME_DIR/bin/android-cli.staging" "$ANDROID_USER_HOME_DIR/bin/android-cli"

progress 50 "[INSTALL] 首次解包内嵌安装（自带 JRE，约 70 MB，不联网）"
if [ ! -d "$ANDROID_USER_HOME_DIR/cli/bundles" ]; then
    ANDROID_USER_HOME="$ANDROID_USER_HOME_DIR" "$TOOL_DIR/bin/android" --version >/dev/null 2>&1 ||
        fail "android 首次解包失败：请确认沙箱 glibc 可用（需要 GLIBC_2.25 以上）"
fi
test -d "$ANDROID_USER_HOME_DIR/cli/bundles" || fail "内嵌安装未解包：$ANDROID_USER_HOME_DIR/cli/bundles 不存在"

progress 70 "[COPY] 部署官方技能到 /attachments/skills/android-skills"
test -d /attachments || fail "/attachments 未挂载：技能不会进入自动扫描根，已中止安装"
mkdir -p "$SKILLS_ROOT"
rm -rf "$SKILLS_STAGING"
mkdir -p "$SKILLS_STAGING"
cp -a "$PAYLOAD/skills/." "$SKILLS_STAGING/"

skill_count=$(find "$SKILLS_STAGING" -type f -name 'SKILL.md' | wc -l | tr -d ' ')
test "$skill_count" -ge "$MIN_SKILL_COUNT" ||
    fail "技能数量不足：期望至少 $MIN_SKILL_COUNT 个 SKILL.md，实际 $skill_count"

# 原子替换：先把旧集合挪走再落位，失败时保持旧集合可用。
rm -rf "$SKILLS_PREVIOUS"
if [ -e "$SKILLS_COLLECTION" ]; then
    mv "$SKILLS_COLLECTION" "$SKILLS_PREVIOUS"
fi
mv "$SKILLS_STAGING" "$SKILLS_COLLECTION"
rm -rf "$SKILLS_PREVIOUS"

progress 85 "[VERIFY] 运行安装后校验"
/bin/sh "$PAYLOAD/scripts/verify-android-skills.sh"

progress 95 "[CLEAN] 释放沙箱内载荷暂存（约 90 MB；重新安装时会从包内再次复制）"
rm -f "$PAYLOAD/bin/android-cli" "$PAYLOAD/bin/android-launcher"
rm -rf "$PAYLOAD/archives"

progress 100 "[VERIFY] Android CLI 与 33 个官方技能已就绪"
