package top.tianyan.app.ui.chat

import top.tianyan.app.core.model.RedTeamConsoleModel
import top.tianyan.app.harness.redteam.RedTeamSkillStore

/** 控制台页签。上游只有后三个，`概览` 是本移植原有的单页面板内容，保留下来不做功能删减。 */
enum class RedTeamConsoleTab(val label: String) {
    OVERVIEW("概览"),
    ASSETS("资产测绘"),
    PROMPTS("智能体提示词"),
    SKILLS("技能库"),
}

/** 资产测绘的两种视图：列表与力导向图谱（上游的「列表/图谱」切换）。 */
enum class RedTeamConsoleView(val label: String) {
    LIST("列表"),
    GRAPH("图谱"),
}

/**
 * 控制台整体状态。
 *
 * 收在一个不可变 data class 里而不是散成十几个 StateFlow：面板重组的判据是「这一整块
 * 视图数据变没变」，拆开写很容易出现底栏统计已经更新、资产列表还是上一份的错位。
 */
data class RedTeamConsoleState(
    val open: Boolean = false,
    val tab: RedTeamConsoleTab = RedTeamConsoleTab.OVERVIEW,
    val loading: Boolean = false,
    val error: String? = null,
    // 资产测绘
    val engagements: List<RedTeamConsoleModel.Engagement> = emptyList(),
    val currentEngagement: String? = null,
    val stats: RedTeamConsoleModel.Stats = RedTeamConsoleModel.Stats(),
    val segments: List<RedTeamConsoleModel.Segment> = emptyList(),
    /** null 表示「全部 C 段」；非空时只列该段资产。 */
    val selectedSegment: String? = null,
    val filter: RedTeamConsoleModel.Filter = RedTeamConsoleModel.Filter(),
    /** 搜索框草稿：上游是回车才搜，边打边搜会把每次按键都变成一次全量过滤。 */
    val queryDraft: String = "",
    val serviceDraft: String = "",
    val portDraft: String = "",
    val assets: List<RedTeamConsoleModel.Asset> = emptyList(),
    val total: Int = 0,
    val view: RedTeamConsoleView = RedTeamConsoleView.LIST,
    val graph: RedTeamConsoleModel.Graph? = null,
    val graphLoading: Boolean = false,
    val expandedAsset: String? = null,
    val detail: RedTeamConsoleModel.Detail? = null,
    // 智能体提示词
    val roles: List<RedTeamConsoleModel.Role> = emptyList(),
    val roleId: String? = null,
    val roleDraft: String = "",
    val roleSaving: Boolean = false,
    val roleMessage: String? = null,
    // 技能库
    val skills: List<RedTeamSkillStore.Skill> = emptyList(),
    val skillDraft: RedTeamSkillStore.Skill? = null,
    val skillSaving: Boolean = false,
    val skillMessage: String? = null,
) {
    val selectedRole: RedTeamConsoleModel.Role? get() = roles.firstOrNull { it.id == roleId }

    /** 草稿与已保存正文不一致才算脏；否则「保存」按钮永远是亮的，用户不知道有没有改动。 */
    val roleDirty: Boolean get() = selectedRole?.let { it.content != roleDraft } ?: false

    val selectedSkill: RedTeamSkillStore.Skill? get() = skillDraft

    /** 内置技能不可编辑不可删：改了就回不到出厂文案，而面板上没有「恢复默认」。 */
    val skillEditable: Boolean get() = skillDraft?.builtin != true
}
