package ai.droidlm.observation

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import ai.droidlm.portal.UiNodeAction
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenObservationBuilderTest {
    @Test
    fun stableFingerprintSurvivesPathOnlyNodeRefChanges() {
        val builder = ScreenObservationBuilder { 1_000L }
        val first = builder.build(
            portalState(
                node(
                    nodeId = "com.example:id/save@w0.0",
                    viewId = "com.example:id/save",
                    text = "Save",
                    className = "android.widget.Button",
                    clickable = true,
                    actions = listOf(UiNodeAction("CLICK", droidLmAction = "TAP_NODE"))
                )
            )
        )
        val second = builder.build(
            portalState(
                node(
                    nodeId = "com.example:id/save@w0.4",
                    viewId = "com.example:id/save",
                    text = "Save",
                    className = "android.widget.Button",
                    clickable = true,
                    actions = listOf(UiNodeAction("CLICK", droidLmAction = "TAP_NODE"))
                )
            )
        )

        assertEquals(first.nodes.single().stableFingerprint, second.nodes.single().stableFingerprint)
        assertNotEquals(first.nodes.single().nodeRef, second.nodes.single().nodeRef)
    }

    @Test
    fun screenHashChangesWhenVisibleTextChanges() {
        val builder = ScreenObservationBuilder { 1_000L }
        val first = builder.build(portalState(node(text = "Save", clickable = true)))
        val second = builder.build(portalState(node(text = "Saved", clickable = true)))

        assertNotEquals(first.screenHash, second.screenHash)
    }

    @Test
    fun jsonRanksSemanticCandidatesBeforeRawNodes() {
        val builder = ScreenObservationBuilder { 1_000L }
        val observation = builder.build(
            portalState(
                node(nodeId = "label", text = "Preferences", clickable = false),
                node(
                    nodeId = "saveButton",
                    text = "Save",
                    className = "android.widget.Button",
                    clickable = true,
                    actions = listOf(UiNodeAction("CLICK", droidLmAction = "TAP_NODE"))
                )
            )
        )

        val json = observation.toJson()
        val candidates = json.getJSONArray("semanticCandidates")
        assertTrue(candidates.length() >= 1)
        assertEquals("saveButton", candidates.getJSONObject(0).getString("nodeRef"))
        assertEquals("Save", candidates.getJSONObject(0).getString("label"))
        assertTrue(json.has("nodes"))
    }

    @Test
    fun buildIncludesOcrBlocksAndPriorDelta() {
        var now = 1_000L
        val builder = ScreenObservationBuilder { now }
        val first = builder.build(portalState(node(text = "Loading", className = "android.widget.ProgressBar")))
        now = 1_500L
        val second = builder.build(
            state = portalState(node(text = "Done", clickable = true)),
            ocrBlocks = listOf(OcrBlock("Done", Rect(1, 2, 10, 20), source = "ML_KIT_ON_DEVICE")),
            previous = first,
            ocrAttempted = true
        )

        assertEquals(1, second.ocrBlocks.size)
        assertNotNull(second.priorActionDelta)
        assertTrue(second.priorActionDelta!!.screenChanged)
        assertFalse(second.loadingLikely)
        assertTrue(second.confidence.reasons.any { it.startsWith("ocr_blocks:") })
    }

    private fun portalState(vararg nodes: UiNode): PortalState = PortalState(
        packageName = "com.example",
        activityName = "MainActivity",
        screenWidth = 1080,
        screenHeight = 2400,
        nodes = nodes.toList()
    )

    private fun node(
        nodeId: String = "node",
        viewId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        className: String? = "android.view.View",
        bounds: Rect = Rect(0, 0, 120, 80),
        clickable: Boolean = false,
        editable: Boolean = false,
        focused: Boolean = false,
        enabled: Boolean = true,
        selected: Boolean = false,
        focusable: Boolean = clickable || editable,
        scrollable: Boolean = false,
        checked: Boolean = false,
        checkable: Boolean = false,
        actions: List<UiNodeAction> = emptyList()
    ): UiNode = UiNode(
        nodeId = nodeId,
        text = text,
        contentDescription = contentDescription,
        className = className,
        packageName = "com.example",
        bounds = bounds,
        clickable = clickable,
        editable = editable,
        focused = focused,
        enabled = enabled,
        selected = selected,
        viewIdResourceName = viewId,
        visible = true,
        focusable = focusable,
        scrollable = scrollable,
        checked = checked,
        checkable = checkable,
        actions = actions.map { it.name },
        availableActions = actions
    )
}
