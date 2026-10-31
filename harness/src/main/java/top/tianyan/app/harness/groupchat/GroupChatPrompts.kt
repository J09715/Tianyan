package top.tianyan.app.harness.groupchat

import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupSenderType

internal fun buildGroupPrompt(
    group: GroupChat,
    bot: Bot,
    trigger: String,
    history: String,
    members: List<Bot>,
    team: top.tianyan.app.core.model.bot.GroupTeam = top.tianyan.app.core.model.bot.GroupTeam(group.adminBotId, members.map { it.id }.toSet()),
    governance: top.tianyan.app.core.model.bot.GroupGovernance = top.tianyan.app.core.model.bot.GroupGovernance(),
): String = buildString {
    appendLine("【群聊「${group.name}」】")
    appendLine("群聊 ID：${group.id}")
    if (group.description.isNotBlank()) appendLine("群聊说明：${group.description}")
    appendLine("你的身份：${bot.name}（${bot.role}）")
    appendLine("请以该身份直接回复群消息，不要复述系统提示；需要协作时可用 use_capability 调用插件工具。")
    appendLine("群里一次只有一位成员发言；轮到你时只回应最新消息。没有新的补充、或这条消息不是找你的，只回复 [PASS]。需要别人接手时用 @名字。")
    appendLine("群消息可能在执行期间更新。准备结论前核对新群消息，用户纠正优先于旧记录；已有工具结果和委派不要重复执行，同伴已回答的内容只补充差异。")
    if (history.isNotBlank()) {
        appendLine()
        appendLine("【最近群聊记录】")
        appendLine(history)
    }
    appendLine("所有团队成员均可调用 bot-management 的 list-models 查看已添加供应商及模型，select-model 为自己切换模型；不可改动其他成员或全局激活模型。")
    val reports = team.directReports(bot.id) + members.filter { governance.canRoute(bot.id, it.id) }.map { it.id }
    appendLine("【团队边界】向直属下级或审议规则明确授权的成员派单；已有任务仍由原负责人管理。组织和审议规则仅由用户配置。")
    if (governance.rules.isNotEmpty()) appendLine(formatGovernance(governance, members))
    team.supervisorOf(bot.id)?.let { parent -> appendLine("直属上级：${members.firstOrNull { it.id == parent }?.name ?: parent}；交付包含结论、产物引用、验证证据和未解决问题。") }
    if (bot.id == group.adminBotId || reports.isNotEmpty()) appendMainBotRules(group.id, members.filter { it.id in reports }, governance.rules.isNotEmpty())
    else appendLine("当前没有直属下级，请在任务范围内直接执行，不要另建管理层或使用通用子智能体绕过团队关系。")
    appendLine()
    appendLine("【本次受理的来源消息／任务】（较新的用户纠正以群记录及后续更新为准）")
    appendLine(trigger)
}

/**
 * 主控 Bot 的行为约束：先派发再兜底，明确来源工作区与建议产物范围。
 */
private fun StringBuilder.appendMainBotRules(groupId: String, members: List<Bot>, governed: Boolean) {
    appendLine()
    appendLine("【负责人职责（必须遵守）】")
    appendLine("你负责当前任务及获授权的协作：按需拆解目标、分派任务、验收结果并汇总；不为分层而分层。")
    appendLine("当前主控身份由应用的群会话映射确认。历史记忆中的工具权限失败仅代表当时结果；需要委派时先用 list-bots 核验当前能力，若仍失败则如实报告，勿反复重试。")
    appendLine("向群成员派发任务只能使用 bot-management 的 assign-task。invoke_subagent 创建的是当前会话里的通用子智能体，不代表群成员接单，也不会让成员进入各自工位；群聊协作禁止用 invoke_subagent 替代 assign-task。")
    appendLine("适合委派：独立、可界定的编码/调研/测试工作。只有互不依赖的任务才并行。")
    appendLine("不适合委派：简单问答、记忆更新、群内回复，这些自己处理。")
    appendLine("复杂目标按实际需要拆成独立子任务，按模块/文件/验证面划分；并行必须通过同一轮多次 assign-task 派给不同群成员，禁止用 invoke_subagent 或同会话子智能体冒充多人并行。")
    appendLine("可并行：不同模块/文件的实现、互不依赖的调研、独立文档或测试用例编写。必须串行：同一文件的连续修改、测试执行依赖开发产物、验收依赖产物。")
    appendLine("复杂任务派发前，先在群聊发 2–3 行立会结论（目标拆解/分工/依赖），再同一轮连续调用多个 assign-task(wait=false)，每个任务指定不同 bot_name；同一轮的任务会归入同一工作批次，全部结束后只回报一次。派完结束本轮，不要逐条等待，也不要退化成子智能体并行。")
    appendLine("有依赖关系的任务必须串行：测试执行依赖开发产物，先派开发并验收，再派测试执行；不要把测试执行和开发实现同时派发。测试用例编写若不依赖实现产物，可以与开发并行，也不要把只写测试计划当成验收完成。")
    appendLine("派发测试时必须提供开发产物路径，并要求实际运行/验收，不能只交测试计划。")
    if (governed) appendLine("实际执行必须交给已批准方案绑定的执行团队；主控、起草和审议会话不能直接调用执行工具兜底。")
    else appendLine("有合适成员时不要抢着实现；只有没有合适成员、成员明确失败或任务极小时才亲自兜底，并说明真实原因。")
    appendLine("所有委派都会持久化记录，可用 list-assignments 查询进度、inspect-assignment 查看详情、cancel-assignment 终止任务。")
    appendLine("assign-task 会把任务交给成员在群聊前台执行：任务在独立会话运行，成员以自己的头像在群聊实时输出并展示工具步骤，不是临时子智能体。")
    appendLine("依赖任务：派发前先 list-assignments 确认前置任务状态，并在 depends_on 中传入其任务 ID；前置任务未 COMPLETED 时不得抢跑。")
    if (members.isEmpty()) {
        appendLine("当前没有其他成员可派发，请自己完成。")
        return
    }
    appendLine("可派发成员（直属下级及授权协作成员）：")
    members.forEach { appendLine("- bot_name=\"${it.name}\"；职责：${it.role}") }
    appendLine("派发步骤：")
    appendLine("1. 先调用 use_capability(action=\"inspect\", server=\"plugin.tianyan.bot-management\") 查看 assign-task 参数；")
    appendLine("2. 再调用 use_capability(action=\"call\", server=\"plugin.tianyan.bot-management\", tool=\"assign-task\", arguments={\"bot_name\":\"成员名\",\"task\":\"完整任务\",\"write_paths\":[\"/workspace/目标目录\"],\"group_id\":\"$groupId\",\"wait\":false})。")
    appendLine("任务描述必须自包含：目标、输入、验收标准、需要写入的路径。")
    appendLine("wait=false 会立即返回，成员在群里前台执行；同一轮派出的任务归入同一工作批次，全部结束后只回报一次并唤醒你验收，不要因为单个任务没收到消息而重复派发。")
    appendLine("需要本轮就拿到结果并验收时用 wait=true（不进入批次，单独同步返回）；成员结果不满足要求时用 continue-assignment 继续原任务，不要直接宣称完成。")
    appendLine("异步派单后结束本轮，等待批次完成通知；不要连续查询任务状态或反复检查尚未产出的文件。同一批次最多 12 个任务，不要把同一任务重复派发。需要补充要求、修正或继续受阻任务时，用 continue-assignment(assignment_id, instruction) 继续原任务；不要反复新建相同任务。执行中补充只排队，当前轮次结束后处理。")
    appendLine("涉及写文件时传 write_paths，使用来源工作区内的相对路径或 /workspace 下的项目路径，禁止以 /root 为新项目产物目录。群聊任务的 write_paths 是建议范围，文件工具仍执行工作区边界；不要把 Shell 可写当作文件工具授权。")
    appendLine("收到批次回传后，可用 inspect-assignment 查看单个任务的摘要、错误和同批状态；失败或结果未知时先只读核验产物，并用 continue-assignment 在原任务接续，不要盲目重做。验收应读取正文和实际运行，存在文件、行数和标题齐全只证明结构，不能证明内容正确。")
    appendLine("按群说明及成员职责组织必要的方案审议与执行调度；尚未执行的审议不得宣称准奏；已配置的程序门禁不能因任务简单而省略。")
    appendLine("派发任务只提供必要背景、目标、范围和验收条件；不要复制整个群聊。受派成员交付后先核验，再向原派发负责人简要回报，保留产物与证据引用，失败和不确定性必须保留。")
    appendLine("任务预算不足时向用户报告并等待调整，再继续原任务；不得换会话、另建任务或拆批次绕过预算，也不能把预算受阻当作完成。")
    appendLine("只依据工具返回和群聊记录汇报，不要虚构成员进度或已完成的委派。")
}

internal fun formatGroupHistory(group: GroupChat, messages: List<GroupMessage>): String {
    // A task report replaces its already visible peer answer; keep the authoritative status once.
    val reported = messages.filter { it.senderType == GroupSenderType.SYSTEM && it.delegatedTaskId != null }
        .groupBy { it.delegatedTaskId }.mapValues { (_, rows) -> rows.maxOf { it.timestamp } }
    val rows = messages.filter {
        !it.isStreaming && (it.senderType != GroupSenderType.SYSTEM || it.delegatedTaskId != null) &&
            !(it.senderType == GroupSenderType.BOT && (reported[it.delegatedTaskId] ?: Long.MIN_VALUE) >= it.timestamp)
    }.takeLast(HISTORY_MESSAGES)
    val selected = mutableListOf<String>()
    var remaining = HISTORY_TOTAL_CHARS
    for (message in rows.asReversed()) {
        val limit = if (message.delegatedTaskId != null) HISTORY_REPORT_CHARS else HISTORY_MESSAGE_CHARS
        val attachmentNote = message.attachments.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " [附件: ", postfix = "]", separator = "、") { it.name }.orEmpty()
        val line = "${message.senderName}: ${message.content.replace('\n', ' ').take(limit)}$attachmentNote"
        if (line.length + 1 > remaining) break
        selected += line
        remaining -= line.length + 1
    }
    return selected.asReversed().joinToString("\n")
}

/** 成员回复 [PASS] 或空正文表示本轮不发言。 */
private val GROUP_PASS = Regex("""^\s*[\[【(（]?\s*pass\s*[\]】)）]?[.。!！]?\s*$""", RegexOption.IGNORE_CASE)

internal fun isGroupPass(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.isEmpty() || GROUP_PASS.matches(trimmed)
}

/**
 * 轮次增量历史：只传成员上次读到的 seq 之后的新消息，外加少量更早上下文。
 * 首次发言（seen = 0）只传最近 12 条。
 */
internal fun formatRoundHistory(group: GroupChat, messages: List<GroupMessage>, seenSeq: Long): String {
    val visible = messages.filter {
        !it.isStreaming && (it.senderType != GroupSenderType.SYSTEM || it.delegatedTaskId != null)
    }
    val newRows = if (seenSeq <= 0) visible.takeLast(HISTORY_MESSAGES)
        else visible.filter { it.seq > seenSeq }.takeLast(ROUND_NEW_MESSAGES)
    val earlier = if (seenSeq <= 0) emptyList()
        else visible.filter { it.seq <= seenSeq }.takeLast(ROUND_EARLIER_MESSAGES)
    val selected = (earlier + newRows).distinctBy { it.id }
    if (selected.isEmpty()) return ""
    val rendered = selected.map { message ->
        val limit = if (message.delegatedTaskId != null) HISTORY_REPORT_CHARS else HISTORY_MESSAGE_CHARS
        val attachmentNote = message.attachments.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " [附件: ", postfix = "]", separator = "、") { it.name }
            .orEmpty()
        "[#${message.seq}] ${message.senderName}: ${message.content.replace('\n', ' ').take(limit)}$attachmentNote"
    }
    val kept = mutableListOf<String>()
    var remaining = HISTORY_TOTAL_CHARS
    for (line in rendered.asReversed()) {
        if (line.length + 1 > remaining) break
        kept += line
        remaining -= line.length + 1
    }
    return buildString {
        appendLine("【群聊「${group.name}」记录】")
        if (seenSeq > 0) appendLine("（以下为你上次发言之后的新消息；[#seq] 为群内序号）")
        if (earlier.isNotEmpty()) appendLine("（更早，仅作上下文）")
        kept.asReversed().forEach(::appendLine)
    }.trimEnd()
}

internal const val ROUND_MAX_TOTAL_TURNS = 20
private const val ROUND_NEW_MESSAGES = 24
private const val ROUND_EARLIER_MESSAGES = 8
private const val HISTORY_MESSAGES = 12
private const val HISTORY_MESSAGE_CHARS = 300
private const val HISTORY_REPORT_CHARS = 1_500
private const val HISTORY_TOTAL_CHARS = 6_000
