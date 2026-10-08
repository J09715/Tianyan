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
     */
    @Test
    fun gitTabsAreNotBottomFloating() {
        val source = read(gitPanel)
        assertTrue(
            "GitPanel 又用回了底部悬浮 FloatingTabBar：它会和 App 底部导航重叠",
            !source.contains("FloatingTabBar("),
        )
        assertTrue(
            "GitPanel 应该用 SecondaryTabRow 承载状态/分支/提交三个页签",
            source.contains("SecondaryTabRow("),
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
