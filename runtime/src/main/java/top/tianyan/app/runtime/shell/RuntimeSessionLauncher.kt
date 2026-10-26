package top.tianyan.app.runtime.shell

import top.tianyan.app.runtime.pty.PtyManager

/** STDIO protocols must not depend on a device's native PTY or util-linux script backend. */
internal suspend fun launchRuntimeSession(
    pty: PtyManager,
    config: SessionConfig,
    command: List<String>,
    fallbackCommand: List<String>,
    hostEnvironment: Map<String, String>,
    resize: suspend (Int, Int) -> Unit,
    cleanup: suspend () -> Unit,
    pipeFactory: (List<String>, Map<String, String>, suspend () -> Unit) -> LinuxSession = { argv, env, release ->
        ProcessLinuxSession(command = argv, hostEnvironment = env, allowSttyResize = false, cleanupCallback = release)
    },
): LinuxSession = when {
    !config.usePty -> pipeFactory(command, hostEnvironment, cleanup)
    pty.nativeAvailable -> pty.openNative(command, hostEnvironment, config, cleanup)
    else -> pty.open(fallbackCommand, hostEnvironment, config, resize, cleanup)
}
