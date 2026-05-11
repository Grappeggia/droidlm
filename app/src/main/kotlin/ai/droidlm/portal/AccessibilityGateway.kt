package ai.droidlm.portal

import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.textedit.EditableTarget

interface AccessibilityGateway {
    fun captureState(includeAllWindows: Boolean): PortalState
    suspend fun tap(x: Int, y: Int): ActionResult
    suspend fun tapNode(nodeId: String): ActionResult
    suspend fun tapText(text: String, role: String?, containerNodeId: String?): ActionResult
    fun focusNode(nodeId: String): ActionResult
    suspend fun longPressNode(nodeId: String?, text: String?, durationMs: Int): ActionResult
    suspend fun scroll(direction: ScrollDirection, targetNodeId: String?, untilText: String?): ActionResult
    suspend fun waitForUi(text: String?, packageName: String?, nodeId: String?, timeoutMs: Int): ActionResult
    fun pressImeAction(action: ImeActionType): ActionResult
    suspend fun dialogAction(buttonText: String?, role: DialogButtonRole?): ActionResult
    suspend fun openMenu(menu: MenuType): ActionResult
    suspend fun selectTab(label: String): ActionResult
    suspend fun setToggle(label: String?, nodeId: String?, value: Boolean): ActionResult
    suspend fun expandCollapse(label: String?, nodeId: String?, expanded: Boolean): ActionResult
    suspend fun setSlider(label: String?, nodeId: String?, value: Float?, percent: Int?): ActionResult
    suspend fun refresh(targetNodeId: String?): ActionResult
    suspend fun findTextOnScreen(text: String, tapOnMatch: Boolean): ActionResult
    suspend fun longPress(x: Int, y: Int, durationMs: Int): ActionResult
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): ActionResult
    fun setFocusedText(text: String): ActionResult
    fun inputTextAtCurrentCursor(text: String): ActionResult
    fun performGlobalBack(): ActionResult
    fun performGlobalHome(): ActionResult
    fun performGlobalNotifications(): ActionResult
    fun performGlobalQuickSettings(): ActionResult
    fun performGlobalRecents(): ActionResult
    suspend fun takeScreenshotBitmap(): ScreenshotResult
    fun findFocusedEditableTarget(): EditableTarget?
    fun findEditableTargets(): List<EditableTarget>
    fun getNodeText(nodeId: String): String?
    fun getNodeSelection(nodeId: String): Pair<Int, Int>?
    fun performSetSelection(nodeId: String, start: Int, end: Int): ActionResult
    fun performSetText(nodeId: String, text: String): ActionResult
}
