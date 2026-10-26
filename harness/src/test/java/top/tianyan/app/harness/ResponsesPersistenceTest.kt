package top.tianyan.app.harness

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.RoomHarnessRuntimeRepository
import top.tianyan.app.harness.session.ApiMessageProjector
import top.tianyan.app.harness.session.SessionTreeStore
import top.tianyan.app.harness.subagent.isolatedProviderMessages

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResponsesPersistenceTest {
    @Test fun `database reopen retains tool only native anchor in both main and child histories`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "responses-replay-${System.nanoTime()}.db"
        val databasePath = context.getDatabasePath(databaseName).also { it.parentFile?.mkdirs() }
        val json = Json { ignoreUnknownKeys = true }
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        val model = ModelConfig(name = "test", provider = "OpenAI", model = "gpt-test",
            baseUrl = "https://example.com/v1", apiKey = "key", responseApiEnabled = true)
        val output = json.parseToJsonElement("""[
            {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"persisted-opaque"},
            {"type":"function_call","id":"fc_1","call_id":"provider_1","name":"read","arguments":"{}"}
        ]""").jsonArray
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, databasePath.absolutePath)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            val writer = SessionTreeStore(RoomHarnessRuntimeRepository(database.harnessRuntimeDao()), json, logger)
            val messages = listOf(
                UserMessage("u", 1, "task"),
                AssistantText("a", 2, "", responsesTurn = ResponsesTurn.capture(model, output)),
                ToolCall("local_1", 3, HarnessTool.READ, JsonObject(emptyMap())),
                ToolResult("r", 4, "local_1", true, "done"),
            )
            messages.forEach { writer.append("s", it) }
            database.close()
            database = open()
            val reader = SessionTreeStore(RoomHarnessRuntimeRepository(database.harnessRuntimeDao()), json, logger)
            val restored = reader.load("s")
            assertEquals(messages, restored)
            val main = ApiMessageProjector.project(restored, ToolCallMode.NATIVE, false)
            val child = isolatedProviderMessages(restored, "system", false)
            for (projected in listOf(main, child)) {
                val buffer = Buffer()
                buildResponsesRequest(model, projected, true).body!!.writeTo(buffer)
                val input = json.parseToJsonElement(buffer.readUtf8()).jsonObject["input"]!!.jsonArray
                assertEquals(output[0], input[1])
                assertEquals(output[1], input[2])
                assertEquals("provider_1", input[3].jsonObject["call_id"]!!.jsonPrimitive.content)
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
