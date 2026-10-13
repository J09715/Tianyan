package top.tianyan.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 依赖声明解析。
 *
 * 这个解析器决定「一个工具需要哪些运行时、版本约束是什么」。它出错的表现不是崩溃，
 * 而是**静默跳过依赖**——`DependencyResolver.resolve` 用 mapNotNull，
 * 解析失败的那一项直接消失。结果就是工具装到一半缺运行时，
 * 报错是 command not found，看不出是清单里的依赖字符串没被认出来。
 */
class ManifestDependencyParserTest {

    @Test
    fun `parses bare runtime names`() {
        assertEquals("git", ManifestDependencyParser.parse("git")?.name)
        assertEquals(null, ManifestDependencyParser.parse("git")?.constraint)
    }

    @Test
    fun `parses each supported operator`() {
        val ge = ManifestDependencyParser.parse("node>=20.0.0")!!
        assertEquals("node", ge.name)
        assertEquals(">=20.0.0", ge.constraint)

        val gt = ManifestDependencyParser.parse("python>3.11")!!
        assertEquals("python", gt.name)
        assertEquals(">3.11", gt.constraint)

        val eq = ManifestDependencyParser.parse("node=22.22.3")!!
        assertEquals("=22.22.3", eq.constraint)
    }

    @Test
    fun `handles compound package names and surrounding space`() {
        val parsed = ManifestDependencyParser.parse("  ca-certificates  ")!!
        assertEquals("ca-certificates", parsed.name)
        assertEquals(null, parsed.constraint)
    }

    @Test
    fun `tolerates whitespace around the operator`() {
        val parsed = ManifestDependencyParser.parse("node >= 20.0.0")!!
        assertEquals("node", parsed.name)
        assertEquals(">=20.0.0", parsed.constraint)
    }

    /**
     * 认不出来的输入必须返回 null，而不是返回一个半成品。
     *
     * 调用方是 mapNotNull：返回半成品会让一个并不存在/拼错的依赖混进安装列表，
     * 然后在 apt 阶段以「找不到包」失败，比直接跳过更难排查。
     */
    @Test
    fun `rejects malformed input instead of returning partial results`() {
        assertNull(ManifestDependencyParser.parse(""))
        assertNull(ManifestDependencyParser.parse("   "))
        assertNull(ManifestDependencyParser.parse("1node"))
        assertNull(ManifestDependencyParser.parse("node>="))
        assertNull(ManifestDependencyParser.parse("node>=abc"))
        assertNull(ManifestDependencyParser.parse("node 20.0.0"))
        assertNull(ManifestDependencyParser.parse("node>=20.0.0 extra"))
    }

    /**
     * 大小写是宽容的，不是拒绝条件：解析器先 lowercase 再匹配，
     * 所以 `Node` / `NODE` 都会被认成 node。注册表里的大小写差异不该让依赖静默消失。
     */
    @Test
    fun `case is normalized so registry casing does not matter`() {
        assertEquals("node", ManifestDependencyParser.parse("NODE>=20.0.0")!!.name)
        assertEquals("node", ManifestDependencyParser.parse("Node")!!.name)
        assertEquals(">=20.0.0", ManifestDependencyParser.parse("NODE>=20.0.0")!!.constraint)
    }

    /** 约束字符串要能被下游原样使用（含操作符），不能只剩版本号。 */
    @Test
    fun `constraint keeps the operator for downstream comparison`() {
        val constraint = ManifestDependencyParser.parse("python>=3.11")!!.constraint
        assertEquals(">=3.11", constraint)
        assertNull("只有版本号会让下游无法判断比较方式", ManifestDependencyParser.parse("python>=3.11")!!.constraint?.toDoubleOrNull())
    }
}
