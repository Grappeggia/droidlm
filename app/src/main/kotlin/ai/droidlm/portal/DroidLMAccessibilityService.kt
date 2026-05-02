package ai.droidlm.portal

import ai.droidlm.textedit.EditableTarget
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
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

    private fun collectNodes(node: AccessibilityNodeInfo, path: String): List<UiNode> {
        val id = buildNodeId(node, path)
        nodeCache[id] = node
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val uiNode = UiNode(
            nodeId = id,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            bounds = Rect(rect),
            clickable = node.isClickable,
            editable = isEditableNode(node),
            focused = node.isFocused,
            enabled = node.isEnabled,
            selected = node.isSelected
        )
        val children = buildList {
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { addAll(collectNodes(it, "$path.$index")) }
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

    companion object {
        private val currentService = AtomicReference<DroidLMAccessibilityService?>()
        fun current(): DroidLMAccessibilityService? = currentService.get()
    }
}
