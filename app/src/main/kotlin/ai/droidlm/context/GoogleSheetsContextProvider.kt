package ai.droidlm.context

import ai.droidlm.context.GoogleWorkspaceContextUtils.MAX_CURSOR_TEXT
import ai.droidlm.context.GoogleWorkspaceContextUtils.MAX_EDITABLE_TEXT
import ai.droidlm.context.GoogleWorkspaceContextUtils.MAX_VISIBLE_TEXT
import ai.droidlm.context.GoogleWorkspaceContextUtils.SHEETS_PACKAGE
import ai.droidlm.context.GoogleWorkspaceContextUtils.cap
import ai.droidlm.context.GoogleWorkspaceContextUtils.currentLine
import ai.droidlm.context.GoogleWorkspaceContextUtils.focusedEditable
import ai.droidlm.context.GoogleWorkspaceContextUtils.hasAnyText
import ai.droidlm.context.GoogleWorkspaceContextUtils.inferTitle
import ai.droidlm.context.GoogleWorkspaceContextUtils.safetyContext
import ai.droidlm.context.GoogleWorkspaceContextUtils.selectionContext
import ai.droidlm.context.GoogleWorkspaceContextUtils.textAfterCursor
import ai.droidlm.context.GoogleWorkspaceContextUtils.textBeforeCursor
import ai.droidlm.context.GoogleWorkspaceContextUtils.visibleText
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import org.json.JSONArray
import org.json.JSONObject

class GoogleSheetsContextProvider : DeviceContextProvider {
    override suspend fun collect(request: DeviceContextRequest): JSONObject {
        val state = request.state ?: return JSONObject()
        if (state.packageName != SHEETS_PACKAGE) return JSONObject()

        val focusedEditable = focusedEditable(state.nodes)
        val editableText = focusedEditable?.text.orEmpty()
        val uiMode = detectUiMode(state, focusedEditable)
        val visibleText = visibleText(state.nodes)
        val title = inferSpreadsheetTitle(state.nodes)
        val activeCell = activeCellContext(state.nodes, focusedEditable, editableText)
        val actions = availableActions(uiMode, focusedEditable)
        val artifactContext = ArtifactContextBuilder.build(
            source = "google_sheets",
            type = "spreadsheet",
            title = title,
            uiMode = uiMode,
            state = state,
            visibleText = visibleText,
            focusedText = editableText,
            currentBlock = activeCell.optString("value").ifBlank { currentLine(editableText, focusedEditable?.textSelectionStart) },
            availableActions = actions
        )
        val safety = safetyContext(
            text = listOfNotNull(title, visibleText, editableText).joinToString("\n"),
            sharingFlowActive = uiMode == "SHARE_DIALOG",
            deleteFlowActive = hasAnyText(state.nodes, "delete", "move to trash", "remove")
        )

        return JSONObject()
            .put(
                "activeSpreadsheet",
                JSONObject()
                    .put("source", "google_sheets")
                    .put("title", title)
                    .put("spreadsheetId", JSONObject.NULL)
                    .put("uri", JSONObject.NULL)
                    .put("confidence", if (title != null) 0.62 else 0.0)
            )
            .put(
                "sheetsContext",
                JSONObject()
                    .put("uiMode", uiMode)
                    .put("isGoogleSheets", true)
                    .put("availableActions", JSONArray(actions))
            )
            .put("artifactContext", artifactContext)
            .put("activeCell", activeCell)
            .put("selectionContext", selectionContext(focusedEditable, editableText))
            .put(
                "sheetTextWindow",
                JSONObject()
                    .put("visibleText", cap(visibleText, MAX_VISIBLE_TEXT))
                    .put("focusedEditableText", cap(editableText, MAX_EDITABLE_TEXT))
                    .put("textBeforeCursor", cap(textBeforeCursor(editableText, focusedEditable?.textSelectionStart), MAX_CURSOR_TEXT))
                    .put("textAfterCursor", cap(textAfterCursor(editableText, focusedEditable?.textSelectionEnd), MAX_CURSOR_TEXT))
                    .put("currentCellText", cap(currentLine(editableText, focusedEditable?.textSelectionStart), MAX_CURSOR_TEXT))
            )
            .put("visibleGrid", visibleGridContext(state.nodes))
            .put("availableSheetActions", JSONArray(actions))
            .put("safety", safety)
    }

    private fun detectUiMode(state: PortalState, focusedEditable: UiNode?): String = when {
        ArtifactContextBuilder.isShareDialog(state.nodes) -> "SHARE_DIALOG"
        hasAnyText(state.nodes, "find", "replace", "search in spreadsheet") -> "FIND_BAR"
        hasAnyText(state.nodes, "bold", "italic", "text color", "fill color", "number format") -> "FORMAT_TOOLBAR"
        focusedEditable != null && hasAnyText(state.nodes, "formula", "fx") -> "FORMULA_BAR"
        focusedEditable != null -> "CELL_EDIT"
        hasAnyText(state.nodes, "sheet1", "sheet 1", "rows", "columns", "cell") -> "SHEET_GRID"
        hasAnyText(state.nodes, "recent spreadsheets", "owned by me", "shared with me", "templates") -> "SHEET_LIST"
        else -> "UNKNOWN"
    }

    private fun activeCellContext(nodes: List<UiNode>, focusedEditable: UiNode?, editableText: String): JSONObject {
        val selectedNode = focusedEditable ?: nodes.firstOrNull { it.selected || it.focused }
        val label = listOfNotNull(selectedNode?.contentDescription, selectedNode?.text, selectedNode?.viewIdResourceName)
            .firstOrNull { it.isNotBlank() }
        return JSONObject()
            .put("focusedNodeId", selectedNode?.nodeId)
            .put("value", cap(editableText.ifBlank { selectedNode?.text.orEmpty() }, MAX_CURSOR_TEXT))
            .put("label", label)
            .put("row", inferRow(label))
            .put("column", inferColumn(label))
            .put("formulaBarText", cap(editableText, MAX_CURSOR_TEXT))
            .put("cursorKnown", focusedEditable?.textSelectionStart != null)
    }

    private fun visibleGridContext(nodes: List<UiNode>): JSONObject {
        val cellTexts = nodes.asSequence()
            .mapNotNull { node ->
                val text = node.text?.trim().orEmpty()
                val label = node.contentDescription?.trim().orEmpty()
                (text.ifBlank { label }).takeIf { it.isNotBlank() && it.length <= 120 }
            }
            .distinct()
            .take(80)
            .toList()
        val tabs = cellTexts.filter { it.startsWith("Sheet", ignoreCase = true) || it.contains("tab", ignoreCase = true) }
        return JSONObject()
            .put("visibleCellText", JSONArray(cellTexts))
            .put("sheetTabs", JSONArray(tabs))
    }

    private fun availableActions(uiMode: String, focusedEditable: UiNode?): List<String> {
        val actions = linkedSetOf("OPEN_FIND", "SET_CURRENT_CELL")
        when (uiMode) {
            "CELL_EDIT", "FORMULA_BAR", "FORMAT_TOOLBAR" -> {
                actions += "TYPE_TEXT"
                actions += "INSERT_FORMULA"
                actions += "ADD_ROW"
                actions += "FORMAT_CELL"
                actions += "UNDO"
            }
            "SHEET_GRID" -> {
                actions += "ENTER_CELL_EDIT"
                actions += "ADD_ROW"
                actions += "SWITCH_SHEET_TAB"
            }
            "SHEET_LIST" -> actions += "OPEN_SPREADSHEET"
            "SHARE_DIALOG" -> actions += "SHARE"
        }
        if (focusedEditable != null) actions += "TYPE_TEXT"
        return actions.toList()
    }

    private fun inferSpreadsheetTitle(nodes: List<UiNode>): String? = inferTitle(
        nodes,
        setOf("sheets", "google sheets", "share", "edit", "undo", "redo", "format", "insert", "formula")
    )

    private fun inferRow(label: String?): Int? = label?.let { Regex("(?:row|r)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private fun inferColumn(label: String?): String? = label?.let { Regex("(?:column|col|c)\\s*([A-Z]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1) }
}
