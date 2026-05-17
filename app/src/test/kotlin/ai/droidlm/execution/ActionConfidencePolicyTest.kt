package ai.droidlm.execution

import ai.droidlm.agent.ToolRisk
import ai.droidlm.intent.ActionConfidence
import ai.droidlm.intent.DroidLmAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionConfidencePolicyTest {
    @Test fun lowConfidenceMutatingActionIsBlocked() {
        val result = ActionConfidencePolicy.evaluate(
            confidence = ActionConfidence.LOW,
            action = DroidLmAction.TapNode("save", "tap save")
        )

        assertFalse(result.allowed)
    }

    @Test fun lowConfidenceObservationActionIsAllowed() {
        val result = ActionConfidencePolicy.evaluate(
            confidence = ActionConfidence.LOW,
            action = DroidLmAction.FindTextOnScreen("Save", false, "find save")
        )

        assertTrue(result.allowed)
    }

    @Test fun mediumConfidenceReversibleLowRiskActionIsAllowed() {
        val result = ActionConfidencePolicy.evaluate(
            confidence = ActionConfidence.MEDIUM,
            action = DroidLmAction.PressBack
        )

        assertTrue(result.allowed)
    }

    @Test fun highRiskActionRequiresConfirmationAtAnyConfidence() {
        val result = ActionConfidencePolicy.evaluate(
            confidence = ActionConfidence.HIGH,
            action = DroidLmAction.OpenAppStoreListing("Sheets", "com.google.android.apps.docs.editors.sheets", "install sheets"),
            risk = ToolRisk.INSTALL_OR_STORE,
            mutating = true
        )

        assertTrue(result.allowed)
        assertTrue(result.requiresConfirmation)
    }
}
