package ai.droidlm.portal
import ai.droidlm.di.appGraph

import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.textedit.EditableTarget
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import android.text.InputType
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class DroidLMAccessibilityService : AccessibilityService() {
    private val deps by lazy { applicationContext.appGraph().accessibilityServiceDeps() }
    private val nodeCache = LinkedHashMap<String, AccessibilityNodeInfo>()
    private var accessibilityEventCount = 0L
    private var lastAccessibilityEventLogAtMs = 0L
    private var accessibilityRegistrationToken: Long? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        accessibilityRegistrationToken = deps.accessibilityRuntime.attach(DroidLMAccessibilityGateway(this))
        recordLifecycle("accessibility_service_connected", mapOf("sdk" to Build.VERSION.SDK_INT))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        accessibilityEventCount += 1
        val now = SystemClock.elapsedRealtime()
        val eventSnapshot = deps.accessibilityRuntime.recordAccessibilityEvent(
            eventType = event?.eventType,
            packageName = event?.packageName?.toString(),
            className = event?.className?.toString(),
            contentChangeTypes = event?.contentChangeTypesCompat(),
            windowChangeTypes = event?.windowChangeTypesCompat(),
            eventTimeMs = event?.eventTime,
            observedAtElapsedMs = now
        )
        if (accessibilityEventCount == 1L || accessibilityEventCount % 25L == 0L || now - lastAccessibilityEventLogAtMs >= 5_000L) {
            lastAccessibilityEventLogAtMs = now
            recordLifecycle(
                "accessibility_event_observed",
                mapOf(
                    "count" to accessibilityEventCount,
                    "sequence" to eventSnapshot.sequence,
                    "type" to eventSnapshot.eventType,
                    "package" to eventSnapshot.packageName,
                    "className" to eventSnapshot.className,
                    "contentChangeTypes" to eventSnapshot.contentChangeTypes,
                    "windowChangeTypes" to eventSnapshot.windowChangeTypes,
                    "textCount" to (event?.text?.size ?: 0),
                    "quietForMs" to deps.accessibilityRuntime.eventState.value.quietForMs(now)
                )
            )
        }
    }

    override fun onInterrupt() {
        recordLifecycle("accessibility_service_interrupted")
    }

    override fun onDestroy() {
        accessibilityRegistrationToken?.let { deps.accessibilityRuntime.detach(it) }
        accessibilityRegistrationToken = null
        recordLifecycle("accessibility_service_destroyed", mapOf("cachedNodeCount" to nodeCache.size, "eventCount" to accessibilityEventCount))
        nodeCache.clear()
        super.onDestroy()
    }

    private fun recordLifecycle(event: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching { deps.speechDiagnosticsLogger.record(null, event, fields) }
    }

    private fun AccessibilityEvent.contentChangeTypesCompat(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) contentChangeTypes else null

    private fun AccessibilityEvent.windowChangeTypesCompat(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) windowChanges else null

    fun captureState(includeAllWindows: Boolean): PortalState {
        val metrics = resources.displayMetrics
        val roots = if (includeAllWindows && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            windows.mapNotNull { it.root }
        } else {
            listOfNotNull(rootInActiveWindow)
        }
        nodeCache.clear()
        val nodes = roots.flatMapIndexed { index, root -> collectNodes(root, "w$index") }
        return PortalState(
            packageName = roots.firstOrNull()?.packageName?.toString(),
            activityName = null,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            nodes = nodes
        )
    }

    suspend fun tap(x: Int, y: Int): ActionResult = dispatchPathGesture(
        path = Path().apply { moveTo(x.toFloat(), y.toFloat()) },
        durationMs = 80,
        message = "Tapped $x,$y"
    )

    suspend fun tapNode(nodeId: String): ActionResult {
        refreshNodeCache()
        val node = nodeCache[nodeId] ?: return ActionResult.fail("Node is no longer available: $nodeId", "NODE_NOT_FOUND")
        val clickTarget = tapCandidates(node).firstOrNull { candidate ->
            hasAction(candidate, AccessibilityNodeInfo.ACTION_CLICK) && candidate.isEnabled && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (clickTarget != null) {
            val suffix = if (clickTarget === node) "" else " via nearest tappable parent"
            return ActionResult.ok("Clicked node $nodeId$suffix")
        }
        val rect = tapCandidates(node).firstNotNullOfOrNull { candidate ->
            val bounds = tappableBounds(candidate)
            if (bounds != null && (candidate === node || isTappableNode(candidate))) bounds else null
        }
        if (rect == null) return ActionResult.fail("Node has no tappable bounds: $nodeId", "NODE_BOUNDS_MISSING")
        return tap(rect.centerX(), rect.centerY())
    }

    fun focusNode(nodeId: String): ActionResult {
        refreshNodeCache()
        val node = nodeCache[nodeId] ?: return ActionResult.fail("Node is no longer available: $nodeId", "NODE_NOT_FOUND")
        return if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            ActionResult.ok("Focused node $nodeId")
        } else {
            ActionResult.fail("Node did not accept focus: $nodeId", "FOCUS_NODE_FAILED")
        }
    }


    suspend fun longPress(x: Int, y: Int, durationMs: Int): ActionResult = dispatchPathGesture(
        path = Path().apply { moveTo(x.toFloat(), y.toFloat()) },
        durationMs = durationMs.toLong(),
        message = "Long pressed $x,$y"
    )

    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): ActionResult {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        return dispatchPathGesture(path, durationMs.toLong(), "Swiped from $startX,$startY to $endX,$endY")
    }

    suspend fun tapText(text: String, role: String? = null, containerNodeId: String? = null): ActionResult {
        refreshNodeCache()
        val match = findBestMatchingNodeId(text, role, containerNodeId)
            ?: return ActionResult.fail("Could not find visible text: $text", "TEXT_NOT_FOUND")
        return tapNode(match)
    }

    suspend fun longPressNode(nodeId: String? = null, text: String? = null, durationMs: Int = 600): ActionResult {
        refreshNodeCache()
        val resolvedNodeId = nodeId
            ?: text?.let { findBestMatchingNodeId(it, role = null, containerNodeId = null) }
            ?: return ActionResult.fail("Long press target was not provided", "LONG_PRESS_TARGET_MISSING")
        val node = nodeCache[resolvedNodeId] ?: return ActionResult.fail("Node is no longer available: $resolvedNodeId", "NODE_NOT_FOUND")
        val longClickTarget = tapCandidates(node).firstOrNull { candidate ->
            hasAction(candidate, AccessibilityNodeInfo.ACTION_LONG_CLICK) && candidate.isEnabled && candidate.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        if (longClickTarget != null) {
            val suffix = if (longClickTarget === node) "" else " via actionable parent"
            return ActionResult.ok("Long-clicked ${text ?: resolvedNodeId}$suffix")
        }
        val bounds = tapCandidates(node).firstNotNullOfOrNull { tappableBounds(it) }
            ?: return ActionResult.fail("Node has no tappable bounds: $resolvedNodeId", "NODE_BOUNDS_MISSING")
        return longPress(bounds.centerX(), bounds.centerY(), durationMs)
    }

    suspend fun scroll(direction: ScrollDirection, targetNodeId: String? = null, untilText: String? = null): ActionResult {
        val attempts = if (untilText.isNullOrBlank()) 1 else MAX_SCROLL_ATTEMPTS
        repeat(attempts) { attempt ->
            refreshNodeCache()
            if (!untilText.isNullOrBlank() && findBestMatchingNodeId(untilText, null, null) != null) {
                return ActionResult.ok("Found $untilText after scrolling")
            }
            val scrollTarget = resolveScrollTarget(targetNodeId)
            val result = if (scrollTarget != null) {
                performScroll(scrollTarget, direction)
            } else {
                performViewportScroll(direction, label = "gesture fallback")
            }
            if (!result.success) return result
            if (untilText.isNullOrBlank()) return result
            if (attempt < attempts - 1) delay(SCROLL_RETRY_DELAY_MS)
        }
        refreshNodeCache()
        return if (!untilText.isNullOrBlank() && findBestMatchingNodeId(untilText, null, null) != null) {
            ActionResult.ok("Found $untilText after scrolling")
        } else {
            ActionResult.fail("Could not find $untilText after scrolling", "TEXT_NOT_FOUND_AFTER_SCROLL")
        }
    }

    suspend fun waitForUi(text: String? = null, packageName: String? = null, nodeId: String? = null, timeoutMs: Int = 2_500): ActionResult {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt <= timeoutMs) {
            refreshNodeCache()
            val packageMatches = packageName.isNullOrBlank() || rootInActiveWindow?.packageName?.toString() == packageName
            val nodeMatches = nodeId.isNullOrBlank() || nodeCache.containsKey(nodeId)
            val textMatches = text.isNullOrBlank() || findBestMatchingNodeId(text, null, null) != null
            if (packageMatches && nodeMatches && textMatches) {
                return ActionResult.ok("UI became ready")
            }
            delay(WAIT_FOR_UI_POLL_MS)
        }
        return ActionResult.fail("Timed out waiting for the Android UI to update", "WAIT_FOR_UI_TIMEOUT")
    }

    fun pressImeAction(action: ImeActionType): ActionResult {
        refreshNodeCache()
        val focused = nodeCache.values.firstOrNull { it.isFocused && isEditableNode(it) }
            ?: return ActionResult.fail("No focused editable field found", "NO_EDITABLE_FOCUS")
        if (action == ImeActionType.NEXT) {
            val editables = nodeCache.entries.filter { (_, node) -> isEditableNode(node) }
            val currentIndex = editables.indexOfFirst { (_, node) -> node === focused }
            val next = editables.getOrNull(currentIndex + 1)
            if (next != null && next.value.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                return ActionResult.ok("Moved to the next field")
            }
        }
        val imeActionId = optionalAccessibilityActionId("ACTION_IME_ENTER")
        if (imeActionId != null && focused.performAction(imeActionId)) {
            return ActionResult.ok("Pressed ${action.name.lowercase()} on the keyboard")
        }
        return if (inputTextAtCurrentCursor("\n").success) {
            ActionResult.ok("Inserted an enter keypress")
        } else {
            ActionResult.fail("The focused field did not accept the requested keyboard action", "IME_ACTION_FAILED")
        }
    }

    suspend fun dialogAction(buttonText: String? = null, role: DialogButtonRole? = null): ActionResult {
        if (!buttonText.isNullOrBlank()) return tapText(buttonText, role = "button")
        refreshNodeCache()
        if (role == DialogButtonRole.DISMISS) {
            val dismissNode = nodeCache.values.firstOrNull { hasAction(it, DISMISS_ACTION_ID ?: -1) }
            if (dismissNode != null && dismissNode.performAction(DISMISS_ACTION_ID ?: -1)) {
                return ActionResult.ok("Dismissed the dialog")
            }
        }
        val labels = dialogLabels(role)
        return labels.firstNotNullOfOrNull { label ->
            val result = tapText(label, role = "button")
            result.takeIf { it.success }
        } ?: ActionResult.fail("Could not find a matching dialog action", "DIALOG_ACTION_NOT_FOUND")
    }

    suspend fun openMenu(menu: MenuType): ActionResult {
        val labels = when (menu) {
            MenuType.OVERFLOW -> listOf("More options", "More", "Menu")
            MenuType.NAVIGATION_DRAWER -> listOf("Open navigation drawer", "Navigation drawer", "Menu")
            MenuType.CONTEXT -> listOf("More options", "Context menu", "Menu")
        }
        return labels.firstNotNullOfOrNull { label ->
            val result = tapText(label, role = "menu")
            result.takeIf { it.success }
        } ?: ActionResult.fail("Could not find the requested menu", "MENU_NOT_FOUND")
    }

    suspend fun selectTab(label: String): ActionResult {
        refreshNodeCache()
        val match = findBestMatchingNodeId(label, role = "tab", containerNodeId = null)
            ?: return ActionResult.fail("Could not find a visible tab named $label", "TAB_NOT_FOUND")
        val node = nodeCache[match]
        if (node?.isSelected == true) return ActionResult.ok("Tab $label is already selected")
        return tapNode(match)
    }

    suspend fun setToggle(label: String? = null, nodeId: String? = null, value: Boolean): ActionResult {
        refreshNodeCache()
        val resolvedNodeId = nodeId ?: label?.let { findBestMatchingNodeId(it, role = "toggle", containerNodeId = null) }
            ?: return ActionResult.fail("Could not find the requested toggle", "TOGGLE_NOT_FOUND")
        val node = nodeCache[resolvedNodeId] ?: return ActionResult.fail("Node is no longer available: $resolvedNodeId", "NODE_NOT_FOUND")
        if (node.isCheckable && node.isChecked == value) return ActionResult.ok("Toggle is already ${if (value) "on" else "off"}")
        return tapNode(resolvedNodeId)
    }

    suspend fun expandCollapse(label: String? = null, nodeId: String? = null, expanded: Boolean): ActionResult {
        refreshNodeCache()
        val resolvedNodeId = nodeId ?: label?.let { findBestMatchingNodeId(it, role = null, containerNodeId = null) }
            ?: return ActionResult.fail("Could not find the requested section", "SECTION_NOT_FOUND")
        val node = nodeCache[resolvedNodeId] ?: return ActionResult.fail("Node is no longer available: $resolvedNodeId", "NODE_NOT_FOUND")
        val description = nodeStateDescription(node)
        if (expanded && description.contains("expanded")) return ActionResult.ok("Section is already expanded")
        if (!expanded && description.contains("collapsed")) return ActionResult.ok("Section is already collapsed")
        val actionId = if (expanded) EXPAND_ACTION_ID else COLLAPSE_ACTION_ID
        if (actionId != null) {
            val target = tapCandidates(node).firstOrNull { candidate -> hasAction(candidate, actionId) && candidate.performAction(actionId) }
            if (target != null) return ActionResult.ok(if (expanded) "Expanded the section" else "Collapsed the section")
        }
        return tapNode(resolvedNodeId)
    }

    suspend fun setSlider(label: String? = null, nodeId: String? = null, value: Float? = null, percent: Int? = null): ActionResult {
        refreshNodeCache()
        val resolvedNodeId = nodeId ?: label?.let { findBestMatchingNodeId(it, role = "slider", containerNodeId = null) }
            ?: return ActionResult.fail("Could not find the requested slider", "SLIDER_NOT_FOUND")
        val node = nodeCache[resolvedNodeId] ?: return ActionResult.fail("Node is no longer available: $resolvedNodeId", "NODE_NOT_FOUND")
        val progressTarget = tapCandidates(node).firstOrNull { candidate -> hasAction(candidate, SET_PROGRESS_ACTION_ID ?: -1) }
            ?: return ActionResult.fail("The slider does not support direct progress changes", "SET_PROGRESS_UNSUPPORTED")
        val range = progressTarget.rangeInfo
        val resolvedValue = when {
            percent != null && range != null -> range.min + (range.max - range.min) * (percent.coerceIn(0, 100) / 100f)
            value != null && range != null && value in 0f..1f && range.max > 1f -> range.min + (range.max - range.min) * value
            value != null && range != null -> value.coerceIn(range.min, range.max)
            percent != null -> percent.coerceIn(0, 100) / 100f
            value != null -> value
            else -> return ActionResult.fail("SET_SLIDER requires a value or percent", "SET_PROGRESS_MISSING_VALUE")
        }
        val args = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, resolvedValue)
        }
        return if (progressTarget.performAction(SET_PROGRESS_ACTION_ID ?: -1, args)) {
            ActionResult.ok("Adjusted the slider")
        } else {
            ActionResult.fail("The slider did not accept the requested value", "SET_PROGRESS_FAILED")
        }
    }

    suspend fun refresh(targetNodeId: String? = null): ActionResult {
        val refreshButton = tapText("Refresh", role = "button")
        if (refreshButton.success) return refreshButton
        refreshNodeCache()
        val target = resolveScrollTarget(targetNodeId)
        return if (target != null) {
            performPullToRefresh(target)
        } else {
            performViewportRefresh()
        }
    }

    suspend fun findTextOnScreen(text: String, tapOnMatch: Boolean = false): ActionResult {
        refreshNodeCache()
        val match = findBestMatchingNodeId(text, role = null, containerNodeId = null)
            ?: return ActionResult.fail("Could not find visible text: $text", "TEXT_NOT_FOUND")
        return if (tapOnMatch) tapNode(match) else ActionResult.ok("Found $text on screen")
    }

    fun performGlobalBack(): ActionResult =
        if (performGlobalAction(GLOBAL_ACTION_BACK)) ActionResult.ok("Pressed back")
        else ActionResult.fail("Failed to press back", "GLOBAL_BACK_FAILED")

    fun performGlobalHome(): ActionResult =
        if (performGlobalAction(GLOBAL_ACTION_HOME)) ActionResult.ok("Pressed home")
        else ActionResult.fail("Failed to press home", "GLOBAL_HOME_FAILED")

    fun performGlobalNotifications(): ActionResult =
        if (performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)) ActionResult.ok("Opened notifications")
        else ActionResult.fail("Failed to open notifications", "GLOBAL_NOTIFICATIONS_FAILED")

    fun performGlobalQuickSettings(): ActionResult =
        if (performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) ActionResult.ok("Opened Quick Settings")
        else ActionResult.fail("Failed to open Quick Settings", "GLOBAL_QUICK_SETTINGS_FAILED")

    fun performGlobalRecents(): ActionResult =
        if (performGlobalAction(GLOBAL_ACTION_RECENTS)) ActionResult.ok("Opened recent apps")
        else ActionResult.fail("Failed to open recent apps", "GLOBAL_RECENTS_FAILED")

    suspend fun takeScreenshotBitmap(): ai.droidlm.portal.ScreenshotResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ai.droidlm.portal.ScreenshotResult(false, message = "Screenshots require Android 11+", errorCode = "SCREENSHOT_UNSUPPORTED")
        }
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()
                        if (bitmap == null) {
                            continuation.resume(
                                ai.droidlm.portal.ScreenshotResult(false, message = "Could not convert screenshot", errorCode = "SCREENSHOT_CONVERT_FAILED")
                            )
                        } else {
                            continuation.resume(ai.droidlm.portal.ScreenshotResult(true, bitmap = bitmap, message = "Screenshot captured"))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        continuation.resume(
                            ai.droidlm.portal.ScreenshotResult(false, message = "Screenshot failed: $errorCode", errorCode = "SCREENSHOT_FAILED_$errorCode")
                        )
                    }
                }
            )
        }
    }

    fun findFocusedEditableTarget(): EditableTarget? {
        refreshNodeCache()
        return nodeCache.entries
            .firstOrNull { (_, node) -> node.isFocused && isEditableNode(node) }
            ?.let { (id, node) -> node.toEditableTarget(id) }
    }

    fun findEditableTargets(): List<EditableTarget> {
        refreshNodeCache()
        return nodeCache.entries
            .filter { (_, node) -> isEditableNode(node) }
            .map { (id, node) -> node.toEditableTarget(id) }
    }

    fun getNodeText(nodeId: String): String? {
        refreshNodeCache()
        return nodeCache[nodeId]?.text?.toString()
    }

    fun getNodeSelection(nodeId: String): Pair<Int, Int>? {
        refreshNodeCache()
        val node = nodeCache[nodeId] ?: return null
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        return if (start >= 0 && end >= 0) start to end else null
    }

    fun performSetSelection(nodeId: String, start: Int, end: Int): ActionResult {
        refreshNodeCache()
        val node = nodeCache[nodeId] ?: return ActionResult.fail("Editable node is no longer available", "NODE_NOT_FOUND")
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
            ActionResult.ok("Set selection to $start..$end")
        } else {
            ActionResult.fail("Node did not accept selection change", "SET_SELECTION_FAILED")
        }
    }

    fun performSetText(nodeId: String, text: String): ActionResult {
        refreshNodeCache()
        val node = nodeCache[nodeId] ?: return ActionResult.fail("Editable node is no longer available", "NODE_NOT_FOUND")
        return performSetTextForNode(node, text)
    }

    fun inputTextAtCurrentCursor(text: String): ActionResult {
        refreshNodeCache()
        val focused = nodeCache.values.firstOrNull { it.isFocused && isEditableNode(it) }
            ?: nodeCache.values.firstOrNull { isEditableNode(it) }?.also { it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            ?: return ActionResult.fail("No editable field is focused", "NO_EDITABLE_FOCUS")
        val existing = focused.text?.toString().orEmpty()
        val start = focused.textSelectionStart.takeIf { it >= 0 } ?: existing.length
        val end = focused.textSelectionEnd.takeIf { it >= 0 } ?: start
        val safeStart = start.coerceIn(0, existing.length)
        val safeEnd = end.coerceIn(safeStart, existing.length)
        val newText = existing.substring(0, safeStart) + text + existing.substring(safeEnd)
        val setText = performSetTextForNode(focused, newText)
        if (!setText.success) return setText
        focused.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                val cursor = safeStart + text.length
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
        )
        return ActionResult.ok("Inserted text at cursor")
    }

    fun setFocusedText(text: String): ActionResult {
        refreshNodeCache()
        val focused = nodeCache.values.firstOrNull { it.isFocused && isEditableNode(it) }
            ?: return ActionResult.fail("No editable field is focused", "NO_EDITABLE_FOCUS")
        return performSetTextForNode(focused, text)
    }

    private fun performSetTextForNode(node: AccessibilityNodeInfo, text: String): ActionResult {
        if (!hasAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) {
            return ActionResult.fail("Focused field does not support ACTION_SET_TEXT", "SET_TEXT_UNSUPPORTED")
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            ActionResult.ok("Set editable text")
        } else {
            ActionResult.fail("Node did not accept text", "SET_TEXT_FAILED")
        }
    }

    private suspend fun dispatchPathGesture(path: Path, durationMs: Long, message: String): ActionResult {
        return suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
                .build()
            val started = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        continuation.resume(ActionResult.ok(message))
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        continuation.resume(ActionResult.fail("Gesture cancelled", "GESTURE_CANCELLED"))
                    }
                },
                null
            )
            if (!started) continuation.resume(ActionResult.fail("Gesture dispatch failed", "GESTURE_DISPATCH_FAILED"))
        }
    }

    private fun refreshNodeCache() {
        nodeCache.clear()
        rootInActiveWindow?.let { collectNodes(it, "w0") }
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        path: String,
        parentId: String? = null,
        depth: Int = 0,
        childIndex: Int = 0,
        inheritedActions: List<UiNodeAction> = emptyList()
    ): List<UiNode> {
        val id = buildNodeId(node, path)
        nodeCache[id] = node
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val availableActions = UiNodeActionCatalog.fromAccessibilityNode(node)
        val effectiveActions = if (availableActions.isEmpty() && inheritedActions.isNotEmpty()) inheritedActions else emptyList()
        val uiNode = UiNode(
            nodeId = id,
            text = if (node.isPassword) null else node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            bounds = Rect(rect),
            clickable = node.isClickable,
            editable = isEditableNode(node),
            focused = node.isFocused,
            enabled = node.isEnabled,
            selected = node.isSelected,
            viewIdResourceName = node.viewIdResourceName,
            visible = node.isVisibleToUser,
            focusable = node.isFocusable,
            scrollable = node.isScrollable,
            checked = node.isChecked,
            checkable = node.isCheckable,
            longClickable = node.isLongClickable,
            password = node.isPassword,
            textSelectionStart = node.textSelectionStart.takeIf { it >= 0 },
            textSelectionEnd = node.textSelectionEnd.takeIf { it >= 0 },
            actions = UiNodeActionCatalog.labels(availableActions),
            hintText = node.hintText?.toString(),
            stateDescription = stateDescription(node),
            tooltipText = node.tooltipText?.toString(),
            paneTitle = node.paneTitle?.toString(),
            inputType = node.inputType.takeIf { it != 0 },
            inputTypeLabel = inputTypeLabel(node.inputType),
            textEntryKey = node.isTextEntryKey,
            multiLine = node.isMultiLine || ((node.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0),
            heading = node.isHeading,
            screenReaderFocusable = node.isScreenReaderFocusable,
            showingHintText = node.isShowingHintText,
            contextClickable = node.isContextClickable,
            parentId = parentId,
            depth = depth,
            childIndex = childIndex,
            collectionInfo = node.collectionInfo?.toUiCollectionInfo(),
            collectionItemInfo = node.collectionItemInfo?.toUiCollectionItemInfo(),
            rangeInfo = node.rangeInfo?.toUiRangeInfo(),
            availableActions = availableActions,
            effectiveActions = effectiveActions
        )
        val childInheritedActions = if (availableActions.isNotEmpty()) {
            UiNodeActionCatalog.effectiveFromParent(id, availableActions)
        } else {
            inheritedActions
        }
        val children = buildList {
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    addAll(collectNodes(child, "$path.$index", id, depth + 1, index, childInheritedActions))
                }
            }
        }
        return listOf(uiNode) + children
    }

    private fun buildNodeId(node: AccessibilityNodeInfo, path: String): String {
        val viewId = node.viewIdResourceName?.takeIf { it.isNotBlank() }
        return if (viewId != null) "$viewId@$path" else listOfNotNull(node.packageName, node.className, path).joinToString(":")
    }

    private fun AccessibilityNodeInfo.toEditableTarget(nodeId: String): EditableTarget {
        val rect = Rect().also { getBoundsInScreen(it) }
        return EditableTarget(
            nodeId = nodeId,
            packageName = packageName?.toString(),
            className = className?.toString(),
            bounds = Rect(rect),
            isFocused = isFocused,
            isEditable = isEditableNode(this),
            supportsSetText = hasAction(this, AccessibilityNodeInfo.ACTION_SET_TEXT),
            supportsSetSelection = hasAction(this, AccessibilityNodeInfo.ACTION_SET_SELECTION),
            supportsKeyboardInput = true
        )
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isEditable || className.contains("EditText", ignoreCase = true) || hasAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)
    }

    private fun hasAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        return node.actionList.any { it.id == actionId } || (node.actions and actionId) == actionId
    }

    private fun tapCandidates(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops <= MAX_TAP_PARENT_HOPS) {
            yield(current)
            current = current.parent
            hops += 1
        }
    }

    private fun tappableBounds(node: AccessibilityNodeInfo): Rect? {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return rect.takeUnless { it.isEmpty }
    }

    private fun isTappableNode(node: AccessibilityNodeInfo): Boolean =
        node.isEnabled && node.isVisibleToUser && (node.isClickable || hasAction(node, AccessibilityNodeInfo.ACTION_CLICK))

    private fun stateDescription(node: AccessibilityNodeInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) node.stateDescription?.toString() else null

    private fun AccessibilityNodeInfo.CollectionInfo.toUiCollectionInfo(): UiCollectionInfo = UiCollectionInfo(
        rowCount = rowCount.takeIf { it >= 0 },
        columnCount = columnCount.takeIf { it >= 0 },
        hierarchical = isHierarchical,
        selectionMode = selectionModeLabel(selectionMode)
    )

    private fun AccessibilityNodeInfo.CollectionItemInfo.toUiCollectionItemInfo(): UiCollectionItemInfo = UiCollectionItemInfo(
        rowIndex = rowIndex.takeIf { it >= 0 },
        rowSpan = rowSpan.takeIf { it >= 0 },
        columnIndex = columnIndex.takeIf { it >= 0 },
        columnSpan = columnSpan.takeIf { it >= 0 },
        heading = isHeading,
        selected = isSelected
    )

    private fun AccessibilityNodeInfo.RangeInfo.toUiRangeInfo(): UiRangeInfo = UiRangeInfo(
        type = rangeTypeLabel(type),
        min = min,
        max = max,
        current = current
    )

    private fun selectionModeLabel(selectionMode: Int): String = when (selectionMode) {
        AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_NONE -> "NONE"
        AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE -> "SINGLE"
        AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_MULTIPLE -> "MULTIPLE"
        else -> "UNKNOWN_$selectionMode"
    }

    private fun rangeTypeLabel(type: Int): String = when (type) {
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT -> "INT"
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT -> "FLOAT"
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT -> "PERCENT"
        else -> "UNKNOWN_$type"
    }

    private fun inputTypeLabel(inputType: Int): String? {
        if (inputType == 0) return null
        val typeClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val classLabel = when (typeClass) {
            InputType.TYPE_CLASS_TEXT -> "TEXT"
            InputType.TYPE_CLASS_NUMBER -> "NUMBER"
            InputType.TYPE_CLASS_PHONE -> "PHONE"
            InputType.TYPE_CLASS_DATETIME -> "DATETIME"
            else -> "UNKNOWN_CLASS_$typeClass"
        }
        val variationLabel = when {
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "EMAIL_ADDRESS"
            variation == InputType.TYPE_TEXT_VARIATION_URI -> "URI"
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD -> "PASSWORD"
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> "VISIBLE_PASSWORD"
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> "WEB_PASSWORD"
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> "NUMBER_PASSWORD"
            variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> "PERSON_NAME"
            variation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> "POSTAL_ADDRESS"
            variation == InputType.TYPE_TEXT_VARIATION_NORMAL -> null
            variation == 0 -> null
            else -> "VARIATION_$variation"
        }
        return listOfNotNull(classLabel, variationLabel).joinToString("_")
    }

    private fun findBestMatchingNodeId(text: String, role: String?, containerNodeId: String?): String? {
        val query = normalize(text)
        if (query.isBlank()) return null
        return nodeCache.entries
            .asSequence()
            .filter { (_, node) -> node.isVisibleToUser && node.isEnabled }
            .mapNotNull { (id, node) ->
                val score = candidateScore(id, node, query, role, containerNodeId)
                if (score <= 0) null else Triple(id, node, score)
            }
            .sortedWith(
                compareByDescending<Triple<String, AccessibilityNodeInfo, Int>> { it.third }
                    .thenByDescending { it.second.isClickable }
                    .thenByDescending { it.second.isFocusable }
            )
            .map { it.first }
            .firstOrNull()
    }

    private fun candidateScore(
        nodeId: String,
        node: AccessibilityNodeInfo,
        query: String,
        role: String?,
        containerNodeId: String?
    ): Int {
        val label = nodeLabel(node)
        if (label.isBlank()) return 0
        val normalizedLabel = normalize(label)
        val baseScore = when {
            normalizedLabel == query -> 130
            normalizedLabel.startsWith(query) -> 110
            normalizedLabel.contains(query) -> 90
            queryWords(query).all { normalizedLabel.contains(it) } -> 75
            else -> 0
        }
        if (baseScore == 0) return 0
        val roleScore = when {
            role.isNullOrBlank() -> 0
            roleMatches(node, role) -> 20
            else -> -50
        }
        val containerScore = when {
            containerNodeId.isNullOrBlank() -> 0
            isWithinContainer(nodeId, containerNodeId) -> 20
            else -> -10
        }
        val affordanceScore =
            (if (node.isClickable) 12 else 0) +
                (if (node.isFocusable) 8 else 0) +
                (if (node.isSelected) 6 else 0) +
                (if (node.isCheckable) 4 else 0) +
                (if (node.isScrollable) 4 else 0)
        return baseScore + roleScore + containerScore + affordanceScore
    }

    private fun resolveScrollTarget(targetNodeId: String?): AccessibilityNodeInfo? {
        targetNodeId?.let { explicitId ->
            nodeCache[explicitId]?.let { return it }
        }
        return nodeCache.values.firstOrNull { it.isScrollable && it.isVisibleToUser } ?: nodeCache.values.firstOrNull { node ->
            node.isVisibleToUser && (hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) || hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        }
    }

    private suspend fun performScroll(node: AccessibilityNodeInfo, direction: ScrollDirection): ActionResult {
        val directActionIds = when (direction) {
            ScrollDirection.UP -> listOfNotNull(SCROLL_UP_ACTION_ID, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            ScrollDirection.DOWN -> listOfNotNull(SCROLL_DOWN_ACTION_ID, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ScrollDirection.LEFT -> listOfNotNull(SCROLL_LEFT_ACTION_ID, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            ScrollDirection.RIGHT -> listOfNotNull(SCROLL_RIGHT_ACTION_ID, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        val target = tapCandidates(node).firstOrNull { candidate ->
            directActionIds.any { actionId -> hasAction(candidate, actionId) && candidate.performAction(actionId) }
        }
        if (target != null) return ActionResult.ok("Scrolled ${direction.name.lowercase()} via accessibility action")
        return performViewportScroll(direction, label = "gesture fallback")
    }

    private suspend fun performViewportScroll(direction: ScrollDirection, label: String): ActionResult {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val horizontalInset = (width * 0.18f).toInt()
        val verticalInset = (height * 0.2f).toInt()
        val swipe = when (direction) {
            ScrollDirection.DOWN -> intArrayOf(width / 2, height - verticalInset, width / 2, verticalInset)
            ScrollDirection.UP -> intArrayOf(width / 2, verticalInset, width / 2, height - verticalInset)
            ScrollDirection.LEFT -> intArrayOf(width - horizontalInset, height / 2, horizontalInset, height / 2)
            ScrollDirection.RIGHT -> intArrayOf(horizontalInset, height / 2, width - horizontalInset, height / 2)
        }
        val result = swipe(swipe[0], swipe[1], swipe[2], swipe[3], DEFAULT_SCROLL_GESTURE_MS)
        return if (result.success) {
            ActionResult.ok("Scrolled ${direction.name.lowercase()} via $label")
        } else {
            result
        }
    }

    private suspend fun performPullToRefresh(node: AccessibilityNodeInfo): ActionResult {
        val rect = tappableBounds(node) ?: return performViewportRefresh()
        val startX = rect.centerX().coerceAtLeast(rect.left + 1)
        val startY = (rect.top + rect.height() * 0.2f).toInt().coerceIn(rect.top + 1, rect.bottom - 1)
        val endY = (rect.top + rect.height() * 0.75f).toInt().coerceIn(rect.top + 1, rect.bottom - 1)
        val result = swipe(startX, startY, startX, endY, DEFAULT_REFRESH_GESTURE_MS)
        return if (result.success) ActionResult.ok("Triggered pull-to-refresh") else result
    }

    private suspend fun performViewportRefresh(): ActionResult {
        val metrics = resources.displayMetrics
        val startX = metrics.widthPixels / 2
        val startY = (metrics.heightPixels * 0.22f).toInt()
        val endY = (metrics.heightPixels * 0.7f).toInt()
        val result = swipe(startX, startY, startX, endY, DEFAULT_REFRESH_GESTURE_MS)
        return if (result.success) ActionResult.ok("Triggered pull-to-refresh") else result
    }

    private fun dialogLabels(role: DialogButtonRole?): List<String> = when (role) {
        DialogButtonRole.POSITIVE -> listOf("OK", "Allow", "Yes", "Continue", "Done", "Save", "Confirm", "Open")
        DialogButtonRole.NEGATIVE -> listOf("Cancel", "Deny", "No", "Not now", "Don\'t allow")
        DialogButtonRole.NEUTRAL -> listOf("Later", "Maybe later", "Remind me later")
        DialogButtonRole.DISMISS -> listOf("Dismiss", "Close", "Cancel")
        null -> emptyList()
    }

    private fun roleMatches(node: AccessibilityNodeInfo, role: String): Boolean {
        val normalizedRole = normalize(role)
        val className = node.className?.toString().orEmpty().lowercase()
        val label = nodeLabel(node).lowercase()
        return when (normalizedRole) {
            "tab" -> className.contains("tab") || label.contains("tab") || node.isSelected
            "button", "dialog button" -> className.contains("button") || node.isClickable
            "toggle", "switch", "checkbox", "radio" -> node.isCheckable || className.contains("switch") || className.contains("checkbox") || className.contains("radio")
            "slider", "seekbar" -> node.rangeInfo != null || className.contains("seekbar") || className.contains("slider")
            "menu" -> label.contains("menu") || label.contains("more") || className.contains("imagebutton")
            "row", "item", "list item" -> node.collectionItemInfo != null || node.isClickable
            "editable", "input", "field" -> isEditableNode(node)
            else -> label.contains(normalizedRole)
        }
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String = listOfNotNull(
        node.text?.toString(),
        node.contentDescription?.toString(),
        node.hintText?.toString(),
        stateDescription(node),
        node.tooltipText?.toString(),
        node.viewIdResourceName
    ).joinToString(" ").trim()

    private fun nodeStateDescription(node: AccessibilityNodeInfo): String = stateDescription(node).orEmpty().lowercase()

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun queryWords(value: String): List<String> = normalize(value).split(' ').filter { it.isNotBlank() }

    private fun isWithinContainer(nodeId: String, containerNodeId: String): Boolean {
        if (nodeId == containerNodeId) return true
        val nodePath = nodeId.substringAfter('@', missingDelimiterValue = "")
        val containerPath = containerNodeId.substringAfter('@', missingDelimiterValue = "")
        return nodePath.isNotBlank() && containerPath.isNotBlank() && nodePath.startsWith(containerPath)
    }

    private fun optionalAccessibilityActionId(fieldName: String): Int? = lookupActionId(fieldName)

    companion object {
        private const val MAX_TAP_PARENT_HOPS = 12
        private const val DEFAULT_SCROLL_GESTURE_MS = 260
        private const val DEFAULT_REFRESH_GESTURE_MS = 420
        private const val MAX_SCROLL_ATTEMPTS = 5
        private const val SCROLL_RETRY_DELAY_MS = 220L
        private const val WAIT_FOR_UI_POLL_MS = 120L
        private val EXPAND_ACTION_ID = lookupActionId("ACTION_EXPAND")
        private val COLLAPSE_ACTION_ID = lookupActionId("ACTION_COLLAPSE")
        private val DISMISS_ACTION_ID = lookupActionId("ACTION_DISMISS")
        private val SCROLL_UP_ACTION_ID = lookupActionId("ACTION_SCROLL_UP")
        private val SCROLL_DOWN_ACTION_ID = lookupActionId("ACTION_SCROLL_DOWN")
        private val SCROLL_LEFT_ACTION_ID = lookupActionId("ACTION_SCROLL_LEFT")
        private val SCROLL_RIGHT_ACTION_ID = lookupActionId("ACTION_SCROLL_RIGHT")
        private val SET_PROGRESS_ACTION_ID = lookupActionId("ACTION_SET_PROGRESS")
        private fun lookupActionId(fieldName: String): Int? = runCatching {
            val action = AccessibilityNodeInfo.AccessibilityAction::class.java.getField(fieldName).get(null)
            (action as AccessibilityNodeInfo.AccessibilityAction).id
        }.getOrNull()
    }
}
