package ai.droidlm.execution

import ai.droidlm.context.AccessibilityContentExtractor
import ai.droidlm.context.DeviceContextAggregator
import ai.droidlm.intent.AnchorPosition
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import ai.droidlm.textedit.EditableTextSnapshot
import ai.droidlm.textedit.EditableTarget
import ai.droidlm.textedit.TextEditingController
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

internal class ArtifactToolExecutor(
    private val portalController: PortalController,
    private val textEditingController: TextEditingController,
    private val deviceContextAggregator: DeviceContextAggregator
) {
    suspend fun execute(action: DroidLmAction.ArtifactToolAction, transcript: String, diagnosticSessionId: String?): ActionResult = when (action) {
        is DroidLmAction.ArtifactToolAction.GetStructure -> getStructure(action, transcript, diagnosticSessionId)
        is DroidLmAction.ArtifactToolAction.ResolveTarget -> resolveTargetResult(action)
        is DroidLmAction.ArtifactToolAction.GetContentWindow -> getContentWindow(action)
        is DroidLmAction.ArtifactToolAction.GetSelectionState -> getSelectionState()
        is DroidLmAction.ArtifactToolAction.VerifyEndState -> verifyEndState(action)
        is DroidLmAction.ArtifactToolAction.NavigateToTarget -> navigateToTarget(action)
        is DroidLmAction.ArtifactToolAction.SetCursorAtTarget -> setCursorAtTarget(action)
        is DroidLmAction.ArtifactToolAction.SelectTarget -> selectTarget(action)
        is DroidLmAction.ArtifactToolAction.ScrollToMatch -> scrollToMatch(action)
        is DroidLmAction.ArtifactToolAction.UndoLastAction -> undoLastAction()
        is DroidLmAction.ArtifactToolAction.DocInsertAtTarget -> textEditingController.insertTextAtAnchor(
            anchorText = action.targetLabel,
            anchorPosition = action.position,
            textToInsert = action.text,
            sectionLabel = action.sectionLabel,
            occurrenceIndex = action.occurrenceIndex
        )
        is DroidLmAction.ArtifactToolAction.DocReplaceTargetText -> textEditingController.replaceText(
            targetText = action.targetText,
            replacementText = action.replacementText,
            sectionLabel = action.sectionLabel,
            occurrenceIndex = action.occurrenceIndex
        )
        is DroidLmAction.ArtifactToolAction.DocDeleteTargetText -> textEditingController.replaceText(
            targetText = action.targetText,
            replacementText = "",
            sectionLabel = action.sectionLabel,
            occurrenceIndex = action.occurrenceIndex
        )
        is DroidLmAction.ArtifactToolAction.DocApplyFormat -> applyDocFormat(action)
        is DroidLmAction.ArtifactToolAction.DocMoveBlock -> moveBlock(action.blockLabel, action.destinationLabel, action.position)
        is DroidLmAction.ArtifactToolAction.DocCreateSection -> createDocSection(action)
        is DroidLmAction.ArtifactToolAction.DocGetTargetMetadata -> targetMetadata(action.targetLabel)
        is DroidLmAction.ArtifactToolAction.DocExtractActionItems -> extractActionItems(action.targetLabel)
        is DroidLmAction.ArtifactToolAction.SheetResolveRange -> sheetResolveRange(action)
        is DroidLmAction.ArtifactToolAction.SheetSetRangeValues -> setSheetRangeValues(action.range, action.values)
        is DroidLmAction.ArtifactToolAction.SheetAppendTableRow -> appendSheetRow(action.values)
        is DroidLmAction.ArtifactToolAction.SheetUpdateRowByMatch -> updateStructuredRow(action.matchValue, action.values)
        is DroidLmAction.ArtifactToolAction.SheetApplyFormula -> setSheetRangeValues(action.range, listOf(listOf(normalizeFormula(action.formula))))
        is DroidLmAction.ArtifactToolAction.SheetSortFilterRange -> sortFilterRange(action)
        is DroidLmAction.ArtifactToolAction.SheetInsertDeleteRowsColumns -> insertDeleteRowsColumns(action)
        is DroidLmAction.ArtifactToolAction.SheetValidateTableState -> validateTableState(action)
        is DroidLmAction.ArtifactToolAction.NotionResolveBlockOrPage -> notionResolve(action)
        is DroidLmAction.ArtifactToolAction.NotionCreatePageOrBlock -> notionCreate(action)
        is DroidLmAction.ArtifactToolAction.NotionUpdateDatabaseItem -> updateStructuredRow(action.matchValue, action.properties)
        is DroidLmAction.ArtifactToolAction.NotionMoveOrReorderBlock -> moveBlock(action.blockLabel, action.destinationLabel, action.position)
    }

    private suspend fun getStructure(action: DroidLmAction.ArtifactToolAction.GetStructure, transcript: String, diagnosticSessionId: String?): ActionResult {
        val state = portalController.getState()
        val editable = editableSnapshotOrNull()
        val targets = collectTargets(state, editable?.snapshot?.text.orEmpty())
        val artifactContext = runCatching {
            deviceContextAggregator.collect(action.reason.ifBlank { transcript }, state, diagnosticSessionId = diagnosticSessionId)
                .extras
                .optJSONObject("artifactContext")
        }.getOrNull()
        val json = JSONObject()
            .put("artifactType", action.artifactType ?: artifactContext?.optJSONObject("artifact")?.optString("type") ?: JSONObject.NULL)
            .put("packageName", state.packageName ?: JSONObject.NULL)
            .put("activityName", state.activityName ?: JSONObject.NULL)
            .put("selection", selectionJson(editable))
            .put("targets", JSONArray(targets.map { it.toJson() }))
            .put("artifactContext", artifactContext ?: JSONObject.NULL)
        return ActionResult.ok("Artifact structure: ${json.toString()}")
    }

    private suspend fun resolveTargetResult(action: DroidLmAction.ArtifactToolAction.ResolveTarget): ActionResult {
        val target = resolveTarget(action.query, action.targetKind)
            ?: return ActionResult.fail("Could not resolve artifact target: ${action.query}", "ARTIFACT_TARGET_NOT_FOUND")
        return ActionResult.ok("Artifact target resolved: ${target.toJson()}")
    }

    private suspend fun getContentWindow(action: DroidLmAction.ArtifactToolAction.GetContentWindow): ActionResult {
        val editable = editableSnapshotOrNull()
        val text = editable?.snapshot?.text ?: AccessibilityContentExtractor.extract(portalController.getState()).fullText
        val target = resolveTarget(action.label ?: action.targetId.orEmpty(), null, targetId = action.targetId)
        val center = target?.selectionStart ?: action.label?.let { text.indexOf(it, ignoreCase = true).takeIf { index -> index >= 0 } } ?: 0
        val start = (center - action.beforeChars).coerceAtLeast(0)
        val end = (center + action.afterChars).coerceAtMost(text.length)
        val json = JSONObject()
            .put("target", target?.toJson() ?: JSONObject.NULL)
            .put("start", start)
            .put("end", end)
            .put("text", text.substring(start, end))
        return ActionResult.ok("Artifact content window: ${json.toString()}")
    }

    private suspend fun getSelectionState(): ActionResult {
        val editable = editableSnapshotOrNull()
        val json = selectionJson(editable)
        return ActionResult.ok("Artifact selection state: ${json.toString()}")
    }

    private suspend fun verifyEndState(action: DroidLmAction.ArtifactToolAction.VerifyEndState): ActionResult {
        val expectedText = action.expectedText?.takeIf { it.isNotBlank() }
        val label = action.label?.takeIf { it.isNotBlank() }
        val required = action.requiredEndState.lowercase()
        val editable = editableSnapshotOrNull()
        val state = portalController.getState()
        val text = editable?.snapshot?.text.orEmpty()
        val visibleText = AccessibilityContentExtractor.extract(state).fullText
        expectedText?.let {
            if (!text.contains(it, ignoreCase = true) && !visibleText.contains(it, ignoreCase = true)) {
                return ActionResult.fail("Expected text was not present: $it", "ARTIFACT_END_STATE_NOT_MET")
            }
        }
        if (required.contains("cursor")) {
            val target = resolveTarget(label ?: action.targetId.orEmpty(), null, targetId = action.targetId)
                ?: return ActionResult.fail("Cursor target was not found", "ARTIFACT_TARGET_NOT_FOUND")
            val selection = editable?.snapshot?.selectionStart
            if (selection != null && selection in target.lineStart..target.lineEndExclusive) {
                return ActionResult.ok("Verified cursor at artifact target: ${target.label}")
            }
            return ActionResult.fail("Cursor is not at artifact target: ${target.label}", "ARTIFACT_END_STATE_NOT_MET")
        }
        if (required.contains("select")) {
            val target = resolveTarget(label ?: action.targetId.orEmpty(), null, targetId = action.targetId)
                ?: return ActionResult.fail("Selection target was not found", "ARTIFACT_TARGET_NOT_FOUND")
            val start = editable?.snapshot?.selectionStart
            val end = editable?.snapshot?.selectionEnd
            if (start != null && end != null && start <= target.selectionStart && end >= target.selectionEnd) {
                return ActionResult.ok("Verified selected artifact target: ${target.label}")
            }
            return ActionResult.fail("Artifact target is not selected: ${target.label}", "ARTIFACT_END_STATE_NOT_MET")
        }
        label?.let {
            if (!text.contains(it, ignoreCase = true) && !visibleText.contains(it, ignoreCase = true)) {
                return ActionResult.fail("Artifact target is not visible or available: $it", "ARTIFACT_END_STATE_NOT_MET")
            }
        }
        return ActionResult.ok("Verified artifact end state: ${action.requiredEndState}")
    }

    private suspend fun navigateToTarget(action: DroidLmAction.ArtifactToolAction.NavigateToTarget): ActionResult {
        val label = action.label?.takeIf { it.isNotBlank() }
        val target = resolveTarget(label ?: action.targetId.orEmpty(), action.kind, action.targetId)
        if (target != null) {
            if (target.nodeId != null && target.clickable) {
                val tap = portalController.tapNode(target.nodeId)
                if (tap.success) return tap
            }
            if (target.selectionStart >= 0) {
                setCursor(target, target.selectionStart)?.let { return it }
            }
            return ActionResult.ok("Artifact target is available: ${target.label}")
        }
        if (label != null) {
            val scroll = portalController.scroll(ScrollDirection.DOWN, untilText = label)
            if (scroll.success) return ActionResult.ok("Navigated to artifact target: $label")
            return scroll
        }
        return ActionResult.fail("ARTIFACT_NAVIGATE_TO_TARGET requires label or targetId", "ARTIFACT_TARGET_REQUIRED")
    }

    private suspend fun setCursorAtTarget(action: DroidLmAction.ArtifactToolAction.SetCursorAtTarget): ActionResult {
        val target = resolveTarget(action.label ?: action.targetId.orEmpty(), null, action.targetId)
            ?: return ActionResult.fail("Could not find cursor target: ${action.label ?: action.targetId}", "ARTIFACT_TARGET_NOT_FOUND")
        val offset = when (action.position.lowercase()) {
            "end", "after" -> target.selectionEnd
            "line_end", "block_end" -> target.lineEndExclusive
            else -> target.selectionStart
        }
        return setCursor(target, offset) ?: ActionResult.fail("Target cannot accept cursor selection", "ARTIFACT_SELECTION_UNAVAILABLE")
    }

    private suspend fun selectTarget(action: DroidLmAction.ArtifactToolAction.SelectTarget): ActionResult {
        val target = resolveTarget(action.label ?: action.targetId.orEmpty(), action.selectionKind, action.targetId)
            ?: return ActionResult.fail("Could not find selection target: ${action.label ?: action.targetId}", "ARTIFACT_TARGET_NOT_FOUND")
        val editable = editableSnapshotOrNull() ?: return ActionResult.fail("No editable target found", "NO_EDITABLE")
        val selectLine = action.selectionKind?.contains("line", ignoreCase = true) == true || action.selectionKind?.contains("block", ignoreCase = true) == true
        val start = if (selectLine) target.lineStart else target.selectionStart
        val end = if (selectLine) target.lineEndExclusive else target.selectionEnd
        return textEditingController.setSelection(editable.target, start, end)
    }

    private suspend fun scrollToMatch(action: DroidLmAction.ArtifactToolAction.ScrollToMatch): ActionResult {
        val state = portalController.getState()
        if (state.hasVisibleText(action.query)) return ActionResult.ok("Artifact match is already visible: ${action.query}")
        val scroll = portalController.scroll(action.direction, untilText = action.query)
        return if (scroll.success) ActionResult.ok("Scrolled to artifact match: ${action.query}") else scroll
    }

    private suspend fun undoLastAction(): ActionResult {
        val visibleUndo = portalController.tapText("Undo")
        if (visibleUndo.success) return visibleUndo
        return portalController.sendKeyCode(KeyEvent.KEYCODE_Z)
    }

    private suspend fun applyDocFormat(action: DroidLmAction.ArtifactToolAction.DocApplyFormat): ActionResult {
        val format = action.format.lowercase()
        val prefix = when {
            format.contains("check") -> "- [ ] "
            format.contains("bullet") -> "- "
            else -> null
        } ?: return ActionResult.fail("DOC_APPLY_FORMAT supports bullet/checklist generically; use UI format controls for ${action.format}", "ARTIFACT_FORMAT_UNSUPPORTED")
        val label = action.targetLabel ?: return ActionResult.fail("DOC_APPLY_FORMAT requires targetLabel for generic formatting", "ARTIFACT_TARGET_REQUIRED")
        return rewriteFocusedText { text ->
            val line = findLineRange(text, label) ?: return@rewriteFocusedText null
            val current = text.substring(line.first, line.second)
            val formatted = if (current.trimStart().startsWith(prefix.trim())) current else prefix + current.trimStart()
            text.substring(0, line.first) + formatted + text.substring(line.second)
        }
    }

    private suspend fun moveBlock(blockLabel: String, destinationLabel: String?, position: String): ActionResult = rewriteFocusedText { text ->
        val source = findLineRange(text, blockLabel) ?: return@rewriteFocusedText null
        val block = text.substring(source.first, source.second).trimEnd('\n')
        var without = text.removeRange(source.first, source.second)
        val destination = destinationLabel?.let { findLineRange(without, it) }
        val insertAt = when {
            destination == null -> without.length
            position.equals("before", ignoreCase = true) -> destination.first
            else -> destination.second
        }
        val separatorBefore = if (insertAt > 0 && without.getOrNull(insertAt - 1) != '\n') "\n" else ""
        val separatorAfter = if (insertAt < without.length && without.getOrNull(insertAt) != '\n') "\n" else ""
        without.substring(0, insertAt) + separatorBefore + block + separatorAfter + without.substring(insertAt)
    }

    private suspend fun createDocSection(action: DroidLmAction.ArtifactToolAction.DocCreateSection): ActionResult {
        val content = buildString {
            append("\n\n")
            append(action.title.trim())
            append("\n")
            action.bodyText?.takeIf { it.isNotBlank() }?.let {
                append(it.trim())
                append("\n")
            }
        }
        val anchor = action.afterLabel
        return if (anchor.isNullOrBlank()) {
            textEditingController.appendText(content)
        } else {
            textEditingController.insertTextAtAnchor(anchor, AnchorPosition.AFTER, content)
        }
    }

    private suspend fun targetMetadata(label: String): ActionResult {
        val target = resolveTarget(label, null) ?: return ActionResult.fail("Document target not found: $label", "ARTIFACT_TARGET_NOT_FOUND")
        val json = target.toJson()
            .put("heading", target.kind == "heading" || target.kind == "section")
            .put("lineLength", (target.lineEndExclusive - target.lineStart).coerceAtLeast(0))
        return ActionResult.ok("Document target metadata: ${json.toString()}")
    }

    private suspend fun extractActionItems(targetLabel: String?): ActionResult {
        val text = scopedText(targetLabel)
        val items = text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.contains("todo", ignoreCase = true) ||
                    line.contains("action", ignoreCase = true) ||
                    line.contains("owner", ignoreCase = true) ||
                    line.startsWith("- [ ]") ||
                    line.startsWith("- [x]", ignoreCase = true)
            }
            .take(40)
            .toList()
        return ActionResult.ok("Document action items: ${JSONObject().put("items", JSONArray(items)).toString()}")
    }

    private suspend fun sheetResolveRange(action: DroidLmAction.ArtifactToolAction.SheetResolveRange): ActionResult {
        val query = action.query.trim()
        val range = CELL_OR_RANGE_REGEX.find(query)?.value
        val target = if (range == null) resolveTarget(query, "cell") else null
        val json = JSONObject()
            .put("query", query)
            .put("sheetName", action.sheetName ?: JSONObject.NULL)
            .put("range", range ?: JSONObject.NULL)
            .put("target", target?.toJson() ?: JSONObject.NULL)
            .put("resolved", range != null || target != null)
        return if (range != null || target != null) {
            ActionResult.ok("Spreadsheet range resolved: ${json.toString()}")
        } else {
            ActionResult.fail("Spreadsheet range not found: $query", "SHEET_RANGE_NOT_FOUND")
        }
    }

    private suspend fun setSheetRangeValues(range: String?, values: List<List<String>>): ActionResult {
        if (values.isEmpty()) return ActionResult.fail("SHEET_SET_RANGE_VALUES requires values", "SHEET_VALUES_REQUIRED")
        val text = values.joinToString("\n") { row -> row.joinToString("\t") }
        val editable = editableSnapshotOrNull()
        if (editable != null) {
            val result = textEditingController.setFullText(editable.target, text)
            return if (result.success) ActionResult.ok("Set spreadsheet ${range ?: "current selection"}") else result
        }
        return portalController.typeText(text, clear = true)
    }

    private suspend fun appendSheetRow(values: List<String>): ActionResult {
        if (values.isEmpty()) return ActionResult.fail("SHEET_APPEND_TABLE_ROW requires values", "SHEET_VALUES_REQUIRED")
        return textEditingController.appendText("\n" + values.joinToString("\t"))
    }

    private suspend fun updateStructuredRow(matchValue: String, values: Map<String, String>): ActionResult = rewriteFocusedText { text ->
        val lines = text.split('\n').toMutableList()
        val index = lines.indexOfFirst { it.contains(matchValue, ignoreCase = true) }
        if (index < 0) return@rewriteFocusedText null
        val suffix = values.entries.joinToString("\t") { (key, value) -> "$key: $value" }
        lines[index] = if (suffix.isBlank()) lines[index] else lines[index] + "\t" + suffix
        lines.joinToString("\n")
    }

    private suspend fun sortFilterRange(action: DroidLmAction.ArtifactToolAction.SheetSortFilterRange): ActionResult {
        val target = action.sortBy ?: action.filterColumn ?: action.range
            ?: return ActionResult.fail("SHEET_SORT_FILTER_RANGE requires range, sortBy, or filterColumn", "SHEET_TARGET_REQUIRED")
        val tap = portalController.tapText(target)
        return if (tap.success) {
            ActionResult.ok("Opened spreadsheet sort/filter target: $target")
        } else {
            ActionResult.fail("Sort/filter target is not visible: $target", "SHEET_TARGET_NOT_VISIBLE")
        }
    }

    private suspend fun insertDeleteRowsColumns(action: DroidLmAction.ArtifactToolAction.SheetInsertDeleteRowsColumns): ActionResult {
        if (!action.axis.equals("row", ignoreCase = true) && !action.axis.equals("rows", ignoreCase = true)) {
            return ActionResult.fail("Generic insert/delete supports rows only; use UI controls for columns", "SHEET_AXIS_UNSUPPORTED")
        }
        return rewriteFocusedText { text ->
            val lines = text.split('\n').toMutableList()
            val index = (action.index ?: lines.size).coerceIn(0, lines.size)
            val count = action.count.coerceAtLeast(1)
            if (action.operation.contains("delete", ignoreCase = true)) {
                repeat(count.coerceAtMost(lines.size - index)) { lines.removeAt(index) }
            } else {
                repeat(count) { lines.add(index, "") }
            }
            lines.joinToString("\n")
        }
    }

    private suspend fun validateTableState(action: DroidLmAction.ArtifactToolAction.SheetValidateTableState): ActionResult {
        val text = editableSnapshotOrNull()?.snapshot?.text ?: AccessibilityContentExtractor.extract(portalController.getState()).fullText
        action.expectedText?.let {
            if (!text.contains(it, ignoreCase = true)) return ActionResult.fail("Expected table text missing: $it", "SHEET_TABLE_STATE_NOT_MET")
        }
        action.expectedRowCount?.let { expected ->
            val actual = text.lines().count { it.isNotBlank() }
            if (actual != expected) return ActionResult.fail("Expected $expected rows but found $actual", "SHEET_TABLE_STATE_NOT_MET")
        }
        return ActionResult.ok("Validated spreadsheet table state")
    }

    private suspend fun notionResolve(action: DroidLmAction.ArtifactToolAction.NotionResolveBlockOrPage): ActionResult {
        val target = resolveTarget(action.query, action.kind)
            ?: return ActionResult.fail("Notion target not found: ${action.query}", "NOTION_TARGET_NOT_FOUND")
        return ActionResult.ok("Notion target resolved: ${target.toJson()}")
    }

    private suspend fun notionCreate(action: DroidLmAction.ArtifactToolAction.NotionCreatePageOrBlock): ActionResult {
        val body = buildString {
            action.title?.takeIf { it.isNotBlank() }?.let {
                append(it.trim())
                append('\n')
            }
            action.text?.takeIf { it.isNotBlank() }?.let { append(it.trim()) }
        }.ifBlank { action.blockType }
        val parent = action.parentLabel
        return if (parent.isNullOrBlank()) {
            textEditingController.insertTextAtSelection(body)
        } else {
            textEditingController.insertTextAtAnchor(parent, AnchorPosition.AFTER, "\n" + body)
        }
    }

    private suspend fun rewriteFocusedText(transform: (String) -> String?): ActionResult {
        val editable = editableSnapshotOrNull() ?: return ActionResult.fail("No editable target found", "NO_EDITABLE")
        val updated = transform(editable.snapshot.text)
            ?: return ActionResult.fail("Artifact target not found in editable text", "ARTIFACT_TARGET_NOT_FOUND")
        return textEditingController.setFullText(editable.target, updated)
    }

    private suspend fun scopedText(label: String?): String {
        val text = editableSnapshotOrNull()?.snapshot?.text ?: AccessibilityContentExtractor.extract(portalController.getState()).fullText
        if (label.isNullOrBlank()) return text
        val line = findLineRange(text, label) ?: return text
        val nextHeading = findNextHeadingStart(text, line.second)
        return text.substring(line.first, nextHeading ?: text.length)
    }

    private suspend fun resolveTarget(query: String, kind: String?, targetId: String? = null): ResolvedTarget? {
        parseTextTargetId(targetId)?.let { return it }
        val editable = editableSnapshotOrNull()
        if (editable != null) {
            resolveTextTarget(editable, query, kind)?.let { return it }
        }
        val state = portalController.getState()
        return resolveNodeTarget(state, query, kind)
    }

    private suspend fun editableSnapshotOrNull(): EditableSnapshot? {
        val target = textEditingController.getFocusedEditable() ?: return null
        return EditableSnapshot(target, textEditingController.readEditableText(target))
    }

    private fun resolveTextTarget(editable: EditableSnapshot, query: String, kind: String?): ResolvedTarget? {
        val text = editable.snapshot.text
        val index = text.indexOf(query, ignoreCase = true).takeIf { it >= 0 } ?: return null
        val line = lineRangeAt(text, index)
        val label = text.substring(index, (index + query.length).coerceAtMost(text.length))
        return ResolvedTarget(
            label = label,
            kind = kind ?: inferTextKind(text, line),
            nodeId = editable.target.nodeId,
            selectionStart = index,
            selectionEnd = index + label.length,
            lineStart = line.first,
            lineEndExclusive = line.second,
            text = text.substring(line.first, line.second).trim(),
            clickable = false
        )
    }

    private fun resolveNodeTarget(state: PortalState, query: String, kind: String?): ResolvedTarget? {
        if (query.isBlank()) return null
        return state.nodes.asSequence()
            .mapNotNull { node -> node.nodeLabel()?.let { label -> node to label } }
            .firstOrNull { (_, label) -> label.contains(query, ignoreCase = true) || query.contains(label, ignoreCase = true) }
            ?.let { (node, label) ->
                ResolvedTarget(
                    label = label,
                    kind = kind ?: if (node.heading || node.collectionItemInfo?.heading == true) "heading" else "node",
                    nodeId = node.nodeId,
                    selectionStart = -1,
                    selectionEnd = -1,
                    lineStart = -1,
                    lineEndExclusive = -1,
                    text = label,
                    clickable = node.clickable
                )
            }
    }

    private fun collectTargets(state: PortalState, editableText: String): List<ResolvedTarget> {
        val textTargets = editableText.lineSequence()
            .fold(0 to mutableListOf<ResolvedTarget>()) { (offset, targets), line ->
                val trimmed = line.trim()
                if (trimmed.length in 2..160) {
                    val start = editableText.indexOf(line, offset).takeIf { it >= 0 } ?: offset
                    targets += ResolvedTarget(
                        label = trimmed,
                        kind = inferTextKind(editableText, start to (start + line.length)),
                        nodeId = null,
                        selectionStart = start + line.indexOf(trimmed).coerceAtLeast(0),
                        selectionEnd = start + line.indexOf(trimmed).coerceAtLeast(0) + trimmed.length,
                        lineStart = start,
                        lineEndExclusive = start + line.length,
                        text = trimmed,
                        clickable = false
                    )
                    start + line.length + 1 to targets
                } else {
                    offset + line.length + 1 to targets
                }
            }
            .second
        val nodeTargets = state.nodes.mapNotNull { node ->
            node.nodeLabel()?.let { label ->
                ResolvedTarget(
                    label = label,
                    kind = if (node.heading || node.collectionItemInfo?.heading == true) "heading" else "node",
                    nodeId = node.nodeId,
                    selectionStart = -1,
                    selectionEnd = -1,
                    lineStart = -1,
                    lineEndExclusive = -1,
                    text = label,
                    clickable = node.clickable
                )
            }
        }
        return (textTargets + nodeTargets).distinctBy { it.id }.take(120)
    }

    private suspend fun setCursor(target: ResolvedTarget, offset: Int): ActionResult? {
        val editable = editableSnapshotOrNull() ?: return null
        val safeOffset = offset.coerceIn(0, editable.snapshot.text.length)
        return textEditingController.setSelection(editable.target, safeOffset, safeOffset)
    }

    private fun selectionJson(editable: EditableSnapshot?): JSONObject = JSONObject()
        .put("nodeId", editable?.target?.nodeId ?: JSONObject.NULL)
        .put("selectionStart", editable?.snapshot?.selectionStart ?: JSONObject.NULL)
        .put("selectionEnd", editable?.snapshot?.selectionEnd ?: JSONObject.NULL)
        .put("selectedText", selectedText(editable) ?: JSONObject.NULL)
        .put("cursorKnown", editable?.snapshot?.selectionStart != null && editable?.snapshot?.selectionEnd != null)

    private fun selectedText(editable: EditableSnapshot?): String? {
        val snapshot = editable?.snapshot ?: return null
        val start = snapshot.selectionStart ?: return null
        val end = snapshot.selectionEnd ?: return null
        return if (end > start && start >= 0 && end <= snapshot.text.length) snapshot.text.substring(start, end) else ""
    }

    private fun parseTextTargetId(targetId: String?): ResolvedTarget? {
        val parts = targetId?.split(':') ?: return null
        if (parts.size < 4 || parts[0] != "text") return null
        val start = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val end = parts.getOrNull(2)?.toIntOrNull() ?: return null
        val label = parts.drop(3).joinToString(":").ifBlank { "target" }
        return ResolvedTarget(label, "text", null, start, end, start, end, label, clickable = false)
    }

    private fun findLineRange(text: String, query: String): Pair<Int, Int>? {
        val index = text.indexOf(query, ignoreCase = true).takeIf { it >= 0 } ?: return null
        return lineRangeAt(text, index)
    }

    private fun lineRangeAt(text: String, index: Int): Pair<Int, Int> {
        val start = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return start to end
    }

    private fun findNextHeadingStart(text: String, start: Int): Int? {
        val lines = text.split('\n')
        var offset = 0
        for (line in lines) {
            val end = offset + line.length
            if (offset > start && looksLikeHeading(text, offset to end)) return offset
            offset = end + 1
        }
        return null
    }

    private fun inferTextKind(text: String, range: Pair<Int, Int>): String = if (looksLikeHeading(text, range)) "heading" else "text"

    private fun looksLikeHeading(text: String, range: Pair<Int, Int>): Boolean {
        val line = text.substring(range.first, range.second).trim()
        if (line.isBlank() || line.length > 80 || line.contains(':')) return false
        val before = text.substring(0, range.first).substringAfterLast('\n', "").trim()
        val after = text.substring(range.second).substringAfter('\n', "").lineSequence().firstOrNull().orEmpty().trim()
        return before.isBlank() && after.isNotBlank()
    }

    private fun UiNode.nodeLabel(): String? = listOfNotNull(text, contentDescription, hintText, stateDescription)
        .firstOrNull { it.isNotBlank() && it.length <= 180 }
        ?.trim()

    private fun PortalState.hasVisibleText(query: String): Boolean = nodes.any { node ->
        listOfNotNull(node.text, node.contentDescription, node.hintText, node.stateDescription).any { it.contains(query, ignoreCase = true) }
    }

    private fun normalizeFormula(formula: String): String = if (formula.trim().startsWith("=")) formula.trim() else "=${formula.trim()}"

    private data class EditableSnapshot(val target: EditableTarget, val snapshot: EditableTextSnapshot)

    private data class ResolvedTarget(
        val label: String,
        val kind: String,
        val nodeId: String?,
        val selectionStart: Int,
        val selectionEnd: Int,
        val lineStart: Int,
        val lineEndExclusive: Int,
        val text: String,
        val clickable: Boolean
    ) {
        val id: String = if (selectionStart >= 0) "text:$selectionStart:$selectionEnd:$label" else "node:${nodeId ?: label}"

        fun toJson(): JSONObject = JSONObject()
            .put("targetId", id)
            .put("label", label)
            .put("kind", kind)
            .put("nodeId", nodeId ?: JSONObject.NULL)
            .put("selectionStart", if (selectionStart >= 0) selectionStart else JSONObject.NULL)
            .put("selectionEnd", if (selectionEnd >= 0) selectionEnd else JSONObject.NULL)
            .put("lineStart", if (lineStart >= 0) lineStart else JSONObject.NULL)
            .put("lineEnd", if (lineEndExclusive >= 0) lineEndExclusive else JSONObject.NULL)
            .put("text", text)
            .put("clickable", clickable)
    }

    private companion object {
        private val CELL_OR_RANGE_REGEX = Regex("[A-Z]{1,3}[0-9]{1,6}(?::[A-Z]{1,3}[0-9]{1,6})?")
    }
}
