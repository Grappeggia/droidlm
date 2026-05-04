package ai.droidlm.voice

import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
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
    val speechSetupChecking: Boolean = false,
    val speechSetupChecked: Boolean = false,
    val speechSetupAvailable: Boolean? = null,
    val speechSetupMessage: String? = null,
    val providerLabel: String = "Android SpeechRecognizer"
) {
    val isActive: Boolean
        get() = isStarting || isListening
}

private class AndroidSpeechRecognitionException(
    val errorCode: Int,
    message: String
) : IllegalStateException(message)

class SpeechRecognitionController(
    private val context: Context,
    private val logs: ActionLogRepository,
    private val diagnostics: SpeechDiagnosticsLogger,
    private val voskRecognizer: VoskOfflineSpeechRecognizer
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
        val sessionId = diagnosticSessionId ?: diagnostics.startSession("speech_recognizer_direct")
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val message = "No Android speech recognizer is available on this device."
            diagnostics.record(diagnosticSessionId, "recognizer_unavailable")
            if (canUseVoskFallback(languageTag)) {
                diagnostics.record(sessionId, "android_recognizer_unavailable_falling_back_to_vosk", mapOf("message" to message))
                return@withContext recognizeWithVoskFallback(sessionId, maxDurationMs, languageTag, message)
            }
            val fullMessage = "$message Install or enable a speech recognition service."
            _state.value = _state.value.copy(
                isStarting = false,
                isListening = false,
                errorMessage = fullMessage,
                missingLanguageTag = null,
                missingLanguageMessage = null
            )
            throw IllegalStateException(fullMessage)
        }

        diagnostics.record(
            sessionId,
            "recognize_command_start",
            recognizerStartFields(preferOffline, maxDurationMs, languageTag) + mapOf("voskFallbackSupported" to canUseVoskFallback(languageTag))
        )

        try {
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
                    if (continuation.isActive) continuation.resumeWithException(AndroidSpeechRecognitionException(error, message))
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

        } catch (error: AndroidSpeechRecognitionException) {
            if (shouldFallbackToVosk(error.errorCode, languageTag)) {
                diagnostics.record(
                    sessionId,
                    "android_speech_failed_falling_back_to_vosk",
                    mapOf("code" to error.errorCode, "message" to error.message)
                )
                return@withContext recognizeWithVoskFallback(sessionId, maxDurationMs, languageTag, error.message ?: "Android speech failed")
            }
            throw error
        }
    }

    fun checkSpeechSetup(
        preferOffline: Boolean,
        languageTag: String = Locale.getDefault().toLanguageTag()
    ) {
        mainHandler.post {
            val fallbackSupported = canUseVoskFallback(languageTag)
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                val message = if (fallbackSupported) {
                    "Android speech recognition is unavailable, but DroidLM's built-in offline English speech is ready."
                } else {
                    "No Android speech recognizer is available on this device. Install or enable a speech recognition service."
                }
                updateSpeechSetupResult(languageTag, fallbackSupported, message, preferOffline)
                logs.log(if (fallbackSupported) ActionLogType.ACTION_RESULT else ActionLogType.ERROR, message, "SPEECH_SETUP_RECOGNIZER_UNAVAILABLE")
                return@post
            }
            val recognizerService = voiceRecognitionService()
            _state.value = _state.value.copy(
                speechSetupChecking = true,
                speechSetupChecked = false,
                speechSetupMessage = "Checking Android speech setup for ${languageDisplayName(languageTag)}...",
                recognizerService = recognizerService
            )
            diagnostics.record(
                null,
                "speech_setup_check_started",
                mapOf(
                    "preferOffline" to preferOffline,
                    "language" to languageTag,
                    "defaultLocale" to Locale.getDefault().toLanguageTag(),
                    "voiceRecognitionService" to recognizerService,
                    "voiceRecognitionPackage" to voiceRecognitionPackage(),
                    "voskFallbackSupported" to fallbackSupported
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkSpeechSetupOnApi33(preferOffline, languageTag)
            } else {
                val message = if (fallbackSupported) {
                    "DroidLM includes built-in offline English speech. Android ${Build.VERSION.RELEASE} cannot verify Google/OEM offline speech before recording, so DroidLM will fall back automatically if Android speech fails."
                } else {
                    "Android ${Build.VERSION.RELEASE} cannot verify offline speech languages before recording. Install or check offline Android speech recognition for ${languageDisplayName(languageTag)} in Android voice input settings, then try recording again."
                }
                updateSpeechSetupResult(languageTag, fallbackSupported, message, preferOffline)
                logs.log(if (fallbackSupported) ActionLogType.ACTION_RESULT else ActionLogType.ERROR, message, "SPEECH_SETUP_CHECK")
            }
        }
    }
    private suspend fun recognizeWithVoskFallback(
        sessionId: String,
        maxDurationMs: Long,
        languageTag: String,
        androidFailureMessage: String
    ): String {
        if (!canUseVoskFallback(languageTag)) {
            throw IllegalStateException("Built-in offline speech currently supports English only.")
        }
        logs.log(ActionLogType.TRANSCRIPTION_REQUEST, "Using built-in offline English speech")
        diagnostics.record(
            sessionId,
            "vosk_fallback_started",
            mapOf("language" to languageTag, "androidFailureMessage" to androidFailureMessage)
        )
        return runCatching {
            val transcript = voskRecognizer.recognizeCommand(
                languageTag = languageTag,
                maxDurationMs = maxDurationMs,
                diagnosticSessionId = sessionId,
                callbacks = VoskOfflineSpeechRecognizer.Callbacks(
                    onStarting = {
                        _state.value = _state.value.copy(
                            isStarting = true,
                            isListening = false,
                            partialTranscript = "",
                            errorMessage = null,
                            providerLabel = VOSK_PROVIDER_LABEL
                        )
                    },
                    onReady = {
                        _state.value = _state.value.copy(
                            isStarting = false,
                            isListening = true,
                            partialTranscript = "",
                            errorMessage = null,
                            providerLabel = VOSK_PROVIDER_LABEL
                        )
                    },
                    onPartial = { partial ->
                        _state.value = _state.value.copy(partialTranscript = partial, errorMessage = null, providerLabel = VOSK_PROVIDER_LABEL)
                    }
                )
            )
            val finalText = appendTranscript(_state.value.finalTranscript, transcript)
            _state.value = _state.value.copy(
                isStarting = false,
                isListening = false,
                partialTranscript = "",
                finalTranscript = finalText,
                errorMessage = null,
                missingLanguageTag = null,
                missingLanguageMessage = null,
                speechSetupChecked = true,
                speechSetupAvailable = true,
                speechSetupMessage = "Using built-in offline English speech.",
                providerLabel = VOSK_PROVIDER_LABEL
            )
            logs.log(ActionLogType.TRANSCRIPTION_RESULT, transcript)
            diagnostics.endSession(sessionId, "vosk_results", transcriptFields(transcript, 1))
            transcript
        }.getOrElse { error ->
            val message = "Built-in offline speech failed: ${error.message ?: error::class.java.simpleName}"
            diagnostics.record(sessionId, "vosk_fallback_failed", mapOf("message" to message, "errorClass" to error::class.java.name))
            _state.value = _state.value.copy(
                isStarting = false,
                isListening = false,
                errorMessage = message,
                providerLabel = VOSK_PROVIDER_LABEL
            )
            logs.log(ActionLogType.ERROR, message, "VOSK_FALLBACK_FAILED")
            throw IllegalStateException(message, error)
        }
    }

    private fun canUseVoskFallback(languageTag: String): Boolean = voskRecognizer.supportsLanguage(languageTag)

    private fun shouldFallbackToVosk(errorCode: Int, languageTag: String): Boolean =
        canUseVoskFallback(languageTag) && (
            errorCode == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                errorCode == SpeechRecognizer.ERROR_SERVER
            )




    fun stopCurrent(): Boolean {
        val recognizer = activeRecognizer ?: return voskRecognizer.stopCurrent()
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
            voskRecognizer.cancelCurrent()
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

    private fun checkSpeechSetupOnApi33(preferOffline: Boolean, languageTag: String) {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        val intent = recognitionIntent(preferOffline, languageTag)
        recognizer.checkRecognitionSupport(
            intent,
            ContextCompat.getMainExecutor(context),
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    runCatching { recognizer.destroy() }
                    val installed = recognitionSupport.installedOnDeviceLanguages
                    val pending = recognitionSupport.pendingOnDeviceLanguages
                    val supported = recognitionSupport.supportedOnDeviceLanguages
                    val online = recognitionSupport.onlineLanguages
                    val installedMatch = installed.any { languageMatches(languageTag, it) }
                    val pendingMatch = pending.any { languageMatches(languageTag, it) }
                    val supportedMatch = supported.any { languageMatches(languageTag, it) }
                    val onlineMatch = online.any { languageMatches(languageTag, it) }
                    diagnostics.record(
                        null,
                        "speech_setup_check_result",
                        mapOf(
                            "language" to languageTag,
                            "preferOffline" to preferOffline,
                            "installedOnDeviceLanguages" to installed,
                            "pendingOnDeviceLanguages" to pending,
                            "supportedOnDeviceLanguages" to supported,
                            "onlineLanguages" to online,
                            "installedMatch" to installedMatch,
                            "pendingMatch" to pendingMatch,
                            "supportedMatch" to supportedMatch,
                            "onlineMatch" to onlineMatch,
                            "voskFallbackSupported" to canUseVoskFallback(languageTag)
                        )
                    )
                    val languageName = languageDisplayName(languageTag)
                    val androidAvailable = if (preferOffline) installedMatch else installedMatch || onlineMatch
                    val fallbackSupported = canUseVoskFallback(languageTag)
                    val available = androidAvailable || fallbackSupported
                    val message = when {
                        preferOffline && installedMatch -> "Offline Android speech recognition for $languageName is installed."
                        preferOffline && pendingMatch && fallbackSupported -> "Offline Android speech recognition for $languageName is pending download. DroidLM can use built-in offline English speech while it finishes."
                        preferOffline && pendingMatch -> "Offline Android speech recognition for $languageName is pending download. Open Android speech settings to finish installing it."
                        preferOffline && supportedMatch && fallbackSupported -> "Offline Android speech recognition for $languageName is supported but not installed. DroidLM can use built-in offline English speech now."
                        preferOffline && supportedMatch -> "Offline Android speech recognition for $languageName is supported but not installed. Open Android speech settings and download it."
                        preferOffline && fallbackSupported -> "Android offline speech for $languageName is not installed or not reported by this device. DroidLM will use built-in offline English speech."
                        preferOffline -> "Offline Android speech recognition for $languageName is not installed or not reported by this device. Open Android speech settings and download it if available."
                        available -> "Android speech recognition for $languageName is available."
                        else -> "Android speech recognition for $languageName is not available on this device. Open Android speech settings or choose another language."
                    }
                    updateSpeechSetupResult(languageTag, available, message, preferOffline)
                    logs.log(
                        if (available) ActionLogType.ACTION_RESULT else ActionLogType.ERROR,
                        message,
                        "SPEECH_SETUP_CHECK"
                    )
                }

                override fun onError(error: Int) {
                    runCatching { recognizer.destroy() }
                    val message = SpeechRecognitionErrorMapper.messageFor(error, preferOffline, languageTag)
                    diagnostics.record(
                        null,
                        "speech_setup_check_error",
                        mapOf("code" to error, "message" to message, "language" to languageTag, "preferOffline" to preferOffline)
                    )
                    val fallbackSupported = canUseVoskFallback(languageTag)
                    val setupMessage = if (fallbackSupported) "$message DroidLM can use built-in offline English speech instead." else message
                    updateSpeechSetupResult(languageTag, fallbackSupported, setupMessage, preferOffline)
                    logs.log(if (fallbackSupported) ActionLogType.ACTION_RESULT else ActionLogType.ERROR, setupMessage, "SPEECH_SETUP_CHECK_$error")
                }
            }
        )
    }

    private fun updateSpeechSetupResult(
        languageTag: String,
        available: Boolean,
        message: String,
        preferOffline: Boolean
    ) {
        val missingLanguageTag = if (preferOffline && !available) languageTag else null
        _state.value = _state.value.copy(
            speechSetupChecking = false,
            speechSetupChecked = true,
            speechSetupAvailable = available,
            speechSetupMessage = message,
            missingLanguageTag = missingLanguageTag,
            missingLanguageMessage = if (missingLanguageTag != null) message else null,
            recognizerService = voiceRecognitionService()
        )
    }

    private fun recognitionIntent(preferOffline: Boolean, languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, POSSIBLY_COMPLETE_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_INPUT_MS)
        }

    private fun languageMatches(requested: String, candidate: String): Boolean {
        val requestedLocale = Locale.forLanguageTag(requested)
        val candidateLocale = Locale.forLanguageTag(candidate)
        val requestedLanguage = requestedLocale.language.ifBlank { requested.substringBefore('-') }
        val candidateLanguage = candidateLocale.language.ifBlank { candidate.substringBefore('-') }
        if (!requestedLanguage.equals(candidateLanguage, ignoreCase = true)) return false
        val requestedCountry = requestedLocale.country
        val candidateCountry = candidateLocale.country
        return requestedCountry.isBlank() || candidateCountry.isBlank() || requestedCountry.equals(candidateCountry, ignoreCase = true)
    }

    private fun languageDisplayName(languageTag: String): String {
        val locale = Locale.forLanguageTag(languageTag)
        return locale.getDisplayName(locale).takeIf { it.isNotBlank() } ?: languageTag
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
        const val VOSK_PROVIDER_LABEL = "Built-in offline English speech"
    }
}

