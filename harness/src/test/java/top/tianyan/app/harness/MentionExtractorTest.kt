package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionExtractorTest {

    @Test
    fun `parses lowercased mention names`() {
        val names = MentionExtractor.parse("@Builder 帮我看看 @CodeR 的实现")
        assertEquals(setOf("builder", "coder"), names)
    }

    @Test
    fun `stops at chinese punctuation and colon boundaries`() {
        val names = MentionExtractor.parse("联系 @Skill，或 @x:y 再问 @trigger_cmd")
        assertEquals(setOf("skill", "x", "trigger_cmd"), names)
    }

    @Test
    fun `email is not treated as mention`() {
        // 邮箱保护：@ 前是词字符时不视为提及（旧行为会把 host.com 当候选名，导致误报）。
        assertTrue(MentionExtractor.parse("邮箱 user@host.com").isEmpty())
    }

    @Test
    fun `text without at returns empty`() {
        assertTrue(MentionExtractor.parse("没有提及的普通消息").isEmpty())
    }

    // ---- 以下为修复「含空格技能名静默失效」新增的回归用例 ----

    @Test
    fun `known name with internal space matches as a whole`() {
        // 核心缺陷回归：旧实现把「Git 敏捷工作流」截成「Git」→ 技能静默不生效。
        val names = MentionExtractor.parse(
            "@Git 敏捷工作流 帮我回退这个合并",
            listOf("Git 敏捷工作流", "git_workflow"),
        )
        assertEquals(setOf("git 敏捷工作流"), names)
    }

    @Test
    fun `known name match is longest-first so short prefix does not win`() {
        // 「Git」与「Git 敏捷工作流-进化」同时存在时，必须命中更长的那个。
        val names = MentionExtractor.parse(
            "@Git 敏捷工作流-进化 沉淀规则",
            listOf("Git", "Git 敏捷工作流", "Git 敏捷工作流-进化"),
        )
        assertEquals(setOf("git 敏捷工作流-进化"), names)
    }

    @Test
    fun `fullwidth at sign and letters are normalized`() {
        // NFKC 归一：全角 ＠ 与全角字母都应命中。
        assertEquals(
            setOf("git 敏捷工作流"),
            MentionExtractor.parse("＠Git 敏捷工作流 修复", listOf("Git 敏捷工作流")),
        )
        assertEquals(
            setOf("git 敏捷工作流"),
            MentionExtractor.parse("@Ｇｉｔ 敏捷工作流 修复", listOf("Git 敏捷工作流")),
        )
    }

    @Test
    fun `chinese full stop terminates the mention`() {
        assertEquals(
            setOf("codegraph"),
            MentionExtractor.parse("@CodeGraph。帮我看看", listOf("CodeGraph")),
        )
    }

    @Test
    fun `brackets terminate the mention`() {
        assertEquals(
            setOf("android_reverse"),
            MentionExtractor.parse("看下 @android_reverse）然后继续", listOf("android_reverse")),
        )
    }

    @Test
    fun `multiple mentions in one message are all collected`() {
        val names = MentionExtractor.parse(
            "@code_graph @git_workflow 一起用",
            listOf("code_graph", "git_workflow"),
        )
        assertEquals(setOf("code_graph", "git_workflow"), names)
    }

    @Test
    fun `unknown entity still collected by generic fallback`() {
        assertEquals(
            setOf("some_unknown_service"),
            MentionExtractor.parse("@some_unknown_service 试试", listOf("已知技能")),
        )
    }

    @Test
    fun `known name does not swallow longer unrelated token`() {
        // 边界保护：候选名后必须紧跟终止符或文本结束，"Git" 不得命中 "GitHub" 的前缀。
        // 注意结果仍是 GitHub 本身（由通用兜底作为未登记实体提取），关键是**不能变成 "git"**。
        val names = MentionExtractor.parse("@GitHub 仓库", listOf("Git"))
        assertEquals(setOf("github"), names)
    }

    @Test
    fun `leading slash in known trigger command is tolerated`() {
        assertEquals(
            setOf("git-workflow"),
            MentionExtractor.parse("@git-workflow 提交", listOf("/git-workflow")),
        )
    }

    // ---- 代码段 / URL 路径 / 全体保留字 ----

    @Test
    fun `mention inside fenced code block is ignored`() {
        // Bot 回复常贴出含 @ 的命令原文；围栏块内不是提及，不得触发群聊接力。
        val text = """
            看下这段：
            ```
            git blame user@host.com
            再喊 @张三 来看
            ```
            结论是 @李四 负责修。
        """.trimIndent()
        assertEquals(setOf("李四"), MentionExtractor.parse(text, listOf("张三", "李四")))
    }

    @Test
    fun `mention inside inline code is ignored`() {
        assertEquals(
            emptySet<String>(),
            MentionExtractor.parse("运行 `@deploy --dry-run` 试试", listOf("deploy")),
        )
    }

    @Test
    fun `url path at sign is not a mention`() {
        assertEquals(emptySet<String>(), MentionExtractor.parse("看 https://weibo.com/@张三 的主页"))
    }

    @Test
    fun `all keyword is detected case-insensitively`() {
        assertTrue(MentionExtractor.containsAllKeyword("@ALL 都到齐了吗"))
        assertTrue(MentionExtractor.containsAllKeyword("@所有人 看一下"))
        assertTrue(MentionExtractor.containsAllKeyword("请 @大家 评审"))
        assertTrue(!MentionExtractor.containsAllKeyword("@张三 所有人都到了"))
    }

    @Test
    fun `all keyword inside code block is not detected`() {
        assertTrue(!MentionExtractor.containsAllKeyword("示例：`@everyone` 只是个例子"))
    }
}
