package ai.droidlm.agent

import ai.droidlm.intent.ActionConfidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentJsonParserTest {
    private val parser = AgentJsonParser()

    @Test fun parsesToolCallsEnvelope() {
        val decision = parser.parseDecision(
            """
            {
              "status":"CALL_TOOLS",
              "message":"Open Drive",
              "toolCalls":[{"id":"open","name":"open-app","args":{"packageName":"com.google.android.apps.docs"},"reason":"requested","confidence":"HIGH","expectedResult":"Drive opens"}]
            }
            """.trimIndent()
        )

        assertEquals(AgentDecisionStatus.CALL_TOOLS, decision.status)
        assertEquals("OPEN_APP", decision.toolCalls.single().name)
        assertEquals("com.google.android.apps.docs", decision.toolCalls.single().args.getString("packageName"))
        assertEquals(ActionConfidence.HIGH, decision.toolCalls.single().confidence)
        assertEquals("Drive opens", decision.toolCalls.single().expectedResult)
    }

    @Test fun parsesLegacySingleActionAsToolCall() {
        val decision = parser.parseDecision("{\"action\":\"OPEN_APP\",\"packageName\":\"com.google.android.apps.docs\",\"reason\":\"test\"}")

        assertEquals(AgentDecisionStatus.CALL_TOOLS, decision.status)
        assertEquals("OPEN_APP", decision.toolCalls.single().name)
        assertEquals(ActionConfidence.LOW, decision.toolCalls.single().confidence)
    }
    @Test fun doneHasNoToolCalls() {
        val decision = parser.parseDecision("{\"status\":\"DONE\",\"message\":\"complete\",\"toolCalls\":[{\"name\":\"OPEN_APP\"}]}")

        assertEquals(AgentDecisionStatus.DONE, decision.status)
        assertTrue(decision.toolCalls.isEmpty())
    }

    @Test fun parsesConfidenceFromArgsFallback() {
        val decision = parser.parseDecision(
            """
            {
              "status":"CALL_TOOLS",
              "toolCalls":[{"id":"tap","name":"TAP_NODE","args":{"nodeId":"save","confidence":"MEDIUM","expectedResult":"Save dialog closes"}}]
            }
            """.trimIndent()
        )

        assertEquals(ActionConfidence.MEDIUM, decision.toolCalls.single().confidence)
        assertEquals("Save dialog closes", decision.toolCalls.single().expectedResult)
    }
}
