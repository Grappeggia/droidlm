package ai.droidlm.portal

import android.view.accessibility.AccessibilityNodeInfo

object UiNodeActionCatalog {
    fun fromAccessibilityNode(node: AccessibilityNodeInfo): List<UiNodeAction> {
        val actionIds = linkedMapOf<Int, CharSequence?>()
        node.actionList.forEach { action -> actionIds[action.id] = action.label }
        STANDARD_ACTIONS.forEach { spec ->
            if (spec.androidActionId in LEGACY_BITMASK_ACTION_IDS && (node.actions and spec.androidActionId) == spec.androidActionId) {
                actionIds.putIfAbsent(spec.androidActionId, null)
            }
        }
        return actionIds.map { (id, label) -> toUiNodeAction(id, label?.toString()) }.distinctBy { it.name }
    }

    fun labels(actions: List<UiNodeAction>): List<String> = actions.map { it.name }

    fun effectiveFromParent(parentNodeId: String, parentActions: List<UiNodeAction>): List<UiNodeAction> = parentActions
        .filter { it.droidLmAction != null && it.name in EFFECTIVE_PARENT_ACTIONS }
        .map {
            it.copy(
                targetNodeId = parentNodeId,
                reason = "nearest actionable parent"
            )
        }

    private fun toUiNodeAction(actionId: Int, label: String?): UiNodeAction {
        val spec = STANDARD_ACTIONS.firstOrNull { it.androidActionId == actionId }
        return UiNodeAction(
            name = spec?.name ?: "CUSTOM_$actionId",
            androidActionId = actionId,
            label = label?.takeIf { it.isNotBlank() },
            droidLmAction = spec?.droidLmAction,
            requiresArgs = spec?.requiresArgs ?: false,
            argSchema = spec?.argSchema.orEmpty(),
            safe = spec?.safe ?: true
        )
    }

    private data class ActionSpec(
        val name: String,
        val androidActionId: Int,
        val droidLmAction: String? = null,
        val requiresArgs: Boolean = false,
        val argSchema: Map<String, String> = emptyMap(),
        val safe: Boolean = true
    )

    private val STANDARD_ACTIONS: List<ActionSpec> = buildList {
        add(ActionSpec("CLICK", AccessibilityNodeInfo.ACTION_CLICK, droidLmAction = "TAP_NODE"))
        add(ActionSpec("FOCUS", AccessibilityNodeInfo.ACTION_FOCUS, droidLmAction = "FOCUS_NODE"))
        add(ActionSpec("CLEAR_FOCUS", AccessibilityNodeInfo.ACTION_CLEAR_FOCUS))
        add(ActionSpec("SELECT", AccessibilityNodeInfo.ACTION_SELECT))
        add(ActionSpec("CLEAR_SELECTION", AccessibilityNodeInfo.ACTION_CLEAR_SELECTION))
        add(ActionSpec("LONG_CLICK", AccessibilityNodeInfo.ACTION_LONG_CLICK, droidLmAction = "LONG_PRESS"))
        add(ActionSpec("SET_TEXT", AccessibilityNodeInfo.ACTION_SET_TEXT, droidLmAction = "SET_FULL_TEXT", requiresArgs = true, argSchema = mapOf("text" to "string"), safe = false))
        add(ActionSpec("SET_SELECTION", AccessibilityNodeInfo.ACTION_SET_SELECTION, droidLmAction = "SET_SELECTION", requiresArgs = true, argSchema = mapOf("start" to "int", "end" to "int")))
        add(ActionSpec("COPY", AccessibilityNodeInfo.ACTION_COPY))
        add(ActionSpec("CUT", AccessibilityNodeInfo.ACTION_CUT, safe = false))
        add(ActionSpec("PASTE", AccessibilityNodeInfo.ACTION_PASTE, safe = false))
        add(ActionSpec("SCROLL_FORWARD", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, droidLmAction = "SWIPE"))
        add(ActionSpec("SCROLL_BACKWARD", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, droidLmAction = "SWIPE"))
        optionalAction("EXPAND", "ACTION_EXPAND")?.let(::add)
        optionalAction("COLLAPSE", "ACTION_COLLAPSE")?.let(::add)
        optionalAction("DISMISS", "ACTION_DISMISS")?.let(::add)
        optionalAction("SHOW_ON_SCREEN", "ACTION_SHOW_ON_SCREEN")?.let(::add)
        optionalAction("SCROLL_UP", "ACTION_SCROLL_UP", droidLmAction = "SWIPE")?.let(::add)
        optionalAction("SCROLL_DOWN", "ACTION_SCROLL_DOWN", droidLmAction = "SWIPE")?.let(::add)
        optionalAction("SCROLL_LEFT", "ACTION_SCROLL_LEFT", droidLmAction = "SWIPE")?.let(::add)
        optionalAction("SCROLL_RIGHT", "ACTION_SCROLL_RIGHT", droidLmAction = "SWIPE")?.let(::add)
        optionalAction("SCROLL_TO_POSITION", "ACTION_SCROLL_TO_POSITION", requiresArgs = true, argSchema = mapOf("row" to "int", "column" to "int"))?.let(::add)
        optionalAction("SET_PROGRESS", "ACTION_SET_PROGRESS", requiresArgs = true, argSchema = mapOf("value" to "number"), safe = false)?.let(::add)
    }

    private fun optionalAction(
        name: String,
        fieldName: String,
        droidLmAction: String? = null,
        requiresArgs: Boolean = false,
        argSchema: Map<String, String> = emptyMap(),
        safe: Boolean = true
    ): ActionSpec? = optionalAccessibilityActionId(fieldName)?.let { id ->
        ActionSpec(name, id, droidLmAction, requiresArgs, argSchema, safe)
    }

    private fun optionalAccessibilityActionId(fieldName: String): Int? = runCatching {
        val action = AccessibilityNodeInfo.AccessibilityAction::class.java.getField(fieldName).get(null)
        (action as AccessibilityNodeInfo.AccessibilityAction).id
    }.getOrNull()

    private val LEGACY_BITMASK_ACTION_IDS = setOf(
        AccessibilityNodeInfo.ACTION_CLICK,
        AccessibilityNodeInfo.ACTION_FOCUS,
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS,
        AccessibilityNodeInfo.ACTION_SELECT,
        AccessibilityNodeInfo.ACTION_CLEAR_SELECTION,
        AccessibilityNodeInfo.ACTION_LONG_CLICK,
        AccessibilityNodeInfo.ACTION_SET_TEXT,
        AccessibilityNodeInfo.ACTION_SET_SELECTION,
        AccessibilityNodeInfo.ACTION_COPY,
        AccessibilityNodeInfo.ACTION_CUT,
        AccessibilityNodeInfo.ACTION_PASTE,
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    )

    private val EFFECTIVE_PARENT_ACTIONS = setOf("CLICK", "LONG_CLICK", "FOCUS", "SCROLL_FORWARD", "SCROLL_BACKWARD")
}
