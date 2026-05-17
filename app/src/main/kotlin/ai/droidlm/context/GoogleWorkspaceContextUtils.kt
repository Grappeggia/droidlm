package ai.droidlm.context

import ai.droidlm.portal.UiNode
import org.json.JSONObject

internal object GoogleWorkspaceContextUtils {
    const val DRIVE_PACKAGE = "com.google.android.apps.docs"
    const val DOCS_PACKAGE = "com.google.android.apps.docs.editors.docs"
    const val SHEETS_PACKAGE = "com.google.android.apps.docs.editors.sheets"
    const val MAX_VISIBLE_TEXT = AccessibilityContentLimits.DEFAULT_CONTEXT_MAX_CHARS
    const val MAX_EDITABLE_TEXT = AccessibilityContentLimits.DEFAULT_CONTEXT_MAX_CHARS
    const val MAX_CURSOR_TEXT = 8_000
    const val MAX_SELECTED_TEXT = 8_000

    private val emailRegex = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val credentialRegex = Regex("\\b(password|passcode|api key|secret|token|credential)\\b", RegexOption.IGNORE_CASE)
    private val financialRegex = Regex("\\b(invoice|budget|salary|bank|routing|account number|payment|revenue)\\b", RegexOption.IGNORE_CASE)

    fun focusedEditable(nodes: List<UiNode>): UiNode? = nodes.firstOrNull { it.editable && it.focused }
        ?: nodes.firstOrNull { it.editable }

    fun visibleText(nodes: List<UiNode>): String = nodes.asSequence()
        .mapNotNull { it.text?.trim() }
        .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .distinct()
        .joinToString("\n")

    fun inferTitle(nodes: List<UiNode>, generic: Set<String>): String? = nodes.asSequence()
        .mapNotNull { it.text?.trim()?.takeIf { text -> text.length in 2..100 } }
        .filterNot { it.lowercase() in generic }
        .filterNot { it.contains('@') }
        .firstOrNull()

    fun hasAnyText(nodes: List<UiNode>, vararg needles: String): Boolean {
        val haystack = nodes.joinToString(" ") { node ->
            listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName).joinToString(" ")
        }.lowercase()
        return needles.any { haystack.contains(it.lowercase()) }
    }

    fun cap(value: String, maxChars: Int): String = if (value.length <= maxChars) value else value.take(maxChars) + "..."

    fun textBeforeCursor(text: String, selectionStart: Int?): String {
        val index = selectionStart?.coerceIn(0, text.length) ?: return ""
        return text.substring(0, index)
    }

    fun textAfterCursor(text: String, selectionEnd: Int?): String {
        val index = selectionEnd?.coerceIn(0, text.length) ?: return ""
        return text.substring(index)
    }

    fun currentLine(text: String, selectionStart: Int?): String {
        if (text.isBlank()) return ""
        val index = selectionStart?.coerceIn(0, text.length) ?: text.length
        val start = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return text.substring(start, end).trim()
    }

    fun selectionContext(node: UiNode?, text: String): JSONObject {
        val start = node?.textSelectionStart?.coerceIn(0, text.length)
        val end = node?.textSelectionEnd?.coerceIn(start ?: 0, text.length)
        val selectedText = if (start != null && end != null && end > start) text.substring(start, end) else ""
        return JSONObject()
            .put("focusedEditableNodeId", node?.nodeId)
            .put("selectionStart", start)
            .put("selectionEnd", end)
            .put("selectedText", cap(selectedText, MAX_SELECTED_TEXT))
            .put("currentLine", cap(currentLine(text, start), MAX_CURSOR_TEXT))
            .put("cursorKnown", start != null && end != null)
    }

    fun safetyContext(
        text: String,
        sharingFlowActive: Boolean,
        deleteFlowActive: Boolean,
        moveFlowActive: Boolean = false,
        renameFlowActive: Boolean = false,
        uploadFlowActive: Boolean = false
    ): JSONObject = JSONObject()
        .put("containsEmail", emailRegex.containsMatchIn(text))
        .put("containsCredentialLikeText", credentialRegex.containsMatchIn(text))
        .put("containsFinancialTerms", financialRegex.containsMatchIn(text))
        .put("sharingFlowActive", sharingFlowActive)
        .put("deleteFlowActive", deleteFlowActive)
        .put("moveFlowActive", moveFlowActive)
        .put("renameFlowActive", renameFlowActive)
        .put("uploadFlowActive", uploadFlowActive)
}
