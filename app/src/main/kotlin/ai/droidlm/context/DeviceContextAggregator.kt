package ai.droidlm.context

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.portal.PortalState
import ai.droidlm.relay.DeviceContext
import ai.droidlm.observation.ArtifactContext
import ai.droidlm.observation.OcrBlock
import ai.droidlm.observation.ScreenObservation
import ai.droidlm.observation.ScreenObservationBuilder
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.portal.PortalController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DeviceContextAggregator(
    private val appInventoryRepository: AppInventoryRepository,
    private val providers: List<DeviceContextProvider>,
    private val diagnostics: SpeechDiagnosticsLogger? = null,
    private val portalController: PortalController? = null,
    private val ocrEngine: OcrEngine? = null,
    private val screenObservationBuilder: ScreenObservationBuilder = ScreenObservationBuilder()
) {
    private val observationLock = Any()
    private var lastObservation: ScreenObservation? = null
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
        val screenObservation = state?.let { currentState ->
            collectScreenObservation(currentState, extras, diagnosticSessionId)
        }
        screenObservation?.let { observation ->
            extras.put("screenObservation", observation.toJson())
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

    private suspend fun collectScreenObservation(
        state: PortalState,
        extras: JSONObject,
        diagnosticSessionId: String?
    ): ScreenObservation {
        val previous = synchronized(observationLock) { lastObservation }
        var ocrAttempted = false
        var ocrError: String? = null
        val ocrBlocks = collectOcrBlocks(diagnosticSessionId)
            .onFailure { error -> ocrError = error.message ?: error::class.java.simpleName }
            .getOrDefault(emptyList())
            .also { ocrAttempted = portalController != null && ocrEngine != null }
        val artifactContext = extras.optJSONObject("artifactContext")?.let { ArtifactContext(JSONObject(it.toString())) }
        val observation = screenObservationBuilder.build(
            state = state,
            ocrBlocks = ocrBlocks,
            artifactContext = artifactContext,
            previous = previous,
            ocrAttempted = ocrAttempted,
            ocrError = ocrError
        )
        synchronized(observationLock) { lastObservation = observation }
        diagnostics?.record(
            diagnosticSessionId,
            "screen_observation_collected",
            mapOf(
                "observationId" to observation.observationId,
                "screenHash" to observation.screenHash,
                "nodeCount" to observation.nodes.size,
                "ocrAttempted" to ocrAttempted,
                "ocrBlockCount" to observation.ocrBlocks.size,
                "ocrError" to ocrError,
                "confidenceScore" to observation.confidence.score,
                "hasPriorDelta" to (observation.priorActionDelta != null)
            )
        )
        return observation
    }

    private suspend fun collectOcrBlocks(diagnosticSessionId: String?): Result<List<OcrBlock>> {
        val controller = portalController ?: return Result.success(emptyList())
        val engine = ocrEngine ?: return Result.success(emptyList())
        val screenshot = controller.takeScreenshot()
        val bitmap = screenshot.bitmap
        if (!screenshot.success || bitmap == null) {
            val message = screenshot.message.ifBlank { "Screenshot capture failed" }
            diagnostics?.record(
                diagnosticSessionId,
                "screen_observation_ocr_unavailable",
                mapOf("errorCode" to screenshot.errorCode, "message" to message)
            )
            return Result.failure(IllegalStateException(message))
        }
        return runCatching {
            val result = engine.recognize(bitmap)
            result.blocks.map { block ->
                OcrBlock(
                    text = block.text,
                    bounds = block.boundingBox?.let { android.graphics.Rect(it) },
                    confidence = block.confidence,
                    source = result.source.name
                )
            }
        }.recoverCatching { error ->
            diagnostics?.record(
                diagnosticSessionId,
                "screen_observation_ocr_failed",
                mapOf("errorClass" to error::class.java.name, "message" to error.message)
            )
            throw error
        }
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
