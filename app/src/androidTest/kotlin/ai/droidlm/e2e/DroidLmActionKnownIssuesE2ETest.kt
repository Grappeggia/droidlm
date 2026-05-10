package ai.droidlm.e2e

import ai.droidlm.DroidLMApp
import ai.droidlm.execution.DroidLmExecutor
import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.ocr.OcrResult
import ai.droidlm.ocr.OcrSource
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.ScreenshotResult
import ai.droidlm.portal.UiNode
import ai.droidlm.textedit.EditableTarget
import ai.droidlm.textedit.TextEditingController
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@RunWith(AndroidJUnit4::class)
class DroidLmActionKnownIssuesE2ETest {
    private val app: DroidLMApp
        get() = ApplicationProvider.getApplicationContext()

    private var server: MockWebServer? = null

    @Before
    fun setUp() = runBlocking {
        assumeTrue(
            "Known issue probes run only via `./gradlew connectedActionKnownIssuesE2e`.",
            InstrumentationRegistry.getArguments().getString("actionKnownIssuesE2e") == "true"
        )
        app.settingsRepository.updateRequireRiskConfirmation(false)
        app.settingsRepository.updateAutoAcceptSafePlans(true)
    }

    @After
    fun tearDown() {
        runCatching { server?.shutdown() }
        server = null
    }

    @Test
    fun typeTextClearTrueReplacesExistingEditableText() = runBlocking {
        val portal = FakePortalController(firstText = "old value", secondText = "other value")
        val executor = executorFor(portal)

        val result = executor.invokeActionForKnownIssue(
            DroidLmAction.TypeText("new value", clear = true, reason = "Replace existing text")
        )
        assertTrue("TYPE_TEXT clear=true should execute: ${result.message}", result.success)

        assertEquals(
            "TYPE_TEXT with clear=true should replace the focused editable text instead of appending/inserting.",
            "new value",
            portal.text("first")
        )
    }

    @Test
    fun focusEditableUsesRequestedNodeId() = runBlocking {
        val portal = FakePortalController(firstText = "first", secondText = "second")
        val executor = executorFor(portal)

        val result = executor.invokeActionForKnownIssue(
            DroidLmAction.FocusEditable("second", reason = "Focus the second editable")
        )
        assertTrue("FOCUS_EDITABLE with nodeId should execute: ${result.message}", result.success)

        assertEquals(
            "FOCUS_EDITABLE should focus the requested nodeId, not just report that some editable exists.",
            "second",
            portal.focusedNodeId
        )
    }

    @Test
    fun verifyTextChangeFailsWhenExpectedTextIsAbsent() = runBlocking {
        val portal = FakePortalController(firstText = "visible text", secondText = "other text")
        val executor = executorFor(portal)

        val result = executor.invokeActionForKnownIssue(
            DroidLmAction.VerifyTextChange("definitely absent text", reason = "Verify missing text")
        )

        assertFalse(
            "VERIFY_TEXT_CHANGE should fail when expected text is absent from the active UI/document.",
            result.success
        )
    }

    @Test
    fun analyzeScreenshotUsesCloudAnalysisEndpointWhenConfigured() = runBlocking {
        val portal = FakePortalController(firstText = "visible text", secondText = "other text")
        val executor = executorFor(portal, ocrEngine = FakeOcrEngine())
        server = MockWebServer().apply {
            enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"fullText":"visible text","suggestedAction":null,"lines":[],"elements":[]}""")
            )
            start()
        }
        app.settingsRepository.updateCloudScreenshotAnalysisEnabled(true)

        executor.invokeActionForKnownIssue(
            DroidLmAction.AnalyzeScreenshot("read visible text", reason = "Analyze screenshot")
        )

        val request = server!!.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull(
            "ANALYZE_SCREENSHOT should call the configured /analyze-screenshot endpoint instead of falling back to OCR-only behavior.",
            request
        )
        assertEquals("/analyze-screenshot", request!!.path?.substringBefore('?'))
    }

    private fun executorFor(
        portal: FakePortalController,
        ocrEngine: OcrEngine = app.ocrEngine
    ): DroidLmExecutor {
        val textEditingController = TextEditingController(
            portalController = portal,
            ocrEngine = ocrEngine,
            relayClient = app.relayClient,
            actionLogRepository = app.actionLogRepository,
            debugLogStore = app.debugLogStore
        )
        return DroidLmExecutor(
            settingsRepository = app.settingsRepository,
            openAiClient = app.openAiClient,
            portalController = portal,
            textEditingController = textEditingController,
            workspaceFileOperationController = app.workspaceFileOperationController,
            ocrEngine = ocrEngine,
            appInventoryRepository = app.appInventoryRepository,
            deviceContextAggregator = app.deviceContextAggregator,
            logs = app.actionLogRepository,
            safetyClassifier = app.safetyClassifier,
            promptHistoryRepository = app.promptHistoryRepository,
            diagnostics = app.speechDiagnosticsLogger,
            debugLogStore = app.debugLogStore,
            mobilerunCloudClient = app.mobilerunCloudClient
        )
    }

    private suspend fun DroidLmExecutor.invokeActionForKnownIssue(action: DroidLmAction): ActionResult =
        suspendCoroutine { continuation ->
            try {
                resetExecutorCancellationForProbe()
                val method = javaClass.getDeclaredMethod(
                    "executeAction",
                    DroidLmAction::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                    Continuation::class.java
                )
                method.isAccessible = true
                val returned = method.invoke(this, action, "known issue e2e", true, null, continuation)
                if (returned !== COROUTINE_SUSPENDED) {
                    continuation.resume(returned as ActionResult)
                }
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    private fun DroidLmExecutor.resetExecutorCancellationForProbe() {
        val field = javaClass.getDeclaredField("cancelled")
        field.isAccessible = true
        field.setBoolean(this, false)
    }

    private class FakePortalController(firstText: String, secondText: String) : PortalController {
        private val fields = linkedMapOf(
            "first" to EditableState(firstText, firstText.length, firstText.length),
            "second" to EditableState(secondText, secondText.length, secondText.length)
        )
        var focusedNodeId: String = "first"
            private set

        fun text(nodeId: String): String = fields.getValue(nodeId).text

        override suspend fun isAccessibilityEnabled(): Boolean = true

        override suspend fun getState(): PortalState = PortalState(
            packageName = "ai.droidlm.knownissues",
            activityName = "FakeEditableActivity",
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = fields.map { (nodeId, state) ->
                UiNode(
                    nodeId = nodeId,
                    text = state.text,
                    contentDescription = "$nodeId editable",
                    className = "android.widget.EditText",
                    packageName = "ai.droidlm.knownissues",
                    bounds = Rect(0, 0, 600, 160),
                    clickable = true,
                    editable = true,
                    focused = nodeId == focusedNodeId,
                    enabled = true,
                    selected = false
                )
            }
        )

        override suspend fun getFullState(): PortalState = getState()
        override suspend fun listPackages(): List<AppPackage> = emptyList()
        override suspend fun openApp(packageName: String): ActionResult = ActionResult.ok("Opened $packageName")
        override suspend fun openSettings(): ActionResult = ActionResult.ok("Opened settings")
        override suspend fun tap(x: Int, y: Int): ActionResult = ActionResult.ok("Tapped")
        override suspend fun tapNode(nodeId: String): ActionResult = ActionResult.ok("Tapped $nodeId")
        override suspend fun focusNode(nodeId: String): ActionResult = focusEditable(nodeId)
        override suspend fun longPress(x: Int, y: Int, durationMs: Int): ActionResult = ActionResult.ok("Long pressed")
        override suspend fun tapText(text: String, role: String?, containerNodeId: String?): ActionResult = ActionResult.ok("Tapped $text")
        override suspend fun longPressNode(nodeId: String?, text: String?, durationMs: Int): ActionResult = ActionResult.ok("Long pressed ${nodeId ?: text}")
        override suspend fun scroll(direction: ScrollDirection, targetNodeId: String?, untilText: String?): ActionResult = ActionResult.ok("Scrolled ${direction.name}")
        override suspend fun waitForUi(text: String?, packageName: String?, nodeId: String?, timeoutMs: Int): ActionResult = ActionResult.ok("Waited")
        override suspend fun pressImeAction(action: ImeActionType): ActionResult = ActionResult.ok("Pressed ${action.name}")
        override suspend fun dialogAction(buttonText: String?, role: DialogButtonRole?): ActionResult = ActionResult.ok("Dialog action")
        override suspend fun openMenu(menu: MenuType): ActionResult = ActionResult.ok("Opened menu")
        override suspend fun selectTab(label: String): ActionResult = ActionResult.ok("Selected $label")
        override suspend fun setToggle(label: String?, nodeId: String?, value: Boolean): ActionResult = ActionResult.ok("Toggle $value")
        override suspend fun expandCollapse(label: String?, nodeId: String?, expanded: Boolean): ActionResult = ActionResult.ok("Expanded $expanded")
        override suspend fun setSlider(label: String?, nodeId: String?, value: Float?, percent: Int?): ActionResult = ActionResult.ok("Set slider")
        override suspend fun refresh(targetNodeId: String?): ActionResult = ActionResult.ok("Refreshed")
        override suspend fun findTextOnScreen(text: String, tapOnMatch: Boolean): ActionResult = ActionResult.ok("Found $text")
        override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): ActionResult = ActionResult.ok("Swiped")
        override suspend fun typeText(text: String, clear: Boolean): ActionResult {
            if (clear) {
                val state = fields.getValue(focusedNodeId)
                state.text = text
                state.selectionStart = text.length
                state.selectionEnd = text.length
                return ActionResult.ok("Typed text")
            }
            return inputTextAtCurrentCursor(text)
        }

        override suspend fun inputTextAtCurrentCursor(text: String): ActionResult {
            val state = fields.getValue(focusedNodeId)
            val start = state.selectionStart.coerceIn(0, state.text.length)
            val end = state.selectionEnd.coerceIn(start, state.text.length)
            state.text = state.text.substring(0, start) + text + state.text.substring(end)
            val cursor = start + text.length
            state.selectionStart = cursor
            state.selectionEnd = cursor
            return ActionResult.ok("Inserted text at cursor")
        }

        override suspend fun sendKeyCode(keyCode: Int): ActionResult =
            if (keyCode == KeyEvent.KEYCODE_DEL) inputTextAtCurrentCursor("") else ActionResult.ok("Sent key")

        override suspend fun pressBack(): ActionResult = ActionResult.ok("Pressed back")
        override suspend fun pressHome(): ActionResult = ActionResult.ok("Pressed home")
        override suspend fun openNotifications(): ActionResult = ActionResult.ok("Opened notifications")
        override suspend fun openQuickSettings(): ActionResult = ActionResult.ok("Opened quick settings")
        override suspend fun openRecents(): ActionResult = ActionResult.ok("Opened recents")
        override suspend fun openUrl(url: String): ActionResult = ActionResult.ok("Opened $url")
        override suspend fun openDeepLink(uri: String): ActionResult = ActionResult.ok("Opened $uri")
        override suspend fun takeScreenshot(): ScreenshotResult = ScreenshotResult(success = false, message = "Fake screenshot unavailable")
        override suspend fun findFocusedEditableNode(): EditableTarget? = editableTarget(focusedNodeId)
        override suspend fun findEditableNodes(): List<EditableTarget> = fields.keys.mapNotNull(::editableTarget)
        override suspend fun getNodeText(nodeId: String): String? = fields[nodeId]?.text
        override suspend fun getNodeSelection(nodeId: String): Pair<Int, Int>? = fields[nodeId]?.let { it.selectionStart to it.selectionEnd }
        override suspend fun performSetSelection(nodeId: String, start: Int, end: Int): ActionResult {
            val state = fields[nodeId] ?: return ActionResult.fail("Missing editable", "NODE_NOT_FOUND")
            state.selectionStart = start.coerceIn(0, state.text.length)
            state.selectionEnd = end.coerceIn(state.selectionStart, state.text.length)
            return ActionResult.ok("Set selection")
        }

        override suspend fun performSetText(nodeId: String, text: String): ActionResult {
            val state = fields[nodeId] ?: return ActionResult.fail("Missing editable", "NODE_NOT_FOUND")
            state.text = text
            state.selectionStart = text.length
            state.selectionEnd = text.length
            return ActionResult.ok("Set text")
        }

        private fun focusEditable(nodeId: String): ActionResult {
            if (!fields.containsKey(nodeId)) return ActionResult.fail("Missing editable", "NODE_NOT_FOUND")
            focusedNodeId = nodeId
            return ActionResult.ok("Focused $nodeId")
        }

        private fun editableTarget(nodeId: String): EditableTarget? {
            if (!fields.containsKey(nodeId)) return null
            return EditableTarget(
                nodeId = nodeId,
                packageName = "ai.droidlm.knownissues",
                className = "android.widget.EditText",
                bounds = Rect(0, 0, 600, 160),
                isFocused = nodeId == focusedNodeId,
                isEditable = true,
                supportsSetText = true,
                supportsSetSelection = true,
                supportsKeyboardInput = true
            )
        }

        private data class EditableState(var text: String, var selectionStart: Int, var selectionEnd: Int)
    }

    private class FakeOcrEngine : OcrEngine {
        override suspend fun recognize(bitmap: Bitmap, deviceContext: ai.droidlm.relay.DeviceContext?): OcrResult =
            OcrResult(fullText = "", blocks = emptyList(), lines = emptyList(), elements = emptyList(), symbols = emptyList(), source = OcrSource.ML_KIT_ON_DEVICE)
    }
}
