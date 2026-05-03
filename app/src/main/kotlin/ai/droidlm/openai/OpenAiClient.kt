package ai.droidlm.openai

import ai.droidlm.context.UiContextJson
import ai.droidlm.intent.DroidLmAction

import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import ai.droidlm.relay.PlanPreview
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.relay.RelayClient
import ai.droidlm.relay.RelayPlanRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class OpenAiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = "https://api.openai.com/v1/chat/completions"
) {
    private val relayJsonParser = RelayClient()

    suspend fun planPreview(apiKey: String, model: String, requestBody: RelayPlanRequest): RelayCallResult<PlanPreview> {
        if (apiKey.isBlank()) return RelayCallResult.Failure("OpenAI API key is not configured on this device", "OPENAI_API_KEY_MISSING")
        val request = buildChatRequest(apiKey, model, planPreviewPrompt(requestBody), maxTokens = 1800)
        return execute(request) { body -> relayJsonParser.parsePlanPreviewJson(extractAssistantJson(body)) }
    }

    suspend fun planAction(apiKey: String, model: String, requestBody: RelayPlanRequest): RelayCallResult<DroidLmAction> {
        if (apiKey.isBlank()) return RelayCallResult.Failure("OpenAI API key is not configured on this device", "OPENAI_API_KEY_MISSING")
        val request = buildChatRequest(apiKey, model, planActionPrompt(requestBody), maxTokens = 900)
        return execute(request) { body -> relayJsonParser.parsePlanActionJson(extractAssistantJson(body)) }
    }

    private fun buildChatRequest(apiKey: String, model: String, prompt: String, maxTokens: Int): Request {
        val json = JSONObject()
            .put("model", model.ifBlank { DEFAULT_MODEL })
            .put("temperature", 0)
            .put("max_tokens", maxTokens)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", prompt))
            )
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
        Prefer TAP_NODE or FOCUS_NODE with nodeId for visible UI targets. Only use TAP, LONG_PRESS, or SWIPE when exact coordinates are present in UI state.
        Keep plans to the minimum safe number of steps.

        Goal: ${request.goal}
        Max steps: ${request.maxSteps}
        UI state: ${request.uiState?.toJson() ?: JSONObject()}
        Installed packages: ${JSONArray(request.packages.map { it.toJson() })}
        History: ${JSONArray(request.history)}
    """.trimIndent()

    private fun planActionPrompt(request: RelayPlanRequest): String = """
        Choose exactly one next Android automation action for this user goal.
        Return only one JSON action object. Do not wrap it in markdown.
        Supported actions and fields are the same as the plan preview prompt.
        Prefer TAP_NODE or FOCUS_NODE with nodeId for visible UI targets. Only use TAP, LONG_PRESS, or SWIPE when exact coordinates are present in UI state.
        If the task is complete, return {"action":"DONE","reason":"Task complete"}.
        If no useful action is possible, return {"action":"NO_OP","message":"brief reason"}.

        Goal: ${request.goal}
        Max steps: ${request.maxSteps}
        UI state: ${request.uiState?.toJson() ?: JSONObject()}
        Installed packages: ${JSONArray(request.packages.map { it.toJson() })}
        History: ${JSONArray(request.history)}
    """.trimIndent()

    private suspend fun <T> execute(request: Request, parser: (String) -> T): RelayCallResult<T> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) {
                        val code = if (e.message?.contains("timeout", ignoreCase = true) == true) "TIMEOUT" else "NETWORK_ERROR"
                        continuation.resume(RelayCallResult.Failure(e.message ?: "Network error", code, e))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            val error = parseOpenAiError(body)
                            continuation.resume(RelayCallResult.Failure(error.first ?: "OpenAI returned HTTP ${it.code}: $body", error.second ?: "HTTP_${it.code}"))
                            return
                        }
                        runCatching { parser(body) }
                            .fold(
                                onSuccess = { value -> continuation.resume(RelayCallResult.Success(value)) },
                                onFailure = { error -> continuation.resume(RelayCallResult.Failure("Invalid OpenAI JSON: ${error.message}", "INVALID_JSON", error)) }
                            )
                    }
                }
            })
        }
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
        .put("history", JSONArray(history))
        .put("maxSteps", maxSteps)

    private fun PortalState.toJson(): JSONObject = UiContextJson.portalStateToJson(this)

    private fun AppPackage.toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("label", label)
        .put("isSystemApp", isSystemApp)

    companion object {
        const val DEFAULT_MODEL = "gpt-4.1-mini"
        private const val SYSTEM_PROMPT = "You are DroidLM's Android automation planner. Return strict JSON only. Never include secrets. Prefer safe, minimal, reversible actions."
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
