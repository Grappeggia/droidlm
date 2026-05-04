package ai.droidlm.voice

import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
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
    val providerLabel: String = "Android SpeechRecognizer"
) {
    val isActive: Boolean
        get() = isStarting || isListening
}

class SpeechRecognitionController(
    private val context: Context,
    private val logs: ActionLogRepository
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(SpeechRecognitionUiState())
    val state: StateFlow<SpeechRecognitionUiState> = _state.asStateFlow()

    @Volatile private var activeRecognizer: SpeechRecognizer? = null

    suspend fun recognizeCommand(
        preferOffline: Boolean,
        maxDurationMs: Long = 20_000L,
        languageTag: String = Locale.getDefault().toLanguageTag()
    ): String = withContext(Dispatchers.Main.immediate) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val message = "Microphone permission is required for Android speech recognition."
            _state.value = _state.value.copy(isStarting = false, isListening = false, errorMessage = message)
            throw IllegalStateException(message)
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val message = "No Android speech recognizer is available on this device. Install or enable a speech recognition service."
            _state.value = _state.value.copy(isStarting = false, isListening = false, errorMessage = message)
            throw IllegalStateException(message)
        }

        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            activeRecognizer = recognizer
            var readyForSpeech = false
            var speechStarted = false
            var retryCount = 0
            val startedAtMs = System.currentTimeMillis()
            lateinit var intent: Intent

            fun updateStartingState() {
                _state.value = _state.value.copy(
                    isStarting = true,
                    isListening = false,
                    partialTranscript = "",
                    errorMessage = null
                )
            }

            val timeout = Runnable {
                runCatching { recognizer.cancel() }
                cleanupRecognizer(recognizer)
                val message = "Speech recognition timed out after ${maxDurationMs / 1000}s."
                _state.value = _state.value.copy(isStarting = false, isListening = false, errorMessage = message)
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            }
            val readyTimeout = Runnable {
                if (!readyForSpeech && continuation.isActive) {
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
                    logs.log(ActionLogType.RECORDING_STARTED, "Speech input detected")
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    if (speechStarted) {
                        _state.value = _state.value.copy(isStarting = false, isListening = false)
                    }
                    logs.log(ActionLogType.RECORDING_STOPPED, "Speech input ended")
                }

                override fun onError(error: Int) {
                    val message = SpeechRecognitionErrorMapper.messageFor(error)
                    val elapsedMs = System.currentTimeMillis() - startedAtMs
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
                        updateStartingState()
                        mainHandler.postDelayed({
                            if (continuation.isActive && activeRecognizer === recognizer) {
                                runCatching { recognizer.startListening(intent) }
                            }
                        }, RECOGNIZER_RESTART_DELAY_MS)
                        return
                    }

                    cleanup()
                    _state.value = _state.value.copy(isStarting = false, isListening = false, errorMessage = message)
                    logs.log(ActionLogType.ERROR, message, "SPEECH_RECOGNIZER_$error")
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                }

                override fun onResults(results: Bundle?) {
                    val transcript = bestTranscript(results)
                    cleanup()
                    if (transcript.isBlank()) {
                        val message = "Android speech recognition returned an empty transcript."
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
                    if (continuation.isActive) continuation.resume(transcript)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = bestTranscript(partialResults)
                    if (partial.isNotBlank()) {
                        _state.value = _state.value.copy(partialTranscript = partial, errorMessage = null)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            continuation.invokeOnCancellation {
                mainHandler.post {
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
            recognizer.startListening(intent)
        }
    }

    fun stopCurrent(): Boolean {
        val recognizer = activeRecognizer ?: return false
        mainHandler.post {
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
            activeRecognizer?.let { recognizer ->
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
            activeRecognizer = null
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
        if (activeRecognizer === recognizer) activeRecognizer = null
        runCatching { recognizer.destroy() }
    }

    private fun appendTranscript(current: String, transcript: String): String = when {
        current.isBlank() -> transcript
        transcript.isBlank() -> current
        else -> "$current\n$transcript"
    }

    private fun bestTranscript(results: Bundle?): String {
        return results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
    }

    private fun isInitialSilenceError(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            error == SpeechRecognizer.ERROR_CLIENT

    private companion object {
        const val COMPLETE_SILENCE_MS = 3_000L
        const val POSSIBLY_COMPLETE_SILENCE_MS = 2_200L
        const val MINIMUM_INPUT_MS = 2_500L
        const val INITIAL_SILENCE_GRACE_MS = 8_000L
        const val INITIAL_SILENCE_RETRY_LIMIT = 2
        const val READY_TIMEOUT_MS = 10_000L
        const val RECOGNIZER_RESTART_DELAY_MS = 250L
    }
}

