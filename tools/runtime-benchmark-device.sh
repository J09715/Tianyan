#!/system/bin/sh
# Run only through runtime-benchmark.py, as the debug application's uid.
set -eu
export PATH=/system/bin
base=$(pwd -P)
case "$base" in /data/user/*/*|/data/data/*) ;; *) echo 'Not an app data directory' >&2; exit 2;; esac
rootfs="$base/files/linux-runtime/distros/$distro/rootfs"
proot="$native/libproot.so"
loader="$native/libproot-loader.so"
[ -x "$proot" ] && [ -r "$loader" ] && [ -x "$rootfs/bin/sh" ] || {
    echo 'Missing extracted native libraries or installed RootFS; initialize the debug app first' >&2; exit 2;
}
# All writes are confined to newly created experiment directories, never user files.
cache=$(mktemp -d "$base/cache/runtime-bench.XXXXXXXX")
persistent=''
cleanup() {
    rm -rf "$cache"
    [ -z "$persistent" ] || rm -rf "$persistent"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
persistent=$(mktemp -d "$base/files/linux-runtime/runtime-bench.XXXXXXXX")
mkdir "$cache/data" "$cache/tmp" "$persistent/tmp"
i=0
while [ "$i" -lt "$files" ]; do
    dir="$cache/data/d$((i / 64))"
    mkdir -p "$dir"
    printf 'runtime-benchmark\nfile %s\n' "$i" > "$dir/f$i.txt"
    i=$((i + 1))
done
# RuntimePathManager stages talloc with its SONAME. Copy into our own directory only.
for library in "$native"/libtalloc*.so; do
    [ -f "$library" ] || continue
    cp "$library" "$cache/libtalloc.so.2"
done
printf 'META\tkernel\t%s\n' "$(uname -r)"
printf 'META\tabi\t%s\n' "$(getprop ro.product.cpu.abi)"
printf 'META\tmodel\t%s\n' "$(getprop ro.product.model)"
printf 'META\tandroid\t%s\n' "$(getprop ro.build.version.release)"
printf 'META\tproot_sha256\t%s\n' "$(sha256sum "$proot" | cut -d ' ' -f 1)"
printf 'META\tloader_sha256\t%s\n' "$(sha256sum "$loader" | cut -d ' ' -f 1)"
guest() {
    mode=$1; location=$2; workload=$3
    shift 3
    # PROOT_NO_SECCOMP belongs to the tracer's environment, not a guest export.
    set -- -i "PATH=/system/bin" "LD_LIBRARY_PATH=$cache" "PROOT_LOADER=$loader" \
        "PROOT_TMP_DIR=$location" "TMPDIR=$location" "PROOT_L2S_DIR=$rootfs/.l2s"
    [ "$mode" != disabled ] || set -- "$@" PROOT_NO_SECCOMP=1
    timeout -s KILL 20 env "$@" "$proot" --kill-on-exit --link2symlink -L --sysvipc \
        --kernel-release=6.17.0-Tianyan --change-id=0:0 -r "$rootfs" \
        -b /dev -b /proc -b /sys -b /system -b /apex \
        -b "$rootfs/.l2s:$rootfs/.l2s" -b "$cache/data:/workspace" \
        -b "$location:/tmp" -w /workspace /bin/sh -c "$workload"
}
run() {
    variant=$1; workload=$2
    case "$variant" in
        host) timeout -s KILL 20 /system/bin/sh -c "$workload" ;;
        default-files) guest default "$persistent/tmp" "$workload" ;;
        default-cache) guest default "$cache/tmp" "$workload" ;;
        disabled-files) guest disabled "$persistent/tmp" "$workload" ;;
        disabled-cache) guest disabled "$cache/tmp" "$workload" ;;
    esac
}
sample() {
    variant=$1; task=$2; round=$3
    # Both inventory variants include process startup; no adb round-trip in this timer.
    case "$task/$variant" in
        inventory/host) command="find '$cache/data' -type f | wc -l" ;;
        inventory/*) command='export PATH=/usr/bin:/bin; find /workspace -type f | wc -l' ;;
        tmp/host) command="i=0; while [ \"\$i\" -lt 128 ]; do printf x > '$cache/tmp/item' || exit 1; i=\$((i+1)); done; printf 128" ;;
        tmp/*) command='i=0; while [ "$i" -lt 128 ]; do printf x > /tmp/item || exit 1; i=$((i+1)); done; printf 128' ;;
    esac
    start=$(date +%s%N)
    set +e
    result=$(run "$variant" "$command" 2> "$cache/error")
    status=$?
    set -e
    end=$(date +%s%N)
    result=$(printf '%s' "$result" | tr -d ' \r\n')
    expected=$files
    [ "$task" != tmp ] || expected=128
    if [ "$status" -eq 0 ] && [ "$result" != "$expected" ]; then status=125; fi
    # Never emit arbitrary guest stdout/stderr into the report.
    printf 'SAMPLE\t%s\t%s\t%s\t%s\t%s\n' "$variant" "$task" "$round" "$((end-start))" "$status"
}
# Rotate ordering each round to reduce fixed-order warm-cache / thermal bias.
round=0
while [ "$round" -le "$rounds" ]; do
    case "$((round % 5))" in
        0) variants='host default-files default-cache disabled-files disabled-cache' ;;
        1) variants='default-files default-cache disabled-files disabled-cache host' ;;
        2) variants='default-cache disabled-files disabled-cache host default-files' ;;
        3) variants='disabled-files disabled-cache host default-files default-cache' ;;
        4) variants='disabled-cache host default-files default-cache disabled-files' ;;
    esac
    for variant in $variants; do
        sample "$variant" inventory "$round"
        sample "$variant" tmp "$round"
    done
    round=$((round+1))
done
