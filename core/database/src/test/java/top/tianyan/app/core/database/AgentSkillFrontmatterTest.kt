package top.tianyan.app.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SKILL.md frontmatter 解析的规范对齐测试（借鉴 OpenMinis / Claude Code 技能生态）。
 */
class AgentSkillFrontmatterTest {

    @Test
    fun `parses simple single line frontmatter`() {
        val md = """
            ---
            name: pdf-export
            description: Export documents to PDF
            ---

            # Body
        """.trimIndent()
        val meta = AgentSkillRepository.parseFrontmatter(md)
        assertEquals("pdf-export", meta["name"])
        assertEquals("Export documents to PDF", meta["description"])
    }

    @Test
    fun `tolerates utf8 bom and crlf line endings`() {
        val md = "﻿---\r\nname: notes\r\ndescription: Obsidian vault skills\r\n---\r\nbody"
        val meta = AgentSkillRepository.parseFrontmatter(md)
        assertEquals("notes", meta["name"])
        assertEquals("Obsidian vault skills", meta["description"])
    }

    @Test
    fun `parses folded multiline description`() {
        val md = """
            ---
            name: web-research
            description: >-
              Research any topic across search
              engines and summarize findings.
            allowed-tools: [search, read]
            ---

            body
        """.trimIndent()
        val meta = AgentSkillRepository.parseFrontmatter(md)
        assertEquals(
            "Research any topic across search engines and summarize findings.",
            meta["description"],
        )
        // 未知字段（allowed-tools / license 等）保留在映射中供上游选用，
        // 技能导入只消费 name / description，其余自动忽略
        assertEquals("[search, read]", meta["allowed-tools"])
    }

    @Test
    fun `strips unquoted inline comments and quoted hashes survive`() {
        val md = """
            ---
            name: runner # main skill
            description: "runs #1 benchmarks"
            ---
        """.trimIndent()
        val meta = AgentSkillRepository.parseFrontmatter(md)
        assertEquals("runner", meta["name"])
        assertEquals("runs #1 benchmarks", meta["description"])
    }

    @Test
    fun `keys are case insensitive and body without frontmatter yields empty`() {
        val meta1 = AgentSkillRepository.parseFrontmatter("---\nName: a\n---\n")
        assertEquals("a", meta1["name"])
        assertTrue(AgentSkillRepository.parseFrontmatter("no frontmatter here").isEmpty())
        assertNull(AgentSkillRepository.extractSkillMetadata("plain text", "name"))
    }

    @Test
    fun `literal block and terminator ellipsis are handled`() {
        val md = "---\ndescription: |\n  line one\n  line two\n...\n"
        val meta = AgentSkillRepository.parseFrontmatter(md)
        assertEquals("line one line two", meta["description"])
    }

    @Test
    fun `folds plain multiline value without block indicator`() {
        // 官方 Android skills（google/android/skills）32/33 个 SKILL.md 都是这种写法：
        // `description:` 首行后接普通缩进续行，没有 >- / | 指示符。
        val md = """
            ---
            name: android-cli
            description: Provides instructions for installing and using the `android` CLI. The
              `android` command-line tool is a critical tool for Android development and helps
              you create new Android projects.
            license: Complete terms in LICENSE.txt
            metadata:
              author: Google LLC
              keywords:
              - android-cli
            ---

            # body
        """.trimIndent()
        val meta = AgentSkillRepository.parseFrontmatter(md)
        assertEquals("android-cli", meta["name"])
        assertEquals(
            "Provides instructions for installing and using the `android` CLI. The " +
                "`android` command-line tool is a critical tool for Android development and helps " +
                "you create new Android projects.",
            meta["description"],
        )
        // 续行折叠不得吞掉后续同级键与嵌套键
        assertEquals("Complete terms in LICENSE.txt", meta["license"])
        assertEquals("Google LLC", meta["author"])
    }

    @Test
    fun `plain multiline value stops at blank line`() {
        val md = "---\ndescription: first line\n  second line\n\nname: after-blank\n---\n"
        val meta = AgentSkillRepository.parseFrontmatter(md)
        assertEquals("first line second line", meta["description"])
        assertEquals("after-blank", meta["name"])
    }
}
