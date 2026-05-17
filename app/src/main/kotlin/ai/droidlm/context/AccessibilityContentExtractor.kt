package ai.droidlm.context

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import org.json.JSONArray
import org.json.JSONObject

object AccessibilityContentLimits {
    const val DEFAULT_CONTEXT_MAX_CHARS = 64_000
    const val LONG_CONTEXT_MAX_CHARS = 128_000
    const val SEARCH_CONTEXT_MAX_CHARS = 256_000
    const val MAX_PROMPT_LINE_ENTRIES = 1_200
    const val MAX_LINE_TEXT_CHARS = 1_200
}

data class AccessibilityContentLine(
    val index: Int,
    val text: String,
    val nodeId: String?,
    val sourceField: String,
    val nodeIndex: Int,
    val visible: Boolean,
    val clickable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val selected: Boolean,
    val heading: Boolean,
    val depth: Int,
    val childIndex: Int
) {
    fun toJson(maxTextChars: Int = AccessibilityContentLimits.MAX_LINE_TEXT_CHARS): JSONObject = JSONObject()
        .put("index", index)
        .put("text", text.cap(maxTextChars))
        .put("nodeId", nodeId ?: JSONObject.NULL)
        .put("sourceField", sourceField)
        .put("nodeIndex", nodeIndex)
        .put("visible", visible)
        .put("clickable", clickable)
        .put("editable", editable)
        .put("focused", focused)
        .put("selected", selected)
        .put("heading", heading)
        .put("depth", depth)
        .put("childIndex", childIndex)
}

data class AccessibilityContentExtraction(
    val packageName: String?,
    val activityName: String?,
    val lines: List<AccessibilityContentLine>,
    val rawCharCount: Int,
    val rawLineCount: Int,
    val emittedCharCount: Int,
    val maxContentChars: Int,
    val truncated: Boolean,
    val nodeCount: Int
) {
    val fullText: String = lines.joinToString("\n") { it.text }

    fun toJson(): JSONObject = JSONObject()
        .put("source", "accessibility")
        .put("packageName", packageName ?: JSONObject.NULL)
        .put("activityName", activityName ?: JSONObject.NULL)
        .put(
            "contentWindow",
            JSONObject()
                .put("fullText", fullText)
                .put("lines", JSONArray(lines.take(AccessibilityContentLimits.MAX_PROMPT_LINE_ENTRIES).map { it.toJson() }))
        )
        .put(
            "provenance",
            JSONObject()
                .put("rawCharCount", rawCharCount)
                .put("rawLineCount", rawLineCount)
                .put("emittedChars", emittedCharCount)
                .put("emittedLineCount", lines.size)
                .put("lineEntryCount", lines.size.coerceAtMost(AccessibilityContentLimits.MAX_PROMPT_LINE_ENTRIES))
                .put("lineEntriesTruncated", lines.size > AccessibilityContentLimits.MAX_PROMPT_LINE_ENTRIES)
                .put("maxContentChars", maxContentChars)
                .put("truncated", truncated)
                .put("nodeCount", nodeCount)
        )
}

data class AccessibilityContentSearchQuery(
    val query: String? = null,
    val sectionLabel: String? = null,
    val exclude: String? = null,
    val ordinal: Int? = null,
    val maxMatches: Int = 5
)

object AccessibilityContentExtractor {
    fun extract(
        state: PortalState,
        maxContentChars: Int = AccessibilityContentLimits.LONG_CONTEXT_MAX_CHARS
    ): AccessibilityContentExtraction {
        val rawLines = mutableListOf<AccessibilityContentLine>()
        var rawCharCount = 0
        state.nodes.forEachIndexed { nodeIndex, node ->
            node.contentFields().forEach { (field, value) ->
                value.toContentLines().forEach { text ->
                    rawCharCount += text.length
                    rawLines += AccessibilityContentLine(
                        index = rawLines.size + 1,
                        text = text,
                        nodeId = node.nodeId,
                        sourceField = field,
                        nodeIndex = nodeIndex,
                        visible = node.visible,
                        clickable = node.clickable,
                        editable = node.editable,
                        focused = node.focused,
                        selected = node.selected,
                        heading = node.heading || node.collectionItemInfo?.heading == true,
                        depth = node.depth,
                        childIndex = node.childIndex
                    )
                }
            }
        }

        val emitted = mutableListOf<AccessibilityContentLine>()
        var emittedChars = 0
        var truncated = false
        for (line in rawLines) {
            val separatorCost = if (emitted.isEmpty()) 0 else 1
            val remaining = maxContentChars - emittedChars - separatorCost
            if (remaining <= 0) {
                truncated = true
                break
            }
            val emittedText = if (line.text.length > remaining) {
                truncated = true
                line.text.take(remaining)
            } else {
                line.text
            }
            emitted += line.copy(index = emitted.size + 1, text = emittedText)
            emittedChars += emittedText.length + separatorCost
            if (truncated) break
        }

        return AccessibilityContentExtraction(
            packageName = state.packageName,
            activityName = state.activityName,
            lines = emitted,
            rawCharCount = rawCharCount,
            rawLineCount = rawLines.size,
            emittedCharCount = emittedChars,
            maxContentChars = maxContentChars,
            truncated = truncated,
            nodeCount = state.nodes.size
        )
    }

    fun search(extraction: AccessibilityContentExtraction, request: AccessibilityContentSearchQuery): JSONObject {
        val section = request.sectionLabel?.trim()?.takeIf { it.isNotBlank() }
        val query = request.query?.trim()?.takeIf { it.isNotBlank() }
        val exclude = request.exclude?.trim()?.takeIf { it.isNotBlank() }
        val maxMatches = request.maxMatches.coerceIn(1, 20)
        val effectiveQuery = if (query != null && section != null && normalize(query) == normalize(section)) null else query
        val matches = mutableListOf<JSONObject>()
        var inSection = section == null
        var candidateCount = 0

        for (line in extraction.lines) {
            val lineText = line.text.trim()
            if (lineText.isBlank()) continue
            val containsSection = section?.let { lineText.contains(it, ignoreCase = true) } ?: false
            if (section != null) {
                if (!inSection && containsSection) {
                    inSection = true
                } else if (inSection && looksLikeNewSection(lineText) && !containsSection) {
                    break
                }
                if (!inSection) continue
            }

            val candidates = lineCandidates(line, section)
            for ((candidateText, fromSectionLine) in candidates) {
                if (candidateText.isBlank()) continue
                if (!matchesQuery(candidateText, effectiveQuery, section, fromSectionLine)) continue
                if (exclude != null && candidateText.contains(exclude, ignoreCase = true)) continue
                candidateCount += 1
                val ordinal = request.ordinal
                if (ordinal != null && ordinal > 0 && candidateCount != ordinal) continue
                matches += JSONObject()
                    .put("text", candidateText.cap(AccessibilityContentLimits.MAX_LINE_TEXT_CHARS))
                    .put("lineText", lineText.cap(AccessibilityContentLimits.MAX_LINE_TEXT_CHARS))
                    .put("lineIndex", line.index)
                    .put("nodeId", line.nodeId ?: JSONObject.NULL)
                    .put("sourceField", line.sourceField)
                    .put("visible", line.visible)
                    .put("clickable", line.clickable)
                    .put("editable", line.editable)
                    .put("focused", line.focused)
                    .put("heading", line.heading)
                if (matches.size >= maxMatches) break
            }
            if (matches.size >= maxMatches) break
        }

        return JSONObject()
            .put("query", query ?: JSONObject.NULL)
            .put("sectionLabel", section ?: JSONObject.NULL)
            .put("exclude", exclude ?: JSONObject.NULL)
            .put("ordinal", request.ordinal ?: JSONObject.NULL)
            .put("matchCount", matches.size)
            .put("candidateCount", candidateCount)
            .put("matches", JSONArray(matches))
            .put(
                "provenance",
                JSONObject()
                    .put("source", "accessibility")
                    .put("packageName", extraction.packageName ?: JSONObject.NULL)
                    .put("activityName", extraction.activityName ?: JSONObject.NULL)
                    .put("rawCharCount", extraction.rawCharCount)
                    .put("emittedChars", extraction.emittedCharCount)
                    .put("truncated", extraction.truncated)
            )
    }

    fun search(state: PortalState, request: AccessibilityContentSearchQuery): JSONObject =
        search(extract(state, AccessibilityContentLimits.SEARCH_CONTEXT_MAX_CHARS), request)

    private fun UiNode.contentFields(): List<Pair<String, String>> = buildList {
        if (!password) text?.let { add("text" to it) }
        contentDescription?.let { add("contentDescription" to it) }
        hintText?.let { add("hintText" to it) }
        stateDescription?.let { add("stateDescription" to it) }
        paneTitle?.let { add("paneTitle" to it) }
        tooltipText?.let { add("tooltipText" to it) }
    }

    private fun String.toContentLines(): List<String> = replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uE000-\\uF8FF]+"), " ")
        .lineSequence()
        .map { line -> line.replace(Regex("[ \\t]+"), " ").trim() }
        .filter { line -> line.isNotBlank() && !line.equals("null", ignoreCase = true) }
        .toList()

    private fun lineCandidates(line: AccessibilityContentLine, sectionLabel: String?): List<Pair<String, Boolean>> {
        val text = line.text.trim().trim('-', ':', '|', ' ')
        if (sectionLabel.isNullOrBlank() || !text.contains(sectionLabel, ignoreCase = true)) return listOf(text to false)
        val remainder = text.substringAfter('-', missingDelimiterValue = "").trim()
        if (remainder.isBlank() || remainder == text) return listOf(text to true)
        val split = remainder.split(',', ';')
            .map { it.trim().trim('-', ':', '|', ' ') }
            .filter { it.isNotBlank() }
        return if (split.isEmpty()) listOf(text to true) else split.map { it to true }
    }

    private fun matchesQuery(candidate: String, query: String?, sectionLabel: String?, fromSectionLine: Boolean): Boolean {
        if (query.isNullOrBlank()) return true
        if (fromSectionLine && !sectionLabel.isNullOrBlank() && normalize(query) == normalize(sectionLabel)) return true
        val normalizedCandidate = normalize(candidate)
        val queryTokens = normalize(query).split(' ').filter { it.isNotBlank() }
        return queryTokens.isNotEmpty() && queryTokens.all { normalizedCandidate.contains(it) }
    }

    private fun looksLikeNewSection(value: String): Boolean {
        val trimmed = value.trimStart()
        return trimmed.startsWith("#") || trimmed.startsWith("-") || trimmed.startsWith("*") ||
            trimmed.startsWith("o ", ignoreCase = true) || trimmed.startsWith("●") || trimmed.startsWith("○")
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun String.cap(maxChars: Int): String = if (length <= maxChars) this else take(maxChars).trimEnd() + "..."
