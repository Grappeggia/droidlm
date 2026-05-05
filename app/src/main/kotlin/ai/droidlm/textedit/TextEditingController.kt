package ai.droidlm.textedit

import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.intent.AnchorPosition
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

    suspend fun insertTextAtAnchor(anchorText: String, anchorPosition: AnchorPosition, textToInsert: String): ActionResult {
        actionLogRepository.log(ActionLogType.TEXT_EDIT_STARTED, "Insert text ${anchorPosition.name.lowercase()} anchor")
        val target = getFocusedEditable()
        if (target != null) {
            val snapshot = readEditableText(target)
            val index = snapshot.text.indexOf(anchorText, ignoreCase = true)
            if (index >= 0) {
                val offset = if (anchorPosition == AnchorPosition.AFTER) index + anchorText.length else index
                val selectionResult = setSelection(target, offset, offset)
                if (selectionResult.success) {
                    val insertResult = portalController.inputTextAtCurrentCursor(textToInsert)
                    val verified = verifyTextContains(target, expected = textToInsert)
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

    suspend fun replaceText(targetText: String, replacementText: String): ActionResult {
        actionLogRepository.log(ActionLogType.TEXT_EDIT_STARTED, "Replace requested text")
        val target = getFocusedEditable() ?: return ActionResult.fail("No editable field found", "NO_EDITABLE")
        val snapshot = readEditableText(target)
        val index = snapshot.text.indexOf(targetText, ignoreCase = true)
        if (index < 0) return ActionResult.fail("Could not find text: $targetText", "TEXT_NOT_FOUND")
        val end = index + targetText.length
        val selection = setSelection(target, index, end)
        val result = if (selection.success) {
            portalController.inputTextAtCurrentCursor(replacementText)
        } else {
            val reconstructed = snapshot.text.substring(0, index) + replacementText + snapshot.text.substring(end)
            setFullText(target, reconstructed)
        }
        val verified = result.success && verifyTextContains(target, replacementText)
        val finalResult = if (verified) ActionResult.ok("Replaced $targetText") else result
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
        val screenshot = portalController.takeScreenshot()
        if (!screenshot.success || screenshot.bitmap == null) {
            return ActionResult.fail("Accessibility text and OCR fallback are unavailable: ${screenshot.message}", screenshot.errorCode)
        }
        debugLogStore?.retainScreenshot(screenshot.bitmap, "text-edit-ocr-fallback")
        actionLogRepository.log(ActionLogType.SCREENSHOT_CAPTURED, "Screenshot captured for OCR fallback")
        actionLogRepository.log(ActionLogType.OCR_STARTED, "Running on-device OCR")
        val ocrResult = runCatching { ocrEngine.recognize(screenshot.bitmap) }
            .getOrElse { return ActionResult.fail("OCR failed: ${it.message}", "OCR_FAILED") }
        actionLogRepository.log(ActionLogType.OCR_RESULT, "OCR completed")
        val coordinate = when (anchorPosition) {
            AnchorPosition.AFTER -> coordinateMapper.estimateCoordinateAfterText(ocrResult, anchorText)
            AnchorPosition.BEFORE -> coordinateMapper.estimateCoordinateBeforeText(ocrResult, anchorText)
        } ?: return ActionResult.fail("OCR could not locate anchor: $anchorText", "OCR_ANCHOR_NOT_FOUND")
        val tap = portalController.tap(coordinate.x, coordinate.y)
        if (!tap.success) return tap
        val input = portalController.inputTextAtCurrentCursor(textToInsert)
        return if (input.success) ActionResult.ok("Inserted text using OCR coordinate estimate") else input
    }

    private suspend fun verifyTextContains(target: EditableTarget, expected: String): Boolean {
        val updated = readEditableText(target).text
        return expected.isBlank() || updated.contains(expected)
    }
}
