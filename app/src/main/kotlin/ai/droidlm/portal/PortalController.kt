package ai.droidlm.portal

import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.textedit.EditableTarget

interface PortalController {
    suspend fun isAccessibilityEnabled(): Boolean

    suspend fun getState(): PortalState
    suspend fun getFullState(): PortalState?
    suspend fun listPackages(): List<AppPackage>

    suspend fun openApp(packageName: String): ActionResult
    suspend fun openSettings(): ActionResult

    suspend fun tap(x: Int, y: Int): ActionResult
    suspend fun tapNode(nodeId: String): ActionResult
    suspend fun focusNode(nodeId: String): ActionResult
    suspend fun longPress(x: Int, y: Int, durationMs: Int = 600): ActionResult
    suspend fun tapText(text: String, role: String? = null, containerNodeId: String? = null): ActionResult
    suspend fun longPressNode(nodeId: String? = null, text: String? = null, durationMs: Int = 600): ActionResult
    suspend fun scroll(direction: ScrollDirection, targetNodeId: String? = null, untilText: String? = null): ActionResult
    suspend fun waitForUi(text: String? = null, packageName: String? = null, nodeId: String? = null, timeoutMs: Int = 2_500): ActionResult
    suspend fun pressImeAction(action: ImeActionType): ActionResult
    suspend fun dialogAction(buttonText: String? = null, role: DialogButtonRole? = null): ActionResult
    suspend fun openMenu(menu: MenuType): ActionResult
    suspend fun selectTab(label: String): ActionResult
    suspend fun setToggle(label: String? = null, nodeId: String? = null, value: Boolean): ActionResult
    suspend fun expandCollapse(label: String? = null, nodeId: String? = null, expanded: Boolean): ActionResult
    suspend fun setSlider(label: String? = null, nodeId: String? = null, value: Float? = null, percent: Int? = null): ActionResult
    suspend fun refresh(targetNodeId: String? = null): ActionResult
    suspend fun findTextOnScreen(text: String, tapOnMatch: Boolean = false): ActionResult
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
    suspend fun openNotifications(): ActionResult
    suspend fun openQuickSettings(): ActionResult
    suspend fun openRecents(): ActionResult
    suspend fun openUrl(url: String): ActionResult
    suspend fun openDeepLink(uri: String): ActionResult
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
