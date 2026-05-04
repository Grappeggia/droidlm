package ai.droidlm.voice

import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SpeechRecognitionUiState(
    val isStarting: Boolean = false,
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val errorMessage: String? = null,
    val missingLanguageTag: String? = null,
    val missingLanguageMessage: String? = null,
    val recognizerService: String? = null,
    val providerLabel: String = "Android SpeechRecognizer"
) {
    val isActive: Boolean
        get() = isStarting || isListening
}

class SpeechRecognitionController(
    private val context: Context,
    private val logs: ActionLogRepository,
    private val diagnostics: SpeechDiagnosticsLogger
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(SpeechRecognitionUiState())
    val state: StateFlow<SpeechRecognitionUiState> = _state.asStateFlow()

    @Volatile private var activeRecognizer: SpeechRecognizer? = null
    @Volatile private var activeDiagnosticSessionId: String? = null

    suspend fun recognizeCommand(
        preferOffline: Boolean,
        maxDurationMs: Long = 20_000L,
        languageTag: String = Locale.getDefault().toLanguageTag(),
        diagnosticSessionId: String? = null
    ): String = withContext(Dispatchers.Main.immediate) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val message = "Microphone permission is required for Android speech recognition."
            diagnostics.record(diagnosticSessionId, "permission_missing", mapOf("permission" to Manifest.permission.RECORD_AUDIO))
            _state.value = _state.value.copy(
                isStarting = false,
                isListening = false,
                errorMessage = message,
                missingLanguageTag = null,
                missingLanguageMessage = null
            )
            throw IllegalStateException(message)
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val message = "No Android speech recognizer is available on this device. Install or enable a speech recognition service."
            diagnostics.record(diagnosticSessionId, "recognizer_unavailable")
            _state.value = _state.value.copy(
                isStarting = false,
                isListening = false,
                errorMessage = message,
                missingLanguageTag = null,
                missingLanguageMessage = null
            )
            throw IllegalStateException(message)
        }

        val sessionId = diagnosticSessionId ?: diagnostics.startSession("speech_recognizer_direct")
        diagnostics.record(
            sessionId,
            "recognize_command_start",
            recognizerStartFields(preferOffline, maxDurationMs, languageTag)
        )

        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            activeRecognizer = recognizer
            activeDiagnosticSessionId = sessionId
            var readyForSpeech = false
            var speechStarted = false
            var retryCount = 0
            var diagnosticsEnded = false
            var peakRmsDb = Float.NEGATIVE_INFINITY
            var lastRmsLogAtMs = 0L
            val startedAtMs = System.currentTimeMillis()
            lateinit var intent: Intent

            fun updateStartingState() {
                _state.value = _state.value.copy(
                    isStarting = true,
                    isListening = false,
                    partialTranscript = "",
                    errorMessage = null,
                    missingLanguageTag = null,
                    missingLanguageMessage = null,
                    recognizerService = voiceRecognitionService()
                )
            }

            fun finishDiagnostics(reason: String, fields: Map<String, Any?> = emptyMap()) {
                if (diagnosticsEnded) return
                diagnosticsEnded = true
                diagnostics.endSession(sessionId, reason, fields)
            }

            val timeout = Runnable {
                diagnostics.record(
                    sessionId,
                    "max_duration_timeout",
                    mapOf("readyForSpeech" to readyForSpeech, "speechStarted" to speechStarted, "retryCount" to retryCount)
                )
                finishDiagnostics("timeout", mapOf("timeoutMs" to maxDurationMs))
                runCatching { recognizer.cancel() }
                cleanupRecognizer(recognizer)
                val message = "Speech recognition timed out after ${maxDurationMs / 1000}s."
                _state.value = _state.value.copy(isStarting = false, isListening = false, errorMessage = message)
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            }
            val readyTimeout = Runnable {
                if (!readyForSpeech && continuation.isActive) {
                    diagnostics.record(sessionId, "ready_timeout", mapOf("timeoutMs" to READY_TIMEOUT_MS, "retryCount" to retryCount))
                    finishDiagnostics("ready_timeout", mapOf("timeoutMs" to READY_TIMEOUT_MS))
                    runCatching { recognizer.cancel() }
                    cleanupRecognizer(recognizer)
                    val message = "Android speech recognition did not become ready."
                    _state.value = _state.value.copy(isStarting = false, isListening = false, errorMessage = message)
                    logs.log(ActionLogType.ERROR, message, "SPEECH_RECOGNIZER_READY_TIMEOUT")
                    continuation.resumeWithException(IllegalStateException(message))
                }
            }

            mainHandler.postDelayed(timeout, maxDurationMs)
            mainHandler.postDelayed(readyTimeout, READY_TIMEOUT_MS)

            fun cleanup() {
                mainHandler.removeCallbacks(timeout)
                mainHandler.removeCallbacks(readyTimeout)
                cleanupRecognizer(recognizer)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    readyForSpeech = true
                    diagnostics.record(sessionId, "onReadyForSpeech")
                    _state.value = _state.value.copy(
                        isStarting = false,
                        isListening = true,
                        partialTranscript = "",
                        errorMessage = null
                    )
                    logs.log(ActionLogType.RECORDING_STARTED, "Android speech recognition ready")
                }

                override fun onBeginningOfSpeech() {
                    speechStarted = true
                    diagnostics.record(sessionId, "onBeginningOfSpeech", mapOf("retryCount" to retryCount))
                    logs.log(ActionLogType.RECORDING_STARTED, "Speech input detected")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (rmsdB > peakRmsDb) peakRmsDb = rmsdB
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastRmsLogAtMs >= RMS_LOG_INTERVAL_MS) {
                        lastRmsLogAtMs = nowMs
                        diagnostics.record(
                            sessionId,
                            "onRmsChanged",
                            mapOf("rmsDb" to rounded(rmsdB), "peakRmsDb" to rounded(peakRmsDb))
                        )
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    diagnostics.record(sessionId, "onBufferReceived", mapOf("bytes" to (buffer?.size ?: 0)))
                }

                override fun onEndOfSpeech() {
                    diagnostics.record(
                        sessionId,
                        "onEndOfSpeech",
                        mapOf("speechStarted" to speechStarted, "peakRmsDb" to rounded(peakRmsDb))
                    )
                    if (speechStarted) {
                        _state.value = _state.value.copy(isStarting = false, isListening = false)
                    }
                    logs.log(ActionLogType.RECORDING_STOPPED, "Speech input ended")
                }

                override fun onError(error: Int) {
                    val message = SpeechRecognitionErrorMapper.messageFor(error, preferOffline, languageTag)
                    val elapsedMs = System.currentTimeMillis() - startedAtMs
                    diagnostics.record(
                        sessionId,
                        "onError",
                        mapOf(
                            "code" to error,
                            "message" to message,
                            "elapsedMs" to elapsedMs,
                            "retryCount" to retryCount,
                            "readyForSpeech" to readyForSpeech,
                            "speechStarted" to speechStarted,
                            "peakRmsDb" to rounded(peakRmsDb)
                        )
                    )
                    if (
                        continuation.isActive &&
                        !speechStarted &&
                        retryCount < INITIAL_SILENCE_RETRY_LIMIT &&
                        elapsedMs < INITIAL_SILENCE_GRACE_MS &&
                        isInitialSilenceError(error)
                    ) {
                        retryCount += 1
                        readyForSpeech = false
                        logs.log(
                            ActionLogType.ERROR,
                            "$message; retrying during initial speech grace window",
                            "SPEECH_RECOGNIZER_${error}_RETRY"
                        )
                        diagnostics.record(
                            sessionId,
                            "retry_scheduled",
                            mapOf("code" to error, "retryCount" to retryCount, "delayMs" to RECOGNIZER_RESTART_DELAY_MS)
                        )
                        updateStartingState()
                        mainHandler.postDelayed({
                            if (continuation.isActive && activeRecognizer === recognizer) {
                                diagnostics.record(sessionId, "retry_startListening", mapOf("retryCount" to retryCount))
                                runCatching { recognizer.startListening(intent) }
                                    .onFailure { failure -> diagnostics.record(sessionId, "retry_startListening_failed", mapOf("message" to failure.message)) }
                            }
                        }, RECOGNIZER_RESTART_DELAY_MS)
                        return
                    }

                    finishDiagnostics("error", mapOf("code" to error, "message" to message))
                    cleanup()
                    val isLanguageError = isLanguageSupportError(error)
                    _state.value = _state.value.copy(
                        isStarting = false,
                        isListening = false,
                        errorMessage = message,
                        missingLanguageTag = if (isLanguageError) languageTag else null,
                        missingLanguageMessage = if (isLanguageError) message else null,
                        recognizerService = voiceRecognitionService()
                    )
                    logs.log(ActionLogType.ERROR, message, "SPEECH_RECOGNIZER_$error")
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                }

                override fun onResults(results: Bundle?) {
                    val candidates = transcriptCandidates(results)
                    val transcript = candidates.firstOrNull().orEmpty()
                    diagnostics.record(
                        sessionId,
                        "onResults",
                        transcriptFields(transcript, candidates.size) + mapOf("peakRmsDb" to rounded(peakRmsDb))
                    )
                    cleanup()
                    if (transcript.isBlank()) {
                        val message = "Android speech recognition returned an empty transcript."
                        finishDiagnostics("empty_results", mapOf("resultCount" to candidates.size))
                        _state.value = _state.value.copy(isStarting = false, isListening = false, errorMessage = message)
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                        return
                    }
                    val finalText = appendTranscript(_state.value.finalTranscript, transcript)
                    _state.value = _state.value.copy(
                        isStarting = false,
                        isListening = false,
                        partialTranscript = "",
                        finalTranscript = finalText,
                        errorMessage = null
                    )
                    logs.log(ActionLogType.RECORDING_STOPPED, "Android speech recognition stopped")
                    logs.log(ActionLogType.TRANSCRIPTION_RESULT, transcript)
                    finishDiagnostics("results", transcriptFields(transcript, candidates.size))
                    if (continuation.isActive) continuation.resume(transcript)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val candidates = transcriptCandidates(partialResults)
                    val partial = candidates.firstOrNull().orEmpty()
                    diagnostics.record(sessionId, "onPartialResults", transcriptFields(partial, candidates.size))
                    if (partial.isNotBlank()) {
                        _state.value = _state.value.copy(partialTranscript = partial, errorMessage = null)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    diagnostics.record(sessionId, "onEvent", mapOf("eventType" to eventType))
                }
            })

            continuation.invokeOnCancellation {
                mainHandler.post {
                    diagnostics.record(sessionId, "continuation_cancelled")
                    finishDiagnostics("cancelled")
                    runCatching { recognizer.cancel() }
                    cleanup()
                    _state.value = _state.value.copy(
                        isStarting = false,
                        isListening = false,
                        errorMessage = "Speech recognition cancelled"
                    )
                }
            }

            intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, POSSIBLY_COMPLETE_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_INPUT_MS)
            }
            updateStartingState()
            diagnostics.record(sessionId, "startListening")
            runCatching { recognizer.startListening(intent) }
                .onFailure { failure ->
                    val message = failure.message ?: failure::class.java.simpleName
                    finishDiagnostics("startListening_failed", mapOf("message" to message))
                    cleanup()
                    _state.value = _state.value.copy(isStarting = false, isListening = false, errorMessage = message)
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
        }
    }

    fun stopCurrent(): Boolean {
        val recognizer = activeRecognizer ?: return false
        mainHandler.post {
            diagnostics.record(activeDiagnosticSessionId, "stop_current_requested")
            runCatching { recognizer.stopListening() }
            _state.value = _state.value.copy(
                isStarting = false,
                isListening = false,
                partialTranscript = "",
                errorMessage = null
            )
            logs.log(ActionLogType.RECORDING_STOPPED, "Android speech recognition stop requested")
        }
        return true
    }

    fun cancelCurrent() {
        mainHandler.post {
            val sessionId = activeDiagnosticSessionId
            diagnostics.record(sessionId, "cancel_current_requested")
            activeRecognizer?.let { recognizer ->
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
            activeRecognizer = null
            activeDiagnosticSessionId = null
            diagnostics.endSession(sessionId, "cancelled_by_user")
            _state.value = _state.value.copy(
                isStarting = false,
                isListening = false,
                errorMessage = "Speech recognition cancelled"
            )
            logs.log(ActionLogType.CANCELLED, "Android speech recognition cancelled")
        }
    }

    fun clear() {
        _state.value = SpeechRecognitionUiState()
    }

    private fun cleanupRecognizer(recognizer: SpeechRecognizer) {
        if (activeRecognizer === recognizer) {
            activeRecognizer = null
            activeDiagnosticSessionId = null
        }
        runCatching { recognizer.destroy() }
    }

    private fun appendTranscript(current: String, transcript: String): String = when {
        current.isBlank() -> transcript
        transcript.isBlank() -> current
        else -> "$current\n$transcript"
    }

    private fun bestTranscript(results: Bundle?): String = transcriptCandidates(results).firstOrNull().orEmpty()

    private fun transcriptCandidates(results: Bundle?): List<String> {
        return results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun transcriptFields(transcript: String, resultCount: Int): Map<String, Any?> = mapOf(
        "resultCount" to resultCount,
        "transcriptLength" to transcript.length,
        "transcript" to transcript.take(MAX_TRANSCRIPT_DIAGNOSTIC_CHARS)
    )

    private fun rounded(value: Float): Float {
        if (!value.isFinite()) return value
        return kotlin.math.round(value * 10f) / 10f
    }

    private fun recognizerStartFields(
        preferOffline: Boolean,
        maxDurationMs: Long,
        languageTag: String
    ): Map<String, Any?> = mapOf(
        "preferOffline" to preferOffline,
        "language" to languageTag,
        "defaultLocale" to Locale.getDefault().toLanguageTag(),
        "maxDurationMs" to maxDurationMs,
        "completeSilenceMs" to COMPLETE_SILENCE_MS,
        "possiblyCompleteSilenceMs" to POSSIBLY_COMPLETE_SILENCE_MS,
        "minimumInputMs" to MINIMUM_INPUT_MS,
        "voiceRecognitionService" to voiceRecognitionService(),
        "voiceRecognitionPackage" to voiceRecognitionPackage()
    )

    private fun voiceRecognitionService(): String? = runCatching {
        android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
    }.getOrNull()

    private fun voiceRecognitionPackage(): String? {
        val service = voiceRecognitionService().orEmpty()
        return android.content.ComponentName.unflattenFromString(service)?.packageName
            ?: service.substringBefore('/').takeIf { it.isNotBlank() }
    }


    private fun isInitialSilenceError(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            error == SpeechRecognizer.ERROR_CLIENT

    private fun isLanguageSupportError(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

    private companion object {
        const val COMPLETE_SILENCE_MS = 3_000L
        const val POSSIBLY_COMPLETE_SILENCE_MS = 2_200L
        const val MINIMUM_INPUT_MS = 2_500L
        const val INITIAL_SILENCE_GRACE_MS = 8_000L
        const val INITIAL_SILENCE_RETRY_LIMIT = 2
        const val READY_TIMEOUT_MS = 10_000L
        const val RECOGNIZER_RESTART_DELAY_MS = 250L
        const val RMS_LOG_INTERVAL_MS = 500L
        const val MAX_TRANSCRIPT_DIAGNOSTIC_CHARS = 160
    }
}

