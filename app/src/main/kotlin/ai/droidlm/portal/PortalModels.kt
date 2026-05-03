package ai.droidlm.portal

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File

data class ActionResult(
    val success: Boolean,
    val message: String,
    val errorCode: String? = null
) {
    companion object {
        fun ok(message: String = "OK") = ActionResult(true, message)
        fun fail(message: String, errorCode: String? = null) = ActionResult(false, message, errorCode)
    }
}

data class ScreenshotResult(
    val success: Boolean,
    val bitmap: Bitmap? = null,
    val file: File? = null,
    val message: String = "",
    val errorCode: String? = null
)

data class AppPackage(
    val packageName: String,
    val label: String?,
    val isSystemApp: Boolean = false
)

data class PortalState(
    val packageName: String?,
    val activityName: String?,
    val screenWidth: Int?,
    val screenHeight: Int?,
    val nodes: List<UiNode>
)

data class UiNode(
    val nodeId: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val packageName: String?,
    val bounds: Rect?,
    val clickable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val viewIdResourceName: String? = null,
    val visible: Boolean = true,
    val focusable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean = false,
    val checkable: Boolean = false,
    val longClickable: Boolean = false,
    val password: Boolean = false,
    val textSelectionStart: Int? = null,
    val textSelectionEnd: Int? = null,
    val actions: List<String> = emptyList()
)
