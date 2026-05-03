package ai.droidlm.context

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiContextJsonTest {
    @Test fun serializesRichSearchNodeContext() {
        val state = PortalState(
            packageName = "com.google.android.apps.docs",
            activityName = null,
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(
                UiNode(
                    nodeId = "com.google.android.apps.docs:id/search_bar",
                    text = "Search in Drive",
                    contentDescription = "Search in Drive",
                    className = "android.widget.TextView",
                    packageName = "com.google.android.apps.docs",
                    bounds = Rect(24, 112, 1056, 184),
                    clickable = true,
                    editable = false,
                    focused = false,
                    enabled = true,
                    selected = false,
                    viewIdResourceName = "com.google.android.apps.docs:id/search_bar",
                    visible = true,
                    focusable = true,
                    actions = listOf("CLICK", "FOCUS")
                )
            )
        )

        val node = UiContextJson.portalStateToJson(state).getJSONArray("nodes").getJSONObject(0)
        assertEquals("com.google.android.apps.docs:id/search_bar", node.getString("id"))
        assertEquals("search", node.getString("role"))
        assertTrue(node.has("center"))
        assertTrue(node.getJSONArray("actions").toString().contains("CLICK"))
    }
}
