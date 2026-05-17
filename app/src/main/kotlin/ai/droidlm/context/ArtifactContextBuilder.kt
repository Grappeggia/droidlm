package ai.droidlm.context

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import org.json.JSONArray
import org.json.JSONObject

internal object ArtifactContextBuilder {
    fun build(
        source: String,
        type: String,
        title: String?,
        uiMode: String,
        state: PortalState,
        visibleText: String,
        focusedText: String = "",
        currentBlock: String = "",
        availableActions: List<String> = emptyList(),
        extraTargets: JSONArray = JSONArray()
    ): JSONObject {
        val targets = linkedMapOf<String, JSONObject>()
        appendTargets(targets, extraTargets)
        state.nodes.forEach { node ->
            nodeLabel(node)?.let { label ->
                addTarget(targets, nodeTarget(label, node, source))
            }
            textLineTargets(node, source).forEach { target -> addTarget(targets, target) }
        }

        val navigationTargets = JSONArray(targets.values.take(MAX_NAVIGATION_TARGETS))
        return JSONObject()
            .put(
                "artifact",
                JSONObject()
                    .put("type", type)
                    .put("source", source)
                    .put("title", title ?: JSONObject.NULL)
                    .put("uri", JSONObject.NULL)
                    .put("confidence", if (title.isNullOrBlank()) 0.0 else 0.62)
            )
            .put(
                "surface",
                JSONObject()
                    .put("packageName", state.packageName ?: JSONObject.NULL)
                    .put("activityName", state.activityName ?: JSONObject.NULL)
                    .put("uiMode", uiMode)
                    .put("activePanel", activePanel(uiMode))
                    .put("keyboardVisible", uiMode.contains("EDIT", ignoreCase = true) || uiMode == "FORMULA_BAR")
            )
            .put("navigationTargets", navigationTargets)
            .put(
                "contentWindow",
                JSONObject()
                    .put("visibleText", cap(visibleText, MAX_CONTENT_TEXT))
                    .put("fullText", cap(visibleText, MAX_CONTENT_TEXT))
                    .put("focusedText", cap(focusedText, MAX_FOCUSED_TEXT))
                    .put("currentBlock", cap(currentBlock, MAX_CURRENT_BLOCK))
            )
            .put("availableTools", availableTools(availableActions, navigationTargets.length()))
            .put(
                "provenance",
                JSONObject()
                    .put("source", "accessibility")
                    .put("truncated", visibleText.length > MAX_CONTENT_TEXT || focusedText.length > MAX_FOCUSED_TEXT)
                    .put("nodeCount", state.nodes.size)
            )
    }

    fun target(
        label: String,
        kind: String,
        nodeId: String? = null,
        actions: List<String> = emptyList(),
        confidence: Double = 0.6
    ): JSONObject = JSONObject()
        .put("label", label)
        .put("kind", kind)
        .put("visible", true)
        .put("nodeId", nodeId ?: JSONObject.NULL)
        .put("actions", JSONArray(actions))
        .put("confidence", confidence)

    fun isShareDialog(nodes: List<UiNode>): Boolean {
        val text = nodes.joinToString(" ") { node ->
            listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName).joinToString(" ")
        }.lowercase()
        val hasDialogMarker = listOf("people with access", "general access", "restricted", "copy link", "link settings", "share with people").any { text.contains(it) }
        val hasShareControl = text.contains("share") || text.contains("sharing")
        return hasDialogMarker && (hasShareControl || text.contains("people with access") || text.contains("general access"))
    }

    fun supportsArtifactPackage(packageName: String?): Boolean = packageName in SUPPORTED_PACKAGES

    fun extractNavigationRequest(goal: String): String? {
        val normalized = goal.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return null
        val rawQuery = NAVIGATION_REQUEST_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(normalized)?.groupValues?.getOrNull(1)
        } ?: return null
        return cleanupNavigationQuery(rawQuery).takeIf { it.length >= 2 }
    }

    fun hasMatchingTarget(artifactContext: JSONObject?, query: String?): Boolean {
        val targets = artifactContext?.optJSONArray("navigationTargets") ?: return false
        val normalizedQuery = normalizeTargetText(query)
        if (normalizedQuery.isBlank()) return false
        return (0 until targets.length()).any { index ->
            val label = targets.optJSONObject(index)?.optString("label").orEmpty()
            targetLabelMatches(label, normalizedQuery)
        }
    }

    private fun appendTargets(targets: MutableMap<String, JSONObject>, array: JSONArray) {
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { addTarget(targets, it) }
        }
    }

    private fun addTarget(targets: MutableMap<String, JSONObject>, target: JSONObject) {
        val label = target.optString("label").trim()
        val kind = target.optString("kind", "text").trim().ifBlank { "text" }
        if (label.isBlank() || isGenericLabel(label)) return
        val key = "${kind.lowercase()}|${label.lowercase()}"
        val existing = targets[key]
        if (existing == null || target.optDouble("confidence", 0.0) > existing.optDouble("confidence", 0.0)) {
            targets[key] = target
        }
    }

    private fun nodeTarget(label: String, node: UiNode, source: String): JSONObject {
        val actions = nodeActions(node, hasSelectionRange = false)
        return target(
            label = label,
            kind = kindForNode(label, node, source),
            nodeId = node.tapTargetNodeId() ?: node.nodeId,
            actions = actions,
            confidence = if (actions.contains("tap")) 0.78 else 0.48
        ).put("labelNodeId", node.nodeId ?: JSONObject.NULL)
    }

    private fun textLineTargets(node: UiNode, source: String): List<JSONObject> {
        val sources = listOfNotNull(
            node.text?.let { "text" to it },
            node.contentDescription?.let { "contentDescription" to it },
            node.hintText?.let { "hintText" to it },
            node.stateDescription?.let { "stateDescription" to it },
            node.paneTitle?.let { "paneTitle" to it },
            node.tooltipText?.let { "tooltipText" to it }
        )
        return sources
            .flatMap { (field, text) ->
                text
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .lineSequence()
                    .map { rawLine -> Triple(field, text, cleanLine(rawLine)) }
                    .toList()
            }
            .filter { (_, _, line) -> line.length in 2..MAX_TARGET_LABEL_CHARS && !isGenericLabel(line) }
            .distinctBy { (_, _, line) -> line.lowercase() }
            .take(MAX_LINE_TARGETS_PER_NODE)
            .map { (field, text, line) ->
                val start = if (field == "text") text.indexOf(line).takeIf { it >= 0 } else null
                val hasSelectionRange = start != null && node.editable && node.availableActions.any { it.droidLmAction == "SET_SELECTION" || it.name == "SET_SELECTION" }
                target(
                    label = line,
                    kind = kindForLine(line, node, source),
                    nodeId = node.nodeId,
                    actions = nodeActions(node, hasSelectionRange),
                    confidence = if (node.heading || node.collectionItemInfo?.heading == true) 0.82 else if (hasSelectionRange) 0.66 else 0.52
                )
                    .put("labelNodeId", node.nodeId ?: JSONObject.NULL)
                    .put("sourceField", field)
                    .put("selectionStart", start ?: JSONObject.NULL)
                    .put("selectionEnd", start?.plus(line.length) ?: JSONObject.NULL)
            }
    }

    private fun nodeActions(node: UiNode, hasSelectionRange: Boolean): List<String> = buildList {
        if (node.tapTargetNodeId() != null) add("tap")
        if (hasSelectionRange) add("set_selection")
        if (node.availableActions.any { it.name == "SHOW_ON_SCREEN" }) add("show_on_screen")
        if (node.scrollable || node.availableActions.any { it.droidLmAction == "SCROLL" }) add("scroll")
        add("find_text_on_screen")
        add("search_accessibility_content")
    }.distinct()

    private fun nodeLabel(node: UiNode): String? {
        val label = listOfNotNull(node.text, node.contentDescription)
            .map { cleanLine(it) }
            .firstOrNull { it.length in 2..MAX_TARGET_LABEL_CHARS }
            ?: return null
        return label.takeIf { !isGenericLabel(it) }
    }

    private fun cleanLine(value: String): String = value
        .replace(Regex("[\\u0000-\\u001F\\uE000-\\uF8FF]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', ':', '|', '\u2022')

    private fun kindForNode(label: String, node: UiNode, source: String): String = when {
        node.heading || node.collectionItemInfo?.heading == true -> "heading"
        source == "google_drive" && label.contains("folder", ignoreCase = true) -> "folder"
        source == "google_drive" -> "file"
        source == "google_sheets" && label.contains("sheet", ignoreCase = true) -> "sheet_tab"
        node.viewIdResourceName?.contains("tab", ignoreCase = true) == true -> "tab"
        node.clickable || node.effectiveActions.any { it.droidLmAction == "TAP_NODE" } -> "control"
        else -> "text"
    }

    private fun kindForLine(line: String, node: UiNode, source: String): String = when {
        node.heading || node.collectionItemInfo?.heading == true -> "heading"
        source == "google_sheets" && CELL_REF_REGEX.matches(line) -> "cell"
        source == "google_sheets" && line.startsWith("Sheet", ignoreCase = true) -> "sheet_tab"
        source == "google_docs" && line.split(' ').size <= 6 -> "section"
        else -> "text"
    }

    private fun activePanel(uiMode: String): String = when {
        uiMode.contains("FIND", ignoreCase = true) || uiMode.contains("SEARCH", ignoreCase = true) -> "find"
        uiMode.contains("SHARE", ignoreCase = true) -> "share"
        uiMode.contains("COMMENT", ignoreCase = true) -> "comments"
        uiMode.contains("FORMAT", ignoreCase = true) -> "format"
        else -> "none"
    }

    private fun availableTools(actions: List<String>, targetCount: Int): JSONArray {
        val tools = linkedSetOf("FIND_TEXT_ON_SCREEN", "SEARCH_ACCESSIBILITY_CONTENT", "SCROLL")
        if (targetCount > 0) tools += "NAVIGATE_TO_ARTIFACT_TARGET"
        if (actions.any { it.contains("FIND", ignoreCase = true) || it.contains("SEARCH", ignoreCase = true) }) {
            tools += "NAVIGATE_TO_ARTIFACT_TARGET"
        }
        return JSONArray(tools.toList())
    }

    private fun UiNode.tapTargetNodeId(): String? {
        if (availableActions.any { it.droidLmAction == "TAP_NODE" }) return nodeId
        return effectiveActions.firstOrNull { it.droidLmAction == "TAP_NODE" }?.targetNodeId
            ?: nodeId.takeIf { clickable }
    }

    private fun isGenericLabel(label: String): Boolean {
        val normalized = label.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        if (normalized.isBlank()) return true
        if (normalized.length == 1 && normalized.first().isLetterOrDigit()) return true
        return normalized in GENERIC_LABELS
    }

    private fun cleanupNavigationQuery(value: String): String = value
        .replace(Regex("\\b(?:and\\s+)?then\\b.*$", RegexOption.IGNORE_CASE), "")
        .trim()
        .trim('"', '\'', '.', ',', ':', ';', ' ')
        .removePrefix("the ")
        .removeSuffix(" section")
        .removeSuffix(" heading")
        .trim()

    private fun targetLabelMatches(label: String, normalizedQuery: String): Boolean {
        val normalizedLabel = normalizeTargetText(label)
        if (normalizedLabel.isBlank()) return false
        if (normalizedLabel == normalizedQuery) return true
        if (normalizedLabel.contains(normalizedQuery)) return true
        if (normalizedQuery.contains(normalizedLabel) && normalizedLabel.length >= 4) return true
        val queryTokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
        return queryTokens.isNotEmpty() && queryTokens.all { token -> normalizedLabel.contains(token) }
    }

    private fun normalizeTargetText(value: String?): String = value
        ?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), " ")
        ?.trim()
        .orEmpty()

    private fun cap(value: String, maxChars: Int): String = if (value.length <= maxChars) value else value.take(maxChars) + "..."

    private const val MAX_NAVIGATION_TARGETS = 240
    private const val MAX_LINE_TARGETS_PER_NODE = 240
    private const val MAX_TARGET_LABEL_CHARS = 180
    private const val MAX_CONTENT_TEXT = AccessibilityContentLimits.DEFAULT_CONTEXT_MAX_CHARS
    private const val MAX_FOCUSED_TEXT = AccessibilityContentLimits.DEFAULT_CONTEXT_MAX_CHARS
    private const val MAX_CURRENT_BLOCK = 8_000
    private val CELL_REF_REGEX = Regex("[A-Z]{1,3}[0-9]{1,6}")
    private val NAVIGATION_REQUEST_PATTERNS = listOf(
        Regex("^navig(?:ate)?(?: me)?\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^navigate(?: me)? to (.+)$", RegexOption.IGNORE_CASE),
        Regex("^go to (.+)$", RegexOption.IGNORE_CASE),
        Regex("^jump to (.+)$", RegexOption.IGNORE_CASE),
        Regex("^scroll to (.+)$", RegexOption.IGNORE_CASE),
        Regex("^select(?: the)?\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^find (.+)$", RegexOption.IGNORE_CASE),
        Regex("^search(?: for)? (.+)$", RegexOption.IGNORE_CASE),
        Regex("^show me (.+)$", RegexOption.IGNORE_CASE)
    )
    private val SUPPORTED_PACKAGES = setOf(
        GoogleWorkspaceContextUtils.DRIVE_PACKAGE,
        GoogleWorkspaceContextUtils.DOCS_PACKAGE,
        GoogleWorkspaceContextUtils.SHEETS_PACKAGE
    )
    private val GENERIC_LABELS = setOf(
        "back", "close", "done", "cancel", "ok", "save", "edit", "share", "search", "find", "more options",
        "menu", "home", "recent", "recents", "files", "folders", "docs", "sheets", "drive", "google docs",
        "google sheets", "google drive", "comments", "format", "undo", "redo", "screen layout", "current viewers"
    )
}
