package ai.droidlm.execution

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.context.DeviceContextAggregator
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.fileops.WorkspaceFileOperationController
import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.ocr.OcrResult
import ai.droidlm.ocr.OcrSource
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.ScreenshotResult
import ai.droidlm.relay.DeviceContext
import ai.droidlm.relay.RelayClient
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.textedit.EditableTarget
import ai.droidlm.textedit.TextEditingController
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExecutionActionRunnerTest {
    @Test fun switchAppPrefersExplicitPackage() = runTest {
        val portal = FakePortal()
        val runner = runner(portal)

        val result = runner.execute(
            DroidLmAction.SwitchApp(appName = "Docs", packageName = "com.google.android.apps.docs", reason = "switch"),
            transcript = "switch to docs",
            finishState = false
        )

        assertTrue(result.success)
        assertEquals(listOf("openApp:com.google.android.apps.docs"), portal.operations)
    }

    @Test fun openAppRecoveryRequestsStoreConfirmation() = runTest {
        val portal = FakePortal(openAppResult = ActionResult.fail("Missing", "APP_NOT_INSTALLED"))
        val requests = mutableListOf<DroidLmAction>()
        val prompts = mutableListOf<String?>()
        val runner = runner(portal) { _, action, _, _, promptOverride ->
            requests += action
            prompts += promptOverride
            true
        }

        val result = runner.execute(
            DroidLmAction.OpenApp(appName = "Docs", packageName = "com.google.android.apps.docs", reason = "open"),
            transcript = "open docs",
            finishState = false,
            diagnosticSessionId = "session"
        )

        assertTrue(result.success)
        assertEquals(
            listOf("openApp:com.google.android.apps.docs", "openStore:com.google.android.apps.docs:Docs"),
            portal.operations
        )
        assertTrue(requests.single() is DroidLmAction.OpenAppStoreListing)
        assertEquals("Docs is not installed or cannot be launched. Open its Play Store listing?", prompts.single())
    }

    private fun runner(
        portal: FakePortal,
        confirm: suspend (String, DroidLmAction, String, String?, String?) -> Boolean = { _, _, _, _, _ -> false }
    ): ExecutionActionRunner {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepository = SettingsRepository(context)
        val logs = ActionLogRepository()
        val diagnostics = SpeechDiagnosticsLogger(context, settingsRepository, logs)
        val textEditingController = TextEditingController(
            portal,
            object : OcrEngine {
                override suspend fun recognize(bitmap: Bitmap, deviceContext: DeviceContext?) =
                    OcrResult("", emptyList(), emptyList(), emptyList(), emptyList(), OcrSource.ML_KIT_ON_DEVICE)
            },
            RelayClient(),
            logs
        )
        val appInventoryRepository = AppInventoryRepository(context, packageLoader = { emptyList() })
        return ExecutionActionRunner(
            settingsRepository = settingsRepository,
            portalController = portal,
            textEditingController = textEditingController,
            workspaceFileOperationController = WorkspaceFileOperationController(context, textEditingController, logs),
            ocrEngine = object : OcrEngine {
                override suspend fun recognize(bitmap: Bitmap, deviceContext: DeviceContext?) =
                    OcrResult("", emptyList(), emptyList(), emptyList(), emptyList(), OcrSource.ML_KIT_ON_DEVICE)
            },
            deviceContextAggregator = DeviceContextAggregator(appInventoryRepository, emptyList(), diagnostics),
            logs = logs,
            diagnostics = diagnostics,
            debugLogStore = null,
            cloudScreenshotAnalyzer = null,
            uiState = MutableStateFlow(ExecutionUiState()),
            executionDiagnostics = ExecutionDiagnostics(diagnostics, portal),
            cancellationResult = { null },
            finish = { it },
            requestConfirmation = confirm,
            handlePlanning = { _, _ -> ActionResult.fail("planning not under test", "PLANNING_NOT_TESTED") }
        )
    }

    private class FakePortal(
        private val openAppResult: ActionResult = ActionResult.ok("Opened")
    ) : PortalController {
        val operations = mutableListOf<String>()
        private val target = EditableTarget("node", "pkg", "EditText", Rect(0, 0, 200, 80), true, true, true, true, true)

        override suspend fun isAccessibilityEnabled() = true
        override suspend fun getState() = PortalState("pkg", null, 100, 100, emptyList())
        override suspend fun getFullState() = getState()
        override suspend fun listPackages(): List<AppPackage> = emptyList()
        override suspend fun openApp(packageName: String): ActionResult {
            operations += "openApp:$packageName"
            return openAppResult
        }
        override suspend fun openAppStoreListing(packageName: String, appName: String?): ActionResult {
            operations += "openStore:$packageName:$appName"
            return ActionResult.ok("store")
        }
        override suspend fun openSettings() = ActionResult.ok()
        override suspend fun tap(x: Int, y: Int) = ActionResult.ok()
        override suspend fun tapNode(nodeId: String) = ActionResult.ok()
        override suspend fun focusNode(nodeId: String) = ActionResult.ok()
        override suspend fun longPress(x: Int, y: Int, durationMs: Int) = ActionResult.ok()
        override suspend fun tapText(text: String, role: String?, containerNodeId: String?) = ActionResult.ok()
        override suspend fun longPressNode(nodeId: String?, text: String?, durationMs: Int) = ActionResult.ok()
        override suspend fun scroll(direction: ScrollDirection, targetNodeId: String?, untilText: String?) = ActionResult.ok()
        override suspend fun waitForUi(text: String?, packageName: String?, nodeId: String?, timeoutMs: Int) = ActionResult.ok()
        override suspend fun pressImeAction(action: ImeActionType) = ActionResult.ok()
        override suspend fun dialogAction(buttonText: String?, role: DialogButtonRole?) = ActionResult.ok()
        override suspend fun openMenu(menu: MenuType) = ActionResult.ok()
        override suspend fun selectTab(label: String) = ActionResult.ok()
        override suspend fun setToggle(label: String?, nodeId: String?, value: Boolean) = ActionResult.ok()
        override suspend fun expandCollapse(label: String?, nodeId: String?, expanded: Boolean) = ActionResult.ok()
        override suspend fun setSlider(label: String?, nodeId: String?, value: Float?, percent: Int?) = ActionResult.ok()
        override suspend fun refresh(targetNodeId: String?) = ActionResult.ok()
        override suspend fun findTextOnScreen(text: String, tapOnMatch: Boolean) = ActionResult.ok()
        override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int) = ActionResult.ok()
        override suspend fun typeText(text: String, clear: Boolean) = ActionResult.ok()
        override suspend fun inputTextAtCurrentCursor(text: String) = ActionResult.ok()
        override suspend fun sendKeyCode(keyCode: Int) = ActionResult.ok()
        override suspend fun pressBack() = ActionResult.ok()
        override suspend fun pressHome() = ActionResult.ok()
        override suspend fun openNotifications() = ActionResult.ok()
        override suspend fun openQuickSettings() = ActionResult.ok()
        override suspend fun openRecents() = ActionResult.ok()
        override suspend fun openUrl(url: String) = ActionResult.ok()
        override suspend fun openDeepLink(uri: String) = ActionResult.ok()
        override suspend fun takeScreenshot() = ScreenshotResult(false, message = "unused")
        override suspend fun findFocusedEditableNode() = target
        override suspend fun findEditableNodes() = listOf(target)
        override suspend fun getNodeText(nodeId: String) = ""
        override suspend fun getNodeSelection(nodeId: String) = 0 to 0
        override suspend fun performSetSelection(nodeId: String, start: Int, end: Int) = ActionResult.ok()
        override suspend fun performSetText(nodeId: String, text: String) = ActionResult.ok()
    }
}
