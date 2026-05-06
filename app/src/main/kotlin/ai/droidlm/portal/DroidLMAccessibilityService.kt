package ai.droidlm.portal

import ai.droidlm.textedit.EditableTarget
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class DroidLMAccessibilityService : AccessibilityService() {
    private val nodeCache = LinkedHashMap<String, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentService.set(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (currentService.get() === this) currentService.set(null)
        nodeCache.clear()
        super.onDestroy()
    }

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
        if (hasAction(node, AccessibilityNodeInfo.ACTION_CLICK) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return ActionResult.ok("Clicked node $nodeId")
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.isEmpty) return ActionResult.fail("Node has no tappable bounds: $nodeId", "NODE_BOUNDS_MISSING")
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

    fun performGlobalBack(): ActionResult =
        if (performGlobalAction(GLOBAL_ACTION_BACK)) ActionResult.ok("Pressed back")
        else ActionResult.fail("Failed to press back", "GLOBAL_BACK_FAILED")

    fun performGlobalHome(): ActionResult =
        if (performGlobalAction(GLOBAL_ACTION_HOME)) ActionResult.ok("Pressed home")
        else ActionResult.fail("Failed to press home", "GLOBAL_HOME_FAILED")

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
        return viewId ?: listOfNotNull(node.packageName, node.className, path).joinToString(":")
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

    companion object {
        private val currentService = AtomicReference<DroidLMAccessibilityService?>()
        fun current(): DroidLMAccessibilityService? = currentService.get()
    }
}
