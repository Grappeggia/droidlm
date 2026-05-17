package ai.droidlm.context

import ai.droidlm.context.GoogleWorkspaceContextUtils.DRIVE_PACKAGE
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import ai.droidlm.portal.UiNodeAction
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
                node(text = "Share", clickable = true),
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
        assertEquals("google_drive", json.getJSONObject("artifactContext").getJSONObject("artifact").getString("source"))
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

    @Test fun usesEffectiveParentTargetForVisibleFileLabels() = runTest {
        val state = PortalState(
            packageName = DRIVE_PACKAGE,
            activityName = "DriveActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(
                node(text = "My Drive"),
                node(nodeId = "row-1", clickable = true),
                node(
                    nodeId = "entry-label",
                    text = "Summary of Docs document",
                    effectiveActions = listOf(UiNodeAction("CLICK", droidLmAction = "TAP_NODE", targetNodeId = "row-1"))
                )
            )
        )

        val json = GoogleDriveContextProvider().collect(DeviceContextRequest(null, state, null, emptyList()))
        val file = json.getJSONObject("driveContext").getJSONArray("visibleFiles").getJSONObject(0)

        assertEquals("row-1", file.getString("nodeId"))
        assertEquals("entry-label", file.getString("labelNodeId"))
        assertTrue(file.getBoolean("tappable"))
    }

    @Test fun promotesDrivePreviewContentDescriptionIntoContentWindows() = runTest {
        val preview = "Summary of Docs\r\n- Meetings - H&O PAT Weekly, PAT Touchbase\r\n- Planning - Next roadmap"
        val state = PortalState(
            packageName = DRIVE_PACKAGE,
            activityName = "DriveActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(
                node(contentDescription = "Open with"),
                node(nodeId = "preview", contentDescription = preview)
            )
        )

        val json = GoogleDriveContextProvider().collect(DeviceContextRequest(null, state, null, emptyList()))
        val drive = json.getJSONObject("driveContext")
        val artifact = json.getJSONObject("artifactContext")

        assertTrue(drive.getString("visibleText").contains("Meetings - H&O PAT Weekly"))
        assertTrue(artifact.getJSONObject("contentWindow").getString("fullText").contains("PAT Touchbase"))
        assertTrue(artifact.getJSONArray("navigationTargets").toString().contains("Meetings - H&O PAT Weekly"))
        assertTrue(artifact.getJSONArray("availableTools").toString().contains("SEARCH_ACCESSIBILITY_CONTENT"))
    }

    private fun node(
        nodeId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        clickable: Boolean = false,
        effectiveActions: List<UiNodeAction> = emptyList()
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
        selected = false,
        effectiveActions = effectiveActions
    )
}
