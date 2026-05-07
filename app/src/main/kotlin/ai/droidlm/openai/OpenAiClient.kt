package ai.droidlm.openai

import ai.droidlm.context.UiContextJson
import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.displayName
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import ai.droidlm.relay.ActiveApp
import ai.droidlm.relay.DeviceContext
import ai.droidlm.relay.PlanPreview
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.relay.RelayClient
import ai.droidlm.relay.RelayPlanRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = "https://api.openai.com/v1/chat/completions",
    private val debugLogStore: DebugLogStore? = null
) {
    private val relayJsonParser = RelayClient()

    suspend fun planPreview(apiKey: String, model: String, requestBody: RelayPlanRequest): RelayCallResult<PlanPreview> {
        if (apiKey.isBlank()) return RelayCallResult.Failure("OpenAI API key is not configured on this device", "OPENAI_API_KEY_MISSING")
        val resolvedModel = model.ifBlank { DEFAULT_MODEL }
        val payload = buildChatPayload(resolvedModel, planPreviewPrompt(requestBody), maxTokens = 1800)
        val request = buildChatRequest(apiKey, payload)
        return executeTracedChat("plan-preview", resolvedModel, payload, request) { assistantContent ->
            val plan = relayJsonParser.parsePlanPreviewJson(assistantContent)
            ParsedChat(plan, plan.toDebugJson())
        }
    }

    suspend fun planAction(apiKey: String, model: String, requestBody: RelayPlanRequest): RelayCallResult<DroidLmAction> {
        if (apiKey.isBlank()) return RelayCallResult.Failure("OpenAI API key is not configured on this device", "OPENAI_API_KEY_MISSING")
        val resolvedModel = model.ifBlank { DEFAULT_MODEL }
        val payload = buildChatPayload(resolvedModel, planActionPrompt(requestBody), maxTokens = 900)
        val request = buildChatRequest(apiKey, payload)
        return executeTracedChat("plan-action", resolvedModel, payload, request) { assistantContent ->
            val action = relayJsonParser.parsePlanActionJson(assistantContent)
            ParsedChat(action, action.toDebugJson())
        }
    }

    private fun buildChatPayload(model: String, prompt: String, maxTokens: Int): JSONObject {
        val json = JSONObject()
            .put("model", model)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", prompt))
            )
        if (usesMaxCompletionTokens(model)) {
            json.put("max_completion_tokens", maxTokens)
        } else {
            json.put("temperature", 0)
            json.put("max_tokens", maxTokens)
        }
        return json
    }

    private fun buildChatRequest(apiKey: String, json: JSONObject): Request {
        return Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun planPreviewPrompt(request: RelayPlanRequest): String = """
        Create a short Android automation plan for this user goal.
        Return only JSON with this exact shape:
        {
          "model": "${'$'}{model name}",
          "summary": "one sentence",
          "riskLevel": "LOW|MEDIUM|HIGH",
          "requiresConfirmation": false,
          "steps": [
            {"index":1,"action":"OPEN_APP","appName":"Google Sheets","packageName":"com.google.android.apps.docs.editors.sheets","reason":"why","requiresConfirmation":false}
          ]
        }
        Each step object must include an action field and all required fields for that action.
        Supported actions: OPEN_APP, OPEN_SETTINGS, TAP_NODE, FOCUS_NODE, TAP, LONG_PRESS, SWIPE, TYPE_TEXT, GLOBAL_BACK, GLOBAL_HOME, TAKE_SCREENSHOT, FOCUS_EDITABLE, SET_SELECTION, INSERT_TEXT, REPLACE_SELECTION, SET_FULL_TEXT, MOVE_CURSOR, TAP_TEXT_ANCHOR, OCR_SCREEN, ANALYZE_SCREENSHOT, INSERT_TEXT_AT_ANCHOR, REPLACE_TEXT_RANGE, APPEND_TEXT, PREPEND_TEXT, SELECT_ALL, DELETE_SELECTED_TEXT, VERIFY_TEXT_CHANGE, FORMAT_CURRENT_LINE_AS_BULLET, REPLACE_CURRENT_DOCUMENT_TEXT, APPEND_DOCUMENT_NOTE, SET_CURRENT_SHEET_CELL, ADD_SPREADSHEET_ROW, ASK_CONFIRMATION, DONE, NO_OP.
        Use Device context as authoritative state. For Google Docs, inspect docsContext.uiMode, editor, selectionContext, documentTextWindow, and availableDocActions before planning edits.
        For Google Sheets, inspect sheetsContext.uiMode, activeCell, visibleGrid, sheetTextWindow, and availableSheetActions before spreadsheet edits.
        For Google Drive, inspect driveContext.uiMode, currentLocation, visibleFiles, selectedFile, searchContext, and availableDriveActions before file operations.
        If Google Docs is not in DOCUMENT_EDIT mode, enter edit mode before typing. Prefer accessibility text and selection context over OCR; use OCR only when text context is missing.
        If Google Sheets is not in CELL_EDIT or FORMULA_BAR mode, enter cell edit mode before typing cell text. For Drive, prefer visible file nodeIds when opening/searching files.
        Ask confirmation before sharing, deleting, moving, uploading, downloading, renaming, or editing sensitive document/spreadsheet content.
        Prefer TAP_NODE or FOCUS_NODE with nodeId for visible UI targets. Only use TAP, LONG_PRESS, or SWIPE when exact coordinates are present in UI state.
        Use tapTargetNodeId or focusTargetNodeId when present. Never tap a static label's own id when effectiveActions has a targetNodeId.
        Use each UI node's availableActions as the source of truth for direct node operations, and effectiveActions when a label/text child points to a clickable or focusable parent.
        For visible files or documents, use the visibleFiles/visibleDocuments nodeId; these are already the tappable row targets.
        Do not turn LONG_CLICK node actions into LONG_PRESS unless exact x/y coordinates are available.
        Prefer node-level actions with nodeId over coordinates. If a node offers SET_TEXT or SET_SELECTION, use text actions instead of simulated typing when editing existing text.
        Keep plans to the minimum safe number of steps.

        Goal: ${request.goal}
        Max steps: ${request.maxSteps}
        Active app: ${request.activeApp?.toJson() ?: JSONObject()}
        Device context: ${request.deviceContext?.toJson() ?: JSONObject()}
        UI state: ${request.uiState?.toJson() ?: JSONObject()}
        Installed packages: ${JSONArray(request.packages.map { it.toJson() })}
        History: ${JSONArray(request.history)}
    """.trimIndent()

    private fun planActionPrompt(request: RelayPlanRequest): String = """
        Choose exactly one next Android automation action for this user goal.
        Return only one JSON action object. Do not wrap it in markdown.
        Supported actions and fields are the same as the plan preview prompt.
        Use Device context as authoritative state. For Google Docs, inspect docsContext.uiMode, editor, selectionContext, documentTextWindow, and availableDocActions before choosing edits.
        For Google Sheets, inspect sheetsContext.uiMode, activeCell, visibleGrid, sheetTextWindow, and availableSheetActions before spreadsheet edits.
        For Google Drive, inspect driveContext.uiMode, currentLocation, visibleFiles, selectedFile, searchContext, and availableDriveActions before file operations.
        If Google Docs is not in DOCUMENT_EDIT mode, enter edit mode before typing. Prefer accessibility text and selection context over OCR; use OCR only when text context is missing.
        If Google Sheets is not in CELL_EDIT or FORMULA_BAR mode, enter cell edit mode before typing cell text. For Drive, prefer visible file nodeIds when opening/searching files.
        Ask confirmation before sharing, deleting, moving, uploading, downloading, renaming, or editing sensitive document/spreadsheet content.
        Prefer TAP_NODE or FOCUS_NODE with nodeId for visible UI targets. Only use TAP, LONG_PRESS, or SWIPE when exact coordinates are present in UI state.
        Use tapTargetNodeId or focusTargetNodeId when present. Never tap a static label's own id when effectiveActions has a targetNodeId.
        Use each UI node's availableActions as the source of truth for direct node operations, and effectiveActions when a label/text child points to a clickable or focusable parent.
        For visible files or documents, use the visibleFiles/visibleDocuments nodeId; these are already the tappable row targets.
        Do not turn LONG_CLICK node actions into LONG_PRESS unless exact x/y coordinates are available.
        Prefer node-level actions with nodeId over coordinates. If a node offers SET_TEXT or SET_SELECTION, use text actions instead of simulated typing when editing existing text.
        If the task is complete, return {"action":"DONE","reason":"Task complete"}.
        If no useful action is possible, return {"action":"NO_OP","message":"brief reason"}.

        Goal: ${request.goal}
        Max steps: ${request.maxSteps}
        Active app: ${request.activeApp?.toJson() ?: JSONObject()}
        Device context: ${request.deviceContext?.toJson() ?: JSONObject()}
        UI state: ${request.uiState?.toJson() ?: JSONObject()}
        Installed packages: ${JSONArray(request.packages.map { it.toJson() })}
        History: ${JSONArray(request.history)}
    """.trimIndent()

    private suspend fun <T> executeTracedChat(
        source: String,
        model: String,
        requestJson: JSONObject,
        request: Request,
        parser: (String) -> ParsedChat<T>
    ): RelayCallResult<T> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        var httpStatus: Int? = null
        var rawResponse: String? = null
        var assistantContent: String? = null
        var parsedContent: JSONObject? = null
        var errorCode: String? = null
        var errorMessage: String? = null
        val result = try {
            client.newCall(request).execute().use { response ->
                httpStatus = response.code
                rawResponse = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = parseOpenAiError(rawResponse.orEmpty())
                    errorMessage = error.first ?: "OpenAI returned HTTP ${response.code}: ${rawResponse.orEmpty()}"
                    errorCode = error.second ?: "HTTP_${response.code}"
                    RelayCallResult.Failure(errorMessage.orEmpty(), errorCode)
                } else {
                    runCatching {
                        assistantContent = extractAssistantJson(rawResponse.orEmpty())
                        val parsed = parser(assistantContent.orEmpty())
                        parsedContent = parsed.debugJson
                        parsed.value
                    }.fold(
                        onSuccess = { value -> RelayCallResult.Success(value) },
                        onFailure = { error ->
                            errorMessage = "Invalid OpenAI JSON: ${error.message}"
                            errorCode = "INVALID_JSON"
                            RelayCallResult.Failure(errorMessage.orEmpty(), errorCode, error)
                        }
                    )
                }
            }
        } catch (error: IOException) {
            errorMessage = error.message ?: "Network error"
            errorCode = if (errorMessage?.contains("timeout", ignoreCase = true) == true) "TIMEOUT" else "NETWORK_ERROR"
            RelayCallResult.Failure(errorMessage.orEmpty(), errorCode, error)
        } catch (error: Throwable) {
            errorMessage = error.message ?: error::class.java.name
            errorCode = "OPENAI_CLIENT_ERROR"
            RelayCallResult.Failure(errorMessage.orEmpty(), errorCode, error)
        }
        retainLlmTrace(
            source = source,
            model = model,
            requestJson = requestJson,
            startedAtMs = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
            httpStatus = httpStatus,
            rawResponse = rawResponse,
            assistantContent = assistantContent,
            parsedContent = parsedContent,
            errorCode = errorCode,
            errorMessage = errorMessage,
            success = result is RelayCallResult.Success
        )
        result
    }

    private suspend fun retainLlmTrace(
        source: String,
        model: String,
        requestJson: JSONObject,
        startedAtMs: Long,
        durationMs: Long,
        httpStatus: Int?,
        rawResponse: String?,
        assistantContent: String?,
        parsedContent: JSONObject?,
        errorCode: String?,
        errorMessage: String?,
        success: Boolean
    ) {
        val trace = JSONObject()
            .put("traceId", "llm-$startedAtMs-$source")
            .put("source", source)
            .put("startedAtMs", startedAtMs)
            .put("durationMs", durationMs)
            .put("model", model)
            .put("endpoint", endpoint)
            .put("request", JSONObject(requestJson.toString()))
            .put(
                "response",
                JSONObject()
                    .put("success", success)
                    .put("httpStatus", httpStatus ?: JSONObject.NULL)
                    .put("rawBody", rawResponse ?: JSONObject.NULL)
                    .put("assistantContent", assistantContent ?: JSONObject.NULL)
                    .put("parsed", parsedContent ?: JSONObject.NULL)
                    .put(
                        "error",
                        if (errorMessage == null && errorCode == null) JSONObject.NULL else JSONObject()
                            .put("code", errorCode ?: JSONObject.NULL)
                            .put("message", errorMessage ?: JSONObject.NULL)
                    )
            )
        debugLogStore?.retainText("llm", source, trace.toString(2), extension = "json")
    }

    private fun extractAssistantJson(body: String): String {
        val obj = JSONObject(body)
        val content = obj.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
        return content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun parseOpenAiError(body: String): Pair<String?, String?> {
        return runCatching {
            val error = JSONObject(body).optJSONObject("error")
            val message = error?.optString("message")?.takeIf { it.isNotBlank() }
            val code = error?.optString("code")?.takeIf { it.isNotBlank() }
                ?: error?.optString("type")?.takeIf { it.isNotBlank() }
            message to code
        }.getOrDefault(null to null)
    }

    private fun RelayPlanRequest.toJson(): JSONObject = JSONObject()
        .put("goal", goal)
        .put("uiState", uiState?.toJson() ?: JSONObject())
        .put("packages", JSONArray(packages.map { it.toJson() }))
        .put("activeApp", activeApp?.toJson() ?: JSONObject())
        .put("deviceContext", deviceContext?.toJson() ?: JSONObject())
        .put("history", JSONArray(history))
        .put("maxSteps", maxSteps)

    private fun PortalState.toJson(): JSONObject = UiContextJson.portalStateToJson(this)

    private fun AppPackage.toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("label", label)
        .put("isSystemApp", isSystemApp)

    private fun ActiveApp.toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("activityName", activityName)
        .put("label", label)

    private fun DeviceContext.toJson(): JSONObject {
        val json = JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("activeApp", activeApp?.toJson() ?: JSONObject())
            .put("installedApps", JSONArray(packages.map { it.toJson() }))
            .put("packages", JSONArray(packages.map { it.toJson() }))
        extras.keys().forEach { key -> json.put(key, extras.opt(key)) }
        return json
    }

    private fun PlanPreview.toDebugJson(): JSONObject = JSONObject()
        .put("model", model)
        .put("summary", summary)
        .put("riskLevel", riskLevel)
        .put("requiresConfirmation", requiresConfirmation)
        .put(
            "steps",
            JSONArray(steps.map { step ->
                JSONObject()
                    .put("index", step.index)
                    .put("action", step.actionLabel)
                    .put("reason", step.reason)
                    .put("requiresConfirmation", step.requiresConfirmation)
                    .put("parsedAction", step.action.displayName())
            })
        )

    private fun DroidLmAction.toDebugJson(): JSONObject = JSONObject()
        .put("displayName", displayName())

    private data class ParsedChat<T>(val value: T, val debugJson: JSONObject)

    private fun usesMaxCompletionTokens(model: String): Boolean {
        val normalized = model.trim().lowercase()
        return normalized.startsWith("gpt-5") ||
            normalized.startsWith("o1") ||
            normalized.startsWith("o3") ||
            normalized.startsWith("o4") ||
            normalized.startsWith("o5")
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-5.4-nano"
        private const val SYSTEM_PROMPT = "You are DroidLM's Android automation planner. Return strict JSON only. Never include secrets. Prefer safe, minimal, reversible actions."
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
