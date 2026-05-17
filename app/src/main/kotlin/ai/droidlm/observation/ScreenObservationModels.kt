package ai.droidlm.observation

import android.graphics.Rect
import org.json.JSONObject

data class ScreenObservation(
    val observationId: String,
    val timestampMs: Long,
    val packageName: String?,
    val activityName: String?,
    val windowTitle: String?,
    val screenHash: String,
    val keyboardVisible: Boolean,
    val dialogVisible: Boolean,
    val loadingLikely: Boolean,
    val nodes: List<ObservedNode>,
    val ocrBlocks: List<OcrBlock>,
    val artifactContext: ArtifactContext?,
    val priorActionDelta: ActionDelta?,
    val confidence: ObservationConfidence,
    val freshness: ObservationFreshness = ObservationFreshness.UNKNOWN
) {
    fun toJson(): JSONObject = ScreenObservationJson.toJson(this)
}

data class ObservedNode(
    val nodeRef: String,
    val stableFingerprint: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val role: UiRole,
    val bounds: Rect,
    val visible: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val selected: Boolean?,
    val actions: Set<NodeAction>
)

data class OcrBlock(
    val text: String,
    val bounds: Rect?,
    val confidence: Float? = null,
    val source: String = "unknown"
)

data class ArtifactContext(
    val json: JSONObject
)

data class ActionDelta(
    val previousObservationId: String,
    val screenChanged: Boolean,
    val packageChanged: Boolean,
    val addedNodeFingerprints: List<String>,
    val removedNodeFingerprints: List<String>,
    val changedTextNodeCount: Int,
    val elapsedMs: Long
)

data class ObservationConfidence(
    val score: Double,
    val reasons: List<String>
)

enum class ObservationFreshness {
    FRESH_AFTER_MUTATION,
    STALE_AFTER_MUTATION,
    SAME_SCREEN_NO_DELTA,
    LOADING_OR_UNSTABLE,
    UNKNOWN
}

enum class UiRole {
    BUTTON,
    EDITABLE,
    SEARCH,
    TOGGLE,
    CHECKBOX,
    SLIDER,
    TAB,
    DIALOG,
    LIST,
    LIST_ITEM,
    SCROLL_CONTAINER,
    HEADING,
    TEXT,
    IMAGE,
    PROGRESS,
    PASSWORD,
    NODE
}

enum class NodeAction {
    CLICK,
    FOCUS,
    CLEAR_FOCUS,
    SELECT,
    CLEAR_SELECTION,
    LONG_CLICK,
    SET_TEXT,
    SET_SELECTION,
    COPY,
    CUT,
    PASTE,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    SCROLL_TO_POSITION,
    EXPAND,
    COLLAPSE,
    DISMISS,
    SHOW_ON_SCREEN,
    SET_PROGRESS,
    CUSTOM;

    companion object {
        fun from(raw: String): NodeAction = entries.firstOrNull { it.name == raw.trim().uppercase() } ?: CUSTOM
    }
}
