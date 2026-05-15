package ai.droidlm.agent

import ai.droidlm.intent.DroidLmAction
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVerifierTest {
    private val verifier = AgentVerifier()

    @Test fun verifiesOpenAppByActivePackage() {
        val result = verifier.verify(
            DroidLmAction.OpenApp("Drive", "com.google.android.apps.docs", "open"),
            ActionResult.ok("launched"),
            beforeState = state("ai.droidlm.debug"),
            afterState = state("com.google.android.apps.docs")
        )

        assertEquals(AgentVerificationStatus.VERIFIED, result.status)
    }

    @Test fun failsOpenAppWhenActivePackageDoesNotMatch() {
        val result = verifier.verify(
            DroidLmAction.OpenApp("Drive", "com.google.android.apps.docs", "open"),
            ActionResult.ok("launched"),
            beforeState = state("ai.droidlm.debug"),
            afterState = state("ai.droidlm.debug")
        )

        assertEquals(AgentVerificationStatus.FAILED, result.status)
    }

    @Test fun failsOpenAppWhenCurrentArtifactAlreadyHasMatchingTarget() {
        val extras = JSONObject().put(
            "artifactContext",
            JSONObject().put(
                "navigationTargets",
                JSONArray().put(JSONObject().put("label", "Meetings").put("kind", "section"))
            )
        )

        val result = verifier.verify(
            action = DroidLmAction.OpenApp("Calendar", "com.google.android.calendar", "open"),
            actionResult = ActionResult.ok("launched"),
            beforeState = state("com.google.android.apps.docs.editors.docs", node("heading", text = "Meetings")),
            afterState = state("com.google.android.calendar"),
            goal = "navigate to meetings",
            deviceContextExtras = extras
        )

        assertEquals(AgentVerificationStatus.FAILED, result.status)
        assertTrue(result.message.contains("current artifact"))
    }

    @Test fun verifiesWaitForTextVisible() {
        val result = verifier.verify(
            DroidLmAction.WaitForUi(text = "Budget", reason = "wait"),
            ActionResult.ok("visible"),
            beforeState = state("pkg"),
            afterState = state("pkg", node("title", text = "Budget overview"))
        )

        assertEquals(AgentVerificationStatus.VERIFIED, result.status)
    }

    @Test fun failedExecutionAlwaysFailsVerification() {
        val result = verifier.verify(
            DroidLmAction.FindTextOnScreen("Budget", tapOnMatch = false, reason = "find"),
            ActionResult.fail("missing", "TEXT_NOT_FOUND"),
            beforeState = state("pkg"),
            afterState = state("pkg")
        )

        assertTrue(result.failed)
    }

    @Test fun nonStrictActionsDoNotFailWhenSuccessful() {
        val result = verifier.verify(
            DroidLmAction.InsertText("hello", "type"),
            ActionResult.ok("inserted"),
            beforeState = state("pkg"),
            afterState = null
        )

        assertFalse(result.failed)
    }

    private fun state(packageName: String, vararg nodes: UiNode) =
        PortalState(packageName, "Activity", 100, 200, nodes.toList())

    private fun node(id: String, text: String? = null) = UiNode(
        nodeId = id,
        text = text,
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
