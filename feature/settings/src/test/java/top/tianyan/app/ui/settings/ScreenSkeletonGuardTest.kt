package top.tianyan.app.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面骨架守卫：顶栏必须由 Scaffold 承载。
 *
 * 这条拦的是一个只有真机才暴露的布局缺陷：
 * 「关于天衍」把 `RuntimeTopBar` 塞进了 `LazyColumn` 的 `item {}`，导致
 *   1. 顶栏跟着内容一起滚动，不再是固定头部；
 *   2. 被 LazyColumn 当作「可滚动项」测量，而澄明主题的顶栏需要确定高度
 *      （drawBackdrop 的玻璃层依赖它），结果被拉成一条很高的色块。
 * 用户实测截图里「关于天衍」顶部异常撑高就是这个。
 *
 * 设置页其余二级页全部用 `Scaffold(topBar = ...)`，这条守卫保证不再有例外。
 */
class ScreenSkeletonGuardTest {

    private val settingsSrc = File("src/main/java/top/tianyan/app/ui/settings")

    /** 收集所有使用了 RuntimeTopBar 的页面文件。 */
    private fun screensUsingTopBar(): List<File> =
        settingsSrc.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("RuntimeTopBar(") }

    /**
     * 顶栏必须出现在 `topBar = { ... }` 里，且不能位于 `item { ... }` 块内。
     *
     * 判据刻意用「同一行含 topBar = { 与 RuntimeTopBar」或「上一行是 topBar = {」，
     * 而不是「往上找几行看到 Scaffold」——后者在实测中拦不住回归：
     * 把顶栏塞回 LazyColumn item 后，往上 4 行仍能看到文件里别处的 Scaffold 字样，
     * 断言照样通过。
     */
    @Test
    fun topBarIsAlwaysHostedByScaffold() {
        val screens = screensUsingTopBar()
        assertTrue("没有找到任何使用 RuntimeTopBar 的页面，守卫可能失效了", screens.isNotEmpty())

        screens.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("RuntimeTopBar(")) return@forEachIndexed
                // 允许两种写法：同一行 `topBar = { RuntimeTopBar(...)`，
                // 或上一行以 `topBar = {` 结尾。
                val sameLine = line.contains("topBar = {")
                val prevLine = index > 0 && lines[index - 1].trimEnd().endsWith("topBar = {")
                assertTrue(
                    "${file.name}:${index + 1} 的 RuntimeTopBar 不在 topBar = { } 中：" +
                        "应改为 Scaffold(topBar = { ... })，否则会被拉伸并随内容滚动。",
                    sameLine || prevLine,
                )
            }
        }
    }

    /** 明确禁止把 RuntimeTopBar 放进 LazyColumn 的 item 块。 */
    @Test
    fun topBarIsNeverALazyListItem() {
        screensUsingTopBar().forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("RuntimeTopBar(")) return@forEachIndexed
                val context = lines.subList(maxOf(0, index - 3), index + 1).joinToString("\n")
                assertTrue(
                    "${file.name}:${index + 1} 把 RuntimeTopBar 放进了 LazyColumn 的 item {}",
                    !Regex("""item\s*\{""").containsMatchIn(context),
                )
            }
        }
    }
}
