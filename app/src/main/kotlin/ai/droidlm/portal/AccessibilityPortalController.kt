package ai.droidlm.portal

import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.textedit.EditableTarget
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccessibilityPortalController(
    private val context: Context,
    private val logs: ActionLogRepository
) : PortalController {
    override suspend fun isAccessibilityEnabled(): Boolean = withContext(Dispatchers.Main) {
        DroidLMAccessibilityService.current() != null || isServiceEnabledInSettings()
    }

    override suspend fun getState(): PortalState = withContext(Dispatchers.Main) {
        DroidLMAccessibilityService.current()?.captureState(includeAllWindows = false)
            ?: PortalState(null, null, null, null, emptyList())
    }

    override suspend fun getFullState(): PortalState? = withContext(Dispatchers.Main) {
        DroidLMAccessibilityService.current()?.captureState(includeAllWindows = true)
    }

    @Suppress("DEPRECATION")
    override suspend fun listPackages(): List<AppPackage> = withContext(Dispatchers.Default) {
        context.packageManager.getInstalledApplications(0)
            .map { info ->
                AppPackage(
                    packageName = info.packageName,
                    label = runCatching { context.packageManager.getApplicationLabel(info).toString() }.getOrNull(),
                    isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedWith(compareBy<AppPackage> { it.label?.lowercase().orEmpty() }.thenBy { it.packageName })
    }

    override suspend fun openApp(packageName: String): ActionResult = withContext(Dispatchers.Main) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            ActionResult.fail("No launchable activity found for $packageName", "APP_NOT_FOUND")
        } else {
            runCatching {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }.fold(
                onSuccess = {
                    logs.log(ActionLogType.ACTION_RESULT, "Launched $packageName")
                    ActionResult.ok("Launched $packageName")
                },
                onFailure = { ActionResult.fail("Failed to launch $packageName: ${it.message}", "LAUNCH_FAILED") }
            )
        }
    }

    override suspend fun openSettings(): ActionResult = withContext(Dispatchers.Main) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.fold(
            onSuccess = { ActionResult.ok("Opened Android Settings") },
            onFailure = { ActionResult.fail("Failed to open settings: ${it.message}", "OPEN_SETTINGS_FAILED") }
        )
    }

    override suspend fun tap(x: Int, y: Int): ActionResult = withService { it.tap(x, y) }
    override suspend fun tapNode(nodeId: String): ActionResult = withService { it.tapNode(nodeId) }
    override suspend fun tapText(text: String, role: String?, containerNodeId: String?): ActionResult = withService {
        it.tapText(text, role, containerNodeId)
    }

    override suspend fun focusNode(nodeId: String): ActionResult = withService { it.focusNode(nodeId) }

    override suspend fun longPressNode(nodeId: String?, text: String?, durationMs: Int): ActionResult = withService {
        it.longPressNode(nodeId, text, durationMs)
    }

    override suspend fun scroll(direction: ScrollDirection, targetNodeId: String?, untilText: String?): ActionResult = withService {
        it.scroll(direction, targetNodeId, untilText)
    }

    override suspend fun waitForUi(text: String?, packageName: String?, nodeId: String?, timeoutMs: Int): ActionResult = withService {
        it.waitForUi(text, packageName, nodeId, timeoutMs)
    }

    override suspend fun pressImeAction(action: ImeActionType): ActionResult = withService { it.pressImeAction(action) }

    override suspend fun dialogAction(buttonText: String?, role: DialogButtonRole?): ActionResult = withService {
        it.dialogAction(buttonText, role)
    }

    override suspend fun openMenu(menu: MenuType): ActionResult = withService { it.openMenu(menu) }

    override suspend fun selectTab(label: String): ActionResult = withService { it.selectTab(label) }

    override suspend fun setToggle(label: String?, nodeId: String?, value: Boolean): ActionResult = withService {
        it.setToggle(label, nodeId, value)
    }

    override suspend fun expandCollapse(label: String?, nodeId: String?, expanded: Boolean): ActionResult = withService {
        it.expandCollapse(label, nodeId, expanded)
    }

    override suspend fun setSlider(label: String?, nodeId: String?, value: Float?, percent: Int?): ActionResult = withService {
        it.setSlider(label, nodeId, value, percent)
    }

    override suspend fun refresh(targetNodeId: String?): ActionResult = withService { it.refresh(targetNodeId) }

    override suspend fun findTextOnScreen(text: String, tapOnMatch: Boolean): ActionResult = withService {
        it.findTextOnScreen(text, tapOnMatch)
    }


    override suspend fun longPress(x: Int, y: Int, durationMs: Int): ActionResult = withService {
        it.longPress(x, y, durationMs)
    }

    override suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int
    ): ActionResult = withService { it.swipe(startX, startY, endX, endY, durationMs) }

    override suspend fun typeText(text: String, clear: Boolean): ActionResult = withService {
        if (clear) it.setFocusedText(text) else it.inputTextAtCurrentCursor(text)
    }

    override suspend fun inputTextAtCurrentCursor(text: String): ActionResult = typeText(text, clear = false)

    override suspend fun sendKeyCode(keyCode: Int): ActionResult = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> pressBack()
        KeyEvent.KEYCODE_HOME -> pressHome()
        else -> ActionResult.fail("Key code injection is not supported for $keyCode", "UNSUPPORTED_KEYCODE")
    }

    override suspend fun pressBack(): ActionResult = withService { it.performGlobalBack() }
    override suspend fun pressHome(): ActionResult = withService { it.performGlobalHome() }
    override suspend fun openNotifications(): ActionResult = withService { it.performGlobalNotifications() }
    override suspend fun openQuickSettings(): ActionResult = withService { it.performGlobalQuickSettings() }
    override suspend fun openRecents(): ActionResult = withService { it.performGlobalRecents() }
    override suspend fun openUrl(url: String): ActionResult = openViewIntent(url, "Opened $url")
    override suspend fun openDeepLink(uri: String): ActionResult = openViewIntent(uri, "Opened app link")
    override suspend fun takeScreenshot(): ScreenshotResult = withServiceScreenshot { it.takeScreenshotBitmap() }

    override suspend fun findFocusedEditableNode(): EditableTarget? = withContext(Dispatchers.Main) {
        DroidLMAccessibilityService.current()?.findFocusedEditableTarget()
    }

    override suspend fun findEditableNodes(): List<EditableTarget> = withContext(Dispatchers.Main) {
        DroidLMAccessibilityService.current()?.findEditableTargets().orEmpty()
    }

    override suspend fun getNodeText(nodeId: String): String? = withContext(Dispatchers.Main) {
        DroidLMAccessibilityService.current()?.getNodeText(nodeId)
    }

    override suspend fun getNodeSelection(nodeId: String): Pair<Int, Int>? = withContext(Dispatchers.Main) {
        DroidLMAccessibilityService.current()?.getNodeSelection(nodeId)
    }

    override suspend fun performSetSelection(nodeId: String, start: Int, end: Int): ActionResult = withService {
        it.performSetSelection(nodeId, start, end)
    }

    override suspend fun performSetText(nodeId: String, text: String): ActionResult = withService {
        it.performSetText(nodeId, text)
    }

    private suspend fun openViewIntent(uri: String, successMessage: String): ActionResult = withContext(Dispatchers.Main) {
        val normalized = uri.trim()
        if (normalized.isBlank()) return@withContext ActionResult.fail("URL is blank", "INVALID_URI")
        runCatching {
            val parsed = if (normalized.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*"))) Uri.parse(normalized) else Uri.parse("https://$normalized")
            context.startActivity(Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.fold(
            onSuccess = { ActionResult.ok(successMessage) },
            onFailure = { ActionResult.fail("Failed to open $normalized: ${it.message}", "OPEN_VIEW_FAILED") }
        )
    }

    private suspend fun withService(block: suspend (DroidLMAccessibilityService) -> ActionResult): ActionResult =
        withContext(Dispatchers.Main) {
            val service = DroidLMAccessibilityService.current()
            if (service == null) ActionResult.fail("Accessibility service is not enabled", "ACCESSIBILITY_DISABLED")
            else block(service)
        }

    private suspend fun withServiceScreenshot(block: suspend (DroidLMAccessibilityService) -> ScreenshotResult): ScreenshotResult =
        withContext(Dispatchers.Main) {
            val service = DroidLMAccessibilityService.current()
            if (service == null) ScreenshotResult(false, message = "Accessibility service is not enabled", errorCode = "ACCESSIBILITY_DISABLED")
            else block(service)
        }

    private fun isServiceEnabledInSettings(): Boolean {
        val expectedSuffix = "${context.packageName}/${DroidLMAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expectedSuffix, ignoreCase = true) || it.endsWith(DroidLMAccessibilityService::class.java.name) }
    }
}
