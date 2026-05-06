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
    val actions: List<String> = emptyList(),
    val hintText: String? = null,
    val stateDescription: String? = null,
    val tooltipText: String? = null,
    val paneTitle: String? = null,
    val inputType: Int? = null,
    val inputTypeLabel: String? = null,
    val textEntryKey: Boolean = false,
    val multiLine: Boolean = false,
    val heading: Boolean = false,
    val screenReaderFocusable: Boolean = false,
    val showingHintText: Boolean = false,
    val contextClickable: Boolean = false,
    val parentId: String? = null,
    val depth: Int = 0,
    val childIndex: Int = 0,
    val collectionInfo: UiCollectionInfo? = null,
    val collectionItemInfo: UiCollectionItemInfo? = null,
    val rangeInfo: UiRangeInfo? = null,
    val availableActions: List<UiNodeAction> = emptyList(),
    val effectiveActions: List<UiNodeAction> = emptyList()
)

data class UiNodeAction(
    val name: String,
    val androidActionId: Int? = null,
    val label: String? = null,
    val droidLmAction: String? = null,
    val requiresArgs: Boolean = false,
    val argSchema: Map<String, String> = emptyMap(),
    val safe: Boolean = true,
    val targetNodeId: String? = null,
    val reason: String? = null
)

data class UiCollectionInfo(
    val rowCount: Int? = null,
    val columnCount: Int? = null,
    val hierarchical: Boolean = false,
    val selectionMode: String? = null
)

data class UiCollectionItemInfo(
    val rowIndex: Int? = null,
    val rowSpan: Int? = null,
    val columnIndex: Int? = null,
    val columnSpan: Int? = null,
    val heading: Boolean = false,
    val selected: Boolean = false
)

data class UiRangeInfo(
    val type: String? = null,
    val min: Float? = null,
    val max: Float? = null,
    val current: Float? = null
)
