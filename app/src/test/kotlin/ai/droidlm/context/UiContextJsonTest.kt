package ai.droidlm.context

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiCollectionInfo
import ai.droidlm.portal.UiCollectionItemInfo
import ai.droidlm.portal.UiNode
import ai.droidlm.portal.UiNodeAction
import ai.droidlm.portal.UiRangeInfo
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    actions = listOf("CLICK", "FOCUS"),
                    hintText = "Search files",
                    stateDescription = "Collapsed",
                    availableActions = listOf(
                        UiNodeAction("CLICK", androidActionId = 16, droidLmAction = "TAP_NODE"),
                        UiNodeAction("FOCUS", androidActionId = 1, droidLmAction = "FOCUS_NODE")
                    )
                )
            )
        )

        val json = UiContextJson.portalStateToJson(state)
        val node = json.getJSONArray("nodes").getJSONObject(0)
        assertEquals("com.google.android.apps.docs:id/search_bar", node.getString("id"))
        assertEquals("search", node.getString("role"))
        assertEquals("Search files", node.getString("hintText"))
        assertEquals("Collapsed", node.getString("stateDescription"))
        assertTrue(node.has("center"))
        assertTrue(node.getJSONArray("actions").toString().contains("CLICK"))
        assertEquals("TAP_NODE", node.getJSONArray("availableActions").getJSONObject(0).getString("droidLmAction"))
        assertTrue(json.getJSONObject("actionMap").getJSONArray("com.google.android.apps.docs:id/search_bar").toString().contains("CLICK"))
    }

    @Test fun serializesEffectiveParentActionsForStaticChildLabels() {
        val state = PortalState(
            packageName = "com.example",
            activityName = null,
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(
                UiNode(
                    nodeId = "title-node",
                    text = "Meeting Notes",
                    contentDescription = null,
                    className = "android.widget.TextView",
                    packageName = "com.example",
                    bounds = Rect(20, 200, 500, 260),
                    clickable = false,
                    editable = false,
                    focused = false,
                    enabled = true,
                    selected = false,
                    parentId = "row-node",
                    depth = 2,
                    childIndex = 1,
                    effectiveActions = listOf(
                        UiNodeAction(
                            name = "CLICK",
                            androidActionId = 16,
                            droidLmAction = "TAP_NODE",
                            targetNodeId = "row-node",
                            reason = "nearest actionable parent"
                        )
                    )
                )
            )
        )

        val node = UiContextJson.portalStateToJson(state).getJSONArray("nodes").getJSONObject(0)
        val effective = node.getJSONArray("effectiveActions").getJSONObject(0)
        assertEquals("row-node", effective.getString("targetNodeId"))
        assertEquals("TAP_NODE", effective.getString("droidLmAction"))
        assertEquals("nearest actionable parent", effective.getString("reason"))
    }

    @Test fun serializesCollectionRangeAndInputMetadata() {
        val state = PortalState(
            packageName = "com.example",
            activityName = null,
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(
                UiNode(
                    nodeId = "slider",
                    text = "Volume",
                    contentDescription = "Volume",
                    className = "android.widget.SeekBar",
                    packageName = "com.example",
                    bounds = Rect(20, 200, 500, 260),
                    clickable = false,
                    editable = false,
                    focused = false,
                    enabled = true,
                    selected = false,
                    inputType = 1,
                    inputTypeLabel = "TEXT",
                    multiLine = true,
                    heading = true,
                    collectionInfo = UiCollectionInfo(rowCount = 5, columnCount = 2, hierarchical = false, selectionMode = "SINGLE"),
                    collectionItemInfo = UiCollectionItemInfo(rowIndex = 1, rowSpan = 1, columnIndex = 0, columnSpan = 1, heading = true, selected = false),
                    rangeInfo = UiRangeInfo(type = "PERCENT", min = 0f, max = 100f, current = 35f)
                )
            )
        )

        val node = UiContextJson.portalStateToJson(state).getJSONArray("nodes").getJSONObject(0)
        assertEquals("range_control", node.getString("role"))
        assertEquals("TEXT", node.getString("inputTypeLabel"))
        assertTrue(node.getBoolean("multiLine"))
        assertTrue(node.getBoolean("heading"))
        assertEquals("SINGLE", node.getJSONObject("collectionInfo").getString("selectionMode"))
        assertEquals(1, node.getJSONObject("collectionItemInfo").getInt("rowIndex"))
        assertEquals("PERCENT", node.getJSONObject("rangeInfo").getString("type"))
    }

    @Test fun suppressesPasswordTextButKeepsAffordances() {
        val state = PortalState(
            packageName = "com.example",
            activityName = null,
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = listOf(
                UiNode(
                    nodeId = "password",
                    text = "secret-password",
                    contentDescription = "Password",
                    className = "android.widget.EditText",
                    packageName = "com.example",
                    bounds = Rect(20, 200, 500, 260),
                    clickable = true,
                    editable = true,
                    focused = true,
                    enabled = true,
                    selected = false,
                    password = true,
                    availableActions = listOf(UiNodeAction("SET_TEXT", androidActionId = 2097152, droidLmAction = "SET_FULL_TEXT", requiresArgs = true, safe = false))
                )
            )
        )

        val node = UiContextJson.portalStateToJson(state).getJSONArray("nodes").getJSONObject(0)
        assertFalse(node.has("text"))
        assertTrue(node.getBoolean("password"))
        assertEquals("SET_FULL_TEXT", node.getJSONArray("availableActions").getJSONObject(0).getString("droidLmAction"))
    }
}
