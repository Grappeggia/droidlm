package ai.droidlm.context

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import org.json.JSONArray
import org.json.JSONObject

class NotionContextProvider : DeviceContextProvider {
    override suspend fun collect(request: DeviceContextRequest): JSONObject {
        val state = request.state ?: return JSONObject()
        if (state.packageName != NOTION_PACKAGE) return JSONObject()

        val contentExtraction = AccessibilityContentExtractor.extract(state, AccessibilityContentLimits.LONG_CONTEXT_MAX_CHARS)
        val visibleText = contentExtraction.fullText
        val focusedEditable = state.nodes.firstOrNull { it.editable && it.focused } ?: state.nodes.firstOrNull { it.editable }
        val editableText = focusedEditable?.text.orEmpty()
        val uiMode = detectUiMode(state, focusedEditable)
        val title = inferPageTitle(state.nodes)
        val actions = availableActions(uiMode, focusedEditable)
        val artifactContext = ArtifactContextBuilder.build(
            source = "notion",
            type = if (looksLikeDatabase(state.nodes)) "notion_database" else "notion_page",
            title = title,
            uiMode = uiMode,
            state = state,
            visibleText = visibleText,
            focusedText = editableText,
            currentBlock = currentBlock(editableText, focusedEditable?.textSelectionStart),
            availableActions = actions
        )

        return JSONObject()
            .put(
                "notionContext",
                JSONObject()
                    .put("uiMode", uiMode)
                    .put("isNotion", true)
                    .put("availableActions", JSONArray(actions))
            )
            .put("artifactContext", artifactContext)
            .put(
                "selectionContext",
                JSONObject()
                    .put("focusedEditableNodeId", focusedEditable?.nodeId)
                    .put("selectionStart", focusedEditable?.textSelectionStart)
                    .put("selectionEnd", focusedEditable?.textSelectionEnd)
                    .put("cursorKnown", focusedEditable?.textSelectionStart != null && focusedEditable?.textSelectionEnd != null)
            )
            .put(
                "notionTextWindow",
                JSONObject()
                    .put("visibleText", visibleText.take(MAX_VISIBLE_TEXT))
                    .put("focusedEditableText", editableText.take(MAX_VISIBLE_TEXT))
            )
    }

    private fun detectUiMode(state: PortalState, focusedEditable: UiNode?): String = when {
        ArtifactContextBuilder.isShareDialog(state.nodes) -> "SHARE_DIALOG"
        focusedEditable != null -> "PAGE_EDIT"
        looksLikeDatabase(state.nodes) -> "DATABASE_VIEW"
        else -> "PAGE_VIEW"
    }

    private fun looksLikeDatabase(nodes: List<UiNode>): Boolean = hasAnyText(nodes, "database", "table", "board", "status", "property")

    private fun inferPageTitle(nodes: List<UiNode>): String? = nodes.asSequence()
        .mapNotNull { it.text?.trim()?.takeIf { text -> text.length in 2..120 } }
        .filterNot { it.equals("Notion", ignoreCase = true) || it.equals("Search", ignoreCase = true) }
        .firstOrNull()

    private fun availableActions(uiMode: String, focusedEditable: UiNode?): List<String> = buildList {
        add("ARTIFACT_RESOLVE_TARGET")
        add("ARTIFACT_NAVIGATE_TO_TARGET")
        add("ARTIFACT_VERIFY_END_STATE")
        add("NOTION_RESOLVE_BLOCK_OR_PAGE")
        add("NOTION_CREATE_PAGE_OR_BLOCK")
        add("NOTION_UPDATE_DATABASE_ITEM")
        add("NOTION_MOVE_OR_REORDER_BLOCK")
        if (focusedEditable != null || uiMode == "PAGE_EDIT") {
            add("TYPE_TEXT")
            add("SET_SELECTION")
        }
    }

    private fun currentBlock(text: String, selectionStart: Int?): String {
        if (text.isBlank()) return ""
        val cursor = selectionStart?.coerceIn(0, text.length) ?: 0
        val start = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
        return text.substring(start, end).take(MAX_VISIBLE_TEXT)
    }

    private fun hasAnyText(nodes: List<UiNode>, vararg needles: String): Boolean {
        val text = nodes.joinToString(" ") { node -> listOfNotNull(node.text, node.contentDescription, node.hintText).joinToString(" ") }.lowercase()
        return needles.any { text.contains(it.lowercase()) }
    }

    companion object {
        const val NOTION_PACKAGE = "notion.id"
        private const val MAX_VISIBLE_TEXT = 8_000
    }
}
