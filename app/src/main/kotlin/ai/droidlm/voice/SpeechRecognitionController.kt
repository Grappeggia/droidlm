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
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val errorMessage: String? = null,
    val providerLabel: String = "Android SpeechRecognizer"
)

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
        maxDurationMs: Long = 12_000L,
        languageTag: String = Locale.getDefault().toLanguageTag()
    ): String = withContext(Dispatchers.Main.immediate) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val message = "Microphone permission is required for Android speech recognition."
            _state.value = _state.value.copy(isListening = false, errorMessage = message)
            throw IllegalStateException(message)
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val message = "No Android speech recognizer is available on this device. Install or enable a speech recognition service."
            _state.value = _state.value.copy(isListening = false, errorMessage = message)
            throw IllegalStateException(message)
        }

        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            activeRecognizer = recognizer
            val timeout = Runnable {
                recognizer.cancel()
                recognizer.destroy()
                activeRecognizer = null
                val message = "Speech recognition timed out after ${maxDurationMs / 1000}s."
                _state.value = _state.value.copy(isListening = false, errorMessage = message)
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            }
            mainHandler.postDelayed(timeout, maxDurationMs)

            fun cleanup() {
                mainHandler.removeCallbacks(timeout)
                activeRecognizer = null
                runCatching { recognizer.destroy() }
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _state.value = _state.value.copy(isListening = true, partialTranscript = "", errorMessage = null)
                    logs.log(ActionLogType.RECORDING_STARTED, "Android speech recognition started")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    _state.value = _state.value.copy(isListening = false)
                }

                override fun onError(error: Int) {
                    val message = SpeechRecognitionErrorMapper.messageFor(error)
                    cleanup()
                    _state.value = _state.value.copy(isListening = false, errorMessage = message)
                    logs.log(ActionLogType.ERROR, message, "SPEECH_RECOGNIZER_$error")
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                }

                override fun onResults(results: Bundle?) {
                    val transcript = bestTranscript(results)
                    cleanup()
                    if (transcript.isBlank()) {
                        val message = "Android speech recognition returned an empty transcript."
                        _state.value = _state.value.copy(isListening = false, errorMessage = message)
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                        return
                    }
                    val finalText = appendTranscript(_state.value.finalTranscript, transcript)
                    _state.value = _state.value.copy(
                        isListening = false,
                        partialTranscript = "",
                        finalTranscript = finalText,
                        errorMessage = null
                    )
                    logs.log(ActionLogType.RECORDING_STOPPED, "Android speech recognition stopped")
                    logs.log(ActionLogType.TRANSCRIPTION_RESULT, transcript)
                    if (continuation.isActive) continuation.resume(transcript)
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            continuation.invokeOnCancellation {
                mainHandler.post {
                    cleanup()
                    runCatching { recognizer.cancel() }
                    _state.value = _state.value.copy(isListening = false, errorMessage = "Speech recognition cancelled")
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
            }
            _state.value = _state.value.copy(isListening = true, partialTranscript = "", errorMessage = null)
            recognizer.startListening(intent)
        }
    }

    fun stopCurrent(): Boolean {
        val recognizer = activeRecognizer ?: return false
        mainHandler.post {
            runCatching { recognizer.stopListening() }
            _state.value = _state.value.copy(isListening = false, partialTranscript = "", errorMessage = null)
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
            _state.value = _state.value.copy(isListening = false, errorMessage = "Speech recognition cancelled")
            logs.log(ActionLogType.CANCELLED, "Android speech recognition cancelled")
        }
    }

    fun clear() {
        _state.value = SpeechRecognitionUiState()
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
}
