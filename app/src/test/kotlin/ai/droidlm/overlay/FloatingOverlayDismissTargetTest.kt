package ai.droidlm.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingOverlayDismissTargetTest {
    @Test fun targetRectStaysAboveBottomInset() {
        val rect = FloatingOverlayDismissTarget.targetRect(
            displayWidth = 1080,
            displayHeight = 2400,
            bottomInset = 120,
            density = 1f
        )

        assertTrue(rect.bottom < 2400 - 120)
    }

    @Test fun dismissZoneIncludesTargetCenter() {
        val rect = FloatingOverlayDismissTarget.targetRect(
            displayWidth = 1080,
            displayHeight = 2400,
            bottomInset = 120,
            density = 1f
        )

        assertTrue(
            FloatingOverlayDismissTarget.isWithinDismissZone(
                centerX = rect.left + ((rect.right - rect.left) / 2),
                centerY = rect.top + ((rect.bottom - rect.top) / 2),
                targetRect = rect,
                density = 1f
            )
        )
    }

    @Test fun dismissZoneRejectsDistantPoints() {
        val rect = FloatingOverlayDismissTarget.targetRect(
            displayWidth = 1080,
            displayHeight = 2400,
            bottomInset = 120,
            density = 1f
        )

        assertFalse(
            FloatingOverlayDismissTarget.isWithinDismissZone(
                centerX = rect.left - 40,
                centerY = rect.top - 40,
                targetRect = rect,
                density = 1f
            )
        )
    }
}
