package ai.droidlm.context

import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import ai.droidlm.portal.UiNodeAction
import ai.droidlm.relay.ActiveApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDocsContextProviderTest {
    @Test fun collectsEditModeSelectionAndTextWindow() = runTest {
        val text = "Title\nBudget notes for Q2\nNext steps"
        val cursor = text.indexOf("Q2") + 2
        val state = PortalState(
            packageName = GoogleDocsContextProvider.DOCS_PACKAGE,
            activityName = "DocumentActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(
                node(text = "Project Plan"),
                node(text = "Share"),
                node(
                    nodeId = "editor",
                    text = text,
                    editable = true,
                    focused = true,
                    textSelectionStart = cursor,
                    textSelectionEnd = cursor
                )
            )
        )

        val json = GoogleDocsContextProvider().collect(
            DeviceContextRequest(
                goal = "append a note",
                state = state,
                activeApp = ActiveApp(GoogleDocsContextProvider.DOCS_PACKAGE, "DocumentActivity", "Docs"),
                packages = listOf(AppPackage(GoogleDocsContextProvider.DOCS_PACKAGE, "Docs"))
            )
        )

        assertEquals("DOCUMENT_EDIT", json.getJSONObject("docsContext").getString("uiMode"))
        assertTrue(json.getJSONObject("editor").getBoolean("canType"))
        assertEquals("Budget notes for Q2", json.getJSONObject("selectionContext").getString("currentParagraph"))
        assertTrue(json.getJSONObject("documentTextWindow").getString("visibleText").contains("Project Plan"))
        assertEquals("google_docs", json.getJSONObject("artifactContext").getJSONObject("artifact").getString("source"))
        assertTrue(json.getJSONObject("artifactContext").getJSONArray("navigationTargets").toString().contains("Budget notes for Q2"))
        assertTrue(json.getJSONArray("availableDocActions").toString().contains("FORMAT_BULLET"))
    }

    @Test fun ignoresNonDocsPackages() = runTest {
        val json = GoogleDocsContextProvider().collect(
            DeviceContextRequest(
                goal = null,
                state = PortalState("com.example", null, null, null, emptyList()),
                activeApp = null,
                packages = emptyList()
            )
        )

        assertEquals(0, json.length())
    }

    @Test fun exposesVisibleDocumentTargetsFromLabelRows() = runTest {
        val state = PortalState(
            packageName = GoogleDocsContextProvider.DOCS_PACKAGE,
            activityName = "DocsActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(
                node(text = "Recent documents"),
                node(nodeId = "document-row", clickable = true),
                node(
                    nodeId = "entry-label",
                    text = "Summary of Docs",
                    effectiveActions = listOf(UiNodeAction("CLICK", droidLmAction = "TAP_NODE", targetNodeId = "document-row"))
                )
            )
        )

        val json = GoogleDocsContextProvider().collect(DeviceContextRequest(null, state, null, emptyList()))
        val docs = json.getJSONObject("docsContext")
        val document = docs.getJSONArray("visibleDocuments").getJSONObject(0)

        assertEquals("DOCUMENT_LIST", docs.getString("uiMode"))
        assertEquals("document-row", document.getString("nodeId"))
        assertEquals("entry-label", document.getString("labelNodeId"))
        assertTrue(json.getJSONArray("availableDocActions").toString().contains("OPEN_RECENT_DOC"))
    }

    private fun node(
        nodeId: String? = null,
        text: String? = null,
        editable: Boolean = false,
        focused: Boolean = false,
        textSelectionStart: Int? = null,
        textSelectionEnd: Int? = null,
        clickable: Boolean = false,
        effectiveActions: List<UiNodeAction> = emptyList()
    ) = UiNode(
        nodeId = nodeId,
        text = text,
        contentDescription = null,
        className = null,
        packageName = GoogleDocsContextProvider.DOCS_PACKAGE,
        bounds = null,
        clickable = clickable,
        editable = editable,
        focused = focused,
        enabled = true,
        selected = false,
        textSelectionStart = textSelectionStart,
        textSelectionEnd = textSelectionEnd,
        effectiveActions = effectiveActions
    )
}
