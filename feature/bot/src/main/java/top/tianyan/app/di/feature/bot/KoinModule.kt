package top.tianyan.app.di.feature.bot

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import top.tianyan.app.core.database.repository.BotMemberGroupRepository
import top.tianyan.app.core.database.repository.BotScheduleRepository
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.harness.bot.schedule.BotScheduleWakeup
import top.tianyan.app.ui.bot.BotDetailViewModel
import top.tianyan.app.ui.bot.BotListViewModel
import top.tianyan.app.ui.bot.GroupChatViewModel

/** Dependency registrations owned by the feature:bot module. */
val featureBotModule = module {
    viewModel<BotListViewModel> {
        BotListViewModel(
            context = get(),
            botRepository = get<PersonaBotRepository>(),
            groupRepository = get<GroupChatRepository>(),
            memberGroupRepository = get<BotMemberGroupRepository>(),
            scheduleRepository = get<BotScheduleRepository>(),
            orchestrator = get(),
            marketRepository = get(), teamImporter = get(),
            packageService = get(),
            models = get(),
            wakeup = getOrNull<BotScheduleWakeup>(),
        )
    }
    viewModel<BotDetailViewModel> {
        BotDetailViewModel(
            context = get(),
            botRepository = get<PersonaBotRepository>(),
            groupRepository = get<GroupChatRepository>(),
            scheduleRepository = get<BotScheduleRepository>(),
            orchestrator = get(),
            memoryManager = get(),
            models = get(),
            packageService = get(),
            wakeup = getOrNull<BotScheduleWakeup>(),
            mcpServerRepository = getOrNull(),
        )
    }
    viewModel<GroupChatViewModel> {
        GroupChatViewModel(
            context = get(),
            groupRepository = get<GroupChatRepository>(),
            botRepository = get<PersonaBotRepository>(),
            dispatcher = get(),
            exporter = get(),
            groupSessions = get(),
            approvals = get(),
            sessionControl = get(),
            assignments = get(), assignmentRuntime = get(),
            orchestrator = get(),
            scheduleRepository = get<BotScheduleRepository>(), budgets = get(), memberGroups = get(),
        )
    }
}
