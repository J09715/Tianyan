package top.tianyan.app.ui.chat

import androidx.compose.ui.Alignment
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import top.tianyan.app.ui.components.OrbitalTabRow
import top.tianyan.app.ui.components.OrbitalTabSpec
import top.tianyan.app.ui.theme.Spacing

/**
 * 红队控制台的两级导航：一级分区 → 二级页签。
 *
 * 为什么需要两级：页签涨到 10 个以后平铺一行放不下（手机宽度只够 4-5 个），
 * 横向滚动会让「有哪些页」变得不可见 —— 用户找不到「技能库」这类低频页。
 * 上游的做法是 5 个一级分区，一级只做分组不重复渲染业务页，二级才是真正的页面。
 *
 * 分区归属是**固定表**而不是从枚举派生：分组的语义（态势/资产/成果/报告/资源）
 * 是产品决策，不该由枚举顺序隐式决定。新增页签时编译器不会提醒，但下面的
 * [unassignedTabs] 兜底会把漏归组的页签并进「资源」，不会凭空消失。
 */
internal data class RedTeamTabGroup(
    val key: String,
    val label: String,
    val tabs: List<RedTeamConsoleTab>,
)

internal val RED_TEAM_TAB_GROUPS: List<RedTeamTabGroup> = listOf(
    RedTeamTabGroup("overview", "态势", listOf(RedTeamConsoleTab.OVERVIEW)),
    RedTeamTabGroup("assets", "资产", listOf(RedTeamConsoleTab.ASSETS)),
    RedTeamTabGroup(
        "results", "成果",
        listOf(RedTeamConsoleTab.VULNS, RedTeamConsoleTab.SCORES),
    ),
    RedTeamTabGroup("report", "报告", listOf(RedTeamConsoleTab.REPORT)),
    RedTeamTabGroup(
        "resources", "资源",
        listOf(
            RedTeamConsoleTab.SESSIONS,
            RedTeamConsoleTab.CHAIN,
            RedTeamConsoleTab.TARGETS,
            RedTeamConsoleTab.KNOWLEDGE,
            RedTeamConsoleTab.ARTIFACTS,
            RedTeamConsoleTab.PROMPTS,
            RedTeamConsoleTab.SKILLS,
        ),
    ),
)

/** 某个页签归属的一级分区；找不到时回落到「资源」，保证页签不会消失。 */
internal fun groupOfTab(tab: RedTeamConsoleTab): RedTeamTabGroup =
    RED_TEAM_TAB_GROUPS.firstOrNull { tab in it.tabs }
        ?: RED_TEAM_TAB_GROUPS.last()

/**
 * 红队控制台顶部导航条：一级分区 + 当前分区的二级页签。
 *
 * 二级页签只在当前分区内渲染 —— 这样一行永远放得下，也不用横向滚动。
 */
@Composable
internal fun RedTeamTabNavigation(
    current: RedTeamConsoleTab,
    sectionCount: (RedTeamConsoleTab) -> Int,
    onSelectTab: (RedTeamConsoleTab) -> Unit,
) {
    val activeGroup = groupOfTab(current)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        // 一级：五个分区。选中态用「有下划线 + 加粗」表达，不抢二级页签的视觉重量。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            RED_TEAM_TAB_GROUPS.forEach { group ->
                val active = group.key == activeGroup.key
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        // 必须可点：之前只渲染了 Text 没有 clickable，
                        // 点击会穿透到下面的消息列表，触发「复制/撤回」这类消息长按菜单。
                        .clickable { onSelectTab(group.tabs.first()) }
                        // 触摸目标下限 48dp：一级分区是主导航，太窄点不中。
                        .heightIn(min = 48.dp)
                        .padding(vertical = Spacing.xs),
                )
            }
        }
        // 二级：当前分区内的页签。分区只有一个页签时（态势/资产/报告）不渲染，
        // 避免出现「一行只有一个页签」的空导航。
        //
        // 超过 4 个就不再用 OrbitalTabRow：它按 maxWidth/tabCount 均分，
        // 7 个页签每个只有 ~50dp，「凭据文件」会被截成「凭据…」。
        // 改用可换行的胶囊标签，一行放 3-4 个，标签保持完整。
        if (activeGroup.tabs.size > 1) {
            if (activeGroup.tabs.size <= 4) {
                OrbitalTabRow(
                    specs = activeGroup.tabs.map { tab ->
                        OrbitalTabSpec(label = tab.label, count = sectionCount(tab))
                    },
                    selectedIndex = activeGroup.tabs.indexOf(current).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                    onSelect = { index -> activeGroup.tabs.getOrNull(index)?.let(onSelectTab) },
                )
            } else {
                WrappedTabChips(
                    tabs = activeGroup.tabs,
                    current = current,
                    sectionCount = sectionCount,
                    onSelectTab = onSelectTab,
                )
            }
        }
    }
}

/** 页签的条数徽章：分区 digest 优先，提示词/技能这类非事实分区取已加载列表长度。 */
internal fun RedTeamConsoleState.badgeCount(tab: RedTeamConsoleTab): Int = when (tab) {
    RedTeamConsoleTab.PROMPTS -> roles.size
    RedTeamConsoleTab.SKILLS -> skills.size
    RedTeamConsoleTab.OVERVIEW, RedTeamConsoleTab.ASSETS -> 0
    RedTeamConsoleTab.ARTIFACTS -> (sections.firstOrNull { it.id == "attackfiles" }?.count ?: 0) +
        (sections.firstOrNull { it.id == "sessions" }?.count ?: 0)
    else -> sections.firstOrNull { it.id == tab.sectionId }?.count ?: 0
}

/**
 * 可换行的页签胶囊：页签多于 4 个时替代 [OrbitalTabRow]。
 *
 * 为什么需要它：[OrbitalTabRow] 按 `maxWidth / tabCount` 均分宽度，
 * 7 个页签每个只剩约 50dp，「凭据文件」会被截成「凭据…」，「技能」只剩半个字。
 * 换行布局让每个标签拿到自己的自然宽度，一行放 3-4 个，标签完整可读。
 */
@Composable
private fun WrappedTabChips(
    tabs: List<RedTeamConsoleTab>,
    current: RedTeamConsoleTab,
    sectionCount: (RedTeamConsoleTab) -> Int,
    onSelectTab: (RedTeamConsoleTab) -> Unit,
) {
    // 按自然宽度流式排列：每行尽量放满，放不下就换行。
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        tabs.forEach { tab ->
            val selected = tab == current
            val count = sectionCount(tab)
            Surface(
                onClick = { onSelectTab(tab) },
                shape = MaterialTheme.shapes.small,
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
                },
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (count > 0) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
