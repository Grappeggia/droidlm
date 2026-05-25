package ai.droidlm.voice

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
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

class SherpaOfflineSpeechRecognizer(
    private val context: Context,
    private val logs: ActionLogRepository,
    private val diagnostics: SpeechDiagnosticsLogger,
    private val model: ModelSpec = ModelSpec.parakeetDownload(),
    private val httpClient: OkHttpClient = sharedHttpClient
) : OfflineSpeechRecognizer {
    override val providerLabel: String = "Sherpa offline English speech"

    @Volatile private var activeRecorder: AudioRecord? = null
    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var activeDiagnosticSessionId: String? = null
    @Volatile private var activeStartedAtMs: Long = 0L
    @Volatile private var activeCapturedBytes: Long = 0L

    private val recognizerLock = Any()
    private var cachedRecognizer: OfflineRecognizer? = null

    override fun supportsLanguage(languageTag: String): Boolean {
        val locale = Locale.forLanguageTag(languageTag)
        val language = locale.language.ifBlank { languageTag.substringBefore('-') }
        return language.equals("en", ignoreCase = true)
    }

    override suspend fun preloadModel(languageTag: String, source: String): Boolean = withContext(Dispatchers.IO) {
        if (!supportsLanguage(languageTag)) {
            diagnostics.record(null, "sherpa_preload_skipped", mapOf("language" to languageTag, "source" to source, "reason" to "unsupported_language"))
            return@withContext false
        }
        val startedAt = System.currentTimeMillis()
        diagnostics.record(null, "sherpa_preload_started", mapOf("language" to languageTag, "source" to source, "model" to model.name, "storage" to model.storage.diagnosticName))
        runCatching { loadRecognizer(null) }
            .onSuccess { diagnostics.record(null, "sherpa_preload_succeeded", mapOf("language" to languageTag, "source" to source, "model" to model.name, "storage" to model.storage.diagnosticName, "durationMs" to (System.currentTimeMillis() - startedAt))) }
            .onFailure { error -> diagnostics.record(null, "sherpa_preload_failed", mapOf("language" to languageTag, "source" to source, "model" to model.name, "storage" to model.storage.diagnosticName, "durationMs" to (System.currentTimeMillis() - startedAt), "errorClass" to error::class.java.name, "message" to error.message)) }
            .isSuccess
    }

    override suspend fun recognizeCommand(
        languageTag: String,
        maxDurationMs: Long,
        diagnosticSessionId: String?,
        callbacks: VoskOfflineSpeechRecognizer.Callbacks
    ): String = withContext(Dispatchers.IO) {
        if (!supportsLanguage(languageTag)) throw IllegalStateException("Sherpa offline speech currently supports English only.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("Microphone permission is required for Sherpa offline speech.")
        }

        callbacks.onStarting()
        val recorder = createAudioRecord()
        val pcm = ByteArrayOutputStream()
        stopRequested = false
        cancelRequested = false
        activeRecorder = recorder
        activeDiagnosticSessionId = diagnosticSessionId
        activeStartedAtMs = SystemClock.elapsedRealtime()
        activeCapturedBytes = 0L

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Sherpa offline speech could not start microphone recording.")
            }
            callbacks.onReady()
            logs.log(ActionLogType.RECORDING_STARTED, "Sherpa offline speech ready")
            diagnostics.record(diagnosticSessionId, "sherpa_audio_recording_started", mapOf("sampleRate" to SAMPLE_RATE, "bufferBytes" to BUFFER_BYTES, "maxDurationMs" to maxDurationMs, "model" to model.name))

            val buffer = ByteArray(BUFFER_BYTES)
            var speechStarted = false
            var firstSpeechAtMs = 0L
            var lastSpeechAtMs = 0L
            var stoppingSignalled = false
            val startedAt = SystemClock.elapsedRealtime()
            val originalPriority = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            try {
                while (!cancelRequested) {
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - startedAt
                    if (elapsed >= maxDurationMs) break
                    if (stopRequested && elapsed >= MIN_STOP_DURATION_MS) break
                    if (speechStarted && now - lastSpeechAtMs >= SILENCE_AFTER_SPEECH_MS) {
                        if (!stoppingSignalled) {
                            callbacks.onStopping("silence_after_speech")
                            stoppingSignalled = true
                        }
                        break
                    }

                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    pcm.write(buffer, 0, read)
                    activeCapturedBytes += read.toLong()
                    val rms = pcmRms(buffer, read)
                    if (rms >= SPEECH_RMS_THRESHOLD) {
                        if (!speechStarted) firstSpeechAtMs = now
                        speechStarted = true
                        lastSpeechAtMs = now
                    }
                    if (!speechStarted && elapsed >= INITIAL_SILENCE_MS) break
                    if (speechStarted && !stoppingSignalled && now - lastSpeechAtMs >= (SILENCE_AFTER_SPEECH_MS / 2)) {
                        callbacks.onStopping("possible_silence_after_speech")
                        stoppingSignalled = true
                    }
                }
            } finally {
                originalPriority?.let { priority -> runCatching { Process.setThreadPriority(priority) } }
            }
            if (cancelRequested) throw IllegalStateException("Sherpa offline speech cancelled.")
            if (!stoppingSignalled) callbacks.onStopping(if (stopRequested) "user_stop" else "capture_complete")

            val audio = pcm.toByteArray()
            diagnostics.record(diagnosticSessionId, "sherpa_audio_capture_complete", mapOf("bytes" to audio.size, "audioDurationMs" to pcmDurationMs(audio.size.toLong()), "speechStarted" to speechStarted, "firstSpeechAtMs" to firstSpeechAtMs.takeIf { it > 0 }, "lastSpeechAtMs" to lastSpeechAtMs.takeIf { it > 0 }, "model" to model.name))
            val modelLoadStartedAt = System.currentTimeMillis()
            diagnostics.record(diagnosticSessionId, "sherpa_model_loading", mapOf("language" to languageTag, "model" to model.name, "storage" to model.storage.diagnosticName))
            val recognizer = loadRecognizer(diagnosticSessionId)
            diagnostics.record(diagnosticSessionId, "sherpa_model_ready", mapOf("model" to model.name, "storage" to model.storage.diagnosticName, "loadDurationMs" to (System.currentTimeMillis() - modelLoadStartedAt)))
            val decoded = decode(recognizer, audio)
            diagnostics.record(diagnosticSessionId, "sherpa_final", mapOf("provider" to providerLabel, "model" to model.name, "transcriptLength" to decoded.length, "transcript" to decoded.take(MAX_TRANSCRIPT_DIAGNOSTIC_CHARS), "audioDurationMs" to pcmDurationMs(audio.size.toLong())))
            decoded
        } finally {
            activeRecorder = null
            activeDiagnosticSessionId = null
            activeStartedAtMs = 0L
            activeCapturedBytes = 0L
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    override fun stopCurrent(): Boolean {
        val recorder = activeRecorder ?: return false
        stopRequested = true
        diagnostics.record(activeDiagnosticSessionId, "sherpa_stop_requested", mapOf("recordingAgeMs" to activeRecordingAgeMs(), "capturedBytes" to activeCapturedBytes, "audioDurationMs" to pcmDurationMs(activeCapturedBytes), "model" to model.name))
        runCatching { recorder.stop() }
        return true
    }

    override fun cancelCurrent(): Boolean {
        val recorder = activeRecorder ?: return false
        cancelRequested = true
        stopRequested = true
        diagnostics.record(activeDiagnosticSessionId, "sherpa_cancel_requested", mapOf("recordingAgeMs" to activeRecordingAgeMs(), "capturedBytes" to activeCapturedBytes, "audioDurationMs" to pcmDurationMs(activeCapturedBytes), "model" to model.name))
        runCatching { recorder.stop() }
        return true
    }

    private fun loadRecognizer(diagnosticSessionId: String?): OfflineRecognizer = synchronized(recognizerLock) {
        cachedRecognizer ?: createRecognizer(resolveModel(diagnosticSessionId)).also { cachedRecognizer = it }
    }

    private fun createRecognizer(resolvedModel: ResolvedModel): OfflineRecognizer {
        val config = recognizerConfig(resolvedModel.pathPrefix)
        return when (resolvedModel.source) {
            ModelSource.ASSET -> OfflineRecognizer(context.assets, config)
            ModelSource.FILE -> OfflineRecognizer(null, config)
        }
    }

    private fun resolveModel(diagnosticSessionId: String?): ResolvedModel = when (val storage = model.storage) {
        is ModelStorage.Asset -> {
            require(assetExists(storage.assetDir)) { "Sherpa model asset '${storage.assetDir}' is missing." }
            require(model.requiredFiles.all { assetExists("${storage.assetDir}/$it") }) { "Sherpa model asset '${storage.assetDir}' is incomplete." }
            ResolvedModel(pathPrefix = storage.assetDir, source = ModelSource.ASSET)
        }
        is ModelStorage.Download -> {
            val dir = ensureDownloadedModel(storage, diagnosticSessionId)
            ResolvedModel(pathPrefix = dir.absolutePath, source = ModelSource.FILE)
        }
    }

    private fun recognizerConfig(pathPrefix: String): OfflineRecognizerConfig = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = model.modelConfig(pathPrefix),
    )

    private fun decode(recognizer: OfflineRecognizer, pcm: ByteArray): String {
        val samples = pcm16ToFloat(pcm)
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "Could not initialize microphone buffer for Sherpa offline speech." }
        val bufferSize = maxOf(minBuffer, BUFFER_BYTES * 2)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        }
    }

    private fun ensureDownloadedModel(storage: ModelStorage.Download, diagnosticSessionId: String?): File {
        val target = File(context.filesDir, "sherpa/models/${model.name}")
        val marker = File(target, READY_MARKER)
        if (marker.isFile && model.requiredFiles.all { File(target, it).isFile }) return target

        val startedAt = System.currentTimeMillis()
        diagnostics.record(diagnosticSessionId, "sherpa_dynamic_model_download_started", mapOf("model" to model.name, "url" to storage.url, "target" to target.absolutePath))
        val archive = downloadArchive(storage, diagnosticSessionId)
        val temp = File(context.filesDir, "sherpa/models/${model.name}.tmp-${System.currentTimeMillis()}")
        try {
            extractTarBz2(archive, temp)
            val extractedRoot = File(temp, model.name).takeIf { it.isDirectory } ?: temp
            require(model.requiredFiles.all { File(extractedRoot, it).isFile }) { "Downloaded Sherpa model '${model.name}' is incomplete." }
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            extractedRoot.copyRecursively(target, overwrite = true)
            marker.writeText(storage.sha256)
            diagnostics.record(diagnosticSessionId, "sherpa_dynamic_model_ready", mapOf("model" to model.name, "target" to target.absolutePath, "durationMs" to (System.currentTimeMillis() - startedAt)))
            return target
        } finally {
            temp.deleteRecursively()
            archive.delete()
        }
    }

    private fun downloadArchive(storage: ModelStorage.Download, diagnosticSessionId: String?): File {
        val cacheDir = File(context.cacheDir, "sherpa-downloads").apply { mkdirs() }
        val archive = File(cacheDir, storage.archiveName)
        val temp = File(cacheDir, "${storage.archiveName}.tmp")
        temp.delete()

        val request = Request.Builder().url(storage.url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Could not download ${model.name}: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Could not download ${model.name}: empty response body")
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytes += read.toLong()
                    }
                }
            }
            val actualSha256 = digest.digest().toHexString()
            diagnostics.record(diagnosticSessionId, "sherpa_dynamic_model_download_finished", mapOf("model" to model.name, "bytes" to bytes, "expectedBytes" to body.contentLength().takeIf { it >= 0 }, "sha256" to actualSha256))
            if (!actualSha256.equals(storage.sha256, ignoreCase = true)) {
                temp.delete()
                throw IOException("Downloaded ${model.name} checksum mismatch.")
            }
        }

        archive.delete()
        require(temp.renameTo(archive)) { "Could not stage downloaded Sherpa model archive." }
        return archive
    }

    private fun extractTarBz2(archive: File, targetDir: File) {
        targetDir.deleteRecursively()
        targetDir.mkdirs()
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val output = safeOutputFile(targetDir, entry.name)
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    private fun safeOutputFile(root: File, entryName: String): File {
        val rootPath = root.canonicalPath + File.separator
        val output = File(root, entryName).canonicalFile
        require(output.path.startsWith(rootPath)) { "Unsafe path in Sherpa model archive: $entryName" }
        return output
    }

    private fun assetExists(path: String): Boolean {
        val children = context.assets.list(path).orEmpty()
        if (children.isNotEmpty()) return true
        return runCatching { context.assets.open(path).close() }.isSuccess
    }

    private fun activeRecordingAgeMs(): Long {
        val started = activeStartedAtMs
        return if (started == 0L) 0L else SystemClock.elapsedRealtime() - started
    }

    data class ModelSpec(
        val name: String,
        val storage: ModelStorage,
        val kind: Kind
    ) {
        enum class Kind { PARAKEET_TRANSDUCER, MOONSHINE }

        val requiredFiles: List<String>
            get() = when (kind) {
                Kind.PARAKEET_TRANSDUCER -> listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
                Kind.MOONSHINE -> listOf("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt")
            }

        fun modelConfig(pathPrefix: String): OfflineModelConfig = when (kind) {
            Kind.PARAKEET_TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$pathPrefix/encoder.int8.onnx",
                    decoder = "$pathPrefix/decoder.int8.onnx",
                    joiner = "$pathPrefix/joiner.int8.onnx"
                ),
                tokens = "$pathPrefix/tokens.txt",
                modelType = "nemo_transducer",
                numThreads = SHERPA_THREADS,
            )
            Kind.MOONSHINE -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = "$pathPrefix/preprocess.onnx",
                    encoder = "$pathPrefix/encode.int8.onnx",
                    uncachedDecoder = "$pathPrefix/uncached_decode.int8.onnx",
                    cachedDecoder = "$pathPrefix/cached_decode.int8.onnx"
                ),
                tokens = "$pathPrefix/tokens.txt",
                numThreads = SHERPA_THREADS,
            )
        }

        companion object {
            fun parakeetDownload(): ModelSpec = ModelSpec(
                name = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming",
                storage = ModelStorage.Download(
                    archiveName = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming.tar.bz2",
                    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming.tar.bz2",
                    sha256 = "99f63605b3a85a54c250c0869670a687b7d6598a47bf2421515e1f839a76e150"
                ),
                kind = Kind.PARAKEET_TRANSDUCER
            )

            fun parakeetAsset(): ModelSpec = ModelSpec(
                name = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming",
                storage = ModelStorage.Asset("sherpa/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming"),
                kind = Kind.PARAKEET_TRANSDUCER
            )

            fun moonshineDownload(): ModelSpec = ModelSpec(
                name = "sherpa-onnx-moonshine-base-en-int8",
                storage = ModelStorage.Download(
                    archiveName = "sherpa-onnx-moonshine-base-en-int8.tar.bz2",
                    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-base-en-int8.tar.bz2",
                    sha256 = "21870cecaa2e44e4e2bf63e02d1072bed183ccd10284871353bd9d24dad14e5e"
                ),
                kind = Kind.MOONSHINE
            )
        }
    }

    sealed class ModelStorage(val diagnosticName: String) {
        data class Asset(val assetDir: String) : ModelStorage("asset")
        data class Download(val archiveName: String, val url: String, val sha256: String) : ModelStorage("download")
    }

    private data class ResolvedModel(val pathPrefix: String, val source: ModelSource)

    private enum class ModelSource { ASSET, FILE }

    private companion object {
        val sharedHttpClient = OkHttpClient()
        const val SAMPLE_RATE = 16_000
        const val BUFFER_BYTES = 3200
        const val DOWNLOAD_BUFFER_BYTES = 128 * 1024
        const val MIN_STOP_DURATION_MS = 700L
        const val INITIAL_SILENCE_MS = 8_000L
        const val SILENCE_AFTER_SPEECH_MS = 1_200L
        const val SPEECH_RMS_THRESHOLD = 450.0
        const val MAX_TRANSCRIPT_DIAGNOSTIC_CHARS = 160
        const val READY_MARKER = ".droidlm-ready"
        const val SHERPA_THREADS = 4
    }
}

private fun pcm16ToFloat(pcm: ByteArray): FloatArray {
    val sampleCount = pcm.size / 2
    val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(sampleCount) { buffer.short / 32768.0f }
}

private fun pcmRms(buffer: ByteArray, length: Int): Double {
    if (length < 2) return 0.0
    var sum = 0.0
    var samples = 0
    var offset = 0
    while (offset + 1 < length) {
        val sample = ((buffer[offset + 1].toInt() shl 8) or (buffer[offset].toInt() and 0xff)).toShort().toDouble()
        sum += sample * sample
        samples += 1
        offset += 2
    }
    return if (samples == 0) 0.0 else sqrt(sum / samples)
}

private fun pcmDurationMs(bytes: Long): Long = bytes * 1000L / (16_000L * 2L)

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
