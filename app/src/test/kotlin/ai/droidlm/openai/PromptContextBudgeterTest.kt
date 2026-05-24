package ai.droidlm.openai

import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import ai.droidlm.portal.UiNodeAction
import ai.droidlm.relay.ActiveApp
import ai.droidlm.relay.DeviceContext
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptContextBudgeterTest {
    @Test fun fixedTierBudgetPreservesVisibleNavigationAndAdjacentContext() {
        val longBody = syntheticLongBody()
        val context = PromptContextBudgeter.build(
            goal = "open budget summary and find financial highlights",
            activeApp = activeApp(),
            deviceContext = longDocsDeviceContext(longBody),
            uiState = longDocsState(longBody),
            packages = installedPackages(),
            history = listOf("previous command"),
            targetContextTokens = 8_000
        ).json

        val asString = context.toString()
        assertTrue(asString.contains("promptBudget"))
        assertTrue(asString.contains("visibleActionable"))
        assertTrue(asString.contains("goalRelevantVisible"))
        assertTrue(asString.contains("navigationStructure"))
        assertTrue(asString.contains("adjacentContext"))
        assertTrue(asString.contains("summaryRemainder"))
        assertTrue(asString.contains("Budget Summary"))
        assertTrue(asString.contains("Financial Highlights"))
        assertTrue(asString.contains("Nearby Context"))
        assertTrue(asString.contains("SEARCH_ACCESSIBILITY_CONTENT"))
        assertTrue(asString.contains("accessibilityContentContext"))
        assertFalse(asString.contains("synthetic-hidden-tail-1800"))
        assertFalse(asString.contains("screen-only-duplicated-artifact-marker"))
        assertTrue(context.getJSONObject("promptBudget").getInt("estimatedContextTokens") <= 8_000)
    }

    @Test fun budgeterDoesNotMutateOriginalDeviceContext() {
        val longBody = syntheticLongBody()
        val deviceContext = longDocsDeviceContext(longBody)
        val originalScreenArtifact = deviceContext.extras
            .getJSONObject("screenObservation")
            .getJSONObject("artifactContext")
            .getJSONObject("contentWindow")
            .getString("fullText")

        PromptContextBudgeter.build(
            goal = "find financial highlights",
            activeApp = activeApp(),
            deviceContext = deviceContext,
            uiState = longDocsState(longBody),
            packages = installedPackages(),
            targetContextTokens = 8_000
        )

        val afterScreenArtifact = deviceContext.extras
            .getJSONObject("screenObservation")
            .getJSONObject("artifactContext")
            .getJSONObject("contentWindow")
            .getString("fullText")
        assertTrue(originalScreenArtifact.contains("screen-only-duplicated-artifact-marker"))
        assertTrue(afterScreenArtifact.contains("screen-only-duplicated-artifact-marker"))
    }

    private fun activeApp(): ActiveApp = ActiveApp(
        packageName = "com.google.android.apps.docs.editors.docs",
        activityName = "DocsActivity",
        label = "Docs"
    )

    private fun installedPackages(): List<AppPackage> = listOf(
        AppPackage("com.google.android.apps.docs.editors.docs", "Docs", launchable = true, enabled = true),
        AppPackage("com.example.mail", "Mail", launchable = true, enabled = true)
    )

    private fun longDocsState(longBody: String): PortalState = PortalState(
        packageName = "com.google.android.apps.docs.editors.docs",
        activityName = "DocsActivity",
        screenWidth = 1080,
        screenHeight = 2400,
        nodes = listOf(
            UiNode(
                nodeId = "row-budget-summary",
                text = "Budget Summary",
                contentDescription = null,
                className = "android.widget.TextView",
                packageName = "com.google.android.apps.docs.editors.docs",
                bounds = Rect(20, 200, 1000, 280),
                clickable = true,
                editable = false,
                focused = false,
                enabled = true,
                selected = false,
                parentId = "list-root",
                childIndex = 1,
                availableActions = listOf(UiNodeAction("CLICK", androidActionId = 16, droidLmAction = "TAP_NODE"))
            ),
            UiNode(
                nodeId = "nearby-context",
                text = "Nearby Context",
                contentDescription = null,
                className = "android.widget.TextView",
                packageName = "com.google.android.apps.docs.editors.docs",
                bounds = Rect(20, 285, 1000, 340),
                clickable = false,
                editable = false,
                focused = false,
                enabled = true,
                selected = false,
                parentId = "list-root",
                childIndex = 2
            ),
            UiNode(
                nodeId = "heading-financial-highlights",
                text = "Financial Highlights",
                contentDescription = null,
                className = "android.widget.TextView",
                packageName = "com.google.android.apps.docs.editors.docs",
                bounds = Rect(20, 360, 1000, 420),
                clickable = false,
                editable = false,
                focused = false,
                enabled = true,
                selected = false,
                heading = true,
                parentId = "doc-root",
                childIndex = 1
            ),
            UiNode(
                nodeId = "doc-editable",
                text = longBody,
                contentDescription = null,
                className = "android.widget.EditText",
                packageName = "com.google.android.apps.docs.editors.docs",
                bounds = Rect(0, 420, 1080, 2200),
                clickable = true,
                editable = true,
                focused = true,
                enabled = true,
                selected = false,
                textSelectionStart = 10,
                textSelectionEnd = 10,
                availableActions = listOf(UiNodeAction("SET_SELECTION", androidActionId = 131072, droidLmAction = "SET_SELECTION"))
            )
        )
    )

    private fun longDocsDeviceContext(longBody: String): DeviceContext {
        val artifactContext = JSONObject()
            .put("artifact", JSONObject().put("type", "document").put("source", "google_docs").put("title", "Budget Summary"))
            .put(
                "navigationTargets",
                JSONArray()
                    .put(JSONObject().put("label", "Budget Summary").put("kind", "document").put("nodeId", "row-budget-summary").put("visible", true).put("actions", JSONArray().put("tap")))
                    .put(JSONObject().put("label", "Financial Highlights").put("kind", "heading").put("nodeId", "heading-financial-highlights").put("visible", true).put("actions", JSONArray().put("show_on_screen")))
            )
            .put("contentWindow", JSONObject().put("fullText", longBody).put("focusedText", longBody).put("currentBlock", "Financial Highlights"))
            .put("availableTools", JSONArray().put("NAVIGATE_TO_ARTIFACT_TARGET").put("SEARCH_ACCESSIBILITY_CONTENT"))
        val accessibilityLines = JSONArray()
        longBody.lineSequence().take(300).forEachIndexed { index, line ->
            accessibilityLines.put(
                JSONObject()
                    .put("index", index + 1)
                    .put("text", line)
                    .put("nodeId", "doc-editable")
                    .put("visible", index < 5)
                    .put("focused", index == 0)
                    .put("heading", line.contains("Financial Highlights"))
            )
        }
        val extras = JSONObject()
            .put("docsContext", JSONObject().put("uiMode", "DOCUMENT_EDIT").put("visibleDocuments", JSONArray()))
            .put("editor", JSONObject().put("uiMode", "DOCUMENT_EDIT").put("canType", true).put("focusedEditableNodeId", "doc-editable"))
            .put("selectionContext", JSONObject().put("focusedEditableNodeId", "doc-editable").put("selectionStart", 10).put("selectionEnd", 10).put("currentParagraph", "Financial Highlights"))
            .put("documentTextWindow", JSONObject().put("visibleText", longBody).put("focusedEditableText", longBody).put("currentParagraph", "Financial Highlights").put("textBeforeCursor", "Before cursor").put("textAfterCursor", "After cursor"))
            .put("artifactContext", artifactContext)
            .put("structuredCollections", JSONArray().put(JSONObject().put("collectionType", "document_list").put("items", JSONArray().put(JSONObject().put("primaryLabel", "Budget Summary").put("tapTargetNodeId", "row-budget-summary")))))
            .put("accessibilityContentContext", JSONObject().put("contentWindow", JSONObject().put("fullText", longBody).put("lines", accessibilityLines)).put("provenance", JSONObject().put("rawCharCount", longBody.length).put("emittedLineCount", 300).put("truncated", true)))
            .put("screenObservation", JSONObject().put("observationId", "obs-test").put("screenHash", "hash-test").put("keyboardVisible", true).put("dialogVisible", false).put("loadingLikely", false).put("semanticCandidates", JSONArray().put(JSONObject().put("nodeRef", "row-budget-summary").put("label", "Budget Summary").put("role", "LIST_ITEM").put("visible", true).put("actions", JSONArray().put("CLICK")))).put("artifactContext", JSONObject().put("contentWindow", JSONObject().put("fullText", "screen-only-duplicated-artifact-marker $longBody"))))
        return DeviceContext(activeApp = activeApp(), packages = installedPackages(), extras = extras)
    }

    private fun syntheticLongBody(): String = (1..2_000).joinToString("\n") { index ->
        if (index == 40) "Financial Highlights" else "synthetic-hidden-tail-$index budget planning filler text"
    }
}
