package ai.droidlm.textedit

import ai.droidlm.intent.AnchorPosition
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.ocr.OcrResult
import ai.droidlm.ocr.OcrSource
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.ScreenshotResult
import ai.droidlm.relay.RelayClient
import ai.droidlm.relay.DeviceContext
import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextEditingControllerTest {
    @Test fun insertAfterBudgetUsesSelectionBeforeInput() = kotlinx.coroutines.test.runTest {
        val portal = FakePortal("Project budget due Friday")
        val controller = controller(portal)
        val result = controller.insertTextAtAnchor("budget", AnchorPosition.AFTER, ", revised")
        assertTrue(result.success)
        assertEquals("Project budget, revised due Friday", portal.text)
        assertEquals(listOf("setSelection:14:14", "input:, revised"), portal.operations.take(2))
    }

    @Test fun replaceDraftWithFinal() = kotlinx.coroutines.test.runTest {
        val portal = FakePortal("This is a draft version")
        val controller = controller(portal)
        val result = controller.replaceText("draft", "final")
        assertTrue(result.success)
        assertEquals("This is a final version", portal.text)
    }

    @Test fun appendNewlineText() = kotlinx.coroutines.test.runTest {
        val portal = FakePortal("Hello")
        val controller = controller(portal)
        val result = controller.appendText("\nsigned, Alex")
        assertTrue(result.success)
        assertEquals("Hello\nsigned, Alex", portal.text)
    }

    private fun controller(portal: FakePortal) = TextEditingController(
        portal,
        object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap, deviceContext: DeviceContext?) = OcrResult("", emptyList(), emptyList(), emptyList(), emptyList(), OcrSource.ML_KIT_ON_DEVICE)
        },
        RelayClient(),
        ActionLogRepository()
    )

    private class FakePortal(initial: String) : PortalController {
        var text: String = initial
        var selection: Pair<Int, Int> = initial.length to initial.length
        val operations = mutableListOf<String>()
        private val target = EditableTarget("node", "pkg", "EditText", Rect(0, 0, 200, 80), true, true, true, true, true)

        override suspend fun isAccessibilityEnabled() = true
        override suspend fun getState() = PortalState("pkg", null, 100, 100, emptyList())
        override suspend fun getFullState() = getState()
        override suspend fun listPackages(): List<AppPackage> = emptyList()
        override suspend fun openApp(packageName: String) = ActionResult.ok()
        override suspend fun openSettings() = ActionResult.ok()
        override suspend fun tap(x: Int, y: Int) = ActionResult.ok()
        override suspend fun tapNode(nodeId: String) = ActionResult.ok()
        override suspend fun focusNode(nodeId: String) = ActionResult.ok()
        override suspend fun longPress(x: Int, y: Int, durationMs: Int) = ActionResult.ok()
        override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int) = ActionResult.ok()
        override suspend fun typeText(text: String, clear: Boolean) = inputTextAtCurrentCursor(text)
        override suspend fun inputTextAtCurrentCursor(text: String): ActionResult {
            operations += "input:$text"
            val start = selection.first.coerceIn(0, this.text.length)
            val end = selection.second.coerceIn(start, this.text.length)
            this.text = this.text.substring(0, start) + text + this.text.substring(end)
            val cursor = start + text.length
            selection = cursor to cursor
            return ActionResult.ok("input")
        }
        override suspend fun sendKeyCode(keyCode: Int) = ActionResult.ok()
        override suspend fun pressBack() = ActionResult.ok()
        override suspend fun pressHome() = ActionResult.ok()
        override suspend fun takeScreenshot() = ScreenshotResult(false, message = "unused")
        override suspend fun findFocusedEditableNode() = target
        override suspend fun findEditableNodes() = listOf(target)
        override suspend fun getNodeText(nodeId: String) = text
        override suspend fun getNodeSelection(nodeId: String) = selection
        override suspend fun performSetSelection(nodeId: String, start: Int, end: Int): ActionResult {
            operations += "setSelection:$start:$end"
            selection = start to end
            return ActionResult.ok("selection")
        }
        override suspend fun performSetText(nodeId: String, text: String): ActionResult {
            operations += "setText:$text"
            this.text = text
            selection = text.length to text.length
            return ActionResult.ok("setText")
        }
    }
}
