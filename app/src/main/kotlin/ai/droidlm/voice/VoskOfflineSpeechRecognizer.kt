package ai.droidlm.voice

import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

open class VoskOfflineSpeechRecognizer(
    private val context: Context,
    private val logs: ActionLogRepository,
    private val diagnostics: SpeechDiagnosticsLogger,
    private val debugLogStore: DebugLogStore? = null
) {
    data class Callbacks(
        val onStarting: () -> Unit = {},
        val onReady: () -> Unit = {},
        val onPartial: (String) -> Unit = {}
    )

    @Volatile private var activeAudioRecord: AudioRecord? = null
    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false

    private val modelLock = Any()
    private var cachedModel: Model? = null

    open fun supportsLanguage(languageTag: String): Boolean {
        val locale = Locale.forLanguageTag(languageTag)
        val language = locale.language.ifBlank { languageTag.substringBefore('-') }
        return language.equals("en", ignoreCase = true)
    }

    open fun stopCurrent(): Boolean {
        val recorder = activeAudioRecord ?: return false
        stopRequested = true
        runCatching { recorder.stop() }
        return true
    }

    open fun cancelCurrent(): Boolean {
        val recorder = activeAudioRecord ?: return false
        cancelRequested = true
        stopRequested = true
        runCatching { recorder.stop() }
        return true
    }

    open suspend fun recognizeCommand(
        languageTag: String,
        maxDurationMs: Long,
        diagnosticSessionId: String?,
        callbacks: Callbacks = Callbacks()
    ): String = withContext(Dispatchers.IO) {
        if (!supportsLanguage(languageTag)) {
            throw IllegalStateException("Built-in offline speech currently supports English only.")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("Microphone permission is required for built-in offline speech.")
        }

        callbacks.onStarting()
        diagnostics.record(diagnosticSessionId, "vosk_model_loading", mapOf("language" to languageTag, "assetModel" to ASSET_MODEL_NAME))
        val model = loadModel()
        diagnostics.record(diagnosticSessionId, "vosk_model_ready", mapOf("model" to ASSET_MODEL_NAME))

        val recorder = createAudioRecord()
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        val acceptedSegments = mutableListOf<String>()
        var lastPartial = ""
        var peakRms = 0.0
        var lastRmsLogAt = 0L
        var speechStarted = false
        var lastSpeechAt: Long? = null
        val startedAt = System.currentTimeMillis()
        val audioCaptureFile = debugLogStore?.createRetainedFile("audio", "pcm", "vosk")
        val audioCaptureOutput = audioCaptureFile?.let { runCatching { it.outputStream() }.getOrNull() }
        audioCaptureFile?.let {
            diagnostics.record(
                diagnosticSessionId,
                "debug_audio_capture_started",
                mapOf("file" to it.name, "format" to "pcm_s16le_mono", "sampleRate" to SAMPLE_RATE)
            )
        }
        if (audioCaptureFile != null && audioCaptureOutput == null) {
            diagnostics.record(diagnosticSessionId, "debug_audio_capture_open_failed", mapOf("file" to audioCaptureFile.name))
        }
        stopRequested = false
        cancelRequested = false
        activeAudioRecord = recorder

        try {
            recorder.startRecording()
            callbacks.onReady()
            logs.log(ActionLogType.RECORDING_STARTED, "Built-in offline speech ready")
            diagnostics.record(diagnosticSessionId, "vosk_ready", mapOf("sampleRate" to SAMPLE_RATE))

            val buffer = ByteArray(BUFFER_BYTES)
            while (!stopRequested && System.currentTimeMillis() - startedAt < maxDurationMs) {
                val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(0)
                if (read <= 0) continue
                runCatching { audioCaptureOutput?.write(buffer, 0, read) }

                val rms = pcmRms(buffer, read)
                if (rms > peakRms) peakRms = rms
                val now = System.currentTimeMillis()
                if (now - lastRmsLogAt >= RMS_LOG_INTERVAL_MS) {
                    lastRmsLogAt = now
                    diagnostics.record(
                        diagnosticSessionId,
                        "vosk_rms",
                        mapOf("rms" to rounded(rms), "peakRms" to rounded(peakRms), "speechStarted" to speechStarted)
                    )
                }
                if (rms >= SPEECH_RMS_THRESHOLD) {
                    speechStarted = true
                    lastSpeechAt = now
                }

                if (recognizer.acceptWaveForm(buffer, read)) {
                    val text = parseText(recognizer.result)
                    if (text.isNotBlank()) {
                        acceptedSegments += text
                        callbacks.onPartial(text)
                        diagnostics.record(diagnosticSessionId, "vosk_segment", transcriptFields(text))
                    }
                } else {
                    val partial = parsePartial(recognizer.partialResult)
                    if (partial.isNotBlank() && partial != lastPartial) {
                        lastPartial = partial
                        speechStarted = true
                        lastSpeechAt = now
                        callbacks.onPartial(partial)
                        diagnostics.record(diagnosticSessionId, "vosk_partial", transcriptFields(partial))
                    }
                }

                val elapsed = now - startedAt
                val silentLongEnough = speechStarted && elapsed >= MIN_DURATION_MS && lastSpeechAt?.let { now - it >= SILENCE_AFTER_SPEECH_MS } == true
                val noSpeechTimeout = !speechStarted && elapsed >= INITIAL_NO_SPEECH_TIMEOUT_MS
                if (silentLongEnough || noSpeechTimeout) break
            }

            val finalText = parseText(recognizer.finalResult)
            val transcript = (acceptedSegments + finalText)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { lastPartial }
                .trim()
            val durationMs = System.currentTimeMillis() - startedAt
            diagnostics.record(
                diagnosticSessionId,
                "vosk_final",
                transcriptFields(transcript) + mapOf(
                    "durationMs" to durationMs,
                    "speechStarted" to speechStarted,
                    "cancelRequested" to cancelRequested,
                    "peakRms" to rounded(peakRms)
                )
            )
            if (cancelRequested) throw IllegalStateException("Built-in offline speech cancelled.")
            if (transcript.isBlank()) throw IllegalStateException("Built-in offline speech did not hear a command.")
            logs.log(ActionLogType.RECORDING_STOPPED, "Built-in offline speech stopped after ${durationMs}ms")
            transcript
        } catch (error: Throwable) {
            diagnostics.record(diagnosticSessionId, "vosk_error", mapOf("message" to error.message, "errorClass" to error::class.java.name))
            throw error
        } finally {
            runCatching { audioCaptureOutput?.close() }
            audioCaptureFile?.let { file ->
                if (file.length() > 0L) {
                    diagnostics.record(diagnosticSessionId, "debug_audio_capture_saved", mapOf("file" to file.name, "bytes" to file.length()))
                } else {
                    file.delete()
                }
            }
            activeAudioRecord = null
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { recognizer.close() }
        }
    }

    suspend fun transcribePcm16Mono(
        pcm: ByteArray,
        sampleRate: Float = SAMPLE_RATE.toFloat(),
        languageTag: String = "en-US",
        diagnosticSessionId: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (!supportsLanguage(languageTag)) {
            throw IllegalStateException("Built-in offline speech currently supports English only.")
        }
        diagnostics.record(diagnosticSessionId, "vosk_file_model_loading", mapOf("bytes" to pcm.size, "sampleRate" to sampleRate))
        val recognizer = Recognizer(loadModel(), sampleRate)
        try {
            var offset = 0
            val segments = mutableListOf<String>()
            while (offset < pcm.size) {
                val length = minOf(BUFFER_BYTES, pcm.size - offset)
                val chunk = pcm.copyOfRange(offset, offset + length)
                if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                    parseText(recognizer.result).takeIf { it.isNotBlank() }?.let { segments += it }
                }
                offset += length
            }
            val finalText = parseText(recognizer.finalResult)
            val transcript = (segments + finalText).filter { it.isNotBlank() }.joinToString(" ").trim()
            diagnostics.record(diagnosticSessionId, "vosk_file_final", transcriptFields(transcript))
            transcript
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private fun loadModel(): Model = synchronized(modelLock) {
        cachedModel ?: Model(ensureModelCopied().absolutePath).also { cachedModel = it }
    }

    private fun ensureModelCopied(): File {
        val target = File(context.filesDir, "vosk/$ASSET_MODEL_NAME")
        val marker = File(target, READY_MARKER)
        if (marker.isFile) return target
        target.deleteRecursively()
        target.mkdirs()
        copyAssetPath(ASSET_MODEL_NAME, target)
        marker.writeText(ASSET_MODEL_NAME)
        return target
    }

    private fun copyAssetPath(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetPath("$assetPath/$child", File(target, child)) }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "Could not initialize microphone buffer for built-in offline speech." }
        val bufferSize = maxOf(minBuffer, BUFFER_BYTES * 2)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }
    }

    private fun parseText(json: String): String = runCatching { JSONObject(json).optString("text").trim() }.getOrDefault("")

    private fun parsePartial(json: String): String = runCatching { JSONObject(json).optString("partial").trim() }.getOrDefault("")

    private fun pcmRms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < length) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xff)).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            count += 1
            index += 2
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }

    private fun transcriptFields(transcript: String): Map<String, Any?> = mapOf(
        "transcriptLength" to transcript.length,
        "transcript" to transcript.take(MAX_TRANSCRIPT_DIAGNOSTIC_CHARS)
    )

    private fun rounded(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0

    private companion object {
        const val ASSET_MODEL_NAME = "vosk-model-en-us-0.22-lgraph"
        const val READY_MARKER = ".droidlm-model-ready"
        const val SAMPLE_RATE = 16_000
        const val BUFFER_BYTES = 4_096
        const val MIN_DURATION_MS = 1_000L
        const val INITIAL_NO_SPEECH_TIMEOUT_MS = 8_000L
        const val SILENCE_AFTER_SPEECH_MS = 1_400L
        const val SPEECH_RMS_THRESHOLD = 350.0
        const val RMS_LOG_INTERVAL_MS = 500L
        const val MAX_TRANSCRIPT_DIAGNOSTIC_CHARS = 160
    }
}
