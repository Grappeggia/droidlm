package ai.droidlm.voice

import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.settings.SettingsRepository
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class CommandRecorder(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val logs: ActionLogRepository
) {
    @Volatile private var activeRecorder: MediaRecorder? = null

    suspend fun recordCommand(maxDurationMs: Long = 12_000): RecordedCommand = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("Microphone permission is missing")
        }
        val file = File.createTempFile("droidlm-command-", ".m4a", context.cacheDir)
        val recorder = createMediaRecorder()
        val startedAt = System.currentTimeMillis()
        activeRecorder = recorder
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setAudioSamplingRate(16_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            logs.log(ActionLogType.RECORDING_STARTED, "Recording command audio")
            waitForSilenceOrMaxDuration(recorder, startedAt, maxDurationMs)
            stopRecorder(recorder)
            val durationMs = System.currentTimeMillis() - startedAt
            logs.log(ActionLogType.RECORDING_STOPPED, "Recording stopped after ${durationMs}ms")
            if (durationMs < 400 || file.length() < 512) {
                throw IllegalStateException("Audio clip was too short")
            }
            RecordedCommand(file, durationMs, "audio/mp4")
        } catch (error: Throwable) {
            if (!settingsRepository.settings.first().debugAudioRetention) file.delete()
            throw error
        } finally {
            activeRecorder = null
            runCatching { recorder.release() }
        }
    }

    fun cancelCurrent() {
        activeRecorder?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
        }
        activeRecorder = null
        logs.log(ActionLogType.CANCELLED, "Command recording cancelled")
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private suspend fun waitForSilenceOrMaxDuration(
        recorder: MediaRecorder,
        startedAt: Long,
        maxDurationMs: Long,
        minDurationMs: Long = 900,
        silenceRequiredMs: Long = 1_400,
        silenceThreshold: Int = 600
    ) {
        var silenceStartedAt: Long? = null
        while (System.currentTimeMillis() - startedAt < maxDurationMs) {
            delay(200)
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < minDurationMs) continue
            val amplitude = runCatching { recorder.maxAmplitude }.getOrDefault(silenceThreshold + 1)
            if (amplitude < silenceThreshold) {
                val since = silenceStartedAt ?: System.currentTimeMillis().also { silenceStartedAt = it }
                if (System.currentTimeMillis() - since >= silenceRequiredMs) return
            } else {
                silenceStartedAt = null
            }
        }
    }

    private fun stopRecorder(recorder: MediaRecorder) {
        runCatching { recorder.stop() }
            .onFailure { throw IllegalStateException("Could not stop microphone recording: ${it.message}", it) }
    }
}
