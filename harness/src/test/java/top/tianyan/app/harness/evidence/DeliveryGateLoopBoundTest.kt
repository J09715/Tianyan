package top.tianyan.app.harness.evidence

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.ChatResult
import top.tianyan.app.harness.ProviderResponseNormalizer
import top.tianyan.app.harness.TurnOutcome
import top.tianyan.app.harness.TurnProviderOutcome
import top.tianyan.app.harness.TurnRunner

/**
 * 闸门与回合循环的边界行为：闸门最多把交付延长一轮，之后必须放行。
 *
 * 这是「闸门不会变成死循环」的可执行证据——单测 [GateObligationTrackerTest] 只证明
 * 账本本身的语义，这里证明账本被真正接进了收尾判定。
 */
class DeliveryGateLoopBoundTest {

    private val runner = TurnRunner(ProviderResponseNormalizer(Json { ignoreUnknownKeys = true }))

    private fun assistantTurn(text: String) = TurnProviderOutcome.Success(
        ChatResult(content = text, toolCalls = emptyList()),
        text,
    )

    /** 模拟 HarnessLoop 的收尾判定：红灯时按账本决定继续还是放行。 */
    private suspend fun runOneRound(
        sessionId: String,
        state: EvidenceState,
        text: String,
        tracker: GateObligationTracker,
    ): TurnOutcome {
        val verdict = DeliveryGate.evaluate(state)
        return runner.run(
            toolsEnabled = true,
            callProvider = { assistantTurn(text) },
            persistAssistant = {},
            consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> true },
            gateBeforeComplete = {
                if (!verdict.canDeliver && tracker.shouldContinueOnce(sessionId, "delivery-gate")) {
                    "${verdict.reason}\n下一步：${verdict.nextAction}"
                } else {
                    null
                }
            },
        )
    }

    @Test
    fun `red gate forces one continuation then lets the turn complete`() = runBlocking {
        val tracker = GateObligationTracker()
        val failing = EvidenceState(
            filesModified = setOf("src/Main.kt"),
            lastMutationAt = 1_000L,
            verifications = listOf(VerificationRecord("./gradlew test", false, 2_000L)),
        )

        val first = runOneRound("s1", failing, "已全部完成。", tracker)
        val second = runOneRound("s1", failing, "已全部完成。", tracker)
        val third = runOneRound("s1", failing, "已全部完成。", tracker)

        assertTrue("第一次收尾必须被闸门否决", first is TurnOutcome.Continue)
        assertEquals(
            "下一次收尾必须放行，否则闸门就是死循环发生器",
            TurnOutcome.Complete,
            second,
        )
        assertEquals(TurnOutcome.Complete, third)
        Unit
    }

    @Test
    fun `gate hint carries the reason and the next action`() = runBlocking {
        val tracker = GateObligationTracker()
        val failing = EvidenceState(
            filesModified = setOf("src/Main.kt"),
            lastMutationAt = 1_000L,
            verifications = listOf(VerificationRecord("./gradlew test", false, 2_000L)),
        )

        val outcome = runOneRound("s1", failing, "已全部完成。", tracker) as TurnOutcome.Continue

        assertTrue(outcome.gateHint.orEmpty().contains("./gradlew test"))
        assertTrue(outcome.gateHint.orEmpty().contains("先修复失败的验证"))
        Unit
    }

    @Test
    fun `reset re-arms exactly one continuation per new user turn`() = runBlocking {
        val tracker = GateObligationTracker()
        val failing = EvidenceState(
            filesModified = setOf("src/Main.kt"),
            lastMutationAt = 1_000L,
            verifications = listOf(VerificationRecord("./gradlew test", false, 2_000L)),
        )

        assertTrue(runOneRound("s1", failing, "完成。", tracker) is TurnOutcome.Continue)
        assertEquals(TurnOutcome.Complete, runOneRound("s1", failing, "完成。", tracker))

        // 新用户回合：闸门重新获得一次机会，但仍然只有一次。
        tracker.reset("s1")
        assertTrue(runOneRound("s1", failing, "完成。", tracker) is TurnOutcome.Continue)
        assertEquals(TurnOutcome.Complete, runOneRound("s1", failing, "完成。", tracker))
        Unit
    }

    @Test
    fun `green gate completes without consuming the obligation`() = runBlocking {
        val tracker = GateObligationTracker()
        val verified = EvidenceState(
            filesModified = setOf("src/Main.kt"),
            lastMutationAt = 1_000L,
            verifications = listOf(VerificationRecord("./gradlew test", true, 2_000L)),
        )

        assertEquals(TurnOutcome.Complete, runOneRound("s1", verified, "已完成。", tracker))
        // 绿灯没有消耗额度：后续真出红灯时仍能拿到那一次机会。
        assertTrue(tracker.shouldContinueOnce("s1", "claim-audit"))
        Unit
    }
}
