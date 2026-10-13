package top.tianyan.app.runtime.terminal

/**
 * 终端登录横幅。由 [top.tianyan.app.runtime.LinuxRuntimeImpl] 在终端会话启动前
 * 写入发行版 `/opt/tianyan/motd`，登录 shell 通过 `cat` 打印，绕开命令串转义问题。
 */
internal fun terminalBanner(): String =
    "天衍 · Tianyan Linux AI Runtime\n"
