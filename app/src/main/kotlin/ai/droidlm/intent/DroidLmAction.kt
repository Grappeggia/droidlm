package ai.droidlm.intent

enum class AnchorPosition {
    BEFORE,
    AFTER
}

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT
}

enum class ImeActionType {
    DEFAULT,
    ENTER,
    SEARCH,
    DONE,
    SEND,
    NEXT,
    GO
}

enum class DialogButtonRole {
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
    DISMISS
}

enum class MenuType {
    OVERFLOW,
    NAVIGATION_DRAWER,
    CONTEXT
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
    data class Scroll(
        val direction: ScrollDirection,
        val targetNodeId: String? = null,
        val amount: String? = null,
        val untilText: String? = null,
        val reason: String
    ) : DroidLmAction()
    data class TapText(
        val text: String,
        val role: String? = null,
        val containerNodeId: String? = null,
        val reason: String
    ) : DroidLmAction()
    data class LongPressNode(
        val nodeId: String? = null,
        val text: String? = null,
        val durationMs: Int = 600,
        val reason: String
    ) : DroidLmAction()
    data class WaitForUi(
        val text: String? = null,
        val packageName: String? = null,
        val nodeId: String? = null,
        val timeoutMs: Int = 2_500,
        val reason: String
    ) : DroidLmAction()
    data class PressImeAction(
        val action: ImeActionType = ImeActionType.DEFAULT,
        val reason: String
    ) : DroidLmAction()
    data class DialogAction(
        val buttonText: String? = null,
        val role: DialogButtonRole? = null,
        val reason: String
    ) : DroidLmAction()
    data class OpenMenu(
        val menu: MenuType = MenuType.OVERFLOW,
        val reason: String
    ) : DroidLmAction()
    data class SelectTab(
        val label: String,
        val reason: String
    ) : DroidLmAction()
    data class SetToggle(
        val label: String? = null,
        val nodeId: String? = null,
        val value: Boolean,
        val reason: String
    ) : DroidLmAction()
    data class ExpandCollapse(
        val label: String? = null,
        val nodeId: String? = null,
        val expanded: Boolean,
        val reason: String
    ) : DroidLmAction()
    data class SetSlider(
        val label: String? = null,
        val nodeId: String? = null,
        val value: Float? = null,
        val percent: Int? = null,
        val reason: String
    ) : DroidLmAction()
    data class Refresh(
        val targetNodeId: String? = null,
        val reason: String = "Refresh the current screen"
    ) : DroidLmAction()
    data class FindTextOnScreen(
        val text: String,
        val tapOnMatch: Boolean = false,
        val reason: String
    ) : DroidLmAction()
    data object OpenNotifications : DroidLmAction()
    data object OpenQuickSettings : DroidLmAction()
    data object OpenRecents : DroidLmAction()
    data class SwitchApp(
        val appName: String? = null,
        val packageName: String? = null,
        val reason: String
    ) : DroidLmAction()
    data class OpenUrl(
        val url: String,
        val reason: String
    ) : DroidLmAction()
    data class OpenDeepLink(
        val uri: String,
        val reason: String
    ) : DroidLmAction()
    data class PickFromChooser(
        val itemText: String,
        val reason: String
    ) : DroidLmAction()
    data class PickFile(
        val fileName: String,
        val reason: String
    ) : DroidLmAction()
    data class PickPhoto(
        val photoLabel: String,
        val reason: String
    ) : DroidLmAction()
    data class ShareToApp(
        val appName: String? = null,
        val packageName: String? = null,
        val reason: String
    ) : DroidLmAction()
    data class PermissionDecision(
        val allow: Boolean,
        val reason: String
    ) : DroidLmAction()
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
    is DroidLmAction.Scroll -> "SCROLL ${direction.name}"
    is DroidLmAction.TapText -> "TAP_TEXT $text"
    is DroidLmAction.LongPressNode -> "LONG_PRESS_NODE ${nodeId ?: text ?: "UNKNOWN"}"
    is DroidLmAction.WaitForUi -> "WAIT_FOR_UI"
    is DroidLmAction.PressImeAction -> "PRESS_IME_ACTION ${action.name}"
    is DroidLmAction.DialogAction -> "DIALOG_ACTION ${buttonText ?: role?.name ?: "UNKNOWN"}"
    is DroidLmAction.OpenMenu -> "OPEN_MENU ${menu.name}"
    is DroidLmAction.SelectTab -> "SELECT_TAB $label"
    is DroidLmAction.SetToggle -> "SET_TOGGLE ${nodeId ?: label ?: "UNKNOWN"}=$value"
    is DroidLmAction.ExpandCollapse -> "EXPAND_COLLAPSE ${nodeId ?: label ?: "UNKNOWN"}=$expanded"
    is DroidLmAction.SetSlider -> "SET_SLIDER ${nodeId ?: label ?: "UNKNOWN"}"
    is DroidLmAction.Refresh -> "REFRESH"
    is DroidLmAction.FindTextOnScreen -> "FIND_TEXT_ON_SCREEN $text"
    DroidLmAction.OpenNotifications -> "OPEN_NOTIFICATIONS"
    DroidLmAction.OpenQuickSettings -> "OPEN_QUICK_SETTINGS"
    DroidLmAction.OpenRecents -> "OPEN_RECENTS"
    is DroidLmAction.SwitchApp -> "SWITCH_APP ${appName ?: packageName ?: "UNKNOWN"}"
    is DroidLmAction.OpenUrl -> "OPEN_URL $url"
    is DroidLmAction.OpenDeepLink -> "OPEN_DEEP_LINK $uri"
    is DroidLmAction.PickFromChooser -> "PICK_FROM_CHOOSER $itemText"
    is DroidLmAction.PickFile -> "PICK_FILE $fileName"
    is DroidLmAction.PickPhoto -> "PICK_PHOTO $photoLabel"
    is DroidLmAction.ShareToApp -> "SHARE_TO_APP ${appName ?: packageName ?: "UNKNOWN"}"
    is DroidLmAction.PermissionDecision -> if (allow) "PERMISSION_ALLOW" else "PERMISSION_DENY"
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
