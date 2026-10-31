#!/bin/sh
# 天衍本地插件 · Android CLI 与官方技能（android-skills）卸载脚本
#
# 只清理本插件下发的文件：
#   - android 包装层与引导器（工具目录由安装框架随后整体删除）
#   - /opt/tianyan/bin/android 命令入口
#   - /attachments/skills/android-skills/ 技能集合（绝不触碰用户其它技能）
# 保留 $TIANYAN_TOOL_DATA：其中的 android-user-home 是 CLI 运行时缓存
#（解包后的 main.jar 与 JRE，约 70 MB）。需要释放空间时请显式删除该目录。
set -eu

TOOL_DIR="${TIANYAN_TOOL_DIR:-/opt/tianyan/tools/android-skills}"
TOOL_DATA="${TIANYAN_TOOL_DATA:-/opt/tianyan/data/android-skills}"

rm -f /opt/tianyan/bin/android
rm -f "$TOOL_DIR/bin/android"
rm -f "$TOOL_DIR/lib/android-launcher"

rm -rf /attachments/skills/android-skills
rm -rf /attachments/skills/android-skills.staging
rm -rf /attachments/skills/android-skills.previous

echo "android-skills 已卸载：android 命令与官方技能目录均已移除"
echo "如需一并释放 CLI 运行缓存（约 70 MB），请手动删除：$TOOL_DATA"
