package top.tianyan.app.harness.groupchat

import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupGovernance

internal fun formatGovernance(policy: GroupGovernance, members: List<Bot>): String = buildString {
    val names = members.associate { it.id to it.name }
    appendLine("【程序强制的团队审议】")
    policy.rules.forEach {
        appendLine("规则 ${it.id}「${it.name}」：${names[it.authorId]}起草 → ${names[it.reviewerId]}审核 → ${names[it.executorId]}及下属执行；最多${it.maxRevisions}轮，争议交群主控裁决。")
    }
    appendLine("先 inspect-governance 查看版本与审核依据。起草者用 submit-proposal(rule_id, version=0, content) 提交，修改须传 proposal_id 与当前version。")
    appendLine("向审核者派单传 proposal_id，要求其调用 review-proposal(proposal_id, version, decision, reason)。审核任务COMPLETED不等于APPROVED。")
    appendLine("驳回后由原起草者修改并重新审核；已通过方案修改后旧批准失效。只有APPROVED的准确版本可以派给执行成员，assign-task须传proposal_id，下级继承。")
    appendLine("旧执行任务绑定的方案过期后不得改用新版冒充原批准；核验已有副作用后，为新批准版本创建新执行任务。")
    appendLine("每阶段以派单回执中的原负责人为准，不另起同阶段任务。已派任务的群公告无需再@执行者；重复派单只返回原任务，补充要求用continue-assignment。")
    appendLine("执行负责人应先检查直属成员：按可验收交付物划分互不冲突的work_item_id、write_paths及依赖，再并行派给合适的开发/测试成员。自己负责协调验收；成员回报原负责人，无可分工作时不强行凑人数。")
    appendLine("本群普通发言、起草和审核仅允许阅读和团队协调；代码写入、Shell、宿主、外部MCP与通用子智能体只允许已批准的执行任务，不能在主控会话兜底绕过。")
}
