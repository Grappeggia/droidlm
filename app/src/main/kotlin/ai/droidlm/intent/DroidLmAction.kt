package ai.droidlm.intent

enum class AnchorPosition {
    BEFORE,
    AFTER
}

sealed class DroidLmAction {
    data class NoOp(val message: String) : DroidLmAction()
    data class NeedLlmPlanning(val reason: String) : DroidLmAction()
    data class AskConfirmation(val reason: String, val confirmationPrompt: String) : DroidLmAction()
    data class OpenApp(val appName: String?, val packageName: String, val reason: String) : DroidLmAction()
    data class OpenSettings(val reason: String = "User asked to open Android Settings") : DroidLmAction()
    data object PressHome : DroidLmAction()
    data object PressBack : DroidLmAction()
    data class Tap(val x: Int, val y: Int, val reason: String) : DroidLmAction()
    data class TapNode(val nodeId: String, val reason: String) : DroidLmAction()
    data class FocusNode(val nodeId: String, val reason: String) : DroidLmAction()
    data class LongPress(val x: Int, val y: Int, val durationMs: Int = 600, val reason: String) : DroidLmAction()
    data class Swipe(val startX: Int, val startY: Int, val endX: Int, val endY: Int, val durationMs: Int, val reason: String) : DroidLmAction()
    data class TypeText(val text: String, val clear: Boolean = false, val reason: String) : DroidLmAction()
    data object TakeScreenshot : DroidLmAction()
    data class FocusEditable(val nodeId: String? = null, val reason: String) : DroidLmAction()
    data class SetSelection(val nodeId: String?, val start: Int, val end: Int, val reason: String) : DroidLmAction()
    data class InsertText(val text: String, val reason: String) : DroidLmAction()
    data class ReplaceSelection(val text: String, val reason: String) : DroidLmAction()
    data class SetFullText(val nodeId: String?, val text: String, val reason: String) : DroidLmAction()
    data class MoveCursor(val targetDescription: String, val reason: String) : DroidLmAction()
    data class TapTextAnchor(val anchorText: String, val anchorPosition: AnchorPosition, val reason: String) : DroidLmAction()
    data object OcrScreen : DroidLmAction()
    data class AnalyzeScreenshot(val goal: String, val reason: String) : DroidLmAction()
    data class VerifyTextChange(val expectedText: String, val reason: String) : DroidLmAction()
    data class InsertTextAtAnchor(
        val anchorText: String,
        val anchorPosition: AnchorPosition,
        val text: String,
        val reason: String
    ) : DroidLmAction()

    data class ReplaceTextRange(
        val targetText: String,
        val replacementText: String,
        val reason: String
    ) : DroidLmAction()

    data class AppendText(val text: String, val reason: String = "User asked to append text") : DroidLmAction()
    data class PrependText(val text: String, val reason: String = "User asked to prepend text") : DroidLmAction()
    data object SelectAll : DroidLmAction()
    data object DeleteSelectedText : DroidLmAction()
    data class FormatCurrentLineAsBullet(
        val fileUri: String? = null,
        val bulletPrefix: String = "- ",
        val reason: String = "User asked to add a bullet point on the current line"
    ) : DroidLmAction()

    data class ReplaceDocumentText(
        val targetText: String = "",
        val replacementText: String = "",
        val fileUri: String? = null,
        val reason: String = "User asked to replace document text"
    ) : DroidLmAction()

    data class AppendDocumentNote(
        val note: String = "",
        val fileUri: String? = null,
        val reason: String = "User asked to append a document note"
    ) : DroidLmAction()

    data class SetCurrentSheetCell(
        val value: String = "",
        val fileUri: String? = null,
        val reason: String = "User asked to set the current spreadsheet cell"
    ) : DroidLmAction()

    data class AddSpreadsheetRow(
        val values: List<String> = emptyList(),
        val fileUri: String? = null,
        val reason: String = "User asked to add a spreadsheet row"
    ) : DroidLmAction()
    data object Done : DroidLmAction()
}

fun DroidLmAction.displayName(): String = when (this) {
    is DroidLmAction.NoOp -> "NO_OP"
    is DroidLmAction.NeedLlmPlanning -> "NEED_LLM_PLANNING"
    is DroidLmAction.AskConfirmation -> "ASK_CONFIRMATION"
    is DroidLmAction.OpenApp -> "OPEN_APP ${appName ?: packageName}"
    is DroidLmAction.OpenSettings -> "OPEN_SETTINGS"
    DroidLmAction.PressHome -> "GLOBAL_HOME"
    DroidLmAction.PressBack -> "GLOBAL_BACK"
    is DroidLmAction.Tap -> "TAP $x,$y"
    is DroidLmAction.TapNode -> "TAP_NODE $nodeId"
    is DroidLmAction.FocusNode -> "FOCUS_NODE $nodeId"
    is DroidLmAction.LongPress -> "LONG_PRESS $x,$y"
    is DroidLmAction.Swipe -> "SWIPE"
    is DroidLmAction.TypeText -> "TYPE_TEXT"
    DroidLmAction.TakeScreenshot -> "TAKE_SCREENSHOT"
    is DroidLmAction.FocusEditable -> "FOCUS_EDITABLE"
    is DroidLmAction.SetSelection -> "SET_SELECTION"
    is DroidLmAction.InsertText -> "INSERT_TEXT"
    is DroidLmAction.ReplaceSelection -> "REPLACE_SELECTION"
    is DroidLmAction.SetFullText -> "SET_FULL_TEXT"
    is DroidLmAction.MoveCursor -> "MOVE_CURSOR"
    is DroidLmAction.TapTextAnchor -> "TAP_TEXT_ANCHOR"
    DroidLmAction.OcrScreen -> "OCR_SCREEN"
    is DroidLmAction.AnalyzeScreenshot -> "ANALYZE_SCREENSHOT"
    is DroidLmAction.VerifyTextChange -> "VERIFY_TEXT_CHANGE"
    is DroidLmAction.InsertTextAtAnchor -> "INSERT_TEXT_AT_ANCHOR ${anchorPosition.name} $anchorText"
    is DroidLmAction.ReplaceTextRange -> "REPLACE_TEXT_RANGE $targetText"
    is DroidLmAction.AppendText -> "APPEND_TEXT"
    is DroidLmAction.PrependText -> "PREPEND_TEXT"
    DroidLmAction.SelectAll -> "SELECT_ALL"
    DroidLmAction.DeleteSelectedText -> "DELETE_SELECTED_TEXT"
    is DroidLmAction.FormatCurrentLineAsBullet -> "FORMAT_CURRENT_LINE_AS_BULLET"
    is DroidLmAction.ReplaceDocumentText -> "REPLACE_CURRENT_DOCUMENT_TEXT"
    is DroidLmAction.AppendDocumentNote -> "APPEND_DOCUMENT_NOTE"
    is DroidLmAction.SetCurrentSheetCell -> "SET_CURRENT_SHEET_CELL"
    is DroidLmAction.AddSpreadsheetRow -> "ADD_SPREADSHEET_ROW"
    DroidLmAction.Done -> "DONE"
}
