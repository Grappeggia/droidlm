package ai.droidlm.overlay

import ai.droidlm.intent.DroidLmAction
import ai.droidlm.relay.PlanPreview
import ai.droidlm.relay.PlanPreviewStep

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayStatusFormatterTest {
    @Test fun idlePromptsUserToSpeak() {
        assertEquals("Tap circle to speak", OverlayStatusFormatter.label(false, "", "", "Idle", ""))
        assertEquals("●", OverlayStatusFormatter.recordButton(false, "Idle"))
    }

    @Test fun listeningHidesPartialTranscript() {
        val label = OverlayStatusFormatter.label(true, "open drive", "", "Idle", "")
        assertEquals("Listening...", label)
        assertEquals("■", OverlayStatusFormatter.recordButton(true, "Idle"))
    }

    @Test fun executingShowsCancelButton() {
        assertEquals("×", OverlayStatusFormatter.recordButton(false, "Executing OPEN_APP"))
    }


    @Test fun errorResultWinsOverFinalTranscript() {
        val label = OverlayStatusFormatter.label(
            isListening = false,
            partialTranscript = "",
            finalTranscript = "open drive",
            executionStatus = "Error",
            lastResult = "OpenAI API key is required for GPT planning"
        )
        assertEquals("OpenAI key needed", label)
    }

    @Test fun compactPlanUsesSingleLineActionChain() {
        val plan = PlanPreview(
            model = "gpt-4.1-mini",
            summary = "Open Sheets and type a value",
            riskLevel = "LOW",
            requiresConfirmation = false,
            steps = listOf(
                PlanPreviewStep(1, DroidLmAction.OpenApp("Google Sheets", "com.google.android.apps.docs.editors.sheets", "open app"), "Open Google Sheets", "open app", false),
                PlanPreviewStep(2, DroidLmAction.Tap(10, 20, "tap plus"), "Tap plus", "tap plus", false),
                PlanPreviewStep(3, DroidLmAction.TypeText("hello", false, "type text"), "Type hello", "type text", false)
            )
        )

        assertEquals("P:O:GSheets>T:plus>Ty:hello", OverlayStatusFormatter.compactPlan(plan))
    }

    @Test fun compactPlanShowsRiskAndRemainingSteps() {
        val plan = PlanPreview(
            model = "gpt-4.1-mini",
            summary = "Risky task",
            riskLevel = "HIGH",
            requiresConfirmation = true,
            steps = (1..6).map {
                PlanPreviewStep(it, DroidLmAction.NoOp("step"), "Step $it", "reason", it == 6)
            }
        )

        assertEquals("P[HIGH]:Step1>Step2>Step3>Step4+2", OverlayStatusFormatter.compactPlan(plan))
    }


    @Test fun overlayYStaysAboveBottomGestureArea() {
        val safeY = FloatingOverlayBounds.safeY(
            requestedY = 920,
            displayHeight = 1000,
            viewHeight = 72,
            bottomInset = 96,
            density = 1f
        )
        assertEquals(824, safeY)
    }

    @Test fun overlayYUsesMinimumGestureGuardWhenInsetIsMissing() {
        val safeY = FloatingOverlayBounds.safeY(
            requestedY = 920,
            displayHeight = 1000,
            viewHeight = 72,
            bottomInset = 0,
            density = 1f
        )
        assertEquals(872, safeY)
    }
}
