package top.tianyan.app.core.database

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.BackupRecords

class ExecutionTargetBackupTest {
    @Test fun `portable history never transfers host identity or execution authorization`() {
        val row = buildJsonObject {
            put("id", "session"); put("title", "history"); put("approvalMode", "full_access")
            put("executionTargetJson", """{"distribution":"ssh:private","remote":{"host":"private-host"}}""")
        }
        val exported = BackupRecordPolicy.portable(BackupRecords(mapOf("harness_sessions" to listOf(row))))
        val saved = exported.tables.getValue("harness_sessions").single()
        assertEquals("history", saved.getValue("title").jsonPrimitive.content)
        assertEquals("restore-required", saved.getValue("executionTargetJson").jsonPrimitive.content)
        assertEquals("full_access", saved.getValue("approvalMode").jsonPrimitive.content)
        assertFalse(saved.toString().contains("private-host"))
    }
}
