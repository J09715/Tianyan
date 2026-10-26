package top.tianyan.app.harness.session

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import org.junit.Before
import androidx.test.core.app.ApplicationProvider
import top.tianyan.app.core.database.HarnessEntryEntity
import top.tianyan.app.core.database.HarnessLaneEntity
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.UserMessage

/** 相邻迁移链 + 腐坏台账语义（DSH fail-closed 借鉴项）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionEntryPayloadTest {

    // ---- SessionPayloadMigrations ----

    @Test
    fun `adjacent steps apply in order`() {
        val migrations = SessionPayloadMigrations(
            listOf(
                PayloadMigrationStep(1, 2) { it.trim() },
                PayloadMigrationStep(2, 3) { "$it#v3" },
            ),
        )
        assertEquals("a#v3", migrations.upgrade(" a ", fromVersion = 1, toVersion = 3))
        assertEquals("a#v3", migrations.upgrade(" a ", fromVersion = 1, toVersion = 3))
    }

    @Test
    fun `gap in chain fails instead of guessing`() {
        val migrations = SessionPayloadMigrations(listOf(PayloadMigrationStep(2, 3) { it }))
        assertThrows(NoSuchElementException::class.java) {
            migrations.upgrade("{}", fromVersion = 1, toVersion = 3)
        }
    }

    @Test
    fun `non adjacent step is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            PayloadMigrationStep(1, 3) { it }
        }
    }

    @Test
    fun `same version is a no-op`() {
        val migrations = SessionPayloadMigrations(emptyList())
        assertEquals("{}", migrations.upgrade("{}", fromVersion = 1, toVersion = 1))
    }

    // ---- SessionCorruptionLedger ----

    @Test
    fun `ledger records per session and caps history`() {
        val ledger = SessionCorruptionLedger(maxRecordsPerSession = 2)
        val session = "s1"
        ledger.record(entry("e1", session), "first")
        ledger.record(entry("e2", session), "second")
        ledger.record(entry("e3", session), "third")
        val records = ledger.snapshotFor(session)
        assertEquals(listOf("e2", "e3"), records.map { it.entryId })
        assertEquals(listOf("second", "third"), records.map { it.cause })
        assertTrue(ledger.snapshotFor("other").isEmpty())
        ledger.clearSession(session)
        assertTrue(ledger.snapshotFor(session).isEmpty())
    }

    // ---- SessionTreeStore 解码策略 ----

    private lateinit var logger: AppLogger

    @Before fun setUp() {
        logger = AppLogger(ApplicationProvider.getApplicationContext(), SensitiveDataRedactor { it })
    }

    @Test
    fun `corrupt entries are skipped from projection but accounted in ledger`() = runBlocking {
        val good = entry("good", "s", payload = Json.encodeToString(HarnessMessage.serializer(), UserMessage("good", 1, "hi")))
        val bad = entry("bad", "s", payload = "{not-json")
        val store = store(listOf(good, bad))
        val messages = store.load("s")
        assertEquals(1, messages.size)
        val report = store.corruptionReport("s")
        assertEquals(1, report.size)
        assertEquals("bad", report.single().entryId)
        assertTrue(report.single().cause.contains("解码失败"))
    }

    @Test
    fun `future payload version is treated as corrupt not blindly decoded`() = runBlocking {
        val tooNew = entry("future", "s", payload = "{}", version = HarnessEntryPayloadVersion.CURRENT + 1)
        val store = store(listOf(tooNew))
        assertTrue(store.load("s").isEmpty())
        val cause = store.corruptionReport("s").single().cause
        assertTrue(cause.contains("高于当前支持"))
    }

    @Test
    fun `strict load still throws on corrupt entry`() = runBlocking {
        val bad = entry("bad", "s", payload = "{not-json")
        val store = store(listOf(bad))
        try {
            store.loadStrict("s")
            fail("loadStrict must not silently drop unreadable message")
        } catch (_: SerializationException) {
        }
    }

    @Test
    fun `strict load rejects future version with explicit message`() = runBlocking {
        val tooNew = entry("future", "s", payload = "{}", version = HarnessEntryPayloadVersion.CURRENT + 1)
        val store = store(listOf(tooNew))
        try {
            store.loadStrict("s")
            fail("loadStrict must refuse unknown payload version")
        } catch (rejected: IllegalArgumentException) {
            assertTrue(rejected.message.orEmpty().contains("高于当前支持"))
        }
    }

    @Test
    fun `append stamps current payload version`() = runBlocking {
        val appended = mutableListOf<HarnessEntryEntity>()
        val store = store(emptyList(), capture = { appended.add(it) })
        store.append("s", UserMessage("m1", 1, "hello"))
        assertEquals(HarnessEntryPayloadVersion.CURRENT, appended.single().payloadVersion)
    }

    // ---- helpers ----

    private fun entry(id: String, sessionId: String, payload: String = "{}", version: Int = 1) = HarnessEntryEntity(
        id = id, sessionId = sessionId, parentId = null, createdAt = 1,
        entryType = "message", customType = "user", payloadJson = payload, payloadVersion = version,
    )

    private fun store(
        entries: List<HarnessEntryEntity>,
        capture: (HarnessEntryEntity) -> Unit = {},
    ): SessionTreeStore {
        val lane = HarnessLaneEntity(sessionId = "s", name = SessionTreeStore.MAIN_LANE, leafId = null, updatedAt = 1)
        val repository = Proxy.newProxyInstance(
            HarnessRuntimeRepository::class.java.classLoader,
            arrayOf(HarnessRuntimeRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "ensureLane", "findLane" -> lane
                "branch", "branchTail" -> entries
                "appendToLane" -> { @Suppress("UNCHECKED_CAST") capture(args[2] as HarnessEntryEntity); Unit }
                else -> null
            }
        } as HarnessRuntimeRepository
        return SessionTreeStore(repository, Json, logger)
    }
}
