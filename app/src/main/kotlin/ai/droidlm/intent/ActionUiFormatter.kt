package ai.droidlm.intent

object ActionUiFormatter {
    fun full(action: DroidLmAction, fallbackLabel: String? = null, reason: String? = null): String = when (action) {
        is DroidLmAction.NoOp -> semanticLabel(reason, fallbackLabel) ?: "No action"
        is DroidLmAction.NeedLlmPlanning -> "Plan with AI"
        is DroidLmAction.AskConfirmation -> semanticLabel(action.confirmationPrompt, reason, fallbackLabel) ?: "Ask for confirmation"
        is DroidLmAction.OpenApp -> "Open ${appName(action)}"
        is DroidLmAction.OpenSettings -> "Open Android Settings"
        DroidLmAction.PressHome -> "Press Home"
        DroidLmAction.PressBack -> "Press Back"
        is DroidLmAction.Tap -> semanticLabel(reason, fallbackLabel) ?: "Tap the screen"
        is DroidLmAction.TapNode -> semanticLabel(reason, fallbackLabel) ?: "Tap an on-screen item"
        is DroidLmAction.FocusNode -> semanticLabel(reason, fallbackLabel) ?: "Focus an on-screen item"
        is DroidLmAction.LongPress -> semanticLabel(reason, fallbackLabel) ?: "Long-press the screen"
        is DroidLmAction.Swipe -> semanticLabel(reason, fallbackLabel) ?: "Swipe on the screen"
        is DroidLmAction.TypeText -> "Type text"
        DroidLmAction.TakeScreenshot -> "Take a screenshot"
        is DroidLmAction.FocusEditable -> semanticLabel(reason, fallbackLabel) ?: "Focus the text field"
        is DroidLmAction.SetSelection -> semanticLabel(reason, fallbackLabel) ?: "Move the text selection"
        is DroidLmAction.InsertText -> "Insert text"
        is DroidLmAction.ReplaceSelection -> "Replace selected text"
        is DroidLmAction.SetFullText -> "Replace text field contents"
        is DroidLmAction.MoveCursor -> semanticLabel(reason, fallbackLabel) ?: "Move the cursor"
        is DroidLmAction.TapTextAnchor -> "Tap ${anchorPositionPhrase(action.anchorPosition)} \"${shorten(action.anchorText, 28)}\""
        DroidLmAction.OcrScreen -> "Read the current screen"
        is DroidLmAction.AnalyzeScreenshot -> semanticLabel(reason, fallbackLabel) ?: "Analyze the current screen"
        is DroidLmAction.VerifyTextChange -> "Verify the text change"
        is DroidLmAction.InsertTextAtAnchor -> "Insert text ${anchorPositionPhrase(action.anchorPosition)} \"${shorten(action.anchorText, 28)}\""
        is DroidLmAction.ReplaceTextRange -> "Replace \"${shorten(action.targetText, 28)}\""
        is DroidLmAction.AppendText -> "Append text"
        is DroidLmAction.PrependText -> "Prepend text"
        DroidLmAction.SelectAll -> "Select all text"
        DroidLmAction.DeleteSelectedText -> "Delete selected text"
        is DroidLmAction.FormatCurrentLineAsBullet -> "Format the current line as a bullet"
        is DroidLmAction.ReplaceDocumentText -> "Replace document text"
        is DroidLmAction.AppendDocumentNote -> "Append a document note"
        is DroidLmAction.SetCurrentSheetCell -> "Set the current sheet cell"
        is DroidLmAction.AddSpreadsheetRow -> "Add a spreadsheet row"
        DroidLmAction.Done -> "Finish the task"
    }

    fun compact(action: DroidLmAction, fallbackLabel: String? = null, reason: String? = null): String = when (action) {
        is DroidLmAction.OpenApp -> "Open ${shortAppName(appName(action))}"
        is DroidLmAction.OpenSettings -> "Open Settings"
        DroidLmAction.PressHome -> "Home"
        DroidLmAction.PressBack -> "Back"
        is DroidLmAction.Tap -> compactSemanticLabel(reason, fallbackLabel) ?: "Tap"
        is DroidLmAction.TapNode -> compactSemanticLabel(reason, fallbackLabel) ?: "Tap item"
        is DroidLmAction.FocusNode -> compactSemanticLabel(reason, fallbackLabel) ?: "Focus item"
        is DroidLmAction.LongPress -> compactSemanticLabel(reason, fallbackLabel) ?: "Long press"
        is DroidLmAction.Swipe -> compactSemanticLabel(reason, fallbackLabel) ?: "Swipe"
        is DroidLmAction.TypeText -> "Type"
        DroidLmAction.TakeScreenshot -> "Screenshot"
        is DroidLmAction.FocusEditable -> "Focus field"
        is DroidLmAction.SetSelection -> "Select text"
        is DroidLmAction.InsertText -> "Insert text"
        is DroidLmAction.ReplaceSelection -> "Replace text"
        is DroidLmAction.SetFullText -> "Replace field"
        is DroidLmAction.MoveCursor -> "Move cursor"
        is DroidLmAction.TapTextAnchor -> "Tap \"${shorten(action.anchorText, 16)}\""
        DroidLmAction.OcrScreen -> "Read screen"
        is DroidLmAction.AnalyzeScreenshot -> "Analyze screen"
        is DroidLmAction.VerifyTextChange -> "Verify text"
        is DroidLmAction.InsertTextAtAnchor -> "Insert near \"${shorten(action.anchorText, 14)}\""
        is DroidLmAction.ReplaceTextRange -> "Replace \"${shorten(action.targetText, 14)}\""
        is DroidLmAction.AppendText -> "Append text"
        is DroidLmAction.PrependText -> "Prepend text"
        DroidLmAction.SelectAll -> "Select all"
        DroidLmAction.DeleteSelectedText -> "Delete text"
        is DroidLmAction.FormatCurrentLineAsBullet -> "Bullet line"
        is DroidLmAction.ReplaceDocumentText -> "Replace doc text"
        is DroidLmAction.AppendDocumentNote -> "Append note"
        is DroidLmAction.SetCurrentSheetCell -> "Set cell"
        is DroidLmAction.AddSpreadsheetRow -> "Add row"
        is DroidLmAction.AskConfirmation -> "Confirm"
        is DroidLmAction.NeedLlmPlanning -> "Plan"
        is DroidLmAction.NoOp -> compactSemanticLabel(reason, fallbackLabel) ?: "No action"
        DroidLmAction.Done -> "Done"
    }

    fun reasonAddsDetail(reason: String, actionLabel: String): Boolean {
        val cleanedReason = cleanPhrase(reason) ?: return false
        if (isGeneric(cleanedReason)) return false
        val cleanedAction = cleanPhrase(actionLabel) ?: return true
        return !cleanedReason.equals(cleanedAction, ignoreCase = true) &&
            !cleanedReason.contains(cleanedAction, ignoreCase = true) &&
            !cleanedAction.contains(cleanedReason, ignoreCase = true)
    }

    private fun appName(action: DroidLmAction.OpenApp): String = action.appName?.takeIf { it.isNotBlank() } ?: action.packageName

    private fun semanticLabel(vararg candidates: String?): String? = candidates
        .asSequence()
        .mapNotNull { cleanPhrase(it) }
        .firstOrNull { label -> !isGeneric(label) }

    private fun compactSemanticLabel(vararg candidates: String?): String? = semanticLabel(*candidates)?.let { shorten(it, 22) }

    private fun cleanPhrase(value: String?): String? {
        val cleaned = value
            ?.replace('_', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.trim('.', ':', ';', '-')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return if (cleaned.all { char -> !char.isLetter() || char.isUpperCase() }) {
            cleaned.lowercase().replaceFirstChar { it.titlecase() }
        } else {
            cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun isGeneric(label: String): Boolean {
        val normalized = label.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        return normalized in setOf(
            "open app",
            "tap",
            "tap node",
            "focus node",
            "type text",
            "insert text",
            "replace text",
            "set full text",
            "analyze screenshot",
            "ocr screen",
            "no op",
            "done",
            "reason"
        )
    }

    private fun shortAppName(name: String): String = name
        .removePrefix("Google ")
        .removePrefix("Android ")
        .let { shorten(it, 18) }

    private fun anchorPositionPhrase(position: AnchorPosition): String = when (position) {
        AnchorPosition.BEFORE -> "before"
        AnchorPosition.AFTER -> "after"
    }

    private fun shorten(value: String, maxChars: Int): String {
        val cleaned = value.replace(Regex("\\s+"), " ").trim()
        return if (cleaned.length <= maxChars) cleaned else cleaned.take((maxChars - 1).coerceAtLeast(1)).trimEnd() + "..."
    }
}
