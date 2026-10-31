package top.tianyan.app.core.database

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.model.AgentSkill

class AgentSkillDirectorySyncTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dao = MemorySkillDao()
    private val repository = AgentSkillRepository(dao)

    @Test
    fun `navigation skill keeps nested prompts and markdown as resources`() = runBlocking {
        val root = temporaryFolder.newFolder("skills")
        val parent = prompt(root, "navigator/SKILL.md", "navigator")
        val reference = prompt(root, "navigator/references/step/SKILL.md", "step")
        prompt(root, "navigator/workflows/prompt.md", "workflow")
        File(parent.parentFile, "references/guide.md").writeText("# Guide")

        val imported = repository.syncFromDirectories(listOf(SkillScanRoot(root, "/attachments/skills")))

        assertEquals(listOf("navigator"), imported.map { it.name })
        assertEquals(parent.parentFile!!.absolutePath, imported.single().resourcePath)
        assertTrue(imported.single().systemPrompt.contains("【Skill 资源目录】/attachments/skills/navigator"))
        assertTrue(reference.isFile)
        assertEquals(listOf("navigator"), customSkills().map { it.name })
    }

    @Test
    fun `collection without entry discovers independent sibling skills at different depths`() = runBlocking {
        val root = temporaryFolder.newFolder("skills")
        prompt(root, "collection/first/SKILL.md", "first")
        prompt(root, "collection/group/second/prompt.md", "second")
        prompt(root, "collection/group/second/references/child/SKILL.md", "child")

        val roots = listOf(SkillScanRoot(root, "/workspace/skills"))
        assertEquals(setOf("first", "second"), repository.syncFromDirectories(roots).map { it.name }.toSet())
        assertTrue(repository.syncFromDirectories(roots).isEmpty())
    }

    @Test
    fun `rescan removes legacy child rows and preserves parent settings and all files`() = runBlocking {
        val root = temporaryFolder.newFolder("skills")
        val parent = prompt(root, "navigator/SKILL.md", "navigator").parentFile!!
        val child = prompt(root, "navigator/references/child/SKILL.md", "child").parentFile!!
        val sibling = prompt(root, "navigator-other/SKILL.md", "sibling").parentFile!!
        repository.addCustom(skill("parent", parent).copy(isEnabled = false))
        repository.addCustom(skill("child", child))
        repository.addCustom(skill("sibling", sibling))
        repository.addCustom(AgentSkill("manual", "manual", "", "# Manual", isBuiltin = false))

        assertTrue(repository.syncFromDirectories(listOf(SkillScanRoot(root, "/attachments/skills"))).isEmpty())

        assertEquals(setOf("parent", "sibling", "manual"), customSkills().map { it.id }.toSet())
        assertFalse(customSkills().first { it.id == "parent" }.isEnabled)
        assertTrue(File(parent, "SKILL.md").isFile)
        assertTrue(File(child, "SKILL.md").isFile)
    }

    @Test
    fun `new parent replaces previously discovered child entries`() = runBlocking {
        val root = temporaryFolder.newFolder("skills")
        val child = prompt(root, "navigator/references/child/SKILL.md", "child").parentFile!!
        repository.addCustom(skill("legacy-child", child))
        prompt(root, "navigator/SKILL.md", "navigator")

        val imported = repository.syncFromDirectories(listOf(SkillScanRoot(root, "/attachments/skills")))

        assertEquals(listOf("navigator"), imported.map { it.name })
        assertEquals(imported, customSkills())
        assertTrue(File(child, "SKILL.md").isFile)
    }

    @Test
    fun `overlapping scan roots cannot promote nested resources regardless of order`() = runBlocking {
        val root = temporaryFolder.newFolder("skills")
        val parent = prompt(root, "navigator/SKILL.md", "navigator").parentFile!!
        val references = File(parent, "references")
        prompt(references, "child/SKILL.md", "child")

        val imported = repository.syncFromDirectories(listOf(
            SkillScanRoot(references, "/attachments/skills/navigator/references"),
            SkillScanRoot(root, "/attachments/skills"),
        ))

        assertEquals(listOf("navigator"), imported.map { it.name })
    }

    @Test
    fun `legacy archive wrapper remains one registered package`() = runBlocking {
        val root = temporaryFolder.newFolder("skills")
        val wrapper = File(root, "custom_archive")
        val child = prompt(wrapper, "navigator/SKILL.md", "navigator").parentFile!!
        prompt(child, "references/step/SKILL.md", "step")
        repository.addCustom(skill("archive", wrapper))
        repository.addCustom(skill("duplicate", child))

        assertTrue(repository.syncFromDirectories(listOf(SkillScanRoot(root, "/attachments/skills"))).isEmpty())
        assertEquals(listOf("archive"), customSkills().map { it.id })
        assertTrue(File(child, "SKILL.md").isFile)
    }

    @Test
    fun `scan does not remove rows outside selected roots`() = runBlocking {
        val root = temporaryFolder.newFolder("skills")
        val otherRoot = temporaryFolder.newFolder("other")
        val parent = prompt(otherRoot, "navigator/SKILL.md", "navigator").parentFile!!
        val child = prompt(parent, "references/child/SKILL.md", "child").parentFile!!
        repository.addCustom(skill("parent", parent))
        repository.addCustom(skill("child", child))

        repository.syncFromDirectories(listOf(SkillScanRoot(root, "/attachments/skills")))

        assertEquals(setOf("parent", "child"), customSkills().map { it.id }.toSet())
    }

    @Test
    fun `selected skill root prefers SKILL md to prompt md`() = runBlocking {
        val root = temporaryFolder.newFolder("navigator")
        prompt(root, "prompt.md", "fallback")
        prompt(root, "skill.MD", "navigator")
        prompt(root, "references/step/SKILL.md", "step")

        val imported = repository.syncFromDirectories(listOf(SkillScanRoot(root, "/attachments/skills/navigator")))

        assertEquals(listOf("navigator"), imported.map { it.name })
        assertTrue(imported.single().systemPrompt.contains("【Skill 资源目录】/attachments/skills/navigator\n"))
    }

    @Test
    fun `archive discovery finds one wrapped main entry and keeps relative resource paths`() {
        val root = temporaryFolder.newFolder("archive")
        val main = prompt(root, "package/navigator/SKILL.md", "navigator")
        prompt(root, "package/navigator/prompt.md", "fallback")
        val child = prompt(root, "package/navigator/references/child/SKILL.md", "child")

        val discovered = SkillDirectoryDiscovery.discover(root)

        assertEquals(listOf(main), discovered)
        assertEquals("references/child/SKILL.md", child.relativeTo(discovered.single().parentFile!!).invariantSeparatorsPath)
    }

    private fun prompt(root: File, path: String, name: String): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText("---\nname: $name\ndescription: $name skill\n---\n# $name\n")
    }

    private fun skill(id: String, directory: File) = AgentSkill(
        id, id, "", "# $id", isBuiltin = false, resourcePath = directory.absolutePath,
    )

    private suspend fun customSkills() = repository.allSkills.first().filterNot { it.isBuiltin }

    private class MemorySkillDao : AgentSkillDao {
        private val rows = MutableStateFlow<List<AgentSkillEntity>>(emptyList())
        override fun observeAll() = rows

        override suspend fun insertAll(skills: List<AgentSkillEntity>) {
            rows.value += skills.filter { skill -> rows.value.none { it.id == skill.id } }
        }

        override suspend fun upsert(skill: AgentSkillEntity) {
            rows.value = rows.value.filterNot { it.id == skill.id } + skill
        }

        override suspend fun setEnabled(id: String, enabled: Boolean) {
            rows.value = rows.value.map { if (it.id == id && !it.isImmutable) it.copy(isEnabled = enabled) else it }
        }

        override suspend fun deleteCustom(id: String) {
            rows.value = rows.value.filterNot { it.id == id && !it.isBuiltin }
        }
    }
}
