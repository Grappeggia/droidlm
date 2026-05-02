package ai.droidlm.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStatusFormatterTest {
    @Test fun idlePromptsUserToSpeak() {
        assertEquals("Tap circle to speak", OverlayStatusFormatter.label(false, "", "", "Idle", ""))
        assertEquals("●", OverlayStatusFormatter.recordButton(false, "Idle"))
    }

    @Test fun listeningShowsPartialTranscript() {
        val label = OverlayStatusFormatter.label(true, "open drive", "", "Idle", "")
        assertTrue(label.contains("open drive"))
        assertEquals("■", OverlayStatusFormatter.recordButton(true, "Idle"))
    }

    @Test fun executingShowsCancelButton() {
        assertEquals("×", OverlayStatusFormatter.recordButton(false, "Executing OPEN_APP"))
    }
}
