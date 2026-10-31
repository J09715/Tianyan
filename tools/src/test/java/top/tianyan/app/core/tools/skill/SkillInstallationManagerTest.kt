package top.tianyan.app.core.tools.skill

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.database.AgentSkillDao
import top.tianyan.app.core.database.AgentSkillEntity
import top.tianyan.app.core.database.AgentSkillRepository

class SkillInstallationManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installingDifferentNameOnlySkillsRetainsBothRecordsAndResources() = runBlocking {
        verifySeparateInstallations(
            "---\nname: code-review\n---\n# Review code",
            "---\nname: test-runner\n---\n# Run tests",
        )
    }

    @Test
    fun installingSkillsWithoutMetadataRetainsBothRecordsAndResources() = runBlocking {
        verifySeparateInstallations("# Review code", "# Run tests")
    }

    private suspend fun verifySeparateInstallations(firstMarkdown: String, secondMarkdown: String) {
        val repository = AgentSkillRepository(InMemorySkillDao())
        val manager = SkillInstallationManager(
            SkillPackageParser(),
            SkillPackageInspector(),
            SkillCompatibilityEvaluator(),
            ClawHubClient(OkHttpClient()),
            repository,
        )
        val target = temporaryFolder.newFolder("skills")
        val first = manager.commitInstallation(manager.inspectZipBytes(archive(firstMarkdown, "first")), target)
        val second = manager.commitInstallation(manager.inspectZipBytes(archive(secondMarkdown, "second")), target)

        assertNotEquals(first.id, second.id)
        assertEquals(setOf(first.id, second.id), repository.allSkills.first().filterNot { it.isBuiltin }.map { it.id }.toSet())
        assertEquals("first", File(requireNotNull(first.resourcePath), "references/note.txt").readText())
        assertEquals("second", File(requireNotNull(second.resourcePath), "references/note.txt").readText())
    }

    private fun archive(markdown: String, resource: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            mapOf("SKILL.md" to markdown, "references/note.txt" to resource).forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private class InMemorySkillDao : AgentSkillDao {
        private val rows = MutableStateFlow<List<AgentSkillEntity>>(emptyList())
        override fun observeAll() = rows
        override suspend fun insertAll(skills: List<AgentSkillEntity>) {
            rows.value += skills.filter { incoming -> rows.value.none { it.id == incoming.id } }
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
