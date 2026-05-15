package ai.droidlm.execution

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.ScreenshotResult
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.textedit.EditableTarget
import android.content.Context
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
class ExecutionInstallMonitorRunnerTest {
    @Test fun opensTargetAfterInstallBecomesLaunchable() = runTest {
        var appQueries = 0
        var now = 0L
        val portal = FakePortal()
        val runner = runner(
            packageLoader = {
                appQueries += 1
                if (appQueries >= 2) listOf(docsPackage()) else emptyList()
            },
            portal = portal,
            nowProvider = { now },
            delayProvider = { now += it }
        )

        val result = runner.run(
            InstallMonitorTarget(
                appName = "Google Docs",
                packageName = DOCS_PACKAGE,
                sourceTranscript = "open google docs",
                openWhenInstalled = true
            ),
            transcript = "wait until it's finished",
            diagnosticSessionId = "session"
        )

        assertTrue(result.success)
        assertEquals("Installed and opened Google Docs", result.message)
        assertEquals(listOf("openApp:$DOCS_PACKAGE"), portal.operations)
    }

    @Test fun canOnlyWaitForInstallWithoutOpening() = runTest {
        var now = 0L
        val portal = FakePortal()
        val runner = runner(
            packageLoader = { listOf(docsPackage()) },
            portal = portal,
            nowProvider = { now },
            delayProvider = { now += it }
        )

        val result = runner.run(
            InstallMonitorTarget(
                appName = "Google Docs",
                packageName = DOCS_PACKAGE,
                sourceTranscript = "install google docs",
                openWhenInstalled = false
            ),
            transcript = "wait until it's finished",
            diagnosticSessionId = "session"
        )

        assertTrue(result.success)
        assertEquals("Google Docs finished installing", result.message)
        assertTrue(portal.operations.isEmpty())
    }

    private fun runner(
        packageLoader: () -> List<AppPackage>,
        portal: FakePortal,
        nowProvider: () -> Long,
        delayProvider: suspend (Long) -> Unit
    ): ExecutionInstallMonitorRunner {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logs = ActionLogRepository()
        val settingsRepository = SettingsRepository(context)
        val diagnostics = SpeechDiagnosticsLogger(context, settingsRepository, logs)
        val appInventory = AppInventoryRepository(context, nowProvider = nowProvider, packageLoader = packageLoader)
        return ExecutionInstallMonitorRunner(
            appInventoryRepository = appInventory,
            portalController = portal,
            logs = logs,
            uiState = MutableStateFlow(ExecutionUiState()),
            executionDiagnostics = ExecutionDiagnostics(diagnostics, portal),
            cancellationResult = { null },
            finish = { it },
            nowProvider = nowProvider,
            delayProvider = delayProvider
        )
    }

    private class FakePortal : PortalController {
        val operations = mutableListOf<String>()
        private var activePackage = "com.android.vending"
        private val target = EditableTarget("node", "pkg", "EditText", Rect(0, 0, 200, 80), true, true, true, true, true)

        override suspend fun isAccessibilityEnabled() = true
        override suspend fun getState() = PortalState(activePackage, null, 100, 100, emptyList())
        override suspend fun getFullState() = getState()
        override suspend fun listPackages(): List<AppPackage> = emptyList()
        override suspend fun openApp(packageName: String): ActionResult {
            operations += "openApp:$packageName"
            activePackage = packageName
            return ActionResult.ok("Opened $packageName")
        }
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
