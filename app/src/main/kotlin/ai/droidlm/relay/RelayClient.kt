package ai.droidlm.relay

import ai.droidlm.context.UiContextJson
import ai.droidlm.intent.AnchorPosition
import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.ImeActionType
import ai.droidlm.intent.MenuType
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.ocr.OcrElement
import ai.droidlm.ocr.OcrLine
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalState
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class TranscriptionResponse(val text: String, val durationMs: Long? = null)

data class RelayPlanRequest(
    val goal: String,
    val uiState: PortalState?,
    val packages: List<AppPackage>,
    val history: List<String>,
    val maxSteps: Int,
    val activeApp: ActiveApp? = null,
    val deviceContext: DeviceContext? = null
)

data class ActiveApp(
    val packageName: String,
    val activityName: String?,
    val label: String?
)

data class DeviceContext(
    val activeApp: ActiveApp?,
    val packages: List<AppPackage>,
    val schemaVersion: Int = 1,
    val extras: JSONObject = JSONObject()
)

data class PlannerStatus(
    val openAiKeyConfigured: Boolean,
    val plannerModel: String,
    val latestNanoModel: String?,
    val relayReady: Boolean
)

data class PlanPreviewStep(
    val index: Int,
    val action: DroidLmAction,
    val actionLabel: String,
    val reason: String,
    val requiresConfirmation: Boolean
)

data class PlanPreview(
    val model: String,
    val summary: String,
    val riskLevel: String,
    val requiresConfirmation: Boolean,
    val steps: List<PlanPreviewStep>
) {
    val isSafe: Boolean
        get() = riskLevel.equals("LOW", ignoreCase = true) && !requiresConfirmation && steps.none { it.requiresConfirmation }
}

data class OpenAiKeySetupResponse(
    val ok: Boolean,
    val openAiKeyConfigured: Boolean
)

data class VisionBoundingBox(val x: Int, val y: Int, val width: Int, val height: Int)

data class VisionSuggestedAction(
    val type: String,
    val x: Int?,
    val y: Int?,
    val confidence: Double?,
    val reason: String?
)

data class VisionAnalysis(
    val fullText: String,
    val suggestedAction: VisionSuggestedAction?,
    val lines: List<OcrLine>,
    val elements: List<OcrElement>
)

sealed class RelayCallResult<out T> {
    data class Success<T>(val value: T) : RelayCallResult<T>()
    data class Failure(val message: String, val errorCode: String? = null, val cause: Throwable? = null) : RelayCallResult<Nothing>()
}

class RelayClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    suspend fun health(baseUrl: String): RelayCallResult<Boolean> {
        val normalized = normalizeBaseUrl(baseUrl) ?: return RelayCallResult.Failure("Relay URL is not configured", "NO_RELAY_URL")
        val request = Request.Builder().url("$normalized/health").get().build()
        return execute(request) { body -> JSONObject(body).optBoolean("ok", false) }
    }

    suspend fun plannerStatus(baseUrl: String): RelayCallResult<PlannerStatus> {
        val normalized = normalizeBaseUrl(baseUrl) ?: return RelayCallResult.Failure("Relay URL is not configured", "NO_RELAY_URL")
        val request = Request.Builder().url("$normalized/planner/status").get().build()
        return execute(request, ::parsePlannerStatusJson)
    }

    suspend fun saveOpenAiKey(baseUrl: String, setupToken: String, apiKey: String): RelayCallResult<OpenAiKeySetupResponse> {
        val normalized = normalizeBaseUrl(baseUrl) ?: return RelayCallResult.Failure("Relay URL is not configured", "NO_RELAY_URL")
        val json = JSONObject()
            .put("setupToken", setupToken)
            .put("openAiApiKey", apiKey)
        val request = Request.Builder()
            .url("$normalized/setup/openai-key")
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, ::parseOpenAiKeySetupJson)
    }

    suspend fun deleteOpenAiKey(baseUrl: String, setupToken: String): RelayCallResult<OpenAiKeySetupResponse> {
        val normalized = normalizeBaseUrl(baseUrl) ?: return RelayCallResult.Failure("Relay URL is not configured", "NO_RELAY_URL")
        val json = JSONObject().put("setupToken", setupToken)
        val request = Request.Builder()
            .url("$normalized/setup/openai-key")
            .delete(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, ::parseOpenAiKeySetupJson)
    }

    suspend fun transcribe(
        baseUrl: String,
        audioFile: File,
        mimeType: String,
        language: String? = null,
        modelHint: String? = null
    ): RelayCallResult<TranscriptionResponse> {
        val normalized = normalizeBaseUrl(baseUrl) ?: return RelayCallResult.Failure("Relay URL is not configured", "NO_RELAY_URL")
        if (!audioFile.exists() || audioFile.length() <= 0) return RelayCallResult.Failure("Audio file is empty", "EMPTY_AUDIO")
        val mediaType = mimeType.toMediaType()
        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", audioFile.name, audioFile.asRequestBody(mediaType))
        language?.takeIf { it.isNotBlank() }?.let { bodyBuilder.addFormDataPart("language", it) }
        modelHint?.takeIf { it.isNotBlank() }?.let { bodyBuilder.addFormDataPart("modelHint", it) }
        val request = Request.Builder()
            .url("$normalized/transcribe")
            .post(bodyBuilder.build())
            .build()
        return execute(request, ::parseTranscriptionJson)
    }

    suspend fun planAction(baseUrl: String, requestBody: RelayPlanRequest): RelayCallResult<DroidLmAction> {
        val normalized = normalizeBaseUrl(baseUrl) ?: return RelayCallResult.Failure("Relay URL is not configured", "NO_RELAY_URL")
        val request = Request.Builder()
            .url("$normalized/plan-action")
            .post(requestBody.toJson().toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, ::parsePlanActionJson)
    }

    suspend fun planPreview(baseUrl: String, requestBody: RelayPlanRequest): RelayCallResult<PlanPreview> {
        val normalized = normalizeBaseUrl(baseUrl) ?: return RelayCallResult.Failure("Relay URL is not configured", "NO_RELAY_URL")
        val request = Request.Builder()
            .url("$normalized/plan-preview")
            .post(requestBody.toJson().toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, ::parsePlanPreviewJson)
    }

    suspend fun analyzeScreenshot(
        baseUrl: String,
        imageFile: File,
        goal: String,
        uiStateJson: String? = null,
        deviceContext: DeviceContext? = null
    ): RelayCallResult<VisionAnalysis> {
        val normalized = normalizeBaseUrl(baseUrl) ?: return RelayCallResult.Failure("Relay URL is not configured", "NO_RELAY_URL")
        if (!imageFile.exists() || imageFile.length() <= 0) return RelayCallResult.Failure("Screenshot file is empty", "EMPTY_SCREENSHOT")
        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", imageFile.name, imageFile.asRequestBody("image/png".toMediaType()))
            .addFormDataPart("goal", goal)
        uiStateJson?.let { bodyBuilder.addFormDataPart("uiState", it) }
        deviceContext?.let { bodyBuilder.addFormDataPart("deviceContext", it.toJson().toString()) }
        val request = Request.Builder()
            .url("$normalized/analyze-screenshot")
            .post(bodyBuilder.build())
            .build()
        return execute(request, ::parseVisionAnalysisJson)
    }

    fun parseTranscriptionJson(json: String): TranscriptionResponse {
        val obj = JSONObject(json)
        val text = obj.optString("text").trim()
        if (text.isBlank()) throw JSONException("Transcription text was empty")
        val duration = if (obj.has("durationMs")) obj.optLong("durationMs") else null
        return TranscriptionResponse(text, duration)
    }

    fun parsePlannerStatusJson(json: String): PlannerStatus {
        val obj = JSONObject(json)
        return PlannerStatus(
            openAiKeyConfigured = obj.optBoolean("openAiKeyConfigured", false),
            plannerModel = obj.optString("plannerModel", "gpt-5.4-nano"),
            latestNanoModel = obj.optString("latestNanoModel").takeIf { it.isNotBlank() },
            relayReady = obj.optBoolean("relayReady", false)
        )
    }

    fun parseOpenAiKeySetupJson(json: String): OpenAiKeySetupResponse {
        val obj = JSONObject(json)
        return OpenAiKeySetupResponse(
            ok = obj.optBoolean("ok", false),
            openAiKeyConfigured = obj.optBoolean("openAiKeyConfigured", false)
        )
    }

    fun parsePlanPreviewJson(json: String): PlanPreview {
        val obj = JSONObject(json)
        val stepsArray = obj.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsArray.length()).mapNotNull { index ->
            stepsArray.optJSONObject(index)?.let { step ->
                PlanPreviewStep(
                    index = step.optInt("index", index + 1),
                    action = parsePlanActionJson(step.toString()),
                    actionLabel = step.optString("action", "NO_OP"),
                    reason = step.optString("reason", ""),
                    requiresConfirmation = step.optBoolean("requiresConfirmation", false)
                )
            }
        }
        return PlanPreview(
            model = obj.optString("model", "gpt-5.4-nano"),
            summary = obj.optString("summary", ""),
            riskLevel = obj.optString("riskLevel", "MEDIUM"),
            requiresConfirmation = obj.optBoolean("requiresConfirmation", false),
            steps = steps
        )
    }

    fun parsePlanActionJson(json: String): DroidLmAction {
        val obj = coerceActionObject(JSONObject(json))
        return when (obj.optString("action").uppercase()) {
            "OPEN_APP" -> DroidLmAction.OpenApp(
                appName = obj.optString("appName").takeIf { it.isNotBlank() },
                packageName = obj.getString("packageName"),
                reason = obj.optString("reason", "Open app")
            )
            "OPEN_SETTINGS" -> DroidLmAction.OpenSettings(obj.optString("reason", "Open settings"))
            "TAP" -> DroidLmAction.Tap(obj.requireInt("x", "TAP"), obj.requireInt("y", "TAP"), obj.optString("reason", "Tap"))
            "TAP_NODE" -> DroidLmAction.TapNode(obj.requireString("nodeId", "TAP_NODE"), obj.optString("reason", "Tap node"))
            "FOCUS_NODE" -> DroidLmAction.FocusNode(obj.requireString("nodeId", "FOCUS_NODE"), obj.optString("reason", "Focus node"))
            "LONG_PRESS" -> DroidLmAction.LongPress(obj.requireInt("x", "LONG_PRESS"), obj.requireInt("y", "LONG_PRESS"), obj.optInt("durationMs", 600), obj.optString("reason", "Long press"))
            "SWIPE" -> DroidLmAction.Swipe(
                obj.requireInt("startX", "SWIPE"), obj.requireInt("startY", "SWIPE"), obj.requireInt("endX", "SWIPE"), obj.requireInt("endY", "SWIPE"), obj.optInt("durationMs", 400), obj.optString("reason", "Swipe")
            )
            "SCROLL", "SCROLL_UP", "SCROLL_DOWN", "SCROLL_LEFT", "SCROLL_RIGHT" -> DroidLmAction.Scroll(
                direction = parseScrollDirection(obj.optString("direction").takeIf { it.isNotBlank() } ?: obj.optString("action")),
                targetNodeId = obj.optString("targetNodeId").takeIf { it.isNotBlank() },
                amount = obj.optString("amount").takeIf { it.isNotBlank() },
                untilText = obj.optString("untilText").takeIf { it.isNotBlank() },
                reason = obj.optString("reason", "Scroll")
            )
            "TAP_TEXT", "SELECT_ITEM" -> DroidLmAction.TapText(
                text = obj.requireStringAny("TAP_TEXT", "text", "label", "itemText", "targetText", "buttonText"),
                role = obj.optString("role").takeIf { it.isNotBlank() },
                containerNodeId = obj.optString("containerNodeId").takeIf { it.isNotBlank() },
                reason = obj.optString("reason", "Tap visible text")
            )
            "LONG_PRESS_NODE", "LONG_CLICK" -> DroidLmAction.LongPressNode(
                nodeId = obj.optString("nodeId").takeIf { it.isNotBlank() },
                text = obj.optFirstNonBlank("text", "label", "itemText", "targetText"),
                durationMs = obj.optInt("durationMs", 600),
                reason = obj.optString("reason", "Long press visible item")
            )
            "WAIT_FOR_UI" -> DroidLmAction.WaitForUi(
                text = obj.optString("text").takeIf { it.isNotBlank() },
                packageName = obj.optString("packageName").takeIf { it.isNotBlank() },
                nodeId = obj.optString("nodeId").takeIf { it.isNotBlank() },
                timeoutMs = obj.optInt("timeoutMs", 2_500),
                reason = obj.optString("reason", "Wait for the screen to update")
            )
            "PRESS_IME_ACTION" -> DroidLmAction.PressImeAction(
                action = parseImeActionType(obj.optString("imeAction").ifBlank { obj.optString("actionType") }),
                reason = obj.optString("reason", "Press the keyboard action")
            )
            "DIALOG_ACTION" -> DroidLmAction.DialogAction(
                buttonText = obj.optString("buttonText").takeIf { it.isNotBlank() },
                role = obj.optString("role").takeIf { it.isNotBlank() }?.let(::parseDialogButtonRole),
                reason = obj.optString("reason", "Respond to the dialog")
            )
            "OPEN_MENU" -> DroidLmAction.OpenMenu(
                menu = parseMenuType(obj.optString("menu").ifBlank { obj.optString("menuType") }),
                reason = obj.optString("reason", "Open the menu")
            )
            "SELECT_TAB" -> DroidLmAction.SelectTab(
                label = obj.requireStringAny("SELECT_TAB", "label", "text", "tabLabel"),
                reason = obj.optString("reason", "Select the tab")
            )
            "SET_TOGGLE" -> DroidLmAction.SetToggle(
                label = obj.optString("label").takeIf { it.isNotBlank() } ?: obj.optString("text").takeIf { it.isNotBlank() },
                nodeId = obj.optString("nodeId").takeIf { it.isNotBlank() },
                value = obj.optBooleanOrNull("value") ?: obj.optBooleanOrNull("enabled") ?: throw JSONException("SET_TOGGLE requires value"),
                reason = obj.optString("reason", "Set the toggle")
            )
            "EXPAND_COLLAPSE" -> DroidLmAction.ExpandCollapse(
                label = obj.optString("label").takeIf { it.isNotBlank() } ?: obj.optString("text").takeIf { it.isNotBlank() },
                nodeId = obj.optString("nodeId").takeIf { it.isNotBlank() },
                expanded = obj.optBooleanOrNull("expanded") ?: obj.optString("state").equals("expanded", ignoreCase = true),
                reason = obj.optString("reason", "Expand or collapse the section")
            )
            "SET_SLIDER" -> DroidLmAction.SetSlider(
                label = obj.optString("label").takeIf { it.isNotBlank() } ?: obj.optString("text").takeIf { it.isNotBlank() },
                nodeId = obj.optString("nodeId").takeIf { it.isNotBlank() },
                value = obj.optDoubleOrNull("value")?.toFloat(),
                percent = obj.optIntOrNull("percent"),
                reason = obj.optString("reason", "Adjust the slider")
            )
            "REFRESH" -> DroidLmAction.Refresh(
                targetNodeId = obj.optString("targetNodeId").takeIf { it.isNotBlank() },
                reason = obj.optString("reason", "Refresh the current screen")
            )
            "FIND_TEXT_ON_SCREEN" -> DroidLmAction.FindTextOnScreen(
                text = obj.requireStringAny("FIND_TEXT_ON_SCREEN", "text", "targetText", "label"),
                tapOnMatch = obj.optBoolean("tapOnMatch", false),
                reason = obj.optString("reason", "Find text on screen")
            )
            "OPEN_NOTIFICATIONS" -> DroidLmAction.OpenNotifications
            "OPEN_QUICK_SETTINGS" -> DroidLmAction.OpenQuickSettings
            "OPEN_RECENTS" -> DroidLmAction.OpenRecents
            "SWITCH_APP" -> DroidLmAction.SwitchApp(
                appName = obj.optString("appName").takeIf { it.isNotBlank() },
                packageName = obj.optString("packageName").takeIf { it.isNotBlank() },
                reason = obj.optString("reason", "Switch apps")
            )
            "OPEN_URL" -> DroidLmAction.OpenUrl(
                url = obj.requireStringAny("OPEN_URL", "url", "uri"),
                reason = obj.optString("reason", "Open a URL")
            )
            "OPEN_DEEP_LINK" -> DroidLmAction.OpenDeepLink(
                uri = obj.requireStringAny("OPEN_DEEP_LINK", "uri", "url", "deepLink"),
                reason = obj.optString("reason", "Open the app link")
            )
            "PICK_FROM_CHOOSER" -> DroidLmAction.PickFromChooser(
                itemText = obj.requireStringAny("PICK_FROM_CHOOSER", "itemText", "label", "text", "choice"),
                reason = obj.optString("reason", "Pick an item from the chooser")
            )
            "PICK_FILE" -> DroidLmAction.PickFile(
                fileName = obj.requireStringAny("PICK_FILE", "fileName", "label", "text"),
                reason = obj.optString("reason", "Pick a file")
            )
            "PICK_PHOTO" -> DroidLmAction.PickPhoto(
                photoLabel = obj.requireStringAny("PICK_PHOTO", "photoLabel", "label", "text"),
                reason = obj.optString("reason", "Pick a photo")
            )
            "SHARE_TO_APP" -> DroidLmAction.ShareToApp(
                appName = obj.optString("appName").takeIf { it.isNotBlank() },
                packageName = obj.optString("packageName").takeIf { it.isNotBlank() },
                reason = obj.optString("reason", "Share to an app")
            )
            "PERMISSION_DECISION" -> DroidLmAction.PermissionDecision(
                allow = parsePermissionAllow(obj),
                reason = obj.optString("reason", "Respond to the permission request")
            )
            "TYPE_TEXT" -> DroidLmAction.TypeText(obj.optString("text"), clear = obj.optBoolean("clear", false), reason = obj.optString("reason", "Type text"))
            "GLOBAL_BACK" -> DroidLmAction.PressBack
            "GLOBAL_HOME" -> DroidLmAction.PressHome
            "TAKE_SCREENSHOT" -> DroidLmAction.TakeScreenshot
            "FOCUS_EDITABLE" -> DroidLmAction.FocusEditable(obj.optString("nodeId").takeIf { it.isNotBlank() }, obj.optString("reason", "Focus editable"))
            "SET_SELECTION" -> DroidLmAction.SetSelection(obj.optString("nodeId").takeIf { it.isNotBlank() }, obj.getInt("start"), obj.getInt("end"), obj.optString("reason", "Set selection"))
            "INSERT_TEXT" -> DroidLmAction.InsertText(obj.optString("text"), obj.optString("reason", "Insert text"))
            "REPLACE_SELECTION" -> DroidLmAction.ReplaceSelection(obj.optString("text"), obj.optString("reason", "Replace selection"))
            "SET_FULL_TEXT" -> DroidLmAction.SetFullText(obj.optString("nodeId").takeIf { it.isNotBlank() }, obj.optString("text"), obj.optString("reason", "Set full text"))
            "MOVE_CURSOR" -> DroidLmAction.MoveCursor(obj.optString("targetDescription"), obj.optString("reason", "Move cursor"))
            "TAP_TEXT_ANCHOR" -> DroidLmAction.TapTextAnchor(obj.optString("anchorText"), parseAnchorPosition(obj.optString("anchorPosition")), obj.optString("reason", "Tap text anchor"))
            "OCR_SCREEN" -> DroidLmAction.OcrScreen
            "ANALYZE_SCREENSHOT" -> DroidLmAction.AnalyzeScreenshot(obj.optString("goal"), obj.optString("reason", "Analyze screenshot"))
            "INSERT_TEXT_AT_ANCHOR" -> DroidLmAction.InsertTextAtAnchor(
                obj.optString("anchorText"), parseAnchorPosition(obj.optString("anchorPosition")), obj.optString("text"), obj.optString("reason", "Insert text at anchor")
            )
            "REPLACE_TEXT_RANGE" -> DroidLmAction.ReplaceTextRange(obj.optString("targetText"), obj.optString("replacementText"), obj.optString("reason", "Replace text range"))
            "APPEND_TEXT" -> DroidLmAction.AppendText(obj.optString("text"), obj.optString("reason", "Append text"))
            "PREPEND_TEXT" -> DroidLmAction.PrependText(obj.optString("text"), obj.optString("reason", "Prepend text"))
            "SELECT_ALL" -> DroidLmAction.SelectAll
            "DELETE_SELECTED_TEXT" -> DroidLmAction.DeleteSelectedText
            "VERIFY_TEXT_CHANGE" -> DroidLmAction.VerifyTextChange(obj.optString("expectedText"), obj.optString("reason", "Verify text change"))
            "FORMAT_CURRENT_LINE_AS_BULLET" -> DroidLmAction.FormatCurrentLineAsBullet(
                fileUri = obj.optFileUri(),
                bulletPrefix = obj.optString("bulletPrefix", "- "),
                reason = obj.optString("reason", "Add bullet point to current line")
            )
            "REPLACE_CURRENT_DOCUMENT_TEXT" -> DroidLmAction.ReplaceDocumentText(
                targetText = obj.optString("targetText"),
                replacementText = obj.optString("replacementText"),
                fileUri = obj.optFileUri(),
                reason = obj.optString("reason", "Replace document text")
            )
            "APPEND_DOCUMENT_NOTE" -> DroidLmAction.AppendDocumentNote(
                note = obj.optString("note", obj.optString("text")),
                fileUri = obj.optFileUri(),
                reason = obj.optString("reason", "Append document note")
            )
            "SET_CURRENT_SHEET_CELL" -> DroidLmAction.SetCurrentSheetCell(
                value = obj.optString("value", obj.optString("text")),
                fileUri = obj.optFileUri(),
                reason = obj.optString("reason", "Set current sheet cell")
            )
            "ADD_SPREADSHEET_ROW" -> DroidLmAction.AddSpreadsheetRow(
                values = obj.optStringArray("values").ifEmpty {
                    obj.optString("row").split(',').map { value -> value.trim() }.filter { value -> value.isNotBlank() }
                },
                fileUri = obj.optFileUri(),
                reason = obj.optString("reason", "Add spreadsheet row")
            )
            "ASK_CONFIRMATION" -> DroidLmAction.AskConfirmation(
                reason = obj.optString("reason", "Confirmation required"),
                confirmationPrompt = obj.optString("confirmationPrompt", "Confirm this action?")
            )
            "DONE" -> DroidLmAction.Done
            "NO_OP" -> DroidLmAction.NoOp(obj.optString("message", obj.optString("reason", "No operation")))
            else -> DroidLmAction.NeedLlmPlanning("Relay returned unsupported action: ${obj.optString("action")}")
        }
    }

    fun parseVisionAnalysisJson(json: String): VisionAnalysis {
        val obj = JSONObject(json)
        val suggested = obj.optJSONObject("suggestedAction")?.let {
            VisionSuggestedAction(
                type = it.optString("type"),
                x = it.optIntOrNull("x"),
                y = it.optIntOrNull("y"),
                confidence = it.optDoubleOrNull("confidence"),
                reason = it.optString("reason").takeIf { value -> value.isNotBlank() }
            )
        }
        return VisionAnalysis(
            fullText = obj.optString("fullText"),
            suggestedAction = suggested,
            lines = parseOcrLines(obj.optJSONArray("lines")),
            elements = parseOcrElements(obj.optJSONArray("elements"))
        )
    }

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
                            val relayError = parseRelayError(body)
                            continuation.resume(
                                RelayCallResult.Failure(
                                    relayError.first ?: "Relay returned HTTP ${it.code}: $body",
                                    relayError.second ?: "HTTP_${it.code}"
                                )
                            )
                            return
                        }
                        runCatching { parser(body) }
                            .fold(
                                onSuccess = { value -> continuation.resume(RelayCallResult.Success(value)) },
                                onFailure = { error -> continuation.resume(RelayCallResult.Failure("Invalid relay JSON: ${error.message}", "INVALID_JSON", error)) }
                            )
                    }
                }
            })
        }
    }

    private fun parseRelayError(body: String): Pair<String?, String?> {
        return runCatching {
            val obj = JSONObject(body)
            val detail = obj.opt("detail")
            val detailObj = detail as? JSONObject
            val code = detailObj?.optString("errorCode")?.takeIf { it.isNotBlank() }
                ?: obj.optString("errorCode").takeIf { it.isNotBlank() }
            val message = detailObj?.optString("message")?.takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: obj.optString("detail").takeIf { it.isNotBlank() }
            message to code
        }.getOrDefault(null to null)
    }

    private fun normalizeBaseUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    private fun RelayPlanRequest.toJson(): JSONObject = JSONObject()
        .put("goal", goal)
        .put("uiState", uiState?.toJson() ?: JSONObject())
        .put("packages", JSONArray(packages.map { it.toJson() }))
        .put("activeApp", activeApp?.toJson() ?: JSONObject())
        .put("deviceContext", deviceContext?.toJson() ?: JSONObject())
        .put("history", JSONArray(history))
        .put("maxSteps", maxSteps)

    private fun DeviceContext.toJson(): JSONObject {
        val json = JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("activeApp", activeApp?.toJson() ?: JSONObject())
            .put("installedApps", JSONArray(packages.map { it.toJson() }))
            .put("packages", JSONArray(packages.map { it.toJson() }))
        extras.keys().forEach { key -> json.put(key, extras.opt(key)) }
        return json
    }

    private fun ActiveApp.toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("activityName", activityName)
        .put("label", label)

    private fun PortalState.toJson(): JSONObject = UiContextJson.portalStateToJson(this)

    private fun AppPackage.toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("label", label)
        .put("isSystemApp", isSystemApp)

    private fun parseAnchorPosition(value: String): AnchorPosition =
        if (value.equals("BEFORE", ignoreCase = true)) AnchorPosition.BEFORE else AnchorPosition.AFTER

    private fun parseOcrLines(array: JSONArray?): List<OcrLine> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { obj -> OcrLine(obj.optString("text"), obj.optBoundingRect(), emptyList(), null) }
        }
    }

    private fun parseOcrElements(array: JSONArray?): List<OcrElement> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { obj -> OcrElement(obj.optString("text"), obj.optBoundingRect(), emptyList(), null) }
        }
    }

    private fun JSONObject.optBoundingRect(): Rect? {
        val box = optJSONObject("boundingBox") ?: return null
        val x = box.optInt("x")
        val y = box.optInt("y")
        return Rect(x, y, x + box.optInt("width"), y + box.optInt("height"))
    }

    private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
    private fun JSONObject.optDoubleOrNull(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name) else null
    private fun JSONObject.optFileUri(): String? =
        listOf("fileUri", "filePath", "documentUri", "uri")
            .firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() } }

    private fun JSONObject.requireString(name: String, action: String): String {
        return optString(name).takeIf { it.isNotBlank() }
            ?: throw org.json.JSONException("$action requires $name")
    }

    private fun JSONObject.requireStringAny(action: String, vararg names: String): String {
        return names.firstNotNullOfOrNull { name -> optString(name).takeIf { it.isNotBlank() } }
            ?: throw org.json.JSONException("$action requires ${names.firstOrNull() ?: "value"}")
    }

    private fun JSONObject.requireInt(name: String, action: String): Int {
        if (!has(name) || isNull(name)) throw org.json.JSONException("$action requires $name")
        return getInt(name)
    }


    private fun JSONObject.optStringArray(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    }

    private fun JSONObject.optFirstNonBlank(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> optString(name).takeIf { it.isNotBlank() } }

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
        if (has(name) && !isNull(name)) getBoolean(name) else null

    private fun coerceActionObject(obj: JSONObject): JSONObject {
        val repaired = JSONObject(obj.toString())
        val action = repaired.optString("action").uppercase()
        if (action == "SWIPE" && (!repaired.has("startX") || !repaired.has("startY") || !repaired.has("endX") || !repaired.has("endY"))) {
            inferScrollDirection(repaired)?.let { direction ->
                repaired.put("action", "SCROLL")
                repaired.put("direction", direction.name)
            }
        }
        if ((action == "LONG_PRESS" || action == "LONG_CLICK") && (!repaired.has("x") || !repaired.has("y"))) {
            if (!repaired.optFirstNonBlank("nodeId", "text", "label", "itemText").isNullOrBlank()) {
                repaired.put("action", "LONG_PRESS_NODE")
            }
        }
        if ((action == "TAP" || action == "TAP_NODE") && !repaired.has("nodeId")) {
            repaired.optFirstNonBlank("text", "label", "itemText", "targetText", "buttonText")?.let { text ->
                repaired.put("action", "TAP_TEXT")
                repaired.put("text", text)
            }
        }
        return repaired
    }

    private fun inferScrollDirection(obj: JSONObject): ScrollDirection? {
        parseScrollDirectionOrNull(obj.optString("direction"))?.let { return it }
        return when {
            obj.optString("action").contains("UP", ignoreCase = true) -> ScrollDirection.UP
            obj.optString("action").contains("DOWN", ignoreCase = true) -> ScrollDirection.DOWN
            obj.optString("action").contains("LEFT", ignoreCase = true) -> ScrollDirection.LEFT
            obj.optString("action").contains("RIGHT", ignoreCase = true) -> ScrollDirection.RIGHT
            else -> listOfNotNull(
                obj.optFirstNonBlank("reason", "summary", "message", "text", "goal")
            ).joinToString(" ").let { hint ->
                when {
                    hint.contains("scroll up", ignoreCase = true) -> ScrollDirection.UP
                    hint.contains("scroll down", ignoreCase = true) -> ScrollDirection.DOWN
                    hint.contains("scroll left", ignoreCase = true) -> ScrollDirection.LEFT
                    hint.contains("scroll right", ignoreCase = true) -> ScrollDirection.RIGHT
                    else -> null
                }
            }
        }
    }

    private fun parseScrollDirection(value: String): ScrollDirection =
        parseScrollDirectionOrNull(value) ?: throw JSONException("SCROLL requires direction")

    private fun parseScrollDirectionOrNull(value: String?): ScrollDirection? = when (value?.trim()?.uppercase()) {
        "UP", "SCROLL_UP" -> ScrollDirection.UP
        "DOWN", "SCROLL_DOWN" -> ScrollDirection.DOWN
        "LEFT", "SCROLL_LEFT" -> ScrollDirection.LEFT
        "RIGHT", "SCROLL_RIGHT" -> ScrollDirection.RIGHT
        else -> null
    }

    private fun parseImeActionType(value: String?): ImeActionType = when (value?.trim()?.uppercase()) {
        "ENTER" -> ImeActionType.ENTER
        "SEARCH" -> ImeActionType.SEARCH
        "DONE" -> ImeActionType.DONE
        "SEND" -> ImeActionType.SEND
        "NEXT" -> ImeActionType.NEXT
        "GO" -> ImeActionType.GO
        else -> ImeActionType.DEFAULT
    }

    private fun parseDialogButtonRole(value: String): DialogButtonRole = when (value.trim().uppercase()) {
        "POSITIVE", "ALLOW", "CONFIRM" -> DialogButtonRole.POSITIVE
        "NEGATIVE", "DENY", "CANCEL" -> DialogButtonRole.NEGATIVE
        "NEUTRAL" -> DialogButtonRole.NEUTRAL
        "DISMISS", "CLOSE" -> DialogButtonRole.DISMISS
        else -> throw JSONException("Unsupported dialog role: $value")
    }

    private fun parseMenuType(value: String?): MenuType = when (value?.trim()?.uppercase()) {
        "NAVIGATION_DRAWER", "DRAWER" -> MenuType.NAVIGATION_DRAWER
        "CONTEXT", "CONTEXT_MENU" -> MenuType.CONTEXT
        else -> MenuType.OVERFLOW
    }

    private fun parsePermissionAllow(obj: JSONObject): Boolean {
        obj.optBooleanOrNull("allow")?.let { return it }
        return when (obj.optString("decision").trim().lowercase()) {
            "allow", "grant", "approve", "yes" -> true
            "deny", "reject", "block", "no" -> false
            else -> throw JSONException("PERMISSION_DECISION requires allow or decision")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
