package top.tianyan.app.harness.prompt

import top.tianyan.app.runtime.environment.ExecutionEnvironment

internal fun executionLocationVariables(remote: Boolean) = mapOf(
    "EXECUTION_LOCATION" to if (remote) "远程 Linux 服务器" else "Android 私有 Linux 沙箱",
    "EXECUTION_ARCH" to if (remote) "由 uname -m 检测" else "aarch64",
    "EXECUTION_LAYER" to if (remote) "SSH（权限由服务器用户决定）" else "PRoot（无真正内核级 root / systemd）",
)

internal fun remoteEnvironmentPrompt(environment: ExecutionEnvironment): String = """
    ## 当前执行环境：远程 Linux（SSH）
    本会话固定绑定远程服务器，工作目录：${environment.id.workspace}。
    base / process 在服务器执行，read / write / edit 通过 SFTP 操作此远程工作区。
    手机 /workspace 项目名映射为远程工作目录下的同名子目录，文件不自动同步。
    先用 uname、/etc/os-release 和 command -v 检查服务器系统、架构及可用工具；不能假定手机已安装的工具、包管理器、SDK 或 /opt/tianyan 存在。
    MCP STDIO 在同一远程环境启动。host、浏览器和 Android 特权能力仍作用于手机，不代表远程宿主能力。
    不适用手机 PRoot、/sdcard 挂载、HostBridge、QEMU 和本地构建规则。下载、构建绑定及产物等缺少远程适配器的能力会明确拒绝。
    服务器命令拥有该 SSH 用户的实际权限，沿用原有工具审批规则。
""".trimIndent()
