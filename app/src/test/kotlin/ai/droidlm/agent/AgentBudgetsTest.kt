package ai.droidlm.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentBudgetsTest {
    @Test fun normalizesToConservativeHardLimits() {
        val budgets = AgentBudgets(
            maxTurns = 99,
            maxToolCallsTotal = 99,
            maxToolCallsPerTurn = 99,
            maxMutatingToolCallsPerTurn = 99,
            maxConsecutiveFailures = 99,
            maxRuntimeMs = 999_999L
        ).normalized()

        assertEquals(16, budgets.maxTurns)
        assertEquals(32, budgets.maxToolCallsTotal)
        assertEquals(5, budgets.maxToolCallsPerTurn)
        assertEquals(4, budgets.maxMutatingToolCallsPerTurn)
        assertEquals(3, budgets.maxConsecutiveFailures)
        assertEquals(120_000L, budgets.maxRuntimeMs)
    }
}
