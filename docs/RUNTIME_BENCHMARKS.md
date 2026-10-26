# Runtime 设备实验

第三阶段先建立可复现证据，再决定是否改变生产路径。`read`、`write`、`edit` 和 Diff 已由本地 Kotlin 文件层完成；远程环境走自己的文件接口。新增通用 RuntimeBackend 或把远程文件操作回退到手机没有必要。

只读宿主旁路现已接入 `read({"path":"src","query":"literal text","max_results":50})`：本地 Kotlin 扫描返回相对路径和 1 起始行号，不进入 PRoot。`query` 区分大小写、不解释正则、不能与 offset/limit 混用。扫描最多 5000 项、深度 16、单文件 1 MiB、总读取 8 MiB、100 个命中与约 5 秒协作式时间预算；排除常见依赖/构建目录，跳过链接与含 NUL 的文件，遇到限额或跳过项显式说明不完整，每行显示命中附近最多 400 字符。它不是完整代码索引或 gitignore 搜索器，也不提供并发快照；远程尚未实现时明确提示使用当前远程环境的 base/grep。下方 toybox/guest 微基准没有测量这个 Kotlin 实现，不能用于声称该入口的性能倍数。

## 运行

要求 Python 3.9+、ADB、已授权设备，以及**安装并初始化 RootFS 的 debug APK**。不调用模型、网络或特权服务。先停止 Agent、终端和构建任务，避免同时修改 RootFS。工具不安装 APK，不拉取文件系统，也不改变发行版配置。

```powershell
adb devices -l
python tools/runtime-benchmark.py --serial <设备序列号> --distro ubuntu --output build/runtime-benchmark-device-01.json
# ADB 不在 PATH 时加 --adb C:/Users/<用户>/AppData/Local/Android/Sdk/platform-tools/adb.exe
# TianyanDev 使用 --package top.tianyan.app.dev
```

默认根据 base.apk 定位已提取的 `lib/arm64`；设备布局不同时显式传入 `--native-lib-dir /data/app/.../lib/arm64`。库未提取、RootFS 未初始化、release APK 不允许 `run-as` 时，工具明确失败。不存在自动降级为 shell/root uid 的路径。

脚本以 App uid 在两个 `mktemp` 新建目录中准备 1024 个小文件，最后只清除自己的目录。测试中断使用退出 trap 清理；设备断电或 shell 被 SIGKILL 时，可能留有 `cache/runtime-bench.*` 或 `files/linux-runtime/runtime-bench.*`，核对目录名后单独删除即可。禁止清理 RootFS 或现有 workspace/tmp。

## 实验矩阵和判定

| 组别 | 执行 | 临时目录 | tracer seccomp |
| --- | --- | --- | --- |
| host | Android toybox / shell | 本次 cache 临时目录 | 不经过 PRoot |
| default-files | PRoot guest | files/linux-runtime 下实验目录 | 二进制默认行为 |
| default-cache | PRoot guest | Android cache 下实验目录 | 二进制默认行为 |
| disabled-files | PRoot guest | files 下实验目录 | `PROOT_NO_SECCOMP=1` |
| disabled-cache | PRoot guest | cache 下实验目录 | `PROOT_NO_SECCOMP=1` |

只读 inventory 遍历同一批物理文件并核对文件数量，验证原生旁路的可行性；tmp 反复创建/截断一个小文件，用于临时 I/O 探针。后者只写实验目录。每次包括命令启动，使用设备纳秒时钟计时，不含 ADB 往返。每组单次超时 20 秒；退出失败、超时、结果不一致均作废该组（包括预热失败），不会把快速崩溃当性能收益。首轮预热不计入统计，后续默认 7 轮轮换顺序。报告保留原始样本、退出码、median/min/max、内核、Android/ABI 与 PRoot/loader SHA-256；已有报告不会覆盖。

这不是生产全链路等价回放：host 使用 toybox，guest 使用发行版 find；启动方式、挂载清单和环境变量刻意精简，不读取 Provider/HostBridge 密钥或加载用户 shell profile。结果不能单独归因于 ptrace，也不能外推为 CodeGraph、Git、SQLite 或 Kotlin 文件层的倍数收益。临时目录实验不含 npm/pip 解压与持久化缓存，文件存储位置也不等于存储介质不同。默认 seccomp 不代表内核实际启用了某种加速。任何 crash/超时都需要独立诊断。

## 进入生产的门槛

1. 至少覆盖实际支持设备的不同内核（例如 5.10/5.15/6.1）和发行版；每台设备记录冷启动、稳定热态、温度/电量及重复 campaign。纳秒格式不等于纳秒精度。当前脚本不测 CPU 温度、RSS/PSS 或能耗，不能据此声称改善发热/内存。
2. 使用真实 Git/依赖解压/构建负载重复测量，补齐取消、磁盘满、App 重启、Android 清理 cacheDir 后恢复的回归；实验不能在活跃包管理事务中切换配置。
3. 只有多轮有一致收益且无稳定性回退，才新增显式实验开关。选择结果需绑定设备/内核、PRoot 摘要和发行版，升级失效；探针失败保留当前默认。此工具不持久化策略，不自动选择最快模式。
4. 真正接入新旁路时，从单一只读操作开始，经当前 `ExecutionEnvironment.files` 或受控 MCP 路由；保留工作区 canonical 边界、配额、审批和脱敏。SSH 不得落回手机。不以直接宿主 shell 替代文件层。

当前无设备数据，生产 `/tmp`、HOME、pip/npm 缓存挂载与 seccomp 行为保持现状。AVF 后端继续沿用现有 ExecutionEnvironment 的扩展边界，待可用 API 与实机可行性证据齐备再实现。
