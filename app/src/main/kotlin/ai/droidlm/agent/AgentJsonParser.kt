package ai.droidlm.agent

import org.json.JSONArray
import org.json.JSONObject

class AgentJsonParser {
    fun parseDecision(json: String): AgentDecision {
        val obj = JSONObject(json)
        topLevelAction(obj)?.let { return it }
        val toolCallsJson = obj.optJSONArray("toolCalls") ?: obj.optJSONArray("tool_calls") ?: JSONArray()
        val calls = buildList {
            for (index in 0 until toolCallsJson.length()) {
                val call = toolCallsJson.optJSONObject(index) ?: continue
                add(parseToolCall(call, index + 1))
            }
        }
        val status = parseStatus(obj.optString("status"), calls)
        return AgentDecision(
            status = status,
            message = obj.optString("message").takeIf { it.isNotBlank() }
                ?: obj.optString("reason").takeIf { it.isNotBlank() }
                ?: defaultMessage(status),
            toolCalls = if (status == AgentDecisionStatus.CALL_TOOLS) calls else emptyList()
        )
    }

    private fun topLevelAction(obj: JSONObject): AgentDecision? {
        val action = obj.optString("action").takeIf { it.isNotBlank() } ?: return null
        if (action.equals("DONE", ignoreCase = true)) {
            return AgentDecision(AgentDecisionStatus.DONE, obj.optString("reason", "Task complete"), emptyList())
        }
        if (action.equals("NO_OP", ignoreCase = true)) {
            return AgentDecision(AgentDecisionStatus.NO_OP, obj.optString("message", "No action"), emptyList())
        }
        if (action.equals("ASK_USER", ignoreCase = true) || action.equals("ASK_CONFIRMATION", ignoreCase = true)) {
            return AgentDecision(AgentDecisionStatus.ASK_USER, obj.optString("message", obj.optString("confirmationPrompt", "Please confirm or clarify")), emptyList())
        }
        val args = JSONObject(obj.toString())
        return AgentDecision(
            status = AgentDecisionStatus.CALL_TOOLS,
            message = obj.optString("message", "Calling one tool"),
            toolCalls = listOf(
                AgentToolCall(
                    id = obj.optString("id").takeIf { it.isNotBlank() } ?: "call_1",
                    name = action.normalizedToolName(),
                    args = args,
                    reason = obj.optString("reason")
                )
            )
        )
    }

    private fun parseToolCall(obj: JSONObject, ordinal: Int): AgentToolCall {
        val rawName = obj.optString("name")
            .ifBlank { obj.optString("tool") }
            .ifBlank { obj.optString("action") }
        require(rawName.isNotBlank()) { "tool call $ordinal requires name" }
        val args = obj.optJSONObject("args")?.let { JSONObject(it.toString()) } ?: topLevelArgs(obj)
        val reason = obj.optString("reason").takeIf { it.isNotBlank() }
            ?: args.optString("reason").takeIf { it.isNotBlank() }
            ?: ""
        return AgentToolCall(
            id = obj.optString("id").takeIf { it.isNotBlank() } ?: "call_$ordinal",
            name = rawName.normalizedToolName(),
            args = args,
            reason = reason
        )
    }

    private fun topLevelArgs(obj: JSONObject): JSONObject {
        val args = JSONObject()
        val excluded = setOf("id", "name", "tool", "args", "toolCalls", "tool_calls", "status", "message")
        obj.keys().forEach { key ->
            if (key !in excluded) args.put(key, obj.opt(key))
        }
        return args
    }

    private fun parseStatus(rawStatus: String, calls: List<AgentToolCall>): AgentDecisionStatus {
        val normalized = rawStatus.trim().uppercase()
        return enumValues<AgentDecisionStatus>().firstOrNull { it.name == normalized }
            ?: if (calls.isNotEmpty()) AgentDecisionStatus.CALL_TOOLS else AgentDecisionStatus.NO_OP
    }

    private fun defaultMessage(status: AgentDecisionStatus): String = when (status) {
        AgentDecisionStatus.CALL_TOOLS -> "Calling tools"
        AgentDecisionStatus.ASK_USER -> "Please clarify"
        AgentDecisionStatus.DONE -> "Task complete"
        AgentDecisionStatus.NO_OP -> "No action"
    }

    private fun String.normalizedToolName(): String = trim().uppercase().replace('-', '_').replace(' ', '_')
}
