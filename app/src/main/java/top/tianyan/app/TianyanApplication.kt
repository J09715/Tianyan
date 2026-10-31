package top.tianyan.app

import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.context.startKoin
import top.tianyan.app.di.tianyanModule
import android.annotation.SuppressLint
import android.app.Application
import android.database.CursorWindow
import android.util.Log
import androidx.work.Configuration
import top.tianyan.app.core.common.logging.CrashReporter
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.memory.MemoryWatchdog
import top.tianyan.app.harness.HarnessLoop
import top.tianyan.app.core.datastore.AppStatsPreferences
import top.tianyan.app.core.database.AgentSkillRepository
import top.tianyan.app.core.database.McpServerRepository
import top.tianyan.app.service.AgentForegroundService
import top.tianyan.app.service.NOTIFIABLE_AGENT_STATES
import top.tianyan.app.service.WorkflowForegroundService
import top.tianyan.app.runtime.privilege.PrivilegeManager
import top.tianyan.app.harness.browser.BrowserMcpBootstrap
import top.tianyan.app.bot.BotSchedulePlanner
import top.tianyan.app.harness.agent.AgentMcpBootstrap
import top.tianyan.app.harness.groupchat.GroupChatDispatcher
import top.tianyan.app.harness.bot.BotAssignmentTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.tianyan.app.harness.workflow.WorkflowRunManager
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.runtime.virtualdisplay.VirtualDisplayCoordinator
import top.tianyan.app.workflow.AppForegroundTracker
import top.tianyan.app.workflow.WorkflowApprovalNotifier
import top.tianyan.app.workflow.WorkflowRunUiController
import java.io.File

class TianyanApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "TianyanApp"
    }
    val crashReporter: CrashReporter by inject()

    // WorkManager 按需初始化 + KoinWorkerFactory：定时计划 Worker 靠它注入
    // harness 单例（WorkflowRunManager / WorkflowScheduleRepository）
    private val workerFactory: KoinWorkerFactory by lazy { KoinWorkerFactory() }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    // 启动性能：HarnessLoop / Room 仓储的构造图很重（DAO、DataStore、Agent 引擎全家桶），
    // eager 注入会拖慢第一帧。改为 Kotlin Lazy，把实际构建推迟到首个 IO 协程内。
    val harnessLoopLazy: Lazy<HarnessLoop> = inject()
    val workflowRunManagerLazy: Lazy<WorkflowRunManager> = inject()
    val workflowRunUiController: WorkflowRunUiController by inject()
    val workflowApprovalNotifier: WorkflowApprovalNotifier by inject()
    val appForegroundTracker: AppForegroundTracker by inject()
    val appStatsPreferences: AppStatsPreferences by inject()
    val agentSkillRepositoryLazy: Lazy<AgentSkillRepository> = inject()
    val mcpServerRepositoryLazy: Lazy<McpServerRepository> = inject()
    val pathManagerLazy: Lazy<RuntimePathManager> = inject()
    val privilegeManager: PrivilegeManager by inject()
    val browserMcpBootstrap: BrowserMcpBootstrap by inject()
    val agentMcpBootstrap: AgentMcpBootstrap by inject()
    val botSchedulePlannerLazy: Lazy<BotSchedulePlanner> = inject()
    private val botAssignmentRuntimeLazy: Lazy<top.tianyan.app.harness.bot.BotAssignmentRuntime> = inject()
    private val groupChatDispatcherLazy: Lazy<GroupChatDispatcher> = inject()
    private val botAssignmentTrackerLazy: Lazy<BotAssignmentTracker> = inject()

    private val appLoggerLazy: Lazy<AppLogger> = inject()
    private val virtualDisplayCoordinatorLazy: Lazy<VirtualDisplayCoordinator> = inject()

    /**
     * 内存水位哨兵：低频采样堆水位，越过 85% 时先释放"可再生数据"（视频等待缓冲等）
     * 并写运行日志留痕，把慢性内存增长拦截在 OOM 闪退之前。释放钩子必须是丢了能自动重建的数据。
     */
    private val memoryWatchdog = MemoryWatchdog(log = { message -> appLoggerLazy.value.w(message) })

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            allowOverride(false)
            androidContext(this@TianyanApplication)
            modules(tianyanModule)
        }
        configureCursorWindowSize()
        crashReporter.install()
        recoverPendingDataBackup()
        installMemoryWatchdog()
        appScope.launch(Dispatchers.IO) {
            // 并发执行互不依赖的启动任务（crash 导出 / 特权恢复 / 浏览器 MCP bootstrap /
            // 技能入库 / MCP 预设入库），单任务失败不拖垮其他任务。
            coroutineScope {
                // 上一次未捕获崩溃会先落在应用私有目录；下次启动后复制到公共下载目录，
                // 方便测试用户直接从 Download/Tianyan/crash-reports 取出并反馈。
                launch { runCatching { crashReporter.exportPendingReports() } }
                launch { runCatching { privilegeManager.reconcilePersistedMode() } }
                // 启动进程内 MCP HTTP server（loopback 127.0.0.1:8787）供 harness / 外部 IDE 接入浏览器工具
                launch { runCatching { browserMcpBootstrap.bootstrap() } }
                // 被控端：默认关闭，仅按偏好启用；start() 内部自持协程监听偏好，改配置无需重启 App
                launch { runCatching { agentMcpBootstrap.start() } }
                launch {
                    runCatching {
                        val skillRepository = agentSkillRepositoryLazy.value
                        skillRepository.ensureInitialized()
                        // 批量自动发现：把 rikkahub / aicode 等工具的 skills 目录整体复制到
                        // attachments/skills 或工作区 skills 目录后，重启即可全部导入；
                        // 按 resourcePath 去重，重复扫描安全。
                        val pathManager = pathManagerLazy.value
                        val imported = skillRepository.syncFromDirectories(
                            AgentSkillRepository.standardScanRoots(
                                attachmentsDir = pathManager.attachmentsDir,
                                workspaceDir = pathManager.workspaceDir,
                            )
                        )
                        if (imported.isNotEmpty()) {
                            Log.i(TAG, "Skill 目录自动发现并导入 ${imported.size} 个：${imported.joinToString { it.name }}")
                        }
                    }
                }
                launch { runCatching { mcpServerRepositoryLazy.value.ensureInitialized() } }
                // 上次进程被杀时仍在 RUNNING 的委派无法恢复执行，收敛为失败以免工作面板永久转圈。
                launch { runCatching { botAssignmentTrackerLazy.value.recoverOrphaned() } }
            }
            appStatsPreferences.incrementLaunchCount()
            // Bot 定时排期：进程重启后收敛旧唤醒链，只保留最近一次触发。
            launch { runCatching { botSchedulePlannerLazy.value.start() } }
            // 时序门：上面的任务全部就绪后才构造 HarnessLoop——
            //  1) MCP 预设已入库：McpManager 预热（构造时触发）能读到完整 server 列表，
            //     否则首启预热读到空表，第一轮对话缺工具；
            //  2) 浏览器 HTTP server 已监听 + 引擎已注册：内置 browser server 的自环发现
            //     不会撞"连接拒绝 → 5 分钟冷却"；
            //  3) 构造不再由前台服务监听协程在主线程提前触发（重 依赖图主线程构造即启动 jank，
            //     且预热时机不受控）。即使页面抢先注入触发构造，预热失败也按轮自愈，此处只是尽量保证顺序。
            val harnessLoop = harnessLoopLazy.value
            // Agent 开始执行时拉起前台服务，保证后台存活 + 通知进度；结束后由服务发带回复框的通知。
            // 并入本协程：构造完成后才开始监听，不再单独开协程抢构造。
            launch {
                harnessLoop.sessionRunStates.map { states ->
                    states.filterValues { it in NOTIFIABLE_AGENT_STATES }.keys
                }.distinctUntilChanged().collectLatest { active ->
                    if (active.isNotEmpty()) {
                        runCatching { AgentForegroundService.start(this@TianyanApplication) }
                    }
                }
            }
            // 进程被杀后重启时，先按 operation replay policy 修复中断检查点，再续跑已
            // 获得 autoResume 授权且仍有尝试预算的 durable task。等待审批的任务保持冻结，
            // 不可重放工具只写中断结果，绝不自动再次产生副作用。
            runCatching {
                val recovered = harnessLoop.recoverAllInterruptedSessions()
                if (recovered > 0) {
                    Log.i(TAG, "已恢复 $recovered 个被中断的 Agent 会话/任务")
                }
            }.onFailure {
                Log.w(TAG, "恢复中断会话失败", it)
            }
            runCatching { botAssignmentRuntimeLazy.value.start() }
                .onFailure { Log.w(TAG, "恢复独立委派失败", it) }
            runCatching { groupChatDispatcherLazy.value.startDeliveryProcessing() }
                .onFailure { Log.w(TAG, "恢复群消息分发失败", it) }
            // 工作流后台化：启动对账（把进程死亡遗留的非终态运行标为已中断）+ 通知/HUD
            // 控制器 + FGS 联动。工作流 agent 会话由工作流体系自管，已在恢复中排除。
            val workflowRunManager = workflowRunManagerLazy.value
            appForegroundTracker.register(this@TianyanApplication)
            workflowRunUiController.start()
            workflowApprovalNotifier.start(this@TianyanApplication)
            runCatching { workflowRunManager.reconcileInterruptedRuns() }
                .onFailure { Log.w(TAG, "工作流启动对账失败", it) }
            launch {
                workflowRunManager.running.collectLatest { running ->
                    if (running) {
                        runCatching { WorkflowForegroundService.start(this@TianyanApplication) }
                    }
                }
            }
        }
    }

    override fun onTerminate() {
        memoryWatchdog.stop()
        appScope.cancel()
        super.onTerminate()
    }

    /** Retry after notification permission is granted, without forcing early Harness construction. */
    fun refreshAgentNotifications() {
        if (harnessLoopLazy.isInitialized() && harnessLoopLazy.value.sessionRunStates.value.values.any {
                it in NOTIFIABLE_AGENT_STATES
            }) AgentForegroundService.start(this)
    }

    /**
     * 🌟 内存水位哨兵装配：注册"可再生数据"释放钩子并启动低频采样（30s 一次，85% 触发）。
     * - shower-video-pending-frames：虚拟屏视频流等待 SPS/PPS 的包缓冲（历史 OOM 根因），
     *   丢弃后画面随下一个 IDR 自动恢复；
     * 触发与释放效果统一写入 AppLogger（runtime.log，磁盘轮转），崩溃时可对照时间线定位增长源。
     */
    private fun installMemoryWatchdog() {
        memoryWatchdog.registerReleaser("shower-video-pending-frames") {
            virtualDisplayCoordinatorLazy.value.trimVideoBuffers()
        }
        memoryWatchdog.start()
    }

    /**
     * 🌟 全局 CursorWindow 缓冲扩容：将 Android SQLite 原生游标窗口由默认 2MB 扩容至 100MB，
     * 彻底解决已有会话中超大单行记录在 Room 查询时报 `Row too big to fit into CursorWindow` 的问题。
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun configureCursorWindowSize() {
        runCatching {
            val field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            field.set(null, 100 * 1024 * 1024) // 100MB
        }.onFailure {
            Log.w(TAG, "Failed to configure CursorWindow size", it)
        }
    }
}
