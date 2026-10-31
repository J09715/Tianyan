package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundBudgetTest {

    @Test
    fun `segment exhaustion can auto-continue until continuation budget is used`() {
        val budget = RoundBudget(roundsPerSegment = 100, maxContinuations = 2)
        assertEquals(300, budget.totalBudget)

        repeat(100) { budget.advance() }
        assertTrue(budget.exhausted)
        assertTrue(budget.canContinue())
        assertEquals(1, budget.beginNextSegment())

        repeat(100) { budget.advance() }
        assertEquals(2, budget.beginNextSegment())
        assertEquals(200, budget.totalRounds)

        repeat(100) { budget.advance() }
        assertTrue(budget.exhausted)
        assertFalse(budget.canContinue())
        assertEquals(300, budget.totalRounds)
    }

    @Test
    fun `zero continuations means the first segment is the whole budget`() {
        val budget = RoundBudget(roundsPerSegment = 50, maxContinuations = 0)
        assertEquals(50, budget.totalBudget)
        assertFalse(budget.canContinue())
    }
}
