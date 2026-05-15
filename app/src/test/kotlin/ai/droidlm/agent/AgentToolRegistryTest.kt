package ai.droidlm.agent

import ai.droidlm.intent.DroidLmAction
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import android.graphics.Rect
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRegistryTest {
    private val registry = AgentToolRegistry()

    @Test fun rejectsUnknownTool() {
        val result = registry.toExecution(AgentToolCall("call_1", "INSTALL_APP", JSONObject(), ""), null, emptyList())

        assertTrue(result.isFailure)
    }

    @Test fun rejectsMissingOpenAppPackageWhenInventoryAvailable() {
        val call = AgentToolCall("call_1", "OPEN_APP", JSONObject().put("packageName", "missing.pkg"), "")
        val result = registry.toExecution(call, null, listOf(AppPackage("installed.pkg", "Installed", launchable = true)))

        assertTrue(result.isFailure)
    }

    @Test fun acceptsLaunchableOpenApp() {
        val call = AgentToolCall("call_1", "OPEN_APP", JSONObject().put("packageName", "installed.pkg"), "")
        val result = registry.toExecution(call, null, listOf(AppPackage("installed.pkg", "Installed", enabled = true, launchable = true)))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().action is DroidLmAction.OpenApp)
    }

    @Test fun acceptsArtifactNavigationTool() {
        val call = AgentToolCall("call_1", "NAVIGATE_TO_ARTIFACT_TARGET", JSONObject().put("label", "Meetings").put("kind", "section"), "")

        assertTrue(registry.toExecution(call, null, emptyList()).isSuccess)
    }

    @Test fun rejectsStaleNodeTarget() {
        val state = PortalState("pkg", "Activity", 100, 200, listOf(node("visible")))
        val call = AgentToolCall("call_1", "TAP_NODE", JSONObject().put("nodeId", "stale"), "")

        assertTrue(registry.toExecution(call, state, emptyList()).isFailure)
    }

    @Test fun rejectsOffscreenCoordinates() {
        val state = PortalState("pkg", "Activity", 100, 200, emptyList())
        val call = AgentToolCall("call_1", "TAP", JSONObject().put("x", 120).put("y", 10), "")

        assertTrue(registry.toExecution(call, state, emptyList()).isFailure)
    }

    private fun node(id: String) = UiNode(
        nodeId = id,
        text = null,
        contentDescription = null,
        className = null,
        packageName = "pkg",
        bounds = Rect(0, 0, 10, 10),
        clickable = true,
        editable = false,
        focused = false,
        enabled = true,
        selected = false
    )
}
