package top.tianyan.app.runtime.doctor

import top.tianyan.app.core.model.DoctorItem
import top.tianyan.app.core.model.DoctorReport
import top.tianyan.app.core.model.DoctorStatus

/**
 * 环境体检 → AI 自愈 Prompt 构造器。
 *
 * 与 [EnvironmentRepairer] 的区别：那个是**固定 5 步脚本**（写 DNS、换镜像源、
 * 刷索引、装工具链、升 Node），不管实际报什么错都跑同一套，报错信息完全没进决策。
 * 本类把体检报告里**真正失败的项 + 其 detail** 交给 Agent，由模型判断该跑什么命令。
 *
 * 只做纯文本构造，不碰 UI / 不启动 Agent —— 调用方拿到字符串后走既有的
 * HealingTask 通路（与工具中心「AI 自愈」同一条），保证行为一致。
 */
object EnvironmentHealingPrompt {

    /**
     * 把体检报告里需要处理的项整理成 Agent 任务描述。
     *
     * @return null = 没有需要修的项（全绿），调用方不该弹自愈入口。
     */
    fun build(report: DoctorReport, recentLogs: List<String> = emptyList()): String? {
        val broken = report.items.filter { it.status == DoctorStatus.ERROR || it.status == DoctorStatus.WARNING }
        if (broken.isEmpty()) return null

        return buildString {
            appendLine("【环境体检失败诊断与自愈任务】")
            appendLine("天衍的 Linux 沙箱环境体检发现 ${broken.size} 项异常，请逐项排查并修复。")
            appendLine()
            appendLine("【失败项清单】：")
            broken.forEachIndexed { index, item ->
                appendLine("${index + 1}. [${item.status}] ${item.title}（${item.category.displayName}）")
                appendLine("   摘要：${item.summary}")
                item.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    appendLine("   详情：")
                    detail.lineSequence().forEach { line -> appendLine("     $line") }
                }
            }
            if (recentLogs.isNotEmpty()) {
                appendLine()
                appendLine("【沙箱最近日志】：")
                appendLine("```")
                recentLogs.takeLast(40).forEach { appendLine(it) }
                appendLine("```")
            }
            appendLine()
            appendLine("【行动指南】：")
            appendLine("1. 先按上面每一项的 detail 定位真实原因（不要套用固定步骤，不同报错的成因不同）；")
            appendLine("2. 用 base 工具在沙箱里执行针对性修复命令；")
            appendLine("3. 每修完一项，重新跑一次对应检查确认已恢复；")
            appendLine("4. 全部处理完后，向我汇报：哪几项修好了、哪几项没修好、各自原因。")
        }
    }

    /** 单条体检项的快捷构造（工具页/单项修复入口用）。 */
    fun buildForItem(item: DoctorItem, recentLogs: List<String> = emptyList()): String {
        val single = DoctorReport(
            items = listOf(item),
            overallStatus = item.status,
            warningCount = if (item.status == DoctorStatus.WARNING) 1 else 0,
            errorCount = if (item.status == DoctorStatus.ERROR) 1 else 0,
        )
        return build(single, recentLogs) ?: "【环境体检】${item.title} 未发现需要处理的问题。"
    }
}
