package ai.droidlm.voice

import ai.droidlm.logs.ActionLogType
import ai.droidlm.portal.ActionResult
import ai.droidlm.runtime.OverlayNoticeKind
import kotlinx.coroutines.flow.first

internal class PushToTalkSessionRunner(
    private val deps: WakeWordForegroundServiceDeps,
    private val serviceLifecycleFields: (Map<String, Any?>) -> Map<String, Any?>
) {
    suspend fun run(sessionId: String) {
        runCatching {
            deps.overlayRuntime.clearNotice()
            val settings = deps.settingsRepository.settings.first()
            deps.speechDiagnosticsLogger.record(
                sessionId,
                "push_to_talk_settings_loaded",
                serviceLifecycleFields(mapOf("preferOfflineSpeechRecognition" to settings.preferOfflineSpeechRecognition))
            )
            deps.executor.prepareForNewRecording()
            deps.actionLogRepository.log(ActionLogType.TRANSCRIPTION_REQUEST, "Starting push-to-talk speech recognition")
            val transcript = deps.speechRecognitionController.recognizeCommand(
                preferOffline = settings.preferOfflineSpeechRecognition,
                diagnosticSessionId = sessionId
            )
            deps.speechDiagnosticsLogger.record(
                sessionId,
                "push_to_talk_transcript_ready",
                mapOf("transcriptLength" to transcript.length, "transcript" to transcript.take(160))
            )
            deps.speechDiagnosticsLogger.record(sessionId, "push_to_talk_execution_started")
            val result = deps.executor.executeTranscript(transcript, sessionId)
            if (!result.success) {
                deps.overlayRuntime.showNotice(
                    title = pushToTalkFailureTitle(result),
                    details = result.message,
                    kind = OverlayNoticeKind.ERROR
                )
            }
            deps.speechDiagnosticsLogger.record(
                sessionId,
                if (result.success) "push_to_talk_execution_succeeded" else "push_to_talk_execution_failed",
                serviceLifecycleFields(mapOf("message" to result.message, "errorCode" to result.errorCode))
            )
        }.onFailure { error ->
            val cancelled = error.message?.contains("cancelled", ignoreCase = true) == true
            deps.speechDiagnosticsLogger.record(
                sessionId,
                if (cancelled) "push_to_talk_cancelled" else "push_to_talk_failed",
                mapOf("errorClass" to error::class.java.name, "message" to error.message)
            )
            deps.actionLogRepository.log(
                if (cancelled) ActionLogType.CANCELLED else ActionLogType.ERROR,
                error.message ?: "Push-to-talk failed"
            )
            if (!cancelled) {
                deps.overlayRuntime.showNotice(
                    title = "Command failed",
                    details = error.message ?: "Push-to-talk failed",
                    kind = OverlayNoticeKind.ERROR
                )
            }
        }
    }

    private fun pushToTalkFailureTitle(result: ActionResult): String = when (result.errorCode) {
        "OPENAI_API_KEY_MISSING" -> "Add OpenAI key for plans"
        "AMBIGUOUS_OPEN_APP" -> "Need a more specific app"
        else -> "Command failed"
    }
}
