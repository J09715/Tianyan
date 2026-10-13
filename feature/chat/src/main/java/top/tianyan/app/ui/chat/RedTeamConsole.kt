package top.tianyan.app.ui.chat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.RedTeamFactEntity
import top.tianyan.app.core.model.RedTeamConsoleModel
import top.tianyan.app.core.model.RedTeamPhase
import top.tianyan.app.core.model.RedTeamPreflightReport
import top.tianyan.app.harness.redteam.RedTeamSkillStore
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton
import top.tianyan.app.ui.components.OrbitalTabRow
import top.tianyan.app.ui.components.OrbitalTabSpec
import top.tianyan.app.ui.components.CountBadge
import top.tianyan.app.ui.components.PulseDot
import top.tianyan.app.ui.components.tabular
import top.tianyan.app.ui.components.staggeredEntrance

/**
 * RedTeam 控制台：作战指挥室外壳（星轨 Orbital 改造）。
 *
 * 信息架构从「11 个横排滚动页签」归组为「6 主页签 + 概览页『更多分区』入口卡」：
 * 11 项塞进一条可滚动页签条时，滚动区外的页签（知识/技能/提示词）对用户不可见，
 * 等于功能丢失；归组后高频分区一屏可达，低频分区从概览进入。枚举仍是 11 个值——
 * ViewModel 的加载联动（进页签才拉数据）按枚举引用，一个不删。
 *
 * 上游 `client.js` 的三页签（资产/提示词/技能）都在保留之列：资产升主页签，
 * 提示词/技能收进「更多分区」；本移植原有的概览（靶标绑定、阶段推进、技能体检）
 * 仍是默认落地页，不做功能删减。
 *
 * 外壳只做「氛围卡 + 页签 + 页签内容」的编排，不吞任何页面自己的卡片：
 * `RuntimeCard` 里再套 `RuntimeCard` 会让玻璃背景叠两层，视觉上是一团糊。
 */
@Composable
internal fun RedTeamConsole(
    session: HarnessSessionEntity,
    facts: List<RedTeamFactEntity>,
    state: RedTeamConsoleState,
    onBindTarget: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onSelectTab: (RedTeamConsoleTab) -> Unit,
    onRefresh: () -> Unit,
    onSelectSegment: (String?) -> Unit,
    onQueryChange: (String) -> Unit,
    onServiceChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSubmitQuery: () -> Unit,
    onProvenanceChange: (String?) -> Unit,
    onSortChange: (RedTeamConsoleModel.Sort) -> Unit,
    onViewChange: (RedTeamConsoleView) -> Unit,
    onToggleAsset: (String) -> Unit,
    onSelectRole: (String) -> Unit,
    onRoleDraftChange: (String) -> Unit,
    onSaveRole: () -> Unit,
    onResetRole: () -> Unit,
    onSelectSkill: (String) -> Unit,
    onNewSkill: () -> Unit,
    onSkillDraftChange: ((RedTeamSkillStore.Skill) -> RedTeamSkillStore.Skill) -> Unit,
    onSaveSkill: () -> Unit,
    onDeleteSkill: () -> Unit,
    onSetAgentsMax: (Int) -> Unit,
    /** 自动绑定提示；界面显示一次后回调清掉。 */
    autoBindNotice: String? = null,
    onConsumeAutoBind: () -> Unit = {},
    skillHealth: RedTeamPreflightReport? = null,
    onRefreshSkillHealth: (() -> Unit)? = null,
) {
    // 整页而不是叠在对话上：和 Git 面板同一套语义（fillMaxSize + RuntimeTopBar + BackHandler）。
    // 原来它是个 fillMaxWidth 的卡片堆，和 Scaffold 里的对话内容并存——
    // 对话正文、快捷开始、输入框会从半透明卡片下面透出来，看着就是「两页糊在一起」。
    BackHandler(onBack = onDismiss)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        RuntimeTopBar(
            title = "RedTeam 控制台",
            onBack = onDismiss,
            actions = {
                RuntimeIconButton(onClick = onRefresh, enabled = !state.loading) {
                    RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(18.dp))
                }
            },
        )
        ConsoleChrome(
            session = session,
            state = state,
            onSelectTab = onSelectTab,
        )

        // 内容区必须可滚动，并给底部中枢导航留出空间。
        // 这里原来是个不滚动的 Column：超出一屏的资产/技能列表被直接裁掉，
        // 用户既看不到也划不到；而底部导航是浮层，会盖在最后几行上。
        //
        // 用 weight(1f) 而不是 fillMaxSize()：外层 Column 里已经有顶栏与页签条，
        // fillMaxSize 会让滚动区按「整屏高度」测量，内容被推到屏幕外；
        // weight 取的是剩余空间，滚动视口高度才正确。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp),
        ) {
        when (state.tab) {
            RedTeamConsoleTab.OVERVIEW -> {
                // 自动绑定提示：用户根本没进面板就绑上了目标，不提示一下会以为是系统乱改。
                autoBindNotice?.let { notice ->
                    androidx.compose.runtime.LaunchedEffect(notice) { onConsumeAutoBind() }
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(notice, style = MaterialTheme.typography.labelMedium)
                    }
                }
                // 态势总览的信息顺序：数字面板 → 低频分区入口 → 执行配置 → 靶标绑定。
                // 指挥官进控制台第一眼要看「现在打成什么样」，再决定深入哪个分区；
                // 原来把智能体并发配置压在最顶上——那是配置项，不是态势。
                RedTeamStatsCard(state = state)
                RedTeamMoreSectionsCard(state = state, onSelectTab = onSelectTab)
                RedTeamAgentsCard(state = state, onSetMax = onSetAgentsMax)
                RedTeamPanel(
                    session = session,
                    facts = facts,
                    onBindTarget = onBindTarget,
                    skillHealth = skillHealth,
                    onRefreshSkillHealth = onRefreshSkillHealth,
                )
            }

            RedTeamConsoleTab.ASSETS -> RedTeamAssetsTab(
                state = state,
                onSelectSegment = onSelectSegment,
                onQueryChange = onQueryChange,
                onServiceChange = onServiceChange,
                onPortChange = onPortChange,
                onSubmitQuery = onSubmitQuery,
                onProvenanceChange = onProvenanceChange,
                onSortChange = onSortChange,
                onViewChange = onViewChange,
                onToggleAsset = onToggleAsset,
            )

            RedTeamConsoleTab.SESSIONS,
            RedTeamConsoleTab.VULNS,
            RedTeamConsoleTab.CHAIN,
            RedTeamConsoleTab.SCORES,
            RedTeamConsoleTab.TARGETS,
            RedTeamConsoleTab.REPORT,
            RedTeamConsoleTab.KNOWLEDGE,
            -> RedTeamSectionTab(
                tab = state.tab,
                rows = state.sectionRows[state.tab.sectionId].orEmpty(),
                digest = state.sections.firstOrNull { it.id == state.tab.sectionId },
                loading = state.loading,
            )

            RedTeamConsoleTab.PROMPTS -> RedTeamPromptsTab(
                state = state,
                onSelectRole = onSelectRole,
                onDraftChange = onRoleDraftChange,
                onSave = onSaveRole,
                onReset = onResetRole,
            )

            RedTeamConsoleTab.SKILLS -> RedTeamSkillsTab(
                state = state,
                onSelectSkill = onSelectSkill,
                onNewSkill = onNewSkill,
                onDraftChange = onSkillDraftChange,
                onSave = onSaveSkill,
                onDelete = onDeleteSkill,
            )
        }
        }
    }
}

/**
 * 页签 → 分区 id。
 *
 * 面板只需要 id 去查已加载的行；`Section` 枚举本体在 core/model，
 * 这里不复制一份 kinds 归属，避免两处定义漂移。
 */
internal val RedTeamConsoleTab.sectionId: String
    get() = when (this) {
        RedTeamConsoleTab.SESSIONS -> "sessions"
        RedTeamConsoleTab.VULNS -> "vulns"
        RedTeamConsoleTab.CHAIN -> "chain"
        RedTeamConsoleTab.SCORES -> "scores"
        RedTeamConsoleTab.TARGETS -> "targets"
        RedTeamConsoleTab.REPORT -> "report"
        RedTeamConsoleTab.KNOWLEDGE -> "knowledge"
        else -> ""
    }

/**
 * 通用分区页：把一类事实按「标题 · 目标 · 状态」列出来。
 *
 * 这批分区（会话 / 漏洞 / 链路 / 得分 / 目标 / 报告 / 知识）是上游
 * `consoleDigest` 里就有的分类；11 页签归组后它们从概览的「更多分区」卡进入，
 * 本页只负责把这一类的行摆出来。
 */
@Composable
private fun RedTeamSectionTab(
    tab: RedTeamConsoleTab,
    rows: List<RedTeamConsoleModel.Fact>,
    digest: RedTeamConsoleModel.SectionDigest?,
    loading: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
            Text(
                buildString {
                    append(tab.label)
                    append(" · ${rows.size} 条")
                    digest?.latestAt?.let { append(" · 最近 ${formatEpochTime(it)}") }
                },
                // 整行套 tabular：tnum 只作用于数字，条数/时间写回刷新时位宽稳定，
                // 标题不会因为「9 条 → 10 条」多出一位而左右抖动。
                style = MaterialTheme.typography.labelLarge.tabular(),
                fontWeight = FontWeight.Bold,
            )
            if (rows.isEmpty()) {
                Text(
                    if (loading) "加载中…" else "这一类还没有记录。派发角色后由工具写回，或在上方对话里让智能体补录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                return@RuntimeCard
            }
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEachIndexed { index, fact ->
                    Surface(
                        // 交错入场：前 6 行依次浮现（组件内部截断），让「新写回的记录」
                        // 有一条被看见的动线；更深的行不参与，滚动长列表不卡。
                        modifier = Modifier.fillMaxWidth().staggeredEntrance(index),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                fact.title.ifBlank { fact.id },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            fact.subtitle.takeIf { it.isNotBlank() }?.let { sub ->
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            fact.severity?.takeIf { it.isNotBlank() }?.let { severity ->
                                SeverityPill(severity)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 严重级胶囊徽章（红 / 黄 / 灰三档）。
 *
 * 为什么从纯色文本改成胶囊：玻璃卡上「critical」这类纯文本与普通说明文字
 * 混在一起，扫一眼分不出轻重。胶囊自带底色，三档色阶一眼分层，severity
 * 成为行内最醒目的元素——漏洞列表里它本来就该最醒目。等级字用 tabular，
 * 同一列表里不同等级宽度稳定，行首不会锯齿错位。
 */
@Composable
private fun SeverityPill(severity: String) {
    val (container, content) = when (severity.lowercase()) {
        "critical", "high" -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f) to MaterialTheme.colorScheme.error
        "medium" -> Color(0x1AF59E0B) to Color(0xFFF59E0B)
        // low / info / 未知等级：中性灰，不抢红黄两档的注意力。
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .height(18.dp)
            .background(container, RoundedCornerShape(percent = 50))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            severity,
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * 态势总览统计网格（概览页第一区块）。
 *
 * 为什么不沿用底栏那行「C 段 3 · 资产 12（存活 8）· 端口 …」小字：七个口径
 * 挤一行，小屏直接被省略号截断，数字混在文字里也没法对比。拆成 4 列网格后，
 * 「资产 / 存活」这类比值上下对齐、一眼可读；数字用 titleLarge + tabular，
 * 写回刷新时位宽稳定，网格不抖。
 *
 * 全 0 也照常渲染：空态数字本身就是信息（「还没测绘」），藏掉网格
 * 反而让人以为页面没加载完。
 */
@Composable
private fun RedTeamStatsCard(state: RedTeamConsoleState) {
    val stats = state.stats
    // 顺序即阅读顺序：先面（网段/资产/存活），再点（端口/指纹），最后信号来源。
    val cells = listOf(
        "网段" to stats.segments,
        "资产" to stats.assets,
        "存活" to stats.liveAssets,
        "开放端口" to stats.openPorts,
        "指纹" to stats.fingerprints,
        "被动信号" to stats.passiveSignals,
        "主动信号" to stats.activeSignals,
    )
    RuntimeCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .staggeredEntrance(0),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "态势总览",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        cells.chunked(4).forEachIndexed { rowIndex, rowCells ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (rowIndex == 0) 10.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowCells.forEach { (label, value) ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            value.toString(),
                            style = MaterialTheme.typography.titleLarge
                                .copy(fontWeight = FontWeight.Bold)
                                .tabular(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 末行不满 4 格时补空占位：weight 平分会把最后一行拉宽，
                // 补齐后上下两行列边界对齐，网格才成立。
                repeat(4 - rowCells.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 「更多分区」入口卡：五个低频分区的导航枢纽。
 *
 * 11 页签归组后，会话/目标/知识/提示词/技能不再占页签位——使用频率低
 * 但不能没有入口（删掉就是功能缩水）。收进概览页的 2 列网格：图标 + 名称 +
 * 条数徽章，「有没有数据」仍然一眼可辨，点击直达对应页签。
 */
@Composable
private fun RedTeamMoreSectionsCard(
    state: RedTeamConsoleState,
    onSelectTab: (RedTeamConsoleTab) -> Unit,
) {
    // 图标语义：会话=隧道线缆，目标=靶面（Globe），知识=大脑；
    // 提示词/技能沿用 RuntimeIconName 里的同名图标。
    val entries = listOf(
        RedTeamConsoleTab.SESSIONS to RuntimeIconName.Cable,
        RedTeamConsoleTab.TARGETS to RuntimeIconName.Globe,
        RedTeamConsoleTab.KNOWLEDGE to RuntimeIconName.Brain,
        RedTeamConsoleTab.PROMPTS to RuntimeIconName.Prompt,
        RedTeamConsoleTab.SKILLS to RuntimeIconName.Wrench,
    )
    RuntimeCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .staggeredEntrance(1),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "更多分区",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "低频分区收在这里，徽章是条数；次级页签下点「概览」即可回到本页",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            entries.chunked(2).forEach { rowEntries ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowEntries.forEach { (tab, icon) ->
                        SecondarySectionEntry(
                            icon = icon,
                            label = tab.label,
                            count = when (tab) {
                                // 提示词/技能不是事实分区（digest 里没有对应 id），
                                // 条数直接取已加载列表长度，口径与页内标题一致。
                                RedTeamConsoleTab.PROMPTS -> state.roles.size
                                RedTeamConsoleTab.SKILLS -> state.skills.size
                                else -> state.sections.firstOrNull { it.id == tab.sectionId }?.count ?: 0
                            },
                            // 正处该分区时高亮：从次级页签回到概览能看出「我从哪来」。
                            current = state.tab == tab,
                            onClick = { onSelectTab(tab) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 末行单格补空位：保持 2 列等宽，「技能」不独占整行。
                    if (rowEntries.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * 「更多分区」的单个入口项。
 *
 * 手写 clip + background + clickable 而不用 Surface(onClick)：后者是实验 API，
 * 且这里要按 current 切底色/字色，modifier 链更直白。clip 放最前，
 * ripple 与底色都被圆角裁住。
 */
@Composable
private fun SecondarySectionEntry(
    icon: RuntimeIconName,
    label: String,
    count: Int,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (current) {
                    // 与 OrbitalTabRow 选中态同一套配色语言（primaryContainer 底）。
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RuntimeIcon(
            icon,
            Modifier.size(16.dp),
            tint = if (current) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
            color = if (current) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        CountBadge(count = count)
    }
}

/**
 * 智能体并发上限（上游「智能体」页签的核心控件）。
 *
 * 上限来源要如实标出来：设置项 / 环境变量 / 默认值。用户改了设置却没生效时
 * （被 `REDTEAM_MAX_AGENTS` 压住），没有这行提示就会以为界面坏了。
 */
@Composable
private fun RedTeamAgentsCard(
    state: RedTeamConsoleState,
    onSetMax: (Int) -> Unit,
) {
    val agents = state.agents ?: return
    RuntimeCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .staggeredEntrance(2),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Bot, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            Column(Modifier.weight(1f)) {
                Text(
                    "并发执行智能体 ${agents.max} 个 · 已用 ${agents.used} / 空闲 ${agents.free}",
                    // 计数行套 tabular：并发数字在跑动中频繁变化，等宽保证
                    // 「已用 3 / 空闲 2」跳到「已用 4 / 空闲 1」时不左右抖。
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(
                            when (agents.source) {
                                "settings" -> "来源：设置项"
                                "env" -> "来源：环境变量（会压住设置项）"
                                else -> "来源：默认值"
                            },
                        )
                        append(" · 上限 ${agents.limit}")
                        if (agents.running.isNotEmpty()) append(" · 在跑 ${agents.running.joinToString("、")}")
                    },
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (1..agents.limit).forEach { value ->
                val selected = value == agents.max
                RuntimeTextButton(
                    onClick = { onSetMax(value) },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = if (selected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(
                        value.toString(),
                        style = MaterialTheme.typography.labelLarge.tabular(),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Text(
                "改完立即生效，不用重启",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1,
            )
        }

        state.agentsMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * 顶部氛围卡 + 主页签条。
 *
 * 氛围卡是「作战状态」的固定语义位：靶标 / 阶段 / 资产数 + 呼吸灯，
 * 让指挥官不进任何页签也知道现在打的是谁、打到哪一步。页签条只放
 * 六个主页签（等宽轨道布局放不下 11 项，滚动页签又会把后段藏起来）。
 */
@Composable
private fun ConsoleChrome(
    session: HarnessSessionEntity,
    state: RedTeamConsoleState,
    onSelectTab: (RedTeamConsoleTab) -> Unit,
) {
    val phase = RedTeamPhase.fromId(session.redTeamPhase)
    RuntimeCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Shield, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Column(Modifier.weight(1f)) {
                Text(
                    "RedTeam 控制台",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(session.redTeamTarget?.takeIf { it.isNotBlank() } ?: "未绑定靶标")
                        append(" · ")
                        append(phase.displayName)
                        if (state.stats.assets > 0) append(" · ${state.stats.assets} 资产")
                    },
                    // 副行带资产计数：tabular 让写回新资产时数字位宽稳定，副行不抖。
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 作战氛围指示：有资产说明交战进行中，红点呼吸；还没测绘时静默半透明，
            // 不误导用户以为「已经在打了」。
            PulseDot(
                color = MaterialTheme.colorScheme.error,
                pulsing = state.stats.assets > 0,
            )
        }
    }

    // 六个主页签：概览 / 资产 / 漏洞 / 链路 / 得分 / 报告。
    // 条数直接标在页签上（CountBadge）：否则用户要点进每个页签才知道有没有数据，
    // 空页签和「没实现的页签」看起来一模一样。
    val primaryTabs = listOf(
        RedTeamConsoleTab.OVERVIEW,
        RedTeamConsoleTab.ASSETS,
        RedTeamConsoleTab.VULNS,
        RedTeamConsoleTab.CHAIN,
        RedTeamConsoleTab.SCORES,
        RedTeamConsoleTab.REPORT,
    )
    OrbitalTabRow(
        specs = primaryTabs.map { tab ->
            OrbitalTabSpec(
                label = tab.label,
                // 概览/资产没有分区 digest（sectionId 为空串），count 落 0，
                // 徽章不渲染——这两个页签的「有没有数据」由氛围卡与统计网格表达。
                count = state.sections.firstOrNull { it.id == tab.sectionId }?.count ?: 0,
            )
        },
        // 次级页签（会话/目标/知识/提示词/技能）不在主页签列表里，indexOf 为 -1；
        // coerceAtLeast(0) 落回「概览」高亮。已知折衷：次级页签内容仍完整显示，
        // 用户点「概览」即回到导航枢纽，再从「更多分区」卡去别处。
        selectedIndex = primaryTabs.indexOf(state.tab).coerceAtLeast(0),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        onSelect = { onSelectTab(primaryTabs[it]) },
    )
}
