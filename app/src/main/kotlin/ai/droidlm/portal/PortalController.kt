package ai.droidlm.portal

import ai.droidlm.textedit.EditableTarget

interface PortalController {
    suspend fun isAccessibilityEnabled(): Boolean

    suspend fun getState(): PortalState
    suspend fun getFullState(): PortalState?
    suspend fun listPackages(): List<AppPackage>

    suspend fun openApp(packageName: String): ActionResult
    suspend fun openSettings(): ActionResult

    suspend fun tap(x: Int, y: Int): ActionResult
    suspend fun longPress(x: Int, y: Int, durationMs: Int = 600): ActionResult
    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int
    ): ActionResult

    suspend fun typeText(text: String, clear: Boolean = false): ActionResult
    suspend fun inputTextAtCurrentCursor(text: String): ActionResult
    suspend fun sendKeyCode(keyCode: Int): ActionResult

    suspend fun pressBack(): ActionResult
    suspend fun pressHome(): ActionResult
    suspend fun takeScreenshot(): ScreenshotResult

    suspend fun findFocusedEditableNode(): EditableTarget?
    suspend fun findEditableNodes(): List<EditableTarget>
    suspend fun getNodeText(nodeId: String): String?
    suspend fun getNodeSelection(nodeId: String): Pair<Int, Int>?
    suspend fun performSetSelection(
        nodeId: String,
        start: Int,
        end: Int
    ): ActionResult

    suspend fun performSetText(
        nodeId: String,
        text: String
    ): ActionResult
}
