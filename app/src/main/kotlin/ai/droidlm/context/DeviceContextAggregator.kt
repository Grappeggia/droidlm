package ai.droidlm.context

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.portal.PortalState
import ai.droidlm.relay.DeviceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DeviceContextAggregator(
    private val appInventoryRepository: AppInventoryRepository,
    private val providers: List<DeviceContextProvider>,
    private val diagnostics: SpeechDiagnosticsLogger? = null
) {
    suspend fun collect(
        goal: String?,
        state: PortalState?,
        history: List<String> = emptyList(),
        diagnosticSessionId: String? = null
    ): DeviceContext = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        diagnostics?.record(
            diagnosticSessionId,
            "context_collection_started",
            mapOf(
                "goalLength" to (goal?.length ?: 0),
                "historyCount" to history.size,
                "hasPortalState" to (state != null),
                "activePackage" to state?.packageName,
                "nodeCount" to (state?.nodes?.size ?: 0),
                "providerCount" to providers.size
            )
        )
        val inventoryStartedAt = System.currentTimeMillis()
        val packages = appInventoryRepository.getInstalledApps()
        val activeApp = appInventoryRepository.activeAppFor(state)
        diagnostics?.record(
            diagnosticSessionId,
            "context_inventory_collected",
            mapOf(
                "durationMs" to (System.currentTimeMillis() - inventoryStartedAt),
                "packageCount" to packages.size,
                "activePackage" to activeApp?.packageName,
                "activeActivity" to activeApp?.activityName,
                "activeLabelConfigured" to !activeApp?.label.isNullOrBlank()
            )
        )
        val request = DeviceContextRequest(
            goal = goal,
            state = state,
            activeApp = activeApp,
            packages = packages,
            history = history
        )
        val extras = JSONObject()
        providers.forEach { provider ->
            val providerName = provider::class.java.simpleName
            val providerStartedAt = System.currentTimeMillis()
            runCatching { provider.collect(request) }
                .onSuccess { json ->
                    diagnostics?.record(
                        diagnosticSessionId,
                        "context_provider_collected",
                        mapOf("provider" to providerName, "durationMs" to (System.currentTimeMillis() - providerStartedAt)) + jsonSummary(json)
                    )
                    merge(extras, json)
                }
                .onFailure { error ->
                    diagnostics?.record(
                        diagnosticSessionId,
                        "context_provider_failed",
                        mapOf(
                            "provider" to providerName,
                            "durationMs" to (System.currentTimeMillis() - providerStartedAt),
                            "errorClass" to error::class.java.name,
                            "message" to error.message
                        )
                    )
                }
        }
        val context = DeviceContext(
            activeApp = activeApp,
            packages = packages,
            extras = extras
        )
        diagnostics?.record(
            diagnosticSessionId,
            "context_collection_finished",
            mapOf(
                "durationMs" to (System.currentTimeMillis() - startedAt),
                "packageCount" to packages.size,
                "activePackage" to activeApp?.packageName
            ) + jsonSummary(extras)
        )
        context
    }

    private fun merge(target: JSONObject, source: JSONObject) {
        source.keys().forEach { key -> target.put(key, source.opt(key)) }
    }

    private fun jsonSummary(json: JSONObject): Map<String, Any?> {
        val keys = json.keys().asSequence().toList()
        return mapOf(
            "topLevelKeys" to keys,
            "topLevelKeyCount" to keys.size,
            "jsonBytes" to json.toString().toByteArray(Charsets.UTF_8).size,
            "screenTextChars" to countLikelyTextChars(json),
            "arrayValueCount" to countArrayValues(json),
            "truncatedTextFieldCount" to countTruncatedStrings(json)
        )
    }

    private fun countLikelyTextChars(value: Any?): Int = when (value) {
        is JSONObject -> value.keys().asSequence().sumOf { key ->
            val child = value.opt(key)
            if (key.contains("text", ignoreCase = true) || key.contains("title", ignoreCase = true) || key.contains("label", ignoreCase = true)) {
                child?.toString()?.length ?: 0
            } else {
                countLikelyTextChars(child)
            }
        }
        is JSONArray -> (0 until value.length()).sumOf { index -> countLikelyTextChars(value.opt(index)) }
        else -> 0
    }

    private fun countArrayValues(value: Any?): Int = when (value) {
        is JSONObject -> value.keys().asSequence().sumOf { key -> countArrayValues(value.opt(key)) }
        is JSONArray -> value.length() + (0 until value.length()).sumOf { index -> countArrayValues(value.opt(index)) }
        else -> 0
    }

    private fun countTruncatedStrings(value: Any?): Int = when (value) {
        is JSONObject -> value.keys().asSequence().sumOf { key -> countTruncatedStrings(value.opt(key)) }
        is JSONArray -> (0 until value.length()).sumOf { index -> countTruncatedStrings(value.opt(index)) }
        is String -> if (value.endsWith("...")) 1 else 0
        else -> 0
    }
}
