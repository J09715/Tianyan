package top.tianyan.app.di.feature.custom.iteration

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import top.tianyan.app.iteration.engine.CustomIterationBootstrap
import top.tianyan.app.iteration.engine.CustomIterationPaths
import top.tianyan.app.iteration.engine.TianyanDevBuildCli
import top.tianyan.app.iteration.engine.TianyanDevBuildCoordinator
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.runtime.shell.ShellCommand
import top.tianyan.app.ui.iteration.CustomIterationViewModel

/** Dependency registrations owned by the feature:custom_iteration module. */
val featureCustomIterationModule = module {
    // CI 构建协调器：ShellRunner 绑定 LinuxRuntime 标准契约，在沙盒内执行 gh 命令。
    // 目标仓库不在此写死——协调器运行时从工作区 `origin` 解析（Fork 后自动指向主人账户）。
    factory<TianyanDevBuildCoordinator> {
        TianyanDevBuildCoordinator(
            shellRunner = { command -> get<LinuxRuntime>().execute(command) },
            fallbackRepo = TianyanDevBuildCli.DEFAULT_REPO,
            workspaceDir = CustomIterationBootstrap.GUEST_WORKSPACE,
        )
    }

    viewModel<CustomIterationViewModel> {
        CustomIterationViewModel(
            application = get(),
            buildCoordinator = get(),
            // 点击时刻解析：RootFS 安装/发行版切换会改变这些路径，不能构造期快照
            resolvePaths = {
                val paths = get<RuntimePathManager>()
                CustomIterationPaths(
                    hostHomeDir = paths.homeDir,
                    attachmentsDir = paths.attachmentsDir,
                    workspaceDir = paths.workspaceDir,
                )
            },
            // 与 Coordinator 相同的 origin 探测；构造期不执行，工作区尚未克隆时返回 null
            readOriginRemoteUrl = {
                val result = runCatching {
                    get<LinuxRuntime>().execute(
                        ShellCommand(
                            commandLine = TianyanDevBuildCli.readOriginRemoteUrl(),
                            workingDirectory = CustomIterationBootstrap.GUEST_WORKSPACE,
                            timeoutMs = 30_000L,
                        ),
                    )
                }.getOrNull()
                if (result != null && result.isSuccess) result.stdout else null
            },
        )
    }
}
