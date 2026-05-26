package ai.droidlm.agent

import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.DroidLmActionContract
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import ai.droidlm.relay.RelayClient
import org.json.JSONObject

class AgentToolRegistry(
    private val relayClient: RelayClient = RelayClient()
) {
    private val specs: Map<String, AgentToolSpec> = defaultSpecs().associateBy { it.name }

    fun toolSpecs(): List<AgentToolSpec> = specs.values.sortedBy { it.name }

    fun toExecution(
        call: AgentToolCall,
        state: PortalState?,
        packages: List<AppPackage>,
        callsSoFarByTool: Map<String, Int> = emptyMap()
    ): Result<AgentToolExecution> {
        val spec = specs[call.name]
            ?: return Result.failure(IllegalArgumentException("Unknown agent tool: ${call.name}"))
        val currentCallsForTool = callsSoFarByTool[call.name] ?: 0
        if (currentCallsForTool >= spec.maxCallsPerRun) {
            return Result.failure(IllegalStateException("Tool ${call.name} exceeded run limit ${spec.maxCallsPerRun}"))
        }
        val action = runCatching { call.toAction() }
            .getOrElse { return Result.failure(it) }
        validateAction(action, state, packages)?.let { return Result.failure(IllegalArgumentException(it)) }
        return Result.success(AgentToolExecution(call, spec, action))
    }

    fun isFreshObservationRequired(action: DroidLmAction, spec: AgentToolSpec): Boolean =
        spec.requiresFreshObservationAfter || action is DroidLmAction.OpenApp || action is DroidLmAction.OpenAppStoreListing ||
            action is DroidLmAction.Tap || action is DroidLmAction.TapNode || action is DroidLmAction.TapText ||
            action is DroidLmAction.Scroll || action is DroidLmAction.Swipe || action is DroidLmAction.PressBack ||
            action is DroidLmAction.OpenSettings || action is DroidLmAction.SwitchApp || action is DroidLmAction.TypeText ||
            action is DroidLmAction.InsertText || action is DroidLmAction.ReplaceSelection || action is DroidLmAction.SetFullText ||
            action is DroidLmAction.AppendText || action is DroidLmAction.PrependText

    private fun AgentToolCall.toAction(): DroidLmAction {
        val obj = JSONObject(args.toString())
        obj.put("action", name)
        if (!obj.has("reason") && reason.isNotBlank()) obj.put("reason", reason)
        return relayClient.parsePlanActionJson(obj.toString())
    }

    private fun validateAction(action: DroidLmAction, state: PortalState?, packages: List<AppPackage>): String? = when (action) {
        is DroidLmAction.OpenApp -> validateLaunchablePackage(action.packageName, packages)
        is DroidLmAction.OpenAppStoreListing -> validatePackageName(action.packageName)
        is DroidLmAction.SwitchApp -> action.packageName?.let { validateLaunchablePackage(it, packages) }
        is DroidLmAction.ShareToApp -> action.packageName?.let { validateLaunchablePackage(it, packages) }
        is DroidLmAction.TapNode -> validateNode(action.nodeId, state, "TAP_NODE")
        is DroidLmAction.FocusNode -> validateNode(action.nodeId, state, "FOCUS_NODE")
        is DroidLmAction.LongPressNode -> action.nodeId?.let { validateNode(it, state, "LONG_PRESS_NODE") }
        is DroidLmAction.SetToggle -> action.nodeId?.let { validateNode(it, state, "SET_TOGGLE") }
        is DroidLmAction.ExpandCollapse -> action.nodeId?.let { validateNode(it, state, "EXPAND_COLLAPSE") }
        is DroidLmAction.SetSlider -> action.nodeId?.let { validateNode(it, state, "SET_SLIDER") }
        is DroidLmAction.Refresh -> action.targetNodeId?.let { validateNode(it, state, "REFRESH") }
        is DroidLmAction.WaitForUi -> null
        is DroidLmAction.Tap -> validateCoordinates(action.x, action.y, state, "TAP")
        is DroidLmAction.LongPress -> validateCoordinates(action.x, action.y, state, "LONG_PRESS")
        is DroidLmAction.Swipe -> validateCoordinates(action.startX, action.startY, state, "SWIPE")
            ?: validateCoordinates(action.endX, action.endY, state, "SWIPE")
        else -> null
    }

    private fun validateLaunchablePackage(packageName: String, packages: List<AppPackage>): String? {
        validatePackageName(packageName)?.let { return it }
        if (packages.isEmpty()) return null
        val pkg = packages.firstOrNull { it.packageName == packageName }
            ?: return "Package is not installed: $packageName"
        if (pkg.enabled == false) return "Package is disabled: $packageName"
        if (pkg.launchable == false) return "Package is not launchable: $packageName"
        return null
    }

    private fun validatePackageName(packageName: String): String? =
        if (packageName.isBlank()) "Package name is required" else null

    private fun validateNode(nodeId: String, state: PortalState?, toolName: String): String? {
        if (nodeId.isBlank()) return "$toolName requires nodeId"
        if (state == null) return null
        return if (state.nodes.any { it.nodeId == nodeId }) null else "$toolName target node is not present: $nodeId"
    }

    private fun validateCoordinates(x: Int, y: Int, state: PortalState?, toolName: String): String? {
        if (x < 0 || y < 0) return "$toolName coordinates must be non-negative"
        val width = state?.screenWidth
        val height = state?.screenHeight
        if (width != null && x > width) return "$toolName x coordinate is outside the screen"
        if (height != null && y > height) return "$toolName y coordinate is outside the screen"
        return null
    }

    companion object {
        fun defaultSpecs(): List<AgentToolSpec> = listOf(
            AgentToolSpec("DONE", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("NO_OP", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("ASK_CONFIRMATION", ToolRisk.SENSITIVE, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("OPEN_APP", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("OPEN_APP_STORE_LISTING", ToolRisk.INSTALL_OR_STORE, mutating = true, requiresFreshObservationAfter = true, maxCallsPerRun = 1),
            AgentToolSpec("OPEN_SETTINGS", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("GLOBAL_HOME", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("GLOBAL_BACK", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("WAIT_FOR_UI", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("FIND_TEXT_ON_SCREEN", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("SEARCH_ACCESSIBILITY_CONTENT", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("ARTIFACT_GET_STRUCTURE", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("ARTIFACT_RESOLVE_TARGET", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("ARTIFACT_GET_CONTENT_WINDOW", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("ARTIFACT_GET_SELECTION_STATE", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("ARTIFACT_VERIFY_END_STATE", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("ARTIFACT_NAVIGATE_TO_TARGET", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("ARTIFACT_SET_CURSOR_AT_TARGET", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("ARTIFACT_SELECT_TARGET", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("ARTIFACT_SCROLL_TO_MATCH", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("ARTIFACT_UNDO_LAST_ACTION", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true, maxCallsPerRun = 2),
            AgentToolSpec("DOC_INSERT_AT_TARGET", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("DOC_REPLACE_TARGET_TEXT", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("DOC_DELETE_TARGET_TEXT", ToolRisk.SENSITIVE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("DOC_APPLY_FORMAT", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("DOC_MOVE_BLOCK", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("DOC_CREATE_SECTION", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("DOC_GET_TARGET_METADATA", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("DOC_EXTRACT_ACTION_ITEMS", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("SHEET_RESOLVE_RANGE", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("SHEET_SET_RANGE_VALUES", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SHEET_APPEND_TABLE_ROW", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SHEET_UPDATE_ROW_BY_MATCH", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SHEET_APPLY_FORMULA", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SHEET_SORT_FILTER_RANGE", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SHEET_INSERT_DELETE_ROWS_COLUMNS", ToolRisk.SENSITIVE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SHEET_VALIDATE_TABLE_STATE", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("NOTION_RESOLVE_BLOCK_OR_PAGE", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("NOTION_CREATE_PAGE_OR_BLOCK", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("NOTION_UPDATE_DATABASE_ITEM", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("NOTION_MOVE_OR_REORDER_BLOCK", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("OCR_SCREEN", ToolRisk.SENSITIVE, mutating = false, requiresFreshObservationAfter = false, maxCallsPerRun = 2),
            AgentToolSpec("TAKE_SCREENSHOT", ToolRisk.SENSITIVE, mutating = false, requiresFreshObservationAfter = false, maxCallsPerRun = 2),
            AgentToolSpec("TAP_NODE", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("FOCUS_NODE", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("TAP_TEXT", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("TAP", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("LONG_PRESS", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("LONG_PRESS_NODE", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SWIPE", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SCROLL", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("OPEN_MENU", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SELECT_TAB", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SET_TOGGLE", ToolRisk.SENSITIVE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("EXPAND_COLLAPSE", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SET_SLIDER", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("REFRESH", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("DIALOG_ACTION", ToolRisk.SENSITIVE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("PRESS_IME_ACTION", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("TYPE_TEXT", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("NAVIGATE_TO_ARTIFACT_TARGET", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("FOCUS_EDITABLE", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("SET_SELECTION", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("INSERT_TEXT", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("REPLACE_SELECTION", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SET_FULL_TEXT", ToolRisk.SENSITIVE, mutating = true, requiresFreshObservationAfter = true, maxCallsPerRun = 2),
            AgentToolSpec("MOVE_CURSOR", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SELECT_ALL", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("DELETE_SELECTED_TEXT", ToolRisk.SENSITIVE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("OPEN_NOTIFICATIONS", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("OPEN_QUICK_SETTINGS", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("OPEN_RECENTS", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SWITCH_APP", ToolRisk.SAFE_NAVIGATION, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("OPEN_URL", ToolRisk.EXTERNAL_SHARE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("OPEN_DEEP_LINK", ToolRisk.EXTERNAL_SHARE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("PICK_FROM_CHOOSER", ToolRisk.EXTERNAL_SHARE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("PICK_FILE", ToolRisk.EXTERNAL_SHARE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("PICK_PHOTO", ToolRisk.EXTERNAL_SHARE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SHARE_TO_APP", ToolRisk.EXTERNAL_SHARE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("PERMISSION_DECISION", ToolRisk.PERMISSION_OR_CREDENTIAL, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("VERIFY_TEXT_CHANGE", ToolRisk.READ_ONLY, mutating = false, requiresFreshObservationAfter = false),
            AgentToolSpec("TAP_TEXT_ANCHOR", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("ANALYZE_SCREENSHOT", ToolRisk.SENSITIVE, mutating = false, requiresFreshObservationAfter = false, maxCallsPerRun = 2),
            AgentToolSpec("INSERT_TEXT_AT_ANCHOR", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("REPLACE_TEXT_RANGE", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("APPLY_DOCUMENT_EDITS", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true, maxCallsPerRun = 2),
            AgentToolSpec("APPEND_TEXT", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("PREPEND_TEXT", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("FORMAT_CURRENT_LINE_AS_BULLET", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("REPLACE_CURRENT_DOCUMENT_TEXT", ToolRisk.SENSITIVE, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("APPEND_DOCUMENT_NOTE", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("SET_CURRENT_SHEET_CELL", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true),
            AgentToolSpec("ADD_SPREADSHEET_ROW", ToolRisk.USER_VISIBLE_EDIT, mutating = true, requiresFreshObservationAfter = true)
        ).also(::validateContractCoverage)

        private fun validateContractCoverage(specs: List<AgentToolSpec>) {
            val specNames = specs.map { it.name }.toSet()
            val contractNames = DroidLmActionContract.supportedActions.toSet()
            require(specNames == contractNames) {
                "Agent tool specs must match DroidLmActionContract. Missing specs=${contractNames - specNames}; extra specs=${specNames - contractNames}"
            }
        }
    }
}
