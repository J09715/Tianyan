package top.tianyan.app.core.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.database.AiModelRepository
import top.tianyan.app.core.model.AiModelProfileExport
import top.tianyan.app.core.model.AiProfileImportMode

class AiProfileBackupCodecTest {
    private val models = Models()
    private val secrets = Secrets()
    private val codec = AiProfileBackupCodec(models, secrets, AiProfileWriter(models, secrets))
    private fun existing(active: Boolean = true) = AiModelEntity("old", "Old", "OpenAI", "model", "https://example.com/v1",
        secretRef = "old_secret", isActive = active, createdAt = 123,
        compactionKeepRecentTokens = 12000, compactionReserveTokens = 4096,
        customHeaders = "Authorization: header-secret\nX-Tenant: private", apiKeyCount = 2,
        responseApiEnabled = true, promptCacheTtl1h = true, requestsPerMinutePerKey = 5)
    private fun seed(active: Boolean = true) { models.rows.value = listOf(existing(active)); secrets.values["old_secret"] = listOf("key-1", "key-2") }
    private fun raw(profile: AiModelProfileExport) = AiProfileTransferFormat.json.encodeToString(AiModelProfileExport.serializer(), profile)

    @Test fun `default export excludes all credentials without reading encrypted storage`() = runBlocking {
        seed()
        val output = codec.exportAll(false)
        val profile = codec.parseProfiles(output).getOrThrow().single()
        assertEquals(2, profile.schemaVersion)
        assertEquals(false, profile.credentialsIncluded)
        assertTrue(profile.apiKeys.isEmpty())
        assertTrue(profile.customHeaders.isBlank())
        assertFalse(output.contains("header-secret"))
        assertFalse(output.contains("key-1"))
        assertEquals(0, secrets.reads)
        assertEquals(12000, profile.compactionKeepRecentTokens)
        assertEquals(4096, profile.compactionReserveTokens)
    }
    @Test fun `full export round trip preserves all profile parameters and keys`() = runBlocking {
        seed()
        val profile = codec.parseProfiles(codec.exportSingle("old", true)!!).getOrThrow().single()
        assertEquals(listOf("key-1", "key-2"), profile.apiKeys)
        assertEquals(existing().customHeaders, profile.customHeaders)
        assertTrue(codec.importProfiles(raw(profile)).isSuccess)
        val imported = models.rows.value.single { it.id != "old" }
        assertEquals(existing().copy(id = imported.id, secretRef = imported.secretRef, isActive = false, createdAt = imported.createdAt), imported)
        assertEquals(listOf("key-1", "key-2"), secrets.values[imported.secretRef])
    }
    @Test fun `default import creates a copy and cannot overwrite a matching id or inherit its credentials`() = runBlocking {
        seed()
        assertEquals(1, codec.importProfiles(raw(AiModelProfileExport(id = "old", name = "New", model = "new"))).getOrThrow())
        assertEquals(existing(), models.rows.value.single { it.id == "old" })
        val created = models.rows.value.single { it.id != "old" }
        assertEquals(0, created.apiKeyCount)
        assertTrue(secrets.values[created.secretRef].isNullOrEmpty())
        assertEquals("old", models.activeModel()!!.id)
    }
    @Test fun `credential free update preserves local keys headers and activation`() = runBlocking {
        seed()
        val output = codec.exportSingle("old", false)!!
        assertTrue(codec.importProfiles(output, AiProfileImportMode.UPDATE_MATCHING_IDS).isSuccess)
        val updated = models.rows.value.single()
        assertEquals(existing().copy(secretRef = updated.secretRef), updated)
        assertEquals(listOf("key-1", "key-2"), secrets.values[updated.secretRef])
        assertFalse(secrets.values.containsKey("old_secret"))
    }
    @Test fun `legacy update keeps compaction overrides while v2 can clear them`() = runBlocking {
        seed()
        val legacy = AiModelProfileExport(id = "old", model = "model")
        assertTrue(codec.importProfiles(raw(legacy), AiProfileImportMode.UPDATE_MATCHING_IDS).isSuccess)
        assertEquals(12000, models.rows.value.single().compactionKeepRecentTokens)
        assertTrue(codec.importProfiles(raw(legacy.copy(schemaVersion = 2)), AiProfileImportMode.UPDATE_MATCHING_IDS).isSuccess)
        assertNull(models.rows.value.single().compactionKeepRecentTokens)
        assertNull(models.rows.value.single().compactionReserveTokens)
    }
    @Test fun `a fully validated batch activates exactly one profile when none is active`() = runBlocking {
        seed(active = false)
        val output = """[{"id":"old","model":"updated"},{"id":"new","model":"new"}]"""
        assertEquals(2, codec.importProfiles(output, AiProfileImportMode.UPDATE_MATCHING_IDS).getOrThrow())
        assertEquals(1, models.rows.value.count { it.isActive })
        assertEquals("old", models.activeModel()!!.id)
    }
    @Test fun `invalid later row produces zero model and credential writes`() = runBlocking {
        seed()
        assertTrue(codec.importProfiles("""[{"model":"valid"},{}]""").isFailure)
        assertEquals(listOf(existing()), models.rows.value)
        assertEquals(setOf("old_secret"), secrets.values.keys)
        assertEquals(0, secrets.writes)
    }
    @Test fun `credential staging failure rolls back without touching old keys or models`() = runBlocking {
        seed(); secrets.failAt = 2
        assertTrue(codec.importProfiles("""[{"id":"old","model":"a","apiKey":"changed"},{"model":"b"}]""",
            AiProfileImportMode.UPDATE_MATCHING_IDS).isFailure)
        assertEquals(listOf(existing()), models.rows.value)
        assertEquals(mapOf("old_secret" to listOf("key-1", "key-2")), secrets.values)
    }
    @Test fun `database failure discards staged keys and retains the whole old batch`() = runBlocking {
        seed(); models.failCommit = true
        assertTrue(codec.importProfiles(raw(AiModelProfileExport(id = "old", model = "changed", apiKey = "new-key")),
            AiProfileImportMode.UPDATE_MATCHING_IDS).isFailure)
        assertEquals(listOf(existing()), models.rows.value)
        assertEquals(setOf("old_secret"), secrets.values.keys)
    }
    @Test fun `concurrent edit prevents stale import from overwriting it`() = runBlocking {
        seed()
        secrets.onWrite = { models.rows.value = listOf(existing().copy(name = "Concurrent edit")) }
        assertTrue(codec.importProfiles(raw(AiModelProfileExport(id = "old", model = "changed")), AiProfileImportMode.UPDATE_MATCHING_IDS).isFailure)
        assertEquals("Concurrent edit", models.rows.value.single().name)
        assertEquals(setOf("old_secret"), secrets.values.keys)
    }
    @Test fun `cancellation before commit propagates and cleans staged references`() = runBlocking {
        seed(); secrets.onWrite = { throw CancellationException("cancelled") }
        try {
            codec.importProfiles(raw(AiModelProfileExport(model = "new")))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf(existing()), models.rows.value)
        assertEquals(setOf("old_secret"), secrets.values.keys)
    }
    @Test fun `cancellation during commit never deletes keys referenced by committed models`() = runBlocking {
        seed()
        lateinit var task: Job
        models.afterCommit = { task.cancel() }
        task = launch { codec.importProfiles(raw(AiModelProfileExport(id = "old", model = "updated")), AiProfileImportMode.UPDATE_MATCHING_IDS) }
        task.join()
        val committed = models.rows.value.single()
        assertEquals("updated", committed.model)
        assertEquals(listOf("key-1", "key-2"), secrets.values[committed.secretRef])
    }
    @Test fun `unreadable existing keys are not silently replaced or exported as a full backup`() = runBlocking {
        seed(); secrets.values.clear()
        assertTrue(runCatching { codec.exportSingle("old", true) }.isFailure)
        assertTrue(codec.importProfiles(raw(AiModelProfileExport(id = "old", model = "new")), AiProfileImportMode.UPDATE_MATCHING_IDS).isFailure)
        assertEquals(listOf(existing()), models.rows.value)
    }
    @Test fun `shared legacy credential reference remains available to unmodified profiles`() = runBlocking {
        seed()
        val other = existing().copy(id = "other", name = "Other", isActive = false)
        models.rows.value = models.rows.value + other
        assertTrue(codec.importProfiles(raw(AiModelProfileExport(id = "old", model = "updated")), AiProfileImportMode.UPDATE_MATCHING_IDS).isSuccess)
        assertEquals(listOf("key-1", "key-2"), secrets.values["old_secret"])
        assertEquals(other, models.rows.value.single { it.id == "other" })
    }
    @Test fun `full export rejects missing credential reference while credential free export is still available`() = runBlocking {
        models.rows.value = listOf(existing().copy(secretRef = ""))
        assertTrue(runCatching { codec.exportSingle("old", true) }.isFailure)
        assertNotNull(codec.exportSingle("old", false))
    }

    private class Secrets : ModelCredentialStore {
        val values = linkedMapOf<String, List<String>>()
        var writes = 0; var reads = 0; var failAt: Int? = null; var onWrite: () -> Unit = {}
        override suspend fun readModelApiKeys(secretRef: String): List<String> { reads++; return values[secretRef].orEmpty() }
        override suspend fun setModelApiKeys(secretRef: String, values: List<String>) {
            this.values[secretRef] = values; writes++; onWrite()
            check(writes != failAt) { "Storage unavailable" }
        }
        override suspend fun removeModelApiKey(secretRef: String) { values.remove(secretRef) }
    }
    private class Models : AiModelRepository {
        val rows = MutableStateFlow<List<AiModelEntity>>(emptyList())
        var failCommit = false; var afterCommit: () -> Unit = {}
        override fun observeAll() = rows
        override suspend fun findById(id: String) = rows.value.find { it.id == id }
        override suspend fun activeModel() = rows.value.find { it.isActive }
        override suspend fun upsert(model: AiModelEntity) { rows.value = rows.value.filterNot { it.id == model.id } + model }
        override suspend fun importBatch(expected: List<AiModelEntity>, models: List<AiModelEntity>) {
            check(!failCommit && rows.value == expected)
            rows.value = rows.value.filterNot { row -> models.any { it.id == row.id } } + models
            afterCommit()
        }
        override suspend fun clearActive() { rows.value = rows.value.map { it.copy(isActive = false) } }
        override suspend fun setActive(id: String) { rows.value = rows.value.map { if (it.id == id) it.copy(isActive = true) else it } }
        override suspend fun activate(id: String) { clearActive(); setActive(id) }
        override suspend fun updateReasoning(id: String, mode: String?, effort: String?) {}
        override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
    }
}
