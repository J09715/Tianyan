package top.tianyan.app.runtime.backend

import top.tianyan.app.core.model.StorageMountBinding
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.shell.ShellCommand
import java.io.File

/**
 * 客态命令构造器的统一抽象。
 *
 * 过去 [top.tianyan.app.runtime.proot.ProotCommandBuilder] 同时承担了两个职责：
 * ① 决定「客态命令长什么样」；② 决定「用哪种后端把它跑起来」。
 * 抽出本接口后，[top.tianyan.app.runtime.LinuxRuntimeImpl] 等调用方只依赖 ①，
 * 后端选型收敛到 Koin 绑定处（见 `runtime` 模块 KoinModule），替换后端不再改动调用点。
 *
 * 参数名 `prootBinary` 沿用既有命名以免波及既有调用点与测试；其语义应理解为
 * 「该后端所需的可执行文件路径」（PRoot 为 tracee loader，Droidspaces 为 CLI 本体）。
 * 具体实现可以忽略该参数——命令本身的差异由实现自行决定。
 */
interface GuestCommandBuilder {

    /**
     * 构造一条非交互命令的完整 argv（交给 [top.tianyan.app.runtime.shell.ShellExecutor] 直接执行）。
     *
     * @param prootBinary 后端可执行文件（语义见接口注释，部分实现不使用）
     * @param rootfsDir 目标发行版根文件系统目录；其父目录名即发行版标识
     * @param command 待执行的客态命令
     * @param mounts 存储挂载绑定；不逐次生效的后端应在实现内显式声明忽略
     * @param emulatorBinary x86_64 兼容模式的 QEMU 用户态仿真器；不支持的后端应显式拒绝
     * @param hostCredential 宿主编排层注入的凭据，优先级高于 Provider 环境
     */
    fun build(
        prootBinary: File,
        rootfsDir: File,
        workspaceDir: File,
        homeDir: File = File(rootfsDir.parentFile, "home"),
        optDir: File = File(rootfsDir.parentFile, "opt/tianyan"),
        tmpDir: File = File(rootfsDir.parentFile, "tmp"),
        attachmentsDir: File = File(rootfsDir.parentFile, "attachments"),
        command: ShellCommand,
        mounts: List<StorageMountBinding> = emptyList(),
        emulatorBinary: File? = null,
        hostCredential: Map<String, String> = emptyMap(),
    ): List<String>

    /**
     * 构造一条交互/长驻命令的完整 argv。
     *
     * 与 [build] 的差异是携带会话几何（cols/rows）、banner、PTY 标记等终端语义；
     * 不具备原生 PTY 的后端应在实现内退化为 `script` 伪终端路径。
     */
    fun buildInteractive(
        prootBinary: File,
        rootfsDir: File,
        workspaceDir: File,
        homeDir: File = File(rootfsDir.parentFile, "home"),
        optDir: File = File(rootfsDir.parentFile, "opt/tianyan"),
        tmpDir: File = File(rootfsDir.parentFile, "tmp"),
        attachmentsDir: File = File(rootfsDir.parentFile, "attachments"),
        config: SessionConfig,
        ptyMarker: String? = null,
        nativePty: Boolean = false,
        mounts: List<StorageMountBinding> = emptyList(),
        emulatorBinary: File? = null,
        hostCredential: Map<String, String> = emptyMap(),
    ): List<String>
}
