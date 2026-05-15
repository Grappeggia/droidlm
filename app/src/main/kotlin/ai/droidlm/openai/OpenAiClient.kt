package ai.droidlm.openai

import ai.droidlm.agent.AgentBudgets
import ai.droidlm.agent.AgentDecision
import ai.droidlm.agent.AgentJsonParser
import ai.droidlm.agent.AgentToolRegistry
import ai.droidlm.agent.AgentTurnRequest
import ai.droidlm.context.UiContextJson
import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.diagnostics.NetworkDiagnostics
import ai.droidlm.intent.DroidLmActionContract
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
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

class OpenAiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = "https://api.openai.com/v1/chat/completions",
    private val debugLogStore: DebugLogStore? = null,
    private val networkDiagnostics: NetworkDiagnostics? = null
) {
    private val relayJsonParser = RelayClient()
    private val agentJsonParser = AgentJsonParser()
    private val networkTraceRecorder = HttpNetworkTraceRecorder()
    private val tracedClient: OkHttpClient = client.newBuilder()
        .eventListenerFactory(networkTraceRecorder)
        .build()

    suspend fun planPreview(apiKey: String, model: String, requestBody: RelayPlanRequest): RelayCallResult<PlanPreview> {
        if (apiKey.isBlank()) return RelayCallResult.Failure("OpenAI API key is not configured on this device", "OPENAI_API_KEY_MISSING")
        val resolvedModel = model.ifBlank { DEFAULT_MODEL }
        val payload = buildChatPayload(resolvedModel, planPreviewPrompt(requestBody), maxTokens = 1800)
        val request = buildChatRequest(apiKey, payload)
        return executeTracedChat("plan-preview", apiKey, resolvedModel, payload, request) { assistantContent ->
            val plan = relayJsonParser.parsePlanPreviewJson(assistantContent)
            ParsedChat(plan, plan.toDebugJson())
        }
    }

    suspend fun planAction(apiKey: String, model: String, requestBody: RelayPlanRequest): RelayCallResult<DroidLmAction> {
        if (apiKey.isBlank()) return RelayCallResult.Failure("OpenAI API key is not configured on this device", "OPENAI_API_KEY_MISSING")
        val resolvedModel = model.ifBlank { DEFAULT_MODEL }
        val payload = buildChatPayload(resolvedModel, planActionPrompt(requestBody), maxTokens = 900)
        val request = buildChatRequest(apiKey, payload)
        return executeTracedChat("plan-action", apiKey, resolvedModel, payload, request) { assistantContent ->
            val action = relayJsonParser.parsePlanActionJson(assistantContent)
            ParsedChat(action, action.toDebugJson())
        }
    }

    suspend fun nextAgentTurn(apiKey: String, model: String, requestBody: AgentTurnRequest): RelayCallResult<AgentDecision> {
        if (apiKey.isBlank()) return RelayCallResult.Failure("OpenAI API key is not configured on this device", "OPENAI_API_KEY_MISSING")
        val resolvedModel = model.ifBlank { DEFAULT_MODEL }
        val payload = buildChatPayload(resolvedModel, agentTurnPrompt(requestBody), maxTokens = 1200)
        val request = buildChatRequest(apiKey, payload)
        return executeTracedChat("agent-turn", apiKey, resolvedModel, payload, request) { assistantContent ->
            val decision = agentJsonParser.parseDecision(assistantContent)
            ParsedChat(decision, decision.toJson())
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
            {"index":1,"action":"OPEN_APP","appName":"<installed launchable app label>","packageName":"<installed.launchable.package>","reason":"why","requiresConfirmation":false}
          ]
        }
        Each step object must include an action field and all required fields for that action.
        Supported actions: ${DroidLmActionContract.supportedActionsPrompt}
        Use OPEN_APP only when the target package appears in installed packages with launchable=true. If the requested app is missing, disabled, or not launchable, ask confirmation and use OPEN_APP_STORE_LISTING with the requested packageName. If the command does not include an app name, return NO_OP with a brief clarification instead of guessing.
        If Device context includes artifactContext, treat it as the primary source for current document, spreadsheet, or folder navigation. Inspect artifactContext.navigationTargets, artifactContext.contentWindow, artifactContext.surface, and artifactContext.availableTools before deciding to launch another app.
        If the goal is to navigate, go, jump, scroll, find, or search within the current Google Docs, Sheets, or Drive artifact and artifactContext already contains a matching target label, do not OPEN_APP. Prefer NAVIGATE_TO_ARTIFACT_TARGET with {"label":"target text","nodeId":"optional visible node id","kind":"optional target kind","reason":"why"}.
        Use Device context as authoritative state. For Google Docs, inspect docsContext.uiMode, editor, selectionContext, documentTextWindow, and availableDocActions before planning edits.
        For Google Sheets, inspect sheetsContext.uiMode, activeCell, visibleGrid, sheetTextWindow, and availableSheetActions before spreadsheet edits.
        For Google Drive, inspect driveContext.uiMode, currentLocation, visibleFiles, selectedFile, searchContext, and availableDriveActions before file operations.
        If Google Docs is not in DOCUMENT_EDIT mode, enter edit mode before typing. Prefer accessibility text and selection context over OCR; use OCR only when text context is missing.
        If Google Sheets is not in CELL_EDIT or FORMULA_BAR mode, enter cell edit mode before typing cell text. For Drive, prefer visible file nodeIds when opening/searching files.
        Ask confirmation before sharing, deleting, moving, uploading, downloading, renaming, or editing sensitive document/spreadsheet content.
        Prefer semantic actions over raw coordinates. Use SCROLL for scroll intents, TAP_TEXT for visible labels, LONG_PRESS_NODE for visible items, WAIT_FOR_UI after transitions, DIALOG_ACTION for popups, OPEN_MENU for drawer/overflow menus, SELECT_TAB for tab switches, SET_TOGGLE for switches, EXPAND_COLLAPSE for accordions, SET_SLIDER for sliders, REFRESH for pull-to-refresh flows, and OPEN_URL or OPEN_DEEP_LINK when direct navigation is safer.
        Prefer TAP_NODE or FOCUS_NODE with nodeId for visible UI targets. Only use TAP, LONG_PRESS, or SWIPE when exact coordinates are present in UI state and no semantic action fits.
        Use tapTargetNodeId or focusTargetNodeId when present. Never tap a static label's own id when effectiveActions has a targetNodeId.
        Use each UI node's availableActions as the source of truth for direct node operations, and effectiveActions when a label/text child points to a clickable or focusable parent.
        For visible files or documents, use the visibleFiles/visibleDocuments nodeId; these are already the tappable row targets.
        Do not emit malformed coordinate actions. If a scroll is intended but coordinates are unavailable, emit SCROLL with direction. If a long-press target is visible but x/y are unavailable, emit LONG_PRESS_NODE with nodeId or text. If a text label is visible but nodeId is missing, emit TAP_TEXT instead of TAP.
        Prefer node-level actions with nodeId over coordinates. If a node offers SET_TEXT, SET_SELECTION, LONG_CLICK, SCROLL_*, EXPAND, COLLAPSE, DISMISS, or SET_PROGRESS, use the matching semantic action instead of a gesture fallback.
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
        Use OPEN_APP only for installed packages with launchable=true. If the requested app is missing, disabled, or not launchable, ask confirmation and use OPEN_APP_STORE_LISTING. If the goal is ambiguous, return {"action":"NO_OP","message":"Please say which app to open."}.
        Supported actions and fields are the same as the plan preview prompt.
        If Device context includes artifactContext, treat it as the primary source for current document, spreadsheet, or folder navigation. Inspect artifactContext.navigationTargets, artifactContext.contentWindow, artifactContext.surface, and artifactContext.availableTools before deciding to launch another app.
        If the goal is to navigate, go, jump, scroll, find, or search within the current Google Docs, Sheets, or Drive artifact and artifactContext already contains a matching target label, do not OPEN_APP. Prefer NAVIGATE_TO_ARTIFACT_TARGET with {"label":"target text","nodeId":"optional visible node id","kind":"optional target kind","reason":"why"}.
        Use Device context as authoritative state. For Google Docs, inspect docsContext.uiMode, editor, selectionContext, documentTextWindow, and availableDocActions before choosing edits.
        For Google Sheets, inspect sheetsContext.uiMode, activeCell, visibleGrid, sheetTextWindow, and availableSheetActions before spreadsheet edits.
        For Google Drive, inspect driveContext.uiMode, currentLocation, visibleFiles, selectedFile, searchContext, and availableDriveActions before file operations.
        If Google Docs is not in DOCUMENT_EDIT mode, enter edit mode before typing. Prefer accessibility text and selection context over OCR; use OCR only when text context is missing.
        If Google Sheets is not in CELL_EDIT or FORMULA_BAR mode, enter cell edit mode before typing cell text. For Drive, prefer visible file nodeIds when opening/searching files.
        Ask confirmation before sharing, deleting, moving, uploading, downloading, renaming, or editing sensitive document/spreadsheet content.
        Prefer semantic actions over raw coordinates. Use SCROLL for scroll intents, TAP_TEXT for visible labels, LONG_PRESS_NODE for visible items, WAIT_FOR_UI after transitions, DIALOG_ACTION for popups, OPEN_MENU for drawer/overflow menus, SELECT_TAB for tab switches, SET_TOGGLE for switches, EXPAND_COLLAPSE for accordions, SET_SLIDER for sliders, REFRESH for pull-to-refresh flows, and OPEN_URL or OPEN_DEEP_LINK when direct navigation is safer.
        Prefer TAP_NODE or FOCUS_NODE with nodeId for visible UI targets. Only use TAP, LONG_PRESS, or SWIPE when exact coordinates are present in UI state and no semantic action fits.
        Use tapTargetNodeId or focusTargetNodeId when present. Never tap a static label's own id when effectiveActions has a targetNodeId.
        Use each UI node's availableActions as the source of truth for direct node operations, and effectiveActions when a label/text child points to a clickable or focusable parent.
        For visible files or documents, use the visibleFiles/visibleDocuments nodeId; these are already the tappable row targets.
        Do not emit malformed coordinate actions. If a scroll is intended but coordinates are unavailable, emit SCROLL with direction. If a long-press target is visible but x/y are unavailable, emit LONG_PRESS_NODE with nodeId or text. If a text label is visible but nodeId is missing, emit TAP_TEXT instead of TAP.
        Prefer node-level actions with nodeId over coordinates. If a node offers SET_TEXT, SET_SELECTION, LONG_CLICK, SCROLL_*, EXPAND, COLLAPSE, DISMISS, or SET_PROGRESS, use the matching semantic action instead of a gesture fallback.
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

    private fun agentTurnPrompt(request: AgentTurnRequest): String = """
        You are DroidLM's constrained Android agent runtime.
        Return only JSON with this exact shape:
        {
          "status": "CALL_TOOLS|ASK_USER|DONE|NO_OP",
          "message": "brief user-visible status",
          "toolCalls": [
            {"id":"call_1","name":"OPEN_APP","args":{"packageName":"installed.launchable.package","appName":"Installed App","reason":"why"},"reason":"why"}
          ]
        }
        You may request at most ${request.budgets.maxToolCallsPerTurn} tool calls this turn and ${request.remainingToolCalls} more tool calls for the whole run. Prefer one tool call unless the next calls are clearly safe and do not depend on a changed UI.
        Use DONE only after the observed UI or tool results show the goal is complete. Use ASK_USER for ambiguity. Use NO_OP when no useful safe action is possible.
        Use installed packages as authoritative: OPEN_APP only when package launchable=true and enabled is not false. If the requested app is missing, use ASK_USER or one confirmed OPEN_APP_STORE_LISTING.
        Prefer node tools with nodeId over coordinates. Never invent node IDs. Never repeat a failed call unless the observation changed or the strategy changed.
        DroidLM validates safety and may ask confirmation. Do not try to bypass confirmations. Avoid high-risk tools unless directly requested.
        DroidLM verifies app launches, wait targets, visible text, and text-change checks after tools run. If a prior result says verification failed, choose a changed strategy instead of repeating the same call.
        After app launches, taps, scrolling, back/home, text edits, dialog actions, or app-store actions, prefer ending this turn so DroidLM can observe fresh UI before more calls.
        If Device context includes artifactContext, use it as the primary source for current document, spreadsheet, or folder navigation. Inspect artifactContext.navigationTargets, artifactContext.contentWindow, artifactContext.surface, and artifactContext.availableTools before deciding to launch another app.
        If the goal is to navigate, go, jump, scroll, find, or search within the current Google Docs, Sheets, or Drive artifact and artifactContext already contains a matching target label, stay in the current app. Do not OPEN_APP or SWITCH_APP for names like section titles, files, tabs, or headings. Prefer NAVIGATE_TO_ARTIFACT_TARGET with {"label":"target text","nodeId":"optional visible node id","kind":"optional target kind","reason":"why"}.

        Available tools: ${JSONArray(AgentToolRegistry.defaultSpecs().map { it.toJson() })}
        Goal: ${request.goal}
        Turn: ${request.turnIndex}/${request.budgets.maxTurns}
        Budgets: ${request.budgets.toJson()}
        Remaining tool calls: ${request.remainingToolCalls}
        Active app: ${request.activeApp?.toJson() ?: JSONObject()}
        Device context: ${request.deviceContext?.toJson() ?: JSONObject()}
        UI state: ${request.uiState?.toJson() ?: JSONObject()}
        Installed packages: ${JSONArray(request.packages.map { it.toJson() })}
        History: ${JSONArray(request.history)}
        Last tool results: ${JSONArray(request.lastResults.map { it.toJson() })}
    """.trimIndent()


    private suspend fun <T> executeTracedChat(
        source: String,
        apiKey: String,
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
        var call: Call? = null
        val result = try {
            call = tracedClient.newCall(request)
            call.execute().use { response ->
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
                        val parsed = parseAssistantContent(apiKey, source, model, assistantContent.orEmpty(), parser)
                        parsedContent = parsed.debugJson
                        assistantContent = parsed.assistantContent
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
            val rawErrorMessage = error.message ?: "Network error"
            val isTimeout = error is SocketTimeoutException || rawErrorMessage.contains("timeout", ignoreCase = true)
            errorMessage = if (isTimeout && !rawErrorMessage.contains("timeout", ignoreCase = true)) {
                "Network timeout: $rawErrorMessage"
            } else {
                rawErrorMessage
            }
            errorCode = if (isTimeout) "TIMEOUT" else "NETWORK_ERROR"
            RelayCallResult.Failure(errorMessage.orEmpty(), errorCode, error)
        } catch (error: Throwable) {
            errorMessage = error.message ?: error::class.java.name
            errorCode = "OPENAI_CLIENT_ERROR"
            RelayCallResult.Failure(errorMessage.orEmpty(), errorCode, error)
        }
        val networkTrace = networkTraceRecorder.snapshotAndRemove(call)
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
            success = result is RelayCallResult.Success,
            networkTrace = networkTrace,
            connectivityFields = networkDiagnostics?.connectivityFields(endpoint)
        )
        result
    }

    private fun <T> parseAssistantContent(
        apiKey: String,
        source: String,
        model: String,
        assistantJson: String,
        parser: (String) -> ParsedChat<T>
    ): ParsedAssistant<T> {
        return runCatching { parser(assistantJson) }
            .fold(
                onSuccess = { parsed -> ParsedAssistant(parsed.value, parsed.debugJson, assistantJson) },
                onFailure = { error ->
                    val repairedAssistantJson = repairAssistantJson(apiKey, source, model, assistantJson, error.message ?: error::class.java.simpleName)
                    val repaired = parser(repairedAssistantJson)
                    val debugJson = JSONObject(repaired.debugJson.toString())
                        .put("repairAttempted", true)
                    ParsedAssistant(repaired.value, debugJson, repairedAssistantJson)
                }
            )
    }

    private fun repairAssistantJson(
        apiKey: String,
        source: String,
        model: String,
        invalidJson: String,
        errorMessage: String
    ): String {
        val payload = buildChatPayload(
            model = model,
            prompt = repairPrompt(source, invalidJson, errorMessage),
            maxTokens = 900
        )
        val request = buildChatRequest(apiKey, payload)
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = parseOpenAiError(body)
                throw IOException(error.first ?: "OpenAI repair returned HTTP ${response.code}")
            }
            return extractAssistantJson(body)
        }
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
        success: Boolean,
        networkTrace: Map<String, Any?>?,
        connectivityFields: Map<String, Any?>?
    ) {
        val trace = JSONObject()
            .put("traceId", "llm-$startedAtMs-$source")
            .put("source", source)
            .put("endpointMode", "direct_openai")
            .put("startedAtMs", startedAtMs)
            .put("durationMs", durationMs)
            .put("model", model)
            .put("endpoint", endpoint)
            .put("timeoutConfig", mapToJson(timeoutConfigFields()))
            .put("requestMetadata", mapToJson(requestMetadata(requestJson)))
            .put("responseMetadata", mapToJson(responseMetadata(rawResponse, assistantContent, parsedContent)))
            .put("connectivity", mapToJson(connectivityFields.orEmpty()))
            .put("networkTrace", mapToJson(networkTrace.orEmpty()))
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

    private fun timeoutConfigFields(): Map<String, Any?> = mapOf(
        "connectTimeoutMs" to tracedClient.connectTimeoutMillis,
        "readTimeoutMs" to tracedClient.readTimeoutMillis,
        "writeTimeoutMs" to tracedClient.writeTimeoutMillis,
        "callTimeoutMs" to tracedClient.callTimeoutMillis,
        "retryOnConnectionFailure" to tracedClient.retryOnConnectionFailure
    )

    private fun requestMetadata(requestJson: JSONObject): Map<String, Any?> {
        val requestText = requestJson.toString()
        val messages = requestJson.optJSONArray("messages") ?: JSONArray()
        var promptChars = 0
        val messageSummaries = mutableListOf<Map<String, Any?>>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val content = message.optString("content")
            promptChars += content.length
            messageSummaries += mapOf(
                "index" to index,
                "role" to message.optString("role"),
                "contentChars" to content.length,
                "contentTokenEstimate" to estimateTokens(content.length)
            )
        }
        return mapOf(
            "requestBytes" to requestText.toByteArray(Charsets.UTF_8).size,
            "messageCount" to messages.length(),
            "promptChars" to promptChars,
            "promptTokenEstimate" to estimateTokens(promptChars),
            "maxCompletionTokens" to requestJson.opt("max_completion_tokens"),
            "maxTokens" to requestJson.opt("max_tokens"),
            "responseFormat" to requestJson.optJSONObject("response_format")?.optString("type"),
            "messages" to messageSummaries
        )
    }

    private fun responseMetadata(rawResponse: String?, assistantContent: String?, parsedContent: JSONObject?): Map<String, Any?> = mapOf(
        "rawResponseBytes" to (rawResponse?.toByteArray(Charsets.UTF_8)?.size ?: 0),
        "assistantContentBytes" to (assistantContent?.toByteArray(Charsets.UTF_8)?.size ?: 0),
        "assistantContentChars" to (assistantContent?.length ?: 0),
        "parsedBytes" to (parsedContent?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0)
    )

    private fun estimateTokens(chars: Int): Int = (chars + 3) / 4

    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, jsonValue(value)) }
        return json
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject -> value
        is JSONArray -> value
        is Map<*, *> -> {
            val json = JSONObject()
            value.forEach { (key, item) -> json.put(key.toString(), jsonValue(item)) }
            json
        }
        is Iterable<*> -> JSONArray(value.map(::jsonValue))
        is Array<*> -> JSONArray(value.map(::jsonValue))
        else -> value
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

    private fun repairPrompt(source: String, invalidJson: String, errorMessage: String): String = """
        Repair this DroidLM $source JSON so it is valid for the original intent.
        Supported actions: ${DroidLmActionContract.supportedActionsPrompt}
        Return JSON only. Keep the same meaning. Prefer semantic Android actions like SCROLL, TAP_TEXT, LONG_PRESS_NODE, WAIT_FOR_UI, DIALOG_ACTION, OPEN_MENU, SELECT_TAB, SET_TOGGLE, EXPAND_COLLAPSE, SET_SLIDER, REFRESH, OPEN_NOTIFICATIONS, OPEN_QUICK_SETTINGS, OPEN_RECENTS, OPEN_URL, and OPEN_DEEP_LINK when they fit.
        If coordinates are missing for a scroll intent, convert it to SCROLL with a direction.
        If coordinates are missing for a long-press on a UI element, convert it to LONG_PRESS_NODE using nodeId or text.

        Validation error: $errorMessage
        Invalid JSON:
        $invalidJson
    """.trimIndent()

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
        .put("enabled", enabled)
        .put("launchable", launchable)
        .put("launchActivity", launchActivity)

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

    private fun AgentBudgets.toJson(): JSONObject = JSONObject()
        .put("maxTurns", maxTurns)
        .put("maxToolCallsTotal", maxToolCallsTotal)
        .put("maxToolCallsPerTurn", maxToolCallsPerTurn)
        .put("maxMutatingToolCallsPerTurn", maxMutatingToolCallsPerTurn)
        .put("maxConsecutiveFailures", maxConsecutiveFailures)
        .put("maxRuntimeMs", maxRuntimeMs)

    private fun ai.droidlm.agent.AgentToolSpec.toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("risk", risk.name)
        .put("mutating", mutating)
        .put("requiresFreshObservationAfter", requiresFreshObservationAfter)
        .put("maxCallsPerRun", maxCallsPerRun)

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
                    .put("parsed", step.action.toDebugJson())
            })
        )

    private fun DroidLmAction.toDebugJson(): JSONObject {
        val json = JSONObject().put("displayName", displayName())
        when (this) {
            is DroidLmAction.OpenApp -> json.put("appName", appName).put("packageName", packageName)
            is DroidLmAction.OpenAppStoreListing -> json.put("appName", appName).put("packageName", packageName)
            is DroidLmAction.SwitchApp -> json.put("appName", appName).put("packageName", packageName)
            is DroidLmAction.ShareToApp -> json.put("appName", appName).put("packageName", packageName)
            else -> Unit
        }
        return json
    }

    private data class ParsedChat<T>(val value: T, val debugJson: JSONObject)
    private data class ParsedAssistant<T>(val value: T, val debugJson: JSONObject, val assistantContent: String)

    private class HttpNetworkTraceRecorder : EventListener.Factory {
        private val traces = ConcurrentHashMap<Call, MutableNetworkTrace>()

        override fun create(call: Call): EventListener {
            val trace = MutableNetworkTrace(call.request().url.toString())
            traces[call] = trace
            return Listener(trace)
        }

        fun snapshotAndRemove(call: Call?): Map<String, Any?>? {
            if (call == null) return null
            return traces.remove(call)?.toMap()
        }
    }

    private class Listener(private val trace: MutableNetworkTrace) : EventListener() {
        override fun callStart(call: Call) {
            trace.mark("callStart", mapOf("method" to call.request().method, "host" to call.request().url.host))
        }

        override fun dnsStart(call: Call, domainName: String) {
            trace.dnsStarted(domainName)
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            trace.dnsEnded(domainName, inetAddressList)
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            trace.connectStarted(inetSocketAddress, proxy)
        }

        override fun secureConnectStart(call: Call) {
            trace.tlsStarted()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            trace.tlsEnded(handshake)
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            trace.connectEnded(inetSocketAddress, proxy, protocol)
        }

        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            trace.connectFailed(inetSocketAddress, proxy, protocol, ioe)
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            trace.connectionAcquired(connection)
        }

        override fun requestHeadersStart(call: Call) {
            trace.spanStarted("requestHeaders")
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            trace.spanEnded("requestHeaders", mapOf("headerCount" to request.headers.size))
        }

        override fun requestBodyStart(call: Call) {
            trace.spanStarted("requestBody")
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            trace.spanEnded("requestBody", mapOf("byteCount" to byteCount))
        }

        override fun responseHeadersStart(call: Call) {
            trace.spanStarted("responseHeaders")
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            trace.responseHeadersEnded(response)
        }

        override fun responseBodyStart(call: Call) {
            trace.spanStarted("responseBody")
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            trace.spanEnded("responseBody", mapOf("byteCount" to byteCount))
        }

        override fun callEnd(call: Call) {
            trace.mark("callEnd", mapOf("durationMs" to trace.elapsedMs()))
        }

        override fun callFailed(call: Call, ioe: IOException) {
            trace.failed("callFailed", ioe)
        }
    }

    private class MutableNetworkTrace(private val url: String) {
        private val startedNanos = System.nanoTime()
        private val spanStarts = mutableMapOf<String, Long>()
        private val durations = linkedMapOf<String, Long>()
        private val events = mutableListOf<Map<String, Any?>>()
        private val dnsAddresses = mutableListOf<String>()
        private val remoteAddresses = mutableListOf<String>()
        private var selectedRemoteAddress: String? = null
        private var connectAttemptCount = 0
        private var responseCode: Int? = null
        private var responseBodyBytes: Long? = null
        private var failureClass: String? = null
        private var failureMessage: String? = null
        private var failureCauseClass: String? = null
        private var failureCauseMessage: String? = null

        @Synchronized fun mark(name: String, fields: Map<String, Any?> = emptyMap()) {
            events += mapOf("name" to name, "tMs" to elapsedMs()) + fields
        }

        @Synchronized fun dnsStarted(domainName: String) {
            spanStarts["dns"] = System.nanoTime()
            mark("dnsStart", mapOf("domainName" to domainName))
        }

        @Synchronized fun dnsEnded(domainName: String, addresses: List<InetAddress>) {
            endDuration("dns")
            dnsAddresses.clear()
            dnsAddresses += addresses.mapNotNull { it.hostAddress }
            mark("dnsEnd", mapOf("domainName" to domainName, "addresses" to dnsAddresses, "addressCount" to addresses.size))
        }

        @Synchronized fun connectStarted(address: InetSocketAddress, proxy: Proxy) {
            connectAttemptCount += 1
            spanStarts["connect"] = System.nanoTime()
            remoteAddresses += socketAddress(address)
            mark("connectStart", mapOf("attempt" to connectAttemptCount, "remoteAddress" to socketAddress(address), "proxy" to proxyFields(proxy)))
        }

        @Synchronized fun connectEnded(address: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            endDuration("connect")
            selectedRemoteAddress = socketAddress(address)
            mark("connectEnd", mapOf("remoteAddress" to socketAddress(address), "proxy" to proxyFields(proxy), "protocol" to protocol?.toString()))
        }

        @Synchronized fun connectFailed(address: InetSocketAddress, proxy: Proxy, protocol: Protocol?, error: IOException) {
            endDuration("connect")
            selectedRemoteAddress = socketAddress(address)
            captureFailure(error)
            mark(
                "connectFailed",
                mapOf(
                    "remoteAddress" to socketAddress(address),
                    "proxy" to proxyFields(proxy),
                    "protocol" to protocol?.toString(),
                    "errorClass" to error::class.java.name,
                    "message" to error.message,
                    "causeClass" to error.cause?.javaClass?.name,
                    "causeMessage" to error.cause?.message
                )
            )
        }

        @Synchronized fun tlsStarted() {
            spanStarts["tls"] = System.nanoTime()
            mark("secureConnectStart")
        }

        @Synchronized fun tlsEnded(handshake: Handshake?) {
            endDuration("tls")
            mark(
                "secureConnectEnd",
                mapOf(
                    "tlsVersion" to handshake?.tlsVersion?.javaName,
                    "cipherSuite" to handshake?.cipherSuite?.javaName
                )
            )
        }

        @Synchronized fun connectionAcquired(connection: Connection) {
            mark(
                "connectionAcquired",
                mapOf(
                    "protocol" to connection.protocol().toString(),
                    "routeSocketAddress" to connection.route().socketAddress.toString(),
                    "routeProxy" to proxyFields(connection.route().proxy)
                )
            )
        }

        @Synchronized fun spanStarted(name: String) {
            spanStarts[name] = System.nanoTime()
            mark("${name}Start")
        }

        @Synchronized fun spanEnded(name: String, fields: Map<String, Any?> = emptyMap()) {
            val durationMs = endDuration(name)
            if (name == "responseBody") responseBodyBytes = fields["byteCount"] as? Long
            mark("${name}End", mapOf("durationMs" to durationMs) + fields)
        }

        @Synchronized fun responseHeadersEnded(response: Response) {
            responseCode = response.code
            val durationMs = endDuration("responseHeaders")
            mark("responseHeadersEnd", mapOf("durationMs" to durationMs, "httpStatus" to response.code, "headerCount" to response.headers.size))
        }

        @Synchronized fun failed(name: String, error: IOException) {
            captureFailure(error)
            mark(name, mapOf("errorClass" to error::class.java.name, "message" to error.message, "durationMs" to elapsedMs()))
        }

        @Synchronized fun toMap(): Map<String, Any?> = mapOf(
            "url" to url,
            "durationMs" to elapsedMs(),
            "dnsDurationMs" to durations["dns"],
            "dnsAddresses" to dnsAddresses,
            "connectAttemptCount" to connectAttemptCount,
            "connectDurationMs" to durations["connect"],
            "remoteAddresses" to remoteAddresses,
            "selectedRemoteAddress" to selectedRemoteAddress,
            "tlsDurationMs" to durations["tls"],
            "requestHeadersDurationMs" to durations["requestHeaders"],
            "requestBodyDurationMs" to durations["requestBody"],
            "responseHeadersDurationMs" to durations["responseHeaders"],
            "responseBodyDurationMs" to durations["responseBody"],
            "responseBodyBytes" to responseBodyBytes,
            "httpStatus" to responseCode,
            "failureClass" to failureClass,
            "failureMessage" to failureMessage,
            "failureCauseClass" to failureCauseClass,
            "failureCauseMessage" to failureCauseMessage,
            "events" to events.toList()
        )

        fun elapsedMs(): Long = (System.nanoTime() - startedNanos) / 1_000_000L

        private fun endDuration(name: String): Long? {
            val start = spanStarts.remove(name) ?: return null
            val durationMs = (System.nanoTime() - start) / 1_000_000L
            durations[name] = durationMs
            return durationMs
        }

        private fun captureFailure(error: IOException) {
            failureClass = error::class.java.name
            failureMessage = error.message
            failureCauseClass = error.cause?.javaClass?.name
            failureCauseMessage = error.cause?.message
        }

        private fun socketAddress(address: InetSocketAddress): String =
            "${address.hostString}/${address.address?.hostAddress ?: "unresolved"}:${address.port}"

        private fun proxyFields(proxy: Proxy): Map<String, Any?> {
            val address = proxy.address() as? InetSocketAddress
            return mapOf(
                "type" to proxy.type().name,
                "host" to address?.hostString,
                "port" to address?.port
            )
        }
    }

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
