package ai.droidlm.observation

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

object ScreenObservationJson {
    fun toJson(observation: ScreenObservation): JSONObject = JSONObject()
        .put("schemaVersion", 2)
        .put("observationId", observation.observationId)
        .put("timestampMs", observation.timestampMs)
        .put("packageName", observation.packageName ?: JSONObject.NULL)
        .put("activityName", observation.activityName ?: JSONObject.NULL)
        .put("windowTitle", observation.windowTitle ?: JSONObject.NULL)
        .put("screenHash", observation.screenHash)
        .put("keyboardVisible", observation.keyboardVisible)
        .put("dialogVisible", observation.dialogVisible)
        .put("loadingLikely", observation.loadingLikely)
        .put("freshness", observation.freshness.name)
        .put("confidence", observation.confidence.toJson())
        .put("priorActionDelta", observation.priorActionDelta?.toJson() ?: JSONObject.NULL)
        .put("artifactContext", observation.artifactContext?.json ?: JSONObject.NULL)
        .put("semanticCandidates", JSONArray(rankedSemanticCandidates(observation).map { it.toCandidateJson() }))
        .put("nodes", JSONArray(observation.nodes.take(MAX_NODES).map { it.toJson() }))
        .put("ocrBlocks", JSONArray(observation.ocrBlocks.take(MAX_OCR_BLOCKS).map { it.toJson() }))
        .put(
            "provenance",
            JSONObject()
                .put("nodeCount", observation.nodes.size)
                .put("emittedNodeCount", observation.nodes.size.coerceAtMost(MAX_NODES))
                .put("ocrBlockCount", observation.ocrBlocks.size)
                .put("emittedOcrBlockCount", observation.ocrBlocks.size.coerceAtMost(MAX_OCR_BLOCKS))
                .put("candidateCount", rankedSemanticCandidates(observation).size)
        )

    private fun ObservedNode.toJson(): JSONObject = JSONObject()
        .put("nodeRef", nodeRef)
        .put("stableFingerprint", stableFingerprint)
        .put("text", text ?: JSONObject.NULL)
        .put("contentDescription", contentDescription ?: JSONObject.NULL)
        .put("className", className ?: JSONObject.NULL)
        .put("role", role.name)
        .put("bounds", bounds.toJson())
        .put("visible", visible)
        .put("enabled", enabled)
        .put("clickable", clickable)
        .put("focusable", focusable)
        .put("focused", focused)
        .put("editable", editable)
        .put("scrollable", scrollable)
        .put("checked", checked ?: JSONObject.NULL)
        .put("selected", selected ?: JSONObject.NULL)
        .put("actions", JSONArray(actions.map { it.name }.sorted()))

    private fun OcrBlock.toJson(): JSONObject = JSONObject()
        .put("text", text)
        .put("bounds", bounds?.toJson() ?: JSONObject.NULL)
        .put("confidence", confidence ?: JSONObject.NULL)
        .put("source", source)

    private fun ObservationConfidence.toJson(): JSONObject = JSONObject()
        .put("score", score)
        .put("reasons", JSONArray(reasons))

    private fun ActionDelta.toJson(): JSONObject = JSONObject()
        .put("previousObservationId", previousObservationId)
        .put("screenChanged", screenChanged)
        .put("packageChanged", packageChanged)
        .put("addedNodeFingerprints", JSONArray(addedNodeFingerprints))
        .put("removedNodeFingerprints", JSONArray(removedNodeFingerprints))
        .put("changedTextNodeCount", changedTextNodeCount)
        .put("elapsedMs", elapsedMs)

    private fun ObservedNode.toCandidateJson(): JSONObject = JSONObject()
        .put("nodeRef", nodeRef)
        .put("stableFingerprint", stableFingerprint)
        .put("label", label() ?: JSONObject.NULL)
        .put("role", role.name)
        .put("actions", JSONArray(actions.map { it.name }.sorted()))
        .put("enabled", enabled)
        .put("visible", visible)
        .put("focused", focused)
        .put("editable", editable)
        .put("semanticRank", semanticScore())
        .put("bounds", bounds.toJson())

    private fun rankedSemanticCandidates(observation: ScreenObservation): List<ObservedNode> = observation.nodes
        .asSequence()
        .filter { it.visible && it.enabled }
        .filter { it.isSemanticCandidate() }
        .sortedWith(compareByDescending<ObservedNode> { it.semanticScore() }.thenBy { it.label().orEmpty() })
        .take(MAX_CANDIDATES)
        .toList()

    private fun ObservedNode.isSemanticCandidate(): Boolean = focused || editable || scrollable || clickable ||
        role in setOf(
            UiRole.BUTTON,
            UiRole.EDITABLE,
            UiRole.SEARCH,
            UiRole.TOGGLE,
            UiRole.CHECKBOX,
            UiRole.SLIDER,
            UiRole.TAB,
            UiRole.DIALOG,
            UiRole.LIST,
            UiRole.LIST_ITEM,
            UiRole.SCROLL_CONTAINER,
            UiRole.HEADING
        ) || actions.isNotEmpty()

    private fun ObservedNode.semanticScore(): Int = listOf(
        120 to focused,
        105 to editable,
        95 to actions.contains(NodeAction.SET_TEXT),
        90 to actions.contains(NodeAction.CLICK),
        82 to clickable,
        80 to (role == UiRole.SEARCH),
        76 to (role == UiRole.TAB),
        72 to (role == UiRole.TOGGLE || role == UiRole.CHECKBOX),
        68 to (role == UiRole.SLIDER),
        64 to scrollable,
        60 to actions.isNotEmpty(),
        50 to (role == UiRole.HEADING),
        24 to !label().isNullOrBlank()
    ).sumOf { (points, applies) -> if (applies) points else 0 }

    private fun ObservedNode.label(): String? = text?.takeIf { it.isNotBlank() }
        ?: contentDescription?.takeIf { it.isNotBlank() }

    private fun Rect.toJson(): JSONObject = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)

    private const val MAX_CANDIDATES = 80
    private const val MAX_NODES = 160
    private const val MAX_OCR_BLOCKS = 80
}
