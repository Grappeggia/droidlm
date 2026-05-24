package ai.droidlm.context

import android.graphics.Rect

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

    @Test fun exposesLongEditableTextAbovePreviousEightThousandCharLimit() = runTest {
        val longText = "Document start\n" + "A".repeat(12_000) + "\nDocument end"
        val state = PortalState(
            packageName = GoogleDocsContextProvider.DOCS_PACKAGE,
            activityName = "DocumentActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(node(nodeId = "editor", text = longText, editable = true, focused = true))
        )

        val json = GoogleDocsContextProvider().collect(DeviceContextRequest(null, state, null, emptyList()))
        val textWindow = json.getJSONObject("documentTextWindow")

        assertTrue(textWindow.getString("focusedEditableText").contains("Document end"))
        assertTrue(json.getJSONObject("artifactContext").getJSONObject("contentWindow").getString("fullText").contains("Document end"))
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

    @Test fun treatsSearchBoxOnlyEditableAsDocumentList() = runTest {
        val state = PortalState(
            packageName = GoogleDocsContextProvider.DOCS_PACKAGE,
            activityName = "DocsActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(
                node(
                    nodeId = "search-box",
                    text = "",
                    contentDescription = "Search Docs",
                    hintText = "Search Docs",
                    editable = true,
                    focused = true
                ),
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
        val visibleDocuments = docs.getJSONArray("visibleDocuments")

        assertEquals("DOCUMENT_LIST", docs.getString("uiMode"))
        assertEquals(false, json.getJSONObject("editor").getBoolean("canType"))
        assertEquals(1, visibleDocuments.length())
        assertEquals("Summary of Docs", visibleDocuments.getJSONObject(0).getString("title"))
    }

    @Test fun exposesStructuredDocumentCollectionWithRowGrounding() = runTest {
        val state = PortalState(
            packageName = GoogleDocsContextProvider.DOCS_PACKAGE,
            activityName = "DocsActivity",
            screenWidth = 600,
            screenHeight = 1000,
            nodes = listOf(
                node(text = "Recent documents"),
                node(nodeId = "summary-row", clickable = true, bounds = Rect(0, 400, 600, 900)),
                node(
                    nodeId = "summary-label",
                    contentDescription = "Summary of Docs",
                    bounds = Rect(20, 438, 540, 884),
                    parentId = "summary-row"
                ),
                node(
                    nodeId = "summary-more",
                    contentDescription = "More actions for Summary of Docs",
                    bounds = Rect(420, 478, 480, 538)
                ),
                node(nodeId = "meeting-row", text = "Distractor Meeting Notes", clickable = true, bounds = Rect(0, 910, 600, 980))
            )
        )

        val json = GoogleDocsContextProvider().collect(
            DeviceContextRequest("open summary of docs", state, null, emptyList())
        )
        val collection = json.getJSONArray("structuredCollections").getJSONObject(0)
        val items = collection.getJSONArray("items")
        val summary = (0 until items.length())
            .map { items.getJSONObject(it) }
            .first { it.getString("primaryLabel") == "Summary of Docs" }

        assertEquals("document_list", collection.getString("type"))
        assertEquals("OPEN_ROW", summary.getString("actionability"))
        assertEquals("summary-row", summary.getString("tapTargetNodeId"))
        assertTrue(summary.getJSONArray("accessoryActions").toString().contains("more_actions"))
        assertTrue(summary.getDouble("goalOverlapScore") > 0.9)
    }

    private fun node(
        nodeId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        hintText: String? = null,
        editable: Boolean = false,
        focused: Boolean = false,
        textSelectionStart: Int? = null,
        textSelectionEnd: Int? = null,
        clickable: Boolean = false,
        effectiveActions: List<UiNodeAction> = emptyList(),
        bounds: Rect? = null,
        parentId: String? = null
    ) = UiNode(
        nodeId = nodeId,
        text = text,
        contentDescription = contentDescription,
        className = null,
        packageName = GoogleDocsContextProvider.DOCS_PACKAGE,
        bounds = bounds,
        clickable = clickable,
        editable = editable,
        focused = focused,
        enabled = true,
        selected = false,
        textSelectionStart = textSelectionStart,
        textSelectionEnd = textSelectionEnd,
        effectiveActions = effectiveActions,
        hintText = hintText,
        parentId = parentId
    )
}
