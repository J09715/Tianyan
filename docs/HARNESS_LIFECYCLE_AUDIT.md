# 执行环境与生命周期验收记录

本次复查覆盖生产调用链中的资源获取、执行、取消、回滚、关闭、失败重试及最终权限检查。目标是完成统一执行环境与资源归属契约；真实 SSH 后端、远程终端渲染、远程工程构建属于独立功能，当前不宣称已经实现。

| 边界 | 已实现的契约 | 验证证据 |
| --- | --- | --- |
| 执行环境 | 文件、命令、PTY、协议进程、后台进程及产物固定同一后端和工作区；不支持的能力明确失败 | 假远程后端替换测试，`LocalExecutionEnvironmentFactory` 与绑定注册表测试 |
| 会话与子任务 | 获取前登记；关闭拒绝新活动，取消并排空，再逆序释放；失败保留恢复指针 | `SessionResourceScope`、`SessionExecutionEnvironments`、子任务收尾回归 |
| 扩展与权限 | 注册随作用域撤销；回调只能否决和追加备注；审批、PLAN、身份与成功状态由最终管线控制 | `ToolCheckpoints`、工具执行边界与策略回归 |
| 递归清理 | 活动及释放回调不能关闭自身；父子资源循环明确失败，保留重试入口 | `ResourceDisposalTest`、`TerminalDisposalBoundaryTest` |
| 前台命令 | 分配前登记；取消/超时确认退出，排空读任务；失败记录同时由环境与执行器持有 | `ProcessShellLifecycleTest`：正常完成、取消、超时、关闭绑定拒绝、批量重试 |
| 后台进程 | 同名替换先清理旧实例；日志排空；旧停止请求不能误停新实例；失败可重试 | `LocalBackgroundLifecycleTest`、`ProcessRegistryLifecycleTest` |
| 兼容子进程 | 有界终止确认、独立读任务作用域、输出完成、逐项清理、回调自关闭保护 | `ProcessLinuxSessionLifecycleTest`，假 Process 与实际阻塞线程 |
| JNI PTY | 有界非阻塞读写；PID 回收与信号串行；先排空再释放描述符；分步清理可重试 | `NativePtyLifecycleTest`：15 项故障注入；Android 原生构建；真机执行待验证 |
| 运行时交互会话 | 清理成功才撤销；已死亡但清理失败的记录仍阻止存储清理 | `InteractiveSessionRegistryTest` |
| MCP | 创建记录先归属会话；撤销排空创建和读泵；失败回滚保留；借用环境不关闭共享环境 | `McpStartupRollbackTest`、`McpTransportAcquisitionTest`、`McpChannelCleanupTest` |
| MCP 共享作用域 | 读泵拥有子作用域，关闭通道不取消父作用域或兄弟任务；无 Job 的传入上下文也能排空 | `McpChannelCleanupTest` 的父/兄弟隔离、无 Job 与递归清理测试 |
| Termux 终端 | 创建失败分步回滚；关闭等待进程退出后释放环境与记录；批量失败仍尝试全部 | 终端生命周期测试与 `TerminalDisposalBoundaryTest`；真机渲染待验证 |
| 存储维护 | 卸载、重置、更新、关闭拒绝正在执行的命令；维护期间禁止新启动；失败/取消恢复准入 | `StorageActivityGateTest` 与运行时清理回归 |

统一验证命令：`./gradlew test :app:assembleDebug architectureCheck`。项目打包预编译的 `libpty_native.so`，修改 C 源码后必须先用 `tools/build-pty-native.ps1 -NdkRoot <Android NDK 路径>` 重建；`preBuild` 检查生成清单的源码/二进制哈希及 Kotlin 声明的全部 JNI 符号，即使 JNI 名称不变，也拒绝未经重编译的源码和被替换的库。具体命令见 [PTY 原生库重建](COMMANDS.md#5-pty-原生库重建与统一验收)。所有协议与链路测试使用假 Provider、假环境或假进程，无 API Key，不调用付费模型或真实 SSH 服务。

单元测试验证了故障和竞态下的生命周期契约，NDK 编译及导出符号检查验证了原生 C，APK 打包检查验证了 Kotlin/JNI 接口匹配。当前没有连接的 Android 设备，实际 PRoot/JNI 读写、关闭和 Termux 渲染的设备验收尚未执行，不能据此声称真机链路已通过。

2026-10-11 验收结果：新增 36 项边界回归；全量 `test`、`assembleDebug` 和 32 模块架构检查通过。NDK 重编译通过，最终 APK 内的 7 个 JNI 符号与 Kotlin 声明匹配，原生库 3 个 LOAD 段均满足至少 16 KiB 对齐。验收日志为 `.gradle/harness-lifecycle-final-packaging.log`，产物为 `app/build/outputs/apk/debug/tianyan-v0.21.0-debug.apk`。

同日交付门控验证：分别注入源码哈希、二进制哈希不匹配，`:app:preBuild` 均按预期拒绝；恢复正确清单后，统一验收再次通过。日志为 `.gradle/pty-guard-source-reject.log`、`.gradle/pty-guard-binary-reject.log` 和 `.gradle/lifecycle-delivery-acceptance.log`。清单与源码/库一同版本管理，普通构建也会执行一致性校验。
