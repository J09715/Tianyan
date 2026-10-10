package top.tianyan.app.harness.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交付闸门四规则测试。
 *
 * 用例名里的中文路径是刻意保留的：失败时能直接看出是哪条规则被破坏。
 */
class DeliveryGateTest {

    private fun verification(command: String, passed: Boolean, at: Long) =
        VerificationRecord(command = command, passed = passed, at = at)

    @Test
    fun `rule1 no modified files is green and deliverable without verification`() {
        val verdict = DeliveryGate.evaluate(EvidenceState(filesRead = setOf("a.kt")))

        assertEquals(DeliveryGate.GateColor.GREEN, verdict.color)
        assertTrue(verdict.canDeliver)
        assertEquals("no_changes", verdict.attribution)
        assertEquals("没有文件改动，无需验证。", verdict.reason)
        assertNull(verdict.nextAction)
    }

    @Test
    fun `rule2 failing verification newer than last mutation is red and blocks delivery`() {
        val verdict = DeliveryGate.evaluate(
            EvidenceState(
                filesModified = setOf("src/Main.kt"),
                lastMutationAt = 1_000L,
                verifications = listOf(verification("./gradlew test", passed = false, at = 2_000L)),
            ),
        )

        assertEquals(DeliveryGate.GateColor.RED, verdict.color)
        assertFalse(verdict.canDeliver)
        assertEquals("owned_failure", verdict.attribution)
        assertTrue("reason 必须点名失败的命令", verdict.reason.contains("./gradlew test"))
        assertEquals("先修复失败的验证，再声称完成。", verdict.nextAction)
    }

    @Test
    fun `rule2 failing verification older than last mutation does not block`() {
        // 失败发生在改动之前，已被本次改动覆盖；它不该继续阻塞交付。
        val verdict = DeliveryGate.evaluate(
            EvidenceState(
                filesModified = setOf("src/Main.kt"),
                lastMutationAt = 5_000L,
                verifications = listOf(verification("./gradlew test", passed = false, at = 1_000L)),
            ),
        )

        assertEquals(DeliveryGate.GateColor.YELLOW, verdict.color)
        assertEquals("unverified", verdict.attribution)
    }

    @Test
    fun `rule3 passing verification newer than last mutation is green`() {
        val verdict = DeliveryGate.evaluate(
            EvidenceState(
                filesModified = setOf("src/Main.kt"),
                lastMutationAt = 1_000L,
                verifications = listOf(verification("./gradlew test", passed = true, at = 1_500L)),
            ),
        )

        assertEquals(DeliveryGate.GateColor.GREEN, verdict.color)
        assertTrue(verdict.canDeliver)
        assertEquals("verified", verdict.attribution)
        assertTrue(verdict.reason.contains("./gradlew test"))
    }

    @Test
    fun `rule3 stale green older than last mutation does not verify the new change`() {
        // 核心用例：陈旧绿。改动之前的通过结果只证明旧代码，必须落到 YELLOW。
        val verdict = DeliveryGate.evaluate(
            EvidenceState(
                filesModified = setOf("src/Main.kt"),
                lastMutationAt = 9_000L,
                verifications = listOf(verification("./gradlew test", passed = true, at = 1_000L)),
            ),
        )

        assertEquals(DeliveryGate.GateColor.YELLOW, verdict.color)
        assertTrue("YELLOW 必须可交付", verdict.canDeliver)
        assertEquals("unverified", verdict.attribution)
    }

    @Test
    fun `rule4 modified files with no verification at all is yellow but deliverable`() {
        val verdict = DeliveryGate.evaluate(
            EvidenceState(filesModified = setOf("src/Main.kt"), lastMutationAt = 7_000L),
        )

        assertEquals(DeliveryGate.GateColor.YELLOW, verdict.color)
        assertTrue("把「没验证」做成硬阻塞会逼模型编造验证", verdict.canDeliver)
        assertEquals("unverified", verdict.attribution)
        assertNotNull(verdict.nextAction)
        assertEquals("运行相关验证（测试/构建/类型检查）后再声称完成。", verdict.nextAction)
    }

    @Test
    fun `rule4 reason states changes are unverified and forbids claiming otherwise`() {
        val verdict = DeliveryGate.evaluate(
            EvidenceState(filesModified = setOf("src/Main.kt"), lastMutationAt = 7_000L),
        )

        assertTrue("必须如实说明未验证", verdict.reason.contains("未验证"))
        assertTrue("必须禁止声称已验证", verdict.reason.contains("不得声称已验证"))
    }

    @Test
    fun `newest fresh verification wins over an older fresh pass`() {
        // 同一改动后先成功后失败：以最近一次为准，必须红灯。
        val verdict = DeliveryGate.evaluate(
            EvidenceState(
                filesModified = setOf("src/Main.kt"),
                lastMutationAt = 1_000L,
                verifications = listOf(
                    verification("./gradlew test", passed = true, at = 2_000L),
                    verification("./gradlew test", passed = false, at = 3_000L),
                ),
            ),
        )

        assertEquals(DeliveryGate.GateColor.RED, verdict.color)
        assertFalse(verdict.canDeliver)
    }

    @Test
    fun `passing verification exactly at last mutation still counts`() {
        // 时间戳边界：验证与改动同刻（同一毫秒内完成）不算陈旧，避免高频编辑下的持续误报。
        val verdict = DeliveryGate.evaluate(
            EvidenceState(
                filesModified = setOf("src/Main.kt"),
                lastMutationAt = 4_000L,
                verifications = listOf(verification("./gradlew test", passed = true, at = 4_000L)),
            ),
        )

        assertEquals(DeliveryGate.GateColor.GREEN, verdict.color)
        assertEquals("verified", verdict.attribution)
    }
}
