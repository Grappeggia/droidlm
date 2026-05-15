package ai.droidlm.execution

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.intent.displayName
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.ScreenshotResult
import ai.droidlm.portal.UiNode
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.textedit.EditableTarget
import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExecutionAdaptiveGoalRunnerTest {
    @Test fun openMissingAppCanInstallMonitorAndOpenFromSingleGoal() = runTest {
        var installed = false
        val portal = FakePortal()
        val actions = mutableListOf<String>()
        val confirmations = mutableListOf<String>()
        val runner = runner(
            portal = portal,
            packageLoader = { if (installed) listOf(docsPackage()) else emptyList() },
            executeAction = { action, _, _, _ ->
                actions += action.displayName()
                when (action) {
                    is DroidLmAction.OpenAppStoreListing -> {
                        portal.activePackage = ExecutionAdaptiveGoalRunner.PLAY_STORE_PACKAGE
                        portal.visibleTexts = listOf("Install")
                        ActionResult.ok("Opened Play Store listing for Google Docs")
                    }
                    is DroidLmAction.WaitForUi -> {
                        if (portal.visibleTexts.any { it.contains(action.text.orEmpty(), ignoreCase = true) }) {
                            ActionResult.ok("UI became ready")
                        } else {
                            ActionResult.fail("missing", "WAIT_FOR_UI_TIMEOUT")
                        }
                    }
                    is DroidLmAction.TapText -> {
                        assertEquals("Install", action.text)
                        portal.visibleTexts = listOf("Installing", "Cancel")
                        ActionResult.ok("Tapped Install")
                    }
                    else -> ActionResult.fail("unexpected ${action.displayName()}", "UNEXPECTED_ACTION")
                }
            },
            requestConfirmation = { _, _, _, _, prompt ->
                confirmations += prompt.orEmpty()
                true
            },
            runInstallMonitor = { target, _, _ ->
                assertEquals(DOCS_PACKAGE, target.packageName)
                assertTrue(target.openWhenInstalled)
                installed = true
                portal.activePackage = DOCS_PACKAGE
                portal.visibleTexts = emptyList()
                ActionResult.ok("Installed and opened Google Docs")
            }
        )

        val result = runner.runIfSupported(
            DroidLmAction.OpenAppStoreListing("Google Docs", DOCS_PACKAGE, "missing app recovery"),
            transcript = "open google docs",
            diagnosticSessionId = "session"
        )

        assertTrue(result!!.success)
        assertEquals("Installed and opened Google Docs", result.message)
        assertEquals(
            listOf("OPEN_APP_STORE_LISTING Google Docs", "WAIT_FOR_UI", "TAP_TEXT Install"),
            actions
        )
        assertEquals(listOf("Install Google Docs from Play Store and open it when it finishes?"), confirmations)
    }

    @Test fun installedOpenAppGoalFinishesWithoutInstallRecovery() = runTest {
        val portal = FakePortal()
        val actions = mutableListOf<String>()
        val runner = runner(
            portal = portal,
            packageLoader = { listOf(docsPackage()) },
            executeAction = { action, _, _, _ ->
                actions += action.displayName()
                when (action) {
                    is DroidLmAction.OpenApp -> {
                        portal.activePackage = DOCS_PACKAGE
                        ActionResult.ok("Launched $DOCS_PACKAGE")
                    }
                    else -> ActionResult.fail("unexpected ${action.displayName()}", "UNEXPECTED_ACTION")
                }
            },
            requestConfirmation = { _, _, _, _, _ -> error("No confirmation expected") },
            runInstallMonitor = { _, _, _ -> error("Install monitor should not run") }
        )

        val result = runner.runIfSupported(
            DroidLmAction.OpenApp("Google Docs", DOCS_PACKAGE, "open docs"),
            transcript = "open google docs",
            diagnosticSessionId = "session"
        )

        assertTrue(result!!.success)
        assertEquals("Google Docs is open", result.message)
        assertEquals(listOf("OPEN_APP Google Docs"), actions)
    }

    @Test fun openAppSuccessDoesNotFailWhenAccessibilityStateUnavailable() = runTest {
        val portal = FakePortal().apply { activePackage = null }
        val actions = mutableListOf<String>()
        val runner = runner(
            portal = portal,
            packageLoader = { listOf(docsPackage()) },
            executeAction = { action, _, _, _ ->
                actions += action.displayName()
                when (action) {
                    is DroidLmAction.OpenApp -> ActionResult.ok("Launched $DOCS_PACKAGE")
                    else -> ActionResult.fail("unexpected ${action.displayName()}", "UNEXPECTED_ACTION")
                }
            },
            requestConfirmation = { _, _, _, _, _ -> error("No confirmation expected") },
            runInstallMonitor = { _, _, _ -> error("Install monitor should not run") }
        )

        val result = runner.runIfSupported(
            DroidLmAction.OpenApp("Google Docs", DOCS_PACKAGE, "open docs"),
            transcript = "open google docs",
            diagnosticSessionId = "session"
        )

        assertTrue(result!!.success)
        assertEquals("Launched $DOCS_PACKAGE", result.message)
        assertEquals(listOf("OPEN_APP Google Docs"), actions)
    }

    @Test fun goalVerifierDoesNotTreatPlayStoreAsOpenAppSuccess() {
        val verification = ExecutionGoalVerifier().verify(
            ExecutionGoal.OpenApp("Google Docs", DOCS_PACKAGE, allowInstallRecovery = true),
            PortalState(ExecutionAdaptiveGoalRunner.PLAY_STORE_PACKAGE, null, 100, 100, emptyList()),
            emptyList()
        )

        assertFalse(verification.complete)
        assertEquals(ExecutionAdaptiveGoalRunner.PLAY_STORE_PACKAGE, verification.actual)
    }

    private fun runner(
        portal: FakePortal,
        packageLoader: () -> List<AppPackage>,
        executeAction: suspend (DroidLmAction, String, Boolean, String?) -> ActionResult,
        requestConfirmation: suspend (String, DroidLmAction, String, String?, String?) -> Boolean,
        runInstallMonitor: suspend (InstallMonitorTarget, String, String?) -> ActionResult
    ): ExecutionAdaptiveGoalRunner {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logs = ActionLogRepository()
        val settingsRepository = SettingsRepository(context)
        val diagnostics = SpeechDiagnosticsLogger(context, settingsRepository, logs)
        val appInventory = AppInventoryRepository(context, packageLoader = packageLoader)
        return ExecutionAdaptiveGoalRunner(
            appInventoryRepository = appInventory,
            portalController = portal,
            logs = logs,
            uiState = MutableStateFlow(ExecutionUiState()),
            executionDiagnostics = ExecutionDiagnostics(diagnostics, portal),
            goalVerifier = ExecutionGoalVerifier(),
            cancellationResult = { null },
            finish = { it },
            executeAction = executeAction,
            requestConfirmation = requestConfirmation,
            runInstallMonitor = runInstallMonitor
        )
    }

    private class FakePortal : PortalController {
        var activePackage: String? = "launcher"
        var visibleTexts: List<String> = emptyList()
        private val target = EditableTarget("node", "pkg", "EditText", Rect(0, 0, 200, 80), true, true, true, true, true)

        override suspend fun isAccessibilityEnabled() = true
        override suspend fun getState() = PortalState(activePackage, null, 100, 100, visibleTexts.map { text -> visibleNode(text) })
        override suspend fun getFullState() = getState()
        override suspend fun listPackages(): List<AppPackage> = emptyList()
        override suspend fun openApp(packageName: String) = ActionResult.ok()
        override suspend fun openAppStoreListing(packageName: String, appName: String?) = ActionResult.ok()
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

        private fun visibleNode(text: String) = UiNode(
            nodeId = null,
            text = text,
            contentDescription = null,
            className = "TextView",
            packageName = activePackage,
            bounds = null,
            clickable = true,
            editable = false,
            focused = false,
            enabled = true,
            selected = false
        )
    }

    private companion object {
        const val DOCS_PACKAGE = "com.google.android.apps.docs.editors.docs"

        fun docsPackage() = AppPackage(
            packageName = DOCS_PACKAGE,
            label = "Google Docs",
            enabled = true,
            launchable = true
        )
    }
}
