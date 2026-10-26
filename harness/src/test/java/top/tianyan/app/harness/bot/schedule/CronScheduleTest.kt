package top.tianyan.app.harness.bot.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronScheduleTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun next(expression: String, from: String): LocalDateTime? =
        CronSchedule.parse(expression).nextAfter(epoch(from), zone)
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }

    private fun epoch(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun time(text: String): LocalDateTime = LocalDateTime.parse(text)

    @Test
    fun `strictly later than now so the same minute is skipped`() {
        assertEquals(time("2026-10-20T09:00:00"), next("0 9 * * *", "2026-10-19T09:00:00"))
    }

    @Test
    fun `daily schedule waits for the next day when today has passed`() {
        assertEquals(time("2026-10-20T09:00:00"), next("0 9 * * *", "2026-10-19T10:30:00"))
    }

    @Test
    fun `step field advances to the next aligned minute`() {
        assertEquals(time("2026-10-19T10:15:00"), next("*/15 * * * *", "2026-10-19T10:07:00"))
    }

    @Test
    fun `range with step only matches values inside the range`() {
        assertEquals(time("2026-10-19T10:15:00"), next("10-20/5 * * * *", "2026-10-19T10:11:00"))
        assertEquals(time("2026-10-19T11:10:00"), next("10-20/5 * * * *", "2026-10-19T10:21:00"))
    }

    @Test
    fun `weekday seven is normalized to sunday`() {
        // 2026-10-19 是周一，下一次周日在 2026-10-25。
        assertEquals(time("2026-10-25T08:00:00"), next("0 8 * * 7", "2026-10-19T08:00:00"))
    }

    @Test
    fun `day of month and weekday combine with or semantics`() {
        // 2026-10-31 是周六：11-01（日字段）先于下一个周一 11-02 命中。
        assertEquals(time("2026-11-01T09:00:00"), next("0 9 1 * 1", "2026-10-31T10:00:00"))
    }

    @Test
    fun `month restriction rolls over into next year`() {
        assertEquals(time("2027-01-01T00:00:00"), next("0 0 1 1 *", "2026-10-19T00:00:00"))
    }

    @Test
    fun `question mark is treated as any value`() {
        assertEquals(time("2026-10-20T09:00:00"), next("0 9 ? * ?", "2026-10-19T12:00:00"))
    }

    @Test
    fun `impossible date yields no match instead of looping forever`() {
        assertNull(next("0 0 30 2 *", "2026-10-19T00:00:00"))
    }

    @Test
    fun `matches ignores seconds and validates each field`() {
        val schedule = CronSchedule.parse("30 9 * * 1-5")
        assertTrue(schedule.matches(LocalDateTime.parse("2026-10-19T09:30:59")))
        assertTrue(!schedule.matches(LocalDateTime.parse("2026-10-19T09:31:00")))
        assertTrue(!schedule.matches(LocalDateTime.parse("2026-10-25T09:30:00")))
    }

    @Test
    fun `dst overlap returns the later occurrence instead of a past instant`() {
        // Europe/Berlin 2026-10-25 回拨：本地 02:45 出现两次（00:45Z CEST 与 01:45Z CET）。
        // now 落在第二次的 02:30 CET(01:30Z)：必须返回 01:45Z，而不是已过去的 00:45Z。
        val berlin = ZoneId.of("Europe/Berlin")
        val schedule = CronSchedule.parse("45 2 * * *")
        val now = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()

        val next = schedule.nextAfter(now, berlin)

        assertNotNull("回拨重叠必须仍有未来触发点", next)
        val fire = requireNotNull(next)
        assertTrue("nextAfter 必须严格晚于 now，实际 delta=${fire - now}", fire > now)
        assertEquals(Instant.parse("2026-10-25T01:45:00Z").toEpochMilli(), fire)
    }

    @Test
    fun `nextAfter never returns a past instant while scanning a dst overlap`() {
        // 回拨窗口内逐分钟扫描，任何起点都不得产出 <= now 的结果。
        val berlin = ZoneId.of("Europe/Berlin")
        val schedule = CronSchedule.parse("*/15 * * * *")
        val start = Instant.parse("2026-10-25T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-10-25T02:00:00Z").toEpochMilli()

        var now = start
        while (now <= end) {
            val next = schedule.nextAfter(now, berlin)
            assertTrue("now=${Instant.ofEpochMilli(now)} 返回了非未来时刻 $next", next != null && next > now)
            now += 60_000L
        }
    }

    @Test
    fun `validate accepts standard expressions and rejects malformed ones`() {
        assertNull(CronSchedule.validate("0 9 * * *"))
        assertNull(CronSchedule.validate("*/5 1-6/2 1,15 * 1-5"))
        assertNotNull(CronSchedule.validate("0 9 * *"))
        assertNotNull(CronSchedule.validate("60 * * * *"))
        assertNotNull(CronSchedule.validate("*/0 * * * *"))
        assertNotNull(CronSchedule.validate("5-1 * * * *"))
        assertNotNull(CronSchedule.validate("* * 32 * *"))
        assertNotNull(CronSchedule.validate("* * * 13 *"))
        assertNotNull(CronSchedule.validate("* * * * 8"))
    }
}
