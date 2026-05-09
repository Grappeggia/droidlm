package ai.droidlm.agent

import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import ai.droidlm.relay.ActiveApp
import ai.droidlm.relay.DeviceContext
import org.json.JSONArray
import org.json.JSONObject

enum class AgentDecisionStatus {
    CALL_TOOLS,
    ASK_USER,
    DONE,
    NO_OP
}

enum class ToolRisk {
    READ_ONLY,
    SAFE_NAVIGATION,
    USER_VISIBLE_EDIT,
    SENSITIVE,
    EXTERNAL_SHARE,
    INSTALL_OR_STORE,
    PERMISSION_OR_CREDENTIAL
}

data class AgentBudgets(
    val maxTurns: Int = DEFAULT_MAX_TURNS,
    val maxToolCallsTotal: Int = DEFAULT_MAX_TOOL_CALLS_TOTAL,
    val maxToolCallsPerTurn: Int = DEFAULT_MAX_TOOL_CALLS_PER_TURN,
    val maxMutatingToolCallsPerTurn: Int = DEFAULT_MAX_MUTATING_TOOL_CALLS_PER_TURN,
    val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
    val maxRuntimeMs: Long = DEFAULT_MAX_RUNTIME_MS
) {
    fun normalized(): AgentBudgets = copy(
        maxTurns = maxTurns.coerceIn(1, HARD_MAX_TURNS),
        maxToolCallsTotal = maxToolCallsTotal.coerceIn(1, HARD_MAX_TOOL_CALLS_TOTAL),
        maxToolCallsPerTurn = maxToolCallsPerTurn.coerceIn(1, HARD_MAX_TOOL_CALLS_PER_TURN),
        maxMutatingToolCallsPerTurn = maxMutatingToolCallsPerTurn.coerceIn(1, HARD_MAX_MUTATING_TOOL_CALLS_PER_TURN),
        maxConsecutiveFailures = maxConsecutiveFailures.coerceIn(1, HARD_MAX_CONSECUTIVE_FAILURES),
        maxRuntimeMs = maxRuntimeMs.coerceIn(MIN_RUNTIME_MS, HARD_MAX_RUNTIME_MS)
    )

    companion object {
        const val DEFAULT_MAX_TURNS = 4
        const val DEFAULT_MAX_TOOL_CALLS_TOTAL = 8
        const val DEFAULT_MAX_TOOL_CALLS_PER_TURN = 3
        const val DEFAULT_MAX_MUTATING_TOOL_CALLS_PER_TURN = 2
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 2
        const val HARD_MAX_TURNS = 8
        const val HARD_MAX_TOOL_CALLS_TOTAL = 16
        const val HARD_MAX_TOOL_CALLS_PER_TURN = 4
        const val HARD_MAX_MUTATING_TOOL_CALLS_PER_TURN = 3
        const val HARD_MAX_CONSECUTIVE_FAILURES = 3
        const val MIN_RUNTIME_MS = 10_000L
        const val DEFAULT_MAX_RUNTIME_MS = 75_000L
        const val HARD_MAX_RUNTIME_MS = 120_000L
    }
}

data class AgentToolCall(
    val id: String,
    val name: String,
    val args: JSONObject = JSONObject(),
    val reason: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("args", JSONObject(args.toString()))
        .put("reason", reason)
}

data class AgentDecision(
    val status: AgentDecisionStatus,
    val message: String,
    val toolCalls: List<AgentToolCall>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("status", status.name)
        .put("message", message)
        .put("toolCalls", JSONArray(toolCalls.map { it.toJson() }))
}

data class AgentToolSpec(
    val name: String,
    val risk: ToolRisk,
    val mutating: Boolean,
    val requiresFreshObservationAfter: Boolean,
    val maxCallsPerRun: Int = Int.MAX_VALUE
)

data class AgentToolExecution(
    val call: AgentToolCall,
    val spec: AgentToolSpec,
    val action: ai.droidlm.intent.DroidLmAction
)

data class AgentToolResult(
    val callId: String,
    val toolName: String,
    val result: ActionResult,
    val mutating: Boolean,
    val requiresFreshObservationAfter: Boolean
) {
    fun summary(): String = "$toolName[$callId] -> ${result.success}: ${result.message}"

    fun toJson(): JSONObject = JSONObject()
        .put("callId", callId)
        .put("toolName", toolName)
        .put("success", result.success)
        .put("message", result.message)
        .put("errorCode", result.errorCode ?: JSONObject.NULL)
        .put("mutating", mutating)
        .put("requiresFreshObservationAfter", requiresFreshObservationAfter)
}

data class AgentTurnRequest(
    val goal: String,
    val turnIndex: Int,
    val budgets: AgentBudgets,
    val remainingToolCalls: Int,
    val uiState: PortalState?,
    val packages: List<AppPackage>,
    val history: List<String>,
    val activeApp: ActiveApp? = null,
    val deviceContext: DeviceContext? = null,
    val lastResults: List<AgentToolResult> = emptyList()
)
