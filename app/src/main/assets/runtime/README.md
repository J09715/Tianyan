Phase 1 runtime payloads

Linux ARM64 rootfs 默认在线下载，不放入 APK。Android 10+ 不允许目标应用从
可写私有目录执行 ELF，因此 APK 只携带很小的 ARM64 PRoot 主程序、外置 loader
及其动态依赖；Linux 系统由首次引导使用 `proot-distro 5.10.0` OCI 流程安装。

安装器从 Docker/OCI Registry 选择 `linux/arm64` manifest，将通过 digest 校验的
layer 缓存到应用私有目录，按 OCI whiteout 规则合并后再原子启用 RootFS。
这里是 Kotlin 原生安装流程对齐上游 v5.10.0，并不打包 Python proot-distro CLI。
支持 Docker Hub 名称与 `name[:tag]@sha256:<64位小写摘要>`；固定摘要优先于 tag，
manifest 和压缩 layer 均校验原始字节，完整缓存可离线重装，固定镜像不会回退到 LXC。
下载禁止 HTTPS 降级重定向，连续两分钟没有数据会超时；元数据加速探测保留短超时。
天衍不实现上游 build/push/sync/copy/login 命令，也不生成上游 sysdata 的伪造 `/proc` 文件。
PRoot 的主程序与 loader 必须来自同一官方 Termux 包；构建前运行
`tools/prepare-proot-runtime.ps1` 可校验包 SHA-256 并同时准备两者。
