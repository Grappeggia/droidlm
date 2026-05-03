package ai.droidlm.context

import ai.droidlm.context.GoogleWorkspaceContextUtils.DRIVE_PACKAGE
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveContextProviderTest {
    @Test fun collectsFileListContext() = runTest {
        val state = PortalState(
            packageName = DRIVE_PACKAGE,
            activityName = "DriveActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(
                node(text = "My Drive"),
                node(nodeId = "file-1", text = "Budget tracker spreadsheet", clickable = true),
                node(nodeId = "file-2", text = "Meeting notes document", clickable = true),
                node(text = "Search in Drive", contentDescription = "Search in Drive", clickable = true)
            )
        )

        val json = GoogleDriveContextProvider().collect(
            DeviceContextRequest(null, state, null, emptyList())
        )
        val drive = json.getJSONObject("driveContext")

        assertEquals("SEARCH_RESULTS", drive.getString("uiMode"))
        assertTrue(drive.getJSONArray("visibleFiles").toString().contains("Budget tracker spreadsheet"))
        assertTrue(json.getJSONArray("availableDriveActions").toString().contains("CREATE_SHEET"))
    }

    @Test fun detectsShareDialogSafety() = runTest {
        val state = PortalState(
            packageName = DRIVE_PACKAGE,
            activityName = "DriveActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(
                node(text = "People with access"),
                node(text = "General access"),
                node(text = "e2e@example.test")
            )
        )

        val json = GoogleDriveContextProvider().collect(DeviceContextRequest(null, state, null, emptyList()))

        assertEquals("SHARE_DIALOG", json.getJSONObject("driveContext").getString("uiMode"))
        assertTrue(json.getJSONObject("safety").getBoolean("sharingFlowActive"))
        assertTrue(json.getJSONObject("safety").getBoolean("containsEmail"))
    }

    private fun node(
        nodeId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        clickable: Boolean = false
    ) = UiNode(
        nodeId = nodeId,
        text = text,
        contentDescription = contentDescription,
        className = null,
        packageName = DRIVE_PACKAGE,
        bounds = null,
        clickable = clickable,
        editable = false,
        focused = false,
        enabled = true,
        selected = false
    )
}
