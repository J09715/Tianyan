package top.tianyan.app.core.tools.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.tianyan.app.core.model.skill.SkillPermission
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillPackageParserTest {

    private val parser = SkillPackageParser()

    @Test
    fun parseFromZip_distinguishesNameOnlySkillsWithSameFallbackId() {
        val first = createTestZip("SKILL.md" to "---\nname: code-review\ndescription: Review code\n---\n# Review")
        val second = createTestZip("SKILL.md" to "---\nname: test-runner\ndescription: Run tests\n---\n# Tests")

        assertEquals("code-review", parser.parseFromZip(first, "custom_zip").manifest.id)
        assertEquals("test-runner", parser.parseFromZip(second, "custom_zip").manifest.id)
        assertEquals("code-review", parser.parseFromZip(first, "another_filename").manifest.id)
    }

    @Test
    fun parseFromZip_explicitIdTakesPriorityOverName() {
        val zip = createTestZip("SKILL.md" to "---\nid: stable-id\nname: display-name\n---\n# Skill")

        assertEquals("stable-id", parser.parseFromZip(zip, "custom_zip").manifest.id)
    }

    @Test
    fun parseFromZip_nameWithoutSafeIdFallsBackToCallerId() {
        val zip = createTestZip("SKILL.md" to "---\nname: 中文技能\n---\n# Skill")

        assertEquals("unique-import", parser.parseFromZip(zip, "unique-import").manifest.id)
    }

    @Test
    fun parseFromZip_acceptsMoreThan500FilesAndDirectories() {
        val entries = buildList {
            add("SKILL.md" to "# Large Skill")
            repeat(600) { index ->
                add("references/dir-$index/" to "")
                add("references/dir-$index/note.md" to "Reference $index")
            }
        }

        val pkg = parser.parseFromZip(createTestZip(*entries.toTypedArray()))

        assertEquals(601, pkg.rawFiles.size)
        assertEquals("Reference 599", pkg.rawFiles.getValue("references/dir-599/note.md").toString(StandardCharsets.UTF_8))
        assertTrue(SkillPackageInspector().inspect(pkg).isSafe)
    }

    @Test
    fun parseFromDirectory_acceptsMoreThan500Files() {
        val dir = java.nio.file.Files.createTempDirectory("large-skill").toFile()
        try {
            java.io.File(dir, "SKILL.md").writeText("# Large Skill")
            repeat(600) { index ->
                java.io.File(dir, "note-$index.md").writeText("Reference $index")
            }

            val pkg = parser.parseFromDirectory(dir)

            assertEquals(601, pkg.rawFiles.size)
            assertTrue(SkillPackageInspector().inspect(pkg).isSafe)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parseFromZip_deconstructsTemplatesCorrectly() {
        val zipBytes = createTestZip(
            "SKILL.md" to """
                ---
                id: test-skill
                name: 测试解构技能
                version: 1.0.0
                description: 用于单元测试的解构技能包
                author: TestAuthor
                category: 开发工程
                tags: test, kotlin
                permissions: [exec_command, file_write]
                required_tools: [git, python3]
                trigger_command: /test
                ---
                # 测试入口总纲
                这是测试技能的主入口说明。
            """.trimIndent(),
            "AGENT.md" to "你是一个代码审查智能体，严格执行 Lint 规范。",
            "SOUL.md" to "严谨周密、保持礼貌。",
            "TOOLS.md" to "优先使用 read 工具查阅代码。",
            "USER.md" to "假设用户是一名经验丰富的 Android 架构师。",
            "MEMORY.md" to "项目代码采用 Kotlin 2.4 与 Jetpack Compose。",
            "scripts/run.sh" to "#!/bin/bash\necho hello",
        )

        val pkg = parser.parseFromZip(zipBytes)

        assertEquals("test-skill", pkg.manifest.id)
        assertEquals("测试解构技能", pkg.manifest.name)
        assertEquals("1.0.0", pkg.manifest.version)
        assertEquals("TestAuthor", pkg.manifest.author)
        assertEquals("/test", pkg.manifest.triggerCommand)
        assertEquals(2, pkg.manifest.permissions.size)
        assertTrue(SkillPermission.EXEC_COMMAND in pkg.manifest.permissions)
        assertTrue(SkillPermission.FILE_WRITE in pkg.manifest.permissions)
        assertEquals(listOf("git", "python3"), pkg.manifest.requiredTools)

        // 验证模板解构
        assertNotNull(pkg.templates.skillMd)
        assertEquals("你是一个代码审查智能体，严格执行 Lint 规范。", pkg.templates.agentMd)
        assertEquals("严谨周密、保持礼貌。", pkg.templates.soulMd)
        assertEquals("优先使用 read 工具查阅代码。", pkg.templates.toolsMd)
        assertEquals("假设用户是一名经验丰富的 Android 架构师。", pkg.templates.userMd)
        assertEquals("项目代码采用 Kotlin 2.4 与 Jetpack Compose。", pkg.templates.memoryMd)

        // 验证系统提示词组装
        val systemPrompt = pkg.templates.composeSystemPrompt("/attachments/skills/test-skill")
        assertTrue(systemPrompt.contains("### 【核心角色与指令 (AGENT)】"))
        assertTrue(systemPrompt.contains("### 【人格边界与风格 (SOUL)】"))
        assertTrue(systemPrompt.contains("### 【工具使用规范 (TOOLS)】"))
        assertTrue(systemPrompt.contains("### 【用户交互契约 (USER)】"))
        assertTrue(systemPrompt.contains("### 【初始化事实记忆 (MEMORY)】"))
        assertTrue(systemPrompt.contains("资源物理路径：`/attachments/skills/test-skill`"))
    }

    @Test
    fun parseFromZip_blocksZipSlipAttack() {
        val evilZip = createTestZip(
            "../../etc/cron.d/evil" to "malicious script",
            "SKILL.md" to "# Normal Skill",
        )

        try {
            parser.parseFromZip(evilZip)
            fail("应该捕获 Zip Slip 攻击并抛出异常")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Zip Slip") == true)
        }
    }

    @Test
    fun parseFromZip_rejectsMaliciousManifestId() {
        val evilZip = createTestZip(
            "SKILL.md" to """
                ---
                id: .
                name: 恶意技能
                ---
                # 恶意入口
            """.trimIndent(),
        )

        try {
            parser.parseFromZip(evilZip)
            fail("应该拒绝非法的 frontmatter id")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("id") == true)
        }
    }

    @Test
    fun parseFromZip_sanitizesPathTraversalIdIntoSafeDirectoryComponent() {
        val traversalZip = createTestZip(
            "SKILL.md" to """
                ---
                id: ../evil
                name: 穿越技能
                ---
                # 入口
            """.trimIndent(),
        )

        val pkg = parser.parseFromZip(traversalZip)
        assertTrue(pkg.manifest.id.matches(Regex("[a-z0-9_-]+")))
        assertTrue(!pkg.manifest.id.contains('/') && !pkg.manifest.id.contains('.'))
    }

    @Test
    fun parseFrontmatter_handlesComplexYamlScalars() {
        val markdown = """
            ---
            Name: "quoted-name"
            Description: >-
              多行折叠的长描述文本
              跨越多行合并为单行
            Author: 'Single Quoted' # 这是行内注释
            Category: 系统运维
            ---
            正文内容
        """.trimIndent()

        val meta = parser.parseFrontmatter(markdown)
        assertEquals("quoted-name", meta["name"])
        assertEquals("多行折叠的长描述文本 跨越多行合并为单行", meta["description"])
        assertEquals("Single Quoted", meta["author"])
        assertEquals("系统运维", meta["category"])
    }

    @Test
    fun parseFrontmatter_foldsPlainMultilineValueWithoutIndicator() {
        val markdown = """
            ---
            name: edge-to-edge
            description: Instructions to make an app edge-to-edge. The
              system bars draw behind the app content and
              inset handling is delegated to Compose.
            license: Complete terms in LICENSE.txt
            metadata:
              author: Google LLC
            ---
            正文内容
        """.trimIndent()

        val meta = parser.parseFrontmatter(markdown)
        assertEquals("edge-to-edge", meta["name"])
        assertEquals(
            "Instructions to make an app edge-to-edge. The system bars draw behind the app " +
                "content and inset handling is delegated to Compose.",
            meta["description"],
        )
        assertEquals("Complete terms in LICENSE.txt", meta["license"])
        assertEquals("Google LLC", meta["author"])
    }

    @Test
    fun parseFromZip_keepsFoldedDescriptionInManifest() {
        val zipBytes = createTestZip(
            "SKILL.md" to """
                ---
                name: r8-analyzer
                description: Analyzes Android build files and R8 keep rules to identify redundancies,
                  broad package-wide rules, and rules that subsume library consumer keep rules.
                ---
                # R8
            """.trimIndent(),
        )

        val pkg = parser.parseFromZip(zipBytes)
        assertEquals(
            "Analyzes Android build files and R8 keep rules to identify redundancies, " +
                "broad package-wide rules, and rules that subsume library consumer keep rules.",
            pkg.manifest.description,
        )
    }

    private fun createTestZip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
