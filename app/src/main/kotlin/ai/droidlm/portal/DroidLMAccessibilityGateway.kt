package ai.droidlm.portal

import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.textedit.EditableTarget

internal class DroidLMAccessibilityGateway(
    private val service: DroidLMAccessibilityService
) : AccessibilityGateway {
    override fun captureState(includeAllWindows: Boolean): PortalState = service.captureState(includeAllWindows)
    override suspend fun tap(x: Int, y: Int): ActionResult = service.tap(x, y)
    override suspend fun tapNode(nodeId: String): ActionResult = service.tapNode(nodeId)
    override suspend fun tapText(text: String, role: String?, containerNodeId: String?): ActionResult =
        service.tapText(text, role, containerNodeId)

    override fun focusNode(nodeId: String): ActionResult = service.focusNode(nodeId)
    override suspend fun longPressNode(nodeId: String?, text: String?, durationMs: Int): ActionResult =
        service.longPressNode(nodeId, text, durationMs)

    override suspend fun scroll(direction: ScrollDirection, targetNodeId: String?, untilText: String?): ActionResult =
        service.scroll(direction, targetNodeId, untilText)

    override suspend fun waitForUi(text: String?, packageName: String?, nodeId: String?, timeoutMs: Int): ActionResult =
        service.waitForUi(text, packageName, nodeId, timeoutMs)

    override fun pressImeAction(action: ImeActionType): ActionResult = service.pressImeAction(action)
    override suspend fun dialogAction(buttonText: String?, role: DialogButtonRole?): ActionResult =
        service.dialogAction(buttonText, role)

    override suspend fun openMenu(menu: MenuType): ActionResult = service.openMenu(menu)
    override suspend fun selectTab(label: String): ActionResult = service.selectTab(label)
    override suspend fun setToggle(label: String?, nodeId: String?, value: Boolean): ActionResult =
        service.setToggle(label, nodeId, value)

    override suspend fun expandCollapse(label: String?, nodeId: String?, expanded: Boolean): ActionResult =
        service.expandCollapse(label, nodeId, expanded)

    override suspend fun setSlider(label: String?, nodeId: String?, value: Float?, percent: Int?): ActionResult =
        service.setSlider(label, nodeId, value, percent)

    override suspend fun refresh(targetNodeId: String?): ActionResult = service.refresh(targetNodeId)
    override suspend fun findTextOnScreen(text: String, tapOnMatch: Boolean): ActionResult =
        service.findTextOnScreen(text, tapOnMatch)

    override suspend fun longPress(x: Int, y: Int, durationMs: Int): ActionResult = service.longPress(x, y, durationMs)
    override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): ActionResult =
        service.swipe(startX, startY, endX, endY, durationMs)

    override fun setFocusedText(text: String): ActionResult = service.setFocusedText(text)
    override fun inputTextAtCurrentCursor(text: String): ActionResult = service.inputTextAtCurrentCursor(text)
    override fun performGlobalBack(): ActionResult = service.performGlobalBack()
    override fun performGlobalHome(): ActionResult = service.performGlobalHome()
    override fun performGlobalNotifications(): ActionResult = service.performGlobalNotifications()
    override fun performGlobalQuickSettings(): ActionResult = service.performGlobalQuickSettings()
    override fun performGlobalRecents(): ActionResult = service.performGlobalRecents()
    override suspend fun takeScreenshotBitmap(): ScreenshotResult = service.takeScreenshotBitmap()
    override fun findFocusedEditableTarget(): EditableTarget? = service.findFocusedEditableTarget()
    override fun findEditableTargets(): List<EditableTarget> = service.findEditableTargets()
    override fun getNodeText(nodeId: String): String? = service.getNodeText(nodeId)
    override fun getNodeSelection(nodeId: String): Pair<Int, Int>? = service.getNodeSelection(nodeId)
    override fun performSetSelection(nodeId: String, start: Int, end: Int): ActionResult =
        service.performSetSelection(nodeId, start, end)

    override fun performSetText(nodeId: String, text: String): ActionResult = service.performSetText(nodeId, text)
}
