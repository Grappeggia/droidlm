package ai.droidlm.context

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiCollectionInfo
import ai.droidlm.portal.UiCollectionItemInfo
import ai.droidlm.portal.UiNode
import ai.droidlm.portal.UiNodeAction
import ai.droidlm.portal.UiRangeInfo
import org.json.JSONArray
import org.json.JSONObject

object UiContextJson {
    fun portalStateToJson(state: PortalState): JSONObject {
        val rankedNodes = rankedNodes(state.nodes).take(MAX_NODES)
        return JSONObject()
            .put("packageName", state.packageName)
            .put("activityName", state.activityName)
            .put("screenWidth", state.screenWidth)
            .put("screenHeight", state.screenHeight)
            .put("nodes", JSONArray(rankedNodes.map { it.toJson() }))
            .put("actionMap", actionMap(rankedNodes))
    }

    private fun rankedNodes(nodes: List<UiNode>): List<UiNode> = nodes.sortedByDescending { it.relevanceScore() }

    private fun UiNode.toJson(): JSONObject {
        val safeText = if (password) null else text
        val json = JSONObject()
            .put("id", nodeId)
            .put("viewId", viewIdResourceName)
            .put("text", safeText)
            .put("contentDescription", contentDescription)
            .put("className", className)
            .put("packageName", packageName)
            .put("bounds", bounds?.let { bounds ->
                JSONObject()
                    .put("left", bounds.left)
                    .put("top", bounds.top)
                    .put("right", bounds.right)
                    .put("bottom", bounds.bottom)
            })
            .put("center", bounds?.let { bounds ->
                JSONObject()
                    .put("x", (bounds.left + bounds.right) / 2)
                    .put("y", (bounds.top + bounds.bottom) / 2)
            })
            .put("role", role())
            .put("actions", JSONArray(actions))
            .put("clickable", clickable)
            .put("editable", editable)
            .put("focused", focused)
            .put("enabled", enabled)
            .put("selected", selected)
            .put("visible", visible)
            .put("focusable", focusable)
            .put("scrollable", scrollable)
            .put("checked", checked)
            .put("checkable", checkable)
            .put("longClickable", longClickable)
            .put("password", password)
            .put("textSelectionStart", textSelectionStart)
            .put("textSelectionEnd", textSelectionEnd)
            .put("depth", depth)
            .put("childIndex", childIndex)

        putIfPresent(json, "parentId", parentId)
        putIfPresent(json, "hintText", hintText)
        putIfPresent(json, "stateDescription", stateDescription)
        putIfPresent(json, "tooltipText", tooltipText)
        putIfPresent(json, "paneTitle", paneTitle)
        putIfPresent(json, "inputType", inputType)
        putIfPresent(json, "inputTypeLabel", inputTypeLabel)
        putIfTrue(json, "textEntryKey", textEntryKey)
        putIfTrue(json, "multiLine", multiLine)
        putIfTrue(json, "heading", heading)
        putIfTrue(json, "screenReaderFocusable", screenReaderFocusable)
        putIfTrue(json, "showingHintText", showingHintText)
        putIfTrue(json, "contextClickable", contextClickable)
        putIfPresent(json, "tapTargetNodeId", actionTargetNodeId("TAP_NODE"))
        putIfPresent(json, "focusTargetNodeId", actionTargetNodeId("FOCUS_NODE"))
        collectionInfo?.let { json.put("collectionInfo", it.toJson()) }
        collectionItemInfo?.let { json.put("collectionItemInfo", it.toJson()) }
        rangeInfo?.let { json.put("rangeInfo", it.toJson()) }
        if (availableActions.isNotEmpty()) json.put("availableActions", JSONArray(availableActions.map { it.toJson() }))
        if (effectiveActions.isNotEmpty()) json.put("effectiveActions", JSONArray(effectiveActions.map { it.toJson() }))
        return json
    }

    private fun actionMap(nodes: List<UiNode>): JSONObject {
        val json = JSONObject()
        nodes.forEach { node ->
            val id = node.nodeId ?: return@forEach
            val actions = (node.availableActions + node.effectiveActions).map { action ->
                action.targetNodeId?.let { "${action.name}@$it" } ?: action.name
            }.distinct()
            if (actions.isNotEmpty()) json.put(id, JSONArray(actions))
        }
        return json
    }

    private fun UiNodeAction.toJson(): JSONObject {
        val json = JSONObject()
            .put("name", name)
            .put("safe", safe)
            .put("requiresArgs", requiresArgs)
        putIfPresent(json, "androidActionId", androidActionId)
        putIfPresent(json, "label", label)
        putIfPresent(json, "droidLmAction", droidLmAction)
        putIfPresent(json, "targetNodeId", targetNodeId)
        putIfPresent(json, "reason", reason)
        if (argSchema.isNotEmpty()) {
            json.put("argSchema", JSONObject().also { schema -> argSchema.forEach { (key, value) -> schema.put(key, value) } })
        }
        return json
    }

    private fun UiNode.actionTargetNodeId(droidLmAction: String): String? {
        if (availableActions.any { it.droidLmAction == droidLmAction }) return nodeId
        return effectiveActions.firstOrNull { it.droidLmAction == droidLmAction }?.targetNodeId
    }

    private fun UiCollectionInfo.toJson(): JSONObject = JSONObject()
        .put("rowCount", rowCount)
        .put("columnCount", columnCount)
        .put("hierarchical", hierarchical)
        .also { putIfPresent(it, "selectionMode", selectionMode) }

    private fun UiCollectionItemInfo.toJson(): JSONObject = JSONObject()
        .put("rowIndex", rowIndex)
        .put("rowSpan", rowSpan)
        .put("columnIndex", columnIndex)
        .put("columnSpan", columnSpan)
        .put("heading", heading)
        .put("selected", selected)

    private fun UiRangeInfo.toJson(): JSONObject = JSONObject()
        .also { putIfPresent(it, "type", type) }
        .put("min", min)
        .put("max", max)
        .put("current", current)

    private fun UiNode.relevanceScore(): Int {
        val label = listOfNotNull(text, contentDescription, hintText, stateDescription, viewIdResourceName, className)
            .joinToString(" ")
            .lowercase()
        return listOf(
            100 to focused,
            90 to availableActions.isNotEmpty(),
            85 to effectiveActions.isNotEmpty(),
            80 to editable,
            70 to label.contains("search"),
            60 to (collectionInfo != null || collectionItemInfo != null),
            55 to (rangeInfo != null),
            50 to clickable,
            40 to actions.isNotEmpty(),
            30 to visible,
            25 to text.isNullOrBlank().not(),
            20 to contentDescription.isNullOrBlank().not(),
            18 to hintText.isNullOrBlank().not(),
            15 to scrollable,
            10 to enabled
        ).sumOf { (points, applies) -> if (applies) points else 0 }
    }

    private fun UiNode.role(): String = when {
        listOfNotNull(text, contentDescription, hintText, viewIdResourceName).any { it.contains("search", ignoreCase = true) } -> "search"
        editable -> "editable"
        rangeInfo != null -> "range_control"
        collectionInfo != null -> "collection"
        collectionItemInfo != null -> "collection_item"
        scrollable -> "scroll_container"
        checkable -> "checkable"
        clickable -> "button"
        else -> "node"
    }

    private fun putIfPresent(json: JSONObject, key: String, value: Any?) {
        if (value != null && value != JSONObject.NULL && value.toString().isNotBlank()) json.put(key, value)
    }

    private fun putIfTrue(json: JSONObject, key: String, value: Boolean) {
        if (value) json.put(key, true)
    }

    private const val MAX_NODES = 120
}
