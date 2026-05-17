package ai.droidlm.context

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import org.json.JSONArray
import org.json.JSONObject

class GoogleDocsContextProvider : DeviceContextProvider {
    override suspend fun collect(request: DeviceContextRequest): JSONObject {
        val state = request.state ?: return JSONObject()
        if (!supports(state.packageName)) return JSONObject()

        val focusedEditable = state.nodes.firstOrNull { it.editable && it.focused && !it.isDocsSearchBox() }
            ?: state.nodes.firstOrNull { it.editable && !it.isDocsSearchBox() }
        val editableText = focusedEditable?.text.orEmpty()
        val selectionStart = focusedEditable?.textSelectionStart
        val selectionEnd = focusedEditable?.textSelectionEnd
        val uiMode = detectUiMode(state, focusedEditable)
        val contentExtraction = AccessibilityContentExtractor.extract(state, AccessibilityContentLimits.LONG_CONTEXT_MAX_CHARS)
        val visibleText = contentExtraction.fullText
        val visibleDocuments = visibleDocuments(state.nodes, uiMode)
        val selectedDocument = selectedDocument(state.nodes, visibleDocuments)
        val title = inferDocumentTitle(state.nodes)
        val docActions = availableActions(uiMode, focusedEditable, visibleDocuments.length() > 0)
        val sharingFlowActive = uiMode == "SHARE_DIALOG"
        val deleteFlowActive = hasAnyText(state.nodes, "delete", "move to trash", "remove")

        val selection = selectionContext(focusedEditable, editableText, selectionStart, selectionEnd)
        val artifactContext = ArtifactContextBuilder.build(
            source = "google_docs",
            type = "document",
            title = title,
            uiMode = uiMode,
            state = state,
            visibleText = visibleText,
            focusedText = editableText,
            currentBlock = selection.optString("currentParagraph"),
            availableActions = docActions
        )
        val editor = JSONObject()
            .put("uiMode", uiMode)
            .put("isEditMode", uiMode == "DOCUMENT_EDIT")
            .put("isViewOnly", uiMode == "DOCUMENT_VIEW")
            .put("keyboardVisible", uiMode == "DOCUMENT_EDIT")
            .put("focusedEditableNodeId", focusedEditable?.nodeId)
            .put("canType", focusedEditable != null && uiMode == "DOCUMENT_EDIT")

        val safety = safetyContext(
            text = listOfNotNull(title, visibleText, editableText).joinToString("\n"),
            sharingFlowActive = sharingFlowActive,
            deleteFlowActive = deleteFlowActive
        )

        return JSONObject()
            .put(
                "activeDocument",
                JSONObject()
                    .put("source", "google_docs")
                    .put("title", title)
                    .put("documentId", JSONObject.NULL)
                    .put("uri", JSONObject.NULL)
                    .put("confidence", if (title != null) 0.62 else 0.0)
            )
            .put(
                "docsContext",
                JSONObject()
                    .put("uiMode", uiMode)
                    .put("isGoogleDocs", true)
                    .put("visibleDocuments", visibleDocuments)
                    .put("selectedDocument", selectedDocument)
                    .put("availableActions", JSONArray(docActions))
            )
            .put("artifactContext", artifactContext)
            .put("editor", editor)
            .put("selectionContext", selection)
            .put(
                "documentTextWindow",
                JSONObject()
                    .put("visibleText", cap(visibleText, MAX_VISIBLE_TEXT))
                    .put("focusedEditableText", cap(editableText, MAX_EDITABLE_TEXT))
                    .put("textBeforeCursor", cap(textBeforeCursor(editableText, selectionStart), MAX_CURSOR_TEXT))
                    .put("textAfterCursor", cap(textAfterCursor(editableText, selectionEnd), MAX_CURSOR_TEXT))
                    .put("currentParagraph", cap(currentParagraph(editableText, selectionStart), MAX_CURSOR_TEXT))
            )
            .put("availableDocActions", JSONArray(docActions))
            .put("safety", safety)
    }

    private fun supports(packageName: String?): Boolean = packageName == DOCS_PACKAGE

    private fun detectUiMode(state: PortalState, focusedEditable: UiNode?): String = when {
        ArtifactContextBuilder.isShareDialog(state.nodes) -> "SHARE_DIALOG"
        hasAnyText(state.nodes, "find", "replace", "search in document") -> "FIND_BAR"
        hasAnyText(state.nodes, "comment", "resolve", "reply") -> "COMMENT_PANEL"
        hasAnyText(state.nodes, "bold", "italic", "underline", "text color", "paragraph") -> "FORMAT_TOOLBAR"
        looksLikeDocumentList(state.nodes) -> "DOCUMENT_LIST"
        focusedEditable != null -> "DOCUMENT_EDIT"
        hasAnyText(state.nodes, "edit", "editing", "view only", "print layout") -> "DOCUMENT_VIEW"
        hasAnyText(state.nodes, "recent documents", "owned by me", "shared with me", "templates") -> "DOCUMENT_LIST"
        else -> "UNKNOWN"
    }

    private fun looksLikeDocumentList(nodes: List<UiNode>): Boolean =
        hasAnyText(nodes, "recent documents", "owned by me", "shared with me", "templates", "sort by", "view as list", "new document menu") ||
            nodes.any { node ->
                val label = listOfNotNull(node.text, node.contentDescription, node.hintText, node.viewIdResourceName).joinToString(" ").lowercase()
                label.contains("more actions for ") || label.contains("search docs")
            }

    private fun UiNode.isDocsSearchBox(): Boolean {
        val label = listOfNotNull(text, contentDescription, hintText, viewIdResourceName).joinToString(" ").lowercase()
        return label.contains("search docs") || (label.contains("search") && label.contains("top_app_bar"))
    }

    private fun selectionContext(node: UiNode?, text: String, start: Int?, end: Int?): JSONObject {
        val safeStart = start?.coerceIn(0, text.length)
        val safeEnd = end?.coerceIn(safeStart ?: 0, text.length)
        val selectedText = if (safeStart != null && safeEnd != null && safeEnd > safeStart) {
            text.substring(safeStart, safeEnd)
        } else {
            ""
        }
        return JSONObject()
            .put("focusedEditableNodeId", node?.nodeId)
            .put("selectionStart", safeStart)
            .put("selectionEnd", safeEnd)
            .put("selectedText", cap(selectedText, MAX_SELECTED_TEXT))
            .put("currentParagraph", cap(currentParagraph(text, safeStart), MAX_CURSOR_TEXT))
            .put("cursorKnown", safeStart != null && safeEnd != null)
    }

    private fun visibleText(nodes: List<UiNode>): String = nodes.asSequence()
        .mapNotNull { it.text?.trim() }
        .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .distinct()
        .joinToString("\n")

    private fun inferDocumentTitle(nodes: List<UiNode>): String? {
        val generic = setOf(
            "docs", "google docs", "edit", "share", "comments", "more options", "undo", "redo", "find", "format"
        )
        return nodes.asSequence()
            .mapNotNull { it.text?.trim()?.takeIf { text -> text.length in 2..80 } }
            .filterNot { it.lowercase() in generic }
            .filterNot { it.contains('@') }
            .firstOrNull()
    }

    private fun visibleDocuments(nodes: List<UiNode>, uiMode: String): JSONArray {
        if (uiMode == "DOCUMENT_EDIT" || uiMode == "FORMAT_TOOLBAR" || uiMode == "COMMENT_PANEL") return JSONArray()
        val rows = nodes.asSequence()
            .filter { it.visible && (it.clickable || it.effectiveActions.isNotEmpty() || !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank()) }
            .mapNotNull { documentCandidate(it) }
            .distinctBy { it.optString("title") }
            .take(50)
            .toList()
        return JSONArray(rows)
    }

    private fun documentCandidate(node: UiNode): JSONObject? {
        val raw = listOfNotNull(node.text, node.contentDescription).joinToString(" ").trim()
        val title = normalizeDocumentListTitle(raw)
        if (title.isBlank() || title.length < 2 || title.length > 180) return null
        if (title.lowercase() in documentListGenericLabels()) return null
        val targetNodeId = node.tapTargetNodeId()
        return JSONObject()
            .put("title", title)
            .put("nodeId", targetNodeId ?: JSONObject.NULL)
            .put("labelNodeId", node.nodeId ?: JSONObject.NULL)
            .put("tappable", targetNodeId != null)
            .put("confidence", if (targetNodeId != null) 0.7 else 0.3)
    }

    private fun normalizeDocumentListTitle(raw: String): String = raw
        .trim()
        .removePrefix("More actions for ")
        .trim()

    private fun selectedDocument(nodes: List<UiNode>, visibleDocuments: JSONArray): JSONObject {
        val selected = nodes.firstOrNull { it.selected || it.focused }
        if (selected != null) return documentCandidate(selected) ?: JSONObject()
        return if (visibleDocuments.length() == 1) visibleDocuments.optJSONObject(0) ?: JSONObject() else JSONObject()
    }

    private fun availableActions(uiMode: String, focusedEditable: UiNode?, hasVisibleDocuments: Boolean): List<String> {
        val actions = linkedSetOf("OPEN_DOC", "OPEN_FIND")
        when (uiMode) {
            "DOCUMENT_EDIT", "FORMAT_TOOLBAR" -> {
                actions += "TYPE_TEXT"
                actions += "REPLACE_TEXT"
                actions += "INSERT_AT_CURSOR"
                actions += "INSERT_AT_ANCHOR"
                actions += "FORMAT_BULLET"
                actions += "UNDO"
            }
            "DOCUMENT_VIEW" -> actions += "ENTER_EDIT_MODE"
            "DOCUMENT_LIST" -> actions += "OPEN_RECENT_DOC"
            "COMMENT_PANEL" -> actions += "ADD_COMMENT"
            "SHARE_DIALOG" -> actions += "SHARE"
        }
        if (focusedEditable != null) {
            actions += "TYPE_TEXT"
            actions += "REPLACE_TEXT"
        }
        if (hasVisibleDocuments) actions += "OPEN_RECENT_DOC"
        return actions.toList()
    }

    private fun UiNode.tapTargetNodeId(): String? {
        if (availableActions.any { it.droidLmAction == "TAP_NODE" }) return nodeId
        return effectiveActions.firstOrNull { it.droidLmAction == "TAP_NODE" }?.targetNodeId
            ?: nodeId.takeIf { clickable }
    }

    private fun documentListGenericLabels(): Set<String> = setOf(
        "docs", "google docs", "recent documents", "owned by me", "shared with me", "templates",
        "edit", "share", "comments", "more options", "undo", "redo", "find", "format", "search",
        "search docs", "view as list", "view as grid", "sort by", "new document menu"
    )

    private fun safetyContext(text: String, sharingFlowActive: Boolean, deleteFlowActive: Boolean): JSONObject = JSONObject()
        .put("containsEmail", EMAIL_REGEX.containsMatchIn(text))
        .put("containsCredentialLikeText", CREDENTIAL_REGEX.containsMatchIn(text))
        .put("containsFinancialTerms", FINANCIAL_REGEX.containsMatchIn(text))
        .put("sharingFlowActive", sharingFlowActive)
        .put("deleteFlowActive", deleteFlowActive)

    private fun hasAnyText(nodes: List<UiNode>, vararg needles: String): Boolean {
        val haystack = nodes.joinToString(" ") { node ->
            listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName).joinToString(" ")
        }.lowercase()
        return needles.any { haystack.contains(it.lowercase()) }
    }

    private fun textBeforeCursor(text: String, selectionStart: Int?): String {
        val index = selectionStart?.coerceIn(0, text.length) ?: return ""
        return text.substring(0, index)
    }

    private fun textAfterCursor(text: String, selectionEnd: Int?): String {
        val index = selectionEnd?.coerceIn(0, text.length) ?: return ""
        return text.substring(index)
    }

    private fun currentParagraph(text: String, selectionStart: Int?): String {
        if (text.isBlank()) return ""
        val index = selectionStart?.coerceIn(0, text.length) ?: text.length
        val start = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return text.substring(start, end).trim()
    }

    private fun cap(value: String, maxChars: Int): String = if (value.length <= maxChars) value else value.take(maxChars) + "..."

    companion object {
        const val DOCS_PACKAGE = "com.google.android.apps.docs.editors.docs"
        private const val MAX_VISIBLE_TEXT = AccessibilityContentLimits.DEFAULT_CONTEXT_MAX_CHARS
        private const val MAX_EDITABLE_TEXT = AccessibilityContentLimits.DEFAULT_CONTEXT_MAX_CHARS
        private const val MAX_CURSOR_TEXT = 8_000
        private const val MAX_SELECTED_TEXT = 8_000
        private val EMAIL_REGEX = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        private val CREDENTIAL_REGEX = Regex("\\b(password|passcode|api key|secret|token|credential)\\b", RegexOption.IGNORE_CASE)
        private val FINANCIAL_REGEX = Regex("\\b(invoice|budget|salary|bank|routing|account number|payment|revenue)\\b", RegexOption.IGNORE_CASE)
    }
}
