package top.tianyan.app.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager
import java.time.Instant

/**
 * Robolectric 自带 SQLite 未编 JSON1，无法跑 Room 的 json_extract 查询。
 * 这里用带 JSON1 的 sqlite-jdbc 执行与 DAO 相同的按日聚合 SQL。
 */
class HarnessDailyAggregateSqlTest {

    @Test
    fun `daily aggregate sql sums tokens skips invalid json and uses local offset`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().execute(
                """
                CREATE TABLE harness_entries (
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    id TEXT NOT NULL,
                    sessionId TEXT NOT NULL,
                    parentId TEXT,
                    createdAt INTEGER NOT NULL,
                    entryType TEXT NOT NULL,
                    customType TEXT,
                    payloadJson TEXT NOT NULL
                )
                """.trimIndent(),
            )
            val shanghaiOffsetMs = 8L * 60 * 60 * 1000
            val afternoon = Instant.parse("2026-09-17T15:00:00Z").toEpochMilli()
            val evening = Instant.parse("2026-09-17T16:00:00Z").toEpochMilli()
            conn.prepareStatement(
                "INSERT INTO harness_entries(id, sessionId, parentId, createdAt, entryType, customType, payloadJson) VALUES (?,?,?,?,?,?,?)",
            ).use { stmt ->
                fun insert(id: String, parent: String?, createdAt: Long, customType: String, payload: String) {
                    stmt.setString(1, id)
                    stmt.setString(2, "stats-daily")
                    stmt.setString(3, parent)
                    stmt.setLong(4, createdAt)
                    stmt.setString(5, "message")
                    stmt.setString(6, customType)
                    stmt.setString(7, payload)
                    stmt.executeUpdate()
                }
                insert("user-1", null, afternoon, "user", """{"text":"abcdefghij"}""")
                insert("asst-1", "user-1", evening, "assistant", """{"text":"ok","promptTokens":120,"completionTokens":40,"cachedTokens":8}""")
                insert("blob-1", "asst-1", evening, "tool_result", "@@TIANYAN_BLOB@@:harness_blobs/huge")
            }

            val sql = """
                SELECT CAST((createdAt + ?) / 86400000 AS INTEGER) AS localEpochDay,
                       sessionId AS sessionId,
                       customType AS customType,
                       COUNT(*) AS entryCount,
                       SUM(CASE WHEN json_valid(payloadJson) THEN COALESCE(json_extract(payloadJson, '${'$'}.promptTokens'), 0) ELSE 0 END) AS promptTokens,
                       SUM(CASE WHEN json_valid(payloadJson) THEN COALESCE(json_extract(payloadJson, '${'$'}.completionTokens'), 0) ELSE 0 END) AS completionTokens,
                       SUM(CASE WHEN json_valid(payloadJson) THEN COALESCE(json_extract(payloadJson, '${'$'}.cachedTokens'), 0) ELSE 0 END) AS cachedTokens,
                       SUM(CASE WHEN json_valid(payloadJson) THEN LENGTH(COALESCE(json_extract(payloadJson, '${'$'}.text'), '')) ELSE 0 END) AS textChars,
                       SUM(CASE WHEN json_valid(payloadJson) THEN LENGTH(COALESCE(json_extract(payloadJson, '${'$'}.reasoning'), '')) ELSE 0 END) AS reasoningChars
                FROM harness_entries
                GROUP BY CAST((createdAt + ?) / 86400000 AS INTEGER), sessionId, customType
            """.trimIndent()

            data class Row(
                val day: Long,
                val type: String,
                val count: Int,
                val prompt: Long,
                val completion: Long,
                val cached: Long,
                val textChars: Long,
            )
            val rows = mutableListOf<Row>()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, shanghaiOffsetMs)
                stmt.setLong(2, shanghaiOffsetMs)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        rows += Row(
                            day = rs.getLong("localEpochDay"),
                            type = rs.getString("customType"),
                            count = rs.getInt("entryCount"),
                            prompt = rs.getLong("promptTokens"),
                            completion = rs.getLong("completionTokens"),
                            cached = rs.getLong("cachedTokens"),
                            textChars = rs.getLong("textChars"),
                        )
                    }
                }
            }

            val user = rows.single { it.type == "user" }
            val asst = rows.single { it.type == "assistant" }
            val blob = rows.single { it.type == "tool_result" }
            assertTrue(user.day != asst.day)
            assertEquals((afternoon + shanghaiOffsetMs) / 86_400_000L, user.day)
            assertEquals((evening + shanghaiOffsetMs) / 86_400_000L, asst.day)
            assertEquals(10, user.textChars)
            assertEquals(120, asst.prompt)
            assertEquals(40, asst.completion)
            assertEquals(8, asst.cached)
            assertEquals(0, blob.prompt)
            assertEquals(0, blob.textChars)
            assertEquals(1, blob.count)
        }
    }
}
