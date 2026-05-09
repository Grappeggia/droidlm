package ai.droidlm.cloud

import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class MobilerunTaskEvent(
    val type: String,
    val message: String,
    val raw: String? = null
)

data class MobilerunTaskResult(
    val success: Boolean,
    val message: String
)

class MobilerunCloudClient(
    private val settingsRepository: SettingsRepository,
    private val logs: ActionLogRepository,
    private val diagnostics: SpeechDiagnosticsLogger? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(130, TimeUnit.SECONDS)
        .build()
) {
    fun runTask(task: String): Flow<MobilerunTaskEvent> = flow {
        val settings = settingsRepository.settings.first()
        val apiKey = settingsRepository.getMobilerunApiKey()
        if (apiKey.isNullOrBlank() || settings.mobilerunDeviceId.isBlank()) {
            diagnostics?.record(
                null,
                "mobilerun_task_not_configured",
                mapOf("apiKeyConfigured" to !apiKey.isNullOrBlank(), "deviceIdConfigured" to settings.mobilerunDeviceId.isNotBlank())
            )
            emit(MobilerunTaskEvent("error", "Mobilerun API key or device ID is missing"))
            return@flow
        }
        diagnostics?.record(
            null,
            "mobilerun_task_started",
            mapOf(
                "taskLength" to task.length,
                "deviceIdConfigured" to settings.mobilerunDeviceId.isNotBlank(),
                "maxSteps" to settings.maxAutonomousSteps,
                "endpointHost" to "api.mobilerun.ai"
            )
        )
        val body = JSONObject()
            .put("task", task)
            .put("deviceId", settings.mobilerunDeviceId)
            .put("maxSteps", settings.maxAutonomousSteps)
            .put("reasoning", true)
            .put("vision", true)
            .put("executionTimeout", 120)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("https://api.mobilerun.ai/v1/tasks/stream")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()
        val startedAt = System.currentTimeMillis()
        val result = executeRaw(request)
        diagnostics?.record(
            null,
            if (result.success) "mobilerun_task_stream_succeeded" else "mobilerun_task_stream_failed",
            mapOf(
                "durationMs" to (System.currentTimeMillis() - startedAt),
                "responseBytes" to result.message.toByteArray(Charsets.UTF_8).size,
                "eventLineCount" to result.message.lineSequence().count { it.startsWith("data:") },
                "messageLength" to result.message.length
            )
        )
        if (result.success) {
            result.message.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .filter { it.isNotBlank() && it != "[DONE]" }
                .forEach { emit(MobilerunTaskEvent("event", it, it)) }
        } else {
            emit(MobilerunTaskEvent("error", result.message))
        }
    }

    suspend fun runTaskNonStreaming(task: String): MobilerunTaskResult {
        val last = mutableListOf<MobilerunTaskEvent>()
        runTask(task).collect { event ->
            logs.log(ActionLogType.ACTION_RESULT, event.message)
            last += event
        }
        val failed = last.firstOrNull { it.type == "error" }
        return if (failed != null) MobilerunTaskResult(false, failed.message) else MobilerunTaskResult(true, last.lastOrNull()?.message ?: "Mobilerun task completed")
    }

    private suspend fun executeRaw(request: Request): MobilerunTaskResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(MobilerunTaskResult(false, e.message ?: "Mobilerun network error"))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        continuation.resume(
                            if (it.isSuccessful) MobilerunTaskResult(true, text)
                            else MobilerunTaskResult(false, "Mobilerun HTTP ${it.code}: $text")
                        )
                    }
                }
            })
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
