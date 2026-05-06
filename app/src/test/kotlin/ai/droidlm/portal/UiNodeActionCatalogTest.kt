package ai.droidlm.portal

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UiNodeActionCatalogTest {
    @Test fun mapsAccessibilityActionsToDroidLmAffordances() {
        val node = AccessibilityNodeInfo.obtain().apply {
            addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Open"))
            addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Options"))
            addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, "Set text"))
            addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Scroll forward"))
        }

        val actions = UiNodeActionCatalog.fromAccessibilityNode(node)

        val click = actions.first { it.name == "CLICK" }
        assertEquals(AccessibilityNodeInfo.ACTION_CLICK, click.androidActionId)
        assertEquals("Open", click.label)
        assertEquals("TAP_NODE", click.droidLmAction)
        assertFalse(click.requiresArgs)

        val setText = actions.first { it.name == "SET_TEXT" }
        assertEquals("SET_FULL_TEXT", setText.droidLmAction)
        assertTrue(setText.requiresArgs)
        assertEquals("string", setText.argSchema["text"])
        assertFalse(setText.safe)

        val scrollForward = actions.first { it.name == "SCROLL_FORWARD" }
        assertEquals("SWIPE", scrollForward.droidLmAction)

        val longClick = actions.first { it.name == "LONG_CLICK" }
        assertNull(longClick.droidLmAction)
    }

    @Test fun createsEffectiveActionsTargetingParentNode() {
        val parentActions = listOf(
            UiNodeAction("CLICK", androidActionId = AccessibilityNodeInfo.ACTION_CLICK, droidLmAction = "TAP_NODE"),
            UiNodeAction("SET_TEXT", androidActionId = AccessibilityNodeInfo.ACTION_SET_TEXT, droidLmAction = "SET_FULL_TEXT")
        )

        val effectiveActions = UiNodeActionCatalog.effectiveFromParent("parent-row", parentActions)

        assertEquals(1, effectiveActions.size)
        assertEquals("CLICK", effectiveActions.first().name)
        assertEquals("parent-row", effectiveActions.first().targetNodeId)
        assertEquals("nearest actionable parent", effectiveActions.first().reason)
    }
}
