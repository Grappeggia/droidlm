package ai.droidlm.runtime

import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.portal.AccessibilityGateway
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.ScreenshotResult
import ai.droidlm.textedit.EditableTarget
import android.graphics.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRuntimeStoresTest {
    @Test fun overlayRuntimeStartsStoppedAndToggles() {
        val runtime = OverlayRuntime()

        assertFalse(runtime.isRunning.value)

        runtime.setRunning(true)
        assertTrue(runtime.isRunning.value)

        runtime.setRunning(false)
        assertFalse(runtime.isRunning.value)
    }

    @Test fun listeningRuntimeStartsStoppedAndToggles() {
        val runtime = ListeningRuntime()

        assertFalse(runtime.isRunning.value)

        runtime.setRunning(true)
        assertTrue(runtime.isRunning.value)

        runtime.setRunning(false)
        assertFalse(runtime.isRunning.value)
    }

    @Test fun accessibilityRuntimeIgnoresStaleDetach() {
        val runtime = AccessibilityRuntime()
        val firstGateway = FakeAccessibilityGateway()
        val secondGateway = FakeAccessibilityGateway()

        val firstToken = runtime.attach(firstGateway)
        val secondToken = runtime.attach(secondGateway)
        runtime.detach(firstToken)

        assertTrue(runtime.isConnected.value)
        assertSame(secondGateway, runtime.currentGateway())

        runtime.detach(secondToken)

        assertFalse(runtime.isConnected.value)
        assertNull(runtime.currentGateway())
    }

    private class FakeAccessibilityGateway : AccessibilityGateway {
        override fun captureState(includeAllWindows: Boolean): PortalState = PortalState(null, null, null, null, emptyList())
        override suspend fun tap(x: Int, y: Int): ActionResult = ActionResult.ok()
        override suspend fun tapNode(nodeId: String): ActionResult = ActionResult.ok()
        override suspend fun tapText(text: String, role: String?, containerNodeId: String?): ActionResult = ActionResult.ok()
        override fun focusNode(nodeId: String): ActionResult = ActionResult.ok()
        override suspend fun longPressNode(nodeId: String?, text: String?, durationMs: Int): ActionResult = ActionResult.ok()
        override suspend fun scroll(direction: ScrollDirection, targetNodeId: String?, untilText: String?): ActionResult = ActionResult.ok()
        override suspend fun waitForUi(text: String?, packageName: String?, nodeId: String?, timeoutMs: Int): ActionResult = ActionResult.ok()
        override fun pressImeAction(action: ImeActionType): ActionResult = ActionResult.ok()
        override suspend fun dialogAction(buttonText: String?, role: DialogButtonRole?): ActionResult = ActionResult.ok()
        override suspend fun openMenu(menu: MenuType): ActionResult = ActionResult.ok()
        override suspend fun selectTab(label: String): ActionResult = ActionResult.ok()
        override suspend fun setToggle(label: String?, nodeId: String?, value: Boolean): ActionResult = ActionResult.ok()
        override suspend fun expandCollapse(label: String?, nodeId: String?, expanded: Boolean): ActionResult = ActionResult.ok()
        override suspend fun setSlider(label: String?, nodeId: String?, value: Float?, percent: Int?): ActionResult = ActionResult.ok()
        override suspend fun refresh(targetNodeId: String?): ActionResult = ActionResult.ok()
        override suspend fun findTextOnScreen(text: String, tapOnMatch: Boolean): ActionResult = ActionResult.ok()
        override suspend fun longPress(x: Int, y: Int, durationMs: Int): ActionResult = ActionResult.ok()
        override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): ActionResult = ActionResult.ok()
        override fun setFocusedText(text: String): ActionResult = ActionResult.ok()
        override fun inputTextAtCurrentCursor(text: String): ActionResult = ActionResult.ok()
        override fun performGlobalBack(): ActionResult = ActionResult.ok()
        override fun performGlobalHome(): ActionResult = ActionResult.ok()
        override fun performGlobalNotifications(): ActionResult = ActionResult.ok()
        override fun performGlobalQuickSettings(): ActionResult = ActionResult.ok()
        override fun performGlobalRecents(): ActionResult = ActionResult.ok()
        override suspend fun takeScreenshotBitmap(): ScreenshotResult = ScreenshotResult(false)
        override fun findFocusedEditableTarget(): EditableTarget? = null
        override fun findEditableTargets(): List<EditableTarget> = emptyList()
        override fun getNodeText(nodeId: String): String? = null
        override fun getNodeSelection(nodeId: String): Pair<Int, Int>? = null
        override fun performSetSelection(nodeId: String, start: Int, end: Int): ActionResult = ActionResult.ok()
        override fun performSetText(nodeId: String, text: String): ActionResult = ActionResult.ok()
    }
}
