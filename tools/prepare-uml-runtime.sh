#!/usr/bin/env bash
# 把 UML（User Mode Linux）引擎的四个 native 产物预取进 app/src/main/jniLibs/arm64-v8a/。
#
# 来源：zalexdev/strykerapp 仓库（GPL v3，commit 固定于 Stryker 6.5.x）的
# app/src/main/jniLibs/arm64-v8a/ 下的四个 .so。它们是「可选引擎」——缺失只影响
# UML 可用性，不阻断 PRoot 主链路，因此本脚本失败可降级跳过。
#
# 用法：
#   ./tools/prepare-uml-runtime.sh            # 从 GitHub raw 拉取
#   UML_SRC=/path/to/stryker/app/src/main/jniLibs/arm64-v8a ./tools/prepare-uml-runtime.sh
#
# 合规：二进制以动态部署形式（APK nativeLibraryDir 解压后独立执行）引入，
# 不与天衍主程序静态链接，维持 GPL 的「可分离」边界。
set -euo pipefail
cd "$(dirname "$0")/.."

JNI=app/src/main/jniLibs/arm64-v8a
mkdir -p "$JNI"

# 四个产物 + 目标大小（字节），用作完整性下限校验
declare -A SIZES=(
  [libuml.so]=21000000
  [libstub.so]=1900
  [libumnet.so]=2200000
  [libpasst.so]=2700000
)

# 来源 1：本地 Stryker 检出（优先，离线可用）
SRC="${UML_SRC:-}"
# 来源 2：GitHub raw（commit 需自行 pin，这里用 main 兜底）
GITHUB_RAW="https://raw.githubusercontent.com/zalexdev/strykerapp/main/app/src/main/jniLibs/arm64-v8a"

download_one() {
  local name="$1"
  local out="$JNI/$name"
  if [ -n "$SRC" ] && [ -f "$SRC/$name" ]; then
    echo "  $name: copying from local Stryker jniLibs"
    cp "$SRC/$name" "$out"
    return 0
  fi
  if [ -f "$out" ] && [ "$(stat -c%s "$out")" -ge "${SIZES[$name]}" ]; then
    echo "  $name: already present, skipping"
    return 0
  fi
  echo "  $name: downloading from GitHub raw"
  if curl -fsSL --retry 2 -o "$out" "$GITHUB_RAW/$name"; then
    return 0
  fi
  echo "  $name: download failed" >&2
  return 1
}

overall=0
for name in "${!SIZES[@]}"; do
  download_one "$name" || overall=1
done

if [ "$overall" -eq 0 ]; then
  echo "Prepared UML engine binaries into $JNI:"
  for name in "${!SIZES[@]}"; do
    SZ=$(stat -c%s "$JNI/$name")
    if [ "$SZ" -lt "${SIZES[$name]}" ]; then
      echo "  WARNING: $name size $SZ < expected ${SIZES[$name]} — may be incomplete" >&2
    fi
  done
  echo "UML engine will be available when enabled in settings."
else
  echo "Some UML binaries could not be prepared — UML engine will be unavailable." >&2
  echo "PRoot main flow is not affected. Re-run this script when online." >&2
  exit 0
fi
