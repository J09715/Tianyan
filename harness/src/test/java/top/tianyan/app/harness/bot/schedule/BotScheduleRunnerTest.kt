package top.tianyan.app.harness.bot.schedule

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.BotEntity
import top.tianyan.app.core.database.BotScheduleEntity
import top.tianyan.app.core.database.repository.RoomBotScheduleRepository
import top.tianyan.app.core.database.repository.RoomPersonaBotRepository
import top.tianyan.app.core.model.bot.Bot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotScheduleRunnerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val schedules = RoomBotScheduleRepository(db.botScheduleDao(), db.botScheduleDeliveryDao())
    private val bots = RoomPersonaBotRepository(db.personaBotDao())
    private val log = AppLogger(context) { it }

    @After fun close() = db.close()

    private suspend fun seed() {
        db.personaBotDao().upsert(BotEntity("bot", "name", "role", "persona", "seed", null, null, "dm", true, "#123456", 1, 1))
        db.botScheduleDao().upsert(BotScheduleEntity("schedule", "bot", null, "* * * * *", "work", true, 100, null, 1))
    }

    private fun runner(submit: suspend (Bot, String, String?) -> Boolean) = BotScheduleRunner(schedules, bots, log, BotScheduledSubmission(submit))

    private fun status(): String = db.openHelper.readableDatabase.query("SELECT status FROM bot_schedule_deliveries").use {
        assertTrue(it.moveToFirst()); it.getString(0)
    }

    @Test fun `rejection is not counted as accepted`() = runBlocking {
        seed()
        assertEquals(0, runner { _, _, _ -> false }.runDue(101))
        assertEquals("rejected", status())
    }

    @Test fun `accepted occurrence is not submitted a second time`() = runBlocking {
        seed()
        var submissions = 0
        val runner = runner { _, _, _ -> submissions++; true }
        assertEquals(1, runner.runDue(101))
        assertEquals(0, runner.runDue(102))
        assertEquals(1, submissions)
        assertEquals("admitted", status())
    }

    @Test fun `pending occurrence survives restart after the schedule advanced`() = runBlocking {
        seed()
        db.botScheduleDeliveryDao().stage("schedule", 100, 101, 60_000)
        var submissions = 0
        assertEquals(1, runner { _, text, _ -> assertEquals("work", text); submissions++; true }.runDue(102))
        assertEquals(1, submissions)
    }

    @Test fun `interrupted admission is recorded and never blindly replayed`() = runBlocking {
        seed()
        try { runner { _, _, _ -> throw CancellationException("worker stopped") }.runDue(101); fail() }
        catch (_: CancellationException) { }
        assertEquals("unknown", status())
        assertEquals(0, runner { _, _, _ -> fail("must not replay"); true }.runDue(102))
    }

    @Test fun `exception after possible acceptance remains unknown`() = runBlocking {
        seed()
        assertEquals(0, runner { _, _, _ -> error("lost receipt") }.runDue(101))
        assertEquals("unknown", status())
    }

    @Test fun `null nextAfter delivers once then parks at max and disables`() = runBlocking {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val dueAt = LocalDateTime.parse("2028-02-29T00:00:00").atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
            assertNull(CronSchedule.parse("0 0 29 2 *").nextAfter(dueAt))
            db.personaBotDao().upsert(BotEntity("bot", "name", "role", "persona", "seed", null, null, "dm", true, "#123456", 1, 1))
            db.botScheduleDao().upsert(BotScheduleEntity("leap", "bot", null, "0 0 29 2 *", "leap-day", true, dueAt, null, 1))
            ShadowLog.clear()
            var submissions = 0
            val runner = runner { _, text, _ -> assertEquals("leap-day", text); submissions++; true }
            assertEquals(1, runner.runDue(dueAt))
            assertEquals(1, submissions)
            val row = db.botScheduleDao().findById("leap")!!
            assertEquals(Long.MAX_VALUE, row.nextRunAt)
            assertFalse(row.enabled)
            assertTrue(schedules.listDue(dueAt).isEmpty())
            assertEquals("admitted", status())
            assertTrue(ShadowLog.getLogsForTag("Tianyan").any { it.msg.contains("leap") && it.msg.contains("0 0 29 2 *") })
            assertEquals(0, runner.runDue(dueAt + 60_000))
            assertEquals(1, submissions)
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
