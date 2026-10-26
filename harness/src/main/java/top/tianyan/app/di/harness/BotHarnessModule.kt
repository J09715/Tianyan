package top.tianyan.app.di.harness

import org.koin.core.qualifier.named
import org.koin.dsl.module
import top.tianyan.app.core.database.repository.BotScheduleRepository
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.harness.bot.BotAssignmentExecutor
import top.tianyan.app.harness.bot.BotAssignmentRuntime
import top.tianyan.app.harness.bot.BotAssignmentTracker
import top.tianyan.app.harness.bot.BotOrchestrator
import top.tianyan.app.harness.bot.archive.BotPackageService
import top.tianyan.app.harness.bot.diagnostics.BotDiagnosticsExporter
import top.tianyan.app.harness.bot.market.BotMarketRepository
import top.tianyan.app.harness.bot.memory.BotMemoryManager
import top.tianyan.app.harness.bot.schedule.BotScheduledSubmission
import top.tianyan.app.harness.bot.schedule.BotScheduleRunner
import top.tianyan.app.harness.bot.schedule.BotScheduleWakeup
import top.tianyan.app.harness.groupchat.GroupChatDispatcher
import top.tianyan.app.harness.groupchat.BotAttachmentPreparer
import top.tianyan.app.harness.plugins.HarnessPlugin
import top.tianyan.app.harness.plugins.botManagementPlugin
import top.tianyan.app.harness.plugins.botMemoryPlugin
import top.tianyan.app.harness.plugins.botSchedulePlugin

/** PersonaBot：独立会话、Git 记忆、群聊调度与定时唤醒。 */
val botHarnessModule = module {
    single { top.tianyan.app.harness.bot.GroupGovernanceGuard(get(), get(), get()) }
    single { top.tianyan.app.harness.bot.market.GroupTeamImporter(get(), get(), get()) }
    single { top.tianyan.app.harness.bot.BotModelManager(get(), get(), get(), get(), get()) }
    single { top.tianyan.app.harness.groupchat.GroupContextScope(groups = get(), assignments = get(), nativeTasks = get()) }
    single { top.tianyan.app.harness.groupchat.GroupReplyFreshness(groups = get(), messages = get(), contextScope = get()) }
    single { BotMemoryManager(pathManager = get(), logger = get()) }
    single { BotAttachmentPreparer(context = get(), paths = get()) }
    single { top.tianyan.app.harness.bot.TaskBudgetGuard(get()) }
    single { BotAssignmentTracker(repository = get()) }
    single { top.tianyan.app.harness.bot.GroupTaskUsage(assignments = get(), runtime = get(), budgets = get()) }
    single {
        BotOrchestrator(
            botRepository = get<PersonaBotRepository>(),
            groupRepository = get<GroupChatRepository>(),
            groupSessionRepository = get(),
            sessionRepository = get(),
            sessionControl = get(),
            laneRunner = get(),
            memoryManager = get(),
            logger = get(), assignmentRepository = get(),
            modelManager = get(),
        )
    }
    single {
        GroupChatDispatcher(
            groupRepository = get<GroupChatRepository>(),
            botRepository = get<PersonaBotRepository>(),
            attachmentPreparer = get(),
            orchestrator = get(),
            sessionControl = get(),
            logger = get(),
            freshness = get(),
            taskRepository = get(),
            runtimeRepository = get(),
            contextScope = get(),
        )
    }
    single {
        BotAssignmentRuntime(
            repository = get(),
            groups = get(),
            bots = get(),
            sessions = get(),
            control = get(),
            tracker = get(),
            logger = get(),
            executor = get(),
            modelManager = get(),
            taskUsage = get(),
            nativeTasks = get(),
            runtimeRepository = get(),
        )
    }
    single<BotAssignmentExecutor> {
        BotAssignmentExecutor { task, request, admitted ->
                val groups = get<GroupChatRepository>()
                val bots = get<PersonaBotRepository>()
                val group = requireNotNull(groups.getGroup(requireNotNull(task.groupId)))
                val target = requireNotNull(bots.getBot(task.botId))
                val caller = requireNotNull(bots.getBot(requireNotNull(task.reportToBotId)))
                val proposal = task.proposalId?.let { groups.getProposal(it) }
                val reviewContext = proposal?.let { "\n【绑定方案 ${it.id} v${task.proposalVersion} · ${it.decision}，成员材料】\n${it.content}\n审核依据：${it.reason}\n" }.orEmpty()
                get<GroupChatDispatcher>().runAssignment(
                    group, target, caller,
                    "【独立任务 ${task.id} · 第 ${task.revision} 轮】\n${request.instruction}\n$reviewContext" +
                            "这是同一任务的后续工作，先核验已有产物再修改。真正受阻时用 report-assignment 记录原因与产物；偏好问题60秒未回复采用推荐项继续。",
                    task.id, task.writePaths, task.sessionId, admitted
                )
        }
    }
    single<BotScheduledSubmission> {
        BotScheduledSubmission { bot, instruction, group -> get<GroupChatDispatcher>().triggerScheduled(bot, instruction, group) }
    }
    single { BotScheduleRunner(scheduleRepository = get(), botRepository = get(), logger = get(), submit = get()) }
    single { BotPackageService(botRepository = get<PersonaBotRepository>(), orchestrator = get(), memoryManager = get(), json = get(), logger = get()) }
    single {
        BotDiagnosticsExporter(
            groupRepository = get<GroupChatRepository>(),
            assignmentRepository = get(),
            groupSessionRepository = get(),
            sessionControl = get(),
            runtimeRepository = get(),
            json = get(),
            logger = get(),
            nativeTasks = get(),
        )
    }
    single { BotMarketRepository(orchestrator = get(), subagentRepository = get()) }
    single<HarnessPlugin>(named("tianyan.bot-memory")) { botMemoryPlugin(get<PersonaBotRepository>(), get()) }
    single<HarnessPlugin>(named("tianyan.bot-management")) {
        botManagementPlugin(
            botRepository = get<PersonaBotRepository>(),
            groupRepository = get<GroupChatRepository>(),
            orchestrator = { get() },
            dispatcher = { get() },
            assignments = get(),
            logger = get(),
            runtime = { get() },
            modelManager = get(),
            sessionStates = { get<top.tianyan.app.harness.session.SessionControl>().sessionRunStates.value },
        )
    }
    single<HarnessPlugin>(named("tianyan.bot-schedule")) {
        botSchedulePlugin(
            botRepository = get<PersonaBotRepository>(),
            groupRepository = get<GroupChatRepository>(),
            scheduleRepository = get<BotScheduleRepository>(),
            wakeup = { getOrNull<BotScheduleWakeup>() },
            logger = get(),
        )
    }
}
