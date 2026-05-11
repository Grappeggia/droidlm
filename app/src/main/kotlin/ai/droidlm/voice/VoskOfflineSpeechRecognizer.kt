package ai.droidlm.voice

import ai.droidlm.intent.SpeechTextNormalizer
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
import kotlin.math.log10
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

    private data class AudioRecordHandle(
        val recorder: AudioRecord,
        val fields: Map<String, Any?>
    )

    @Volatile private var activeAudioRecord: AudioRecord? = null
    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var activeDiagnosticSessionId: String? = null
    @Volatile private var activeStartedAtMs: Long = 0L
    @Volatile private var activeCapturedBytes: Long = 0L

    private val modelLock = Any()
    private var cachedModel: Model? = null

    open fun supportsLanguage(languageTag: String): Boolean {
        val locale = Locale.forLanguageTag(languageTag)
        val language = locale.language.ifBlank { languageTag.substringBefore('-') }
        return language.equals("en", ignoreCase = true)
    }

    open fun stopCurrent(): Boolean {
        activeAudioRecord ?: return false
        stopRequested = true
        diagnostics.record(
            activeDiagnosticSessionId,
            "vosk_stop_requested",
            mapOf(
                "recordingAgeMs" to activeRecordingAgeMs(),
                "capturedBytes" to activeCapturedBytes,
                "audioDurationMs" to pcmDurationMs(activeCapturedBytes)
            )
        )
        return true
    }

    open fun cancelCurrent(): Boolean {
        val recorder = activeAudioRecord ?: return false
        cancelRequested = true
        stopRequested = true
        diagnostics.record(
            activeDiagnosticSessionId,
            "vosk_cancel_requested",
            mapOf(
                "recordingAgeMs" to activeRecordingAgeMs(),
                "capturedBytes" to activeCapturedBytes,
                "audioDurationMs" to pcmDurationMs(activeCapturedBytes)
            )
        )
        runCatching { recorder.stop() }
        return true
    }

    open suspend fun preloadModel(languageTag: String = Locale.getDefault().toLanguageTag(), source: String = "app_start"): Boolean = withContext(Dispatchers.IO) {
        if (!supportsLanguage(languageTag)) {
            diagnostics.record(null, "vosk_preload_skipped", mapOf("language" to languageTag, "source" to source, "reason" to "unsupported_language"))
            return@withContext false
        }
        val cachedBefore = isModelCached()
        val markerExistsBefore = modelReadyMarkerFile().isFile
        val startedAt = System.currentTimeMillis()
        diagnostics.record(
            null,
            "vosk_preload_started",
            mapOf(
                "language" to languageTag,
                "source" to source,
                "assetModel" to ASSET_MODEL_NAME,
                "modelCachedBefore" to cachedBefore,
                "modelReadyMarkerExistsBefore" to markerExistsBefore,
                "coldStart" to !cachedBefore
            )
        )
        runCatching { loadModel() }
            .onSuccess {
                diagnostics.record(
                    null,
                    "vosk_preload_succeeded",
                    mapOf(
                        "language" to languageTag,
                        "source" to source,
                        "assetModel" to ASSET_MODEL_NAME,
                        "modelCachedBefore" to cachedBefore,
                        "modelReadyMarkerExistsBefore" to markerExistsBefore,
                        "coldStart" to !cachedBefore,
                        "durationMs" to (System.currentTimeMillis() - startedAt)
                    )
                )
            }
            .onFailure { error ->
                diagnostics.record(
                    null,
                    "vosk_preload_failed",
                    mapOf(
                        "language" to languageTag,
                        "source" to source,
                        "assetModel" to ASSET_MODEL_NAME,
                        "modelCachedBefore" to cachedBefore,
                        "modelReadyMarkerExistsBefore" to markerExistsBefore,
                        "durationMs" to (System.currentTimeMillis() - startedAt),
                        "errorClass" to error::class.java.name,
                        "message" to error.message
                    )
                )
            }
            .isSuccess
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
        val modelCachedBefore = isModelCached()
        val modelReadyMarkerExistsBefore = modelReadyMarkerFile().isFile
        val modelLoadStartedAt = System.currentTimeMillis()
        diagnostics.record(
            diagnosticSessionId,
            "vosk_model_loading",
            mapOf(
                "language" to languageTag,
                "assetModel" to ASSET_MODEL_NAME,
                "modelCachedBefore" to modelCachedBefore,
                "modelReadyMarkerExistsBefore" to modelReadyMarkerExistsBefore,
                "coldStart" to !modelCachedBefore
            )
        )
        val model = loadModel()
        diagnostics.record(
            diagnosticSessionId,
            "vosk_model_ready",
            mapOf(
                "model" to ASSET_MODEL_NAME,
                "modelCachedBefore" to modelCachedBefore,
                "modelReadyMarkerExistsBefore" to modelReadyMarkerExistsBefore,
                "coldStart" to !modelCachedBefore,
                "loadDurationMs" to (System.currentTimeMillis() - modelLoadStartedAt)
            )
        )

        val audioRecord = createAudioRecord()
        val recorder = audioRecord.recorder
        diagnostics.record(diagnosticSessionId, "vosk_audio_record_created", audioRecord.fields)
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        val acceptedSegments = mutableListOf<String>()
        var lastPartial = ""
        var peakRms = 0.0
        var rmsSum = 0.0
        var rmsCount = 0
        var noiseRmsSum = 0.0
        var noiseRmsCount = 0
        var speechRmsSum = 0.0
        var speechRmsCount = 0
        var lastRmsLogAt = 0L
        var speechStarted = false
        var firstSpeechAt: Long? = null
        var lastSpeechAt: Long? = null
        val startedAt = System.currentTimeMillis()
        var capturedBytes = 0L
        var readCount = 0
        var zeroReadCount = 0
        var negativeReadCount = 0
        var lastReadAt: Long? = null
        var maxReadGapMs = 0L
        var totalReadGapMs = 0L
        var slowReadGapCount = 0
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
        activeDiagnosticSessionId = diagnosticSessionId
        activeStartedAtMs = startedAt
        activeCapturedBytes = 0L

        try {
            recorder.startRecording()
            callbacks.onReady()
            val recordingReadyAt = System.currentTimeMillis()
            val modelLoadDurationMs = recordingReadyAt - modelLoadStartedAt
            logs.log(ActionLogType.RECORDING_STARTED, "Built-in offline speech ready")
            diagnostics.record(diagnosticSessionId, "vosk_ready", mapOf("sampleRate" to SAMPLE_RATE))
            diagnostics.record(
                diagnosticSessionId,
                "speech_ready_to_record",
                mapOf(
                    "provider" to VOSK_PROVIDER_LABEL,
                    "sampleRate" to SAMPLE_RATE,
                    "modelCachedBefore" to modelCachedBefore,
                    "modelLoadDurationMs" to modelLoadDurationMs,
                    "captureStartDelayMs" to (recordingReadyAt - startedAt)
                )
            )

            val buffer = ByteArray(BUFFER_BYTES)
            while (System.currentTimeMillis() - startedAt < maxDurationMs) {
                val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(0)
                if (read <= 0) {
                    if (read == 0) zeroReadCount += 1 else negativeReadCount += 1
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (cancelRequested || (stopRequested && elapsed >= MIN_STOP_DURATION_MS)) break
                    continue
                }
                val now = System.currentTimeMillis()
                lastReadAt?.let { previousReadAt ->
                    val readGapMs = now - previousReadAt
                    totalReadGapMs += readGapMs
                    if (readGapMs > maxReadGapMs) maxReadGapMs = readGapMs
                    if (readGapMs >= SLOW_READ_GAP_MS) slowReadGapCount += 1
                }
                lastReadAt = now
                readCount += 1
                capturedBytes += read.toLong()
                activeCapturedBytes = capturedBytes
                runCatching { audioCaptureOutput?.write(buffer, 0, read) }
                val rms = pcmRms(buffer, read)
                if (rms > peakRms) peakRms = rms
                rmsSum += rms
                rmsCount += 1
                if (speechStarted || rms >= SPEECH_RMS_THRESHOLD) {
                    speechRmsSum += rms
                    speechRmsCount += 1
                } else {
                    noiseRmsSum += rms
                    noiseRmsCount += 1
                }
                if (now - lastRmsLogAt >= RMS_LOG_INTERVAL_MS) {
                    lastRmsLogAt = now
                    diagnostics.record(
                        diagnosticSessionId,
                        "vosk_rms",
                        mapOf("rms" to rounded(rms), "peakRms" to rounded(peakRms), "speechStarted" to speechStarted, "speechRmsThreshold" to SPEECH_RMS_THRESHOLD)
                    )
                }
                if (rms >= SPEECH_RMS_THRESHOLD) {
                    if (!speechStarted) firstSpeechAt = now
                    speechStarted = true
                    lastSpeechAt = now
                }

                if (recognizer.acceptWaveForm(buffer, read)) {
                    val segmentJson = recognizer.result
                    val text = parseText(segmentJson)
                    if (text.isNotBlank()) {
                        acceptedSegments += text
                        callbacks.onPartial(text)
                        diagnostics.record(diagnosticSessionId, "vosk_segment", transcriptFields(text) + confidenceFields(segmentJson))
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
                val stopAfterTail = stopRequested && elapsed >= MIN_STOP_DURATION_MS
                if (cancelRequested || stopAfterTail || silentLongEnough || noSpeechTimeout) break
            }

            val finalResultJson = recognizer.finalResult
            val finalText = parseText(finalResultJson)
            val transcript = (acceptedSegments + finalText)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { lastPartial }
                .trim()
            val durationMs = System.currentTimeMillis() - startedAt
            val lastSpeechAtSnapshot = lastSpeechAt
            diagnostics.record(
                diagnosticSessionId,
                "audio_capture_summary",
                audioCaptureSummaryFields(
                    wallDurationMs = durationMs,
                    capturedBytes = capturedBytes,
                    readCount = readCount,
                    zeroReadCount = zeroReadCount,
                    negativeReadCount = negativeReadCount,
                    maxReadGapMs = maxReadGapMs,
                    totalReadGapMs = totalReadGapMs,
                    slowReadGapCount = slowReadGapCount
                )
            )
            diagnostics.record(diagnosticSessionId, "transcript_quality", transcriptQualityFields(transcript))
            diagnostics.record(
                diagnosticSessionId,
                "vosk_final",
                transcriptFields(transcript) + confidenceFields(finalResultJson) + rmsSummaryFields(
                    rmsSum = rmsSum,
                    rmsCount = rmsCount,
                    noiseRmsSum = noiseRmsSum,
                    noiseRmsCount = noiseRmsCount,
                    speechRmsSum = speechRmsSum,
                    speechRmsCount = speechRmsCount
                ) + mapOf(
                    "provider" to VOSK_PROVIDER_LABEL,
                    "durationMs" to durationMs,
                    "speechStarted" to speechStarted,
                    "firstSpeechMs" to firstSpeechAt?.let { it - startedAt },
                    "lastSpeechMs" to lastSpeechAtSnapshot?.let { it - startedAt },
                    "tailSilenceMs" to lastSpeechAtSnapshot?.let { System.currentTimeMillis() - it },
                    "speechRmsThreshold" to SPEECH_RMS_THRESHOLD,
                    "initialNoSpeechTimeoutMs" to INITIAL_NO_SPEECH_TIMEOUT_MS,
                    "silenceAfterSpeechMs" to SILENCE_AFTER_SPEECH_MS,
                    "stopRequested" to stopRequested,
                    "cancelRequested" to cancelRequested,
                    "peakRms" to rounded(peakRms),
                    "readCount" to readCount,
                    "zeroReadCount" to zeroReadCount,
                    "negativeReadCount" to negativeReadCount,
                    "capturedBytes" to capturedBytes,
                    "audioDurationMs" to pcmDurationMs(capturedBytes)
                )
            )
            if (cancelRequested) throw IllegalStateException("Built-in offline speech cancelled.")
            if (transcript.isBlank()) throw IllegalStateException("Built-in offline speech did not hear a command.")
            logs.log(ActionLogType.RECORDING_STOPPED, "Built-in offline speech stopped after ${durationMs}ms")
            transcript
        } catch (error: Throwable) {
            diagnostics.record(diagnosticSessionId, "vosk_error", mapOf("message" to error.message, "errorClass" to error::class.java.name, "recordingAgeMs" to activeRecordingAgeMs(), "capturedBytes" to capturedBytes, "audioDurationMs" to pcmDurationMs(capturedBytes)))
            throw error
        } finally {
            runCatching { audioCaptureOutput?.close() }
            audioCaptureFile?.let { file ->
                if (file.length() > 0L) {
                    diagnostics.record(diagnosticSessionId, "debug_audio_capture_saved", mapOf("file" to file.name, "bytes" to file.length(), "audioDurationMs" to pcmDurationMs(file.length())))
                } else {
                    file.delete()
                }
            }
            activeAudioRecord = null
            activeDiagnosticSessionId = null
            activeStartedAtMs = 0L
            activeCapturedBytes = 0L
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
        val modelCachedBefore = isModelCached()
        val modelLoadStartedAt = System.currentTimeMillis()
        diagnostics.record(diagnosticSessionId, "vosk_file_model_loading", mapOf("bytes" to pcm.size, "sampleRate" to sampleRate, "audioDurationMs" to pcmDurationMs(pcm.size.toLong()), "modelCachedBefore" to modelCachedBefore, "coldStart" to !modelCachedBefore))
        val recognizer = Recognizer(loadModel(), sampleRate)
        diagnostics.record(diagnosticSessionId, "vosk_file_model_ready", mapOf("model" to ASSET_MODEL_NAME, "loadDurationMs" to (System.currentTimeMillis() - modelLoadStartedAt), "modelCachedBefore" to modelCachedBefore, "coldStart" to !modelCachedBefore))
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
            val finalResultJson = recognizer.finalResult
            val finalText = parseText(finalResultJson)
            val transcript = (segments + finalText).filter { it.isNotBlank() }.joinToString(" ").trim()
            diagnostics.record(diagnosticSessionId, "vosk_file_final", transcriptFields(transcript) + confidenceFields(finalResultJson) + mapOf("provider" to VOSK_PROVIDER_LABEL, "audioDurationMs" to pcmDurationMs(pcm.size.toLong())))
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
    private fun createAudioRecord(): AudioRecordHandle {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "Could not initialize microphone buffer for built-in offline speech." }
        val bufferSize = maxOf(minBuffer, BUFFER_BYTES * 2)
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
        val fields = mapOf(
            "audioSource" to "VOICE_RECOGNITION",
            "sampleRate" to SAMPLE_RATE,
            "channelMask" to "CHANNEL_IN_MONO",
            "encoding" to "PCM_16BIT",
            "minBufferBytes" to minBuffer,
            "bufferSizeBytes" to bufferSize,
            "recorderState" to recorder.state,
            "recorderSampleRate" to recorder.sampleRate,
            "recorderChannelCount" to recorder.channelCount,
            "recorderAudioFormat" to recorder.audioFormat
        )
        return AudioRecordHandle(recorder, fields)
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

    private fun activeRecordingAgeMs(): Long =
        activeStartedAtMs.takeIf { it > 0L }?.let { System.currentTimeMillis() - it } ?: 0L

    private fun pcmDurationMs(bytes: Long): Long =
        (bytes * 1000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE)


    private fun isModelCached(): Boolean = synchronized(modelLock) { cachedModel != null }

    private fun modelReadyMarkerFile(): File = File(context.filesDir, "vosk/$ASSET_MODEL_NAME/$READY_MARKER")

    private fun rmsSummaryFields(
        rmsSum: Double,
        rmsCount: Int,
        noiseRmsSum: Double,
        noiseRmsCount: Int,
        speechRmsSum: Double,
        speechRmsCount: Int
    ): Map<String, Any?> {
        val averageRms = average(rmsSum, rmsCount)
        val averageNoiseRms = average(noiseRmsSum, noiseRmsCount)
        val averageSpeechRms = average(speechRmsSum, speechRmsCount)
        val snrDb = if (averageNoiseRms != null && averageNoiseRms > 0.0 && averageSpeechRms != null && averageSpeechRms > 0.0) {
            20.0 * log10(averageSpeechRms / averageNoiseRms)
        } else {
            null
        }
        return mapOf(
            "rmsSampleCount" to rmsCount,
            "averageRms" to averageRms?.let(::rounded),
            "noiseRmsSampleCount" to noiseRmsCount,
            "averageNoiseRms" to averageNoiseRms?.let(::rounded),
            "speechRmsSampleCount" to speechRmsCount,
            "averageSpeechRms" to averageSpeechRms?.let(::rounded),
            "snrDb" to snrDb?.let(::rounded)
        )
    }

    private fun average(sum: Double, count: Int): Double? = if (count <= 0) null else sum / count

    private fun confidenceFields(json: String): Map<String, Any?> {
        val values = runCatching {
            val result = JSONObject(json).optJSONArray("result") ?: return@runCatching emptyList<Double>()
            (0 until result.length()).mapNotNull { index ->
                result.optJSONObject(index)?.takeIf { it.has("conf") }?.optDouble("conf")
            }
        }.getOrDefault(emptyList())
        return mapOf(
            "confidenceAvailable" to values.isNotEmpty(),
            "confidenceCount" to values.size,
            "averageConfidence" to average(values.sum(), values.size)?.let(::rounded),
            "minConfidence" to values.minOrNull()?.let(::rounded),
            "maxConfidence" to values.maxOrNull()?.let(::rounded)
        )
    }

    private fun audioCaptureSummaryFields(
        wallDurationMs: Long,
        capturedBytes: Long,
        readCount: Int,
        zeroReadCount: Int,
        negativeReadCount: Int,
        maxReadGapMs: Long,
        totalReadGapMs: Long,
        slowReadGapCount: Int
    ): Map<String, Any?> {
        val audioDurationMs = pcmDurationMs(capturedBytes)
        val captureEfficiency = if (wallDurationMs > 0L) audioDurationMs.toDouble() / wallDurationMs.toDouble() else null
        val bytesPerSecond = if (wallDurationMs > 0L) capturedBytes * 1000L / wallDurationMs else 0L
        val readGapSamples = (readCount - 1).coerceAtLeast(0)
        return mapOf(
            "wallDurationMs" to wallDurationMs,
            "audioDurationMs" to audioDurationMs,
            "captureEfficiency" to captureEfficiency?.let(::rounded),
            "capturedBytes" to capturedBytes,
            "bytesPerSecond" to bytesPerSecond,
            "readCount" to readCount,
            "zeroReadCount" to zeroReadCount,
            "negativeReadCount" to negativeReadCount,
            "maxReadGapMs" to maxReadGapMs,
            "averageReadGapMs" to average(totalReadGapMs.toDouble(), readGapSamples)?.let(::rounded),
            "slowReadGapCount" to slowReadGapCount,
            "slowReadGapThresholdMs" to SLOW_READ_GAP_MS
        )
    }

    private fun transcriptQualityFields(transcript: String): Map<String, Any?> {
        val normalized = SpeechTextNormalizer.normalizeForRecognition(transcript)
        val words = normalized.split(' ').filter { it.isNotBlank() }
        val quality = when {
            normalized.isBlank() -> "blank"
            normalized in setOf("open", "launch", "start") -> "ambiguous_open"
            words.size <= 1 -> "single_word"
            normalized.length < 6 -> "short"
            else -> "normal"
        }
        return mapOf(
            "transcriptLength" to transcript.length,
            "normalizedLength" to normalized.length,
            "wordCount" to words.size,
            "uniqueWordCount" to words.toSet().size,
            "quality" to quality,
            "ambiguousOpenCommand" to (normalized in setOf("open", "launch", "start"))
        )
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
        const val BYTES_PER_SAMPLE = 2
        const val BUFFER_BYTES = 4_096
        const val MIN_DURATION_MS = 1_000L
        const val MIN_STOP_DURATION_MS = 1_500L
        const val INITIAL_NO_SPEECH_TIMEOUT_MS = 8_000L
        const val SILENCE_AFTER_SPEECH_MS = 1_400L
        const val SPEECH_RMS_THRESHOLD = 350.0
        const val RMS_LOG_INTERVAL_MS = 500L
        const val SLOW_READ_GAP_MS = 250L
        const val VOSK_PROVIDER_LABEL = "Built-in offline English speech"
        const val MAX_TRANSCRIPT_DIAGNOSTIC_CHARS = 160
    }
}
