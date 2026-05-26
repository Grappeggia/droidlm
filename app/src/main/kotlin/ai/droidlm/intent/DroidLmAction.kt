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

enum class DocumentEditOperation {
    REPLACE_TEXT_RANGE,
    INSERT_TEXT_AT_ANCHOR
}

data class DocumentEdit(
    val operation: DocumentEditOperation,
    val targetText: String? = null,
    val replacementText: String? = null,
    val anchorText: String? = null,
    val anchorPosition: AnchorPosition = AnchorPosition.AFTER,
    val text: String? = null,
    val sectionLabel: String? = null,
    val occurrenceIndex: Int? = null
)

sealed class DroidLmAction {
    data class NoOp(val message: String) : DroidLmAction()
    data class NeedLlmPlanning(val reason: String) : DroidLmAction()
    data class AskConfirmation(val reason: String, val confirmationPrompt: String) : DroidLmAction()
    data class OpenApp(val appName: String?, val packageName: String, val reason: String) : DroidLmAction()
    data class OpenAppStoreListing(val appName: String?, val packageName: String, val reason: String) : DroidLmAction()
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
    data class NavigateToArtifactTarget(
        val label: String,
        val nodeId: String? = null,
        val kind: String? = null,
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
    data class SearchAccessibilityContent(
        val query: String? = null,
        val sectionLabel: String? = null,
        val exclude: String? = null,
        val ordinal: Int? = null,
        val maxMatches: Int = 5,
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
        val sectionLabel: String? = null,
        val occurrenceIndex: Int? = null,
        val reason: String
    ) : DroidLmAction()

    data class ReplaceTextRange(
        val targetText: String,
        val replacementText: String,
        val sectionLabel: String? = null,
        val occurrenceIndex: Int? = null,
        val reason: String
    ) : DroidLmAction()
    data class ApplyDocumentEdits(
        val sectionLabel: String? = null,
        val edits: List<DocumentEdit>,
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
    sealed class ArtifactToolAction : DroidLmAction() {
        data class GetStructure(val artifactType: String? = null, val reason: String = "Read artifact structure") : ArtifactToolAction()
        data class ResolveTarget(val query: String, val artifactType: String? = null, val targetKind: String? = null, val reason: String = "Resolve artifact target") : ArtifactToolAction()
        data class GetContentWindow(val targetId: String? = null, val label: String? = null, val beforeChars: Int = 1_200, val afterChars: Int = 1_200, val reason: String = "Read artifact content window") : ArtifactToolAction()
        data class GetSelectionState(val reason: String = "Read artifact selection state") : ArtifactToolAction()
        data class VerifyEndState(val targetId: String? = null, val label: String? = null, val requiredEndState: String = "visible", val expectedText: String? = null, val reason: String = "Verify artifact goal end state") : ArtifactToolAction()
        data class NavigateToTarget(val targetId: String? = null, val label: String? = null, val kind: String? = null, val reason: String = "Navigate to artifact target") : ArtifactToolAction()
        data class SetCursorAtTarget(val targetId: String? = null, val label: String? = null, val position: String = "start", val reason: String = "Set cursor at artifact target") : ArtifactToolAction()
        data class SelectTarget(val targetId: String? = null, val label: String? = null, val selectionKind: String? = null, val reason: String = "Select artifact target") : ArtifactToolAction()
        data class ScrollToMatch(val query: String, val direction: ScrollDirection = ScrollDirection.DOWN, val targetKind: String? = null, val reason: String = "Scroll to matching artifact target") : ArtifactToolAction()
        data class UndoLastAction(val reason: String = "Undo last artifact action") : ArtifactToolAction()
        data class DocInsertAtTarget(val targetLabel: String, val text: String, val position: AnchorPosition = AnchorPosition.AFTER, val sectionLabel: String? = null, val occurrenceIndex: Int? = null, val reason: String = "Insert document text at target") : ArtifactToolAction()
        data class DocReplaceTargetText(val targetText: String, val replacementText: String, val sectionLabel: String? = null, val occurrenceIndex: Int? = null, val reason: String = "Replace document target text") : ArtifactToolAction()
        data class DocDeleteTargetText(val targetText: String, val sectionLabel: String? = null, val occurrenceIndex: Int? = null, val reason: String = "Delete document target text") : ArtifactToolAction()
        data class DocApplyFormat(val targetLabel: String? = null, val format: String, val value: String? = null, val reason: String = "Apply document format") : ArtifactToolAction()
        data class DocMoveBlock(val blockLabel: String, val destinationLabel: String? = null, val position: String = "after", val reason: String = "Move document block") : ArtifactToolAction()
        data class DocCreateSection(val title: String, val afterLabel: String? = null, val bodyText: String? = null, val reason: String = "Create document section") : ArtifactToolAction()
        data class DocGetTargetMetadata(val targetLabel: String, val reason: String = "Read document target metadata") : ArtifactToolAction()
        data class DocExtractActionItems(val targetLabel: String? = null, val reason: String = "Extract document action items") : ArtifactToolAction()
        data class SheetResolveRange(val query: String, val sheetName: String? = null, val reason: String = "Resolve spreadsheet range") : ArtifactToolAction()
        data class SheetSetRangeValues(val range: String? = null, val values: List<List<String>> = emptyList(), val reason: String = "Set spreadsheet range values") : ArtifactToolAction()
        data class SheetAppendTableRow(val tableLabel: String? = null, val values: List<String> = emptyList(), val reason: String = "Append spreadsheet table row") : ArtifactToolAction()
        data class SheetUpdateRowByMatch(val matchColumn: String? = null, val matchValue: String, val values: Map<String, String> = emptyMap(), val reason: String = "Update spreadsheet row by match") : ArtifactToolAction()
        data class SheetApplyFormula(val range: String? = null, val formula: String, val fillDirection: String? = null, val reason: String = "Apply spreadsheet formula") : ArtifactToolAction()
        data class SheetSortFilterRange(val range: String? = null, val sortBy: String? = null, val ascending: Boolean = true, val filterColumn: String? = null, val filterValue: String? = null, val reason: String = "Sort or filter spreadsheet range") : ArtifactToolAction()
        data class SheetInsertDeleteRowsColumns(val operation: String, val axis: String, val index: Int? = null, val count: Int = 1, val reason: String = "Insert or delete spreadsheet rows or columns") : ArtifactToolAction()
        data class SheetValidateTableState(val range: String? = null, val expectedText: String? = null, val expectedRowCount: Int? = null, val reason: String = "Validate spreadsheet table state") : ArtifactToolAction()
        data class NotionResolveBlockOrPage(val query: String, val kind: String? = null, val reason: String = "Resolve Notion block or page") : ArtifactToolAction()
        data class NotionCreatePageOrBlock(val parentLabel: String? = null, val blockType: String = "paragraph", val title: String? = null, val text: String? = null, val reason: String = "Create Notion page or block") : ArtifactToolAction()
        data class NotionUpdateDatabaseItem(val databaseLabel: String? = null, val matchProperty: String? = null, val matchValue: String, val properties: Map<String, String> = emptyMap(), val reason: String = "Update Notion database item") : ArtifactToolAction()
        data class NotionMoveOrReorderBlock(val blockLabel: String, val destinationLabel: String? = null, val position: String = "after", val reason: String = "Move or reorder Notion block") : ArtifactToolAction()
    }

    data object Done : DroidLmAction()
}

fun DroidLmAction.displayName(): String = when (this) {
    is DroidLmAction.NoOp -> "NO_OP"
    is DroidLmAction.NeedLlmPlanning -> "NEED_LLM_PLANNING"
    is DroidLmAction.AskConfirmation -> "ASK_CONFIRMATION"
    is DroidLmAction.OpenApp -> "OPEN_APP ${appName ?: packageName}"
    is DroidLmAction.OpenAppStoreListing -> "OPEN_APP_STORE_LISTING ${appName ?: packageName}"
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
    is DroidLmAction.NavigateToArtifactTarget -> "NAVIGATE_TO_ARTIFACT_TARGET $label"
    is DroidLmAction.SetToggle -> "SET_TOGGLE ${nodeId ?: label ?: "UNKNOWN"}=$value"
    is DroidLmAction.ExpandCollapse -> "EXPAND_COLLAPSE ${nodeId ?: label ?: "UNKNOWN"}=$expanded"
    is DroidLmAction.SetSlider -> "SET_SLIDER ${nodeId ?: label ?: "UNKNOWN"}"
    is DroidLmAction.Refresh -> "REFRESH"
    is DroidLmAction.FindTextOnScreen -> "FIND_TEXT_ON_SCREEN $text"
    is DroidLmAction.SearchAccessibilityContent -> "SEARCH_ACCESSIBILITY_CONTENT ${query ?: sectionLabel ?: exclude ?: ""}"
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
    is DroidLmAction.ApplyDocumentEdits -> "APPLY_DOCUMENT_EDITS ${edits.size}"
    is DroidLmAction.AppendText -> "APPEND_TEXT"
    is DroidLmAction.PrependText -> "PREPEND_TEXT"
    DroidLmAction.SelectAll -> "SELECT_ALL"
    DroidLmAction.DeleteSelectedText -> "DELETE_SELECTED_TEXT"
    is DroidLmAction.FormatCurrentLineAsBullet -> "FORMAT_CURRENT_LINE_AS_BULLET"
    is DroidLmAction.ReplaceDocumentText -> "REPLACE_CURRENT_DOCUMENT_TEXT"
    is DroidLmAction.AppendDocumentNote -> "APPEND_DOCUMENT_NOTE"
    is DroidLmAction.SetCurrentSheetCell -> "SET_CURRENT_SHEET_CELL"
    is DroidLmAction.AddSpreadsheetRow -> "ADD_SPREADSHEET_ROW"
    is DroidLmAction.ArtifactToolAction -> artifactToolDisplayName()
    DroidLmAction.Done -> "DONE"
}

fun DroidLmAction.ArtifactToolAction.artifactToolName(): String = when (this) {
    is DroidLmAction.ArtifactToolAction.GetStructure -> "ARTIFACT_GET_STRUCTURE"
    is DroidLmAction.ArtifactToolAction.ResolveTarget -> "ARTIFACT_RESOLVE_TARGET"
    is DroidLmAction.ArtifactToolAction.GetContentWindow -> "ARTIFACT_GET_CONTENT_WINDOW"
    is DroidLmAction.ArtifactToolAction.GetSelectionState -> "ARTIFACT_GET_SELECTION_STATE"
    is DroidLmAction.ArtifactToolAction.VerifyEndState -> "ARTIFACT_VERIFY_END_STATE"
    is DroidLmAction.ArtifactToolAction.NavigateToTarget -> "ARTIFACT_NAVIGATE_TO_TARGET"
    is DroidLmAction.ArtifactToolAction.SetCursorAtTarget -> "ARTIFACT_SET_CURSOR_AT_TARGET"
    is DroidLmAction.ArtifactToolAction.SelectTarget -> "ARTIFACT_SELECT_TARGET"
    is DroidLmAction.ArtifactToolAction.ScrollToMatch -> "ARTIFACT_SCROLL_TO_MATCH"
    is DroidLmAction.ArtifactToolAction.UndoLastAction -> "ARTIFACT_UNDO_LAST_ACTION"
    is DroidLmAction.ArtifactToolAction.DocInsertAtTarget -> "DOC_INSERT_AT_TARGET"
    is DroidLmAction.ArtifactToolAction.DocReplaceTargetText -> "DOC_REPLACE_TARGET_TEXT"
    is DroidLmAction.ArtifactToolAction.DocDeleteTargetText -> "DOC_DELETE_TARGET_TEXT"
    is DroidLmAction.ArtifactToolAction.DocApplyFormat -> "DOC_APPLY_FORMAT"
    is DroidLmAction.ArtifactToolAction.DocMoveBlock -> "DOC_MOVE_BLOCK"
    is DroidLmAction.ArtifactToolAction.DocCreateSection -> "DOC_CREATE_SECTION"
    is DroidLmAction.ArtifactToolAction.DocGetTargetMetadata -> "DOC_GET_TARGET_METADATA"
    is DroidLmAction.ArtifactToolAction.DocExtractActionItems -> "DOC_EXTRACT_ACTION_ITEMS"
    is DroidLmAction.ArtifactToolAction.SheetResolveRange -> "SHEET_RESOLVE_RANGE"
    is DroidLmAction.ArtifactToolAction.SheetSetRangeValues -> "SHEET_SET_RANGE_VALUES"
    is DroidLmAction.ArtifactToolAction.SheetAppendTableRow -> "SHEET_APPEND_TABLE_ROW"
    is DroidLmAction.ArtifactToolAction.SheetUpdateRowByMatch -> "SHEET_UPDATE_ROW_BY_MATCH"
    is DroidLmAction.ArtifactToolAction.SheetApplyFormula -> "SHEET_APPLY_FORMULA"
    is DroidLmAction.ArtifactToolAction.SheetSortFilterRange -> "SHEET_SORT_FILTER_RANGE"
    is DroidLmAction.ArtifactToolAction.SheetInsertDeleteRowsColumns -> "SHEET_INSERT_DELETE_ROWS_COLUMNS"
    is DroidLmAction.ArtifactToolAction.SheetValidateTableState -> "SHEET_VALIDATE_TABLE_STATE"
    is DroidLmAction.ArtifactToolAction.NotionResolveBlockOrPage -> "NOTION_RESOLVE_BLOCK_OR_PAGE"
    is DroidLmAction.ArtifactToolAction.NotionCreatePageOrBlock -> "NOTION_CREATE_PAGE_OR_BLOCK"
    is DroidLmAction.ArtifactToolAction.NotionUpdateDatabaseItem -> "NOTION_UPDATE_DATABASE_ITEM"
    is DroidLmAction.ArtifactToolAction.NotionMoveOrReorderBlock -> "NOTION_MOVE_OR_REORDER_BLOCK"
}

fun DroidLmAction.ArtifactToolAction.artifactToolDisplayName(): String = when (this) {
    is DroidLmAction.ArtifactToolAction.ResolveTarget -> "${artifactToolName()} $query"
    is DroidLmAction.ArtifactToolAction.NavigateToTarget -> "${artifactToolName()} ${label ?: targetId ?: "UNKNOWN"}"
    is DroidLmAction.ArtifactToolAction.SetCursorAtTarget -> "${artifactToolName()} ${label ?: targetId ?: "UNKNOWN"}"
    is DroidLmAction.ArtifactToolAction.SelectTarget -> "${artifactToolName()} ${label ?: targetId ?: "UNKNOWN"}"
    is DroidLmAction.ArtifactToolAction.ScrollToMatch -> "${artifactToolName()} $query"
    is DroidLmAction.ArtifactToolAction.DocInsertAtTarget -> "${artifactToolName()} $targetLabel"
    is DroidLmAction.ArtifactToolAction.DocReplaceTargetText -> "${artifactToolName()} $targetText"
    is DroidLmAction.ArtifactToolAction.DocDeleteTargetText -> "${artifactToolName()} $targetText"
    is DroidLmAction.ArtifactToolAction.DocApplyFormat -> "${artifactToolName()} $format"
    is DroidLmAction.ArtifactToolAction.DocMoveBlock -> "${artifactToolName()} $blockLabel"
    is DroidLmAction.ArtifactToolAction.DocCreateSection -> "${artifactToolName()} $title"
    is DroidLmAction.ArtifactToolAction.DocGetTargetMetadata -> "${artifactToolName()} $targetLabel"
    is DroidLmAction.ArtifactToolAction.SheetResolveRange -> "${artifactToolName()} $query"
    is DroidLmAction.ArtifactToolAction.SheetSetRangeValues -> "${artifactToolName()} ${range ?: "current"}"
    is DroidLmAction.ArtifactToolAction.SheetAppendTableRow -> "${artifactToolName()} ${tableLabel ?: "current"}"
    is DroidLmAction.ArtifactToolAction.SheetUpdateRowByMatch -> "${artifactToolName()} $matchValue"
    is DroidLmAction.ArtifactToolAction.SheetApplyFormula -> "${artifactToolName()} ${range ?: "current"}"
    is DroidLmAction.ArtifactToolAction.SheetSortFilterRange -> "${artifactToolName()} ${range ?: "current"}"
    is DroidLmAction.ArtifactToolAction.SheetInsertDeleteRowsColumns -> "${artifactToolName()} $operation $axis"
    is DroidLmAction.ArtifactToolAction.SheetValidateTableState -> "${artifactToolName()} ${range ?: expectedText ?: "table"}"
    is DroidLmAction.ArtifactToolAction.NotionResolveBlockOrPage -> "${artifactToolName()} $query"
    is DroidLmAction.ArtifactToolAction.NotionCreatePageOrBlock -> "${artifactToolName()} ${title ?: text ?: blockType}"
    is DroidLmAction.ArtifactToolAction.NotionUpdateDatabaseItem -> "${artifactToolName()} $matchValue"
    is DroidLmAction.ArtifactToolAction.NotionMoveOrReorderBlock -> "${artifactToolName()} $blockLabel"
    else -> artifactToolName()
}

