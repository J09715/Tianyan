package top.tianyan.app.harness.skill

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillResourceReaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `strip frontmatter removes yaml header`() {
        val markdown = "---\nname: demo\ndescription: >\n  多行描述\n---\n# 正文\n内容"
        assertEquals("# 正文\n内容", SkillResourceReader.stripFrontmatter(markdown))
    }

    @Test
    fun `strip frontmatter is a no-op without header`() {
        assertEquals("# 正文", SkillResourceReader.stripFrontmatter("# 正文"))
    }

    @Test
    fun `reads sub resource inside skill directory`() {
        val dir = temporaryFolder.newFolder("skill")
        File(dir, "references").mkdirs()
        File(dir, "references/spec.md").writeText("SPEC")

        assertEquals("SPEC", SkillResourceReader.readSubResource(dir, "references/spec.md"))
    }

    @Test
    fun `rejects parent traversal and absolute escape`() {
        val dir = temporaryFolder.newFolder("skill")
        assertNull(SkillResourceReader.readSubResource(dir, "../secret.txt"))
        assertNull(SkillResourceReader.readSubResource(dir, "/etc/passwd"))
    }

    @Test
    fun `oversized resource returns guidance instead of content`() {
        val dir = temporaryFolder.newFolder("skill")
        File(dir, "big.txt").writeText("x".repeat((SkillResourceReader.MAX_RESOURCE_BYTES + 1).toInt()))

        val out = SkillResourceReader.readSubResource(dir, "big.txt")

        assertTrue(out != null && out.contains("资源过大"))
    }

    @Test
    fun `missing resource returns null`() {
        val dir = temporaryFolder.newFolder("skill")
        assertNull(SkillResourceReader.readSubResource(dir, "nope.md"))
    }
}