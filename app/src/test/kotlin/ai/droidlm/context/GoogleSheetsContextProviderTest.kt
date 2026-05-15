package ai.droidlm.context

import ai.droidlm.context.GoogleWorkspaceContextUtils.SHEETS_PACKAGE
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSheetsContextProviderTest {
    @Test fun collectsCellEditContext() = runTest {
        val state = PortalState(
            packageName = SHEETS_PACKAGE,
            activityName = "SheetsActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(
                node(text = "Budget tracker"),
                node(text = "Share"),
                node(text = "fx Formula", contentDescription = "formula bar"),
                node(
                    nodeId = "cell-editor",
                    text = "1200",
                    contentDescription = "Column B Row 2",
                    editable = true,
                    focused = true,
                    textSelectionStart = 4,
                    textSelectionEnd = 4
                ),
                node(text = "Sheet1")
            )
        )

        val json = GoogleSheetsContextProvider().collect(
            DeviceContextRequest(null, state, null, emptyList())
        )

        assertEquals("FORMULA_BAR", json.getJSONObject("sheetsContext").getString("uiMode"))
        assertEquals("1200", json.getJSONObject("activeCell").getString("value"))
        assertEquals(2, json.getJSONObject("activeCell").getInt("row"))
        assertEquals("B", json.getJSONObject("activeCell").getString("column"))
        assertEquals("google_sheets", json.getJSONObject("artifactContext").getJSONObject("artifact").getString("source"))
        assertTrue(json.getJSONObject("artifactContext").getJSONArray("navigationTargets").toString().contains("1200"))
        assertTrue(json.getJSONArray("availableSheetActions").toString().contains("INSERT_FORMULA"))
    }

    private fun node(
        nodeId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        editable: Boolean = false,
        focused: Boolean = false,
        textSelectionStart: Int? = null,
        textSelectionEnd: Int? = null
    ) = UiNode(
        nodeId = nodeId,
        text = text,
        contentDescription = contentDescription,
        className = null,
        packageName = SHEETS_PACKAGE,
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
