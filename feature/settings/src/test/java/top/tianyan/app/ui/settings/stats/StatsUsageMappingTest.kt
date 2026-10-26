package top.tianyan.app.ui.settings.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.core.model.StatsTokenBucket
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class StatsUsageMappingTest {

    @Test
    fun `assistant aggregate rows fill input output and cached like the range summary`() {
        val tokens = tokenContributionFromAggregate(
            customType = "assistant",
            promptTokens = 120,
            completionTokens = 40,
            cachedTokens = 8,
            textChars = 10,
            reasoningChars = 0,
        )
        val bucket = StatsTokenBucket().add(
            input = tokens.input,
            output = tokens.output,
            cached = tokens.cached,
            activity = 2,
        )
        assertEquals(160L, bucket.totalTokens)
        assertEquals(8L, bucket.cachedTokens)
        assertEquals(2, bucket.activityCount)
    }

    @Test
    fun `user aggregate rows estimate input tokens from character counts`() {
        val tokens = tokenContributionFromAggregate(
            customType = "user",
            promptTokens = 0,
            completionTokens = 0,
            cachedTokens = 0,
            textChars = 20,
            reasoningChars = 0,
        )
        assertEquals(estimateTokensFromChars(20), tokens.input)
        assertEquals(0L, tokens.output)
        assertEquals(15L, tokens.input)
    }

    @Test
    fun `activity-only buckets collapse chart height because totalTokens ignores activity`() {
        val broken = StatsTokenBucket().add(activity = 12)
        assertEquals(0L, broken.totalTokens)
        assertEquals(12L, broken.chartWeight)
    }

    @Test
    fun `local epoch day with shanghai offset matches ZoneOffset conversion`() {
        val offsetMs = 8L * 60 * 60 * 1000
        val utcEvening = Instant.parse("2026-09-17T16:00:00Z")
        val localDay = LocalDate.ofEpochDay((utcEvening.toEpochMilli() + offsetMs) / 86_400_000L)
        assertEquals(
            utcEvening.atZone(ZoneOffset.ofHours(8)).toLocalDate(),
            localDay,
        )
        assertEquals(LocalDate.of(2026, 9, 18), localDay)
        assertEquals(
            LocalDate.of(2026, 9, 17),
            Instant.parse("2026-09-17T16:00:00Z").atZone(ZoneOffset.UTC).toLocalDate(),
        )
    }
}
