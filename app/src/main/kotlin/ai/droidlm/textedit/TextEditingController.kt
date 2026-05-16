package ai.droidlm.textedit

import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.intent.AnchorPosition
import ai.droidlm.intent.DocumentEdit
import ai.droidlm.intent.DocumentEditOperation
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.ocr.TextCoordinateMapper
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.PortalController
import ai.droidlm.relay.RelayClient

class TextEditingController(
    private val portalController: PortalController,
    private val ocrEngine: OcrEngine,
    @Suppress("unused") private val relayClient: RelayClient,
    private val actionLogRepository: ActionLogRepository,
    private val debugLogStore: DebugLogStore? = null,
    private val coordinateMapper: TextCoordinateMapper = TextCoordinateMapper()
) {
    suspend fun getFocusedEditable(): EditableTarget? = portalController.findFocusedEditableNode()
        ?: portalController.findEditableNodes().firstOrNull()

    suspend fun readEditableText(target: EditableTarget): EditableTextSnapshot {
        val nodeId = target.nodeId ?: return EditableTextSnapshot("", null, null, null, TextSnapshotSource.UNKNOWN)
        return EditableTextSnapshot(
            text = portalController.getNodeText(nodeId).orEmpty(),
            selectionStart = portalController.getNodeSelection(nodeId)?.first,
            selectionEnd = portalController.getNodeSelection(nodeId)?.second,
            hint = null,
            source = TextSnapshotSource.ACCESSIBILITY
        )
    }

    suspend fun setSelection(target: EditableTarget, start: Int, end: Int): ActionResult {
        val nodeId = target.nodeId ?: return ActionResult.fail("Editable target has no node id", "NO_NODE_ID")
        return portalController.performSetSelection(nodeId, start, end)
    }

    suspend fun insertTextAtSelection(text: String): ActionResult {
        actionLogRepository.log(ActionLogType.TEXT_EDIT_STARTED, "Insert text at current cursor")
        val result = portalController.inputTextAtCurrentCursor(text)
        actionLogRepository.log(ActionLogType.TEXT_EDIT_RESULT, result.message)
        return result
    }

    suspend fun replaceSelection(text: String): ActionResult = insertTextAtSelection(text)

    suspend fun setFullText(target: EditableTarget, text: String): ActionResult {
        val nodeId = target.nodeId ?: return ActionResult.fail("Editable target has no node id", "NO_NODE_ID")
        return portalController.performSetText(nodeId, text)
    }

    suspend fun moveCursorBySemanticTarget(targetDescription: String): ActionResult {
        val target = getFocusedEditable() ?: return ActionResult.fail("No editable field found", "NO_EDITABLE")
        val snapshot = readEditableText(target)
        val offset = when (targetDescription.lowercase().trim()) {
            "start", "beginning", "top" -> 0
            "end", "bottom" -> snapshot.text.length
            else -> snapshot.text.indexOf(targetDescription, ignoreCase = true).takeIf { it >= 0 }
        } ?: return ActionResult.fail("Could not find cursor target: $targetDescription", "ANCHOR_NOT_FOUND")
        return setSelection(target, offset, offset)
    }

    suspend fun insertTextAtSemanticTarget(targetDescription: String, textToInsert: String): ActionResult =
        insertTextAtAnchor(targetDescription, AnchorPosition.AFTER, textToInsert)

    suspend fun replaceTextRangeBySemanticTarget(targetDescription: String, replacementText: String): ActionResult =
        replaceText(targetDescription, replacementText)

    suspend fun insertTextAtAnchor(
        anchorText: String,
        anchorPosition: AnchorPosition,
        textToInsert: String,
        sectionLabel: String? = null,
        occurrenceIndex: Int? = null
    ): ActionResult {
        actionLogRepository.log(ActionLogType.TEXT_EDIT_STARTED, "Insert text ${anchorPosition.name.lowercase()} anchor")
        val target = getFocusedEditable()
        if (target != null) {
            val snapshot = readEditableText(target)
            val preferredSection = resolvePreferredSection(snapshot, sectionLabel)
            val match = findTextMatch(snapshot.text, anchorText, preferredSection, occurrenceIndex)
                ?: if (sectionLabel == null) findTextMatch(snapshot.text, anchorText, null, occurrenceIndex) else null
            if (match != null) {
                val offset = if (anchorPosition == AnchorPosition.AFTER) match.endExclusive else match.start
                val selectionResult = setSelection(target, offset, offset)
                if (selectionResult.success) {
                    val insertResult = portalController.inputTextAtCurrentCursor(textToInsert)
                    val verified = verifyTextContains(target, expected = textToInsert, sectionLabel = sectionLabel)
                    val result = if (insertResult.success && verified) {
                        ActionResult.ok("Inserted text at anchor $anchorText")
                    } else if (insertResult.success) {
                        ActionResult.ok("Inserted text, but verification was uncertain")
                    } else {
                        insertResult
                    }
                    actionLogRepository.log(ActionLogType.TEXT_EDIT_RESULT, result.message)
                    return result
                }
                val fallbackText = snapshot.text.substring(0, offset) + textToInsert + snapshot.text.substring(offset)
                val fallback = setFullText(target, fallbackText)
                actionLogRepository.log(ActionLogType.TEXT_EDIT_RESULT, fallback.message)
                return fallback
            }
        }
        return insertTextAtAnchorByOcr(anchorText, anchorPosition, textToInsert)
    }

    suspend fun replaceText(
        targetText: String,
        replacementText: String,
        sectionLabel: String? = null,
        occurrenceIndex: Int? = null
    ): ActionResult {
        actionLogRepository.log(ActionLogType.TEXT_EDIT_STARTED, "Replace requested text")
        val target = getFocusedEditable() ?: return ActionResult.fail("No editable field found", "NO_EDITABLE")
        val snapshot = readEditableText(target)
        val preferredSection = resolvePreferredSection(snapshot, sectionLabel)
        val match = findTextMatch(snapshot.text, targetText, preferredSection, occurrenceIndex)
            ?: if (sectionLabel == null) findTextMatch(snapshot.text, targetText, null, occurrenceIndex) else null
        if (match == null) return ActionResult.fail("Could not find text: $targetText", "TEXT_NOT_FOUND")
        val selection = setSelection(target, match.start, match.endExclusive)
        val result = if (selection.success) {
            portalController.inputTextAtCurrentCursor(replacementText)
        } else {
            val reconstructed = snapshot.text.substring(0, match.start) + replacementText + snapshot.text.substring(match.endExclusive)
            setFullText(target, reconstructed)
        }
        val verified = result.success && verifyTextContains(target, replacementText, sectionLabel)
        val finalResult = if (verified) ActionResult.ok("Replaced $targetText") else result
        actionLogRepository.log(ActionLogType.TEXT_EDIT_RESULT, finalResult.message)
        return finalResult
    }

    suspend fun applyDocumentEdits(defaultSectionLabel: String?, edits: List<DocumentEdit>): ActionResult {
        actionLogRepository.log(ActionLogType.TEXT_EDIT_STARTED, "Apply document edits")
        val target = getFocusedEditable() ?: return ActionResult.fail("No editable field found", "NO_EDITABLE")
        val snapshot = readEditableText(target)
        var workingText = snapshot.text
        for (edit in edits) {
            val effectiveSection = edit.sectionLabel ?: defaultSectionLabel
            workingText = when (edit.operation) {
                DocumentEditOperation.REPLACE_TEXT_RANGE -> {
                    val targetText = edit.targetText.orEmpty()
                    val replacementText = edit.replacementText.orEmpty()
                    val preferredSection = resolvePreferredSection(workingText, effectiveSection, selectionStart = null, selectionEnd = null)
                    val match = findTextMatch(workingText, targetText, preferredSection, edit.occurrenceIndex)
                        ?: if (effectiveSection == null) findTextMatch(workingText, targetText, null, edit.occurrenceIndex) else null
                    if (match == null) return ActionResult.fail("Could not find text: $targetText", "TEXT_NOT_FOUND")
                    workingText.substring(0, match.start) + replacementText + workingText.substring(match.endExclusive)
                }
                DocumentEditOperation.INSERT_TEXT_AT_ANCHOR -> {
                    val anchorText = edit.anchorText.orEmpty()
                    val insertText = edit.text.orEmpty()
                    val preferredSection = resolvePreferredSection(workingText, effectiveSection, selectionStart = null, selectionEnd = null)
                    val match = findTextMatch(workingText, anchorText, preferredSection, edit.occurrenceIndex)
                        ?: if (effectiveSection == null) findTextMatch(workingText, anchorText, null, edit.occurrenceIndex) else null
                    if (match == null) return ActionResult.fail("Could not find anchor: $anchorText", "TEXT_NOT_FOUND")
                    val offset = if (edit.anchorPosition == AnchorPosition.AFTER) match.endExclusive else match.start
                    workingText.substring(0, offset) + insertText + workingText.substring(offset)
                }
            }
        }
        val result = setFullText(target, workingText)
        val verified = result.success && edits.all { edit ->
            when (edit.operation) {
                DocumentEditOperation.REPLACE_TEXT_RANGE -> {
                    val expected = edit.replacementText.orEmpty()
                    expected.isBlank() || verifyTextContains(target, expected, edit.sectionLabel ?: defaultSectionLabel)
                }
                DocumentEditOperation.INSERT_TEXT_AT_ANCHOR -> {
                    val expected = edit.text.orEmpty()
                    expected.isBlank() || verifyTextContains(target, expected, edit.sectionLabel ?: defaultSectionLabel)
                }
            }
        }
        val finalResult = if (verified) ActionResult.ok("Applied ${edits.size} document edits") else result
        actionLogRepository.log(ActionLogType.TEXT_EDIT_RESULT, finalResult.message)
        return finalResult
    }

    suspend fun appendText(text: String): ActionResult {
        val target = getFocusedEditable() ?: return ActionResult.fail("No editable field found", "NO_EDITABLE")
        val snapshot = readEditableText(target)
        val selection = setSelection(target, snapshot.text.length, snapshot.text.length)
        return if (selection.success) portalController.inputTextAtCurrentCursor(text) else setFullText(target, snapshot.text + text)
    }

    suspend fun prependText(text: String): ActionResult {
        val target = getFocusedEditable() ?: return ActionResult.fail("No editable field found", "NO_EDITABLE")
        val snapshot = readEditableText(target)
        val selection = setSelection(target, 0, 0)
        return if (selection.success) portalController.inputTextAtCurrentCursor(text) else setFullText(target, text + snapshot.text)
    }

    private suspend fun insertTextAtAnchorByOcr(anchorText: String, anchorPosition: AnchorPosition, textToInsert: String): ActionResult {
        debugLogStore?.recordEvent(
            "text_edit_ocr_fallback_started",
            mapOf("anchorLength" to anchorText.length, "anchorPosition" to anchorPosition.name, "insertLength" to textToInsert.length)
        )
        val screenshot = portalController.takeScreenshot()
        if (!screenshot.success || screenshot.bitmap == null) {
            debugLogStore?.recordEvent(
                "text_edit_ocr_screenshot_failed",
                mapOf("message" to screenshot.message, "errorCode" to screenshot.errorCode, "hasBitmap" to (screenshot.bitmap != null))
            )
            return ActionResult.fail("Accessibility text and OCR fallback are unavailable: ${screenshot.message}", screenshot.errorCode)
        }
        debugLogStore?.retainScreenshot(screenshot.bitmap, "text-edit-ocr-fallback")
        actionLogRepository.log(ActionLogType.SCREENSHOT_CAPTURED, "Screenshot captured for OCR fallback")
        actionLogRepository.log(ActionLogType.OCR_STARTED, "Running on-device OCR")
        val ocrResult = runCatching { ocrEngine.recognize(screenshot.bitmap) }
            .getOrElse {
                debugLogStore?.recordEvent("text_edit_ocr_failed", mapOf("message" to it.message, "errorClass" to it::class.java.name))
                return ActionResult.fail("OCR failed: ${it.message}", "OCR_FAILED")
            }
        debugLogStore?.recordEvent(
            "text_edit_ocr_completed",
            mapOf("lineCount" to ocrResult.lines.size, "elementCount" to ocrResult.elements.size, "source" to ocrResult.source.name)
        )
        actionLogRepository.log(ActionLogType.OCR_RESULT, "OCR completed")
        val coordinate = when (anchorPosition) {
            AnchorPosition.AFTER -> coordinateMapper.estimateCoordinateAfterText(ocrResult, anchorText)
            AnchorPosition.BEFORE -> coordinateMapper.estimateCoordinateBeforeText(ocrResult, anchorText)
        } ?: run {
            debugLogStore?.recordEvent("text_edit_ocr_anchor_not_found", mapOf("anchorLength" to anchorText.length, "anchorPosition" to anchorPosition.name))
            return ActionResult.fail("OCR could not locate anchor: $anchorText", "OCR_ANCHOR_NOT_FOUND")
        }
        debugLogStore?.recordEvent("text_edit_ocr_coordinate_estimated", mapOf("x" to coordinate.x, "y" to coordinate.y))
        val tap = portalController.tap(coordinate.x, coordinate.y)
        if (!tap.success) {
            debugLogStore?.recordEvent("text_edit_ocr_tap_failed", mapOf("message" to tap.message, "errorCode" to tap.errorCode))
            return tap
        }
        val input = portalController.inputTextAtCurrentCursor(textToInsert)
        debugLogStore?.recordEvent("text_edit_ocr_input_result", mapOf("success" to input.success, "message" to input.message, "errorCode" to input.errorCode))
        return if (input.success) ActionResult.ok("Inserted text using OCR coordinate estimate") else input
    }

    private suspend fun verifyTextContains(target: EditableTarget, expected: String, sectionLabel: String?): Boolean {
        val updatedSnapshot = readEditableText(target)
        val scopedText = resolvePreferredSection(updatedSnapshot, sectionLabel)
            ?.let { section -> updatedSnapshot.text.substring(section.start, section.endExclusive.coerceAtMost(updatedSnapshot.text.length)) }
            ?: updatedSnapshot.text
        return expected.isBlank() || scopedText.contains(expected)
    }

    private fun resolvePreferredSection(snapshot: EditableTextSnapshot, explicitSectionLabel: String?): DocSection? =
        resolvePreferredSection(snapshot.text, explicitSectionLabel, snapshot.selectionStart, snapshot.selectionEnd)

    private fun resolvePreferredSection(
        text: String,
        explicitSectionLabel: String?,
        selectionStart: Int?,
        selectionEnd: Int?
    ): DocSection? {
        val normalizedText = text.replace("\r\n", "\n")
        explicitSectionLabel?.let { label ->
            findSectionByLabel(normalizedText, label)?.let { return it }
        }
        val cursor = selectionStart ?: selectionEnd ?: return null
        return findSectionAtSelection(normalizedText, cursor)
    }

    private fun findTextMatch(text: String, targetText: String, section: DocSection?, occurrenceIndex: Int?): TextMatch? {
        if (targetText.isBlank()) return null
        val start = section?.start ?: 0
        val endExclusive = section?.endExclusive ?: text.length
        val desiredOccurrence = occurrenceIndex?.coerceAtLeast(1) ?: 1
        var cursor = start
        var occurrence = 0
        while (cursor <= endExclusive - targetText.length) {
            val index = text.indexOf(targetText, cursor, ignoreCase = true)
            if (index < 0 || index + targetText.length > endExclusive) break
            occurrence += 1
            if (occurrence == desiredOccurrence) {
                return TextMatch(start = index, endExclusive = index + targetText.length)
            }
            cursor = (index + targetText.length).coerceAtLeast(cursor + 1)
        }
        return null
    }

    private fun findSectionByLabel(text: String, label: String): DocSection? {
        val normalizedLabel = normalizeSectionLabel(label)
        if (normalizedLabel.isBlank()) return null
        val sections = extractSections(text)
        return sections.firstOrNull { normalizeSectionLabel(it.heading) == normalizedLabel }
            ?: sections.firstOrNull { normalizeSectionLabel(it.heading).contains(normalizedLabel) }
    }

    private fun findSectionAtSelection(text: String, selection: Int): DocSection? {
        val clamped = selection.coerceIn(0, text.length)
        return extractSections(text).lastOrNull { clamped in it.start until it.endExclusive }
    }

    private fun extractSections(text: String): List<DocSection> {
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split("\n")
        if (lines.isEmpty()) return emptyList()
        val lineStarts = IntArray(lines.size)
        var offset = 0
        lines.forEachIndexed { index, line ->
            lineStarts[index] = offset
            offset += line.length + 1
        }
        val headingIndices = mutableListOf<Int>()
        for (index in 1 until lines.size) {
            val previous = lines[index - 1].trim()
            val current = lines[index].trim()
            val next = lines.getOrElse(index + 1) { "" }.trim()
            if (previous.isNotEmpty()) continue
            if (current.isEmpty() || next.isEmpty()) continue
            if (current.length > MAX_SECTION_HEADING_LENGTH) continue
            if (current.contains(':')) continue
            if (current.startsWith("-") || current.startsWith("*")) continue
            headingIndices += index
        }
        return headingIndices.mapIndexed { sectionIndex, lineIndex ->
            val start = lineStarts[lineIndex]
            val endExclusive = headingIndices.getOrNull(sectionIndex + 1)?.let { nextIndex -> lineStarts[nextIndex] } ?: normalized.length
            DocSection(
                heading = lines[lineIndex].trim(),
                start = start,
                endExclusive = endExclusive.coerceAtMost(normalized.length)
            )
        }
    }

    private fun normalizeSectionLabel(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private data class DocSection(
        val heading: String,
        val start: Int,
        val endExclusive: Int
    )

    private data class TextMatch(
        val start: Int,
        val endExclusive: Int
    )

    companion object {
        private const val MAX_SECTION_HEADING_LENGTH = 80
    }
}
