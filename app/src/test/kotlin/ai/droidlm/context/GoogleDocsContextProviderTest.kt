package ai.droidlm.context

import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
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

    private fun node(
        nodeId: String? = null,
        text: String? = null,
        editable: Boolean = false,
        focused: Boolean = false,
        textSelectionStart: Int? = null,
        textSelectionEnd: Int? = null
    ) = UiNode(
        nodeId = nodeId,
        text = text,
        contentDescription = null,
        className = null,
        packageName = GoogleDocsContextProvider.DOCS_PACKAGE,
        bounds = null,
        clickable = false,
        editable = editable,
        focused = focused,
        enabled = true,
        selected = false,
        textSelectionStart = textSelectionStart,
        textSelectionEnd = textSelectionEnd
    )
}
