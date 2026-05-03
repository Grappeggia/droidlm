package ai.droidlm.context

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import org.json.JSONArray
import org.json.JSONObject

object UiContextJson {
    fun portalStateToJson(state: PortalState): JSONObject = JSONObject()
        .put("packageName", state.packageName)
        .put("activityName", state.activityName)
        .put("screenWidth", state.screenWidth)
        .put("screenHeight", state.screenHeight)
        .put("nodes", JSONArray(rankedNodes(state.nodes).take(MAX_NODES).map { it.toJson() }))

    private fun rankedNodes(nodes: List<UiNode>): List<UiNode> = nodes.sortedByDescending { it.relevanceScore() }

    private fun UiNode.toJson(): JSONObject = JSONObject()
        .put("id", nodeId)
        .put("viewId", viewIdResourceName)
        .put("text", text)
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

    private fun UiNode.relevanceScore(): Int {
        val label = listOfNotNull(text, contentDescription, viewIdResourceName, className)
            .joinToString(" ")
            .lowercase()
        return listOf(
            100 to focused,
            80 to editable,
            70 to label.contains("search"),
            50 to clickable,
            40 to actions.isNotEmpty(),
            30 to visible,
            25 to !text.isNullOrBlank(),
            20 to !contentDescription.isNullOrBlank(),
            15 to scrollable,
            10 to enabled
        ).sumOf { (points, applies) -> if (applies) points else 0 }
    }

    private fun UiNode.role(): String = when {
        listOfNotNull(text, contentDescription, viewIdResourceName).any { it.contains("search", ignoreCase = true) } -> "search"
        editable -> "editable"
        scrollable -> "scroll_container"
        checkable -> "checkable"
        clickable -> "button"
        else -> "node"
    }

    private const val MAX_NODES = 120
}
