package ai.droidlm.overlay

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
