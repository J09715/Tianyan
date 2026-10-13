package top.tianyan.app.harness.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 完成声称审计：防止模型在正文里凭记忆宣布「所有测试通过」。 */
class ClaimAuditorTest {

    private val mutated = EvidenceState(
        filesModified = setOf("src/Main.kt"),
        lastMutationAt = 1_000L,
    )

    private fun verification(command: String, passed: Boolean = true, at: Long = 2_000L) =
        VerificationRecord(command = command, passed = passed, at = at, summary = "exit 0")

    @Test
    fun `no claim at all is not blocked`() {
        val result = ClaimAuditor.audit("已完成重构，请查看改动。", mutated)

        assertFalse(result.blocked)
        assertFalse(result.warning)
        assertEquals("", result.message)
    }

    @Test
    fun `claim without any modified file is not blocked`() {
        // 纯报告/文档交付：没有改动就没有「陈旧的绿」，拦截只会制造误报。
        val state = EvidenceState(filesModified = emptySet(), lastMutationAt = 0L)
        val result = ClaimAuditor.audit("所有测试通过，全绿。", state)

        assertFalse("无改动时审计必须放行", result.blocked)
        assertFalse(result.warning)
    }

    @Test
    fun `test claim with writes but no fresh verification is blocked`() {
        val result = ClaimAuditor.audit("已修复，所有测试通过。", mutated)

        assertTrue(result.blocked)
        assertFalse(result.warning)
        assertTrue(result.message.contains("没有任何晚于最后一次代码改动的验证记录"))
    }

    @Test
    fun `test claim with writes and fresh test verification is not blocked`() {
        val state = mutated.copy(verifications = listOf(verification("./gradlew test")))
        val result = ClaimAuditor.audit("所有测试通过。", state)

        assertFalse(result.blocked)
        assertFalse(result.warning)
    }

    @Test
    fun `test claim backed only by a fresh typecheck verification is blocked`() {
        // 形态错配：类型检查干净不能替测试背书。
        val state = mutated.copy(verifications = listOf(verification("tsc --noEmit")))
        val result = ClaimAuditor.audit("所有测试通过。", state)

        assertTrue(result.blocked)
        assertTrue(result.message.contains("不是同一类验证"))
    }

    @Test
    fun `typecheck claim backed only by a fresh test verification is blocked`() {
        // 反向形态错配。
        val state = mutated.copy(verifications = listOf(verification("npm run pytest")))
        val result = ClaimAuditor.audit("类型检查干净。", state)

        assertTrue(result.blocked)
        assertTrue(result.message.contains("不是同一类验证"))
    }

    @Test
    fun `stale passing verification does not back a test claim`() {
        val state = mutated.copy(
            lastMutationAt = 9_000L,
            verifications = listOf(verification("./gradlew test", at = 1_000L)),
        )
        val result = ClaimAuditor.audit("所有测试通过。", state)

        assertTrue(result.blocked)
        assertTrue(result.message.contains("陈旧的绿"))
    }

    @Test
    fun `unrecognizable verification command is accepted as backing evidence`() {
        // 命令完全无法归类时不拦：宁可漏判也不能因为分类器不认识命令就阻断交付。
        val state = mutated.copy(verifications = listOf(verification("./scripts/check_all.sh")))
        val result = ClaimAuditor.audit("所有测试通过。", state)

        assertFalse(result.blocked)
    }

    @Test
    fun `count mismatch produces a warning and does not block`() {
        val state = mutated.copy(
            verifications = listOf(
                VerificationRecord(command = "./gradlew test", passed = true, at = 2_000L, summary = "12/12 tests passed"),
            ),
        )
        val result = ClaimAuditor.audit("已修复，10/10 通过。", state)

        assertFalse(result.blocked)
        assertTrue(result.warning)
        assertTrue(result.message.contains("10/10"))
        assertTrue(result.message.contains("12/12"))
    }

    @Test
    fun `count match produces no warning`() {
        val state = mutated.copy(
            verifications = listOf(
                VerificationRecord(command = "./gradlew test", passed = true, at = 2_000L, summary = "12/12 tests passed"),
            ),
        )
        val result = ClaimAuditor.audit("已修复，12/12 通过。", state)

        assertFalse(result.blocked)
        assertFalse(result.warning)
    }

    @Test
    fun `english test claim is detected`() {
        val result = ClaimAuditor.audit("All tests passed and the build is green.", mutated)

        assertTrue(result.blocked)
    }

    @Test
    fun `failing fresh verification is reported as the reason`() {
        val state = mutated.copy(
            verifications = listOf(verification("./gradlew test", passed = false)),
        )
        val result = ClaimAuditor.audit("所有测试通过。", state)

        assertTrue(result.blocked)
        assertTrue(result.message.contains("失败的"))
    }

    @Test
    fun `verificationKind classifies common commands`() {
        assertEquals("test", ClaimAuditor.verificationKind("./gradlew :app:testDebugUnitTest"))
        assertEquals("test", ClaimAuditor.verificationKind("pytest -q"))
        assertEquals("test", ClaimAuditor.verificationKind("go test ./..."))
        assertEquals("test", ClaimAuditor.verificationKind("cargo test"))
        assertEquals("test", ClaimAuditor.verificationKind("npx jest"))
        assertEquals("typecheck", ClaimAuditor.verificationKind("tsc --noEmit"))
        assertEquals("typecheck", ClaimAuditor.verificationKind("mypy src"))
        assertEquals("typecheck", ClaimAuditor.verificationKind("运行类型检查"))
        assertEquals("unknown", ClaimAuditor.verificationKind("./scripts/lint_and_report.sh"))
    }
}
