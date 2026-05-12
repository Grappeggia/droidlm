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
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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

    private data class AudioChunk(
        val bytes: ByteArray,
        val readAtMs: Long
    )

    private data class BatchTranscriptionResult(
        val transcript: String,
        val finalResultJson: String,
        val segmentCount: Int,
        val processedBytes: Long,
        val durationMs: Long
    )

    private class AudioCaptureStats {
        var capturedBytes: Long = 0L
        var readCount: Int = 0
        var zeroReadCount: Int = 0
        var negativeReadCount: Int = 0
        var lastReadAt: Long? = null
        var maxReadGapMs: Long = 0L
        var totalReadGapMs: Long = 0L
        var queueCapacity: Int = 0
        var maxQueueDepth: Int = 0
        var queueOverflowCount: Int = 0
        var queueDroppedChunks: Int = 0
        var queueDroppedBytes: Long = 0L
        var discardedChunks: Int = 0
        var discardedBytes: Long = 0L
        var firstReadDelayMs: Long? = null
        var processedBytes: Long = 0L
        var processedChunks: Int = 0
        var processingTotalMs: Long = 0L
        var processingMaxMs: Long = 0L
        var processingSlowCount: Int = 0
        var maxChunkAgeMs: Long = 0L
        var totalChunkAgeMs: Long = 0L
        var postStopDrainWallMs: Long = 0L
        var slowReadGapCount: Int = 0
        var finishedAtMs: Long = 0L
    }

    private data class AudioCaptureStatsSnapshot(
        val capturedBytes: Long,
        val readCount: Int,
        val zeroReadCount: Int,
        val negativeReadCount: Int,
        val maxReadGapMs: Long,
        val totalReadGapMs: Long,
        val queueCapacity: Int,
        val maxQueueDepth: Int,
        val queueOverflowCount: Int,
        val queueDroppedChunks: Int,
        val queueDroppedBytes: Long,
        val discardedChunks: Int,
        val discardedBytes: Long,
        val firstReadDelayMs: Long?,
        val processedBytes: Long,
        val processedChunks: Int,
        val processingTotalMs: Long,
        val processingMaxMs: Long,
        val processingSlowCount: Int,
        val maxChunkAgeMs: Long,
        val totalChunkAgeMs: Long,
        val postStopDrainWallMs: Long,
        val slowReadGapCount: Int,
        val finishedAtMs: Long
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
        runCatching { activeAudioRecord?.stop() }
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
        val startedAt = SystemClock.elapsedRealtime()
        val audioQueueCapacity = audioQueueCapacityFor(maxDurationMs)
        val queueHighWatermarkDepth = (audioQueueCapacity * 3 / 4).coerceAtLeast(1)
        val audioQueue = LinkedBlockingQueue<AudioChunk>(audioQueueCapacity)
        val capturedPcmLock = Any()
        val capturedPcm = ByteArrayOutputStream()
        val captureFinished = AtomicBoolean(false)
        val captureStopRequested = AtomicBoolean(false)
        val captureStopReason = AtomicReference<String?>(null)
        val statsLock = Any()
        val captureStats = AudioCaptureStats()
        synchronized(statsLock) { captureStats.queueCapacity = audioQueueCapacity }
        var captureThread: Thread? = null
        var slowReadLogCount = 0
        var slowProcessingLogCount = 0
        var queueWatermarkLogAt = 0L
        var postStopDrainStartedAt: Long? = null
        var postStopDrainStartProcessedBytes = 0L

        var joinTimeoutLogged = false
        var liveProcessingAbandonedLogged = false
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

        fun processedBytesSnapshot(): Long = synchronized(statsLock) { captureStats.processedBytes }

        fun markCaptureStopReason(reason: String, fields: Map<String, Any?> = emptyMap()): Boolean {
            val now = SystemClock.elapsedRealtime()
            val marked = captureStopReason.compareAndSet(null, reason)
            if (marked) {
                diagnostics.record(
                    diagnosticSessionId,
                    "audio_capture_stop_reason",
                    mapOf(
                        "reason" to reason,
                        "elapsedMs" to (now - startedAt),
                        "capturedBytes" to activeCapturedBytes,
                        "audioDurationMs" to pcmDurationMs(activeCapturedBytes),
                        "processedBytes" to processedBytesSnapshot(),
                        "queueDepth" to audioQueue.size,
                        "queueCapacity" to audioQueueCapacity
                    ) + fields
                )
            }
            return marked
        }

        fun requestCaptureStop(reason: String, fields: Map<String, Any?> = emptyMap()) {
            markCaptureStopReason(reason, fields)
            captureStopRequested.set(true)
            runCatching { recorder.stop() }
        }

        fun joinCaptureThread(timeoutMs: Long) {
            val thread = captureThread ?: return
            thread.join(timeoutMs)
            if (thread.isAlive && !joinTimeoutLogged) {
                joinTimeoutLogged = true
                diagnostics.record(
                    diagnosticSessionId,
                    "audio_capture_join_timeout",
                    mapOf(
                        "timeoutMs" to timeoutMs,
                        "stopReason" to captureStopReason.get(),
                        "capturedBytes" to activeCapturedBytes,
                        "audioDurationMs" to pcmDurationMs(activeCapturedBytes),
                        "queueDepth" to audioQueue.size,
                        "queueCapacity" to audioQueueCapacity
                    )
                )
            }
        }

        fun recordQueueDepth(depth: Int) {
            synchronized(statsLock) {
                if (depth > captureStats.maxQueueDepth) captureStats.maxQueueDepth = depth
            }
        }

        fun capturedPcmBytes(): ByteArray = synchronized(capturedPcmLock) { capturedPcm.toByteArray() }

        fun liveTranscript(): String = (acceptedSegments + lastPartial)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        fun recordLiveProcessingAbandoned(now: Long) {
            if (liveProcessingAbandonedLogged || audioQueue.isEmpty()) return
            liveProcessingAbandonedLogged = true
            val queueDepth = audioQueue.size
            synchronized(statsLock) {
                captureStats.postStopDrainWallMs = postStopDrainStartedAt?.let { now - it } ?: 0L
            }
            diagnostics.record(
                diagnosticSessionId,
                "vosk_live_processing_abandoned",
                mapOf(
                    "reason" to "capture_first_finalizer",
                    "stopReason" to captureStopReason.get(),
                    "queueDepth" to queueDepth,
                    "queuedAudioMs" to pcmDurationMs(queueDepth.toLong() * BUFFER_BYTES),
                    "capturedBytes" to activeCapturedBytes,
                    "processedBytes" to processedBytesSnapshot(),
                    "liveTranscriptLength" to liveTranscript().length
                )
            )
        }


        try {
            recorder.startRecording()
            val recordingReadyAt = SystemClock.elapsedRealtime()
            val modelLoadDurationMs = System.currentTimeMillis() - modelLoadStartedAt
            logs.log(ActionLogType.RECORDING_STARTED, "Built-in offline speech ready")
            diagnostics.record(diagnosticSessionId, "vosk_ready", mapOf("sampleRate" to SAMPLE_RATE))
            diagnostics.record(
                diagnosticSessionId,
                "vosk_audio_recording_started",
                mapOf(
                    "recorderRecordingState" to recorder.recordingState,
                    "expectedRecordingState" to AudioRecord.RECORDSTATE_RECORDING,
                    "audioQueueCapacity" to audioQueueCapacity,
                    "audioQueueCapacityMs" to pcmDurationMs(audioQueueCapacity.toLong() * BUFFER_BYTES)
                )
            )
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Built-in offline speech could not start microphone recording.")
            }
            callbacks.onReady()
            diagnostics.record(
                diagnosticSessionId,
                "speech_ready_to_record",
                mapOf(
                    "provider" to VOSK_PROVIDER_LABEL,
                    "sampleRate" to SAMPLE_RATE,
                    "modelCachedBefore" to modelCachedBefore,
                    "modelLoadDurationMs" to modelLoadDurationMs,
                    "captureStartDelayMs" to (recordingReadyAt - startedAt),
                    "audioQueueCapacity" to audioQueueCapacity,
                    "audioQueueCapacityMs" to pcmDurationMs(audioQueueCapacity.toLong() * BUFFER_BYTES)
                )
            )

            captureThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                diagnostics.record(
                    diagnosticSessionId,
                    "audio_capture_thread_started",
                    mapOf(
                        "threadPriority" to runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull(),
                        "bufferBytes" to BUFFER_BYTES,
                        "bufferAudioMs" to pcmDurationMs(BUFFER_BYTES.toLong()),
                        "queueCapacity" to audioQueueCapacity,
                        "queueCapacityMs" to pcmDurationMs(audioQueueCapacity.toLong() * BUFFER_BYTES),
                        "maxDurationMs" to maxDurationMs
                    )
                )
                val buffer = ByteArray(BUFFER_BYTES)
                try {
                    while (!captureStopRequested.get()) {
                        val beforeRead = SystemClock.elapsedRealtime()
                        if (beforeRead - startedAt >= maxDurationMs) {
                            markCaptureStopReason("max_duration")
                            break
                        }
                        val read = runCatching { recorder.read(buffer, 0, buffer.size) }
                            .getOrElse { error ->
                                if (!captureStopRequested.get() && !cancelRequested) {
                                    diagnostics.record(
                                        diagnosticSessionId,
                                        "vosk_audio_read_failed",
                                        mapOf("message" to error.message, "errorClass" to error::class.java.name)
                                    )
                                    requestCaptureStop("read_error", mapOf("errorClass" to error::class.java.name, "message" to error.message))
                                }
                                0
                            }
                        val now = SystemClock.elapsedRealtime()
                        if (read <= 0) {
                            synchronized(statsLock) {
                                if (read == 0) captureStats.zeroReadCount += 1 else captureStats.negativeReadCount += 1
                            }
                            val elapsed = now - startedAt
                            if (cancelRequested) {
                                requestCaptureStop("cancel")
                                break
                            }
                            if (stopRequested && elapsed >= MIN_STOP_DURATION_MS) {
                                requestCaptureStop("user_stop")
                                break
                            }
                            continue
                        }

                        val chunkBytes = buffer.copyOf(read)
                        var readGapMs: Long? = null
                        var firstReadDelayMs: Long? = null
                        synchronized(statsLock) {
                            if (captureStats.firstReadDelayMs == null) {
                                captureStats.firstReadDelayMs = now - startedAt
                                firstReadDelayMs = captureStats.firstReadDelayMs
                            }
                            captureStats.lastReadAt?.let { previousReadAt ->
                                readGapMs = now - previousReadAt
                                captureStats.totalReadGapMs += readGapMs ?: 0L
                                if ((readGapMs ?: 0L) > captureStats.maxReadGapMs) captureStats.maxReadGapMs = readGapMs ?: 0L
                                if ((readGapMs ?: 0L) >= SLOW_READ_GAP_MS) captureStats.slowReadGapCount += 1
                            }
                            captureStats.lastReadAt = now
                            captureStats.readCount += 1
                            captureStats.capturedBytes += read.toLong()
                            activeCapturedBytes = captureStats.capturedBytes
                        }
                        firstReadDelayMs?.let { delayMs ->
                            diagnostics.record(
                                diagnosticSessionId,
                                "audio_capture_first_read",
                                mapOf("firstReadDelayMs" to delayMs, "bytes" to read, "audioDurationMs" to pcmDurationMs(read.toLong()))
                            )
                        }
                        readGapMs?.takeIf { it >= SLOW_READ_GAP_MS && slowReadLogCount < MAX_SLOW_READ_LOG_EVENTS }?.let { gapMs ->
                            slowReadLogCount += 1
                            diagnostics.record(
                                diagnosticSessionId,
                                "audio_capture_slow_read",
                                mapOf(
                                    "readGapMs" to gapMs,
                                    "readCount" to synchronized(statsLock) { captureStats.readCount },
                                    "capturedBytes" to activeCapturedBytes,
                                    "audioDurationMs" to pcmDurationMs(activeCapturedBytes),
                                    "queueDepth" to audioQueue.size,
                                    "queueCapacity" to audioQueueCapacity,
                                    "recorderRecordingState" to recorder.recordingState
                                )
                            )
                        }
                        synchronized(capturedPcmLock) { capturedPcm.write(chunkBytes, 0, chunkBytes.size) }
                        runCatching { audioCaptureOutput?.write(chunkBytes) }
                        val offered = audioQueue.offer(AudioChunk(chunkBytes, now))
                        val queueDepth = audioQueue.size
                        recordQueueDepth(queueDepth)
                        if (!offered) {
                            synchronized(statsLock) {
                                captureStats.queueOverflowCount += 1
                                captureStats.queueDroppedChunks += 1
                                captureStats.queueDroppedBytes += read.toLong()
                            }
                            diagnostics.record(
                                diagnosticSessionId,
                                "audio_queue_overflow",
                                mapOf(
                                    "queueDepth" to queueDepth,
                                    "queueCapacity" to audioQueueCapacity,
                                    "droppedBytes" to read,
                                    "capturedBytes" to activeCapturedBytes,
                                    "audioDurationMs" to pcmDurationMs(activeCapturedBytes),
                                    "processedBytes" to processedBytesSnapshot(),
                                    "finalizer" to "capture_first_batch"
                                )
                            )
                            continue
                        }
                        if (queueDepth >= queueHighWatermarkDepth && now - queueWatermarkLogAt >= QUEUE_WATERMARK_LOG_INTERVAL_MS) {
                            queueWatermarkLogAt = now
                            diagnostics.record(
                                diagnosticSessionId,
                                "audio_queue_watermark",
                                mapOf(
                                    "queueDepth" to queueDepth,
                                    "queueCapacity" to audioQueueCapacity,
                                    "queuedAudioMs" to pcmDurationMs(queueDepth.toLong() * BUFFER_BYTES),
                                    "capturedBytes" to activeCapturedBytes,
                                    "processedBytes" to processedBytesSnapshot()
                                )
                            )
                        }

                        val elapsed = now - startedAt
                        if (cancelRequested) {
                            requestCaptureStop("cancel")
                            break
                        }
                        if (stopRequested && elapsed >= MIN_STOP_DURATION_MS) {
                            requestCaptureStop("user_stop")
                            break
                        }
                    }
                } finally {
                    synchronized(statsLock) {
                        if (captureStats.finishedAtMs == 0L) captureStats.finishedAtMs = SystemClock.elapsedRealtime()
                    }
                    markCaptureStopReason(if (captureStopRequested.get()) "capture_thread_exit" else "max_duration")
                    val stats = captureStatsSnapshot(captureStats, statsLock)
                    diagnostics.record(
                        diagnosticSessionId,
                        "audio_capture_thread_exit",
                        audioQueueSummaryFields(stats) + audioCaptureSummaryFields(
                            wallDurationMs = ((stats.finishedAtMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()) - startedAt).coerceAtLeast(0L),
                            capturedBytes = stats.capturedBytes,
                            readCount = stats.readCount,
                            zeroReadCount = stats.zeroReadCount,
                            negativeReadCount = stats.negativeReadCount,
                            maxReadGapMs = stats.maxReadGapMs,
                            totalReadGapMs = stats.totalReadGapMs,
                            slowReadGapCount = stats.slowReadGapCount
                        ) + mapOf("stopReason" to captureStopReason.get())
                    )
                    captureFinished.set(true)
                }
            }, "DroidLM-VoskAudioCapture").apply { start() }

            while (!captureFinished.get() || audioQueue.isNotEmpty()) {
                val now = SystemClock.elapsedRealtime()
                val stopObserved = stopRequested || captureStopRequested.get()
                if (stopObserved && postStopDrainStartedAt == null) {
                    postStopDrainStartedAt = now
                    postStopDrainStartProcessedBytes = processedBytesSnapshot()
                    diagnostics.record(
                        diagnosticSessionId,
                        "audio_post_stop_drain_started",
                        mapOf(
                            "stopReason" to captureStopReason.get(),
                            "queueDepth" to audioQueue.size,
                            "queueCapacity" to audioQueueCapacity,
                            "processedBytes" to postStopDrainStartProcessedBytes,
                            "capturedBytes" to activeCapturedBytes
                        )
                    )
                }
                if (stopObserved && captureFinished.get()) {
                    recordLiveProcessingAbandoned(now)
                    break
                }
                val chunk = audioQueue.poll(50, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    val read = chunk.bytes.size
                    val chunkAgeMs = (now - chunk.readAtMs).coerceAtLeast(0L)
                    val rms = pcmRms(chunk.bytes, read)
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
                        if (!speechStarted) firstSpeechAt = chunk.readAtMs
                        speechStarted = true
                        lastSpeechAt = chunk.readAtMs
                    }

                    val processingStartedAt = SystemClock.elapsedRealtime()
                    if (recognizer.acceptWaveForm(chunk.bytes, read)) {
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
                            lastSpeechAt = chunk.readAtMs
                            callbacks.onPartial(partial)
                            diagnostics.record(diagnosticSessionId, "vosk_partial", transcriptFields(partial))
                        }
                    }
                    val processingMs = SystemClock.elapsedRealtime() - processingStartedAt
                    synchronized(statsLock) {
                        captureStats.processedChunks += 1
                        captureStats.processedBytes += read.toLong()
                        captureStats.processingTotalMs += processingMs
                        if (processingMs > captureStats.processingMaxMs) captureStats.processingMaxMs = processingMs
                        if (processingMs >= SLOW_PROCESSING_MS) captureStats.processingSlowCount += 1
                        captureStats.totalChunkAgeMs += chunkAgeMs
                        if (chunkAgeMs > captureStats.maxChunkAgeMs) captureStats.maxChunkAgeMs = chunkAgeMs
                    }

                    if (processingMs >= SLOW_PROCESSING_MS && slowProcessingLogCount < MAX_SLOW_PROCESSING_LOG_EVENTS) {
                        slowProcessingLogCount += 1
                        diagnostics.record(
                            diagnosticSessionId,
                            "vosk_processing_slow",
                            mapOf(
                                "processingMs" to processingMs,
                                "chunkAgeMs" to chunkAgeMs,
                                "queueDepth" to audioQueue.size,
                                "queueCapacity" to audioQueueCapacity,
                                "processedBytes" to processedBytesSnapshot(),
                                "capturedBytes" to activeCapturedBytes
                            )
                        )
                    }
                }

                val elapsed = now - startedAt
                val silentLongEnough = speechStarted && elapsed >= MIN_DURATION_MS && lastSpeechAt?.let { now - it >= SILENCE_AFTER_SPEECH_MS } == true
                val noSpeechTimeout = !speechStarted && elapsed >= INITIAL_NO_SPEECH_TIMEOUT_MS
                val stopAfterTail = stopRequested && elapsed >= MIN_STOP_DURATION_MS
                when {
                    cancelRequested -> requestCaptureStop("cancel")
                    stopAfterTail -> requestCaptureStop("user_stop")
                    silentLongEnough -> requestCaptureStop("silence_after_speech")
                    noSpeechTimeout -> requestCaptureStop("initial_no_speech_timeout")
                }

                if (captureStopRequested.get() && captureFinished.get()) {
                    recordLiveProcessingAbandoned(now)
                    break
                }
            }
            requestCaptureStop("recognition_loop_completed")
            postStopDrainStartedAt?.let { drainStartedAt ->
                synchronized(statsLock) {
                    if (captureStats.postStopDrainWallMs == 0L) {
                        captureStats.postStopDrainWallMs = (SystemClock.elapsedRealtime() - drainStartedAt).coerceAtLeast(0L)
                    }
                }
            }
            joinCaptureThread(2_000)

            val preFinalStats = captureStatsSnapshot(captureStats, statsLock)
            val capturedPcmBytes = capturedPcmBytes()
            diagnostics.record(
                diagnosticSessionId,
                "vosk_capture_first_final_started",
                mapOf(
                    "stopReason" to captureStopReason.get(),
                    "capturedBytes" to capturedPcmBytes.size,
                    "audioDurationMs" to pcmDurationMs(capturedPcmBytes.size.toLong()),
                    "batchChunkBytes" to BATCH_BUFFER_BYTES,
                    "liveProcessedBytes" to preFinalStats.processedBytes,
                    "liveProcessedAudioDurationMs" to pcmDurationMs(preFinalStats.processedBytes),
                    "liveProcessedChunks" to preFinalStats.processedChunks,
                    "liveQueueDepth" to audioQueue.size,
                    "liveQueueDroppedBytes" to preFinalStats.queueDroppedBytes,
                    "liveTranscriptLength" to liveTranscript().length
                )
            )
            val batchResult = transcribeCapturedPcm(model, capturedPcmBytes, SAMPLE_RATE.toFloat())
            diagnostics.record(
                diagnosticSessionId,
                "vosk_capture_first_final_completed",
                mapOf(
                    "durationMs" to batchResult.durationMs,
                    "processedBytes" to batchResult.processedBytes,
                    "processedAudioDurationMs" to pcmDurationMs(batchResult.processedBytes),
                    "segmentCount" to batchResult.segmentCount,
                    "transcriptLength" to batchResult.transcript.length,
                    "stopReason" to captureStopReason.get()
                )
            )
            val finalResultJson = batchResult.finalResultJson
            val transcript = batchResult.transcript
            val stats = captureStatsSnapshot(captureStats, statsLock)
            val durationMs = ((stats.finishedAtMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()) - startedAt).coerceAtLeast(0L)
            val lastSpeechAtSnapshot = lastSpeechAt
            diagnostics.record(
                diagnosticSessionId,
                "vosk_processing_summary",
                audioProcessingSummaryFields(stats) + audioQueueSummaryFields(stats) + mapOf(
                    "stopReason" to captureStopReason.get(),
                    "durationMs" to durationMs
                )
            )
            diagnostics.record(
                diagnosticSessionId,
                "audio_capture_summary",
                audioCaptureSummaryFields(
                    wallDurationMs = durationMs,
                    capturedBytes = stats.capturedBytes,
                    readCount = stats.readCount,
                    zeroReadCount = stats.zeroReadCount,
                    negativeReadCount = stats.negativeReadCount,
                    maxReadGapMs = stats.maxReadGapMs,
                    totalReadGapMs = stats.totalReadGapMs,
                    slowReadGapCount = stats.slowReadGapCount
                ) + audioQueueSummaryFields(stats) + audioProcessingSummaryFields(stats) + mapOf("stopReason" to captureStopReason.get())
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
                    "finalizer" to "capture_first_batch",
                    "batchFinalDurationMs" to batchResult.durationMs,
                    "liveTranscriptLength" to liveTranscript().length,
                    "durationMs" to durationMs,
                    "speechStarted" to speechStarted,
                    "firstSpeechMs" to firstSpeechAt?.let { it - startedAt },
                    "lastSpeechMs" to lastSpeechAtSnapshot?.let { it - startedAt },
                    "tailSilenceMs" to lastSpeechAtSnapshot?.let { SystemClock.elapsedRealtime() - it },
                    "speechRmsThreshold" to SPEECH_RMS_THRESHOLD,
                    "initialNoSpeechTimeoutMs" to INITIAL_NO_SPEECH_TIMEOUT_MS,
                    "silenceAfterSpeechMs" to SILENCE_AFTER_SPEECH_MS,
                    "stopRequested" to stopRequested,
                    "cancelRequested" to cancelRequested,
                    "peakRms" to rounded(peakRms),
                    "readCount" to stats.readCount,
                    "zeroReadCount" to stats.zeroReadCount,
                    "negativeReadCount" to stats.negativeReadCount,
                    "capturedBytes" to stats.capturedBytes,
                    "audioDurationMs" to pcmDurationMs(stats.capturedBytes)
                )
            )
            if (cancelRequested) throw IllegalStateException("Built-in offline speech cancelled.")
            if (transcript.isBlank()) throw IllegalStateException("Built-in offline speech did not hear a command.")
            logs.log(ActionLogType.RECORDING_STOPPED, "Built-in offline speech stopped after ${durationMs}ms")
            transcript
        } catch (error: Throwable) {
            val stats = captureStatsSnapshot(captureStats, statsLock)
            diagnostics.record(
                diagnosticSessionId,
                "vosk_error",
                mapOf(
                    "message" to error.message,
                    "errorClass" to error::class.java.name,
                    "recordingAgeMs" to activeRecordingAgeMs(),
                    "capturedBytes" to stats.capturedBytes,
                    "audioDurationMs" to pcmDurationMs(stats.capturedBytes),
                    "stopReason" to captureStopReason.get(),
                    "queueOverflowCount" to stats.queueOverflowCount,
                    "discardedBytes" to stats.discardedBytes
                )
            )
            throw error
        } finally {
            requestCaptureStop("cleanup")
            runCatching { joinCaptureThread(2_000) }
            runCatching { audioCaptureOutput?.flush() }
            runCatching { audioCaptureOutput?.close() }
            val finalStats = captureStatsSnapshot(captureStats, statsLock)
            audioCaptureFile?.let { file ->
                if (file.length() > 0L) {
                    diagnostics.record(
                        diagnosticSessionId,
                        "debug_audio_capture_saved",
                        mapOf(
                            "file" to file.name,
                            "bytes" to file.length(),
                            "audioDurationMs" to pcmDurationMs(file.length()),
                            "expectedCapturedBytes" to finalStats.capturedBytes,
                            "fileLengthMatches" to (file.length() == finalStats.capturedBytes)
                        )
                    )
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

    private fun captureStatsSnapshot(
        stats: AudioCaptureStats,
        lock: Any
    ): AudioCaptureStatsSnapshot = synchronized(lock) {
        AudioCaptureStatsSnapshot(
            capturedBytes = stats.capturedBytes,
            readCount = stats.readCount,
            zeroReadCount = stats.zeroReadCount,
            negativeReadCount = stats.negativeReadCount,
            maxReadGapMs = stats.maxReadGapMs,
            totalReadGapMs = stats.totalReadGapMs,
            slowReadGapCount = stats.slowReadGapCount,
            queueCapacity = stats.queueCapacity,
            maxQueueDepth = stats.maxQueueDepth,
            queueOverflowCount = stats.queueOverflowCount,
            queueDroppedChunks = stats.queueDroppedChunks,
            queueDroppedBytes = stats.queueDroppedBytes,
            discardedChunks = stats.discardedChunks,
            discardedBytes = stats.discardedBytes,
            firstReadDelayMs = stats.firstReadDelayMs,
            processedBytes = stats.processedBytes,
            processedChunks = stats.processedChunks,
            processingTotalMs = stats.processingTotalMs,
            processingMaxMs = stats.processingMaxMs,
            processingSlowCount = stats.processingSlowCount,
            maxChunkAgeMs = stats.maxChunkAgeMs,
            totalChunkAgeMs = stats.totalChunkAgeMs,
            postStopDrainWallMs = stats.postStopDrainWallMs,
            finishedAtMs = stats.finishedAtMs
        )
    }


    private fun transcribeCapturedPcm(
        model: Model,
        pcm: ByteArray,
        sampleRate: Float
    ): BatchTranscriptionResult {
        val recognizer = Recognizer(model, sampleRate)
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            var offset = 0
            val segments = mutableListOf<String>()
            while (offset < pcm.size) {
                val length = minOf(BATCH_BUFFER_BYTES, pcm.size - offset)
                val chunk = pcm.copyOfRange(offset, offset + length)
                if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                    parseText(recognizer.result).takeIf { it.isNotBlank() }?.let { segments += it }
                }
                offset += length
            }
            val finalResultJson = recognizer.finalResult
            val finalText = parseText(finalResultJson)
            val transcript = (segments + finalText)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()
            BatchTranscriptionResult(
                transcript = transcript,
                finalResultJson = finalResultJson,
                segmentCount = segments.size,
                processedBytes = offset.toLong(),
                durationMs = SystemClock.elapsedRealtime() - startedAt
            )
        } finally {
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
        val model = loadModel()
        diagnostics.record(diagnosticSessionId, "vosk_file_model_ready", mapOf("model" to ASSET_MODEL_NAME, "loadDurationMs" to (System.currentTimeMillis() - modelLoadStartedAt), "modelCachedBefore" to modelCachedBefore, "coldStart" to !modelCachedBefore))
        val result = transcribeCapturedPcm(model, pcm, sampleRate)
        diagnostics.record(
            diagnosticSessionId,
            "vosk_file_final",
            transcriptFields(result.transcript) + confidenceFields(result.finalResultJson) + mapOf(
                "provider" to VOSK_PROVIDER_LABEL,
                "audioDurationMs" to pcmDurationMs(pcm.size.toLong()),
                "batchChunkBytes" to BATCH_BUFFER_BYTES,
                "durationMs" to result.durationMs,
                "segmentCount" to result.segmentCount
            )
        )
        result.transcript
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
        activeStartedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it } ?: 0L

    private fun pcmDurationMs(bytes: Long): Long =
        (bytes * 1000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE)

    private fun audioQueueCapacityFor(maxDurationMs: Long): Int {
        val chunkDurationMs = pcmDurationMs(BUFFER_BYTES.toLong()).coerceAtLeast(1L)
        val chunksForDuration = ((maxDurationMs.coerceAtLeast(0L) + chunkDurationMs - 1L) / chunkDurationMs).coerceAtLeast(1L)
        return chunksForDuration.coerceIn(MIN_AUDIO_QUEUE_CAPACITY.toLong(), MAX_AUDIO_QUEUE_CAPACITY.toLong()).toInt()
    }


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

    private fun audioQueueSummaryFields(stats: AudioCaptureStatsSnapshot): Map<String, Any?> = mapOf(
        "queueCapacity" to stats.queueCapacity,
        "queueCapacityMs" to pcmDurationMs(stats.queueCapacity.toLong() * BUFFER_BYTES),
        "maxQueueDepth" to stats.maxQueueDepth,
        "maxQueuedAudioMs" to pcmDurationMs(stats.maxQueueDepth.toLong() * BUFFER_BYTES),
        "queueOverflowCount" to stats.queueOverflowCount,
        "queueDroppedChunks" to stats.queueDroppedChunks,
        "queueDroppedBytes" to stats.queueDroppedBytes,
        "queueDroppedAudioDurationMs" to pcmDurationMs(stats.queueDroppedBytes),
        "discardedChunks" to stats.discardedChunks,
        "discardedBytes" to stats.discardedBytes,
        "discardedAudioDurationMs" to pcmDurationMs(stats.discardedBytes),
        "firstReadDelayMs" to stats.firstReadDelayMs
    )

    private fun audioProcessingSummaryFields(stats: AudioCaptureStatsSnapshot): Map<String, Any?> = mapOf(
        "processedChunks" to stats.processedChunks,
        "processedBytes" to stats.processedBytes,
        "processedAudioDurationMs" to pcmDurationMs(stats.processedBytes),
        "averageProcessingMs" to average(stats.processingTotalMs.toDouble(), stats.processedChunks)?.let(::rounded),
        "maxProcessingMs" to stats.processingMaxMs,
        "slowProcessingCount" to stats.processingSlowCount,
        "slowProcessingThresholdMs" to SLOW_PROCESSING_MS,
        "averageChunkAgeMs" to average(stats.totalChunkAgeMs.toDouble(), stats.processedChunks)?.let(::rounded),
        "maxChunkAgeMs" to stats.maxChunkAgeMs,
        "postStopDrainWallMs" to stats.postStopDrainWallMs,
        "maxPostStopDrainWallMs" to MAX_POST_STOP_DRAIN_WALL_MS,
        "maxPostStopDrainAudioMs" to MAX_POST_STOP_DRAIN_AUDIO_MS
    )

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
        const val BATCH_BUFFER_BYTES = 32_768
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
        const val SLOW_PROCESSING_MS = 250L
        const val MAX_SLOW_READ_LOG_EVENTS = 8
        const val MAX_SLOW_PROCESSING_LOG_EVENTS = 8
        const val QUEUE_WATERMARK_LOG_INTERVAL_MS = 1_000L
        const val MIN_AUDIO_QUEUE_CAPACITY = 8
        const val MAX_AUDIO_QUEUE_CAPACITY = 1_024
        const val MAX_POST_STOP_DRAIN_WALL_MS = 3_000L
        const val MAX_POST_STOP_DRAIN_AUDIO_MS = 2_500L
        const val VOSK_PROVIDER_LABEL = "Built-in offline English speech"
        const val MAX_TRANSCRIPT_DIAGNOSTIC_CHARS = 160
    }
}
