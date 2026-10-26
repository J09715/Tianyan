package top.tianyan.app.core.database

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.BackupRecords

/** Tests for BackupRecordPolicy — runs in core:database module, no Android runtime required. */
class BackupRecordPolicyTest {

    @Test fun `portable resets approvalMode in harness_sessions`() {
        val row = JsonObject(mapOf(
            "id" to JsonPrimitive("s1"),
            "name" to JsonPrimitive("test"),
            "approvalMode" to JsonPrimitive("full"),
        ))
        val portable = BackupRecordPolicy.portable(BackupRecords(mapOf("harness_sessions" to listOf(row))))
        val r = portable.tables.getValue("harness_sessions").single()
        assertEquals(JsonPrimitive("full_access"), r["approvalMode"])
        assertEquals(JsonPrimitive("s1"), r["id"])
        assertEquals(JsonPrimitive("test"), r["name"])
    }

    @Test fun `portable zeros runtime fields in harness_lanes`() {
        val row = JsonObject(mapOf(
            "id" to JsonPrimitive("lane1"), "sessionId" to JsonPrimitive("s1"),
            "name" to JsonPrimitive("main"),
            "leafId" to JsonPrimitive("e1"),
            "currentOperationId" to JsonPrimitive("op"),
            "faulted" to JsonPrimitive(1),
        ))
        val portable = BackupRecordPolicy.portable(BackupRecords(mapOf("harness_lanes" to listOf(row))))
        val r = portable.tables.getValue("harness_lanes").single()
        assertEquals(JsonNull, r["currentOperationId"])
        assertEquals(JsonPrimitive(0), r["faulted"])
        assertEquals(JsonPrimitive("lane1"), r["id"])
    }

    @Test fun `portable zeros isEnabled and isBuiltin on user-created agent_skills`() {
        val row = JsonObject(mapOf(
            "id" to JsonPrimitive("skill1"), "name" to JsonPrimitive("s"),
            "isEnabled" to JsonPrimitive(1), "isBuiltin" to JsonPrimitive(0), "isImmutable" to JsonPrimitive(1),
        ))
        val portable = BackupRecordPolicy.portable(BackupRecords(mapOf("agent_skills" to listOf(row))))
        val r = portable.tables.getValue("agent_skills").single()
        assertEquals(JsonPrimitive(0), r["isEnabled"])
        assertEquals(JsonPrimitive(0), r["isBuiltin"])
        assertEquals(JsonPrimitive(0), r["isImmutable"])
    }

    @Test fun `portable strips credentials from harness_models`() {
        val row = JsonObject(mapOf(
            "id" to JsonPrimitive("m1"), "name" to JsonPrimitive("GPT-4"),
            "model" to JsonPrimitive("gpt-4"), "baseUrl" to JsonPrimitive("https://api.openai.com/v1"),
            "secretRef" to JsonPrimitive("secret-ref-123"), "apiKeyCount" to JsonPrimitive(2),
            "customHeaders" to JsonPrimitive("Authorization: Bearer sk-abc"),
        ))
        val portable = BackupRecordPolicy.portable(BackupRecords(mapOf("harness_models" to listOf(row))))
        val r = portable.tables.getValue("harness_models").single()
        assertEquals(JsonPrimitive(""), r["secretRef"])
        assertEquals(JsonPrimitive(0), r["apiKeyCount"])
        assertEquals(JsonPrimitive(""), r["customHeaders"])
        assertEquals(JsonPrimitive("GPT-4"), r["name"])
    }

    @Test fun `portable drops builtin skills and subagents`() {
        val builtinSkill = JsonObject(mapOf(
            "id" to JsonPrimitive("bs1"), "name" to JsonPrimitive("builtin"), "isBuiltin" to JsonPrimitive(1), "isImmutable" to JsonPrimitive(1), "isEnabled" to JsonPrimitive(1),
        ))
        val userSkill = JsonObject(mapOf(
            "id" to JsonPrimitive("us1"), "name" to JsonPrimitive("user"), "isBuiltin" to JsonPrimitive(0), "isImmutable" to JsonPrimitive(0), "isEnabled" to JsonPrimitive(1),
        ))
        val portable = BackupRecordPolicy.portable(BackupRecords(mapOf("agent_skills" to listOf(builtinSkill, userSkill))))
        val skills = portable.tables.getValue("agent_skills")
        assertEquals(1, skills.size)
        assertEquals("us1", skills.single()["id"]?.jsonPrimitive?.content)
    }

    @Test fun `missing filters existing sessions and their children`() {
        val existingSession = JsonObject(mapOf("id" to JsonPrimitive("local-s")))
        val current = BackupRecords(mapOf(
            "harness_sessions" to listOf(existingSession),
            "harness_entries" to emptyList(),
        ))
        val incoming = BackupRecords(mapOf(
            "harness_sessions" to listOf(
                existingSession,
                JsonObject(mapOf("id" to JsonPrimitive("new-s"))),
            ),
            "harness_entries" to listOf(
                JsonObject(mapOf("id" to JsonPrimitive("e1"), "sessionId" to JsonPrimitive("local-s"))),
                JsonObject(mapOf("id" to JsonPrimitive("e2"), "sessionId" to JsonPrimitive("new-s"))),
            ),
        ))
        val missing = BackupRecordPolicy.missing(incoming, current)
        assertEquals(1, missing.tables.getValue("harness_sessions").size)
        assertEquals("new-s", missing.tables.getValue("harness_sessions").single()["id"]?.jsonPrimitive?.content)
        assertEquals(1, missing.tables.getValue("harness_entries").size)
        assertEquals("e2", missing.tables.getValue("harness_entries").single()["id"]?.jsonPrimitive?.content)
    }

    @Test fun `missing drops session-scope memories of existing sessions but keeps others`() {
        val current = BackupRecords(mapOf("harness_sessions" to listOf(JsonObject(mapOf("id" to JsonPrimitive("local-s"))))))
        val mem1 = JsonObject(mapOf("id" to JsonPrimitive("m1"), "scope" to JsonPrimitive("session"), "ownerId" to JsonPrimitive("local-s")))
        val mem2 = JsonObject(mapOf("id" to JsonPrimitive("m2"), "scope" to JsonPrimitive("session"), "ownerId" to JsonPrimitive("other-s")))
        val mem3 = JsonObject(mapOf("id" to JsonPrimitive("m3"), "scope" to JsonPrimitive("global"), "ownerId" to JsonPrimitive("local-s")))
        val incoming = BackupRecords(mapOf("agent_memories" to listOf(mem1, mem2, mem3)))
        val missing = BackupRecordPolicy.missing(incoming, current)
        val ids = missing.tables.getValue("agent_memories").map { it["id"]?.jsonPrimitive?.content }
        assertFalse("m1 (session-scope of local session) should be filtered", "m1" in ids)
        assertTrue("m2 (session-scope of foreign session) should pass", "m2" in ids)
        assertTrue("m3 (global-scope) should pass", "m3" in ids)
    }
}
