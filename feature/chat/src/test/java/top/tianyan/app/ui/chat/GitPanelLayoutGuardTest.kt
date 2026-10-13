package top.tianyan.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Git 面板与首页引导的界面回归守卫。
 *
 * 这里断言的是源码层面的结构，不是渲染结果——因为要拦的都是「布局放错位置」
 * 这类在单元测试里看不见、只有真机上才暴露的问题：
 *   · 底部悬浮页签栏和 App 自己的底部中枢导航叠在一起（截图里两层导航压着）；
 *   · 同一份数据被两张卡片并排展示，用户以为其中一个是别的口径；
 *   · 已经删掉的入口（进群取离线包）又被加回来。
 * 这些用 grep 级别断言最直接：改回来就会红。
 */
class GitPanelLayoutGuardTest {

    private val gitPanel = File("src/main/java/top/tianyan/app/ui/chat/GitPanel.kt")
    private val homeScreen = File("../../feature/home/src/main/java/top/tianyan/app/ui/home/HomeScreen.kt")
    private val homeStrings = File("../../feature/home/src/main/res/values/strings.xml")
    private val floatingTabBar = File("../../feature/components/src/main/java/top/tianyan/app/ui/components/FloatingTabBar.kt")

    private fun read(file: File): String {
        assertTrue("找不到文件: ${file.path}", file.isFile)
        return file.readText()
    }

    /**
     * Git 面板的页签必须放在内容上方，不能用固定在 BottomCenter 的悬浮栏。
     *
     * 悬浮栏会和 App 底部导航（天衍/智枢/工坊/乾坤）落在同一块区域，
     * 两层导航叠着显示，点击也互相抢触摸目标。
     *
     * 页签条本体从 SecondaryTabRow 升级为 OrbitalTabRow（星轨轨道滑块，
     * components 模块共享组件）：位置语义不变（内容上方、非悬浮），
     * 断言跟随组件名更新——若回退到底部悬浮栏，第一条仍会红。
     */
    @Test
    fun gitTabsAreNotBottomFloating() {
        val source = read(gitPanel)
        assertTrue(
            "GitPanel 又用回了底部悬浮 FloatingTabBar：它会和 App 底部导航重叠",
            !source.contains("FloatingTabBar("),
        )
        assertTrue(
            "GitPanel 应该用 OrbitalTabRow（内容上方轨道页签条）承载状态/分支/提交三个页签",
            source.contains("OrbitalTabRow("),
        )
    }

    /** 底部既然没有悬浮栏，Snackbar 就不该再留出避让空隙。 */
    @Test
    fun snackbarDoesNotReserveSpaceForARemovedBar() {
        val source = read(gitPanel)
        assertTrue(
            "GitPanel 的 Snackbar 仍在为已移除的悬浮栏留 84dp 空隙",
            !source.contains("84.dp"),
        )
    }

    /**
     * 同一份改动统计只能有一处展示。
     *
     * 面板顶部的 GitWorkspaceHeader 已经给出 暂存/修改/未跟踪/同步 四个数，
     * 状态页里再来一张同样口径的「工作区概览」会让用户以为两处口径不同。
     */
    @Test
    fun changeStatsAreShownOnce() {
        val source = read(gitPanel)
        assertTrue(
            "GitPanel 里又出现了第二张「工作区概览」卡：与顶部 GitWorkspaceHeader 数据重复",
            !source.contains("\"工作区概览\""),
        )
        assertTrue("顶部摘要 GitWorkspaceHeader 必须保留", source.contains("GitWorkspaceHeader("))
    }

    /** 面板管的是「仓库」，不是工坊那个「工作区」——两个词混用会让人误解面板职责。 */
    @Test
    fun panelCallsItselfRepositoryNotWorkspace() {
        val source = read(gitPanel)
        assertTrue(
            "GitPanel 头部又写成了「Git 工作区」：本 App 里「工作区」特指工坊项目目录",
            !source.contains("当前会话 · Git 工作区"),
        )
    }

    /** 「进群取离线包」入口已移除：环境体检要的是直接装，加群解决不了环境缺失。 */
    @Test
    fun environmentCardHasNoGroupJoinEntry() {
        val source = read(homeScreen)
        assertTrue("HomeScreen 又出现了加群逻辑 joinQqGroup", !source.contains("joinQqGroup"))
        assertTrue("HomeScreen 又出现了 QQ 群号常量", !source.contains("TIANYAN_QQ_GROUP_ID"))
        assertTrue(
            "Android 环境引导卡应只保留「插件中心安装」一条路径",
            read(homeStrings).contains("home_android_env_tool_center"),
        )
        assertTrue(
            "「进群取离线包」字符串应已删除",
            !read(homeStrings).contains("home_android_env_qq"),
        )
    }

    /**
     * 红队控制台的内容区必须可滚动，并为底部导航留出空间。
     *
     * 这里出过两个只有真机才看得见的问题：
     *   · 根 Column 没有滚动修饰符，超出一屏的资产/技能列表被直接裁掉，
     *     用户既看不到也划不到；
     *   · 控制台在 Scaffold 之外渲染，拿不到 chatBottomInsets，
     *     底部中枢导航作为浮层盖在最后几行上。
     */
    @Test
    fun redTeamConsoleContentIsScrollableAndClearsBottomNav() {
        val source = File("src/main/java/top/tianyan/app/ui/chat/RedTeamConsole.kt").readText()
        assertTrue(
            "RedTeamConsole 的内容区缺少 verticalScroll：超出一屏的内容会被裁掉",
            source.contains("verticalScroll(rememberScrollState())"),
        )
        assertTrue(
            "RedTeamConsole 内容区缺少底部留白：底部导航会盖住最后几行",
            source.contains("padding(bottom = 96.dp)"),
        )
    }

    /** 资产列表条目要能整行点击展开，而不是只靠一个小按钮。 */
    @Test
    fun assetRowsAreTappableAndNotColumnAligned() {
        val source = File("src/main/java/top/tianyan/app/ui/chat/RedTeamAssetsTab.kt").readText()
        assertTrue(
            "资产条目应支持整行点击展开",
            source.contains("Modifier.fillMaxWidth().clickable { onToggle() }"),
        )
        // 行改成两行式后不该再有假列头（列名对不齐比没有列名更容易误读）
        assertTrue(
            "资产列表不该再保留对不齐的假列头（IP/状态/端口/首见）",
            !source.contains("listOf(\"IP\" to 1f"),
        )
    }

    /**
     * 红队界面里只能有一处「无界」纵向滚动。
     *
     * 0.17.8 线上崩溃的成因：控制台内容区加了一层 verticalScroll 之后，
     * 「概览」页签里的 RedTeamPanel 自己也有一层，两层无界纵向滚动嵌套时，
     * 内层拿到无限的 maxHeight 约束，Compose 直接抛
     * IllegalStateException: Vertically scrollable component was measured with
     * an infinity maximum height constraints。
     *
     * 规则：页面级滚动只允许一处（控制台内容区）；嵌套在里面的滚动区必须用
     * heightIn(max = ...) 限高，否则就是崩溃隐患。
     */
    @Test
    fun redTeamUiHasAtMostOneUnboundedVerticalScroll() {
        val files = listOf("RedTeamConsole.kt", "RedTeamAssetsTab.kt", "RedTeamConsoleTabs.kt", "RedTeamPanel.kt")
            .map { File("src/main/java/top/tianyan/app/ui/chat/$it") }

        val unbounded = mutableListOf<String>()
        files.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("verticalScroll(") || line.trimStart().startsWith("import")) return@forEachIndexed
                // 往上看 4 行，看这个滚动区是否被 heightIn 限了高
                val context = lines.subList(maxOf(0, index - 4), index + 1).joinToString(" ")
                if (!context.contains("heightIn")) {
                    unbounded += "${file.name}:${index + 1}"
                }
            }
        }
        assertTrue(
            "出现了 ${unbounded.size} 处无界纵向滚动（$unbounded）：嵌套的无界纵向滚动会让内层拿到无限 maxHeight，直接崩溃。",
            unbounded.size <= 1,
        )
        assertTrue(
            "唯一允许的无界滚动应是控制台内容区（页面级），实际是 $unbounded",
            unbounded.singleOrNull()?.startsWith("RedTeamConsole.kt") == true,
        )
    }

    /** 概览页签的内容组件不得自带滚动容器：滚动归页面所有。 */
    @Test
    fun overviewPanelDoesNotBringItsOwnScroller() {
        val source = File("src/main/java/top/tianyan/app/ui/chat/RedTeamPanel.kt").readText()
        assertTrue(
            "RedTeamPanel 又加回了 verticalScroll：它与控制台内容区的滚动嵌套会崩溃",
            !source.contains("verticalScroll("),
        )
    }

    /**
     * 同一 composable 内不得出现「无界滚动嵌无界滚动」。
     *
     * 这是 0.17.8 线上崩溃的成因：两层无界纵向滚动嵌套时内层拿到无限 maxHeight，
     * Compose 抛 IllegalStateException。这类问题编译期和单元测试都看不见，
     * 只有真机进页面才崩，所以用源码结构断言拦住。
     *
     * 判据刻意保守：只查「同一个 composable 内部、缩进更深的那层又声明了无界滚动」。
     * 早先用「A 调用了 B 且两者都滚动」的跨函数规则，会把互斥分支
     * （如 GitPanel 的 showCredentialsPage 与 pager）误判成嵌套，
     * 按那个结果改代码反而会破坏本来正常的界面。
     */
    @Test
    fun noNestedUnboundedScrollWithinOneComposable() {
        val files = listOf("RedTeamConsole.kt", "RedTeamAssetsTab.kt", "RedTeamConsoleTabs.kt", "RedTeamPanel.kt", "GitPanel.kt")
            .map { File("src/main/java/top/tianyan/app/ui/chat/$it") }

        files.forEach { file ->
            val lines = file.readLines()
            // 每个 composable 的起止行
            val bounds = mutableListOf<Triple<String, Int, Int>>()
            var name: String? = null
            var start = 0
            var depth = 0
            lines.forEachIndexed { i, l ->
                val m = Regex("""\s*(?:internal |private )?fun (\w+)\(""").find(l)
                if (m != null && lines.subList(maxOf(0, i - 3), i).any { it.contains("@Composable") }) {
                    if (name != null) bounds += Triple(name!!, start, i)
                    name = m.groupValues[1]
                    start = i
                    depth = 0
                }
                if (name != null) {
                    depth += l.count { it == '{' } - l.count { it == '}' }
                    if (depth <= 0 && i > start) {
                        bounds += Triple(name!!, start, i)
                        name = null
                    }
                }
            }

            bounds.forEach { (fn, from, to) ->
                val scrolls = (from..to).mapNotNull { i ->
                    val l = lines[i]
                    if (!l.contains("verticalScroll(") || l.trimStart().startsWith("import")) null
                    else {
                        val ctx = lines.subList(maxOf(0, i - 4), i + 1).joinToString(" ")
                        val indent = l.length - l.trimStart().length
                        Triple(i + 1, indent, !ctx.contains("heightIn"))
                    }
                }.filter { it.third } // 只要无界的

                if (scrolls.size >= 2) {
                    val sorted = scrolls.sortedBy { it.first }
                    for (k in 1 until sorted.size) {
                        val prev = sorted[k - 1]
                        val cur = sorted[k]
                        assertTrue(
                            "${file.name} 的 $fn() 内出现嵌套无界滚动：L${prev.first}(indent ${prev.second}) " +
                                "包裹 L${cur.first}(indent ${cur.second})——内层会拿到无限 maxHeight 并崩溃",
                            cur.second <= prev.second,
                        )
                    }
                }
            }
        }
    }

    /** 群号占位符不得再出现：它曾被兜底复制给用户，搜不到任何群还提示「已复制」。 */
    @Test
    fun noPlaceholderGroupNumberAnywhere() {
        listOf(homeScreen, homeStrings, read(floatingTabBar).let { File("../../core/model/src/main/java/top/tianyan/app/core/model/workflow/BuiltinWorkflows.kt") })
            .forEach { file ->
                if (!file.isFile) return@forEach
                assertTrue(
                    "${file.path} 残留 QQ 群号占位符 000000000",
                    !file.readText().contains("000000000"),
                )
            }
    }
}
