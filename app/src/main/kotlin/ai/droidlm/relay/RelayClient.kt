package ai.droidlm.relay

import ai.droidlm.intent.AnchorPosition
import ai.droidlm.intent.DroidLmAction
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
    val maxSteps: Int
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
        val json = JSONObject()
            .put("goal", requestBody.goal)
            .put("uiState", requestBody.uiState?.toJson() ?: JSONObject())
            .put("packages", JSONArray(requestBody.packages.map { it.toJson() }))
            .put("history", JSONArray(requestBody.history))
            .put("maxSteps", requestBody.maxSteps)
        val request = Request.Builder()
            .url("$normalized/plan-action")
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, ::parsePlanActionJson)
    }

    suspend fun analyzeScreenshot(
        baseUrl: String,
        imageFile: File,
        goal: String,
        uiStateJson: String? = null
    ): RelayCallResult<VisionAnalysis> {
        val normalized = normalizeBaseUrl(baseUrl) ?: return RelayCallResult.Failure("Relay URL is not configured", "NO_RELAY_URL")
        if (!imageFile.exists() || imageFile.length() <= 0) return RelayCallResult.Failure("Screenshot file is empty", "EMPTY_SCREENSHOT")
        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", imageFile.name, imageFile.asRequestBody("image/png".toMediaType()))
            .addFormDataPart("goal", goal)
        uiStateJson?.let { bodyBuilder.addFormDataPart("uiState", it) }
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

    fun parsePlanActionJson(json: String): DroidLmAction {
        val obj = JSONObject(json)
        return when (obj.optString("action").uppercase()) {
            "OPEN_APP" -> DroidLmAction.OpenApp(
                appName = obj.optString("appName").takeIf { it.isNotBlank() },
                packageName = obj.getString("packageName"),
                reason = obj.optString("reason", "Open app")
            )
            "OPEN_SETTINGS" -> DroidLmAction.OpenSettings(obj.optString("reason", "Open settings"))
            "TAP" -> DroidLmAction.Tap(obj.getInt("x"), obj.getInt("y"), obj.optString("reason", "Tap"))
            "LONG_PRESS" -> DroidLmAction.LongPress(obj.getInt("x"), obj.getInt("y"), obj.optInt("durationMs", 600), obj.optString("reason", "Long press"))
            "SWIPE" -> DroidLmAction.Swipe(
                obj.getInt("startX"), obj.getInt("startY"), obj.getInt("endX"), obj.getInt("endY"), obj.optInt("durationMs", 400), obj.optString("reason", "Swipe")
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
                            continuation.resume(RelayCallResult.Failure("Relay returned HTTP ${it.code}: $body", "HTTP_${it.code}"))
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

    private fun normalizeBaseUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    private fun PortalState.toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("activityName", activityName)
        .put("screenWidth", screenWidth)
        .put("screenHeight", screenHeight)
        .put("nodes", JSONArray(nodes.take(80).map { node ->
            JSONObject()
                .put("text", node.text)
                .put("contentDescription", node.contentDescription)
                .put("className", node.className)
                .put("packageName", node.packageName)
                .put("clickable", node.clickable)
                .put("editable", node.editable)
                .put("focused", node.focused)
                .put("enabled", node.enabled)
        }))

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

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
