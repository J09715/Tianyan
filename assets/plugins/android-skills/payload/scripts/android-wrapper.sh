#!/bin/sh
# 天衍 android 包装层（android-skills 插件）
#
# 职责：
#   1. 固定插件自有的 ANDROID_USER_HOME —— android-cli 载荷与首次解包缓存
#      （main.jar + 自带 JRE，约 70 MB）都落在这里，避免与 adb 的 ~/.android
#      （adb 密钥、AVD、许可证状态）互相污染；用户显式指定时尊重其选择。
#   2. 默认注入 --no-metrics，关闭 Google 对命令/子命令/flag 使用情况的遥测上报。
#   3. exec 真正的引导器（$TOOL_DIR/lib/android-launcher），不自指、不构成回环。
set -eu

TOOL_DIR="${TIANYAN_TOOL_DIR:-/opt/tianyan/tools/android-skills}"
TOOL_DATA="${TIANYAN_TOOL_DATA:-/opt/tianyan/data/android-skills}"
LAUNCHER="$TOOL_DIR/lib/android-launcher"
DEFAULT_USER_HOME="$TOOL_DATA/android-user-home"

if [ ! -x "$LAUNCHER" ]; then
    echo "android 插件未安装完整：缺少 $LAUNCHER" >&2
    echo "请在「设置 → 插件与工具中心」重新安装 android-skills 后重试。" >&2
    exit 127
fi

if [ -z "${ANDROID_USER_HOME:-}" ]; then
    ANDROID_USER_HOME="$DEFAULT_USER_HOME"
    export ANDROID_USER_HOME
fi
mkdir -p "$ANDROID_USER_HOME" 2>/dev/null || true

case " $* " in
    *" --no-metrics "*) ;;
    *) set -- --no-metrics "$@" ;;
esac

exec "$LAUNCHER" "$@"
